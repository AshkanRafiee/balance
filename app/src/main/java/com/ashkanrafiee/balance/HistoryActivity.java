package com.ashkanrafiee.balance;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Telephony;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
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
 * <p>The screen re-scans the SMS inbox whenever it opens and whenever a new bank message arrives
 * (a ContentObserver, like the main screen), silently re-rendering on completion.
 */
public final class HistoryActivity extends Activity {
    private static final String MONTH_TAG = "history_month";
    private static final String DAY_TAG = "history_day";
    private static final String YEAR_TAG = "history_year";

    /** Intent extra: when set, the screen shows the history of this canonical bank name only. */
    static final String EXTRA_BANK = "bank_filter";

    /** Intent extra (used with {@link #EXTRA_BANK}): when set, only transactions of this account
     *  number of that bank are shown. */
    static final String EXTRA_ACCOUNT = "account_filter";

    /** Movement-direction filter: all transactions, deposits only, or withdrawals only. */
    static final int DIR_ALL = 0;
    static final int DIR_DEPOSIT = 1;
    static final int DIR_WITHDRAWAL = -1;

    /** Date-range filter: the clearly-bounded presets plus a hand-entered range. CUSTOM is set
     *  whenever the from/to fields were typed directly, so the preset chips stop highlighting. */
    static final int RANGE_ALL = 0;
    static final int RANGE_TODAY = 1;
    static final int RANGE_MONTH = 2;
    static final int RANGE_YEAR = 3;
    static final int RANGE_CUSTOM = 4;

    private int bg, card, muted, accent, fg, divider, negativeColor, positiveColor;
    private int todayColor, monthColor, yearColor;
    private int heroTop, heroBottom, rail, openBg, chipBg;
    private int depBg, depFg, witBg, witFg, badgeFg, badgeBg;
    private final int[] bankColors = {
        Color.rgb(14, 165, 233), Color.rgb(139, 92, 246),
        Color.rgb(16, 185, 129), Color.rgb(245, 158, 11),
        Color.rgb(244, 63, 94), Color.rgb(20, 184, 166)
    };
    private LinearLayout body;
    private LockOverlay lockOverlay;
    private JalaliCalendar todayJalali, yesterdayJalali;
    /** Optional canonical bank name; when set, only that bank's transactions are shown. */
    private String bankFilter;

    /** Optional account number (only meaningful alongside {@link #bankFilter}); when set, only
     *  transactions of that account of the bank are shown. */
    private String accountFilter;

    /** The active direction and date bounds, applied before the transactions are grouped so the hero
     *  and the breakdown always match what is on screen. Starts from {@link Filter#ALL} on every open
     *  and survives rotation through the saved state; never persisted across sessions. */
    private Filter filter = Filter.ALL;

    /** The filter controls row (direction segment above the date presets), rebuilt by every render
     *  so its highlight and labels always mirror {@link #filter}. */
    private LinearLayout filterBar;

    /** Watches for new bank SMS while the screen is open, triggering a silent history re-scan. */
    private ContentObserver smsObserver;

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

