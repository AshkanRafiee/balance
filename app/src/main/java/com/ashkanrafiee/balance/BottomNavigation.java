package com.ashkanrafiee.balance;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.IntConsumer;

/** A compact, reusable bottom navigation bar for the four top-level Balance destinations. */
public final class BottomNavigation extends LinearLayout {
    public static final int HOME = 0;
    public static final int PAYMENTS = 1;
    public static final int SAVINGS = 2;
    public static final int SETTINGS = 3;

    private static final int ITEM_COUNT = 4;
    private final NavigationItem[] items = new NavigationItem[ITEM_COUNT];
    private final IntConsumer onSelect;
    private final int fg;
    private final int muted;
    private final int accent;
    private final int selectedBackground;
    private int selected;

    public BottomNavigation(Context context, int selected, IntConsumer onSelect) {
        super(context);
        this.onSelect = onSelect;
        fg = color(R.color.fg);
        muted = color(R.color.muted);
        accent = color(R.color.accent);
        selectedBackground = color(R.color.hero);
        this.selected = normalize(selected);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(8), dp(5), dp(8), dp(5));
        setBackgroundColor(color(R.color.panel));
        setElevation(dp(3));

        addItem(HOME, R.string.nav_balance, "Balance");
        addItem(PAYMENTS, R.string.nav_payments, "Payments");
        addItem(SAVINGS, R.string.nav_savings, "Savings");
        addItem(SETTINGS, R.string.nav_settings, "Settings");
        setSelectedTab(this.selected);
    }

    private void addItem(final int tab, int labelRes, String fallbackDescription) {
        NavigationItem item = new NavigationItem(getContext());
        item.setOrientation(VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(2), dp(3), dp(2), dp(3));
        item.setMinimumWidth(dp(48));
        item.setMinimumHeight(dp(58));
        item.setClickable(true);
        item.setFocusable(true);
        String label = getContext().getString(labelRes);
        item.setContentDescription(label.isEmpty() ? fallbackDescription : label);

        NavigationIcon icon = new NavigationIcon(getContext(), tab);
        item.icon = icon;
        item.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));

        TextView text = new TextView(getContext());
        item.label = text;
        text.setText(label);
        text.setTextSize(12);
        text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text.setGravity(Gravity.CENTER);
        text.setIncludeFontPadding(true);
        item.addView(text, new LinearLayout.LayoutParams(-1, -2));

        item.setOnClickListener(v -> {
            setSelectedTab(tab);
            if (onSelect != null) onSelect.accept(tab);
        });
        addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        items[tab] = item;
    }

    /** Changes the highlighted destination without invoking the navigation callback. */
    public void setSelectedTab(int tab) {
        selected = normalize(tab);
        for (int i = 0; i < items.length; i++) {
            NavigationItem item = items[i];
            if (item == null) continue;
            boolean active = i == selected;
            item.setSelected(active);
            item.icon.setColor(active ? accent : muted);
            item.label.setTextColor(active ? accent : fg);
            item.setBackground(itemBackground(active));
        }
    }

    int selectedTab() {
        return selected;
    }

    private int normalize(int tab) {
        return tab >= HOME && tab <= SETTINGS ? tab : HOME;
    }

    private Drawable itemBackground(boolean active) {
        GradientDrawable base = new GradientDrawable();
        base.setColor(active ? selectedBackground : android.graphics.Color.TRANSPARENT);
        base.setCornerRadius(dp(16));
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            return new RippleDrawable(ColorStateList.valueOf(android.graphics.Color.argb(35, 0, 0, 0)),
                base, null);
        }
        return base;
    }

    private int color(int res) {
        return getResources().getColor(res, getContext().getTheme());
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    private static final class NavigationItem extends LinearLayout {
        NavigationIcon icon;
        TextView label;

        NavigationItem(Context context) {
            super(context);
        }
    }

    /** Draws small monochrome line icons without adding a dependency or a resource family. */
    private static final class NavigationIcon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int kind;
        private final float density;
        private int iconColor;

        NavigationIcon(Context context, int kind) {
            super(context);
            this.kind = kind;
            density = getResources().getDisplayMetrics().density;
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setColor(int color) {
            iconColor = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            float cx = width / 2f;
            float cy = height / 2f;
            float unit = Math.min(width, height) / 28f;
            paint.setColor(iconColor);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.1f * density);

            switch (kind) {
                case HOME:
                    Path roof = new Path();
                    roof.moveTo(cx - 9 * unit, cy - 1 * unit);
                    roof.lineTo(cx, cy - 9 * unit);
                    roof.lineTo(cx + 9 * unit, cy - 1 * unit);
                    canvas.drawPath(roof, paint);
                    canvas.drawRoundRect(new RectF(cx - 7 * unit, cy - 1 * unit,
                        cx + 7 * unit, cy + 9 * unit), 2 * unit, 2 * unit, paint);
                    canvas.drawLine(cx, cy + 9 * unit, cx, cy + 3 * unit, paint);
                    break;
                case PAYMENTS:
                    canvas.drawRoundRect(new RectF(cx - 10 * unit, cy - 7 * unit,
                        cx + 8 * unit, cy + 5 * unit), 2 * unit, 2 * unit, paint);
                    canvas.drawRoundRect(new RectF(cx - 7 * unit, cy - 3 * unit,
                        cx + 10 * unit, cy + 9 * unit), 2 * unit, 2 * unit, paint);
                    canvas.drawLine(cx - 4 * unit, cy + 2 * unit, cx + 4 * unit, cy + 2 * unit, paint);
                    break;
                case SAVINGS:
                    Path leaf = new Path();
                    leaf.moveTo(cx, cy + 9 * unit);
                    leaf.cubicTo(cx - 10 * unit, cy + 5 * unit, cx - 8 * unit, cy - 7 * unit,
                        cx + 8 * unit, cy - 9 * unit);
                    leaf.cubicTo(cx + 9 * unit, cy + 2 * unit, cx + 5 * unit, cy + 7 * unit,
                        cx, cy + 9 * unit);
                    canvas.drawPath(leaf, paint);
                    canvas.drawLine(cx, cy + 9 * unit, cx + 5 * unit, cy + 2 * unit, paint);
                    break;
                case SETTINGS:
                    canvas.drawCircle(cx, cy, 4 * unit, paint);
                    for (int i = 0; i < 8; i++) {
                        double angle = i * Math.PI / 4;
                        float inner = 7 * unit;
                        float outer = 10 * unit;
                        canvas.drawLine(cx + (float) Math.cos(angle) * inner,
                            cy + (float) Math.sin(angle) * inner,
                            cx + (float) Math.cos(angle) * outer,
                            cy + (float) Math.sin(angle) * outer, paint);
                    }
                    break;
                default:
                    break;
            }
        }
    }
}
