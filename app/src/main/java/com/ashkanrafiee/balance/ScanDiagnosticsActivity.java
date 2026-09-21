package com.ashkanrafiee.balance;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Scan diagnostics: shows which SMS Balance recognized fully (per bank) and which it could not
 *  parse — senders that are a known bank's but whose message layout failed, plus wholly unknown
 *  senders, each with its newest sample. The user ticks the rows to include (nothing is preselected)
 *  and the report buttons copy or email exactly those, pre-filled; email goes to the maintainer's
 *  address and is only sent after the user confirms in their mail app. The inbox is read on demand
 *  for this screen only — nothing is stored, so the feature adds no new persisted data to a device. */
public final class ScanDiagnosticsActivity extends Activity {
    private static final String TAG = "ScanDiag";
    int bg, card, muted, accent, heroColor, fg;
    private LockOverlay lockOverlay;
    private LinearLayout body;
    private final List<ScanDiagnostics.SenderHit> problems = new ArrayList<>();
    private final List<CheckBox> problemChecks = new ArrayList<>();
    private TextView copyButton, emailButton;

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

    /** Highlights a card with the accent color as a translucent wash, for the format gaps a user
     *  should not miss (like a known bank whose new layout we could not parse). */
    int tinted(int base) {
        return (base & 0xFFFFFF) | 0x1F000000;
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
        problems.clear();
        problemChecks.clear();
        problems.addAll(ScanDiagnostics.problemSenders(s));
        if (problems.isEmpty()) {
            body.addView(section(getString(R.string.scan_diag_none_skipped)), margin(0, 12, 0, 0));
            return;
        }
        unparsedCard(s);
        unknownCard(s);
        body.addView(privacyNote(), margin(18, 14, 18, 0));
        body.addView(actionButtons(), margin(0, 14, 0, 0));
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
            TextView name = text(h.bank, 14, fg);
            line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView count = text(String.valueOf(h.messages), 13, muted);
            line.addView(count);
            line.setPadding(0, dp(2), 0, dp(2));
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
        TextView v = text(String.valueOf(s.parsedMessages), 26, fg);
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
            problemChecks.add(addProblemRow(box, h, true));
        }
        body.addView(box, margin(0, 0, 0, 12));
    }

    private void unknownCard(ScanDiagnostics.Summary s) {
        if (s.unknownSenders.isEmpty()) return;
        LinearLayout box = cardBox();
        box.addView(cardTitle(getString(R.string.scan_diag_skipped_senders)));
        box.addView(section(getString(R.string.scan_diag_skipped_hint)), margin(2, 0, 2, 6));
        for (ScanDiagnostics.SenderHit h : s.unknownSenders) {
            problemChecks.add(addProblemRow(box, h, false));
        }
        body.addView(box, margin(0, 0, 0, 12));
    }

    /** One selectable problem sender: bank line (for known banks) + sender + newest-message preview,
     *  a checkbox on the right to include it, and a tap anywhere to open its message-level chooser. */
    private CheckBox addProblemRow(LinearLayout in, ScanDiagnostics.SenderHit h, boolean knownBank) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.VERTICAL);
        line.setPadding(0, dp(5), 0, dp(5));
        in.addView(line);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox box = new CheckBox(this);
        box.setChecked(false);
        head.addView(box, new LinearLayout.LayoutParams(dp(46), -2));
        LinearLayout headTexts = new LinearLayout(this);
        headTexts.setOrientation(LinearLayout.VERTICAL);
        if (knownBank) {
            TextView bank = text(h.bank, 14, fg);
            bank.setTypeface(null, Typeface.BOLD);
            headTexts.addView(bank, new LinearLayout.LayoutParams(0, -2, 1));
        }
        TextView who = text(h.sender, 14, knownBank ? fg : fg);
        headTexts.addView(who, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(headTexts, new LinearLayout.LayoutParams(0, -2, 1));
        TextView count = text(String.valueOf(h.messages), 13, muted);
        head.addView(count);
        line.addView(head, margin(0, 0, 0, 0));

        TextView preview = new TextView(this);
        preview.setTextSize(12);
        preview.setTextColor(muted);
        preview.setMaxLines(2);
        preview.setEllipsize(TextUtils.TruncateAt.END);
        preview.setText(h.stored.isEmpty() ? "" : h.stored.get(0).body.replace("\n", " "));
        preview.setPadding(dp(46), 0, 0, dp(4));
        line.addView(preview);

        line.setOnClickListener(v -> openChooser(h));
        box.setOnClickListener(v -> updateButtons());
        return box;
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

    private List<ScanDiagnostics.SenderHit> selectedProblems() {
        List<ScanDiagnostics.SenderHit> sel = new ArrayList<>();
        for (int i = 0; i < problems.size() && i < problemChecks.size(); i++)
            if (problemChecks.get(i).isChecked()) sel.add(problems.get(i));
        return sel;
    }

    private void updateButtons() {
        int n = selectedProblems().size();
        copyButton.setText(getString(R.string.scan_diag_copy_selected, n));
        emailButton.setText(getString(R.string.scan_diag_email_selected, n));
    }

    private LinearLayout actionButtons() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        copyButton = button(getString(R.string.scan_diag_copy_selected, 0), () -> {
            List<ScanDiagnostics.SenderHit> sel = selectedProblems();
            if (sel.isEmpty()) { Toast.makeText(this, getString(R.string.scan_diag_pick_sender), Toast.LENGTH_SHORT).show(); return; }
            String report = ScanDiagnostics.reportText(sel);
            copy(report);
            Toast.makeText(this, getString(R.string.scan_diag_copied_report), Toast.LENGTH_SHORT).show();
        });
        emailButton = button(getString(R.string.scan_diag_email_selected, 0), () -> {
            List<ScanDiagnostics.SenderHit> sel = selectedProblems();
            if (sel.isEmpty()) { Toast.makeText(this, getString(R.string.scan_diag_pick_sender), Toast.LENGTH_SHORT).show(); return; }
            sendMail(ScanDiagnostics.reportText(sel));
        });
        row.addView(copyButton);
        row.addView(emailButton);
        return row;
    }

    /** Prefills a mail to the maintainer with the chosen report; the system chooser is the user's
     *  approval before anything leaves the device. */
    private void sendMail(String report) {
        try {
            Intent mail = new Intent(Intent.ACTION_SENDTO);
            mail.setData(Uri.parse("mailto:" + SenderShareActivity.MAILTO));
            mail.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.scan_diag_email_subject));
            mail.putExtra(Intent.EXTRA_TEXT, report);
            startActivity(Intent.createChooser(mail, getString(R.string.scan_diag_email_via)));
        } catch (Exception e) {
            Log.w(TAG, "no mail app; falling back to the share sheet");
            try {
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.scan_diag_email_subject));
                share.putExtra(Intent.EXTRA_TEXT, report);
                startActivity(Intent.createChooser(share, getString(R.string.scan_diag_email_via)));
            } catch (Exception e2) {
                Log.w(TAG, "no share target at all");
            }
        }
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

    private void copy(String content) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.scan_diag_title), content));
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