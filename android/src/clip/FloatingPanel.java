package clip;

import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

final class FloatingPanel {
    private final SyncService service;
    private final WindowManager manager;
    private final WindowManager.LayoutParams params;
    private View window;
    private TextView state;
    private boolean ball;
    private int width, height;
    private int x, y;

    FloatingPanel(SyncService service) {
        this.service = service;
        manager = service.getSystemService(WindowManager.class);
        params = new WindowManager.LayoutParams(0, 0, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        width = dp(310);
        height = dp(174);
        x = dp(18);
        y = dp(116);
    }

    void show() {
        if (window != null) return;
        View next = ball ? ballView() : expandedView();
        Point limit = area();
        params.width = Math.min(ball ? dp(54) : width, limit.x);
        params.height = Math.min(ball ? dp(54) : height, limit.y);
        params.x = clamp(x, 0, limit.x - params.width);
        params.y = clamp(y, 0, limit.y - params.height);
        next.setAlpha(0);
        try {
            manager.addView(next, params);
            window = next;
            next.animate().alpha(1).setDuration(125).start();
        } catch (WindowManager.BadTokenException | SecurityException denied) { state = null; }
    }

    void hide() {
        View previous = window;
        window = null;
        state = null;
        if (previous != null) manager.removeViewImmediate(previous);
    }

    void updateStatus(String text) {
        if (state != null) state.setText(text);
    }

    private View ballView() {
        TextView button = Style.label(service, "剪", 18, Style.WHITE, true);
        button.setContentDescription("展开剪贴板");
        button.setBackground(Style.surface(service, Style.BLUE, 28));
        button.setGravity(Gravity.CENTER);
        button.setOnClickListener(view -> switchMode(false));
        gesture(button, false, button::performClick);
        return button;
    }

    private View expandedView() {
        LinearLayout root = Style.column(service);
        root.setBackground(Style.surface(service, Style.BACKGROUND, 22));
        root.setClipToOutline(true);
        root.setPadding(dp(13), dp(7), dp(13), dp(4));

        LinearLayout header = new LinearLayout(service);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Style.label(service, "剪贴板", 17, Style.INK, true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1));
        gesture(title, false, null);
        TextView collapse = Style.action(service, "−", false, () -> switchMode(true));
        collapse.setContentDescription("缩成小球");
        header.addView(collapse, new LinearLayout.LayoutParams(dp(45), dp(40)));
        root.addView(header);

        state = Style.label(service, service.status(), 13, Style.MUTED, false);
        root.addView(state, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout actions = new LinearLayout(service);
        addAction(actions, "发文字", true, () -> open("text"));
        addAction(actions, "发图片", false, () -> open("image"));
        addAction(actions, "设置", false, () -> open(""));
        root.addView(actions, new LinearLayout.LayoutParams(-1, dp(43)));

        TextView resize = Style.label(service, "◢", 15, Style.MUTED, false);
        resize.setGravity(Gravity.END | Gravity.BOTTOM);
        resize.setContentDescription("拖动调整浮窗大小");
        LinearLayout.LayoutParams resizeParams = new LinearLayout.LayoutParams(dp(40), dp(25));
        resizeParams.gravity = Gravity.END;
        root.addView(resize, resizeParams);
        gesture(resize, true, null);
        return root;
    }

    private void addAction(LinearLayout row, String title, boolean primary, Runnable click) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        if (row.getChildCount() > 0) lp.leftMargin = dp(6);
        row.addView(Style.action(service, title, primary, click), lp);
    }

    private void open(String action) {
        Intent intent = new Intent(service, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("mode", action);
        service.startActivity(intent);
    }

    private void switchMode(boolean compact) {
        ball = compact;
        hide();
        show();
    }

    private void gesture(View view, boolean resize, Runnable tap) {
        view.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int baseX, baseY, baseW, baseH;
            boolean moved;

            @Override public boolean onTouch(View target, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        baseX = params.x;
                        baseY = params.y;
                        baseW = params.width;
                        baseH = params.height;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(event.getRawX() - downX);
                        int dy = Math.round(event.getRawY() - downY);
                        int slop = ViewConfiguration.get(service).getScaledTouchSlop();
                        moved |= Math.abs(dx) >= slop || Math.abs(dy) >= slop;
                        if (moved && window != null) {
                            Point limit = area();
                            if (resize) {
                                params.width = width = clamp(baseW + dx, dp(244), limit.x - params.x);
                                params.height = height = clamp(baseH + dy, dp(154), limit.y - params.y);
                            } else {
                                params.x = x = clamp(baseX + dx, 0, limit.x - params.width);
                                params.y = y = clamp(baseY + dy, 0, limit.y - params.height);
                            }
                            manager.updateViewLayout(window, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved && tap != null) tap.run();
                        return true;
                    case MotionEvent.ACTION_CANCEL: return true;
                    default: return false;
                }
            }
        });
    }

    private Point area() {
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowMetrics metrics = manager.getCurrentWindowMetrics();
            android.graphics.Rect bounds = metrics.getBounds();
            android.graphics.Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            return new Point(Math.max(1, bounds.width() - insets.left - insets.right),
                    Math.max(1, bounds.height() - insets.top - insets.bottom));
        }
        Point size = new Point();
        manager.getDefaultDisplay().getSize(size);
        return size;
    }

    private int dp(int value) { return Style.dp(service, value); }
    private static int clamp(int value, int min, int max) { return Math.min(Math.max(min, value), Math.max(0, max)); }
}
