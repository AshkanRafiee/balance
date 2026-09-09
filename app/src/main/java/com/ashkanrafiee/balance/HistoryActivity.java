package com.ashkanrafiee.balance;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
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
 * today, this Persian (Jalali) month and this Persian year (three summary squares), plus a year-by-
 * year breakdown that drills down into months and days. Sums always reflect money <em>movements</em>
 * (deposits minus withdrawals), never remaining balances. All date boundaries follow the Persian
 * calendar.
 *
 * <p>The screen kicks off a background history re-scan whenever it opens and re-renders on the
 * result, showing a pulsing "Updating…" pill while a scan is in flight.
 */
public final class HistoryActivity extends Activity {
    private static final String MONTH_TAG = "history_month";
    private static final String YEAR_TAG = "history_year";
    private static final String UPDATING_TAG = "history_updating";
    private int bg, card, muted, accent, fg, divider, positiveColor, negativeColor;
    private int todayColor, monthColor, yearColor, todayBg, monthBg, yearBg;
    private LinearLayout body;

    int color(int res) {
        return getResources().getColor(res, getTheme());
    }

    int dp(float n) {
        return (int) (n * getResources().getDisplayMetrics().density + .5f);
    }

    TextView text(String s, float size, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        return v;
    }

    GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    LinearLayout.LayoutParams margin(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    boolean isRtl() {
        return getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        bg = color(R.color.bg);
        card = color(R.color.panel);
        muted = color(R.color.muted);
        accent = color(R.color.accent);
        fg = color(R.color.fg);
        divider = color(R.color.divider);
        positiveColor = color(R.color.accent);
        negativeColor = color(R.color.negative);
        todayColor = color(R.color.accent);
        monthColor = color(R.color.purple);
        yearColor = color(R.color.history_year);
        todayBg = color(R.color.history_today_bg);
        monthBg = color(R.color.history_month_bg);
        yearBg = color(R.color.history_year_bg);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        boolean rtl = isRtl();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(14), dp(20), dp(14));
        root.setBackgroundColor(bg);
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

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text(rtl ? "›" : "‹", 34, fg);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(48)));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2);
        titleParams.setMarginStart(dp(10));
        bar.addView(text(getString(R.string.history_title), 21, fg), titleParams);
        root.addView(bar, margin(0, 0, 0, 16));

        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -1));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        render();
    }

    private final Runnable onHistoryChanged = () -> runOnUiThread(this::render);

    @Override
    protected void onResume() {
        super.onResume();
        BalanceData.addHistoryListener(onHistoryChanged);
        // Trigger a re-scan in the background (a no-op if the app's own refresh already started one)
        // so fresh messages are reflected as soon as the screen opens without blocking the UI.
        new Thread(() -> BalanceData.scanHistory(HistoryActivity.this)).start();
    }

    @Override
    protected void onPause() {
        BalanceData.removeHistoryListener(onHistoryChanged);
        super.onPause();
    }

    /** Re-reads the saved history and rebuilds the whole screen from it. */
    private void render() {
        List<Transaction> txs = BalanceData.readTransactions(this);
        Lists lists = buildLists(txs);
        body.removeAllViews();
        boolean scanning = BalanceData.HISTORY_SCANNING;
        if (lists.years.isEmpty()) {
            TextView empty = text(getString(R.string.history_empty), 14, muted);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(48), 0, 0);
            body.addView(empty, margin(0, 0, 0, 16));
        } else {
            body.addView(statsRow(lists), margin(0, 0, 0, 18));
            body.addView(text(getString(R.string.history_breakdown), 14, muted), margin(2, 0, 0, 8));
            allYears = lists.years;
            seedExpanded();
            renderYears(body, allYears);
        }
        if (scanning) {
            TextView pill = text(getString(R.string.history_updating), 13, accent);
            pill.setTag(UPDATING_TAG);
            pill.setPadding(dp(10), dp(6), dp(10), dp(6));
            pill.setBackground(rounded(card, 15));
            body.addView(pill, margin(0, lists.years.isEmpty() ? 6 : 8, 0, 0));
            pulse(pill);
        }
    }

    /** A soft pulsing animation so an in-flight update is visibly "alive". */
    private void pulse(View v) {
        android.view.animation.AlphaAnimation a = new android.view.animation.AlphaAnimation(1f, 0.3f);
        a.setDuration(550);
        a.setRepeatMode(android.view.animation.Animation.REVERSE);
        a.setRepeatCount(android.view.animation.Animation.INFINITE);
        v.startAnimation(a);
    }

    /** The sets of year and month keys currently expanded in the breakdown. The current year and
     *  current month start expanded. */
    private final Set<String> expandedYears = new java.util.LinkedHashSet<>();
    private final Set<String> expandedMonths = new java.util.LinkedHashSet<>();

    /** Cached reference to the year list so year-header taps can re-render the whole section. */
    private List<YearGroup> allYears;

    /** Renders the year-by-year breakdown into the given host. Each year is a card whose header
     *  toggles that year's months on tap. */
    private void renderYears(LinearLayout host, List<YearGroup> years) {
        for (int i = host.getChildCount() - 1; i >= 0; i--) {
            View v = host.getChildAt(i);
            if (YEAR_TAG.equals(v.getTag())) host.removeViewAt(i);
        }
        for (YearGroup y : years) {
            LinearLayout card = yearCard(y);
            card.setTag(YEAR_TAG);
            host.addView(card, margin(0, 0, 0, 10));
        }
    }

    /** Renders a year's month cards into its months container. */
    private void renderMonths(LinearLayout monthsHost, List<MonthGroup> months) {
        for (int i = monthsHost.getChildCount() - 1; i >= 0; i--) {
            View v = monthsHost.getChildAt(i);
            if (MONTH_TAG.equals(v.getTag())) monthsHost.removeViewAt(i);
        }
        for (MonthGroup m : months) {
            LinearLayout card = monthCard(m, monthsHost, months);
            card.setTag(MONTH_TAG);
            monthsHost.addView(card, margin(0, 0, 0, 10));
        }
    }

    private JalaliCalendar nowJalali() {
        Calendar c = Calendar.getInstance(Locale.getDefault());
        return JalaliCalendar.fromGregorian(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** Expands the current year and current month by default, so the freshest history is visible
     *  without any interaction. */
    private void seedExpanded() {
        JalaliCalendar now = nowJalali();
        expandedYears.add(String.valueOf(now.year));
        expandedMonths.add(now.year + "/" + now.month);
    }

    /** The today / this month / this year net sums as three colored squares on one row, leaving the
     *  vertical space below free for the year breakdown. */
    private LinearLayout statsRow(Lists lists) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(-1, -2, 1);
        cellParams.setMarginStart(dp(4));
        cellParams.setMarginEnd(dp(4));
        row.addView(statsSquare(getString(R.string.history_today), lists.today,
            todayColor, todayBg), cellParams);
        row.addView(statsSquare(getString(R.string.history_this_month), lists.month,
            monthColor, monthBg), cellParams);
        row.addView(statsSquare(getString(R.string.history_this_year), lists.year,
            yearColor, yearBg), cellParams);
        return row;
    }

    private LinearLayout statsSquare(String label, long value, int color, int bgColor) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(12), dp(10), dp(12), dp(12));
        cell.setBackground(rounded(bgColor, 15));
        TextView l = text(label, 12, color);
        l.setTypeface(null, Typeface.BOLD);
        cell.addView(l);
        TextView v = text(signedToman(value), 24, color);
        v.setTypeface(null, Typeface.BOLD);
        v.setPadding(0, dp(4), 0, 0);
        cell.addView(v);
        return cell;
    }

    /** A collapsible year card: header with the year, net sum, deposit/withdrawal subtotals and a
     *  chevron. The year's month cards are shown only while expanded. */
    private LinearLayout yearCard(YearGroup y) {
        boolean open = expandedYears.contains(y.key());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(13), dp(16), open ? dp(8) : dp(13));
        box.setBackground(rounded(card, 15));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setClickable(true);
        head.setFocusable(true);
        head.setOnClickListener(v -> {
            if (open) expandedYears.remove(y.key()); else expandedYears.add(y.key());
            renderYears((LinearLayout) box.getParent(), allYears);
        });
        TextView chevron = text(open ? "\u25be" : "\u25b8", 16, muted);
        head.addView(chevron, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams yearParams = new LinearLayout.LayoutParams(-2, -2);
        yearParams.setMarginStart(dp(8));
        String yTitle = LocaleHelper.currentTag(this).equals("fa") ? faDigits(y.year) : String.valueOf(y.year);
        TextView title = text(yTitle, 17, fg);
        title.setTypeface(null, Typeface.BOLD);
        head.addView(title, yearParams);
        LinearLayout.LayoutParams spacer = new LinearLayout.LayoutParams(-2, -2, 1);
        head.addView(new View(this), spacer);
        TextView sum = text(signedToman(y.sum), 17, valueColor(y.sum));
        sum.setTypeface(null, Typeface.BOLD);
        head.addView(sum);
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));
        box.addView(depWitRow(y.dep, y.wit, 0), new LinearLayout.LayoutParams(-1, -2));

        if (open) {
            LinearLayout monthsHost = new LinearLayout(this);
            monthsHost.setOrientation(LinearLayout.VERTICAL);
            renderMonths(monthsHost, y.months);
            LinearLayout.LayoutParams monthsParams = new LinearLayout.LayoutParams(-1, -2);
            monthsParams.topMargin = dp(8);
            box.addView(monthsHost, monthsParams);
        }
        return box;
    }

    /** A collapsible month card: header with the month name, net sum, deposit/withdrawal subtotals
     *  and a chevron. The month's day-by-day details are shown only while expanded. */
    private LinearLayout monthCard(MonthGroup m, LinearLayout monthsHost, List<MonthGroup> months) {
        boolean open = expandedMonths.contains(m.key());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rounded(card, 15));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(11), dp(16), dp(11));
        head.setClickable(true);
        head.setFocusable(true);
        head.setOnClickListener(v -> {
            if (open) expandedMonths.remove(m.key()); else expandedMonths.add(m.key());
            renderMonths(monthsHost, months);
        });
        TextView chevron = text(open ? "\u25be" : "\u25b8", 15, muted);
        head.addView(chevron, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(-2, -2);
        dateParams.setMarginStart(dp(8));
        TextView date = text(monthName(m.month), 16, fg);
        head.addView(date, dateParams);
        LinearLayout.LayoutParams spacer = new LinearLayout.LayoutParams(-2, -2, 1);
        head.addView(new View(this), spacer);
        TextView sum = text(signedToman(m.sum), 16, valueColor(m.sum));
        sum.setTypeface(null, Typeface.BOLD);
        head.addView(sum);
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));
        box.addView(depWitRow(m.dep, m.wit, dp(16)), new LinearLayout.LayoutParams(-1, -2));

        if (open) {
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setPadding(dp(24), 0, dp(24), dp(13));
            for (DayGroup day : m.days) inner.addView(dayCard(day));
            box.addView(inner, new LinearLayout.LayoutParams(-1, -2));
        }
        return box;
    }

    /** Aggregated history data: today/month/year net sums plus deposit/withdrawal subtotals
     *  and the year-by-year breakdown (each year holds its months, each month its days). */
    static final class Lists {
        long today, month, year;
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
    }

    /** One Persian-calendar month of history: header (name + net + deposit/withdrawal subtotals)
     *  and its day-by-day groups, kept in descending date order. */
    static final class MonthGroup {
        final int year, month;
        long sum, dep, wit;
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
            if (t.amount > 0) year.dep += t.amount; else year.wit += t.amount;

            String monthKey = jc.year + "/" + jc.month;
            MonthGroup month = monthIndex.get(monthKey);
            if (month == null) {
                month = new MonthGroup(jc.year, jc.month);
                monthIndex.put(monthKey, month);
                year.months.add(month);
            }
            month.sum += t.amount;
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

    /** One horizontal row of the deposit and withdrawal subtotals as signed, colored numbers. The
     *  sign and color already identify the direction, so no labels are needed. */
    private LinearLayout depWitRow(long deposits, long withdrawals, int hPad) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(hPad, 0, hPad, 0);
        TextView dep = text(signedToman(deposits), 13, positiveColor);
        dep.setTypeface(null, Typeface.BOLD);
        row.addView(dep, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams witParams = new LinearLayout.LayoutParams(-2, -2);
        witParams.setMarginStart(dp(16));
        TextView wit = text(signedToman(-withdrawals), 13, negativeColor);
        wit.setTypeface(null, Typeface.BOLD);
        row.addView(wit, witParams);
        return row;
    }

    private LinearLayout dayCard(DayGroup g) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(12), 0, 0);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView date = text(persianDate(g.date), 16, fg);
        head.addView(date, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams spacer = new LinearLayout.LayoutParams(-2, -2, 1);
        head.addView(new View(this), spacer);
        TextView sum = text(signedToman(g.sum) + (g.txs.size() > 1 ? " (" + g.txs.size() + ")" : ""),
            16, valueColor(g.sum));
        sum.setTypeface(null, Typeface.BOLD);
        head.addView(sum);
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // The day's movements as signed, colored numbers laid out horizontally — the sign and color
        // already show which are deposits and which are withdrawals.
        LinearLayout nums = new LinearLayout(this);
        nums.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < g.txs.size(); i++) {
            Transaction t = g.txs.get(i);
            TextView tv = text(signedToman(t.amount), 14, valueColor(t.amount));
            tv.setTypeface(null, Typeface.BOLD);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) p.setMarginStart(dp(16));
            nums.addView(tv, p);
        }
        LinearLayout.LayoutParams numsParams = new LinearLayout.LayoutParams(-1, -2);
        numsParams.topMargin = dp(6);
        box.addView(nums, numsParams);
        return box;
    }

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

    private String faDigits(long n) {
        return NumberFormat.getNumberInstance(new Locale("fa")).format(n);
    }

    /** Formats a rial amount as a signed toman string, following the app language's digit rules. */
    private String signedToman(long n) {
        String mag = BalanceData.toman(this, Math.abs(n));
        if (n == 0) return mag;
        return (n < 0 ? "\u2212" : "+") + mag;
    }
}