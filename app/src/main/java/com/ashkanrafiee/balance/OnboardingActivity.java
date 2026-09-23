package com.ashkanrafiee.balance;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** The first-run introduction: three short pages that explain what Balance is and what it reads
 *  (Welcome → Privacy → SMS access) before dropping the user on the dashboard. The SMS permission is
 *  requested here, right after the explanation, instead of as a bare system dialog over an empty
 *  dashboard. Skipping anywhere marks the introduction as seen, so it never nags again; it can still
 *  be opened anytime from the About screen. */
public final class OnboardingActivity extends Activity {
    private static final int SMS_REQUEST = 11;
    private static final int PAGE_WELCOME = 0, PAGE_PRIVACY = 1, PAGE_SMS = 2;

    int bg, panel, fg, muted, subtitle, accent, active, divider;
    private LockOverlay lockOverlay;
    private LinearLayout content, dotsRow;
    private TextView primary, skip;
    private ScrollView scroll;
    private float downX, downY;
    private int step;

    int color(int res) { return getResources().getColor(res, getTheme()); }

    int dp(float n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }

    TextView text(String s, float size, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    /** Paragraph text. Forces the base direction to the interface direction so a paragraph that
     *  happens to start with a Latin word (like a brand name) still flows right-to-left on RTL
     *  locales instead of hijacking the whole block to left-to-right. */
    TextView paragraph(String s, float size, int color) {
        TextView v = text(s, size, color);
        v.setTextDirection(getResources().getConfiguration().getLayoutDirection()
            == View.LAYOUT_DIRECTION_RTL ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
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

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(ThemeHelper.wrap(base)));
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        bg = color(R.color.bg);
        panel = color(R.color.panel);
        fg = color(R.color.fg);
        muted = color(R.color.muted);
        subtitle = color(R.color.subtitle);
        accent = color(R.color.accent);
        active = color(R.color.active);
        divider = color(R.color.divider);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bg));

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

        FrameLayout host = new FrameLayout(this);
        setContentView(host);
        host.addView(root, new FrameLayout.LayoutParams(-1, -1));

