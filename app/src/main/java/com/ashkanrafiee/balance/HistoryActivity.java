package com.ashkanrafiee.balance;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shows the transaction history parsed from supported bank SMS: the net sum of transactions for
 * today, this Persian (Jalali) month and this Persian year, an all-time hero summary, plus a
 * year-by-year breakdown that drills down into months and expandable days, each carrying its own
 * deposit/withdrawal subtotals. Sums always reflect money <em>movements</em> (deposits minus
 * withdrawals), never remaining balances. All date boundaries follow the Persian calendar.
 *
 * <p>The screen kicks off a background history re-scan whenever it opens and re-renders on the
 * result, showing a spinning-indicator pill while a scan is in flight.
 */
public final class HistoryActivity extends Activity {
    private static final String MONTH_TAG = "history_month";
    private static final String DAY_TAG = "history_day";
    private static final String YEAR_TAG = "history_year";

    private int bg, card, muted, accent, fg, divider, purple, negativeColor, positiveColor;
    private int todayColor, monthColor, yearColor;
    private int heroTop, heroBottom, rail, openBg, chipBg;
    private int depBg, depFg, witBg, witFg, badgeFg, badgeBg;
    private final int[] bankColors = {
        Color.rgb(14, 165, 233), Color.rgb(139, 92, 246),
        Color.rgb(16, 185, 129), Color.rgb(245, 158, 11),
        Color.rgb(244, 63, 94), Color.rgb(20, 184, 166)
    };
    private LinearLayout body;
    private JalaliCalendar todayJalali, yesterdayJalali;

    /** The refresh glyph in the header, rotated while a history scan is in flight (or for a short
     *  beat after a tap, so a fast scan still reports an update visually). */
    private ImageView refreshView;
    private android.animation.ObjectAnimator refreshSpin;
    private final android.os.Handler refreshHandler = new android.os.Handler(Looper.getMainLooper());
    private final Runnable stopSpinRunnable = this::stopSpin;
    private static final long MIN_SPIN_MS = 1000;
    private long spinSince;

    // ====================================================================
    // Shared drawing helpers
    // ====================================================================

    int color(int res) {
        return getResources().getColor(res, getTheme());
    }

    int dp(float n) {
        return (int) (n * getResources().getDisplayMetrics().density + .5f);
    }

    boolean isRtl() {
        return getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    TextView text(String s, float size, int color) {
        return text(s, size, color, null);
    }

    TextView text(String s, float size, int color, Typeface tf) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        if (tf != null) v.setTypeface(tf);
        else v.setTypeface(null, Typeface.NORMAL);
        v.setIncludeFontPadding(false);
        return v;
    }

    TextView bold(String s, float size, int color) {
        TextView v = text(s, size, color);
        v.setTypeface(null, Typeface.BOLD);
        return v;
    }

    GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    GradientDrawable roundedStroke(int color, float radius, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        g.setStroke(dp(1), strokeColor);
        return g;
    }

