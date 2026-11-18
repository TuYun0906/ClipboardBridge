package clip;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

final class ScreenshotBubble {
    private final SyncService service;
    private final Handler main;
    private final WindowManager windows;
    private TextView button;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private Object request;
    private Runnable timeout;
    private int feedbackVersion;
    private long lastAuthorizationTap = -1000;

    ScreenshotBubble(SyncService service, Handler main) {
        this.service = service;
        this.main = main;
        windows = service.getSystemService(WindowManager.class);
    }

    boolean isVisible() { return button != null && Settings.canDrawOverlays(service); }

    void enable(boolean enabled) {
        if (!enabled || !Settings.canDrawOverlays(service)) {
            close();
            return;
        }
        if (button != null) return;
        TextView view = Style.action(service, "截屏", true, this::tap);
        view.setElevation(Style.dp(service, 5));
        WindowManager.LayoutParams layout = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, Style.dp(service, 44),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.RIGHT;
        layout.x = Style.dp(service, 10);
        layout.y = Style.dp(service, 112);
        try {
            windows.addView(view, layout);
            button = view;
        } catch (WindowManager.BadTokenException | WindowManager.InvalidDisplayException | SecurityException error) {
            android.util.Log.w("ClipCapture", "Could not show screenshot button", error);
        }
    }

    void authorize(int resultCode, Intent data) {
        if (!isVisible() || projection != null) return;
        try {
            service.setCaptureForeground(true);
            MediaProjectionManager manager = service.getSystemService(MediaProjectionManager.class);
            MediaProjection session = manager.getMediaProjection(resultCode, data);
            if (session == null) throw new IllegalStateException("Projection permission unavailable");
            projection = session;
            session.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    if (projection == session) stopProjection();
                }
            }, main);
            DisplayMetrics metrics = dimensions();
            display = session.createVirtualDisplay("ClipboardBridge", metrics.widthPixels, metrics.heightPixels,
                    metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, main);
            if (display == null) throw new IllegalStateException("Projection display unavailable");
        } catch (RuntimeException error) {
            android.util.Log.w("ClipCapture", "Could not start capture", error);
            stopProjection();
            feedback("授权失败", false);
        }
    }

    private void tap() {
        if (request != null || !isVisible()) return;
        if (display == null) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastAuthorizationTap < 1000) return;
            lastAuthorizationTap = now;
            Intent intent = new Intent(service, MainActivity.class).putExtra("mode", "capture-permission")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP |
                            Intent.FLAG_ACTIVITY_SINGLE_TOP);
            service.startActivity(intent);
            return;
        }
        if (!service.capturePeerConnected()) {
            feedback("未连接", false);
            return;
        }
        Object id = new Object();
        request = id;
        feedbackVersion++;
        button.setText("发送中");
        button.setVisibility(View.INVISIBLE);
        timeout = () -> finish(id, "发送超时", false);
        main.postDelayed(timeout, 30000);
        // Give the compositor time to remove the overlay before attaching a fresh reader.
        main.postDelayed(() -> capture(id), 180);
    }

    private DisplayMetrics dimensions() {
        DisplayMetrics metrics = new DisplayMetrics();
        windows.getDefaultDisplay().getRealMetrics(metrics);
        double scale = Math.min(1, 2560.0 / Math.max(metrics.widthPixels, metrics.heightPixels));
        metrics.widthPixels = Math.max(1, (int)Math.round(metrics.widthPixels * scale));
        metrics.heightPixels = Math.max(1, (int)Math.round(metrics.heightPixels * scale));
        return metrics;
    }

    private void capture(Object id) {
        if (request != id || display == null || !isVisible()) return;
        try {
            DisplayMetrics metrics = dimensions();
            int width = metrics.widthPixels, height = metrics.heightPixels;
            display.resize(width, height, metrics.densityDpi);
            ImageReader source = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            reader = source;
            source.setOnImageAvailableListener(available -> onImage(id, available), main);
            display.setSurface(source.getSurface());
        } catch (RuntimeException error) {
            android.util.Log.w("ClipCapture", "Could not capture screen", error);
            finish(id, "截屏失败", false);
        }
    }

    private void onImage(Object id, ImageReader source) {
        if (request != id || reader != source) return;
        Bitmap frame = null;
        try (Image image = source.acquireLatestImage()) {
            if (image == null) return;
            Image.Plane plane = image.getPlanes()[0];
            int strideWidth = plane.getRowStride() / plane.getPixelStride();
            Bitmap padded = Bitmap.createBitmap(strideWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
            try {
                int frameBytes = padded.getByteCount();
                java.nio.ByteBuffer pixels = plane.getBuffer();
                if (pixels.remaining() < frameBytes) {
                    java.nio.ByteBuffer complete = java.nio.ByteBuffer.allocateDirect(frameBytes);
                    complete.put(pixels);
                    complete.rewind();
                    pixels = complete;
                }
                padded.copyPixelsFromBuffer(pixels);
                frame = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
            } finally {
                if (frame != padded) padded.recycle();
            }
        } catch (RuntimeException error) {
            android.util.Log.w("ClipCapture", "Could not read captured image", error);
            if (frame != null) frame.recycle();
            finish(id, "截屏失败", false);
            return;
        }
        releaseReader();
        button.setVisibility(View.VISIBLE);
        service.sendCapture(frame, id);
    }

    boolean isSending(Object id) { return request == id; }

    void delivered(Object id) {
        if (id != null) finish(id, "发送成功", true);
        else if (request == null) feedback("发送成功", true);
    }

    void sendFailed(Object id) { finish(id, "发送失败", false); }

    private void finish(Object id, String message, boolean success) {
        if (request != id) return;
        main.removeCallbacks(timeout);
        timeout = null;
        request = null;
        releaseReader();
        service.finishCapture(id, success);
        feedback(message, success);
    }

    private void feedback(String message, boolean success) {
        if (button == null) return;
        TextView view = button;
        view.setVisibility(View.VISIBLE);
        view.setText(message);
        if (success) {
            ObjectAnimator.ofFloat(view, View.SCALE_X, 0.84f, 1.10f, 1f).setDuration(270).start();
            ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.84f, 1.10f, 1f).setDuration(270).start();
        }
        int version = ++feedbackVersion;
        main.postDelayed(() -> {
            if (version == feedbackVersion && button == view && request == null) view.setText("截屏");
        }, success ? 600 : 1500);
    }

    private void releaseReader() {
        if (reader == null) return;
        try {
            if (display != null) display.setSurface(null);
        } catch (IllegalStateException ignored) {
            // The system may already have stopped the projection.
        }
        reader.close();
        reader = null;
    }

    private void stopProjection() {
        releaseReader();
        VirtualDisplay oldDisplay = display;
        MediaProjection oldProjection = projection;
        display = null;
        projection = null;
        if (oldDisplay != null) oldDisplay.release();
        if (oldProjection != null) oldProjection.stop();
        service.endCaptureForeground();
        if (request != null) finish(request, "截屏已停止", false);
    }

    void close() {
        if (display != null || projection != null) stopProjection();
        if (timeout != null) main.removeCallbacks(timeout);
        timeout = null;
        if (request != null) service.finishCapture(request, false);
        request = null;
        if (button != null) {
            windows.removeViewImmediate(button);
            button = null;
        }
    }
}
