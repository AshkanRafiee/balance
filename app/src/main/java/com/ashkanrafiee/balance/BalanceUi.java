package com.ashkanrafiee.balance;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;

/**
 * Small, dependency-free visual system shared by Balance's programmatic screens.
 *
 * <p>This deliberately contains presentation primitives rather than product behavior. Keeping
 * shape and interaction feedback in one place makes the Java-built screens feel like one app while
 * retaining the app's no-runtime-dependency design. The palette itself lives in color resources so
 * light/dark mode, Persian resources and the widget continue to resolve through Android themes.
 */
final class BalanceUi {
    static final float SHAPE_SMALL = 12f;
    static final float SHAPE_CONTROL = 16f;
    static final float SHAPE_CARD = 24f;
    static final float SHAPE_HERO = 30f;

    private BalanceUi() { }

    static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + .5f);
    }

    static GradientDrawable rounded(Context context, int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radius));
        return drawable;
    }

    /** Adds Android's native pressed-state ripple without pulling in a widget library. */
    static RippleDrawable ripple(Context context, Drawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, content);
    }

    static int rippleColor(Context context) {
        return ThemeHelper.isDark(context) ? 0x38FFFFFF : 0x38000000;
    }

    static int onPrimary(Context context) {
        return context.getResources().getColor(R.color.on_primary, context.getTheme());
    }
}