        lockOverlay = new LockOverlay(this);
        lockOverlay.setUnlockListener(this::updateSecureFlag);
        lockOverlay.setCancelListener(() -> lockOverlay.hide());
        host.addView(lockOverlay, new FrameLayout.LayoutParams(-1, -1));
        lockOverlay.setVisibility(View.GONE);
        updateSecureFlag();

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(getString(R.string.app_name), 18, fg);
        name.setTypeface(null, Typeface.BOLD);
        name.setGravity(Gravity.CENTER_VERTICAL
            | (getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                ? Gravity.RIGHT : Gravity.LEFT));
        bar.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        skip = text(getString(R.string.onboarding_skip), 14, muted);
        skip.setGravity(Gravity.CENTER);
        skip.setMinimumHeight(dp(44));
        skip.setPadding(dp(12), 0, dp(12), 0);
        skip.setOnClickListener(v -> finishAsSeen());
        skip.setContentDescription(getString(R.string.onboarding_skip));
        bar.addView(skip);
        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -1));
        this.scroll = scroll;
        attachSwipe();
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        dotsRow = new LinearLayout(this);
        dotsRow.setGravity(Gravity.CENTER_HORIZONTAL);
        dotsRow.setPadding(0, dp(6), 0, dp(12));
        root.addView(dotsRow);

        primary = new TextView(this);
        primary.setTextSize(15);
        primary.setTypeface(null, Typeface.BOLD);
        primary.setTextColor(fg);
        primary.setGravity(Gravity.CENTER);
        primary.setPadding(dp(16), dp(14), dp(16), dp(14));
        primary.setBackground(rounded(accent, 14));
        root.addView(primary);

        showStep(PAGE_WELCOME);
    }

    private void showStep(int s) {
        s = Math.max(PAGE_WELCOME, Math.min(s, PAGE_SMS));
        final int page = s;
        step = page;
        content.removeAllViews();
        if (page == PAGE_WELCOME) buildWelcome();
        else if (page == PAGE_PRIVACY) buildPrivacy();
        else buildSms();
        updateDots();
        boolean last = page == PAGE_SMS;
        boolean granted = android.os.Build.VERSION.SDK_INT < 23
            || checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
        primary.setText(getString(last
            ? (granted ? R.string.onboarding_continue : R.string.onboarding_allow_sms)
            : R.string.onboarding_next));
        primary.setOnClickListener(v -> {
            if (last) {
                if (granted) finishAsSeen();
                else requestPermissions(new String[]{Manifest.permission.READ_SMS}, SMS_REQUEST);
            } else {
                showStep(page + 1);
            }
        });
    }

    /** A horizontal swipe on the content area steps between the pages in the reading direction:
     *  forward is left in LTR and right in RTL, with the backward swipe the opposite way. A vertical
     *  drag keeps scrolling the content. */
    private void attachSwipe() {
        scroll.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    break;
                case MotionEvent.ACTION_UP: {
                    float dx = e.getX() - downX;
                    float dy = e.getY() - downY;
                    int slop = ViewConfiguration.get(this).getScaledTouchSlop();
                    if (Math.abs(dx) > Math.abs(dy) * 2 && Math.abs(dx) >= slop * 2) {
                        boolean rtl = getResources().getConfiguration().getLayoutDirection()
                            == View.LAYOUT_DIRECTION_RTL;
                        int forward = rtl ? -1 : 1;
                        showStep(step + (dx < 0 ? forward : -forward));
                        return true;
                    }
                    break;
                }
            }
            return false;
        });
    }

    private void updateDots() {
        dotsRow.removeAllViews();
        for (int i = 0; i < 3; i++) {
            View dot = new View(this);
            dot.setBackground(rounded(i == step ? accent : divider, 4));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(8), dp(8));
            p.setMargins(dp(5), 0, dp(5), 0);
            dotsRow.addView(dot, p);
        }
    }

    private void buildWelcome() {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(dp(20), dp(24), dp(20), dp(24));
        hero.setBackground(rounded(color(R.color.hero), 22));
        TextView mark = text("B", 25, bg);
        mark.setGravity(Gravity.CENTER);
        mark.setTypeface(null, Typeface.BOLD);
        mark.setBackground(rounded(accent, 16));
        hero.addView(mark, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView title = text(getString(R.string.app_name), 22, fg);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, dp(14), 0, dp(2));
        hero.addView(title);
        hero.addView(text(getString(R.string.tagline_offline_bank_balance), 13, subtitle));
        content.addView(hero, margin(0, dp(10), 0, dp(18)));

        TextView heading = text(getString(R.string.onboarding_welcome_title), 19, fg);
        heading.setTypeface(null, Typeface.BOLD);
        content.addView(heading, margin(0, 0, 0, dp(8)));
        TextView body = paragraph(getString(R.string.onboarding_welcome_body), 14, muted);
        body.setLineSpacing(2, 1.05f);
        content.addView(body, margin(0, 0, 0, dp(10)));
        addBullet(getString(R.string.onboarding_feature_local), 0);
        addBullet(getString(R.string.onboarding_feature_balance), dp(10));
        addBullet(getString(R.string.onboarding_feature_history), dp(10));
        addBullet(getString(R.string.onboarding_feature_widget), dp(10));
    }

    private void addBullet(String s, int topMargin) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        dot.setBackground(rounded(active, 4));
        row.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.setMarginStart(dp(8));
        TextView t = paragraph(s, 14, fg);
        t.setLineSpacing(2, 1.05f);
        row.addView(t, tp);
        content.addView(row, margin(0, topMargin, 0, 0));
    }

    private void buildPrivacy() {
        TextView heading = text(getString(R.string.onboarding_privacy_title), 19, fg);
        heading.setTypeface(null, Typeface.BOLD);
        content.addView(heading, margin(0, dp(10), 0, dp(8)));
        TextView body = paragraph(getString(R.string.onboarding_privacy_body), 14, muted);
        body.setLineSpacing(2, 1.05f);
        content.addView(body, margin(0, 0, 0, dp(10)));
        addPrivacyCard(getString(R.string.onboarding_p_offline), 0);
        addPrivacyCard(getString(R.string.onboarding_p_local), dp(10));
        addPrivacyCard(getString(R.string.onboarding_p_bottom), dp(10));
        addPrivacyCard(getString(R.string.onboarding_p_lock), dp(10));
    }

    private void addPrivacyCard(String s, int topMargin) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(13), dp(16), dp(13));
        box.setBackground(rounded(panel, 15));
        TextView t = paragraph(s, 14, fg);
        t.setLineSpacing(2, 1.05f);
        box.addView(t);
        content.addView(box, margin(0, topMargin, 0, 0));
    }

    private void buildSms() {
        TextView heading = text(getString(R.string.onboarding_sms_title), 19, fg);
        heading.setTypeface(null, Typeface.BOLD);
        content.addView(heading, margin(0, dp(10), 0, dp(8)));
        TextView body = paragraph(getString(R.string.onboarding_sms_body), 14, muted);
        body.setLineSpacing(2, 1.05f);
        content.addView(body, margin(0, 0, 0, dp(12)));
        if (step == PAGE_SMS && android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(16), dp(13), dp(16), dp(13));
            box.setBackground(rounded(panel, 15));
            TextView t = paragraph(getString(R.string.onboarding_sms_granted), 14, fg);
            t.setLineSpacing(2, 1.05f);
            box.addView(t);
            content.addView(box);
        }
    }

    /** Skipping, backing out or finishing any step ends the introduction for good. */
    private void finishAsSeen() {
        BalanceData.setOnboardingSeen(this, true);
        if (lockOverlay.isShowing()) lockOverlay.hide();
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r, p, g);
        if (r == SMS_REQUEST) finishAsSeen();
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
    protected void onResume() {
        super.onResume();
        LockManager.cancelPendingLock();
        if (LockManager.isEnabled(this) && LockManager.isSessionLocked()
                && lockOverlay != null && !lockOverlay.isShowing()) {
            lockOverlay.showLock();
        }
    }

    @Override
    protected void onPause() {
        if (LockManager.isEnabled(this)) {
            LockManager.scheduleLock(this);
            if (LockManager.isSessionLocked()) {
                lockOverlay.showLock();
                lockOverlay.setAutoFingerprintEnabled(false);
            }
        }
        updateSecureFlag();
        super.onPause();
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
    public void onBackPressed() {
        if (lockOverlay.isShowing()) {
            super.onBackPressed();
            return;
        }
        finishAsSeen();
    }

    /** While the lock is enabled, keep the intro (and recents/screenshots) as private as the rest of
     *  the app. */
    private void updateSecureFlag() {
        if (LockManager.isEnabled(this)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
}