    /** Converts an sp value to px honouring the device density and system font scale. */
    private float sp(float valueSp) {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, valueSp, getResources().getDisplayMetrics());
    }

    /** Fits the view's single-line text to the available width by measuring the actual glyphs, so
     *  sums can never overflow their column or slide under a neighbour at any font scale. Sizes up
     *  to {@code maxSp} sp where there is room, and lets the text shrink all the way down until it
     *  fits — if even the {@code minSp} floor is too wide the text keeps shrinking, because a
     *  slightly smaller digit is always better than a clipped one. When {@code capDp} is positive
     *  the text is fitted to that explicit width; otherwise it is fitted to whatever the parent
     *  actually leaves for it, which is what keeps header sums on-screen at large fonts. */
    private void fitToWidth(TextView v, int maxSp, int minSp, int capDp) {
        v.setSingleLine(true);
        v.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
        v.addOnLayoutChangeListener(
            (view, l, t, r, b, ol, ot, or, ob) -> applyFit(v, maxSp, minSp, capDp));
        v.post(() -> applyFit(v, maxSp, minSp, capDp));
    }

    private void applyFit(TextView v, int maxSp, int minSp, int capDp) {
        int avail;
        View p = v.getParent() instanceof View ? (View) v.getParent() : null;
        boolean fills = v.getLayoutParams() instanceof LinearLayout.LayoutParams
            && ((LinearLayout.LayoutParams) v.getLayoutParams()).width
                == LinearLayout.LayoutParams.MATCH_PARENT;
        if (capDp > 0) {
            avail = dp(capDp);
        } else if (fills && p != null && p.getWidth() > 0) {
            // A fill-width view (hero total, stat labels/values) spans the whole column, so its
            // own laid-out width is the budget — using parent-relative maths here would read as
            // zero in RTL and skip the fit entirely.
            avail = v.getWidth() - v.getCompoundPaddingLeft() - v.getCompoundPaddingRight();
        } else if (p != null && p.getWidth() > 0) {
            // Space still left for a wrap-content sum on the reading side of its parent, regardless
            // of how wide its siblings already are (key for RTL too).
            if (isRtl()) {
                avail = v.getLeft() - p.getPaddingLeft();
            } else {
                avail = p.getWidth() - p.getPaddingRight() - v.getLeft();
            }
            avail -= v.getCompoundPaddingLeft() + v.getCompoundPaddingRight();
            // A wrap-content sum can be measured overlarge on its first pass (pushing past the
            // content edge), leaving zero or negative room here; never bail on that — hand it the
            // widest slice that still keeps it inside the card so the text can shrink into place.
            if (avail <= 0) {
                int content = p.getWidth() - p.getPaddingLeft() - p.getPaddingRight();
                avail = Math.max(1, content / 3);
            }
        } else {
            avail = v.getWidth() - v.getCompoundPaddingLeft() - v.getCompoundPaddingRight();
        }
        if (avail <= 0) return;
        String s = v.getText().toString();
        if (s.isEmpty()) return;
        android.graphics.Paint paint = new android.graphics.Paint(v.getPaint());
        float maxPx = sp(maxSp);
        float minPx = sp(minSp);
        paint.setTextSize(maxPx);
        if (paint.measureText(s) <= avail) {
            fitPx(v, maxPx);
            return;
        }
        paint.setTextSize(minPx);
        if (paint.measureText(s) <= avail) {
            // Binary search the largest size (in px) that still fits.
            float lo = minPx, hi = maxPx;
            for (int i = 0; i < 16; i++) {
                float mid = (lo + hi) / 2f;
                paint.setTextSize(mid);
                if (paint.measureText(s) <= avail) lo = mid; else hi = mid;
            }
            fitPx(v, lo);
            return;
        }
        // Even the floor is too wide: shrink below it until the digits fit completely.
        float size = minPx;
        paint.setTextSize(size);
        while (size > 1f && paint.measureText(s) > avail) {
            size *= 0.95f;
            paint.setTextSize(size);
        }
        fitPx(v, size);
    }

    /** Applies a final pixel text size that actually differs from the current one, so repeated
     *  layout passes converge instead of re-queueing changes forever. */
    private void fitPx(TextView v, float px) {
        if (Math.abs(v.getTextSize() - px) < 0.5f) return;
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
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
            // Track the seeded flag explicitly: the user may have deliberately collapsed every
            // level, so empty sets must not trigger a fresh force-expansion on the next rotation.
            expandedSeeded = state.getBoolean(KEY_EXPANDED_SEEDED, false);
            pendingScroll = state.getInt(KEY_SCROLL_Y, 0);
            restoreFilter(state);
        }
        bankFilter = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_BANK);
        accountFilter = bankFilter == null ? null
            : (getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ACCOUNT));
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
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bg));
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
        FrameLayout host = new FrameLayout(this);
        setContentView(host);
        host.addView(root, new FrameLayout.LayoutParams(-1, -1));

        root.addView(buildHeader(), margin(0, 0, 0, 14));
        filterBar = new LinearLayout(this);
        filterBar.setOrientation(LinearLayout.VERTICAL);
        root.addView(filterBar, margin(0, 0, 0, 12));
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scrollView = new ScrollView(this);
        scrollView.addView(body, new ScrollView.LayoutParams(-1, -1));
        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1));

        lockOverlay = new LockOverlay(this);
        lockOverlay.setUnlockListener(this::updateSecureFlag);
        lockOverlay.setCancelListener(() -> lockOverlay.hide());
        host.addView(lockOverlay, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        lockOverlay.setVisibility(View.GONE);

        updateSecureFlag();
        render();
        if (pendingScroll > 0) {
            int offset = pendingScroll;
            scrollView.post(() -> scrollView.scrollTo(0, offset));
            pendingScroll = 0;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        LockManager.registerActivityStart(this);
        if (LockManager.isEnabled(this) && LockManager.isSessionLocked()) {
            lockOverlay.showLock();
        } else {
            lockOverlay.hide();
        }
        updateSecureFlag();
    }

    @Override
    protected void onStop() {
        if (LockManager.isEnabled(this) && LockManager.registerActivityStop()) {
            lockOverlay.showLock();
            lockOverlay.setAutoFingerprintEnabled(false);
        } else {
            lockOverlay.hide();
        }
        updateSecureFlag();
        super.onStop();
    }

    /** For as long as the lock is enabled the screen content stays hidden from recents and
     *  screenshots, regardless of the current unlock state — see {@link MainActivity}. */
    private void updateSecureFlag() {
        if (LockManager.isEnabled(this)) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
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

    /** The sticky top bar: back chevron, the screen title and, per-bank, the bank badge. */
    private LinearLayout buildHeader() {
        boolean rtl = isRtl();
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = text(rtl ? "›" : "‹", 24, fg);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(getString(R.string.history_back));
        back.setBackground(ripple(rounded(chipBg, 20)));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2);
        titleParams.setMarginStart(dp(10));
        if (bankFilter != null) {
            // A bank or account view: the badge plus the bank's name identifies whose filtered
            // history this is; the chip flags a single account (else the whole bank), so an
            // account's history reads as a first-class scope, exactly like another bank's.
            View badge = bankBadge(bankFilter);
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(32), dp(32));
            badgeLp.setMarginStart(dp(4));
            bar.addView(badge, badgeLp);
            TextView title = text(BankRules.displayName(this, bankFilter), 22, fg, MEDIUM);
            bar.addView(title, titleParams);
            String chipText = accountFilter != null
                ? getString(R.string.account_label) + " " + digits(accountFilter)
                : getString(R.string.history_bank_chip);
            TextView chip = text(chipText, 11, badgeFg, MEDIUM);
            chip.setMaxLines(1);
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.setPadding(dp(8), dp(3), dp(8), dp(3));
            chip.setBackground(rounded(badgeBg, 9));
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-2, -2);
            chipParams.setMarginStart(dp(8));
            bar.addView(chip, chipParams);
        } else {
            TextView title = text(getString(R.string.history_title), 22, fg, MEDIUM);
            bar.addView(title, titleParams);
        }

        LinearLayout.LayoutParams barSpacer = new LinearLayout.LayoutParams(0, 0, 1);
        bar.addView(new View(this), barSpacer);
        return bar;
    }

    // ====================================================================
    // Filter controls
    // ====================================================================

    /** Rebuilds the filter bar to mirror {@link #filter}: the movement-direction segment above the
     *  date presets and a custom-range chip, plus a one-line summary when a custom range is active. */
    private void rebuildFilterBar() {
        filterBar.removeAllViews();
        filterBar.addView(directionSegment());

        LinearLayout dateRow = new LinearLayout(this);
        dateRow.setOrientation(LinearLayout.HORIZONTAL);
        addFilterChip(dateRow, getString(R.string.history_total), RANGE_ALL, true,
            () -> applyRange(RANGE_ALL));
        addFilterChip(dateRow, getString(R.string.history_today), RANGE_TODAY, true,
            () -> applyRange(RANGE_TODAY));
        addFilterChip(dateRow, getString(R.string.history_this_month), RANGE_MONTH, true,
            () -> applyRange(RANGE_MONTH));
        addFilterChip(dateRow, getString(R.string.history_this_year), RANGE_YEAR, true,
            () -> applyRange(RANGE_YEAR));
        addFilterChip(dateRow, getString(R.string.history_filter_custom), RANGE_CUSTOM, true,
            this::customRangeDialog);
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(-1, -2);
        dateLp.topMargin = dp(8);
        filterBar.addView(dateRow, dateLp);

        if (filter.rangePreset == RANGE_CUSTOM && (filter.from != null || filter.to != null)) {
            TextView summary = text(customRangeSummary(), 12, muted);
            LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(-1, -2);
            sumLp.topMargin = dp(6);
            filterBar.addView(summary, sumLp);
        }
    }

    /** Rebuilds the per-bank account chips: "All accounts" plus one chip per account the bank has
     *  transactions for. The row only appears in the per-bank view and derives from the whole bank's
     *  history (before the direction/date filters), so its choices stay stable while narrowing. */
    /** The three-way movement segment: All / Deposits / Withdrawals, the active choice highlighted
     *  as an accent pill inside a quiet strip. */
    private LinearLayout directionSegment() {
        LinearLayout seg = new LinearLayout(this);
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        seg.setBackground(rounded(chipBg, 14));
        addSegmentChip(seg, getString(R.string.history_filter_all), DIR_ALL, () -> applyDirection(DIR_ALL));
        addSegmentChip(seg, getString(R.string.history_filter_deposits), DIR_DEPOSIT,
            () -> applyDirection(DIR_DEPOSIT));
        addSegmentChip(seg, getString(R.string.history_filter_withdrawals), DIR_WITHDRAWAL,
            () -> applyDirection(DIR_WITHDRAWAL));
        return seg;
    }

    private void addSegmentChip(LinearLayout host, String label, int id, Runnable action) {
        boolean selected = filter.direction == id;
        TextView chip = text(label, 12, selected ? Color.WHITE : fg, MEDIUM);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setAutoSizeTextTypeWithDefaults(android.widget.TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM);
        chip.setPadding(dp(6), dp(8), dp(6), dp(8));
        chip.setBackground(rounded(selected ? accent : chipBg, 11));
        chip.setContentDescription(label);
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setOnClickListener(v -> action.run());
        host.addView(chip, new LinearLayout.LayoutParams(0, -2, 1));
    }

    /** One date chip that highlights when its {@code id} is the active preset and otherwise sits as
     *  a quiet toggle. The chips share the row width, so labels stay on-screen at any font scale. */
    private void addFilterChip(LinearLayout host, String label, int id, boolean weight, Runnable action) {
        boolean selected = filter.rangePreset == id;
        TextView chip = text(label, 12, selected ? Color.WHITE : fg, MEDIUM);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setAutoSizeTextTypeWithDefaults(android.widget.TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM);
        chip.setPadding(dp(4), dp(7), dp(4), dp(7));
        chip.setBackground(rounded(selected ? accent : chipBg, 10));
        chip.setContentDescription(label);
        if (action != null) {
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setOnClickListener(v -> action.run());
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(weight ? 0 : -2, -2,
            weight ? 1 : 0);
        lp.setMarginStart(dp(2));
        lp.setMarginEnd(dp(2));
        host.addView(chip, lp);
    }

    private void applyDirection(int direction) {
        filter = filter.withDirection(direction);
        render();
    }

    /** Applies one of the clearly-bounded date presets, recomputing its bounds against "now". */
    private void applyRange(int preset) {
        filter = rangePreset(filter, preset, nowJalali());
        render();
    }

    /** The custom-range dialog: a visual Persian month calendar for tapping From and To instead of
     *  typing. Opening it again restores the current bounds, ranged-selected, and applying with a
     *  single picked day keeps that bound open-ended (matching the old optional text fields). */
    private void customRangeDialog() {
        final RangePicker picker = new RangePicker();

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setLayoutDirection(isRtl() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        wrap.setPadding(dp(24), dp(8), dp(24), 0);
        wrap.addView(picker.status, new LinearLayout.LayoutParams(-1, -2));

        // The arrows land at the reading-start edge: next on the left in LTR, mirroring for RTL.
        LinearLayout monthRow = new LinearLayout(this);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1);
        titleLp.setMarginStart(dp(8));
        titleLp.setMarginEnd(dp(8));
        monthRow.addView(picker.prev, new LinearLayout.LayoutParams(-2, -2));
        monthRow.addView(picker.title, titleLp);
        monthRow.addView(picker.next, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams monthLp = new LinearLayout.LayoutParams(-1, -2);
        monthLp.topMargin = dp(8);
        wrap.addView(monthRow, monthLp);

        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(-1, -2);
        gridLp.topMargin = dp(6);
        wrap.addView(picker.grid, gridLp);

        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.history_filter_custom_range_title))
            .setView(wrap)
            .setNegativeButton(getString(R.string.lock_cancel), null)
            .setPositiveButton(getString(R.string.history_filter_apply), null)
            .create();
        dlg.setOnShowListener(d -> dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v -> {
                // Neither day picked degenerates to the unbounded All-time preset.
                if (picker.picked[0] == null && picker.picked[1] == null) {
                    filter = Filter.ALL;
                } else {
                    filter = new Filter(filter.direction, RANGE_CUSTOM,
                        picker.picked[0], picker.picked[1]);
                }
                dlg.dismiss();
                render();
            }));
        dlg.show();
    }

    /** The From/To month-grid picker used inside the custom-range dialog. Navigate Persian months
     *  with the arrows, tap a day for From and a day for To; tapping while a range is closed starts
     *  a fresh From pick, and a To tapped before From swaps the bounds so the range keeps its order. */
    private final class RangePicker {
        final JalaliCalendar[] picked = new JalaliCalendar[]{filter.from, filter.to};
        final LinearLayout grid = new LinearLayout(HistoryActivity.this);
        final TextView title = text("", 14, fg, MEDIUM);
        final TextView status = text("", 12.5f, muted);
        final TextView prev = navButton("\u2039");
        final TextView next = navButton("\u203A");
        int viewYear;
        int viewMonth;

        RangePicker() {
            JalaliCalendar start = picked[0] != null ? picked[0] : picked[1];
            if (start == null) start = nowJalali();
            viewYear = start.year;
            viewMonth = start.month;
            grid.setOrientation(LinearLayout.VERTICAL);
            render();
        }

        TextView navButton(String arrow) {
            TextView b = text(arrow, 18, fg, MEDIUM);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(12), dp(2), dp(12), dp(2));
            b.setBackground(rounded(chipBg, 10));
            b.setContentDescription(getString("\u2039".equals(arrow)
                ? R.string.history_prev_month : R.string.history_next_month));
            return b;
        }

        /** Rebuilds the month title, the prev/next affordance and the day grid for the viewed month. */
        void render() {
            boolean fa = LocaleHelper.isPersian(HistoryActivity.this);
            title.setText(monthName(viewMonth) + " "
                + (fa ? faDigits(viewYear) : Integer.toString(viewYear)));
            title.setGravity(Gravity.CENTER);
            bindNav(prev, viewYear > 1100 || viewYear == 1100 && viewMonth > 1, -1);
            bindNav(next, viewYear < 1700 || viewYear == 1700 && viewMonth < 12, 1);
            grid.removeAllViews();

            String[] weekdays = weekdayLabels();
            LinearLayout weekRow = new LinearLayout(HistoryActivity.this);
            for (String w : weekdays) {
                TextView h = text(w, 11, muted);
                h.setGravity(Gravity.CENTER);
                weekRow.addView(h, new LinearLayout.LayoutParams(0, -2, 1));
            }
            grid.addView(weekRow, new LinearLayout.LayoutParams(-1, -2));

            int rangeFill = (accent & 0x00FFFFFF) | 0x26000000;
            JalaliCalendar today = nowJalali();
            int firstDay = weekdayIndex(JalaliCalendar.of(viewYear, viewMonth, 1));
            int days = JalaliCalendar.daysInMonth(viewYear, viewMonth);
            for (int offset = 0; offset < firstDay + days; offset += 7) {
                LinearLayout row = new LinearLayout(HistoryActivity.this);
                for (int col = 0; col < 7; col++) {
                    int d = offset + col - firstDay + 1;
                    TextView cell = d < 1 || d > days
                        ? text("", 0, fg) : dayCell(JalaliCalendar.of(viewYear, viewMonth, d), today);
                    row.addView(cell, new LinearLayout.LayoutParams(0, dp(48), 1));
                }
                grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            updateStatus();
        }

        /** One tappable day, highlighted as a range bound, today's outline, or the ranged tint. */
        TextView dayCell(JalaliCalendar day, JalaliCalendar today) {
            boolean fa = LocaleHelper.isPersian(HistoryActivity.this);
            boolean fromSel = picked[0] != null && picked[0].year == day.year
                && picked[0].month == day.month && picked[0].day == day.day;
            boolean toSel = picked[1] != null && picked[1].year == day.year
                && picked[1].month == day.month && picked[1].day == day.day;
            boolean inRange = picked[0] != null && picked[1] != null
                && compareDate(picked[0], day) <= 0 && compareDate(day, picked[1]) <= 0;
            boolean isToday = today.year == day.year
                && today.month == day.month && today.day == day.day;
            TextView cell = text(fa ? faDigits(day.day) : Integer.toString(day.day), 12.5f,
                fromSel || toSel ? Color.WHITE : fg);
            cell.setGravity(Gravity.CENTER);
            if (fromSel || toSel) {
                cell.setBackground(rounded(accent, 10));
            } else if (isToday) {
                cell.setBackground(roundedStroke(Color.TRANSPARENT, 10, accent));
            } else if (inRange) {
                int rangeFill = (accent & 0x00FFFFFF) | 0x26000000;
                cell.setBackground(rounded(rangeFill, 10));
            }
            cell.setContentDescription(persianDate(day)
                + (fromSel ? " \u2014 " + getString(R.string.history_range_start)
                    : toSel ? " \u2014 " + getString(R.string.history_range_end)
                    : inRange ? " \u2014 " + getString(R.string.history_range_selected) : ""));
            cell.setClickable(true);
            cell.setFocusable(true);
            cell.setOnClickListener(v -> {
                pickDay(picked, day);
                render();
            });
            return cell;
        }

        void bindNav(TextView arrow, boolean canStep, int delta) {
            arrow.setEnabled(canStep);
            arrow.setAlpha(canStep ? 1f : .35f);
            arrow.setClickable(canStep);
            arrow.setOnClickListener(canStep ? v -> step(delta) : null);
        }

        void step(int delta) {
            viewMonth += delta;
            if (viewMonth < 1) {
                viewMonth = 12;
                viewYear--;
            } else if (viewMonth > 12) {
                viewMonth = 1;
                viewYear++;
            }
            render();
        }

        /** Tells the user what to pick next, or shows the closing summary once the range is full. */
        void updateStatus() {
            if (picked[0] == null) {
                status.setText(R.string.history_filter_pick_from);
            } else if (picked[1] == null) {
                status.setText(getString(R.string.history_filter_pick_to, compactDate(picked[0])));
            } else {
                status.setText(getString(R.string.history_filter_range_summary,
                    compactDate(picked[0]), compactDate(picked[1])));
            }
        }
    }

    /** The tap rule for the range calendar, kept pure so the tests cover it: the first pick sets
     *  From, the next sets To, a tap while both are set starts a fresh From, and a To tapped before
     *  From swaps the bounds so From always precedes To. */
    static void pickDay(JalaliCalendar[] picked, JalaliCalendar day) {
        if (picked[0] == null || picked[1] != null) {
            picked[0] = day;
            picked[1] = null;
        } else if (compareDate(day, picked[0]) < 0) {
            picked[1] = picked[0];
            picked[0] = day;
        } else {
            picked[1] = day;
        }
    }

    /** Weekday of a Persian date as 0..6 for Saturday..Friday (the grid's leading column). */
    static int weekdayIndex(JalaliCalendar jc) {
        int[] g = jc.toGregorian();
        Calendar c = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(g[0], g[1] - 1, g[2]);
        return (c.get(Calendar.DAY_OF_WEEK) - Calendar.SATURDAY + 7) % 7;
    }

    /** One-line description of the active custom range, e.g. "From 1403/12/1 to 1404/2/5". */
    private String customRangeSummary() {
        if (filter.from != null && filter.to != null) {
            return getString(R.string.history_filter_range_summary,
                compactDate(filter.from), compactDate(filter.to));
        }
        if (filter.from != null) {
            return getString(R.string.history_filter_from_without_to, compactDate(filter.from));
        }
        return getString(R.string.history_filter_to_without_from, compactDate(filter.to));
    }

    // ====================================================================
    // Expansion state
    // ====================================================================

    private static final String KEY_EXPANDED_YEARS = "expanded_years";
    private static final String KEY_EXPANDED_MONTHS = "expanded_months";
    private static final String KEY_EXPANDED_DAYS = "expanded_days";
    private static final String KEY_EXPANDED_SEEDED = "expanded_seeded";
    private static final String KEY_SCROLL_Y = "scroll_y";
    private static final String KEY_FILTER_DIRECTION = "filter_direction";
    private static final String KEY_FILTER_RANGE = "filter_range";
    private static final String KEY_FILTER_FROM_YEAR = "filter_from_year";
    private static final String KEY_FILTER_FROM_MONTH = "filter_from_month";
    private static final String KEY_FILTER_FROM_DAY = "filter_from_day";
    private static final String KEY_FILTER_TO_YEAR = "filter_to_year";
    private static final String KEY_FILTER_TO_MONTH = "filter_to_month";
    private static final String KEY_FILTER_TO_DAY = "filter_to_day";

    /** The sets of year, month and day keys currently expanded in the breakdown. The current year,
     *  current month and its days start expanded. */
    private final Set<String> expandedYears = new java.util.LinkedHashSet<>();
    private final Set<String> expandedMonths = new java.util.LinkedHashSet<>();
    private final Set<String> expandedDays = new java.util.LinkedHashSet<>();

    /** Cached reference to the year list so year-header taps can re-render the whole section. */
    private List<YearGroup> allYears;

    /** Scroll container, kept so the list position survives rotation. */
    private ScrollView scrollView;

    /** Scroll offset pending restore until the rebuilt list is laid out. */
    private int pendingScroll;

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
        outState.putBoolean(KEY_EXPANDED_SEEDED, expandedSeeded);
        if (scrollView != null) outState.putInt(KEY_SCROLL_Y, scrollView.getScrollY());
        outState.putInt(KEY_FILTER_DIRECTION, filter.direction);
        outState.putInt(KEY_FILTER_RANGE, filter.rangePreset);
        writeDate(outState, KEY_FILTER_FROM_YEAR, KEY_FILTER_FROM_MONTH, KEY_FILTER_FROM_DAY, filter.from);
        writeDate(outState, KEY_FILTER_TO_YEAR, KEY_FILTER_TO_MONTH, KEY_FILTER_TO_DAY, filter.to);
    }

    /** Persists one filter date bound as (year, month, day), leaving the keys out when unbounded. */
    private static void writeDate(Bundle outState, String yKey, String mKey, String dKey,
            JalaliCalendar jc) {
        if (jc == null) return;
        outState.putInt(yKey, jc.year);
        outState.putInt(mKey, jc.month);
        outState.putInt(dKey, jc.day);
    }

    /** Rebuilds {@link #filter} from the saved state, staying on {@link Filter#ALL} when a fresh
     *  screen (no state, or state saved before filters existed) is shown. */
    private void restoreFilter(Bundle state) {
        if (!state.containsKey(KEY_FILTER_DIRECTION)) return;
        JalaliCalendar from = readDate(state, KEY_FILTER_FROM_YEAR, KEY_FILTER_FROM_MONTH, KEY_FILTER_FROM_DAY);
        JalaliCalendar to = readDate(state, KEY_FILTER_TO_YEAR, KEY_FILTER_TO_MONTH, KEY_FILTER_TO_DAY);
        filter = new Filter(state.getInt(KEY_FILTER_DIRECTION, DIR_ALL),
            state.getInt(KEY_FILTER_RANGE, RANGE_ALL), from, to);
    }

    private static JalaliCalendar readDate(Bundle state, String yKey, String mKey, String dKey) {
        int y = state.getInt(yKey, -1), m = state.getInt(mKey, -1), d = state.getInt(dKey, -1);
        return y >= 0 && m >= 1 && d >= 1 ? JalaliCalendar.of(y, m, d) : null;
    }

    private final Runnable onHistoryChanged = () -> runOnUiThread(this::render);

    @Override
    protected void onResume() {
        super.onResume();
        LockManager.cancelPendingLock();
        BalanceData.addHistoryListener(onHistoryChanged);
        registerSmsObserver();
        // The delayed lock may have engaged while we were paused on a ROM that skipped onStop;
        // reflect it now that we are back in the foreground.
        if (LockManager.isEnabled(this) && LockManager.isSessionLocked()
                && lockOverlay != null && !lockOverlay.isShowing()) {
            lockOverlay.showLock();
        }
        // Re-scan in the background (a no-op if a scan is already running) so messages that
        // arrived while the screen was closed are reflected as soon as it opens.
        new Thread(() -> BalanceData.scanHistory(HistoryActivity.this)).start();
    }

    @Override
    protected void onPause() {
        unregisterSmsObserver();
        if (LockManager.isEnabled(this)) {
            // Arm the lock now so it engages even on ROMs that delay or skip onStop; the next
            // screen's start cancels it, so navigating between our own screens never locks.
            LockManager.scheduleLock(this);
            if (LockManager.isSessionLocked()) {
                lockOverlay.showLock();
                lockOverlay.setAutoFingerprintEnabled(false);
            }
        }
        updateSecureFlag();
        BalanceData.removeHistoryListener(onHistoryChanged);
        super.onPause();
    }

    /** Re-scans the SMS inbox the moment new bank messages arrive, so the history the user is
     *  looking at stays current without a refresh control. A scan already in flight is a no-op. */
    private void registerSmsObserver() {
        if (smsObserver != null) return;
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return;
        smsObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) { onChange(selfChange, null); }
            @Override public void onChange(boolean selfChange, Uri uri) {
                new Thread(() -> BalanceData.scanHistory(HistoryActivity.this)).start();
            }
        };
        getContentResolver().registerContentObserver(
            Telephony.Sms.Inbox.CONTENT_URI, true, smsObserver);
    }

    private void unregisterSmsObserver() {
        if (smsObserver != null) {
            getContentResolver().unregisterContentObserver(smsObserver);
            smsObserver = null;
        }
    }

    // ====================================================================
    // Screen rendering
    // ====================================================================

    /** Re-reads the saved history, applies the current filters and rebuilds the whole screen from it:
     *  the filter bar first (so its chips mirror the active filter), then the hero and the breakdown
     *  computed over the filtered list, so every figure on screen reflects exactly what is shown. */
    private void render() {
        refreshDates();
        List<Transaction> all = BalanceData.readTransactions(this);
        List<Transaction> bankTxs = bankFilter == null ? all : filterByBank(all, bankFilter);
        List<Transaction> acctTxs = accountFilter == null ? bankTxs : filterByAccount(bankTxs, accountFilter);
        List<Transaction> txs = applyFilters(acctTxs, filter);
        rebuildFilterBar();
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
    }

    /** Returns only the transactions whose bank equals {@code bank}, preserving input order.
     *  Kept static so the instrumented tests can cover the per-bank filter directly. */
    static List<Transaction> filterByBank(List<Transaction> txs, String bank) {
        List<Transaction> only = new ArrayList<>();
        for (Transaction t : txs) if (bank.equals(t.bank)) only.add(t);
        return only;
    }

    /** Returns only the transactions whose account equals {@code account}, preserving input order.
     *  Kept static so the instrumented tests can cover the per-account filter directly. */
    static List<Transaction> filterByAccount(List<Transaction> txs, String account) {
        List<Transaction> only = new ArrayList<>();
        for (Transaction t : txs) if (account.equals(t.account)) only.add(t);
        return only;
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
        String empty;
        if (filter.isActive()) {
            // A filter may hide every transaction even though history exists.
            empty = getString(R.string.history_empty_filtered);
        } else if (accountFilter != null) {
            empty = getString(R.string.history_empty_account);
        } else if (bankFilter != null) {
            empty = getString(R.string.history_empty_bank, BankRules.displayName(this, bankFilter));
        } else {
            empty = getString(R.string.history_empty);
        }
        TextView msg = text(empty, 14, muted);
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
        label.setLetterSpacing(label.getResources().getConfiguration().getLayoutDirection()
            == View.LAYOUT_DIRECTION_LTR ? 0.08f : 0f);
        top.addView(label, labelParams);
        hero.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // The lifetime total.
        TextView total = bold(signedToman(lists.total), 32, valueColor(lists.total));
        total.setGravity(Gravity.START);
        fitToWidth(total, 32, 12, 0);
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

    /** One period stat: colored label over the signed net value, auto-sized to its column. The
     *  label and value shrink to the available column width (also covering system font scale),
     *  and grow up to their maximum only where room allows, so sums stay legible on every screen. */
    private LinearLayout statsCell(String label, long value, int color) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView l = text(label, 12, color, MEDIUM);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        fitToWidth(l, 12, 9, 0);
        cell.addView(l, new LinearLayout.LayoutParams(-1, -2));
        TextView v = bold(signedToman(value), 20, color);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        fitToWidth(v, 20, 8, 0);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, -2);
        vp.topMargin = dp(5);
        cell.addView(v, vp);
        return cell;
    }

    /** A list-style section header. Letter-spaced only in left-to-right layouts: in the Persian
     *  interface extra letter spacing would break the joining of the script's characters. */
    private TextView sectionLabel(String s) {
        TextView t = text(s, 12, muted, MEDIUM);
        t.setLetterSpacing(t.getResources().getConfiguration().getLayoutDirection()
            == View.LAYOUT_DIRECTION_LTR ? 0.09f : 0f);
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
        fitToWidth(sum, 16, 11, 0);
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
                tp.setMarginEnd(dp(30));
                tp.bottomMargin = dp(10);
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
        fitToWidth(sum, 14, 10, 0);
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
        fitToWidth(sum, 13, 10, 0);
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

    /** One movement: bank badge, bank name with time, the account number it hit, and the signed
     *  amount. In a per-bank view every row is the same bank, so the time alone identifies it and
     *  the badge/name are dropped — but the account number stays in both scopes, because a movement's
     *  account is meaningful even in the combined view. */
    private LinearLayout txRow(Transaction t) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPaddingRelative(dp(4), dp(3), dp(4), dp(3));

        boolean perBank = bankFilter != null;
        if (!perBank) {
            View badge = bankBadge(t.bank);
            row.addView(badge, new LinearLayout.LayoutParams(dp(30), dp(30)));
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        if (!perBank) {
            TextView name = text(BankRules.displayName(this, t.bank), 13, fg);
            col.addView(name, new LinearLayout.LayoutParams(-2, -2));
        }
        TextView time = text(timeText(t.date), perBank ? 13 : 11, perBank ? fg : muted);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        if (!perBank) tp.topMargin = dp(2);
        col.addView(time, tp);
        if (t.account != null) {
            TextView account = text(getString(R.string.account_label) + " " + digits(t.account),
                10.5f, muted);
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
            ap.topMargin = dp(1);
            col.addView(account, ap);
        }
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, -2, 1);
        colLp.setMarginStart(dp(9));
        row.addView(col, colLp);

        TextView amt = bold(signedToman(t.amount), 13, valueColor(t.amount));
        fitToWidth(amt, 13, 10, 0);
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

    /** The movement-direction and date bounds applied to the transactions before grouping. A screen
     *  opens on {@link #ALL} and, like the per-bank filter, narrows everything it displays. */
    static final class Filter {
        static final Filter ALL = new Filter(DIR_ALL, RANGE_ALL, null, null);

        final int direction;
        final int rangePreset;
        /** Inclusive lower bound, or null for unbounded. */
        final JalaliCalendar from;
        /** Inclusive upper bound, or null for unbounded. */
        final JalaliCalendar to;

        Filter(int direction, int rangePreset, JalaliCalendar from, JalaliCalendar to) {
            this.direction = direction;
            this.rangePreset = rangePreset;
            this.from = from;
            this.to = to;
        }

        /** Whether anything is dropped relative to the unfiltered list. */
        boolean isActive() {
            return direction != DIR_ALL || from != null || to != null;
        }

        Filter withDirection(int direction) {
            return new Filter(direction, rangePreset, from, to);
        }

        Filter withRange(int preset, JalaliCalendar from, JalaliCalendar to) {
            return new Filter(direction, preset, from, to);
        }
    }

    /** Keeps the transactions whose movement direction and Persian date fall inside {@code f}; a
     *  transaction on a boundary day is included. Never mutates the caller's list, so it composes
     *  safely after the per-bank filter for both the full and the per-bank screens. */
    static List<Transaction> applyFilters(List<Transaction> txs, Filter f) {
        List<Transaction> out = new ArrayList<>(txs.size());
        for (Transaction t : txs) {
            if (f.direction == DIR_DEPOSIT && t.amount <= 0) continue;
            if (f.direction == DIR_WITHDRAWAL && t.amount >= 0) continue;
            if (f.from != null || f.to != null) {
                int[] g = gDate(t.date);
                JalaliCalendar jc = JalaliCalendar.fromGregorian(g[0], g[1], g[2]);
                if (f.from != null && compareDate(jc, f.from) < 0) continue;
                if (f.to != null && compareDate(jc, f.to) > 0) continue;
            }
            out.add(t);
        }
        return out;
    }

    /** Replaces the date bounds with what the preset means on {@code now}; CUSTOM is never applied
     *  here — the dialog sets its own bounds. Kept static so the instrumented tests cover it. */
    static Filter rangePreset(Filter f, int preset, JalaliCalendar now) {
        switch (preset) {
            case RANGE_TODAY:
                return f.withRange(RANGE_TODAY, now, now);
            case RANGE_MONTH:
                return f.withRange(RANGE_MONTH,
                    JalaliCalendar.of(now.year, now.month, 1),
                    JalaliCalendar.of(now.year, now.month, JalaliCalendar.daysInMonth(now.year, now.month)));
            case RANGE_YEAR:
                return f.withRange(RANGE_YEAR,
                    JalaliCalendar.of(now.year, 1, 1),
                    JalaliCalendar.of(now.year, 12, JalaliCalendar.daysInMonth(now.year, 12)));
            default:
                return f.withRange(RANGE_ALL, null, null);
        }
    }

    /** Chronological order of two Persian dates, compared field by field (no round trip needed). */
    private static int compareDate(JalaliCalendar a, JalaliCalendar b) {
        if (a.year != b.year) return Integer.compare(a.year, b.year);
        if (a.month != b.month) return Integer.compare(a.month, b.month);
        return Integer.compare(a.day, b.day);
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

    /** The 7 weekday grid headings, Saturday first, in the app language. */
    private String[] weekdayLabels() {
        boolean fa = LocaleHelper.isPersian(this);
        return fa
            ? new String[]{"ش", "ی", "د", "س", "چ", "پ", "ج"}
            : new String[]{"Sa", "Su", "Mo", "Tu", "We", "Th", "Fr"};
    }

    /** Formats a Persian date in the app language, e.g. "Khordad 12 1403" / "۱۲ خرداد ۱۴۰۳". */
    private String persianDate(JalaliCalendar jc) {
        boolean fa = LocaleHelper.isPersian(this);
        if (fa) {
            return faDigits(jc.day) + " " + monthName(jc.month) + " " + faDigits(jc.year);
        }
        return monthName(jc.month) + " " + jc.day + " " + jc.year;
    }

    /** Formats a Persian date as the compact "y/m/d" used by the custom-range inputs and summary, in
     *  the app language's digits. */
    private String compactDate(JalaliCalendar jc) {
        String s = jc.year + "/" + jc.month + "/" + jc.day;
        return LocaleHelper.isPersian(this) ? faDigitsString(s) : s;
    }

    /** The movement's time of day as a compact "HH:mm" string in the app digits. */
    private String timeText(long date) {
        String s = new java.text.SimpleDateFormat("HH:mm", Locale.US)
            .format(new java.util.Date(date));
        return LocaleHelper.isPersian(this) ? faDigitsString(s) : s;
    }

    /** Converts a calendar number (year, day) to Persian digits without any thousands grouping —
     *  grouping separators belong to prices, not calendar numerals like "۱۴۰۳". */
    public static String faDigits(long n) {
        return faDigitsString(Long.toString(n));
    }

    public static String faDigitsString(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '0' && c <= '9') b.append((char) ('\u06f0' + c - '0'));
            else b.append(c);
        }
        return b.toString();
    }

    /** Account numbers follow the app language's digit rules, like the displayed amounts. */
    private String digits(String s) {
        return LocaleHelper.isPersian(this) ? faDigitsString(s) : s;
    }

    /** Formats a rial amount as a signed toman string, following the app language's digit rules. */
    private String signedToman(long n) {
        String mag = BalanceData.toman(this, Math.abs(n));
        if (n == 0) return mag;
        String sign = (n < 0 ? "\u2212" : "+");
        if (!LocaleHelper.isPersian(this)) return sign + mag;
        return "\u2066" + sign + mag + "\u2069";
    }
}