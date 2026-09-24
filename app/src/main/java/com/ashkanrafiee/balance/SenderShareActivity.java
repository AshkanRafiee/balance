package com.ashkanrafiee.balance;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
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

/** Sender report chooser: the confirmation step before any unrecognized-sender text leaves the
 *  device. Lists the sender's messages with a checkbox each so the user picks exactly which samples
 *  to include — nothing is preselected. The action buttons show a live count of the chosen messages;
 *  nothing else happens until the user taps Copy selected or Send, and Send itself opens a prefilled
 *  email to the maintainer through the system chooser, which is the final approval. */
public final class SenderShareActivity extends Activity {
    static final String EXTRA_SENDER = "sender";
    static final String EXTRA_BANK = "bank";
    static final String EXTRA_MESSAGES = "messages";
    static final String MAILTO = "balance.plausible268@passmail.net";

    private static final String TAG = "SenderShare";

    /** How long a copied report stays in the system clipboard before it is cleared (see
     *  {@link #copySelected}); the report holds raw message text, so it must not linger. */
    private static final long CLIP_CLEAR_MS = 15_000L;
    private static final Handler HANDLER = new Handler(android.os.Looper.getMainLooper());

    int bg, card, muted, accent, heroColor, fg;
    private String sender;
    private String bank;
    private List<String> bodies = new ArrayList<>();
    private List<CheckBox> checks = new ArrayList<>();
    private List<CheckBox> issueChecks = new ArrayList<>();
    private LinearLayout body;
    private TextView select, copy, send;
    private Runnable clearClipRunnable;
    private boolean allChecked = false;

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
        sender = getIntent().getStringExtra(EXTRA_SENDER);
        if (sender == null) sender = "";
        bank = getIntent().getStringExtra(EXTRA_BANK);
        ArrayList<String> b = getIntent().getStringArrayListExtra(EXTRA_MESSAGES);
        if (b != null) bodies.addAll(b);

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
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
        titleParams.setMarginStart(dp(10));
        TextView title = text(sender, 20, fg);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        bar.addView(title, titleParams);
        root.addView(bar, margin(0, 0, 0, 8));

        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -1));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        if (bank != null) {
            TextView known = text(getString(R.string.sender_share_known_bank, bank), 13, accent);
            known.setTypeface(null, Typeface.BOLD);
            body.addView(known, margin(2, 0, 2, 6));
        }
        body.addView(section(getString(R.string.sender_share_note, bodies.size())), margin(2, 2, 2, 10));
        issueCard();
        messagesCard();
        body.addView(privacyNote(), margin(18, 14, 18, 0));
        root.addView(actionBar(), margin(0, 14, 0, 0));
    }

    /** What the user believes is wrong with this sender, used to point the maintainer at the
     *  failing stage of detection: the account, the balance, the sender number, or any mix. The
     *  checks mirror the {@link ScanDiagnostics#ISSUE_*} constants one-to-one in order. */
    private void issueCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(10), dp(16), dp(10));
        box.setBackground(rounded(card, 15));
        box.addView(section(getString(R.string.sender_share_issue_title)), margin(2, 0, 2, 6));
        String[] labels = {
            getString(R.string.sender_share_issue_account),
            getString(R.string.sender_share_issue_balance),
            getString(R.string.sender_share_issue_number)
        };
        for (String label : labels) box.addView(issueRow(label));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        body.addView(box, lp);
    }

    private LinearLayout issueRow(String label) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(6), 0, dp(6));
        CheckBox box = new CheckBox(this);
        box.setChecked(false);
        issueChecks.add(box);
        line.addView(box, new LinearLayout.LayoutParams(dp(46), -2));
        TextView preview = text(label, 13, fg);
        line.addView(preview, new LinearLayout.LayoutParams(0, -2, 1));
        line.setOnClickListener(v -> box.setChecked(!box.isChecked()));
        return line;
    }

    /** The picked issue categories as report tags, in the order the checkboxes show them. */
    private List<String> issueTags() {
        String[] all = {ScanDiagnostics.ISSUE_ACCOUNT, ScanDiagnostics.ISSUE_BALANCE,
            ScanDiagnostics.ISSUE_NUMBER};
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < issueChecks.size() && i < all.length; i++)
            if (issueChecks.get(i).isChecked()) tags.add(all[i]);
        return tags;
    }

    /** Which required piece of the report selection is missing, or 0 when sending/copying is
     *  allowed. A report leaves the device only with at least one diagnosed issue and at least
     *  one message, so the maintainer gets both context and content to reproduce a format. */
    static int missingSelection(boolean issueChosen, int messagesChosen) {
        if (!issueChosen) return R.string.sender_share_pick_issue;
        if (messagesChosen == 0) return R.string.sender_share_pick_one;
        return 0;
    }

    private void messagesCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(10), dp(16), dp(10));
        box.setBackground(rounded(card, 15));
        if (bodies.isEmpty()) {
            box.addView(section(getString(R.string.sender_share_no_messages)), margin(2, 4, 2, 4));
        }
        for (String b : bodies) box.addView(messageRow(b));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        body.addView(box, lp);
    }

    private LinearLayout messageRow(String b) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(6), 0, dp(6));
        CheckBox box = new CheckBox(this);
        box.setChecked(false);
        checks.add(box);
        line.addView(box, new LinearLayout.LayoutParams(dp(46), -2));
        TextView preview = new TextView(this);
        preview.setTextSize(13);
        preview.setTextColor(fg);
        preview.setMaxLines(4);
        preview.setEllipsize(TextUtils.TruncateAt.END);
        String flat = b.replace("\n", " ");
        preview.setText(flat);
        line.addView(preview, new LinearLayout.LayoutParams(0, -2, 1));
        line.setOnClickListener(v -> {
            box.setChecked(!box.isChecked());
            updateButtons();
        });
        box.setOnClickListener(v -> updateButtons());
        return line;
    }

    private LinearLayout actionBar() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        select = text(getString(R.string.sender_share_select_all), 13, fg);
        select.setGravity(Gravity.CENTER);
        select.setPadding(dp(14), dp(11), dp(14), dp(11));
        select.setMinHeight(dp(48));
        select.setBackground(rounded(heroColor, 13));
        select.setOnClickListener(v -> {
            allChecked = !allChecked;
            for (CheckBox c : checks) c.setChecked(allChecked);
            select.setText(getString(allChecked ? R.string.sender_share_select_none
                : R.string.sender_share_select_all));
            updateButtons();
        });
        row.addView(select);

        copy = text(getString(R.string.sender_share_copy, 0), 13, fg);
        copy.setGravity(Gravity.CENTER);
        copy.setPadding(dp(14), dp(11), dp(14), dp(11));
        copy.setMinHeight(dp(48));
        LinearLayout.LayoutParams copyP = new LinearLayout.LayoutParams(-2, -2);
        copyP.setMarginStart(dp(10));
        copy.setBackground(rounded(heroColor, 13));
        copy.setOnClickListener(v -> {
            int n = copySelected();
            if (n > 0) Toast.makeText(this, getString(R.string.sender_share_copied, n), Toast.LENGTH_SHORT).show();
        });
        row.addView(copy, copyP);

        send = text(getString(R.string.sender_share_send, 0), 14, bg);
        send.setGravity(Gravity.CENTER);
        send.setTypeface(null, Typeface.BOLD);
        send.setPadding(dp(18), dp(11), dp(18), dp(11));
        send.setMinHeight(dp(48));
        LinearLayout.LayoutParams sendP = new LinearLayout.LayoutParams(0, -2, 1);
        sendP.setMarginStart(dp(10));
        send.setBackground(rounded(accent, 13));
        send.setOnClickListener(v -> {
            int missing = missingSelection(!issueTags().isEmpty(), selected().size());
            if (missing != 0) {
                Toast.makeText(this, missing, Toast.LENGTH_SHORT).show();
                return;
            }
            sendMail(selectedText());
        });
        row.addView(send, sendP);
        return row;
    }

    private void updateButtons() {
        int n = selected().size();
        copy.setText(getString(R.string.sender_share_copy, n));
        send.setText(getString(R.string.sender_share_send, n));
    }

    private List<ScanDiagnostics.Message> selected() {
        List<ScanDiagnostics.Message> sel = new ArrayList<>();
        for (int i = 0; i < checks.size() && i < bodies.size(); i++)
            if (checks.get(i).isChecked()) sel.add(new ScanDiagnostics.Message(bodies.get(i), 0L));
        return sel;
    }

    private String selectedText() {
        List<ScanDiagnostics.Message> sel = selected();
        if (sel.isEmpty()) return null;
        return ScanDiagnostics.senderReport(sender, bodies.size(), sel, issueTags());
    }

    private int copySelected() {
        int missing = missingSelection(!issueTags().isEmpty(), selected().size());
        if (missing != 0) {
            Toast.makeText(this, missing, Toast.LENGTH_SHORT).show();
            return 0;
        }
        String report = selectedText();
        int n = selected().size();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(sender, report));
        // The report holds raw message text; do not leave it readably in the system clipboard for any
        // longer than the paste window. Clear it again once that has passed, unless the user copied
        // something else in the meantime (then that newer clip is left alone).
        if (clearClipRunnable != null) HANDLER.removeCallbacks(clearClipRunnable);
        clearClipRunnable = () -> {
            clearClipRunnable = null;
            try {
                CharSequence current = clipboard.hasPrimaryClip()
                    && clipboard.getPrimaryClip() != null
                    && clipboard.getPrimaryClip().getItemCount() > 0
                    ? clipboard.getPrimaryClip().getItemAt(0).getText() : null;
                if (report.equals(String.valueOf(current))) {
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        clipboard.clearPrimaryClip();
                    } else {
                        clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
                    }
                }
            } catch (Exception e) {
                // On Android 10+ a background process may be denied reading a clip another app
                // has taken; fail as cleared and keep the app alive.
                Log.w(TAG, "clipboard read failed", e);
            }
        };
        HANDLER.postDelayed(clearClipRunnable, CLIP_CLEAR_MS);
        return n;
    }

    /** Prefills a mail to the maintainer with the chosen messages. The system chooser is the user's
     *  final approval: nothing is sent until they pick an app and press send there. */
    private void sendMail(String report) {
        final String subject = ScanDiagnostics.senderSubject(sender);
        try {
            Intent mail = new Intent(Intent.ACTION_SENDTO, Uri.parse(mailToUri(subject, report)));
            mail.putExtra(Intent.EXTRA_SUBJECT, subject);
            mail.putExtra(Intent.EXTRA_TEXT, report);
            startActivity(Intent.createChooser(mail, getString(R.string.sender_share_send_via)));
        } catch (Exception e) {
            Log.w(TAG, "no mail app; falling back to the share sheet");
            try {
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_SUBJECT, subject);
                share.putExtra(Intent.EXTRA_TEXT, report);
                startActivity(Intent.createChooser(share, getString(R.string.sender_share_send_via)));
            } catch (Exception e2) {
                Log.w(TAG, "no share target at all");
            }
        }
    }

    /** mailto: URI that also carries the subject and body as query parameters. The recipient always
     *  reaches the compose draft because it lives in the URI's path, but Android mail clients
     *  disagree on where to read the rest: AOSP-style apps honour the Intent extras, while Gmail and
     *  ProtonMail rebuild the compose screen from the URI itself and ignore them. Carrying the
     *  fields in both places — the URI here, the extras in {@link #sendMail} — fills every client. */
    static String mailToUri(String subject, String report) {
        return "mailto:" + MAILTO + "?subject=" + Uri.encode(subject) + "&body=" + Uri.encode(report);
    }

    TextView section(String s) {
        TextView v = text(s, 12, muted);
        v.setLineSpacing(2, 1.05f);
        return v;
    }

    TextView privacyNote() {
        TextView v = text(getString(R.string.sender_share_privacy), 11, muted);
        v.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        return v;
    }

    // ---- Lock handling: identical to the other screens ----

    private LockOverlay lockOverlay;

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