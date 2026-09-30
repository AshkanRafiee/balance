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
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The universal way into a report: every sender in the inbox, searchable, whatever Balance makes of
 *  it.
 *
 *  <p>Scan diagnostics lists only the senders it could not read, because that short list is where a
 *  format contribution starts. Reading a message is not the same as understanding all of it -- a
 *  card statement can yield its amount and day while its merchant and time of day are never
 *  modelled -- and the app has no honest way to tell that apart, so it must not quietly decide a
 *  sender is finished with. This screen therefore makes no claim at all: it lists what the inbox
 *  holds, names the bank only where the app knows one, and hands the chosen sender to the same
 *  message chooser the funnel uses. What is ticked, and what leaves the device, stays with the user.
 *
 *  <p>Like diagnostics, it reads the inbox on demand for this screen only and stores nothing.
 */
public final class SenderPickerActivity extends Activity {
    private static final String TAG = "SenderPicker";

    private int bg, card, muted, accent, fg;
    private LockOverlay lockOverlay;
    private LinearLayout body;
    private EditText search;
    private List<ScanDiagnostics.SenderHit> senders = new ArrayList<>();

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

    /** Message counts in the app language's digits, so a Persian screen never mixes scripts. */
    private String count(int n) {
        String s = String.valueOf(n);
        return LocaleHelper.isPersian(this) ? HistoryActivity.faDigitsString(s) : s;
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
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bg));
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
        bar.addView(text(getString(R.string.sender_picker_title), 21, fg), titleParams);
        root.addView(bar, margin(0, 0, 0, 10));

        addSearchField(root);

        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -1));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionCard();
            return;
        }
        reading();
        readInBackground();
    }

    /** The search box. It stays hidden until there is something to search, so the permission and
     *  loading states are not stacked under a field that cannot do anything yet. */
    private void addSearchField(LinearLayout root) {
        search = new EditText(this);
        search.setVisibility(View.GONE);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(fg);
        search.setHintTextColor(muted);
        search.setHint(getString(R.string.sender_picker_search));
        search.setPadding(dp(12), dp(9), dp(12), dp(9));
        search.setBackground(rounded(card, 13));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { renderList(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        root.addView(search, margin(0, 0, 0, 10));
    }

    private void reading() {
        TextView view = text(getString(R.string.scan_diag_reading), 14, muted);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, dp(40), 0, dp(40));
        body.addView(view);
    }

    private void readInBackground() {
        new Thread(() -> {
            // The same activation the scan performs before it reads the inbox. Deciding what the
            // app makes of a message must not depend on a scan having happened first: without this,
            // a bank only a community pack covers looks unrecognized here while the dashboard
            // already shows its balances.
            EngineRules.activate(getApplicationContext());
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
            final List<ScanDiagnostics.SenderHit> all = ScanDiagnostics.inboxSenders(rows);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                senders = all;
                search.setVisibility(View.VISIBLE);
                renderList();
            });
        }).start();
    }

    /** Redraws the senders the current search matches. The filter is on the sender address alone:
     *  matching message text would put bank message contents into a search field, which is not what
     *  this screen is for, and the sender is what a report is addressed to. */
    private void renderList() {
        if (body == null) return;
        body.removeAllViews();
        String needle = search == null ? "" : search.getText().toString().trim()
            .toLowerCase(Locale.ROOT);
        List<ScanDiagnostics.SenderHit> shown = new ArrayList<>();
        for (ScanDiagnostics.SenderHit h : senders)
            if (needle.isEmpty() || h.sender.toLowerCase(Locale.ROOT).contains(needle))
                shown.add(h);
        TextView hint = text(getString(R.string.sender_picker_hint), 12, muted);
        hint.setLineSpacing(2, 1.05f);
        body.addView(hint);
        if (shown.isEmpty()) {
            TextView none = text(getString(senders.isEmpty()
                ? R.string.sender_picker_no_messages : R.string.sender_picker_none), 14, muted);
            none.setPadding(0, dp(18), 0, dp(18));
            body.addView(none);
            return;
        }
        for (ScanDiagnostics.SenderHit h : shown) senderRow(h);
    }

    /** One sender: the address, the bank the app attributes it to when it knows one, how many
     *  messages it holds, and a chevron. No verdict is drawn -- not even how many of the messages
     *  were read, which would invite the "so this one is finished" reading this screen exists to
     *  leave to the user. */
    private void senderRow(ScanDiagnostics.SenderHit h) {
        boolean rtl = getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(12), dp(8), dp(10), dp(8));
        line.setBackground(rounded(card, 13));
        body.addView(line, margin(0, 8, 0, 0));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView who = text(h.sender, 14, fg);
        who.setMaxLines(1);
        who.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        texts.addView(who, new LinearLayout.LayoutParams(-1, -2));
        if (h.bank != null) {
            TextView bank = text(BankRules.displayName(this, h.bank), 12, muted);
            bank.setMaxLines(1);
            bank.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(bank, new LinearLayout.LayoutParams(-1, -2));
        }
        line.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = text(count(h.messages), 13, muted);
        n.setPadding(dp(10), 0, dp(4), 0);
        right.addView(n);
        right.addView(text(rtl ? "\u2039" : "\u203a", 20, fg));
        line.addView(right);

        line.setOnClickListener(v -> openChooser(h));
        line.setContentDescription(getString(R.string.scan_diag_open_sender, h.sender));
    }

    /** Hands the sender and its newest messages to the same chooser the funnel uses. */
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
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(13), dp(14), dp(13));
        box.setBackground(rounded(card, 15));
        TextView note = text(getString(R.string.scan_diag_permission), 12, muted);
        note.setLineSpacing(2, 1.05f);
        box.addView(note, margin(2, 0, 2, 12));
        TextView action = text(getString(R.string.scan_diag_permission_action), 14, fg);
        action.setGravity(Gravity.CENTER);
        action.setTypeface(null, Typeface.BOLD);
        action.setPadding(dp(16), dp(12), dp(16), dp(12));
        action.setBackground(rounded(accent, 14));
        action.setOnClickListener(v -> openSmsSettings());
        box.addView(action);
        body.removeAllViews();
        body.addView(box);
    }

    private void openSmsSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)));
        } catch (Exception e) {
            finish();
        }
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
