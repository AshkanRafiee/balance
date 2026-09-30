package com.ashkanrafiee.balance;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.ashkanrafiee.balance.parser.PackDocument;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Which banks Balance reads, grouped by where their formats came from, with a switch each.
 *
 *  <p>Every shipped bank is on until the reader says otherwise, and turning one off stops Balance
 *  reading its messages without touching a single stored balance, note or exclusion — this screen
 *  writes one preference and reads no financial data at all. The grouping is where the reader can
 *  check where a format came from: a bank whose messages the app was shipped understanding sits
 *  apart from one a user report got added, which is the difference between "this was always here"
 *  and "this was written from a message someone pasted in".
 *
 *  <p>There is no search field here and no verdict per bank: with tens of banks, what a reader wants
 *  is the one switch next to the name they recognize, and turning it off is a decision they make
 *  from the bank's own name, not from a count.
 */
public final class BankRecognitionActivity extends Activity {
    private int bg, card, muted, accent, fg;
    private LockOverlay lockOverlay;
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
        card = color(R.color.panel);
        muted = color(R.color.muted);
        accent = color(R.color.accent);
        fg = color(R.color.fg);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().setBackgroundDrawable(new ColorDrawable(bg));
        boolean rtl = getResources().getConfiguration().getLayoutDirection()
            == View.LAYOUT_DIRECTION_RTL;

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

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text(rtl ? "\u203a" : "\u2039", 34, fg);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(getString(R.string.about_back));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(48)));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2);
        titleParams.setMarginStart(dp(10));
        bar.addView(text(getString(R.string.recognition_title), 21, fg), titleParams);
        root.addView(bar, margin(0, 0, 0, 6));

        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -1));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        render();
    }

    /** The groups, in the order the index registers them, so a region cannot move between opens and
     *  a reader learns one layout instead of a new one each time. */
    private void render() {
        body.removeAllViews();
        TextView note = text(getString(R.string.recognition_note), 12, muted);
        note.setLineSpacing(2, 1.05f);
        body.addView(note, margin(2, 0, 2, 10));

        EngineRules engine;
        try {
            engine = EngineRules.load(this);
        } catch (IOException unavailable) {
            engine = null;
        }
        List<EngineRules.Bank> banks = engine == null ? Collections.emptyList()
            : engine.banksInOrder();
        if (banks.isEmpty()) {
            // The packs are assets of the app itself, so an empty list means the app is running
            // without them. Say so rather than drawing an empty screen that reads as "no banks".
            body.addView(text(getString(R.string.recognition_unavailable), 14, muted),
                margin(2, dp(18), 2, 0));
            return;
        }
        String region = null;
        for (EngineRules.Bank bank : banks) {
            if (!bank.region.equals(region)) {
                region = bank.region;
                sectionLabel(regionLabel(region, banks), 2, dp(16), 2, 0);
            }
            bankRow(bank);
        }
    }

    /** A group's heading: its country, and what kind of evidence its formats rest on. The two are
     *  separate because a reader's question is "where did this come from", and the honest answer can
     *  be "from a country, and from people who pasted their messages in" at the same time. The whole
     *  heading is one localized format string, so a language that puts the qualifier first is free to
     *  do so. */
    private String regionLabel(String region, List<EngineRules.Bank> banks) {
        boolean community = false;
        String country = null;
        for (EngineRules.Bank bank : banks) {
            if (!bank.region.equals(region)) continue;
            community |= bank.provenance == PackDocument.Bank.Provenance.COMMUNITY;
            if (country == null) country = bank.country;
        }
        String where = country == null || country.isEmpty() ? "" : countryLabel(country);
        return getString(community
            ? R.string.recognition_group_community : R.string.recognition_group_official, where);
    }

    /** A country in the reader's own language where the platform knows the name, and its code where it
     *  does not, because an unfamiliar code is still more honest than a guessed country name. */
    private String countryLabel(String code) {
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        String name;
        try {
            name = new Locale("", code).getDisplayCountry(locale);
        } catch (RuntimeException unknown) {
            name = null;
        }
        return name == null || name.isEmpty() ? code : name;
    }

    private void sectionLabel(String label, int l, int t, int r, int b) {
        TextView v = text(label, 12, muted);
        v.setTypeface(null, Typeface.BOLD);
        v.setAllCaps(true);
        body.addView(v, margin(l, t, r, b));
    }

    /** One bank: its name and a switch. Tapping anywhere on the row flips it, the way the message and
     *  issue rows on the report screens do, so a long bank name does not have to be aimed at. */
    private void bankRow(EngineRules.Bank bank) {
        boolean on = RecognitionHelper.isEnabled(bank.name);
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(14), dp(4), dp(10), dp(4));
        line.setBackground(rounded(card, 13));
        body.addView(line, margin(0, 6, 0, 0));

        TextView name = text(BankRules.displayName(this, bank.name), 14, on ? fg : muted);
        name.setMaxLines(2);
        line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));

        Switch toggle = new Switch(this);
        toggle.setChecked(on);
        // The row is the tap target and the switch draws the state; letting the switch take the tap
        // too would fire the row's listener a second time on some devices.
        toggle.setClickable(false);
        line.addView(toggle);

        String what = getString(on
            ? R.string.recognition_on : R.string.recognition_off, BankRules.displayName(this, bank.name));
        line.setContentDescription(what);
        line.setOnClickListener(v -> {
            // Read the stored answer back rather than assuming the tap did what it meant to, so the
            // row and the store can never disagree about what the reader just chose.
            boolean now = RecognitionHelper.setEnabled(this, bank.name, !on);
            // Only the one row changes, so nothing else on screen can disagree with the stored choice.
            renderRow(line, name, toggle, bank, now);
        });
    }

    private void renderRow(LinearLayout line, TextView name, Switch toggle,
            EngineRules.Bank bank, boolean on) {
        toggle.setChecked(on);
        name.setTextColor(on ? fg : muted);
        line.setContentDescription(getString(on
            ? R.string.recognition_on : R.string.recognition_off, BankRules.displayName(this, bank.name)));
    }

    // ---- Lock handling: identical to the other screens ----

    private void updateSecureFlag() {
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
    public void onPause() {
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
    public void onStop() {
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