    GradientDrawable heroGradient() {
        GradientDrawable g = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, new int[]{heroTop, heroBottom});
        g.setCornerRadius(dp(22));
        return g;
    }

    /** A touch-ripple that sits exactly over a rounded fill, so headers give tap feedback without
     *  leaking ripple color outside their corners. */
    RippleDrawable ripple(GradientDrawable bg) {
        int tint = (accent & 0x00FFFFFF) | 0x2E000000;
        return new RippleDrawable(ColorStateList.valueOf(tint), bg, bg);
    }

    LinearLayout.LayoutParams margin(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    /** Enables uniform auto-sizing between {@code min} and {@code max} sp (API 26+, our floor). */
    private void autoSize(TextView v, int min, int max) {
        v.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM);
        v.setAutoSizeTextTypeUniformWithConfiguration(
            min, max, 1, TypedValue.COMPLEX_UNIT_SP);
    }

    // ====================================================================
    // Activity lifecycle
    // ====================================================================

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            java.util.ArrayList<String> y = state.getStringArrayList(KEY_EXPANDED_YEARS);
            java.util.ArrayList<String> m = state.getStringArrayList(KEY_EXPANDED_MONTHS);
            java.util.ArrayList<String> d = state.getStringArrayList(KEY_EXPANDED_DAYS);
            if (y != null) expandedYears.addAll(y);
            if (m != null) expandedMonths.addAll(m);
            if (d != null) expandedDays.addAll(d);
            // Restored state already reflects the user's choices, so the current period must not be
            // force-expanded again by seedExpanded().
            expandedSeeded = !expandedYears.isEmpty() && !expandedMonths.isEmpty() && !expandedDays.isEmpty();
        }
        bg = color(R.color.bg);
        card = color(R.color.panel);
        muted = color(R.color.muted);
        accent = color(R.color.accent);
        fg = color(R.color.fg);
        divider = color(R.color.divider);
        purple = color(R.color.purple);
        positiveColor = color(R.color.accent);
        negativeColor = color(R.color.negative);
        todayColor = color(R.color.accent);
        monthColor = color(R.color.purple);
        yearColor = color(R.color.history_year);
        heroTop = color(R.color.history_hero_top);
        heroBottom = color(R.color.history_hero_bottom);
        rail = color(R.color.history_rail);
        openBg = color(R.color.history_open_bg);
        chipBg = color(R.color.history_chip_bg);
        depBg = color(R.color.history_dep_bg);
        depFg = color(R.color.history_dep_fg);
        witBg = color(R.color.history_wit_bg);
        witFg = color(R.color.history_wit_fg);
        badgeFg = color(R.color.history_badge_fg);
        badgeBg = color(R.color.history_badge_bg);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        refreshDates();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(20), dp(14), dp(20), dp(14));
        root.setOnApplyWindowInsetsListener((v, i) -> {
            int top = 0, bottom = 0;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets x = i.getInsets(android.view.WindowInsets.Type.systemBars());
                top = x.top;
                bottom = x.bottom;
            } else {
                top = i.getSystemWindowInsetTop();
                bottom = i.getSystemWindowInsetBottom();
            }
            v.setPadding(dp(20), top + dp(14), dp(20), bottom + dp(14));
            return i;
        });
        setContentView(root);

        root.addView(buildHeader(), margin(0, 0, 0, 14));
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -1));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        render();
    }

    /** Refreshes the cached "today" and "yesterday" Jalali dates once per render. */
    private void refreshDates() {
        Calendar c = Calendar.getInstance(Locale.getDefault());
        todayJalali = JalaliCalendar.fromGregorian(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        c.add(Calendar.DAY_OF_MONTH, -1);
        yesterdayJalali = JalaliCalendar.fromGregorian(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** The sticky top bar: back chevron, title, experimental badge and the spinning refresh glyph. */
    private LinearLayout buildHeader() {
        boolean rtl = isRtl();
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = text(rtl ? "›" : "‹", 24, fg);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(getString(R.string.history_back));
        back.setBackground(ripple(rounded(chipBg, 20)));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2);
        titleParams.setMarginStart(dp(10));
        TextView title = text(getString(R.string.history_title), 22, fg, MEDIUM);
        bar.addView(title, titleParams);

        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2);
        badgeParams.setMarginStart(dp(8));
        TextView badge = text(getString(R.string.history_experimental), 11, badgeFg, MEDIUM);
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        badge.setBackground(rounded(badgeBg, 9));
        bar.addView(badge, badgeParams);

        LinearLayout.LayoutParams barSpacer = new LinearLayout.LayoutParams(0, 0, 1);
        bar.addView(new View(this), barSpacer);

        refreshView = new ImageView(this);
        refreshView.setImageResource(R.drawable.ic_refresh);
        refreshView.setColorFilter(fg);
        refreshView.setPadding(dp(8), dp(8), dp(8), dp(8));
        refreshView.setContentDescription(getString(R.string.history_refresh));
        refreshView.setBackground(ripple(rounded(chipBg, 20)));
        refreshView.setOnClickListener(v -> startRefresh());
        bar.addView(refreshView, new LinearLayout.LayoutParams(dp(40), dp(40)));
        return bar;
    }

    // ====================================================================
    // Refresh / scanning indicator
    // ====================================================================

    /** Kicks off a background history rescan if one is not already running. The glyph always starts
     *  spinning: when a scan is already in flight, that scan's completion stops it. */
    private void startRefresh() {
        startSpin();
        if (BalanceData.HISTORY_SCANNING) return;
        new Thread(() -> BalanceData.scanHistory(HistoryActivity.this)).start();
    }

    /** Starts rotating the refresh glyph; rotations stop once a scan completes. */
    private void startSpin() {
        if (refreshView == null || refreshSpin != null) return;
        spinSince = android.os.SystemClock.uptimeMillis();
        refreshHandler.removeCallbacks(stopSpinRunnable);
        refreshSpin = android.animation.ObjectAnimator.ofFloat(refreshView, "rotation", 0f, 360f);
        refreshSpin.setDuration(900);
        refreshSpin.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        refreshSpin.setInterpolator(new android.view.animation.LinearInterpolator());
        refreshSpin.start();
    }

    private void stopSpin() {
        stopSpin(false);
    }

    /** Stops the rotation immediately, or lets it complete at least a full turn so a scan that
     *  finished in a few frames still gives visible feedback. */
    private void stopSpin(boolean immediate) {
        if (refreshSpin == null) {
            refreshHandler.removeCallbacks(stopSpinRunnable);
            return;
        }
        refreshHandler.removeCallbacks(stopSpinRunnable);
        long elapsed = android.os.SystemClock.uptimeMillis() - spinSince;
        if (!immediate && elapsed < MIN_SPIN_MS) {
            refreshHandler.postDelayed(stopSpinRunnable, MIN_SPIN_MS - elapsed);
            return;
        }
        refreshSpin.cancel();
        refreshSpin = null;
        refreshView.setRotation(0f);
    }

    // ====================================================================
    // Expansion state
    // ====================================================================

    private static final String KEY_EXPANDED_YEARS = "expanded_years";
    private static final String KEY_EXPANDED_MONTHS = "expanded_months";
    private static final String KEY_EXPANDED_DAYS = "expanded_days";

    /** The sets of year, month and day keys currently expanded in the breakdown. The current year,
     *  current month and its days start expanded. */
    private final Set<String> expandedYears = new java.util.LinkedHashSet<>();
    private final Set<String> expandedMonths = new java.util.LinkedHashSet<>();
    private final Set<String> expandedDays = new java.util.LinkedHashSet<>();

    /** Cached reference to the year list so year-header taps can re-render the whole section. */
    private List<YearGroup> allYears;

    /** Expands the current year, current month and its days by default once per screen, so the
     *  freshest history is visible without any interaction without undoing later collapses. */
    private boolean expandedSeeded;

    private void seedExpanded() {
        if (expandedSeeded) return;
        expandedSeeded = true;
        JalaliCalendar now = nowJalali();
        expandedYears.add(String.valueOf(now.year));
        expandedMonths.add(now.year + "/" + now.month);
        for (YearGroup y : allYears) {
            if (y.year != now.year) continue;
            for (MonthGroup m : y.months) {
                if (m.month != now.month) continue;
                for (DayGroup d : m.days) expandedDays.add(d.key());
            }
        }
    }

    private JalaliCalendar nowJalali() {
        Calendar c = Calendar.getInstance(Locale.getDefault());
        return JalaliCalendar.fromGregorian(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putStringArrayList(KEY_EXPANDED_YEARS, new java.util.ArrayList<>(expandedYears));
        outState.putStringArrayList(KEY_EXPANDED_MONTHS, new java.util.ArrayList<>(expandedMonths));
        outState.putStringArrayList(KEY_EXPANDED_DAYS, new java.util.ArrayList<>(expandedDays));
    }

    private final Runnable onHistoryChanged = () -> runOnUiThread(this::render);

    @Override
    protected void onResume() {
        super.onResume();
        BalanceData.addHistoryListener(onHistoryChanged);
        startSpin();
        // Trigger a re-scan in the background (a no-op if the app's own refresh already started one)
        // so fresh messages are reflected as soon as the screen opens without blocking the UI.
        new Thread(() -> BalanceData.scanHistory(HistoryActivity.this)).start();
    }

    @Override
    protected void onPause() {
        BalanceData.removeHistoryListener(onHistoryChanged);
        stopSpin(true);
        super.onPause();
    }

    // ====================================================================
    // Screen rendering
    // ====================================================================

    /** Re-reads the saved history and rebuilds the whole screen from it. */
    private void render() {
        refreshDates();
        List<Transaction> txs = BalanceData.readTransactions(this);
        Lists lists = buildLists(txs);
        body.removeAllViews();
        if (lists.years.isEmpty()) {
            emptyState();
        } else {
            body.addView(heroCard(lists), margin(0, 0, 0, 6));
            body.addView(sectionLabel(getString(R.string.history_breakdown)), margin(0, 16, 0, 12));
            allYears = lists.years;
            seedExpanded();
            renderYears(body, allYears);
        }
        stopSpin();
    }

    private void emptyState() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        wrap.setPadding(0, dp(56), 0, dp(16));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_history_empty);
        icon.setColorFilter(muted);
        wrap.addView(icon);
        TextView msg = text(getString(R.string.history_empty), 14, muted);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(dp(8), dp(18), dp(8), 0);
        wrap.addView(msg, new LinearLayout.LayoutParams(-2, -2));
        body.addView(wrap, margin(12, 0, 12, 0));
    }

    /** The all-time hero: an accent rail, the lifetime net movement, then today / this month /
     *  this year as three equal columns separated by hairline dividers. */
    private LinearLayout heroCard(Lists lists) {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setBackground(heroGradient());
        hero.setPadding(dp(18), dp(16), dp(18), dp(14));

        // Top row: accent rail + "All time" label.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        View railView = new View(this);
        railView.setBackground(rounded(rail, 3));
        LinearLayout.LayoutParams railLp = new LinearLayout.LayoutParams(dp(22), dp(5));
        top.addView(railView, railLp);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -2);
        labelParams.setMarginStart(dp(9));
        TextView label = text(getString(R.string.history_total), 12, muted, MEDIUM);
        label.setLetterSpacing(0.08f);
        top.addView(label, labelParams);
        hero.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // The lifetime total.
        TextView total = bold(signedToman(lists.total), 30, valueColor(lists.total));
        total.setGravity(Gravity.START);
        total.setSingleLine(true);
        autoSize(total, 12, 30);
        hero.addView(total, margin(0, 2, 0, 0));

        // Hairline divider.
        View hair = new View(this);
        hair.setBackgroundColor(divider);
        LinearLayout.LayoutParams hairParams = new LinearLayout.LayoutParams(-1, dp(1));
        hairParams.topMargin = dp(12);
        hairParams.bottomMargin = dp(12);
        hero.addView(hair, hairParams);

        // Period stats row.
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(statsCell(getString(R.string.history_today), lists.today, todayColor),
            new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(hairDivider(), new LinearLayout.LayoutParams(dp(1), dp(44)));
        row.addView(statsCell(getString(R.string.history_this_month), lists.month, monthColor),
            new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(hairDivider(), new LinearLayout.LayoutParams(dp(1), dp(44)));
        row.addView(statsCell(getString(R.string.history_this_year), lists.year, yearColor),
            new LinearLayout.LayoutParams(0, -2, 1));
        hero.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return hero;
    }

    private View hairDivider() {
        View v = new View(this);
        v.setBackgroundColor(divider);
        v.setAlpha(0.6f);
        return v;
    }

    /** One period stat: colored label over the signed net value, auto-sized to its column. */
    private LinearLayout statsCell(String label, long value, int color) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView l = text(label, 12, color, MEDIUM);
        cell.addView(l, new LinearLayout.LayoutParams(-2, -2));
        TextView v = bold(signedToman(value), 18, color);
        v.setSingleLine(true);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        autoSize(v, 10, 18);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, -2);
        vp.topMargin = dp(5);
        cell.addView(v, vp);
        return cell;
    }

    /** A list-style section header: letter-spaced label on the baseline. */
    private TextView sectionLabel(String s) {
        TextView t = text(s, 12, muted, MEDIUM);
        t.setLetterSpacing(0.09f);
        return t;
    }

    // ====================================================================
    // Year-by-year breakdown
    // ====================================================================

    /** Renders the year-by-year breakdown into the given host. Each year is a card whose header
     *  toggles that year's months on tap. */
    private void renderYears(LinearLayout host, List<YearGroup> years) {
        for (int i = host.getChildCount() - 1; i >= 0; i--) {
            View v = host.getChildAt(i);
            if (YEAR_TAG.equals(v.getTag())) {
                host.removeViewAt(i);
            }
        }
        for (YearGroup y : years) {
            LinearLayout card = yearCard(y);
            card.setTag(YEAR_TAG);
            host.addView(card, margin(0, 0, 0, 12));
        }
    }

    /** A collapsible year card: a chevron header with the year, movement count and net, plus
     *  deposit/withdrawal chips and a proportion track. Months drop into a soft inset sheet. */
    private LinearLayout yearCard(YearGroup y) {
        boolean open = expandedYears.contains(y.key());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(2), dp(2), dp(2), dp(2));
        box.setBackground(roundedStroke(card, 20, divider));

        // Header (tap target with ripple).
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setClickable(true);
        head.setFocusable(true);
        head.setBackground(ripple(rounded(card, 20)));
        head.setPaddingRelative(dp(8), open ? dp(10) : dp(12), dp(8), open ? dp(8) : dp(12));
        head.setOnClickListener(v -> {
            if (open) expandedYears.remove(y.key()); else expandedYears.add(y.key());
            renderYears((LinearLayout) box.getParent(), allYears);
        });
        head.addView(caret(open, 16), new LinearLayout.LayoutParams(dp(26), -2));

        String yTitle = LocaleHelper.currentTag(this).equals("fa")
            ? faDigits(y.year) : String.valueOf(y.year);
        TextView title = bold(yTitle, 18, fg);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-2, -2);
        titleLp.setMarginStart(dp(6));
        head.addView(title, titleLp);

        LinearLayout.LayoutParams spacer = new LinearLayout.LayoutParams(0, 0, 1);
        head.addView(new View(this), spacer);

        LinearLayout.LayoutParams chipsOnHead = new LinearLayout.LayoutParams(-2, -2);
        head.addView(countChip(y.n), chipsOnHead);
        LinearLayout.LayoutParams sumParams = new LinearLayout.LayoutParams(-2, -2);
        sumParams.setMarginStart(dp(10));
        TextView sum = bold(signedToman(y.sum), 16, valueColor(y.sum));
        head.addView(sum, sumParams);
        head.setContentDescription(state(yTitle, y.sum, open));
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));

        if (y.dep > 0 || y.wit < 0) {
            LinearLayout chips = chipsRow(y.dep, y.wit);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.setMarginStart(dp(30));
            box.addView(chips, cp);

            View track = depWitTrack(y.dep, y.wit);
            if (track != null) {
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(6));
                tp.setMarginStart(dp(30));
                tp.setMarginEnd(dp(4));
                tp.topMargin = dp(6);
                box.addView(track, tp);
            }
        }

        if (open) {
            LinearLayout monthsHost = new LinearLayout(this);
            monthsHost.setOrientation(LinearLayout.VERTICAL);
            monthsHost.setPadding(dp(4), dp(6), dp(4), dp(2));
            monthsHost.setBackground(rounded(openBg, 14));
            renderMonths(monthsHost, y.months);
            LinearLayout.LayoutParams monthsParams = new LinearLayout.LayoutParams(-1, -2);
            monthsParams.topMargin = dp(8);
            box.addView(monthsHost, monthsParams);
        }
        return box;
    }

    /** Renders a year's month rows into its months container, stacked with a small gap so each
     *  month reads as one distinct sub-item of its year. */
    private void renderMonths(LinearLayout monthsHost, List<MonthGroup> months) {
        for (int i = monthsHost.getChildCount() - 1; i >= 0; i--) {
            View v = monthsHost.getChildAt(i);
            if (MONTH_TAG.equals(v.getTag())) monthsHost.removeViewAt(i);
        }
        for (int i = 0; i < months.size(); i++) {
            LinearLayout row = monthCard(months.get(i), monthsHost, months);
            row.setTag(MONTH_TAG);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(i > 0 ? 6 : 0);
            monthsHost.addView(row, lp);
        }
    }

    /** A collapsible month row inside the year sheet: caret, month name, net and small
     *  deposit/withdrawal chips; its expanded view lists the day-by-day details. */
    private LinearLayout monthCard(MonthGroup m, LinearLayout monthsHost, List<MonthGroup> months) {
        boolean open = expandedMonths.contains(m.key());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(2), dp(4), dp(2));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setClickable(true);
        head.setFocusable(true);
        head.setBackground(ripple(rounded(openBg, 10)));
        head.setPaddingRelative(dp(6), dp(7), dp(6), dp(7));
        head.setOnClickListener(v -> {
            if (open) expandedMonths.remove(m.key()); else expandedMonths.add(m.key());
            renderMonths(monthsHost, months);
        });
        head.addView(caret(open, 14), new LinearLayout.LayoutParams(dp(22), -2));

        TextView date = text(monthName(m.month), 15, fg, MEDIUM);
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(-2, -2);
        dateLp.setMarginStart(dp(5));
        head.addView(date, dateLp);

        LinearLayout.LayoutParams spacer = new LinearLayout.LayoutParams(0, 0, 1);
        head.addView(new View(this), spacer);
        TextView count = text(getResources().getQuantityString(R.plurals.history_n_tx, m.n, m.n), 11, muted);
        head.addView(count);
        LinearLayout.LayoutParams sumParams = new LinearLayout.LayoutParams(-2, -2);
        sumParams.setMarginStart(dp(10));
        TextView sum = bold(signedToman(m.sum), 14, valueColor(m.sum));
        head.addView(sum, sumParams);
        head.setContentDescription(state(monthName(m.month), m.sum, open));
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));

        if (m.dep > 0 || m.wit < 0) {
            LinearLayout chips = chipsRow(m.dep, m.wit);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.setMarginStart(dp(26));
            cp.topMargin = dp(2);
            box.addView(chips, cp);
        }

        if (open) {
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setPaddingRelative(dp(10), 0, 0, 0);
            renderDays(inner, m.days);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
            ip.topMargin = dp(4);
            box.addView(inner, ip);
        }
        return box;
    }

    /** Renders a month's day rows into its days container, each day a distinct sub-item. */
    private void renderDays(LinearLayout daysHost, List<DayGroup> days) {
        for (int i = daysHost.getChildCount() - 1; i >= 0; i--) {
            View v = daysHost.getChildAt(i);
            if (DAY_TAG.equals(v.getTag())) daysHost.removeViewAt(i);
        }
        for (int i = 0; i < days.size(); i++) {
            LinearLayout card = dayCard(days.get(i), daysHost, days);
            card.setTag(DAY_TAG);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(i > 0 ? 2 : 0);
            daysHost.addView(card, lp);
        }
    }

    /** A collapsible day row: caret, a Today/Yesterday tag over the date, transaction count and
     *  the day's net; expanding it lists that day's transactions newest first. */
    private LinearLayout dayCard(DayGroup g, LinearLayout daysHost, List<DayGroup> days) {
        boolean open = expandedDays.contains(g.key());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPaddingRelative(dp(2), dp(2), dp(2), dp(2));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setClickable(true);
        head.setFocusable(true);
        head.setBackground(ripple(rounded(openBg, 10)));
        head.setPaddingRelative(dp(4), dp(6), dp(4), dp(6));
        head.setOnClickListener(v -> {
            if (open) expandedDays.remove(g.key()); else expandedDays.add(g.key());
            renderDays(daysHost, days);
        });
        head.addView(caret(open, 13), new LinearLayout.LayoutParams(dp(22), -2));

        LinearLayout dateWrap = new LinearLayout(this);
        dateWrap.setGravity(Gravity.CENTER_VERTICAL);
        dateWrap.setOrientation(LinearLayout.HORIZONTAL);
        if (sameDay(g.date, todayJalali)) {
            TextView tag = text(getString(R.string.history_today), 11, badgeFg, MEDIUM);
            tag.setPadding(dp(6), dp(2), dp(6), dp(2));
            tag.setBackground(rounded(badgeBg, 8));
            dateWrap.addView(tag);
        } else if (sameDay(g.date, yesterdayJalali)) {
            TextView tag = text(getString(R.string.history_yesterday), 11, muted, MEDIUM);
            tag.setPadding(dp(6), dp(2), dp(6), dp(2));
            tag.setBackground(rounded(chipBg, 8));
            dateWrap.addView(tag);
        }
        TextView date = text(persianDate(g.date), 13, fg);
        LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(-2, -2);
        dateParams.setMarginStart(dp(6));
        dateWrap.addView(date, dateParams);
        LinearLayout.LayoutParams dateWrapLp = new LinearLayout.LayoutParams(-2, -2);
        dateWrapLp.setMarginStart(dp(4));
        head.addView(dateWrap, dateWrapLp);

        LinearLayout.LayoutParams spacer = new LinearLayout.LayoutParams(0, 0, 1);
        head.addView(new View(this), spacer);
        if (g.txs.size() > 1) head.addView(countChip(g.txs.size()));
        LinearLayout.LayoutParams sumParams = new LinearLayout.LayoutParams(-2, -2);
        sumParams.setMarginStart(dp(10));
        TextView sum = bold(signedToman(g.sum), 13, valueColor(g.sum));
        head.addView(sum, sumParams);
        head.setContentDescription(state(persianDate(g.date), g.sum, open));
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));

        if (open) {
            LinearLayout rows = new LinearLayout(this);
            rows.setOrientation(LinearLayout.VERTICAL);
            rows.setPaddingRelative(dp(8), dp(2), 0, 0);
            for (int i = 0; i < g.txs.size(); i++) {
                if (i > 0) {
                    View sep = new View(this);
                    sep.setBackgroundColor(divider);
                    sep.setAlpha(0.35f);
                    LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(1));
                    slp.setMarginStart(dp(34));
                    rows.addView(sep, slp);
                }
                rows.addView(txRow(g.txs.get(i)), new LinearLayout.LayoutParams(-1, -2));
            }
            box.addView(rows, new LinearLayout.LayoutParams(-1, -2));
        }
        return box;
    }

    /** One movement: bank badge, bank name with time, and the signed amount. */
    private LinearLayout txRow(Transaction t) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPaddingRelative(dp(4), dp(3), dp(4), dp(3));

        View badge = bankBadge(t.bank);
        row.addView(badge, new LinearLayout.LayoutParams(dp(30), dp(30)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(BankRules.displayName(this, t.bank), 13, fg);
        col.addView(name, new LinearLayout.LayoutParams(-2, -2));
        TextView time = text(timeText(t.date), 11, muted);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = dp(2);
        col.addView(time, tp);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, -2, 1);
        colLp.setMarginStart(dp(9));
        row.addView(col, colLp);

        TextView amt = bold(signedToman(t.amount), 13, valueColor(t.amount));
        row.addView(amt, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    /** A small neutral chip with a count, for transaction-count density. */
    private TextView countChip(int n) {
        TextView t = text(String.valueOf(n), 11, muted);
        t.setTypeface(null, Typeface.BOLD);
        t.setBackground(rounded(chipBg, 8));
        t.setPadding(dp(7), dp(3), dp(7), dp(3));
        t.setContentDescription(getResources().getQuantityString(R.plurals.history_n_tx, n, n));
        return t;
    }

    /** The collapse/expand caret: a round chip showing a down caret when the group is open and a
     *  side caret when collapsed (rippling to mirror direction in RTL), unambiguous at a glance. */
    private TextView caret(boolean open, int sp) {
        TextView t = text(open ? "\u25be" : (isRtl() ? "\u25c2" : "\u25b8"), sp, caretColor(open), MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(chipBg, 14));
        return t;
    }

    private int caretColor(boolean open) {
        return open ? accent : muted;
    }

    /** Content description for a collapsible group header also states its current expansion. */
    private String state(String title, long sum, boolean open) {
        return title + ", " + signedToman(sum) + ", "
            + getString(open ? R.string.history_expanded : R.string.history_collapsed);
    }

    /** Deposit and withdrawal subtotals as compact colored pills; sign is shown by the arrow. */
    private LinearLayout chipsRow(long deposits, long withdrawals) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (deposits > 0) {
            row.addView(chip("\u2191 " + BalanceData.toman(this, deposits), depBg, depFg,
                getString(R.string.history_deposit)));
        }
        if (withdrawals < 0) {
            row.addView(chip("\u2193 " + BalanceData.toman(this, -withdrawals), witBg, witFg,
                getString(R.string.history_withdrawal)));
        }
        return row;
    }

    private TextView chip(String s, int bgColor, int fgColor, String desc) {
        TextView t = text(s, 11, fgColor, MEDIUM);
        t.setBackground(rounded(bgColor, 9));
        t.setPadding(dp(9), dp(4), dp(9), dp(4));
        t.setContentDescription(desc);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(dp(8));
        t.setLayoutParams(lp);
        return t;
    }

    /** A deposit/withdrawal proportion track showing the deposit share (green) vs withdrawal share
     *  (red), so direction density is scannable at a glance. */
    private View depWitTrack(long deposits, long withdrawals) {
        float d = Math.max(deposits, 0), w = Math.max(-withdrawals, 0);
        if (d == 0 && w == 0) return null;
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackground(rounded(divider, 2));
        track.setAlpha(0.7f);
        if (d > 0) {
            View seg = new View(this);
            seg.setBackground(rounded(depFg, 2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, d);
            lp.setMarginEnd(dp(2));
            track.addView(seg, lp);
        }
        if (w > 0) {
            View seg = new View(this);
            seg.setBackground(rounded(witFg, 2));
            track.addView(seg, new LinearLayout.LayoutParams(0, -1, w));
        }
        return track;
    }

    /** A 34dp round-cornered badge: the bank's brand icon when one exists, otherwise a colored
     *  square with the bank's initials — mirroring the main screen. */
    private View bankBadge(String canonicalName) {
        int iconRes = BankIcon.iconFor(canonicalName);
        if (iconRes != 0) {
            ImageView iv = new ImageView(this);
            iv.setImageResource(iconRes);
            iv.setContentDescription(BankRules.displayName(this, canonicalName));
            return iv;
        }
        int color = bankColors[Math.floorMod(canonicalName.hashCode(), bankColors.length)];
        GradientDrawable bg = rounded(color, 10);
        LinearLayout sq = new LinearLayout(this);
        sq.setOrientation(LinearLayout.VERTICAL);
        sq.setGravity(Gravity.CENTER);
        sq.setBackground(bg);
        sq.setContentDescription(BankRules.displayName(this, canonicalName));
        TextView init = text(bankInitials(canonicalName), 11, Color.WHITE);
        init.setTypeface(null, Typeface.BOLD);
        sq.addView(init);
        return sq;
    }

    private String bankInitials(String name) {
        String canonical = name.trim();
        if (canonical.isEmpty()) return "?";
        String[] words = canonical.split(" ");
        if (words.length > 1 && words[0].length() > 0 && words[1].length() > 0) {
            return (words[0].substring(0, 1) + words[1].substring(0, 1)).toUpperCase(Locale.US);
        }
        return canonical.substring(0, Math.min(2, canonical.length())).toUpperCase(Locale.US);
    }

    // ====================================================================
    // Data model (kept public/static for the instrumented tests)
    // ====================================================================

    /** Aggregated history data: today/month/year net sums plus deposit/withdrawal subtotals
     *  and the year-by-year breakdown (each year holds its months, each month its days). */
    static final class Lists {
        long today, month, year, total;
        long todayDep, monthDep, yearDep;
        long todayWit, monthWit, yearWit;
        final List<YearGroup> years = new ArrayList<>();
    }

    static final class DayGroup {
        final JalaliCalendar date;
        long sum;
        final List<Transaction> txs = new ArrayList<>();
        DayGroup(JalaliCalendar date) {
            this.date = date;
        }
        String key() {
            return date.year + "/" + date.month + "/" + date.day;
        }
    }

    /** One Persian-calendar month of history: header (name + net + deposit/withdrawal subtotals)
     *  and its day-by-day groups, kept in descending date order. */
    static final class MonthGroup {
        final int year, month;
        long sum, dep, wit;
        int n;
        final List<DayGroup> days = new ArrayList<>();
        MonthGroup(int year, int month) {
            this.year = year;
            this.month = month;
        }
        String key() {
            return year + "/" + month;
        }
    }

    /** One Persian-calendar year of history: header (year + net + deposit/withdrawal subtotals)
     *  and its months, kept in descending date order. */
    static final class YearGroup {
        final int year;
        long sum, dep, wit;
        int n;
        final List<MonthGroup> months = new ArrayList<>();
        YearGroup(int year) {
            this.year = year;
        }
        String key() {
            return String.valueOf(year);
        }
    }

    /** Splits the raw transactions into the summary sums and the year-by-year (month-by-month,
     *  day-by-day) groups. Never mutates the caller's list. */
    static Lists buildLists(List<Transaction> txs) {
        Lists lists = new Lists();
        if (txs.isEmpty()) return lists;

        List<Transaction> sorted = new ArrayList<>(txs);
        Collections.sort(sorted, new Comparator<Transaction>() {
            @Override
            public int compare(Transaction a, Transaction b) {
                return Long.compare(b.date, a.date);
            }
        });

        Calendar cal = Calendar.getInstance(Locale.getDefault());
        long now = cal.getTimeInMillis();
        JalaliCalendar today = JalaliCalendar.fromGregorian(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));

        Map<String, YearGroup> yearIndex = new HashMap<>();
        Map<String, MonthGroup> monthIndex = new HashMap<>();
        for (Transaction t : sorted) {
            lists.total += t.amount;
            int[] g = gDate(t.date);
            JalaliCalendar jc = JalaliCalendar.fromGregorian(g[0], g[1], g[2]);
            boolean todayMatch = sameDay(jc, today);
            boolean monthMatch = jc.year == today.year && jc.month == today.month;
            boolean yearMatch = jc.year == today.year;
            if (todayMatch) {
                lists.today += t.amount;
                if (t.amount > 0) lists.todayDep += t.amount; else lists.todayWit += t.amount;
            }
            if (monthMatch) {
                lists.month += t.amount;
                if (t.amount > 0) lists.monthDep += t.amount; else lists.monthWit += t.amount;
            }
            if (yearMatch) {
                lists.year += t.amount;
                if (t.amount > 0) lists.yearDep += t.amount; else lists.yearWit += t.amount;
            }

            String yearKey = String.valueOf(jc.year);
            YearGroup year = yearIndex.get(yearKey);
            if (year == null) {
                year = new YearGroup(jc.year);
                yearIndex.put(yearKey, year);
                lists.years.add(year);
            }
            year.sum += t.amount;
            year.n++;
            if (t.amount > 0) year.dep += t.amount; else year.wit += t.amount;

            String monthKey = jc.year + "/" + jc.month;
            MonthGroup month = monthIndex.get(monthKey);
            if (month == null) {
                month = new MonthGroup(jc.year, jc.month);
                monthIndex.put(monthKey, month);
                year.months.add(month);
            }
            month.sum += t.amount;
            month.n++;
            if (t.amount > 0) month.dep += t.amount; else month.wit += t.amount;

            DayGroup day = month.days.isEmpty() ? null : month.days.get(month.days.size() - 1);
            if (day == null || day.date.day != jc.day) {
                day = new DayGroup(jc);
                month.days.add(day);
            }
            day.sum += t.amount;
            day.txs.add(t);
        }
        return lists;
    }

    private static boolean sameDay(JalaliCalendar a, JalaliCalendar b) {
        return a.year == b.year && a.month == b.month && a.day == b.day;
    }

    private static int[] gDate(long date) {
        Calendar c = Calendar.getInstance(Locale.getDefault());
        c.setTimeInMillis(date);
        return new int[]{c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)};
    }

    // ====================================================================
    // Formatting
    // ====================================================================

    private int valueColor(long value) {
        return value < 0 ? negativeColor : value > 0 ? positiveColor : muted;
    }

    /** Localized Persian month name from the 1-based month index. */
    private String monthName(int month) {
        String tag = LocaleHelper.currentTag(this);
        boolean fa = "fa".equals(tag);
        switch (month) {
            case 1: return fa ? "\u0641\u0631\u0648\u0631\u062f\u06cc\u0646" : "Farvardin";
            case 2: return fa ? "\u0627\u0631\u062f\u06cc\u0628\u0647\u0634\u062a" : "Ordibehesht";
            case 3: return fa ? "\u062e\u0631\u062f\u0627\u062f" : "Khordad";
            case 4: return fa ? "\u062a\u06cc\u0631" : "Tir";
            case 5: return fa ? "\u0645\u0631\u062f\u0627\u062f" : "Mordad";
            case 6: return fa ? "\u0634\u0647\u0631\u06cc\u0648\u0631" : "Shahrivar";
            case 7: return fa ? "\u0645\u0647\u0631" : "Mehr";
            case 8: return fa ? "\u0622\u0628\u0627\u0646" : "Aban";
            case 9: return fa ? "\u0622\u0630\u0631" : "Azar";
            case 10: return fa ? "\u062f\u06cc" : "Dey";
            case 11: return fa ? "\u0628\u0647\u0645\u0646" : "Bahman";
            default: return fa ? "\u0627\u0633\u0641\u0646\u062f" : "Esfand";
        }
    }

    /** Formats a Persian date in the app language, e.g. "Khordad 12 1403" / "۱۲ خرداد ۱۴۰۳". */
    private String persianDate(JalaliCalendar jc) {
        String tag = LocaleHelper.currentTag(this);
        boolean fa = "fa".equals(tag);
        if (fa) {
            return faDigits(jc.day) + " " + monthName(jc.month) + " " + faDigits(jc.year);
        }
        return monthName(jc.month) + " " + jc.day + " " + jc.year;
    }

    /** The movement's time of day as a compact "HH:mm" string in the app digits. */
    private String timeText(long date) {
        String s = new java.text.SimpleDateFormat("HH:mm", Locale.US)
            .format(new java.util.Date(date));
        return "fa".equals(LocaleHelper.currentTag(this)) ? faDigitsString(s) : s;
    }

    private String faDigits(long n) {
        return NumberFormat.getNumberInstance(new Locale("fa")).format(n);
    }

    private String faDigitsString(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '0' && c <= '9') b.append((char) ('\u06f0' + c - '0'));
            else b.append(c);
        }
        return b.toString();
    }

    /** Formats a rial amount as a signed toman string, following the app language's digit rules. */
    private String signedToman(long n) {
        String mag = BalanceData.toman(this, Math.abs(n));
        if (n == 0) return mag;
        return (n < 0 ? "\u2212" : "+") + mag;
    }
}