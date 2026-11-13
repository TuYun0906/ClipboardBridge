package clip;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Style {
    static final int INK = 0xff222936, MUTED = 0xff5e6a7c, BLUE = 0xff3368ea;
    static final int BACKGROUND = 0xfff6f7fa, WHITE = 0xffffffff, TINT = 0xffeaf0ff;

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable surface(Context context, int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radius));
        return shape;
    }

    static TextView label(Context context, String title, int size, int color, boolean strong) {
        TextView text = new TextView(context);
        text.setText(title);
        text.setTextColor(color);
        text.setTextSize(size);
        if (strong) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    static TextView action(Context context, String title, boolean primary, Runnable run) {
        TextView text = label(context, title, 14, primary ? WHITE : BLUE, true);
        text.setGravity(Gravity.CENTER);
        text.setMinHeight(dp(context, 43));
        int side = dp(context, 12);
        text.setPadding(side, 0, side, 0);
        text.setBackground(new RippleDrawable(ColorStateList.valueOf(0x283368ea),
                surface(context, primary ? BLUE : TINT, 13), null));
        text.setOnClickListener(view -> run.run());
        text.setFocusable(true);
        return text;
    }

    static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
}
