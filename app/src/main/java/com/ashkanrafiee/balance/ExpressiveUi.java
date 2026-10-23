package com.ashkanrafiee.balance;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Balance's hand-built expressive design system.
 *
 * <p>These are intentionally small View primitives, not a replacement widget framework. They keep
 * shape, typography, touch targets and tonal surfaces consistent while leaving the app dependency
 * free. Screen-specific composition belongs to the owning activity; this class only supplies the
 * visual grammar shared by those compositions.
 */
final class ExpressiveUi {
    static final float RADIUS_PILL = 50f;
    static final float RADIUS_CONTROL = 18f;
    static final float RADIUS_CARD = 28f;
    static final float RADIUS_HERO = 36f;
    static final int MIN_TOUCH_DP = 48;

    private ExpressiveUi() { }

    static int color(Context context, int res) {
        return context.getResources().getColor(res, context.getTheme());
    }

    static int color(View view, int res) { return color(view.getContext(), res); }

    static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + .5f);
    }

    static int dp(View view, float value) { return dp(view.getContext(), value); }

    static GradientDrawable shape(Context context, int fill, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radius));
        return drawable;
    }

    static GradientDrawable shape(View view, int fill, float radius) {
        return shape(view.getContext(), fill, radius);
    }

    static GradientDrawable outline(Context context, int fill, float radius, int stroke) {
        GradientDrawable drawable = shape(context, fill, radius);
        drawable.setStroke(dp(context, 1), stroke);
        return drawable;
    }

    static GradientDrawable outline(View view, int fill, float radius, int stroke) {
        return outline(view.getContext(), fill, radius, stroke);
    }

    static RippleDrawable ripple(Context context, Drawable content) {
        int pressed = ThemeHelper.isDark(context) ? 0x40FFFFFF : 0x30000000;
        return new RippleDrawable(ColorStateList.valueOf(pressed), content, content);
    }

    static RippleDrawable ripple(View view, Drawable content) {
        return ripple(view.getContext(), content);
    }

    static TextView label(Context context, CharSequence value, float size, int textColor) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(textColor);
        view.setIncludeFontPadding(false);
        view.setTypeface(Fonts.text(context));
        return view;
    }

    static TextView label(View view, CharSequence value, float size, int textColor) {
        return label(view.getContext(), value, size, textColor);
    }

    static TextView heading(Context context, CharSequence value, float size) {
        TextView view = label(context, value, size, color(context, R.color.fg));
        view.setTypeface(Fonts.medium(context));
        return view;
    }

    static TextView heading(View view, CharSequence value, float size) {
        return heading(view.getContext(), value, size);
    }

    static TextView button(Context context, CharSequence value, boolean filled) {
        int primary = color(context, R.color.expressive_primary);
        int onPrimary = color(context, R.color.expressive_on_primary);
        int surface = color(context, R.color.expressive_surface_container_high);
        int fg = color(context, R.color.fg);
        TextView view = label(context, value, 14, filled ? onPrimary : fg);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(context, MIN_TOUCH_DP));
        view.setPadding(dp(context, 18), dp(context, 11), dp(context, 18), dp(context, 11));
        view.setTypeface(Fonts.medium(context));
        view.setAllCaps(false);
        view.setBackground(ripple(context, shape(context, filled ? primary : surface,
            RADIUS_CONTROL)));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    static TextView button(View view, CharSequence value, boolean filled) {
        return button(view.getContext(), value, filled);
    }

    static ImageButton iconButton(Context context, Drawable icon, String description,
            boolean selected) {
        ImageButton button = new ImageButton(context);
        button.setContentDescription(description);
        button.setMinimumWidth(dp(context, MIN_TOUCH_DP));
        button.setMinimumHeight(dp(context, MIN_TOUCH_DP));
        button.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        int container = selected
            ? color(context, R.color.expressive_primary_container)
            : color(context, R.color.expressive_surface_container_high);
        int tint = selected
            ? color(context, R.color.expressive_on_primary_container)
            : color(context, R.color.expressive_on_surface_variant);
        if (icon != null) {
            Drawable copy = icon.mutate();
            copy.setTint(tint);
            button.setImageDrawable(copy);
        }
        button.setColorFilter(null);
        button.setBackground(ripple(context, shape(context, container, RADIUS_PILL)));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    static ImageButton iconButton(View view, Drawable icon, String description,
            boolean selected) {
        return iconButton(view.getContext(), icon, description, selected);
    }

    static LinearLayout column(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    static LinearLayout column(View view) { return column(view.getContext()); }

    static LinearLayout row(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    static LinearLayout row(View view) { return row(view.getContext()); }

    static LinearLayout card(Context context, int surface, float radius, float padding) {
        LinearLayout view = column(context);
        int p = dp(context, padding);
        view.setPadding(p, p, p, p);
        view.setBackground(shape(context, surface, radius));
        return view;
    }

    static LinearLayout card(View view, int surface, float radius, float padding) {
        return card(view.getContext(), surface, radius, padding);
    }

    static LinearLayout.LayoutParams widthMatch(Context context, float top, float bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(context, top);
        params.bottomMargin = dp(context, bottom);
        return params;
    }

    static LinearLayout.LayoutParams widthMatch(View view, float top, float bottom) {
        return widthMatch(view.getContext(), top, bottom);
    }

    static void setTextDirection(View view, boolean rtl) {
        view.setTextDirection(rtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
        view.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
    }
}
