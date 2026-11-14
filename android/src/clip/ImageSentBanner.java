package clip;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

final class ImageSentBanner {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable hide = this::dismiss;
    private TextView banner;

    ImageSentBanner(Context context) { this.context = context; }

    boolean show(String text) {
        if (!Settings.canDrawOverlays(context)) return false;

        if (banner == null) {
            TextView view = new TextView(context);
            view.setText(text);
            view.setTextColor(0xff191919);
            view.setTextSize(15);
            view.setGravity(Gravity.CENTER);
            view.setPadding(dp(24), dp(13), dp(24), dp(13));
            view.setImportantForAccessibility(TextView.IMPORTANT_FOR_ACCESSIBILITY_NO);
            GradientDrawable background = new GradientDrawable();
            background.setColor(0xf2e5e5e5);
            background.setCornerRadius(dp(28));
            view.setBackground(background);
            WindowManager.LayoutParams layout = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            // Keep the banner click-through on Android 12 and later.
            layout.alpha = 0.8f;
            layout.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            layout.y = dp(64);
            try {
                context.getSystemService(WindowManager.class).addView(view, layout);
                banner = view;
            } catch (WindowManager.BadTokenException | WindowManager.InvalidDisplayException | SecurityException error) {
                android.util.Log.w("ClipDelivery", "Could not display image confirmation banner", error);
                return false;
            }
        }

        main.removeCallbacks(hide);
        main.postDelayed(hide, 2200);
        return true;
    }

    void dismiss() {
        main.removeCallbacks(hide);
        if (banner == null) return;
        try { context.getSystemService(WindowManager.class).removeViewImmediate(banner); }
        catch (IllegalArgumentException ignored) { }
        banner = null;
    }

    private int dp(int size) {
        return Math.round(size * context.getResources().getDisplayMetrics().density);
    }
}
