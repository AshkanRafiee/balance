package com.ashkanrafiee.balance;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Telephony;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
 * today, this month and this year, an all-time hero summary, plus a year-by-year breakdown that
 * drills down into months and expandable days, each carrying its own deposit/withdrawal
 * subtotals. Sums always reflect money <em>movements</em> (deposits minus withdrawals), never
 * remaining balances. All date boundaries follow the calendar system of the active region: the
 * Persian (Jalali) calendar for Iran, the Gregorian calendar for International.
 *
 * <p>The screen re-scans the SMS inbox whenever it opens and whenever a new bank message arrives
 * (a ContentObserver, like the main screen), silently re-rendering on completion.
 */
public final class HistoryActivity extends Activity {
    private static final String MONTH_TAG = "history_month";
    private static final String DAY_TAG = "history_day";
    private static final String YEAR_TAG = "history_year";

    /** Result code for the system file picker that picks where the CSV is saved. */
    private static final int REQ_EXPORT = 31;

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
    /** The amber pair reserved for unaccounted money. Reused from the stale-balance warning so the
     *  "we are not sure about this" reading is the app's own, in both themes. */
    private int warnFg, warnBg;
    private LinearLayout body;
    private LockOverlay lockOverlay;
    /** Draws the pull-to-refresh chip pinned to the true top of the screen — clear of the hero card
     *  and the rows — while the gesture and its state stay in the list's scroll view. */
    private PullIndicatorOverlay indicatorOverlay;
    /** Height of the status bar (px), captured by the insets listener so the overlay can place the
     *  chip just below it, mirroring the dashboard's anchored refresh circle. */
    private int statusInsetTop;
    /** The user's calendar system: true for the Persian (Jalali) calendar, false for Gregorian.
     *  Read once per screen, since the region can only change from the main screen. */
    private boolean iranCalendar = true;
    private CalDate today, yesterday;
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

    /** Coalesces bursty inbox-change notifications into a single background re-scan: inbox apps
     *  often touch many rows at once, and each touch would otherwise spawn its own scan thread. */
    private final Handler smsHandler = new Handler(Looper.getMainLooper());
    private boolean scanPending;

    /** Bumped on every render request; the finished render applies its result only if it is still
     *  the newest, so a quick filter change never gets overwritten by a stale slower build. */
    private int renderGen;

    /** The note map for the screen's current data, read once per render on the worker thread
     *  (decrypting and parsing the store once instead of once per visible row) and consumed only
     *  by the UI pass that rebuilds the tree. Replenished on every render, which any note edit
     *  triggers, so it never serves a stale snapshot. */
    private Map<String, String> notes;

