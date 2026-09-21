package com.ashkanrafiee.balance;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.provider.Telephony;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Scan diagnostics: shows which SMS Balance recognized fully (per bank) and which it could not
 *  parse — senders that are a known bank's but whose message layout failed, plus wholly unknown
 *  senders, each with its newest sample. Every flagged sender is a compact, tappable entry that
 *  opens its message chooser, where the user ticks the exact messages to share (nothing is
 *  preselected) and copies or sends them from there — the checkmarks live inside the messages,
 *  not on this screen. The inbox is read on demand for this screen only — nothing is stored, so
 *  the feature adds no new persisted data to a device. */
public final class ScanDiagnosticsActivity extends Activity {
    private static final String TAG = "ScanDiag";
    int bg, card, muted, accent, heroColor, fg;
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

    /** Message counts in the app language's digits, so a Persian screen never mixes scripts. */
    private String count(int n) {
        String s = String.valueOf(n);
        return LocaleHelper.isPersian(this) ? HistoryActivity.faDigitsString(s) : s;
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

    /** Highlights a card with the accent color as a translucent wash, for the format gaps a user
     *  should not miss (like a known bank whose new layout we could not parse). */
    int tinted(int base) {
        return (base & 0xFFFFFF) | 0x1F000000;
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
        heroColor = color(R.color.hero);
        fg = color(R.color.fg);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bg));
        boolean rtl = getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

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
        TextView back = text(rtl ? "›" : "‹", 34, fg);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(getString(R.string.about_back));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(48)));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2);
        titleParams.setMarginStart(dp(10));
        bar.addView(text(getString(R.string.scan_diag_title), 21, fg), titleParams);
        root.addView(bar, margin(0, 0, 0, 12));

        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -1));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionCard();
            return;
        }
        TextView reading = text(getString(R.string.scan_diag_reading), 14, muted);
        reading.setGravity(Gravity.CENTER);
        reading.setPadding(0, dp(40), 0, dp(40));
        body.addView(reading, margin(0, 0, 0, 0));
        readInBackground();
    }

    private void readInBackground() {
        new Thread(() -> {
            List<Object[]> rows = new ArrayList<>();
            try (android.database.Cursor cursor = getContentResolver().query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE},
                    null, null, Telephony.Sms.DATE + " DESC")) {
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        rows.add(new Object[]{
                            cursor.getString(0), cursor.getString(1), cursor.getLong(2)});
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "inbox read failed", e);
            }
            final ScanDiagnostics.Summary summary = ScanDiagnostics.analyze(rows);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                body.removeAllViews();
                render(summary);
            });
        }).start();
    }

    private void render(ScanDiagnostics.Summary s) {
        summaryCard(s);
        recognizedCard(s);
        if (s.unparsedSenders.isEmpty() && s.unknownSenders.isEmpty()) {
            body.addView(section(getString(R.string.scan_diag_none_skipped)), margin(0, 12, 0, 0));
            return;
        }
        unparsedCard(s);
        unknownCard(s);
        body.addView(privacyNote(), margin(18, 14, 18, 0));
    }

    /** The banks Balance parsed successfully and how many of their messages — the workload that is
     *  already covered, shown so the contribution funnel is easy to weigh. */
    private void recognizedCard(ScanDiagnostics.Summary s) {
        if (s.banks.isEmpty()) return;
        LinearLayout box = cardBox();
        box.addView(cardTitle(getString(R.string.scan_diag_recognized_banks)));
        for (ScanDiagnostics.BankHit h : s.banks) {
            LinearLayout line = new LinearLayout(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = text(BankRules.displayName(this, h.bank), 14, fg);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView count = text(count(h.messages), 13, muted);
            LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(-2, -2);
            countLp.setMarginStart(dp(10));
            line.addView(count, countLp);
            line.setPadding(dp(4), dp(3), dp(4), dp(3));
            box.addView(line);
        }
        body.addView(box, margin(0, 0, 0, 12));
    }

    private void summaryCard(ScanDiagnostics.Summary s) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.setBackground(rounded(heroColor, 18));
        box.addView(text(getString(R.string.scan_diag_recognized), 13, muted));
        TextView v = text(count(s.parsedMessages), 26, fg);
        v.setTypeface(null, Typeface.BOLD);
        box.addView(v);
        box.addView(text(getString(R.string.scan_diag_summary, s.messages, s.unparsedMessages()), 12, muted));
        body.addView(box, margin(0, 0, 0, 14));
    }

    /** Known-bank senders whose message layout did not parse. These are easy to miss — a supported
     *  bank that changed its SMS — so they get the accent wash and come first. */
    private void unparsedCard(ScanDiagnostics.Summary s) {
        if (s.unparsedSenders.isEmpty()) return;
        LinearLayout box = cardBox();
        box.setBackground(rounded(tinted(accent), 15));
        box.addView(cardTitle(getString(R.string.scan_diag_unparsed_banks_title)));
        box.addView(section(getString(R.string.scan_diag_unparsed_bank_hint)), margin(2, 0, 2, 6));
        for (ScanDiagnostics.SenderHit h : s.unparsedSenders) {
            addSenderRow(box, h, true);
        }
        body.addView(box, margin(0, 0, 0, 12));
    }

    private void unknownCard(ScanDiagnostics.Summary s) {
        if (s.unknownSenders.isEmpty()) return;
        LinearLayout box = cardBox();
        box.addView(cardTitle(getString(R.string.scan_diag_skipped_senders)));
        box.addView(section(getString(R.string.scan_diag_skipped_hint)), margin(2, 0, 2, 6));
        for (ScanDiagnostics.SenderHit h : s.unknownSenders) {
            addSenderRow(box, h, false);
        }
        body.addView(box, margin(0, 0, 0, 12));
    }

    /** One compact, obviously-tappable sender: bank name when known + sender on the first line, the
     *  newest-message preview below, a count and a chevron on the right. Tapping opens the message
     *  chooser — the checkmarks live on the messages themselves inside that screen. */
    private void addSenderRow(LinearLayout in, ScanDiagnostics.SenderHit h, boolean knownBank) {
        boolean rtl = getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(12), dp(8), dp(10), dp(8));
        line.setBackground(rounded(knownBank
            ? (accent & 0xFFFFFF) | 0x1C000000
            : (fg & 0xFFFFFF) | 0x0D000000, 13));
        in.addView(line, margin(0, 0, 0, 6));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        if (knownBank) {
            TextView bank = text(BankRules.displayName(this, h.bank), 13, fg);
            bank.setTypeface(null, Typeface.BOLD);
            texts.addView(bank, new LinearLayout.LayoutParams(-1, -2));
        }
        TextView who = text(h.sender, 14, fg);
        who.setMaxLines(1);
        who.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        TextView preview = text(h.stored.isEmpty() ? "" : h.stored.get(0).body.replace("\n", " "), 12, muted);
        preview.setMaxLines(1);
        preview.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(who, new LinearLayout.LayoutParams(-1, -2));
        texts.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        line.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        TextView count = text(count(h.messages), 13, muted);
        count.setPadding(dp(10), 0, dp(4), 0);
        right.addView(count);
        TextView chevron = text(rtl ? "\u2039" : "\u203A", 20, fg);
        right.addView(chevron);
        line.addView(right);

        line.setOnClickListener(v -> openChooser(h));
        line.setContentDescription(getString(R.string.scan_diag_open_sender, h.sender));
    }

    private void openChooser(ScanDiagnostics.SenderHit h) {
        ArrayList<String> bodies = new ArrayList<>();
        for (ScanDiagnostics.Message m : h.stored) bodies.add(m.body);
        Intent i = new Intent(this, SenderShareActivity.class);
        i.putExtra(SenderShareActivity.EXTRA_SENDER, h.sender);
        if (h.bank != null) i.putExtra(SenderShareActivity.EXTRA_BANK, h.bank);
        i.putStringArrayListExtra(SenderShareActivity.EXTRA_MESSAGES, bodies);
        startActivity(i);
    }

    private void permissionCard() {
        LinearLayout box = cardBox();
        box.addView(section(getString(R.string.scan_diag_permission)), margin(2, 0, 2, 12));
        box.addView(button(getString(R.string.scan_diag_permission_action), this::openSmsSettings));
        body.addView(box, margin(0, 0, 0, 0));
    }

    private void openSmsSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)));
        } catch (Exception e) {
            finish();
        }
    }

    TextView button(String label, Runnable action) {
        TextView b = text(label, 14, fg);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(null, Typeface.BOLD);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        b.setBackground(rounded(accent, 14));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    LinearLayout cardBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(13), dp(14), dp(13));
        box.setBackground(rounded(card, 15));
        return box;
    }

    TextView cardTitle(String s) {
        TextView v = text(s, 13, muted);
        v.setPadding(0, 0, 0, dp(8));
        return v;
    }

    TextView section(String s) {
        TextView v = text(s, 12, muted);
        v.setLineSpacing(2, 1.05f);
        return v;
    }

    TextView privacyNote() {
        TextView v = text(getString(R.string.scan_diag_privacy), 11, muted);
        v.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        return v;
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
    protected void onResume() {
        super.onResume();
        LockManager.cancelPendingLock();
        if (LockManager.isEnabled(this) && LockManager.isSessionLocked()
                && lockOverlay != null && !lockOverlay.isShowing()) lockOverlay.showLock();
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
}