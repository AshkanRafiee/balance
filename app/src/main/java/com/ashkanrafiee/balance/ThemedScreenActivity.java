package com.ashkanrafiee.balance;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The chrome every settings screen in this app is built from: the app's own locale and theme, the
 * palette and drawing helpers that follow from them, a title bar with a back arrow, one scrolling
 * column to fill, and the app-lock handling that must behave identically on every screen.
 *
 * <p>This exists because those parts are not the screen: they are the app. A screen that grows its
 * own copy of them is a screen whose lock handling or inset padding can drift from every other
 * screen's, and the drift is invisible until a phone with a cutout or an enabled lock tries it.
 */
abstract class ThemedScreenActivity extends Activity {

    int bg, card, muted, accent, hero, fg;
    LockOverlay lockOverlay;
    /** The scrolling column a subclass fills. */
    LinearLayout body;

    final int color(int res) {
        return getResources().getColor(res, getTheme());
    }

    final int dp(float n) {
        return (int) (n * getResources().getDisplayMetrics().density + .5f);
    }

    final TextView text(String s, float size, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    final GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    final LinearLayout.LayoutParams margin(int l, int t, int r, int b) {
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
        card = color(R.color.panel);
        muted = color(R.color.muted);
        accent = color(R.color.accent);
        hero = color(R.color.hero);
        fg = color(R.color.fg);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().setBackgroundDrawable(new ColorDrawable(bg));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
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
            v.setPadding(dp(20), top + dp(8), dp(20), bottom + dp(8));
            return i;
        });
        FrameLayout host = new FrameLayout(this);
        setContentView(host);
        host.addView(root, new FrameLayout.LayoutParams(-1, -1));

        lockOverlay = new LockOverlay(this);
        lockOverlay.setUnlockListener(this::updateSecureFlag);
        lockOverlay.setCancelListener(() -> lockOverlay.hide());
        host.addView(lockOverlay, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        lockOverlay.setVisibility(View.GONE);
        updateSecureFlag();

        root.addView(titleBar(title()), margin(0, 0, 0, 6));
        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -1));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        render();
    }

    /** The screen's own title, drawn once in onCreate. */
    abstract String title();

    /** Fills {@link #body}. Called on create and again whenever the screen redraws itself. */
    abstract void render();

    /** Replaces the column, for a screen that redraws rather than appending. */
    final void redraw() {
        if (body == null) return;
        body.removeAllViews();
        render();
    }

    /** A back arrow and a title, in the reading direction of the current locale. */
    private LinearLayout titleBar(String title) {
        boolean rtl = getResources().getConfiguration().getLayoutDirection()
            == View.LAYOUT_DIRECTION_RTL;
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text(rtl ? "›" : "‹", 34, fg);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(getString(R.string.about_back));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(48)));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2);
        titleParams.setMarginStart(dp(10));
        bar.addView(text(title, 21, fg), titleParams);
        return bar;
    }

    /** A section heading: small, bold, and in caps, like the headings on the other screens. */
    final void sectionLabel(String label, int l, int t, int r, int b) {
        TextView v = text(label, 12, muted);
        v.setTypeface(null, Typeface.BOLD);
        v.setAllCaps(true);
        body.addView(v, margin(l, t, r, b));
    }

    /** A tappable card, which is how every row on every screen in this app is drawn. */
    final LinearLayout cardRow() {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(14), dp(10), dp(10), dp(10));
        line.setBackground(rounded(card, 13));
        return line;
    }

    /** A secondary action inside a row: a short label, drawn quiet until the row is pressed. */
    final TextView action(String label) {
        TextView v = text(label, 13, accent);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(10), dp(6), dp(10), dp(6));
        return v;
    }

    /** Asks the system file picker for something to read. The type is left open on purpose: a pack
     *  is a plain JSON document, and a provider that files one under {@code application/octet-stream}
     *  or nothing at all would otherwise be unable to show it to the user at all. */
    final void pickFile(String type, int request) {
        Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        open.addCategory(Intent.CATEGORY_OPENABLE);
        open.setType(type);
        startActivityForResult(open, request);
    }

    /** Asks the system file picker where to write something, suggesting a file name. */
    final void createFile(String type, String name, int request) {
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType(type);
        create.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(create, request);
    }

    final void toast(int res) {
        android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_LONG).show();
    }

    // ---- Lock handling: identical on every screen ----

    void updateSecureFlag() {
        if (LockManager.isEnabled(this)) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        LockManager.registerActivityStart(this);
        if (LockManager.isEnabled(this) && LockManager.isSessionLocked()) lockOverlay.showLock();
        else lockOverlay.hide();
        updateSecureFlag();
    }

    @Override
    public void onResume() {
        super.onResume();
        LockManager.cancelPendingLock();
        if (LockManager.isEnabled(this) && LockManager.isSessionLocked()
                && lockOverlay != null && !lockOverlay.isShowing()) lockOverlay.showLock();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (LockManager.isEnabled(this)) {
            LockManager.scheduleLock(this);
            if (LockManager.isSessionLocked()) {
                lockOverlay.showLock();
                lockOverlay.setAutoFingerprintEnabled(false);
            }
        }
        updateSecureFlag();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (LockManager.isEnabled(this) && LockManager.registerActivityStop()) {
            lockOverlay.showLock();
            lockOverlay.setAutoFingerprintEnabled(false);
        } else {
            lockOverlay.hide();
        }
        updateSecureFlag();
    }
}