    /** An account number is sensitive: it is copied to the clipboard only for the paste window,
     *  then cleared again unless the user copied something else in the meantime; the clear is keyed
     *  to the exact clip we placed, so the user's own later copy is never destroyed. Runs on its
     *  own static handler so it survives leaving the screen. */
    private static final long CLIP_CLEAR_MS = 15_000L;
    private static final Handler clipHandler = new Handler(Looper.getMainLooper());
    private Runnable clearClipRunnable;

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
        super.attachBaseContext(LocaleHelper.wrap(ThemeHelper.wrap(base)));
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
        warnFg = color(R.color.warn);
        warnBg = color(R.color.warn_bg);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bg));
        iranCalendar = RegionHelper.isIran(this);
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
            statusInsetTop = top;
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
        scrollView = new PullRefreshScrollView(this);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.addView(body, new ScrollView.LayoutParams(-1, -1));
        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1));

        indicatorOverlay = new PullIndicatorOverlay();
        scrollView.setIndicatorOverlay(indicatorOverlay);
        host.addView(indicatorOverlay, new FrameLayout.LayoutParams(-1, -1));

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

    @Override
    protected void onDestroy() {
        // Drop a pull-refresh ticker that may still be mid-animation (its own guards keep it from
        // touching a destroyed screen, but nothing should keep posting into a finished activity).
        if (scrollView != null) {
            scrollView.handler.removeCallbacks(scrollView.refreshTicker);
        }
        super.onDestroy();
    }

    /** For as long as the lock is enabled the screen content stays hidden from recents and
     *  screenshots, regardless of the current unlock state — see {@link MainActivity}. */
    private void updateSecureFlag() {
        if (LockManager.isEnabled(this)) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    }

    /** Refreshes the cached "today" and "yesterday" dates once per render, in the active calendar
     *  system (Persian for the Iran region, Gregorian for International). */
    private void refreshDates() {
        today = CalDate.today(iranCalendar);
        yesterday = CalDate.yesterday(iranCalendar);
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
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            bar.addView(title, titleParams);
            String chipText = accountFilter != null
                ? getString(R.string.account_label) + " " + digits(accountFilter)
                : getString(R.string.history_bank_chip);
            TextView chip = text(chipText, 11, badgeFg, MEDIUM);
            chip.setMaxLines(1);
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.setMinWidth(0);
            // Tighter sides than the plain chips: the copy glyph eats this chip's width too, and a
            // ten-digit number plus a long label has to fit beside it before the bar starts
            // ellipsizing the number itself.
            chip.setPadding(dp(6), dp(5), dp(6), dp(5));
            // The chip is the bar's flexible element: a very long account number makes the chip
            // shrink and ellipsize instead of pushing the export button out past the screen edge,
            // so the action stays visible no matter how long the account number is.
            if (accountFilter != null) {
                // An account chip carries the account number itself, so tapping it copies that
                // number — the one value here its user cannot easily type by hand.
                makeAccountChipCopyable(chip, digits(accountFilter));
            } else {
                chip.setBackground(rounded(badgeBg, 9));
            }
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(0, -2, 1);
            chipParams.setMarginStart(dp(8));
            bar.addView(chip, chipParams);
        } else {
            TextView title = text(getString(R.string.history_title), 22, fg, MEDIUM);
            bar.addView(title, titleParams);
            // With nothing else before the action, keep the title apart from the export button with
            // a flex spacer so the button still sits at the far end of the bar.
            LinearLayout.LayoutParams barSpacer = new LinearLayout.LayoutParams(0, 0, 1);
            bar.addView(new View(this), barSpacer);
        }

        TextView export = text(getString(R.string.history_export_label), 14, muted, MEDIUM);
        export.setGravity(Gravity.CENTER);
        export.setContentDescription(getString(R.string.history_export));
        export.setPadding(dp(14), 0, dp(14), 0);
        export.setBackground(ripple(rounded(chipBg, 20)));
        export.setOnClickListener(v -> startExport());
        LinearLayout.LayoutParams exportParams = new LinearLayout.LayoutParams(-2, dp(40));
        exportParams.setMarginStart(dp(6));
        bar.addView(export, exportParams);
        return bar;
    }

    // ====================================================================
    // Account-copy (account chip in the resolved-account header)
    // ====================================================================

    /** Turns the account chip into a copy target: tappable with a ripple, a small copy glyph, an
     *  accessibility description and a toast on use, so a plain tap lands the account number on the
     *  clipboard instead of leaving it to the user to type or re-read. */
    private void makeAccountChipCopyable(TextView chip, String account) {
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setContentDescription(getString(R.string.account_copy_cd));
        chip.setBackground(ripple(rounded(badgeBg, 9)));
        Drawable copy = getDrawable(R.drawable.ic_copy);
        if (copy != null) copy.mutate().setTint(badgeFg);
        chip.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, copy, null);
        chip.setOnClickListener(v -> copyAccount(account));
    }

    /** Copies the bare account number and schedules its guarded clear from the system clipboard. */
    private void copyAccount(String account) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(account, account));
        // Do not leave an account number readably in the system clipboard for any longer than the
        // paste window; clear it again once that has passed, unless the user copied something else
        // in the meantime (then that newer clip is left alone).
        if (clearClipRunnable != null) clipHandler.removeCallbacks(clearClipRunnable);
        clearClipRunnable = () -> {
            clearClipRunnable = null;
            try {
                CharSequence current = clipboard.hasPrimaryClip()
                    && clipboard.getPrimaryClip() != null
                    && clipboard.getPrimaryClip().getItemCount() > 0
                    ? clipboard.getPrimaryClip().getItemAt(0).getText() : null;
                if (account.equals(String.valueOf(current))) {
                    if (android.os.Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip();
                    else clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
                }
            } catch (Exception e) {
                // On Android 10+ a background process may be denied reading a clip another app has
                // taken; fail as cleared and keep the activity alive.
                android.util.Log.w("BalanceHistory", "clipboard read failed", e);
            }
        };
        clipHandler.postDelayed(clearClipRunnable, CLIP_CLEAR_MS);
        Toast.makeText(this, getString(R.string.toast_copied_account), Toast.LENGTH_SHORT).show();
    }

    // ====================================================================
    // CSV export
    // ====================================================================

    /** Asks the system file picker (SAF) for a location to write the CSV of the transactions the
     *  user currently sees into. */
    private void startExport() {
        LockManager.holdUnlock();
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType("text/csv");
        create.putExtra(Intent.EXTRA_TITLE, exportFileName());
        startActivityForResult(create, REQ_EXPORT);
    }

    private String exportFileName() {
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .format(new java.util.Date());
        return "balance-transactions-" + stamp + ".csv";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EXPORT && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            writeExport(data.getData());
        }
    }

    /** Builds the CSV of the on-screen transactions and writes it to the SAF uri on a worker
     *  thread. The current filters shape the snapshot (with none active that is the full history),
     *  and the store lock keeps it from ever racing a background scan mid-write. */
    private void writeExport(Uri uri) {
        new Thread(() -> {
            final int[] error = {0};
            try {
                final List<Transaction> txs;
                final java.util.Map<String, String> notes;
                final List<Residual> residuals;
                synchronized (BalanceData.class) {
                    txs = BalanceData.readTransactions(getApplicationContext());
                    notes = BalanceData.readNotes(getApplicationContext());
                    // Detected before narrowing, exactly as on screen, so the file reconciles with
                    // the totals the user just looked at.
                    residuals = Residual.between(txs);
                }
                List<Transaction> scope = txs;
                List<Residual> residualScope = residuals;
                if (bankFilter != null) {
                    scope = filterByBank(txs, bankFilter);
                    residualScope = filterResidualsByBank(residualScope, bankFilter);
                }
                if (accountFilter != null) {
                    scope = filterByAccount(scope, accountFilter);
                    residualScope = filterResidualsByAccount(residualScope, accountFilter);
                }
                List<Residual> residualOut = applyResidualFilters(residualScope, filter, iranCalendar);
                scope = applyFilters(scope, filter, iranCalendar);
                String csv = CsvExport.csv(getApplicationContext(), scope, residualOut, notes);
                OutputStream out = getContentResolver().openOutputStream(uri, "w");
                if (out == null) throw new IOException("no output stream");
                try {
                    out.write(csv.getBytes(StandardCharsets.UTF_8));
                } finally {
                    out.close();
                }
            } catch (Exception e) {
                error[0] = 1;
                android.util.Log.w("BalanceHistory", "csv export failed", e);
            }
            runOnUiThread(() -> Toast.makeText(this,
                getString(error[0] == 0
                    ? R.string.history_export_saved : R.string.history_export_failed),
                Toast.LENGTH_SHORT).show());
        }).start();
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
        filter = rangePreset(filter, preset, now(), iranCalendar);
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

    /** The From/To month-grid picker used inside the custom-range dialog. Navigate the active
     *  calendar's months with the arrows, tap a day for From and a day for To; tapping while a range
     *  is closed starts a fresh From pick, and a To tapped before From swaps the bounds so the
     *  range keeps its order. */
    private final class RangePicker {
        final CalDate[] picked = new CalDate[]{filter.from, filter.to};
        final LinearLayout grid = new LinearLayout(HistoryActivity.this);
        final TextView title = text("", 14, fg, MEDIUM);
        final TextView status = text("", 12.5f, muted);
        final TextView prev = navButton("\u2039");
        final TextView next = navButton("\u203A");
        int viewYear;
        int viewMonth;

        RangePicker() {
            CalDate start = picked[0] != null ? picked[0] : picked[1];
            if (start == null) start = now();
            viewYear = start.year;
            viewMonth = start.month;
            grid.setOrientation(LinearLayout.VERTICAL);
            title.setClickable(true);
            title.setFocusable(true);
            title.setContentDescription(getString(R.string.history_jump_title));
            title.setOnClickListener(v -> promptJump());
            render();
        }

        /** A single dialog to jump straight to any month and year of the active calendar instead of
         *  stepping month by month: a month dropdown plus a typeable year field. */
        void promptJump() {
            final List<String> months = new ArrayList<>();
            for (int m = 1; m <= 12; m++) months.add(monthName(m));
            final Spinner monthSpin = new Spinner(HistoryActivity.this);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(HistoryActivity.this,
                android.R.layout.simple_spinner_dropdown_item, months);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            monthSpin.setAdapter(adapter);
            monthSpin.setSelection(viewMonth - 1);

            final EditText year = new EditText(HistoryActivity.this);
            year.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            year.setText(Integer.toString(viewYear));
            year.selectAll();

            LinearLayout box = new LinearLayout(HistoryActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(8) + dp(18), dp(4), dp(8), dp(4));
            TextView mLabel = text(getString(R.string.history_month_label), 13, muted);
            box.addView(mLabel);
            box.addView(monthSpin, new LinearLayout.LayoutParams(-1, -2));
            String yRange = getString(R.string.history_year_label,
                CalDate.minYear(iranCalendar), CalDate.maxYear(iranCalendar));
            if (LocaleHelper.isPersian(HistoryActivity.this)) yRange = faDigitsString(yRange);
            TextView yLabel = text(yRange, 13, muted);
            LinearLayout.LayoutParams yLp = new LinearLayout.LayoutParams(-1, -2);
            yLp.setMargins(0, dp(10), 0, 0);
            box.addView(yLabel, yLp);
            box.addView(year, new LinearLayout.LayoutParams(-1, -2));

            android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(HistoryActivity.this)
                .setTitle(getString(R.string.history_jump_title))
                .setView(box)
                .setPositiveButton(getString(R.string.history_jump_apply), null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
            dlg.setOnShowListener(d -> dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    int month = monthSpin.getSelectedItemPosition() + 1;
                    viewMonth = month;
                    viewYear = parseYear(year.getText());
                    dlg.dismiss();
                    render();
                }));
            dlg.show();
        }

        /** Reads the entered year, accepting Latin or Persian digits, and clamps it to the
         *  navigable year band that the chevrons already bound to, per calendar system. */
        int parseYear(CharSequence s) {
            StringBuilder b = new StringBuilder(s.length());
            for (char c : s.toString().trim().toCharArray()) {
                if (c >= '\u06f0' && c <= '\u06f9') b.append((char) ('0' + c - '\u06f0'));
                else if (c >= '0' && c <= '9') b.append(c);
            }
            int year;
            try {
                year = Integer.parseInt(b.toString());
            } catch (NumberFormatException e) {
                return viewYear;
            }
            return Math.max(CalDate.minYear(iranCalendar),
                Math.min(CalDate.maxYear(iranCalendar), year));
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
                + (fa ? faDigits(viewYear) : Integer.toString(viewYear)) + " \u25be");
            title.setGravity(Gravity.CENTER);
            bindNav(prev, viewYear > CalDate.minYear(iranCalendar)
                || viewYear == CalDate.minYear(iranCalendar) && viewMonth > 1, -1);
            bindNav(next, viewYear < CalDate.maxYear(iranCalendar)
                || viewYear == CalDate.maxYear(iranCalendar) && viewMonth < 12, 1);
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
            CalDate today = now();
            int firstDay = CalDate.weekdayIndex(CalDate.of(viewYear, viewMonth, 1), iranCalendar);
            int days = CalDate.daysInMonth(viewYear, viewMonth, iranCalendar);
            for (int offset = 0; offset < firstDay + days; offset += 7) {
                LinearLayout row = new LinearLayout(HistoryActivity.this);
                for (int col = 0; col < 7; col++) {
                    int d = offset + col - firstDay + 1;
                    TextView cell = d < 1 || d > days
                        ? text("", 0, fg) : dayCell(CalDate.of(viewYear, viewMonth, d), today);
                    row.addView(cell, new LinearLayout.LayoutParams(0, dp(48), 1));
                }
                grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            updateStatus();
        }

        /** One tappable day, highlighted as a range bound, today's outline, or the ranged tint. */
        TextView dayCell(CalDate day, CalDate today) {
            boolean fa = LocaleHelper.isPersian(HistoryActivity.this);
            boolean fromSel = picked[0] != null && picked[0].sameDay(day);
            boolean toSel = picked[1] != null && picked[1].sameDay(day);
            boolean inRange = picked[0] != null && picked[1] != null
                && picked[0].compare(day) <= 0 && day.compare(picked[1]) <= 0;
            boolean isToday = today.sameDay(day);
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
            cell.setContentDescription(dateText(day)
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
    static void pickDay(CalDate[] picked, CalDate day) {
        if (picked[0] == null || picked[1] != null) {
            picked[0] = day;
            picked[1] = null;
        } else if (day.compare(picked[0]) < 0) {
            picked[1] = picked[0];
            picked[0] = day;
        } else {
            picked[1] = day;
        }
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

    /** The unaccounted money on screen for the current data, newest first. Drives the explainer
     *  affordance next to the breakdown heading; empty whenever the history fully adds up. */
    private List<Residual> allResiduals = new ArrayList<>();

    /** Scroll container, kept so the list position survives rotation. */
    private PullRefreshScrollView scrollView;

    /** Scroll offset pending restore until the rebuilt list is laid out. */
    private int pendingScroll;

    /** Expands the current year, current month and its days by default once per screen, so the
     *  freshest history is visible without any interaction without undoing later collapses. When
     *  the Display menu's "expand all history" option is on, every year, month and day opens instead. */
    private boolean expandedSeeded;

    private void seedExpanded() {
        if (expandedSeeded) return;
        expandedSeeded = true;
        if (BalanceData.getExpandAllHistory(this)) {
            for (YearGroup y : allYears) {
                expandedYears.add(y.key());
                for (MonthGroup m : y.months) {
                    expandedMonths.add(m.key());
                    for (DayGroup d : m.days) expandedDays.add(d.key());
                }
            }
            return;
        }
        CalDate now = now();
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

    private CalDate now() {
        return CalDate.today(iranCalendar);
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
            CalDate d) {
        if (d == null) return;
        outState.putInt(yKey, d.year);
        outState.putInt(mKey, d.month);
        outState.putInt(dKey, d.day);
    }

    /** Rebuilds {@link #filter} from the saved state, staying on {@link Filter#ALL} when a fresh
     *  screen (no state, or state saved before filters existed) is shown. */
    private void restoreFilter(Bundle state) {
        if (!state.containsKey(KEY_FILTER_DIRECTION)) return;
        CalDate from = readDate(state, KEY_FILTER_FROM_YEAR, KEY_FILTER_FROM_MONTH, KEY_FILTER_FROM_DAY);
        CalDate to = readDate(state, KEY_FILTER_TO_YEAR, KEY_FILTER_TO_MONTH, KEY_FILTER_TO_DAY);
        filter = new Filter(state.getInt(KEY_FILTER_DIRECTION, DIR_ALL),
            state.getInt(KEY_FILTER_RANGE, RANGE_ALL), from, to);
    }

    /** Rebuilds a saved date bound on the active calendar system, dropping the bound when the
     *  saved day does not exist there (e.g. February 29 after a region switch to Iran). */
    private CalDate readDate(Bundle state, String yKey, String mKey, String dKey) {
        int y = state.getInt(yKey, -1), m = state.getInt(mKey, -1), d = state.getInt(dKey, -1);
        if (y < 0 || m < 1 || d < 1 || d > CalDate.daysInMonth(y, m, iranCalendar)) return null;
        return CalDate.of(y, m, d);
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
                if (scanPending) return;
                scanPending = true;
                smsHandler.postDelayed(() -> {
                    scanPending = false;
                    new Thread(() -> BalanceData.scanHistory(HistoryActivity.this)).start();
                }, 400);
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
        smsHandler.removeCallbacksAndMessages(null);
        scanPending = false;
    }

    // ====================================================================
    // Screen rendering
    // ====================================================================

    /** Re-reads the saved history, applies the current filters and rebuilds the whole screen from
     *  it: the filter bar first (so its chips mirror the active filter), then the hero and the
     *  breakdown computed over the filtered list, so every figure on screen reflects exactly what
     *  is shown. The decrypt-and-parse plus the per-movement calendar math run on a worker thread
     *  so a large story never stalls the UI; only the finished groups are drawn here. */
    private void render() {
        final int gen = ++renderGen;
        final Filter f = filter;
        final String bank = bankFilter;
        final String acct = accountFilter;
        final boolean iran = iranCalendar;
        new Thread(() -> {
            try {
                List<Transaction> txs = BalanceData.readTransactions(getApplicationContext());
                if (bank != null) txs = filterByBank(txs, bank);
                if (acct != null) txs = filterByAccount(txs, acct);
                // Detected across the whole account before any narrowing, since a residual is only
                // provable between two balance statements a filter may hide, and then narrowed by the
                // same rules so what is on screen and what the totals say always agree.
                final List<Residual> residuals = applyResidualFilters(Residual.between(txs), f, iran);
                final List<Transaction> filtered = applyFilters(txs, f, iran);
                final Lists lists = buildLists(filtered, residuals, iran);
                allResiduals = residuals;
                final Map<String, String> notesNow =
                    BalanceData.readNotes(getApplicationContext());
                runOnUiThread(() -> {
                    if (gen != renderGen || isDestroyed() || isFinishing()) return;
                    notes = notesNow;
                    refreshDates();
                    rebuildFilterBar();
                    body.removeAllViews();
                    if (lists.years.isEmpty()) {
                        emptyState();
                    } else {
                        body.addView(heroCard(lists), margin(0, 0, 0, 6));
                        body.addView(breakdownHeading(filtered.size()), margin(0, 16, 0, 12));
                        allYears = lists.years;
                        seedExpanded();
                        renderYears(body, allYears);
                    }
                });
            } catch (Exception e) {
                // A corrupt store or a scan race must never blank the screen; keep the previous
                // render and flag the failure quietly.
                android.util.Log.w("BalanceHistory", "render failed", e);
            }
        }).start();
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

    /** The same bank narrowing for unaccounted money, so an export of one bank never carries
     *  another bank's gaps. */
    static List<Residual> filterResidualsByBank(List<Residual> residuals, String bank) {
        List<Residual> only = new ArrayList<>();
        for (Residual r : residuals) if (bank.equals(r.bank)) only.add(r);
        return only;
    }

    /** The same account narrowing for unaccounted money. */
    static List<Residual> filterResidualsByAccount(List<Residual> residuals, String account) {
        List<Residual> only = new ArrayList<>();
        for (Residual r : residuals) if (account.equals(r.account)) only.add(r);
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
        TextView total = bold(signedAmount(lists.total), 32, valueColor(lists.total));
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
        TextView v = bold(signedAmount(value), 20, color);
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

    /**
     * The breakdown heading, carrying a question-mark button only while unaccounted money is on
     * screen. The button is how the amber rows explain themselves without every row having to spell
     * it out, and its absence when the history adds up is itself the reassurance.
     */
    private LinearLayout breakdownHeading(int shown) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(sectionLabel(getString(R.string.history_breakdown)));

        LinearLayout.LayoutParams spacer = new LinearLayout.LayoutParams(0, 0, 1);
        row.addView(new View(this), spacer);

        // How much history is on screen. Without it the size of an account is a guess, and the cost
        // of the breakdown below grows with it.
        row.addView(text(getResources().getQuantityString(
            R.plurals.history_n_tx, shown, shown), 12, muted, MEDIUM));

        if (allResiduals.isEmpty()) return row;

        TextView ask = text("?", 12, warnFg, MEDIUM);
        ask.setGravity(Gravity.CENTER);
        ask.setContentDescription(getString(R.string.residual_explainer_cd));
        ask.setBackground(ripple(rounded(warnBg, 11)));
        ask.setClickable(true);
        ask.setFocusable(true);
        ask.setOnClickListener(v -> residualExplainer());
        row.addView(ask, new LinearLayout.LayoutParams(dp(28), dp(28)));
        return row;
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
        TextView sum = bold(signedAmount(y.sum), 16, valueColor(y.sum));
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
        TextView sum = bold(signedAmount(m.sum), 14, valueColor(m.sum));
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
        if (g.date.sameDay(today)) {
            TextView tag = text(getString(R.string.history_today), 11, badgeFg, MEDIUM);
            tag.setPadding(dp(6), dp(2), dp(6), dp(2));
            tag.setBackground(rounded(badgeBg, 8));
            dateWrap.addView(tag);
        } else if (g.date.sameDay(yesterday)) {
            TextView tag = text(getString(R.string.history_yesterday), 11, muted, MEDIUM);
            tag.setPadding(dp(6), dp(2), dp(6), dp(2));
            tag.setBackground(rounded(chipBg, 8));
            dateWrap.addView(tag);
        }
        TextView date = text(dateText(g.date), 13, fg);
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
        TextView sum = bold(signedAmount(g.sum), 13, valueColor(g.sum));
        fitToWidth(sum, 13, 10, 0);
        head.addView(sum, sumParams);
        head.setContentDescription(state(dateText(g.date), g.sum, open));
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));

        if (open) {
            LinearLayout rows = new LinearLayout(this);
            rows.setOrientation(LinearLayout.VERTICAL);
            rows.setPaddingRelative(dp(8), dp(2), 0, 0);
            List<Line> lines = dayLines(g);
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    View sep = new View(this);
                    sep.setBackgroundColor(divider);
                    sep.setAlpha(0.35f);
                    LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(1));
                    slp.setMarginStart(dp(34));
                    rows.addView(sep, slp);
                }
                Line line = lines.get(i);
                rows.addView(line.residual != null ? residualRow(line.residual) : txRow(line.tx),
                    new LinearLayout.LayoutParams(-1, -2));
            }
            box.addView(rows, new LinearLayout.LayoutParams(-1, -2));
        }
        return box;
    }

    /**
     * One day's movements and unaccounted money as a single newest-first list.
     *
     * <p>A residual sits immediately before the balance statement that revealed it: the statement is
     * the proof, so reading the gap first and the evidence after it is the order that makes sense.
     * When both share a timestamp the residual still leads, since a gap is a statement's companion
     * rather than a peer of it. The tie-break compares whether the residual is absent, never the
     * residual objects themselves — two different gaps on one day are equal by that measure, and
     * comparing references would break the ordering contract.
     */
    private static List<Line> dayLines(DayGroup g) {
        List<Line> lines = new ArrayList<>(g.txs.size() + g.residuals.size());
        for (Transaction t : g.txs) lines.add(new Line(t.date, t, null));
        for (Residual r : g.residuals) lines.add(new Line(r.toDate, null, r));
        lines.sort((a, b) -> {
            int byDate = Long.compare(b.date, a.date);
            if (byDate != 0) return byDate;
            if ((a.residual == null) != (b.residual == null))
                return a.residual == null ? 1 : -1;
            return 0;
        });
        return lines;
    }

    /** A rendered history line: exactly one of a parsed movement or unaccounted money. */
    private static final class Line {
        final long date;
        final Transaction tx;
        final Residual residual;

        Line(long date, Transaction tx, Residual residual) {
            this.date = date;
            this.tx = tx;
            this.residual = residual;
        }
    }

    /**
     * One unaccounted amount: an amber row carrying the signed net, the window of balance statements
     * that prove it, and a plain statement of the one thing the app genuinely cannot say. Tappable
     * for the full arithmetic.
     */
    private LinearLayout residualRow(final Residual r) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPaddingRelative(dp(4), dp(3), dp(4), dp(3));
        cell.setBackground(rounded(warnBg, 10));
        cell.setClickable(true);
        cell.setFocusable(true);
        cell.setOnClickListener(v -> residualDetail(r));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPaddingRelative(dp(4), dp(3), dp(4), dp(3));

        // A question mark, not a bank badge: there is no sender to attribute this to, and showing a
        // bank icon there would imply a message we never received.
        TextView ask = text("?", 13, warnFg, MEDIUM);
        ask.setGravity(Gravity.CENTER);
        ask.setBackground(roundedStroke(Color.TRANSPARENT, 12, warnFg));
        row.addView(ask, new LinearLayout.LayoutParams(dp(24), dp(24)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        boolean perBank = bankFilter != null;
        if (!perBank) {
            col.addView(text(BankRules.displayName(this, r.bank), 13, warnFg),
                new LinearLayout.LayoutParams(-2, -2));
        }
        TextView time = text(timeText(r.toDate), perBank ? 13 : 11, warnFg);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        if (!perBank) tp.topMargin = dp(2);
        col.addView(time, tp);
        TextView label = text(getString(R.string.residual_label), 10.5f, warnFg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(1);
        col.addView(label, lp);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, -2, 1);
        colLp.setMarginStart(dp(9));
        row.addView(col, colLp);

        TextView amt = bold(signedAmount(r.amount), 13, warnFg);
        fitToWidth(amt, 13, 10, 0);
        row.addView(amt, new LinearLayout.LayoutParams(-2, -2));
        cell.addView(row, new LinearLayout.LayoutParams(-1, -2));

        TextView why = text(getString(R.string.residual_row_hint), 10.5f, warnFg);
        why.setLineSpacing(0, 1.05f);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-1, -2);
        wp.setMarginStart(dp(37));
        wp.setMarginEnd(dp(4));
        wp.topMargin = dp(2);
        cell.addView(why, wp);
        cell.setContentDescription(getString(R.string.residual_row_cd, signedAmount(r.amount)));
        return cell;
    }

    /**
     * The full explanation for one gap, as plain arithmetic the user can check against their own
     * statement: what the bank said the balance became, what we received in between, and the
     * difference — with an explicit note that the app will not guess what it was.
     */
    private void residualDetail(Residual r) {
        LockManager.holdUnlock();
        String body = getString(R.string.residual_detail_body,
            dateText(calOfResidual(r.fromDate)),
            dateText(calOfResidual(r.toDate)),
            BankRules.displayName(this, r.bank));
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.residual_detail_title, signedAmount(r.amount)))
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .create();
        dlg.show();
    }

    /**
     * What the amber rows mean, in general, plus every gap currently on screen — so a user who taps
     * the "?" once can see the whole picture rather than hunting row by row.
     */
    private void residualExplainer() {
        LockManager.holdUnlock();
        StringBuilder body = new StringBuilder(getString(R.string.residual_explainer_body));
        for (Residual r : allResiduals) {
            body.append("\n\n• ")
                .append(BankRules.displayName(this, r.bank))
                .append(r.account == null ? "" : " " + digits(r.account))
                .append(" — ").append(signedAmount(r.amount))
                .append(" (").append(dateText(calOfResidual(r.fromDate)))
                .append(" → ").append(dateText(calOfResidual(r.toDate))).append(")");
        }
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.residual_explainer_title)
            .setMessage(body.toString())
            .setPositiveButton(android.R.string.ok, null)
            .create();
        dlg.show();
    }

    private CalDate calOfResidual(long date) {
        int[] g = gDate(date);
        return CalDate.fromGregorian(g[0], g[1], g[2], iranCalendar);
    }

    /** One movement: bank badge, bank name with time, the account number it hit, and the signed
     *  amount. In a per-bank view every row is the same bank, so the time alone identifies it and
     *  the badge/name are dropped — but the account number stays in both scopes, because a movement's
     *  account is meaningful even in the combined view. The whole row is tappable to add or edit the
     *  transaction's private note, which then renders underneath; the same note follows the movement
     *  everywhere it appears, whatever the filters. */
    private LinearLayout txRow(final Transaction t) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPaddingRelative(dp(4), dp(3), dp(4), dp(3));
        cell.setClickable(true);
        cell.setFocusable(true);
        cell.setOnClickListener(v -> noteDialog(t));

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

        TextView amt = bold(signedAmount(t.amount), 13, valueColor(t.amount));
        fitToWidth(amt, 13, 10, 0);
        row.addView(amt, new LinearLayout.LayoutParams(-2, -2));
        cell.addView(row, new LinearLayout.LayoutParams(-1, -2));

        String note = notes == null ? null : notes.get(BalanceData.noteKey(t));
        if (note != null) {
            LinearLayout noteChip = new LinearLayout(this);
            noteChip.setOrientation(LinearLayout.HORIZONTAL);
            noteChip.setGravity(Gravity.CENTER_VERTICAL);
            noteChip.setPadding(dp(8), dp(3), dp(8), dp(3));
            noteChip.setBackground(rounded(badgeBg, 9));
            noteChip.setLayoutDirection(isRtl() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);

            TextView pencil = text("\u270e", 11, badgeFg, MEDIUM);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
            plp.setMarginEnd(dp(5));
            if (isRtl()) plp.setMarginStart(dp(5));
            noteChip.addView(pencil, plp);

            TextView noteView = text(note, 12, badgeFg);
            noteView.setLineSpacing(0, 1.05f);
            noteChip.addView(noteView, new LinearLayout.LayoutParams(0, -2, 1));

            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
            np.setMarginStart(dp(perBank ? 0 : 39));
            np.setMarginEnd(dp(4));
            np.topMargin = dp(2);
            cell.addView(noteChip, np);
        }
        cell.setContentDescription(getString(R.string.note_row_hint));
        return cell;
    }

    /** The note editor for one transaction: a free-text field seeded with the current note, with
     *  Save (persists and re-renders), Clear (removes the note) and Cancel. Editing from any filter
     *  or view updates the same shared note, because notes are keyed to the transaction itself. */
    private void noteDialog(final Transaction t) {
        final EditText input = new EditText(this);
        input.setSingleLine(false);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHorizontallyScrolling(false);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setHint(getString(R.string.note_edit_hint));
        input.setTextColor(fg);
        input.setHintTextColor(muted);
        String existing = BalanceData.getNote(this, t);
        input.setText(existing == null ? "" : existing);
        input.setSelection(input.getText().length());

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(20), dp(10), dp(20), 0);
        wrap.addView(input);

        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.note_edit_title))
            .setView(wrap)
            .setPositiveButton(getString(R.string.note_save), null)
            .setNegativeButton(getString(R.string.lock_cancel), null)
            .setNeutralButton(getString(R.string.note_clear), null)
            .create();
        dlg.setOnShowListener(d -> {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    BalanceData.setNote(this, t, input.getText().toString());
                    dlg.dismiss();
                    render();
                });
            dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(v -> {
                    BalanceData.setNote(this, t, null);
                    dlg.dismiss();
                    render();
                });
        });
        dlg.show();
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
        return title + ", " + signedAmount(sum) + ", "
            + getString(open ? R.string.history_expanded : R.string.history_collapsed);
    }

    /** Deposit and withdrawal subtotals as compact colored pills; sign is shown by the arrow. */
    private LinearLayout chipsRow(long deposits, long withdrawals) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (deposits > 0) {
            row.addView(chip("\u2191 " + CurrencyHelper.amount(this, deposits), depBg, depFg,
                getString(R.string.history_deposit)));
        }
        if (withdrawals < 0) {
            row.addView(chip("\u2193 " + CurrencyHelper.amount(this, -withdrawals), witBg, witFg,
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
        int color = BankBadge.colorFor(canonicalName);
        GradientDrawable bg = rounded(color, 10);
        LinearLayout sq = new LinearLayout(this);
        sq.setOrientation(LinearLayout.VERTICAL);
        sq.setGravity(Gravity.CENTER);
        sq.setBackground(bg);
        sq.setContentDescription(BankRules.displayName(this, canonicalName));
        TextView init = text(BankBadge.initials(canonicalName), 11, Color.WHITE);
        init.setTypeface(null, Typeface.BOLD);
        sq.addView(init);
        return sq;
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
        final CalDate date;
        long sum;
        final List<Transaction> txs = new ArrayList<>();
        /** Unaccounted money detected on this day, shown beside the movements rather than among
         *  them: it is proven by the day's own balance statements, not read off a message. */
        final List<Residual> residuals = new ArrayList<>();
        DayGroup(CalDate date) {
            this.date = date;
        }
        String key() {
            return date.key();
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
        final CalDate from;
        /** Inclusive upper bound, or null for unbounded. */
        final CalDate to;

        Filter(int direction, int rangePreset, CalDate from, CalDate to) {
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

        Filter withRange(int preset, CalDate from, CalDate to) {
            return new Filter(direction, preset, from, to);
        }
    }

    /** Keeps the transactions whose movement direction and calendar date fall inside {@code f}; a
     *  transaction on a boundary day is included. Never mutates the caller's list, so it composes
     *  safely after the per-bank filter for both the full and the per-bank screens. */
    static List<Transaction> applyFilters(List<Transaction> txs, Filter f) {
        return applyFilters(txs, f, true);
    }

    static List<Transaction> applyFilters(List<Transaction> txs, Filter f, boolean iran) {
        List<Transaction> out = new ArrayList<>(txs.size());
        for (Transaction t : txs) {
            if (f.direction == DIR_DEPOSIT && t.amount <= 0) continue;
            if (f.direction == DIR_WITHDRAWAL && t.amount >= 0) continue;
            if (f.from != null || f.to != null) {
                int[] g = gDate(t.date);
                CalDate d = CalDate.fromGregorian(g[0], g[1], g[2], iran);
                if (f.from != null && d.compare(f.from) < 0) continue;
                if (f.to != null && d.compare(f.to) > 0) continue;
            }
            out.add(t);
        }
        return out;
    }

    /**
     * The same narrowing for unaccounted money, keyed on the date it is placed at.
     *
     * <p>Detection always runs on the <em>unfiltered</em> movements, because a residual is only
     * provable between two balance statements that a date or direction filter may well hide; it is
     * this pass that decides whether the result belongs on screen. Filtering afterwards can therefore
     * only ever hide a residual, never invent one.
     */
    static List<Residual> applyResidualFilters(List<Residual> residuals, Filter f, boolean iran) {
        List<Residual> out = new ArrayList<>();
        if (residuals == null) return out;
        for (Residual r : residuals) {
            if (f.direction == DIR_DEPOSIT && r.amount <= 0) continue;
            if (f.direction == DIR_WITHDRAWAL && r.amount >= 0) continue;
            if (f.from != null || f.to != null) {
                int[] g = gDate(r.toDate);
                CalDate d = CalDate.fromGregorian(g[0], g[1], g[2], iran);
                if (f.from != null && d.compare(f.from) < 0) continue;
                if (f.to != null && d.compare(f.to) > 0) continue;
            }
            out.add(r);
        }
        return out;
    }

    /** Replaces the date bounds with what the preset means on {@code now}; CUSTOM is never applied
     *  here — the dialog sets its own bounds. Kept static so the instrumented tests cover it. */
    static Filter rangePreset(Filter f, int preset, JalaliCalendar now) {
        return rangePreset(f, preset, CalDate.of(now.year, now.month, now.day), true);
    }

    static Filter rangePreset(Filter f, int preset, CalDate now, boolean iran) {
        switch (preset) {
            case RANGE_TODAY:
                return f.withRange(RANGE_TODAY, now, now);
            case RANGE_MONTH:
                return f.withRange(RANGE_MONTH,
                    CalDate.of(now.year, now.month, 1),
                    CalDate.of(now.year, now.month, CalDate.daysInMonth(now.year, now.month, iran)));
            case RANGE_YEAR:
                return f.withRange(RANGE_YEAR,
                    CalDate.of(now.year, 1, 1),
                    CalDate.of(now.year, 12, CalDate.daysInMonth(now.year, 12, iran)));
            default:
                return f.withRange(RANGE_ALL, null, null);
        }
    }

    /** Splits the raw transactions into the summary sums and the year-by-year (month-by-month,
     *  day-by-day) groups of the given calendar system. Never mutates the caller's list. */
    static Lists buildLists(List<Transaction> txs) {
        return buildLists(txs, true);
    }

    static Lists buildLists(List<Transaction> txs, boolean iran) {
        return buildLists(txs, null, iran);
    }

    /**
     * The same breakdown, additionally folding in the unaccounted money for the day each residual is
     * placed on.
     *
     * <p>A residual counts toward every sum and subtotal — otherwise the totals would quietly omit
     * money the app knows moved, which is the one failure this feature exists to prevent — but it
     * never counts toward the movement <em>count</em>, because "12 transactions" must mean twelve
     * messages were actually received. The gap between those two numbers is the point.
     *
     * <p>Movements and residuals are folded in one date-ordered pass rather than movements first and
     * residuals afterwards. A residual carries no movement of its own to carry it into place, and a
     * filter can leave it without the statement that dated it, so folding it in second would append
     * its day at the end of the month and put the days out of order.
     */
    static Lists buildLists(List<Transaction> txs, List<Residual> residuals, boolean iran) {
        Lists lists = new Lists();
        boolean anyResidual = residuals != null && !residuals.isEmpty();
        if ((txs == null || txs.isEmpty()) && !anyResidual) return lists;

        // One newest-first stream of both kinds, so every day group is built in one go and the
        // groups come out ordered without a second pass.
        List<Line> lines = new ArrayList<>();
        if (txs != null) for (Transaction t : txs) lines.add(new Line(t.date, t, null));
        if (anyResidual) for (Residual r : residuals) lines.add(new Line(r.toDate, null, r));
        Collections.sort(lines, new Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                int byDate = Long.compare(b.date, a.date);
                if (byDate != 0) return byDate;
                if ((a.residual == null) != (b.residual == null)) return a.residual == null ? 1 : -1;
                return 0;
            }
        });

        CalDate today = CalDate.today(iran);
        Map<String, YearGroup> yearIndex = new HashMap<>();
        Map<String, MonthGroup> monthIndex = new HashMap<>();

        for (Line l : lines) {
            boolean isResidual = l.residual != null;
            long amount = isResidual ? l.residual.amount : l.tx.amount;
            DayGroup day = accumulate(lists, yearIndex, monthIndex, calOf(l.date, iran), amount,
                !isResidual, today);
            if (isResidual) day.residuals.add(l.residual); else day.txs.add(l.tx);
        }
        return lists;
    }

    /** The calendar date of a movement, in the active calendar system. */
    private static CalDate calOf(long date, boolean iran) {
        int[] g = gDate(date);
        return CalDate.fromGregorian(g[0], g[1], g[2], iran);
    }

    /** Folds one signed amount into every summary, year, month and day that shares its date, and
     *  returns the day group it landed in. {@code count} is false for unaccounted money, which moves
     *  every total but is not a movement. */
    private static DayGroup accumulate(Lists lists, Map<String, YearGroup> yearIndex,
            Map<String, MonthGroup> monthIndex, CalDate jc, long amount, boolean count, CalDate today) {
        lists.total += amount;
        boolean todayMatch = jc.sameDay(today);
        boolean monthMatch = jc.year == today.year && jc.month == today.month;
        boolean yearMatch = jc.year == today.year;
        if (todayMatch) {
            lists.today += amount;
            if (amount > 0) lists.todayDep += amount; else lists.todayWit += amount;
        }
        if (monthMatch) {
            lists.month += amount;
            if (amount > 0) lists.monthDep += amount; else lists.monthWit += amount;
        }
        if (yearMatch) {
            lists.year += amount;
            if (amount > 0) lists.yearDep += amount; else lists.yearWit += amount;
        }

        YearGroup year = yearIndex.get(String.valueOf(jc.year));
        if (year == null) {
            year = new YearGroup(jc.year);
            yearIndex.put(String.valueOf(jc.year), year);
            lists.years.add(year);
        }
        year.sum += amount;
        if (count) year.n++;
        if (amount > 0) year.dep += amount; else year.wit += amount;

        String monthKey = jc.year + "/" + jc.month;
        MonthGroup month = monthIndex.get(monthKey);
        if (month == null) {
            month = new MonthGroup(jc.year, jc.month);
            monthIndex.put(monthKey, month);
            year.months.add(month);
        }
        month.sum += amount;
        if (count) month.n++;
        if (amount > 0) month.dep += amount; else month.wit += amount;

        DayGroup day = month.days.isEmpty() ? null : month.days.get(month.days.size() - 1);
        if (day == null || day.date.day != jc.day) {
            day = new DayGroup(jc);
            month.days.add(day);
        }
        day.sum += amount;
        return day;
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

    /** Localized month name for the 1-based index, in the active calendar system: the Persian month
     *  names (Jalali script or the "Farvardin"-style transliteration) for the Iran region, and the
     *  Gregorian month names (Persian-scripted in the Persian UI) for International. */
    private String monthName(int month) {
        boolean fa = "fa".equals(LocaleHelper.currentTag(this));
        if (iranCalendar) {
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
        switch (month) {
            case 1: return fa ? "\u0698\u0627\u0646\u0648\u06cc\u0647" : "January";
            case 2: return fa ? "\u0641\u0648\u0631\u06cc\u0647" : "February";
            case 3: return fa ? "\u0645\u0627\u0631\u0633" : "March";
            case 4: return fa ? "\u0622\u0648\u0631\u06cc\u0644" : "April";
            case 5: return fa ? "\u0645\u0647" : "May";
            case 6: return fa ? "\u0698\u0648\u0626\u0646" : "June";
            case 7: return fa ? "\u0698\u0648\u0626\u06cc\u0647" : "July";
            case 8: return fa ? "\u0627\u0648\u062a" : "August";
            case 9: return fa ? "\u0633\u067e\u062a\u0627\u0645\u0628\u0631" : "September";
            case 10: return fa ? "\u0627\u06a9\u062a\u0628\u0631" : "October";
            case 11: return fa ? "\u0646\u0648\u0627\u0645\u0628\u0631" : "November";
            default: return fa ? "\u062f\u0633\u0627\u0645\u0628\u0631" : "December";
        }
    }

    /** The 7 weekday grid headings in the app language. The Iran region leads with Saturday, the
     *  International region leads with Monday (ISO). */
    private String[] weekdayLabels() {
        boolean fa = LocaleHelper.isPersian(this);
        if (iranCalendar) {
            return fa
                ? new String[]{"ش", "ی", "د", "س", "چ", "پ", "ج"}
                : new String[]{"Sa", "Su", "Mo", "Tu", "We", "Th", "Fr"};
        }
        return fa
            ? new String[]{"د", "ی", "س", "چ", "پ", "ج", "ش"}
            : new String[]{"Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"};
    }

    /** Formats a calendar date in the app language, e.g. "Khordad 12 1403" / "۱۲ خرداد ۱۴۰۳" in the
     *  Iran region and "January 26 2026" / "۲۶ ژانویه ۲۰۲۶" in International. */
    private String dateText(CalDate d) {
        boolean fa = LocaleHelper.isPersian(this);
        if (fa) {
            return faDigits(d.day) + " " + monthName(d.month) + " " + faDigits(d.year);
        }
        return monthName(d.month) + " " + d.day + " " + d.year;
    }

    /** Formats a calendar date as the compact "y/m/d" used by the custom-range inputs and summary,
     *  in the app language's digits. */
    private String compactDate(CalDate d) {
        String s = d.year + "/" + d.month + "/" + d.day;
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

    /** Formats an amount as a signed currency string, following the app language's digit rules and
     *  the chosen currency's value (toman divides by ten, other currencies show the raw amount). */
    private String signedAmount(long n) {
        String mag = CurrencyHelper.amount(this, Math.abs(n));
        if (n == 0) return mag;
        String sign = (n < 0 ? "\u2212" : "+");
        if (!LocaleHelper.isPersian(this)) return sign + mag;
        return "\u2066" + sign + mag + "\u2069";
    }

    // ====================================================================
    // Pull-to-refresh
    // ====================================================================

    /** The true top-of-page canvas for the pull-to-refresh chip: a full-screen view above the list
     *  that reads the scroll view's pull state and repaints every time the ticker advances. It
     *  never consumes touches, so the list below receives every gesture; it only paints. */
    private final class PullIndicatorOverlay extends View {
        PullIndicatorOverlay() {
            super(HistoryActivity.this);
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas c) {
            PullRefreshScrollView v = scrollView;
            if (v == null || !v.indicatorVisible || v.pullFade <= 0.02f) return;
            c.save();
            c.scale(v.d, v.d);
            c.translate(0, statusInsetTop / v.d);
            v.drawPullIndicator(c, getWidth() / v.d, PullRefreshScrollView.PULL_TOP);
            c.restore();
        }
    }

    /** The scrollable history list, extended with the same pull-to-refresh gesture the dashboard
     *  has: with the list at its very top, pulling the finger down reveals a circular arrow chip
     *  pinned to the top of the page that follows the pull; releasing past a threshold spins the
     *  arrow while the SMS inbox and history are re-scanned, then the arrow glides back up once the
     *  scans settle. The chip is actually painted by {@link PullIndicatorOverlay}, so it floats
     *  above the header at the very top of the screen, clear of the hero card and the rows, and
     *  never scrolls with the content. Pulling here refreshes the whole app — balances, widget and
     *  history — exactly like a pull on the main screen. */
    private final class PullRefreshScrollView extends ScrollView {
        /** dp of downward drag past which the release arms a refresh. Matches the dashboard. */
        static final float PULL_TRIGGER = 55f;
        /** dp the chip may descend while following the finger. Matches the dashboard. */
        static final float PULL_CAP = 44f;
        /** Full revolutions per second while the arrow spins, so the motion reads the same on any
         *  device and frame rate; the old fixed per-frame step turned jittery when frames spread. */
        static final float SPIN_PER_SEC = 360f;
        /** The chip's anchor just under the status bar (dp from the content top), mirroring the
         *  dashboard's refresh circle so both screens show the same shape in the same place. */
        static final float PULL_TOP = 58f;
        final Paint p = new Paint(3);
        final float d = getResources().getDisplayMetrics().density;
        final Handler handler = new Handler(Looper.getMainLooper());
        float downY;
        float pullShift, pullFrac, pullFade, spinAngle;
        boolean pulling, refreshing, refreshAgain;
        boolean indicatorVisible, spinnerRunning, retracting;
        long spinDeadline;
        View indicatorOverlay;

        PullRefreshScrollView(Context c) {
            super(c);
        }

        /** Tells the covering chip overlay — the thing that actually paints the indicator now — to
         *  repaint alongside this view, so the pull state and its image always agree. */
        void setIndicatorOverlay(View v) {
            indicatorOverlay = v;
        }

        void refreshIndicator() {
            invalidate();
            if (indicatorOverlay != null) indicatorOverlay.invalidate();
        }

        /** Spins the arrow while the refresh runs, then glides it back up. A short minimum beat (a
         *  fraction of a turn) is kept so an almost-instant scan still reads as a completed spin,
         *  not a flicker; {@link MainActivity.BalanceView} paces its spinner the same way.
         *  Every step is scaled by the real elapsed time (frame-time-based), so the spin and the
         *  glides stay smooth and identical whatever the frame rate, instead of stepping by a fixed
         *  amount per tick and jerking when a frame arrives late. */
        final Runnable refreshTicker = new Runnable() {
            long lastTick;
            @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                long now = android.os.SystemClock.uptimeMillis();
                float dt = lastTick == 0 ? 0.016f : Math.min(0.05f, (now - lastTick) / 1000f);
                lastTick = now;
                if (spinnerRunning) {
                    spinAngle += SPIN_PER_SEC * dt;
                    // Settle the chip down onto its cap with an eased glide instead of snapping it.
                    pullShift += (PULL_CAP - pullShift) * (1f - (float) Math.exp(-dt / 0.08f));
                    if (System.currentTimeMillis() >= spinDeadline && !refreshing) {
                        spinnerRunning = false;
                        retracting = true;
                    }
                    refreshIndicator();
                    handler.postDelayed(this, 16);
                } else if (retracting || pullFade > 0.02f) {
                    // Glide home with exponential ease-out: fast at first, gently decelerating, so
                    // the chip melts away instead of being yanked up by a constant per-frame step.
                    float glide = (float) Math.exp(-dt / 0.16f);
                    pullShift *= glide;
                    pullFrac *= glide;
                    pullFade *= (float) Math.exp(-dt / 0.14f);
                    if (pullFade <= 0.02f) {
                        pullFade = 0;
                        indicatorVisible = false;
                        retracting = false;
                        refreshIndicator();
                    } else {
                        refreshIndicator();
                        handler.postDelayed(this, 16);
                    }
                }
            }
        };

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                downY = e.getY();
                pulling = false;
                hideIndicator();
            } else if (e.getAction() == MotionEvent.ACTION_MOVE
                    && getScrollY() == 0
                    && e.getY() - downY > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                // The list is at its top and the drag heads down, where the ScrollView can never
                // scroll to: take the gesture over so the pull indicator follows the finger even
                // when the touch started on a tap-able row.
                pulling = true;
            }
            if (pulling) return true;
            return super.onInterceptTouchEvent(e);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int action = e.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                downY = e.getY();
                pulling = false;
                hideIndicator();
                return super.onTouchEvent(e);
            }
            if (pulling) {
                if (action == MotionEvent.ACTION_MOVE) {
                    startPull((e.getY() - downY) / d);
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    pulling = false;
                    if (action == MotionEvent.ACTION_UP && (e.getY() - downY) / d > PULL_TRIGGER) {
                        beginSpin();
                        refreshAll();
                    } else {
                        retractIndicator();
                    }
                }
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE && getScrollY() == 0
                    && e.getY() - downY > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                // With no child under the finger (an empty list, or a drag that started on the bare
                // backing) the ScrollView receives the moves directly and onInterceptTouchEvent is
                // never consulted, so the pull is armed here as well.
                pulling = true;
                startPull((e.getY() - downY) / d);
                return true;
            }
            return super.onTouchEvent(e);
        }

        /** Starts (or resumes) the finger-following phase of the indicator for a downward pull. */
        void startPull(float delta) {
            indicatorVisible = true;
            spinnerRunning = false;
            retracting = false;
            // The chip follows the finger one to one up to its cap, then keeps travelling with a
            // growing resistance instead of stopping dead against a hard ceiling while the finger
            // keeps pulling — the drag always stays "alive" feeling.
            float pull = Math.max(0, delta);
            pullShift = pull <= PULL_CAP ? pull : PULL_CAP + (pull - PULL_CAP) * 0.35f;
            pullFrac = Math.min(1, pull / PULL_TRIGGER);
            pullFade = Math.min(1, pull / 14f);
            spinAngle = pullFrac * 180f;
            refreshIndicator();
        }

        /** Release past the trigger: the arrow goes fully down and spins while the scans run. */
        void beginSpin() {
            spinnerRunning = true;
            retracting = false;
            indicatorVisible = true;
            pullFade = 1;
            pullFrac = 1;
            spinDeadline = System.currentTimeMillis() + 400;
            handler.removeCallbacks(refreshTicker);
            handler.postDelayed(refreshTicker, 16);
            refreshIndicator();
        }

        /** Lift short of the trigger (or a cancelled gesture): glide the arrow back up. */
        void retractIndicator() {
            if (!indicatorVisible) {
                pullShift = pullFrac = pullFade = 0;
                return;
            }
            spinnerRunning = false;
            retracting = true;
            handler.removeCallbacks(refreshTicker);
            handler.postDelayed(refreshTicker, 16);
        }

        /** A new finger put down: drop whatever indicator state is current so the next pull restarts. */
        void hideIndicator() {
            handler.removeCallbacks(refreshTicker);
            indicatorVisible = false;
            spinnerRunning = false;
            retracting = false;
            pullShift = pullFrac = pullFade = 0;
            refreshIndicator();
        }

        /** Re-scans the SMS inbox (balances and widget) and then the history in the background,
         *  mirroring the dashboard's refresh so a pull here updates the whole app. A missing SMS
         *  permission skips straight to a render of the saved store; an in-flight refresh folds a
         *  second pull into {@link #refreshAgain} instead of stacking another scan. */
        void refreshAll() {
            if (checkSelfPermission(Manifest.permission.READ_SMS)
                    != PackageManager.PERMISSION_GRANTED) {
                render();
                return;
            }
            if (refreshing) {
                refreshAgain = true;
                return;
            }
            refreshing = true;
            new Thread(() -> {
                try {
                    final Context app = getContext().getApplicationContext();
                    BalanceData.scanSms(app, BalanceData.read(app));
                    BalanceWidgetProvider.push(app);
                    BalanceData.scanHistory(HistoryActivity.this);
                } catch (Exception e) {
                    android.util.Log.w("BalanceHistory", "pull-to-refresh scan failed", e);
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    refreshing = false;
                    if (refreshAgain) {
                        refreshAgain = false;
                        refreshAll();
                    }
                });
            }).start();
        }

        /** The pull-to-refresh arrow: a small chip with a circular arrow that follows the finger down
         *  while rotated by how far the pull has gone, then spins on release. Painted by the covering
         *  overlay at {@link #PULL_TOP} below the status bar — squarely pinned to the top of the page,
         *  clear of the hero card and the rows beneath it, visible the moment the drag starts — and
         *  scaled in softly with the fade so it swells out of the background instead of popping in at
         *  full size. Its canvas arrives pre-scaled and pre-translated over the status bar.
         *  {@link MainActivity.BalanceView#drawPullIndicator} draws the same chip. */
        void drawPullIndicator(Canvas c, float w, float topDp) {
            if (pullFade <= 0.02f) return;
            float cx = w / 2f, cy = topDp + pullShift;
            float pop = 0.62f + 0.38f * Math.min(1, pullFade);
            int alpha = (int) (255 * Math.min(1, pullFade));
            p.setStyle(Paint.Style.FILL);
            p.setColor(bg);
            p.setAlpha(alpha);
            c.drawCircle(cx, cy, 17 * pop, p);
            p.setColor(accent);
            c.save();
            c.translate(cx, cy);
            c.scale(pop, pop);
            c.rotate(spinAngle);
            float g = 8.5f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.2f);
            p.setStrokeCap(Paint.Cap.ROUND);
            Path ring = new Path();
            ring.addArc(new RectF(-g, -g, g, g), -90, 300);
            c.drawPath(ring, p);
            // Arrowhead at the open end of the ring: a filled triangle whose base straddles the ring
            // end and whose apex points along the direction of travel, big enough to cover the round
            // stroke cap so the head reads as a crisp arrow instead of a lumpy dot.
            float endAng = 210f;                             // the ring's open end
            float ta = (float) Math.toRadians(endAng);
            float dir = (float) Math.toRadians(endAng + 90f);
            float perp = (float) Math.toRadians(endAng + 180f);
            float bx = g * (float) Math.cos(ta), by = g * (float) Math.sin(ta);
            float px = bx * 0.86f, py = by * 0.86f;
            float len = 5.2f, halfW = 3.4f;
            Path head = new Path();
            head.moveTo((float) (px + len * Math.cos(dir)), (float) (py + len * Math.sin(dir)));
            head.lineTo((float) (bx + halfW * Math.cos(perp)), (float) (by + halfW * Math.sin(perp)));
            head.lineTo((float) (bx - halfW * Math.cos(perp)), (float) (by - halfW * Math.sin(perp)));
            head.close();
            p.setStyle(Paint.Style.FILL);
            p.setStrokeWidth(0);
            c.drawPath(head, p);
            c.restore();
            p.setAlpha(255);
            p.setStrokeCap(Paint.Cap.BUTT);
        }
    }
}