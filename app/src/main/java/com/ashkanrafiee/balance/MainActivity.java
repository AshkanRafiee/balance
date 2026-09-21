package com.ashkanrafiee.balance;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Telephony;
import android.provider.Settings;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.OverScroller;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int SMS_REQUEST = 10;
    private static final int REQ_CREATE_BACKUP = 20;
    private static final int REQ_PICK_RESTORE = 21;
    /** How long a copied balance stays in the system clipboard before it is cleared (see
     *  {@code BalanceView.copyBalance}). */
    private static final long CLIP_CLEAR_MS = 60_000L;
    /** Shortest acceptable backup password: the backup is an off-device ciphertext that brute force
     *  can grind at, so a very short code would nullify the 600k-iteration KDF. */
    private static final int MIN_BACKUP_PASSWORD_LENGTH = 8;
    private BalanceView view;
    private String pendingBackupPassword;
    private LockOverlay lockOverlay;
    private Runnable pendingLockAction;
    /** The last non-progress dialog shown, so it can be dismissed when the app leaves the
     *  foreground. A dialog is its own window and would otherwise float - still interactive -
     *  above the lock on return. */
    private android.app.AlertDialog activeDialog;
    /** True while a lock enable/change/disable or fingerprint toggle is running in the background,
     *  so re-tapping the lock button or options can not open a second flow over the first. */
    private boolean lockChangeBusy = false;
    private ContentObserver smsObserver;
    /** Canvas text font; created once and reused, so drawing frames never fabricate a new font. */
    private static final android.graphics.Typeface SANS = android.graphics.Typeface.create("sans",
        android.graphics.Typeface.NORMAL);

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 30)
            getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(resColor(R.color.status_bar));
        getWindow().setNavigationBarColor(resColor(R.color.nav_bar));
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(resColor(R.color.bg)));
        view = new BalanceView();
        FrameLayout host = new FrameLayout(this);
        setContentView(host);
        host.addView(view, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            view.insetsTop = insets.getSystemWindowInsetTop();
            view.insetsBottom = insets.getSystemWindowInsetBottom();
            view.invalidate();
            return insets;
        });
        lockOverlay = new LockOverlay(this);
        lockOverlay.setUnlockListener(() -> {
            Runnable action = pendingLockAction;
            pendingLockAction = null;
            updateSecureFlag();
            if (action != null) action.run();
        });
        lockOverlay.setCancelListener(() -> {
            pendingLockAction = null;
            lockOverlay.hide();
        });
        host.addView(lockOverlay, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        lockOverlay.setVisibility(View.GONE);
        updateSecureFlag();
        requestSms();
    }

    @Override
    protected void onStart() {
        super.onStart();
        LockManager.registerActivityStart(this);
        showLockOverlay();
        updateSecureFlag();
    }

    @Override
    protected void onPause() {
        unregisterSmsObserver();
        if (view != null && BalanceData.isAutoHide(this)) {
            view.hidden = true;
            view.invalidate();
        }
        if (LockManager.isEnabled(this)) {
            // A dialog is a separate window and would otherwise stay on top of the lock, still
            // clickable, when the app is re-opened; drop whatever is up as we leave the foreground.
            dismissDialogs();
            // Arm the lock now so it engages even on ROMs that delay or skip onStop. The arm is
            // cancelled by the next screen's start, so navigating between our own screens (or
            // returning quickly) never locks; a genuine end-of-foreground does.
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
        // The last screen leaving the foreground locks the session; flip the overlay to the
        // entrance then, so the exit frame and the next resume show the lock rather than the data.
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
        // Drop pending canvas callbacks so a finishing activity is not held (or an auto-field refired)
        // after destruction; the copy-clear in particular would otherwise linger a minute on a finished
        // screen while still holding the clipboard target.
        if (view != null) {
            view.handler.removeCallbacks(view.clearClipRunnable);
            view.handler.removeCallbacks(view.refreshTicker);
            if (view.clearClipRunnable != null) view.clearClipRunnable.run();
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        LockManager.cancelPendingLock();
        if (view != null) {
            view.enforceAutoHide();
            view.refresh();
        }
        registerSmsObserver();
        // The delayed lock may have engaged while we were paused on a ROM that skipped onStop;
        // reflect it now that we are back in the foreground.
        if (LockManager.isEnabled(this) && LockManager.isSessionLocked()
                && lockOverlay != null && !lockOverlay.isShowing()) {
            pendingLockAction = null;
            lockOverlay.showLock();
        }
    }

    /**
     * Forces a full redraw when the window regains focus. Without this, a frame
     * drawn around an Activity recreation can leave the bottom strip (below the
     * footer) showing the dark window background until something triggers a
     * redraw (e.g. tapping the eye). Repainting here clears that stale frame.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && view != null) view.invalidate();
    }

    private void requestSms() {
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.READ_SMS}, SMS_REQUEST);
        else view.refresh();
    }

    /** Opens this app's settings page so the user can re-grant SMS permission after a permanent
     *  denial, which the runtime permission dialog can no longer revoke. */
    private void openSmsSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
            startActivity(i);
        } catch (Exception e) {
            requestSms();
        }
    }

    @Override
    public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r, p, g);
        if (r == SMS_REQUEST) {
            boolean granted = g.length > 0
                && g[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted && shouldShowRequestPermissionRationale(Manifest.permission.READ_SMS)) {
                // A casual first denial: explain briefly and offer to re-ask right here, instead of
                // silently doing nothing or burying the fix in the system settings.
                showDialog(new android.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.permission_rationale_title))
                    .setMessage(getString(R.string.permission_rationale_message))
                    .setPositiveButton(getString(R.string.permission_ask_again), (d, w) -> requestSms())
                    .setNegativeButton(android.R.string.cancel, null)
                    .create());
            } else if (!granted) {
                // A permanent denial (check "don't ask again") can no longer be lifted by re-requesting;
                // point the user at the app's settings screen instead of silently ignoring the result.
                showDialog(new android.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.permission_title))
                    .setMessage(getString(R.string.permission_settings_message))
                    .setPositiveButton(getString(R.string.permission_open_settings), (d, w) -> openSmsSettings())
                    .setNegativeButton(android.R.string.cancel, null)
                    .create());
            }
            view.refresh();
            registerSmsObserver();
        }
    }

    private void registerSmsObserver() {
        if (smsObserver != null) return;
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return;
        smsObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) { onChange(selfChange, null); }
            @Override public void onChange(boolean selfChange, Uri uri) {
                if (view != null) view.refreshSilent();
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

    /** The combined Region & Language & Currency dialog behind the footer item: the dropdowns in a
     *  single menu, so the calendar system (see {@link RegionHelper}), the interface language and
     *  the currency unit (see {@link CurrencyHelper}) are all chosen in one place. A changed region
     *  applies on the next history open; a changed currency re-renders the dashboard and the
     *  widget; a changed language recreates the screen, exactly like the former standalone dialog. */
    private void regionLanguageDialog() {
        int pad = dp(14);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad + dp(12), dp(4), pad, 0);

        String[] regionLabels = {getString(R.string.region_iran), getString(R.string.region_international)};
        String[] langTags = LocaleHelper.SUPPORTED;
        String[] langLabels = new String[langTags.length];
        for (int i = 0; i < langTags.length; i++)
            langLabels[i] = langTags[i].isEmpty()
                ? getString(R.string.language_system_default) : LocaleHelper.displayName(langTags[i]);
        String[] currencyLabels = {
            getString(R.string.currency_toman), getString(R.string.currency_usd),
            getString(R.string.currency_eur), getString(R.string.currency_custom)
        };

        Spinner regionSpin = new Spinner(this);
        ArrayAdapter<String> regionAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, regionLabels);
        regionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        regionSpin.setAdapter(regionAdapter);
        regionSpin.setSelection(RegionHelper.region(this) == RegionHelper.REGION_INTERNATIONAL ? 1 : 0);

        Spinner langSpin = new Spinner(this);
        ArrayAdapter<String> langAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, langLabels);
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        langSpin.setAdapter(langAdapter);
        String current = LocaleHelper.currentTag(this);
        for (int i = 0; i < langTags.length; i++)
            if (langTags[i].equals(current)) { langSpin.setSelection(i); break; }

        String storedCurrency = CurrencyHelper.currency(this);
        final EditText customInput = new EditText(this);
        customInput.setSingleLine(true);
        customInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        if (storedCurrency.startsWith(CurrencyHelper.CUSTOM_PREFIX))
            customInput.setText(storedCurrency.substring(CurrencyHelper.CUSTOM_PREFIX.length()));
        Spinner currencySpin = new Spinner(this);
        ArrayAdapter<String> currencyAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, currencyLabels);
        currencyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        currencySpin.setAdapter(currencyAdapter);
        int curIdx = 0;
        if (CurrencyHelper.CURRENCY_USD.equals(storedCurrency)) curIdx = 1;
        else if (CurrencyHelper.CURRENCY_EUR.equals(storedCurrency)) curIdx = 2;
        else if (storedCurrency.startsWith(CurrencyHelper.CUSTOM_PREFIX)) curIdx = 3;
        currencySpin.setSelection(curIdx);
        customInput.setVisibility(curIdx == 3 ? View.VISIBLE : View.GONE);
        currencySpin.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                customInput.setVisibility(pos == 3 ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });

        TextView regionLabel = new TextView(this);
        regionLabel.setText(getString(R.string.settings_region_label));
        regionLabel.setTextSize(14);
        TextView langLabel = new TextView(this);
        langLabel.setText(getString(R.string.settings_language_label));
        langLabel.setTextSize(14);
        TextView currencyLabel = new TextView(this);
        currencyLabel.setText(getString(R.string.settings_currency_label));
        currencyLabel.setTextSize(14);

        box.addView(regionLabel);
        box.addView(regionSpin);
        LinearLayout.LayoutParams langLp = new LinearLayout.LayoutParams(-1, -2);
        langLp.topMargin = dp(18);
        box.addView(langLabel, langLp);
        box.addView(langSpin);
        LinearLayout.LayoutParams curLp = new LinearLayout.LayoutParams(-1, -2);
        curLp.topMargin = dp(18);
        box.addView(currencyLabel, curLp);
        box.addView(currencySpin);
        LinearLayout.LayoutParams curInLp = new LinearLayout.LayoutParams(-1, -2);
        curInLp.topMargin = dp(8);
        box.addView(customInput, curInLp);

        showDialog(new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.footer_region_language))
            .setView(box)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                int chosen = regionSpin.getSelectedItemPosition() == 1
                    ? RegionHelper.REGION_INTERNATIONAL : RegionHelper.REGION_IRAN;
                String tag = langTags[Math.min(langSpin.getSelectedItemPosition(), langTags.length - 1)];
                boolean langChanged = !tag.equals(LocaleHelper.currentTag(this));
                String chosenCurrency = null;
                int curPos = currencySpin.getSelectedItemPosition();
                if (curPos == 3) {
                    String typed = customInput.getText().toString().trim();
                    if (!typed.isEmpty()) chosenCurrency = CurrencyHelper.CUSTOM_PREFIX + typed;
                } else {
                    chosenCurrency = new String[]{CurrencyHelper.CURRENCY_TOMAN,
                        CurrencyHelper.CURRENCY_USD, CurrencyHelper.CURRENCY_EUR}[curPos];
                }
                boolean currencyChanged = chosenCurrency != null
                    && !chosenCurrency.equals(CurrencyHelper.currency(this));
                RegionHelper.setRegion(this, chosen);
                if (chosenCurrency != null) CurrencyHelper.setCurrency(this, chosenCurrency);
                if (langChanged) {
                    LocaleHelper.setLanguage(this, tag);
                    recreate();
                } else if (currencyChanged) {
                    view.invalidate();
                    BalanceWidgetProvider.push(MainActivity.this);
                }
            })
            .setNegativeButton(getString(R.string.lock_cancel), null)
            .create());
    }

    void hardRefreshDialog() {
        showDialog(new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_hard_refresh_title))
            .setMessage(getString(R.string.dialog_hard_refresh_message))
            .setNegativeButton(getString(R.string.dialog_hard_refresh_cancel), null)
            .setPositiveButton(getString(R.string.dialog_hard_refresh_confirm), (d, w) -> view.refresh(true))
            .create());
    }

    // ====================================================================
    // App lock
    // ====================================================================

    /** While the lock is enabled the window is flagged SECURE, so recents thumbnails, live
     *  snapshots and screenshots never show a frame of the data — even when the session is
     *  temporarily unlocked. The flag is dropped the moment the lock is disabled. */
    private void updateSecureFlag() {
        if (LockManager.isEnabled(this)) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    }

    /** Shows {@code dlg} and remembers it, so {@link #dismissDialogs()} can drop whatever is on
     *  screen the moment the app leaves the foreground. Any dialog it replaces is dismissed, so a
     *  chained dialog can never leave an orphan floating above the lock. */
    private android.app.AlertDialog showDialog(android.app.AlertDialog dlg) {
        if (activeDialog != null && activeDialog.isShowing()) {
            activeDialog.dismiss();
        }
        activeDialog = dlg;
        dlg.setOnDismissListener(d -> {
            if (activeDialog == d) activeDialog = null;
        });
        dlg.show();
        return dlg;
    }

    /** Dismisses the tracked dialog and any progress dialog. Safe to call when nothing is up. */
    private void dismissDialogs() {
        if (activeDialog != null) {
            activeDialog.dismiss();
            activeDialog = null;
        }
        dismissProgress();
    }

    /** Tapping the lock button engages the lock right away; a first tap on a fresh install opens the
     *  enable dialog instead (long-press always ends up in the lock settings). */
    void onLockTap() {
        if (lockChangeBusy || lockOverlay.isShowing()) return;
        if (!LockManager.isEnabled(this)) {
            enableLockFlow();
            return;
        }
        LockManager.lockSession();
        updateSecureFlag();
        showLockOverlay();
    }

    /** Long-pressing the eye toggles auto-mask: when on, every app start and every return from the
     *  background re-hides the balances so prying eyes never catch them off-screen. */
    void setAutoHideToggle() {
        if (view == null) return;
        boolean on = !BalanceData.isAutoHide(this);
        BalanceData.setAutoHide(this, on);
        view.autoHide = on;
        view.enforceAutoHide();
        view.invalidate();
        toast(on ? R.string.toast_auto_hide_on : R.string.toast_auto_hide_off);
    }

    /** Shows the lock entrance whenever the session is locked; hides it otherwise. */
    void showLockOverlay() {
        if (LockManager.isEnabled(this) && LockManager.isSessionLocked()) {
            pendingLockAction = null;
            if (lockOverlay != null) lockOverlay.showLock();
        } else if (lockOverlay != null) {
            lockOverlay.hide();
        }
    }

    private void enableLockFlow() {
        if (lockChangeBusy || lockOverlay.isShowing()) return;
        showDialog(new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.lock_enable_title))
            .setMessage(getString(R.string.lock_enable_message))
            .setNegativeButton(getString(R.string.lock_cancel), null)
            .setPositiveButton(getString(R.string.lock_continue), (d, w) -> setupCodeDialog(false))
            .create());
    }

    /** Long-pressing the lock button asks for the current code first (verify mode), then opens the
     *  settings; on a fresh install it simply jumps to the enable flow. */
    void lockSettingsFlow() {
        if (lockChangeBusy || lockOverlay.isShowing()) return;
        if (!LockManager.isEnabled(this)) {
            enableLockFlow();
            return;
        }
        pendingLockAction = () -> lockSettingsDialog();
        lockOverlay.showVerify();
    }

    private void lockSettingsDialog() {
        String[] options = {
            getString(R.string.lock_settings_change),
            getString(LockManager.isFingerprintEnabled(this)
                ? R.string.lock_settings_fp_on : R.string.lock_settings_fp_off),
            getString(R.string.lock_settings_disable)
        };
        showDialog(new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.lock_settings_title))
            .setItems(options, (d, which) -> {
                if (which == 0) setupCodeDialog(true);
                else if (which == 1) toggleFingerprint();
                else confirmDisableLock();
            })
            .setNegativeButton(getString(R.string.lock_cancel), null)
            .create());
    }

    private void toggleFingerprint() {
        if (lockChangeBusy) return;
        if (LockManager.isFingerprintEnabled(this)) {
            LockManager.setFingerprintEnabled(this, false);
            toast(R.string.lock_fp_off_toast);
            BalanceWidgetProvider.push(this);
            return;
        }
        if (!LockManager.fingerprintCapable(this)) {
            Toast.makeText(this, getString(R.string.lock_fp_no_enroll), Toast.LENGTH_LONG).show();
            return;
        }
        lockChangeBusy = true;
        new Thread(() -> {
            boolean ok = LockManager.setFingerprintEnabled(this, true);
            final boolean result = ok;
            runOnUiThread(() -> {
                lockChangeBusy = false;
                toast(result ? R.string.lock_fp_on_toast : R.string.lock_fingerprint_unavailable);
            });
        }).start();
    }

    private void confirmDisableLock() {
        if (lockChangeBusy) return;
        showDialog(new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.lock_disable_title))
            .setMessage(getString(R.string.lock_disable_message))
            .setNegativeButton(getString(R.string.lock_cancel), null)
            .setPositiveButton(getString(R.string.lock_disable_action), (d, w) -> {
                lockChangeBusy = true;
                showProgress(getString(R.string.lock_progress_saving));
                new Thread(() -> {
                    LockManager.disable(this);
                    runOnUiThread(() -> {
                        dismissProgress();
                        lockChangeBusy = false;
                        updateSecureFlag();
                        showLockOverlay();
                        toast(R.string.lock_toast_disabled);
                        BalanceWidgetProvider.push(this);
                    });
                }).start();
            })
            .create());
    }

    /** The shared PIN/password entry form used by the enable (fresh) and the change-code flows. The
     *  hash derivation is deliberately slow, so it runs off the UI thread before the dialog closes. */
    private void setupCodeDialog(final boolean changing) {
        if (lockChangeBusy || lockOverlay.isShowing()) return;
        final boolean[] pin = {LockManager.isPinMode(this)};
        final EditText code = new EditText(this);
        final EditText confirm = new EditText(this);
        android.widget.CheckBox fpCheck = null;

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(24), dp(8), dp(24), 0);

        final TextView pinOpt = segOption(getString(R.string.lock_pin_label));
        final TextView passOpt = segOption(getString(R.string.lock_password_label));
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.addView(pinOpt, new LinearLayout.LayoutParams(0, -2, 1));
        seg.addView(passOpt, new LinearLayout.LayoutParams(0, -2, 1));
        wrap.addView(seg);

        final Runnable styleSeg = () -> {
            styleSeg(pinOpt, pin[0]);
            styleSeg(passOpt, !pin[0]);
            boolean isPin = pin[0];
            // Both fields are masked identically in both modes, so the code can never be read off
            // the screen while it is typed.
            code.setInputType(isPin
                ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            code.setTransformationMethod(new android.text.method.PasswordTransformationMethod());
            confirm.setInputType(isPin
                ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            confirm.setTransformationMethod(new android.text.method.PasswordTransformationMethod());
            // Both fields follow the locale direction (right-aligned hints in Persian, left in
            // English).  TEXT_DIRECTION_LOCALE alone doesn't always keep Gravity.START aligned
            // correctly after setInputType(), so we also pin the layout direction and use explicit
            // gravity that resolves immediately.
            boolean rtl = getResources().getConfiguration().getLayoutDirection()
                == View.LAYOUT_DIRECTION_RTL;
            int hGrav = rtl ? Gravity.RIGHT : Gravity.LEFT;
            code.setTextDirection(View.TEXT_DIRECTION_LOCALE);
            code.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            code.setGravity(hGrav | Gravity.CENTER_VERTICAL);
            confirm.setTextDirection(View.TEXT_DIRECTION_LOCALE);
            confirm.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            confirm.setGravity(hGrav | Gravity.CENTER_VERTICAL);
            code.setHint(getString(isPin ? R.string.lock_pin_hint : R.string.lock_password_hint));
        };
        pinOpt.setOnClickListener(v -> { pin[0] = true; styleSeg.run(); });
        passOpt.setOnClickListener(v -> { pin[0] = false; styleSeg.run(); });

        applyLockInput(code);
        applyLockInput(confirm);
        confirm.setHint(getString(R.string.lock_confirm_hint));
        LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(-1, -2);
        codeLp.topMargin = dp(12);
        wrap.addView(code, codeLp);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(-1, -2);
        inputLp.topMargin = dp(10);
        confirm.setLayoutParams(inputLp);
        wrap.addView(confirm);

        if (!changing && LockManager.fingerprintCapable(this)) {
            fpCheck = new android.widget.CheckBox(this);
            fpCheck.setChecked(true);
            fpCheck.setText(getString(R.string.lock_fingerprint_option));
            fpCheck.setTextSize(14);
            fpCheck.setTextColor(resColor(R.color.fg));
            LinearLayout.LayoutParams fpLp = new LinearLayout.LayoutParams(-1, -2);
            fpLp.topMargin = dp(14);
            wrap.addView(fpCheck, fpLp);
        } else if (!changing) {
            TextView note = new TextView(this);
            note.setText(getString(R.string.lock_fingerprint_unavailable));
            note.setTextSize(12);
            note.setTextColor(resColor(R.color.negative));
            LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
            noteLp.topMargin = dp(14);
            wrap.addView(note, noteLp);
        }

        styleSeg.run();
        final android.widget.CheckBox fp = fpCheck;
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
            .setTitle(getString(changing ? R.string.lock_change_title : R.string.lock_setup_title))
            .setView(wrap)
            .setNegativeButton(getString(R.string.lock_cancel), null)
            .setPositiveButton(getString(changing
                ? R.string.lock_save_action : R.string.lock_enable_action), null)
            .create();
        dlg.setOnShowListener(d -> dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v -> {
                final boolean isPin = pin[0];
                String a = code.getText().toString().trim();
                String b = confirm.getText().toString().trim();
                if (a.isEmpty() || b.isEmpty()) {
                    toast(R.string.lock_validate_empty);
                    return;
                }
                if (!LockManager.validCode(a, isPin)) {
                    toast(isPin ? R.string.lock_validate_pin_length
                        : R.string.lock_validate_password_length);
                    return;
                }
                if (!a.equals(b)) {
                    toast(R.string.lock_validate_mismatch);
                    return;
                }
                final boolean wantFp = fp != null && fp.isChecked();
                final String value = a;
                dlg.dismiss();
                lockChangeBusy = true;
                showProgress(getString(R.string.lock_progress_saving));
                new Thread(() -> {
                    try {
                        if (changing) LockManager.changeCode(this, value, isPin);
                        else LockManager.enable(this, value, isPin, wantFp);
                        runOnUiThread(() -> {
                            dismissProgress();
                            lockChangeBusy = false;
                            updateSecureFlag();
                            toast(changing ? R.string.lock_toast_code_changed : R.string.lock_toast_enabled);
                            BalanceWidgetProvider.push(this);
                        });
                    } catch (Exception e) {
                        android.util.Log.w("BalanceLock", "setup failed", e);
                        runOnUiThread(() -> {
                            dismissProgress();
                            lockChangeBusy = false;
                            toast(R.string.lock_error_generic);
                        });
                    }
                }).start();
            }));
        showDialog(dlg);
    }

    private TextView segOption(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(10), dp(16), dp(10));
        return t;
    }

    private void styleSeg(TextView t, boolean selected) {
        t.setBackground(rounded(selected ? resColor(R.color.accent) : resColor(R.color.panel), 12));
        t.setTextColor(selected ? Color.WHITE : resColor(R.color.muted));
    }

    private void applyLockInput(EditText e) {
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        e.setTextSize(16);
        e.setTextColor(resColor(R.color.fg));
        e.setHintTextColor(resColor(R.color.muted));
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private void toast(int res) {
        Toast.makeText(this, getString(res), Toast.LENGTH_SHORT).show();
    }

    // ====================================================================
    // Encrypted backup / restore
    // ====================================================================

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + .5f);
    }

    /** [top, bottom] system-bar insets in px across every supported API level. */
    private int[] systemBarInsets(android.view.WindowInsets ins) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            return new int[]{
                ins.getInsets(android.view.WindowInsets.Type.statusBars()).top,
                ins.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom,
            };
        }
        return new int[]{ins.getSystemWindowInsetTop(), ins.getSystemWindowInsetBottom()};
    }

    private void dataDialog() {
        String[] options = {
            getString(R.string.backup_action_create),
            getString(R.string.backup_action_restore),
            getString(R.string.data_action_reset)
        };
        showDialog(new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.data_title))
            .setItems(options, (d, which) -> {
                if (which == 0) askPassword(true, null);
                else if (which == 1) pickRestoreSource();
                else hardRefreshDialog();
            })
            .create());
    }

    private void pickBackupTarget() {
        LockManager.holdUnlock();
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType("application/octet-stream");
        create.putExtra(Intent.EXTRA_TITLE, backupFileName());
        startActivityForResult(create, REQ_CREATE_BACKUP);
    }

    private void pickRestoreSource() {
        LockManager.holdUnlock();
        Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        open.addCategory(Intent.CATEGORY_OPENABLE);
        open.setType("*/*");
        startActivityForResult(open, REQ_PICK_RESTORE);
    }

    private String backupFileName() {
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .format(new java.util.Date());
        return "balance-backup-" + stamp + ".balance";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CREATE_BACKUP) {
            String password = pendingBackupPassword;
            pendingBackupPassword = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || password == null) return;
            createBackup(data.getData(), password);
        } else if (requestCode == REQ_PICK_RESTORE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null)
                askPassword(false, data.getData());
        }
    }

    private void askPassword(boolean forBackup, Uri restoreUri) {
        askPassword(forBackup, restoreUri, null);
    }

    private void askPassword(boolean forBackup, Uri restoreUri, String warning) {
        EditText password = new EditText(this);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        password.setHint(getString(R.string.backup_password_hint));
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(8), dp(24), 0);
        if (warning != null) {
            TextView error = new TextView(this);
            error.setText(warning);
            error.setTextColor(resColor(R.color.negative));
            error.setTextSize(13);
            error.setPadding(0, 0, 0, dp(10));
            layout.addView(error);
        }
        TextView info = new TextView(this);
        info.setTextSize(13);
        info.setText(forBackup ? getString(R.string.backup_password_info) : getString(R.string.backup_restore_info));
        info.setPadding(0, 0, 0, dp(10));
        layout.addView(info);
        layout.addView(password);
        EditText[] confirm = {null};
        if (forBackup) {
            confirm[0] = new EditText(this);
            confirm[0].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            confirm[0].setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            confirm[0].setHint(getString(R.string.backup_password_confirm_hint));
            layout.addView(confirm[0]);
        }
        EditText pw = password;
        EditText cf = confirm[0];
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
            .setTitle(forBackup
                ? getString(R.string.backup_create_title)
                : getString(R.string.backup_restore_title))
            .setView(layout)
            .setNegativeButton(getString(R.string.dialog_hard_refresh_cancel), null)
            .setPositiveButton(forBackup
                ? getString(R.string.backup_action_create)
                : getString(R.string.backup_restore_confirm), null)
            .create();
        dlg.setOnShowListener(d -> dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v -> {
                String value = pw.getText().toString();
                if (value.isEmpty()) {
                    Toast.makeText(MainActivity.this, getString(R.string.backup_validate_empty), Toast.LENGTH_SHORT).show();
                    return;
                }
                if (forBackup && value.length() < MIN_BACKUP_PASSWORD_LENGTH) {
                    Toast.makeText(MainActivity.this, getString(R.string.backup_validate_short), Toast.LENGTH_SHORT).show();
                    return;
                }
                if (forBackup && !value.equals(cf.getText().toString())) {
                    Toast.makeText(MainActivity.this, getString(R.string.backup_validate_mismatch), Toast.LENGTH_SHORT).show();
                    return;
                }
                dlg.dismiss();
                if (forBackup) {
                    pendingBackupPassword = value;
                    pickBackupTarget();
                } else {
                    restoreBackup(restoreUri, value);
                }
            }));
        showDialog(dlg);
    }

    private void createBackup(Uri uri, String password) {
        showProgress(getString(R.string.backup_progress_creating));
        new Thread(() -> {
            final int[] error = {0};
            try {
                BackupManager.create(getApplicationContext(), uri, password);
            } catch (BackupManager.BackupException e) {
                error[0] = e.resId;
            } catch (Exception e) {
                android.util.Log.w("BalanceBackup", "create failed", e);
                error[0] = R.string.backup_error_generic;
            }
            int err = error[0];
            runOnUiThread(() -> {
                dismissProgress();
                if (err != 0) {
                    Toast.makeText(MainActivity.this,
                        getString(R.string.backup_create_failed) + "\n" + getString(err),
                        Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(MainActivity.this, getString(R.string.backup_created), Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private void restoreBackup(Uri uri, String password) {
        showProgress(getString(R.string.backup_progress_restoring));
        new Thread(() -> {
            final int[] error = {0};
            final BackupManager.RestoreResult[] result = {null};
            try {
                result[0] = BackupManager.restore(getApplicationContext(), uri, password);
            } catch (BackupManager.BackupException e) {
                error[0] = e.resId;
            } catch (Exception e) {
                android.util.Log.w("BalanceBackup", "restore failed", e);
                error[0] = R.string.backup_error_generic;
            }
            int err = error[0];
            BackupManager.RestoreResult res = result[0];
            runOnUiThread(() -> {
                dismissProgress();
                if (err != 0) {
                    if (err == R.string.backup_error_password) {
                        // Wrong password (or a corrupted backup): let the user retry the
                        // password for the same file instead of forcing a new file pick.
                        askPassword(false, uri, getString(R.string.backup_error_password));
                    } else {
                        Toast.makeText(MainActivity.this,
                            getString(R.string.backup_restore_failed) + "\n" + getString(err),
                            Toast.LENGTH_LONG).show();
                    }
                } else {
                    view.loadSaved();
                    String summary = res.changed()
                        ? getString(R.string.backup_restore_summary, res.added, res.updated)
                        : getString(R.string.backup_restore_summary_none);
                    Toast.makeText(MainActivity.this,
                        getString(R.string.backup_restored) + "\n" + summary,
                        Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private android.app.AlertDialog progressDialog;

    private void showProgress(String message) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        int margin = dp(24);
        wrap.setPadding(margin, dp(18), margin, dp(14));
        TextView tv = new TextView(this);
        tv.setText(message);
        tv.setTextSize(15);
        tv.setTextColor(resColor(R.color.fg));
        wrap.addView(tv);
        // An indeterminate frame animation that stays visibly moving on every
        // supported Android version and theme, which matters while the decrypt
        // work is running in the background.
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setIndeterminateDrawable(getResources().getDrawable(
            android.R.drawable.progress_indeterminate_horizontal, getTheme()));
        bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(resColor(R.color.accent)));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
        barLp.topMargin = dp(14);
        bar.setLayoutParams(barLp);
        wrap.addView(bar);
        progressDialog = new android.app.AlertDialog.Builder(this)
            .setView(wrap).setCancelable(false).create();
        progressDialog.show();
        // The old password dialog was just dismissed with its soft keyboard still
        // animating away. Pin this dialog to the exact middle of the screen:
        // the window manager centers dialogs inside the area that excludes the
        // status and navigation bars, so without a correction the card sits
        // (statusBarHeight - navBarHeight) / 2 px off the true middle of the
        // display. The a.y offset below (positive moves down from the center)
        // restores an equal margin above and below the card on the full screen.
        android.view.Window win = progressDialog.getWindow();
        if (win != null) {
            win.setLayout(dp(300), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            win.setGravity(Gravity.CENTER);
            win.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            android.view.WindowInsets ins = getWindow().getDecorView().getRootWindowInsets();
            if (ins != null) {
                int[] sb = systemBarInsets(ins);
                android.view.WindowManager.LayoutParams a = win.getAttributes();
                a.y = (sb[1] - sb[0]) / 2;
                win.setAttributes(a);
            }
        }
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
    }

    private int resColor(int res) {
        return getResources().getColor(res, getTheme());
    }

    private final class BalanceView extends View {
        static final int ICON_NONE = 0, ICON_LOCK = 1, ICON_EYE = 2;
        final Paint p = new Paint(3);
        final LinkedHashMap<String, Bank> banks = new LinkedHashMap<>();
        final java.util.Set<String> excluded = new java.util.HashSet<>();
        final float d = getResources().getDisplayMetrics().density;
        /** System font scale, applied to every text the canvas draws (the canvas otherwise renders in
         *  density-scaled px and would silently ignore the user's chosen font size). Layout positions
         *  stay density-scaled; only the glyph sizes scale, matching how system-wide font scaling
         *  works in normal view hierarchies. */
        final float fs = getResources().getConfiguration().fontScale;
        Drawable lockIcon;
        boolean hidden, refreshing;
        boolean autoHide;
        int insetsTop, insetsBottom;
        int sortMode;
        float scrollY = 0, lastY, downY;
        float bankListHeight;
        /** Kinetic scrolling, so the bank list coasts the way the system ScrollView in the history
         *  screen does instead of stopping dead when the finger lifts. */
        final OverScroller scroller;
        VelocityTracker velocityTracker;
        boolean dragging;
        /** Pull-to-refresh indicator: {@code pullShift/pullFrac} track the finger while it drags down
         *  at the top; once released past {@link #PULL_TRIGGER} the arrow spins (ticker) until the
         *  SMS scan settles, then glides back up. All values are dp in the canvas' scaled space. */
        static final float PULL_TRIGGER = 55f, PULL_CAP = 44f;
        float pullShift, pullFrac, pullFade, spinAngle;
        boolean indicatorVisible, spinnerRunning, retracting;
        long spinDeadline;
        boolean lockArmed;
        boolean lockProbeFired;
        boolean eyeArmed;
        boolean eyeProbeFired;
        boolean totalArmed;
        boolean totalProbeFired;
        boolean bankArmed;
        boolean bankProbeFired;
        BankRow bankProbeRow;
        int downIcon = ICON_NONE;
        /** Clears a copied balance from the system clipboard shortly after it was copied (see
         *  {@link #copyBalance}); while set, the clipboard holds sensitive data we placed there. */
        Runnable clearClipRunnable;
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable lockLongProbe = () -> {
            lockProbeFired = true;
            if (MainActivity.this.isFinishing() || MainActivity.this.isDestroyed()) return;
            MainActivity.this.lockSettingsFlow();
        };
        final Runnable eyeLongProbe = () -> {
            eyeProbeFired = true;
            if (MainActivity.this.isFinishing() || MainActivity.this.isDestroyed()) return;
            MainActivity.this.setAutoHideToggle();
        };
        final Runnable totalLongProbe = () -> {
            totalProbeFired = true;
            if (MainActivity.this.isFinishing() || MainActivity.this.isDestroyed()) return;
            copyBalance(getString(R.string.total_label), this.total);
        };
        final Runnable bankLongProbe = () -> {
            bankProbeFired = true;
            if (MainActivity.this.isFinishing() || MainActivity.this.isDestroyed()) return;
            BankRow row = bankProbeRow;
            if (row == null) return;
            String label = BankRules.displayName(MainActivity.this, row.bankName);
            if (row.bank != null && row.bank.account != null)
                label += " " + faDigits(row.bank.account);
            copyBalance(label, row.amount);
        };
        final Runnable refreshTicker = new Runnable() {
            @Override public void run() {
                if (MainActivity.this.isFinishing() || MainActivity.this.isDestroyed()) return;
                if (spinnerRunning) {
                    spinAngle += 6f;                       // about one turn per second
                    pullShift = Math.min(PULL_CAP, pullShift + 5f);
                    // Stop the moment the scan settles; only a short beat (a fraction of a turn) is kept
                    // so an almost-instant refresh still reads as a completed spin, not a flicker.
                    if (System.currentTimeMillis() >= spinDeadline && !refreshing) {
                        spinnerRunning = false;
                        retracting = true;
                    }
                    invalidate();
                    handler.postDelayed(this, 16);
                } else if (retracting || pullFade > 0.02f) {
                    pullShift = Math.max(0, pullShift - 10f);
                    pullFrac = Math.max(0, pullFrac - 0.12f);
                    pullFade = Math.max(0, pullFade - 0.12f);
                    if (pullFade <= 0.02f) {
                        pullFade = 0;
                        indicatorVisible = false;
                        retracting = false;
                    }
                    invalidate();
                    if (indicatorVisible) handler.postDelayed(this, 16);
                }
            }
        };
        String status = getString(R.string.status_reading_sms);
        long total;
        float footerAboutStart, footerAboutEnd, footerLangStart, footerLangEnd,
            footerBackupStart, footerBackupEnd, footerReportStart, footerReportEnd, footerY;
        final int fg = resColor(R.color.fg);
        final int muted = resColor(R.color.muted);
        final int accent = resColor(R.color.accent);
        final int active = resColor(R.color.active);
        final int purple = resColor(R.color.purple);
        final int panel = resColor(R.color.panel);
        final int bg = resColor(R.color.bg);
        final int[] bankColors = {
            Color.rgb(14, 165, 233), Color.rgb(139, 92, 246),
            Color.rgb(16, 185, 129), Color.rgb(245, 158, 11),
            Color.rgb(244, 63, 94), Color.rgb(20, 184, 166)
        };
        BalanceView() {
            super(MainActivity.this);
            hidden = BalanceData.isHidden(MainActivity.this);
            autoHide = BalanceData.isAutoHide(MainActivity.this);
            if (autoHide) hidden = true;
            sortMode = BalanceData.getSort(MainActivity.this);
            p.setTypeface(SANS);
            scroller = new OverScroller(MainActivity.this);
        }

        /** The list's visible height after the system bars (dp), mirroring the touch calculation. */
        float listViewH() {
            int top = insetsTop, bottom = insetsBottom;
            if (top == 0 && bottom == 0 && android.os.Build.VERSION.SDK_INT >= 23
                    && getRootWindowInsets() != null) {
                top = getRootWindowInsets().getSystemWindowInsetTop();
                bottom = getRootWindowInsets().getSystemWindowInsetBottom();
            }
            return (getHeight() - top - bottom) / d;
        }

        /** The farthest the list can be scrolled (dp), i.e. its content height minus the viewport
         *  minus the fixed non-list chrome; never below zero. */
        float maxScroll() {
            return Math.max(0, bankListHeight - (listViewH() - 440));
        }

        /** Advances the inertia of a finished drag, redrawing each frame until it settles at a
         *  hard-bounded resting position exactly like a native scroller. */
        @Override
        public void computeScroll() {
            if (scroller.computeScrollOffset()) {
                scrollY = Math.max(0, Math.min(maxScroll(), scroller.getCurrY()));
                invalidate();
            }
        }

        /** Ends a drag: a released finger with enough velocity coasts the list the way the history
         *  screen does, while a slow deliberate pull at the very top keeps the pull-to-refresh. A
         *  downward release spent at the top cannot scroll (nothing above the viewport), so it falls
         *  through to the refresh gesture exactly as before. */
        private void handleDragRelease(float downY, float y) {
            float vy = 0;
            if (velocityTracker != null) {
                velocityTracker.computeCurrentVelocity(1000, 20000f);
                vy = velocityTracker.getYVelocity() / d;
                velocityTracker.recycle();
                velocityTracker = null;
            }
            float minFling = ViewConfiguration.get(MainActivity.this).getScaledMinimumFlingVelocity() / d;
            boolean roomy = (vy < 0 && scrollY < maxScroll()) || (vy > 0 && scrollY > 0);
            if (roomy && Math.abs(vy) >= minFling) {
                rows();
                scroller.fling(0, Math.round(scrollY), 0, -Math.round(vy),
                    0, 0, 0, Math.round(maxScroll()), 0, 0);
                invalidate();
            } else if (downY < 360 && y - downY > PULL_TRIGGER && scrollY == 0) {
                beginSpin();
                refresh();
            } else if (indicatorVisible) {
                retractIndicator();
            }
        }

        /** Starts (or resumes) the finger-following phase of the indicator for a downward pull. */
        void startPull(float delta, float trigger) {
            indicatorVisible = true;
            spinnerRunning = false;
            retracting = false;
            pullShift = Math.max(0, Math.min(PULL_CAP, delta));
            pullFrac = Math.max(0, Math.min(1, delta / trigger));
            pullFade = Math.min(1, pullFrac * 1.7f);
            spinAngle = pullFrac * 180f;
            invalidate();
        }

        /** Release past the trigger: the arrow goes fully down and spins while the scan runs. */
        void beginSpin() {
            spinnerRunning = true;
            retracting = false;
            indicatorVisible = true;
            pullFade = 1;
            pullShift = PULL_CAP;
            pullFrac = 1;
            spinDeadline = System.currentTimeMillis() + 400;
            handler.removeCallbacks(refreshTicker);
            handler.postDelayed(refreshTicker, 16);
            invalidate();
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
            invalidate();
        }

        boolean isRtl() {
            return getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        }

        void refresh() { refresh(false, false); }
        void refreshSilent() { refresh(false, true); }

        /** When auto-mask is on, the balances must start (and stay) masked; call this from the
         *  lifecycle so every app open or return from the background re-hides them. */
        void enforceAutoHide() {
            hidden = BalanceData.isHidden(MainActivity.this);
            autoHide = BalanceData.isAutoHide(MainActivity.this);
            if (autoHide) hidden = true;
            invalidate();
        }

        /** Recomputes the total from included entries only. Exclusion is per account, matched on the
         *  entry's own {@code bank|account} storage key. */
        void recalcTotal() {
            total = 0;
            for (java.util.Map.Entry<String, Bank> e : banks.entrySet())
                if (!excluded.contains(e.getKey())) total += e.getValue().amount;
        }

        /** A short live summary of the dashboard for screen readers: the total (or its mask state)
         *  followed by the status line. Rebuilt every frame, but only announced on change. */
        String announce() {
            String totalText = hidden ? getString(R.string.accessibility_total_masked)
                : CurrencyHelper.amount(MainActivity.this, total) + " "
                    + CurrencyHelper.label(MainActivity.this);
            return getString(R.string.accessibility_total_balance, totalText) + " " + status + ".";
        }

        /** Reloads the saved balances (e.g. after a restore) without re-scanning SMS. */
        void loadSaved() {
            LinkedHashMap<String, Bank> saved = BalanceData.read(MainActivity.this);
            banks.clear();
            banks.putAll(saved);
            excluded.clear();
            excluded.addAll(BalanceData.getExcluded(MainActivity.this));
            recalcTotal();
            status = getString(R.string.status_loaded_from_saved);
            refreshing = false;
            invalidate();
            BalanceWidgetProvider.push(MainActivity.this);
        }

        void refresh(boolean hard) { refresh(hard, false); }

        /** Refreshes from the SMS inbox. With {@code hard} set, saved balances are discarded first and
         *  only the messages currently in the inbox are re-read, so banks whose SMS are no longer
         *  available disappear. Callers must already have shown the consequence dialog. When
         *  {@code silent} is true the "Refreshing…" status is suppressed — used by the background
         *  ContentObserver so incoming-SMS updates don't flash status on screen. */
        void refresh(boolean hard, boolean silent) {
            if (refreshing) return;
            if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                status = getString(R.string.status_permission_needed);
                invalidate();
                return;
            }
            refreshing = true;
            if (!silent) {
                status = getString(R.string.status_refreshing);
                invalidate();
            }
            String statusNoSms = getString(R.string.status_no_sms_found);
            String updatedNow = getString(R.string.status_updated_now);
            String statusLoaded = getString(R.string.status_loaded_from_saved);
            new Thread(() -> {
                final Context app = MainActivity.this.getApplicationContext();
                try {
                    if (hard) BalanceData.reset(app);
                    LinkedHashMap<String, Bank> saved = BalanceData.read(app);
                    int count = BalanceData.scanSms(app, saved);
                    post(() -> {
                        banks.clear();
                        banks.putAll(saved);
                        excluded.clear();
                        excluded.addAll(BalanceData.getExcluded(app));
                        recalcTotal();
                        status = buildStatus(count, saved.isEmpty(), statusNoSms, updatedNow);
                        refreshing = false;
                        invalidate();
                        BalanceWidgetProvider.push(app);
                    });
                } catch (Exception e) {
                    post(() -> {
                        LinkedHashMap<String, Bank> saved2 = BalanceData.read(app);
                        banks.clear();
                        banks.putAll(saved2);
                        excluded.clear();
                        excluded.addAll(BalanceData.getExcluded(app));
                        recalcTotal();
                        status = banks.isEmpty() ? getString(R.string.status_sms_unreadable) : statusLoaded;
                        refreshing = false;
                        invalidate();
                        BalanceWidgetProvider.push(app);
                    });
                }
                BalanceData.scanHistory(app);
            }).start();
        }

        private String buildStatus(int count, boolean empty, String noSms, String loaded) {
            if (count == 0)
                return empty ? noSms : loaded;
            return getResources().getQuantityString(R.plurals.status_updated_banks, count, count);
        }

        void value(Canvas c, long n, float x, float baseline, float width,
                   float size, Paint.Align align, boolean strikethrough) {
            if (hidden) {
                text(c, "\u2022\u2022\u2022\u2022\u2022\u2022", x, baseline, size, fg, align);
                return;
            }
            String number = CurrencyHelper.amount(MainActivity.this, n);
            float current = size;
            while (current > 10 && measure(number, current) > width) current -= 1;
            text(c, number, x, baseline, current, accent, align);
            text(c, CurrencyHelper.label(MainActivity.this), x, baseline + 19, 11, muted, align);
            if (strikethrough) {
                float numW = measure(number, current);
                float lineX1 = align == Paint.Align.RIGHT ? x - numW : x;
                float lineX2 = align == Paint.Align.RIGHT ? x : x + numW;
                p.setColor(muted);
                p.setStrokeWidth(2f);
                c.drawLine(lineX1, baseline - 5, lineX2, baseline - 5, p);
            }
        }

        void value(Canvas c, long n, float x, float baseline, float width,
                   float size, Paint.Align align) {
            value(c, n, x, baseline, width, size, align, false);
        }

        void totalValue(Canvas c, long n, float x, float baseline, float width, boolean rtl) {
            Paint.Align anchor = rtl ? Paint.Align.RIGHT : Paint.Align.LEFT;
            if (hidden) {
                text(c, "\u2022\u2022\u2022\u2022\u2022\u2022", x, baseline, 34, fg, anchor);
                return;
            }
            String number = CurrencyHelper.amount(MainActivity.this, n);
            String unit = CurrencyHelper.label(MainActivity.this);
            float unitSize = 13, unitGap = 10, unitWidth = measure(unit, unitSize);
            float current = 34;
            while (current > 16 && measure(number, current) + unitGap + unitWidth > width) current -= 1;
            float numberWidth = measure(number, current);
            text(c, number, x, baseline, current, fg, anchor);
            float unitX = rtl ? x - numberWidth - unitGap : x + numberWidth + unitGap;
            text(c, unit, unitX, baseline - 2, unitSize, muted, anchor);
        }

        float measure(String value, float size) {
            p.setTextSize(size * fs);
            return p.measureText(value);
        }

        String fit(String value, float size, float max) {
            p.setTextSize(size * fs);
            if (p.measureText(value) <= max) return value;
            String s = value;
            while (s.length() > 1 && p.measureText(s + "\u2026") > max)
                s = s.substring(0, s.length() - 1);
            return s + "\u2026";
        }

        void text(Canvas c, String s, float x, float y, float size,
                  int color, Paint.Align align) {
            p.setTextSize(size * fs);
            p.setColor(color);
            p.setTextAlign(align);
            c.drawText(s, x, y, p);
        }

        void round(Canvas c, float l, float t, float r, float b,
                   float rad, int color) {
            p.setColor(color);
            c.drawRoundRect(new RectF(l, t, r, b), rad, rad, p);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            boolean rtl = isRtl();
            int top = insetsTop, bottom = insetsBottom;
            if (top == 0 && bottom == 0 && android.os.Build.VERSION.SDK_INT >= 23
                    && getRootWindowInsets() != null) {
                top = getRootWindowInsets().getSystemWindowInsetTop();
                bottom = getRootWindowInsets().getSystemWindowInsetBottom();
            }
            c.save();
            c.translate(0, top);
            c.scale(d, d);
            int w = (int) (getWidth() / d);
            c.drawColor(bg);

            Paint.Align edgeAlign = rtl ? Paint.Align.RIGHT : Paint.Align.LEFT;
            float edgeX = rtl ? w - 32 : 32;
            text(c, getString(R.string.app_name), edgeX, 58, 25, fg, edgeAlign);
            text(c, fit(getString(R.string.subtitle_offline_bank_balances), 14, w - 64), edgeX, 86, 14, muted, edgeAlign);

            drawLockIcon(c, LockManager.isEnabled(MainActivity.this) ? active : accent);

            round(c, 24, 120, w - 24, 270, 28, panel);
            float totalLabelX = rtl ? w - 48 : 48;
            text(c, getString(R.string.total_balance_label), totalLabelX, 156, 13, muted, edgeAlign);
            if (banks.isEmpty() || excluded.containsAll(banks.keySet())) {
                // No counts yet — or nothing counts anymore (every account excluded): a dash, not
                // a literal zero, so a fresh install or an all-excluded state never reads as a
                // real zero-rial balance.
                text(c, getString(R.string.total_empty_value), totalLabelX, 208, 34, fg, edgeAlign);
            } else {
                totalValue(c, total, totalLabelX, 208, w - 150, rtl);
            }
            text(c, getString(R.string.total_history_hint), w / 2f, 253, 11, muted, Paint.Align.CENTER);
            RectF eyeRect = rtl ? new RectF(45, 147, 75, 165) : new RectF(w - 75, 147, w - 45, 165);
            float eyeCenterX = rtl ? 60 : w - 60;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.5f);
            p.setColor(autoHide ? active : accent);
            c.drawOval(eyeRect, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(autoHide ? active : accent);
            c.drawCircle(eyeCenterX, 156, 5, p);
            if (hidden) {
                p.setColor(autoHide ? active : accent);
                p.setStrokeWidth(2.5f);
                if (rtl) c.drawLine(43, 142, 77, 170, p);
                else c.drawLine(w - 77, 142, w - 43, 170, p);
            }

            float banksHeaderX = rtl ? w - 28 : 28;
            text(c, getString(R.string.section_banks), banksHeaderX, 320, 22, fg, edgeAlign);
            float sortX = rtl ? 28 : w - 28;
            Paint.Align sortAlign = rtl ? Paint.Align.LEFT : Paint.Align.RIGHT;
            text(c, sortLabel(), sortX, 320, 14, accent, sortAlign);

            float by = (getHeight() - top - bottom) / d - 32;
            c.save();
            c.clipRect(0, 352, w, by - 42);
            float y = 352 - scrollY;
            if (banks.isEmpty()) {
                round(c, 24, y, w - 24, y + 96, 22, panel);
                float statusX = rtl ? w - 48 : 48;
                text(c, fit(status, 15, w - 96), statusX, y + 56, 15, muted, edgeAlign);
            } else {
                float valueWidth = Math.min(150, Math.max(115, w * .38f));
                float valueLeft = w - 60 - valueWidth;
                float badgeX = rtl ? w - 55 : 55;
                float nameX = rtl ? w - 88 : 88;
                float valueX = rtl ? 60 : w - 60;
                float menuX = rtl ? 36 : w - 36;
                Paint.Align nameAlign = rtl ? Paint.Align.RIGHT : Paint.Align.LEFT;
                Paint.Align valueAlign = rtl ? Paint.Align.LEFT : Paint.Align.RIGHT;
                for (BankRow row : rows()) {
                    float yy = row.top - scrollY;
                    String displayName = BankRules.displayName(MainActivity.this, row.bankName);
                    round(c, 24, yy, w - 24, yy + 82, 20, row.excluded ? bg : panel);
                    bankBadge(c, row.bankName, badgeX, yy + 41);
                    p.setColor(muted);
                    for (int dot = -1; dot <= 1; dot++)
                        c.drawCircle(menuX, yy + 41 + dot * 4.5f, 1.8f, p);
                    text(c, fit(displayName, 17, Math.max(40, valueLeft - 100)), nameX, yy + 36, 17,
                        row.excluded ? muted : fg, nameAlign);
                    if (row.excluded) {
                        value(c, row.amount, valueX, yy + 35, valueWidth, 17, valueAlign, true);
                        String exLabel = getString(R.string.excluded_label);
                        text(c, exLabel, nameX, yy + 68, 11, muted, nameAlign);
                    } else {
                        value(c, row.amount, valueX, yy + 35, valueWidth, 17, valueAlign);
                        if (row.bank.account != null) {
                            // The mask hides the account number too: it is as identifiable as the
                            // balance itself, so a shoulder-surf must not see either.
                            String accountText = hidden
                                ? "\u2022\u2022\u2022\u2022\u2022\u2022"
                                : getString(R.string.account_label) + " " + faDigits(row.bank.account);
                            text(c, fit(accountText, 12, Math.max(40, valueLeft - 100)), nameX, yy + 60, 12,
                                muted, nameAlign);
                        }
                    }
                }
            }
            c.restore();

            p.setTextSize(13);
            String aboutText = getString(R.string.footer_about);
            String langText = getString(R.string.footer_region_language);
            String backupText = getString(R.string.footer_data);
            String reportText = getString(R.string.footer_report);
            String sep = "  \u00b7  ";
            float aboutW = measure(aboutText, 13), langW = measure(langText, 13),
                backupW = measure(backupText, 13), reportW = measure(reportText, 13),
                sepW = measure(sep, 13);
            float totalW = aboutW + langW + backupW + reportW + sepW * 3;
            float scale = Math.min(1, (w - 64) / totalW);
            float x0 = (w - totalW * scale) / 2;
            if (!rtl) {
                footerReportStart = x0; text(c, reportText, x0, by + 4, 13 * scale, accent, Paint.Align.LEFT); x0 += reportW * scale; footerReportEnd = x0;
                text(c, sep, x0, by + 4, 13 * scale, muted, Paint.Align.LEFT); x0 += sepW * scale;
                footerBackupStart = x0; text(c, backupText, x0, by + 4, 13 * scale, purple, Paint.Align.LEFT); x0 += backupW * scale; footerBackupEnd = x0;
                text(c, sep, x0, by + 4, 13 * scale, muted, Paint.Align.LEFT); x0 += sepW * scale;
                footerLangStart = x0; text(c, langText, x0, by + 4, 13 * scale, purple, Paint.Align.LEFT); x0 += langW * scale; footerLangEnd = x0;
                text(c, sep, x0, by + 4, 13 * scale, muted, Paint.Align.LEFT); x0 += sepW * scale;
                footerAboutStart = x0; text(c, aboutText, x0, by + 4, 13 * scale, purple, Paint.Align.LEFT); x0 += aboutW * scale; footerAboutEnd = x0;
            } else {
                footerAboutStart = x0; text(c, aboutText, x0, by + 4, 13 * scale, purple, Paint.Align.LEFT); x0 += aboutW * scale; footerAboutEnd = x0;
                text(c, sep, x0, by + 4, 13 * scale, muted, Paint.Align.LEFT); x0 += sepW * scale;
                footerLangStart = x0; text(c, langText, x0, by + 4, 13 * scale, purple, Paint.Align.LEFT); x0 += langW * scale; footerLangEnd = x0;
                text(c, sep, x0, by + 4, 13 * scale, muted, Paint.Align.LEFT); x0 += sepW * scale;
                footerBackupStart = x0; text(c, backupText, x0, by + 4, 13 * scale, purple, Paint.Align.LEFT); x0 += backupW * scale; footerBackupEnd = x0;
                text(c, sep, x0, by + 4, 13 * scale, muted, Paint.Align.LEFT); x0 += sepW * scale;
                footerReportStart = x0; text(c, reportText, x0, by + 4, 13 * scale, accent, Paint.Align.LEFT); x0 += reportW * scale; footerReportEnd = x0;
            }
            footerY = by;
            if (indicatorVisible) drawPullIndicator(c, w);
            String summary = announce();
            if (!summary.equals(getContentDescription())) setContentDescription(summary);
            c.restore();
        }

        /** The pull-to-refresh arrow: a small chip with a circular arrow that follows the finger down
         *  while rotated by how far the pull has gone, then spins on release. Drawn last, on top. */
        void drawPullIndicator(Canvas c, int w) {
            if (pullFade <= 0.02f) return;
            float cx = w / 2f, cy = 58f + pullShift;
            int alpha = (int) (255 * Math.min(1, pullFade));
            p.setStyle(Paint.Style.FILL);
            p.setColor(bg);
            p.setAlpha(alpha);
            c.drawCircle(cx, cy, 17, p);
            p.setColor(accent);
            p.setAlpha(alpha);
            c.save();
            c.translate(cx, cy);
            c.rotate(spinAngle);
            float g = 9.5f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.4f);
            p.setStrokeCap(Paint.Cap.ROUND);
            Path ring = new Path();
            ring.addArc(new RectF(-g, -g, g, g), -90, 290);
            c.drawPath(ring, p);
            float tipX = (float) (g * Math.cos(Math.toRadians(200)));
            float tipY = (float) (g * Math.sin(Math.toRadians(200)));
            Path head = new Path();
            head.moveTo(tipX, tipY);
            head.lineTo((float) (tipX + 3.2 * Math.cos(Math.toRadians(170))),
                (float) (tipY + 3.2 * Math.sin(Math.toRadians(170))));
            head.lineTo((float) (tipX + 3.2 * Math.cos(Math.toRadians(230))),
                (float) (tipY + 3.2 * Math.sin(Math.toRadians(230))));
            head.close();
            p.setStyle(Paint.Style.FILL);
            p.setStrokeWidth(0);
            c.drawPath(head, p);
            c.restore();
            p.setAlpha(255);
            p.setStrokeCap(Paint.Cap.BUTT);
        }

        /** Uses the canonical (English) name so a bank's badge stays stable across languages: the bank's
         *  brand icon when one is available, otherwise the colored square with the bank's initials. */
        void bankBadge(Canvas c, String canonicalName, float x, float centerY) {
            int iconRes = BankIcon.iconFor(canonicalName);
            if (iconRes != 0) {
                Drawable icon = getResources().getDrawable(iconRes, getTheme());
                icon.setBounds((int) (x - 16), (int) (centerY - 16), (int) (x + 16), (int) (centerY + 16));
                icon.draw(c);
                return;
            }
            int color = bankColors[Math.floorMod(canonicalName.hashCode(), bankColors.length)];
            round(c, x - 18, centerY - 18, x + 18, centerY + 18, 12, color);
            text(c, bankInitials(canonicalName), x, centerY + 5, 11, Color.WHITE, Paint.Align.CENTER);
        }

        String bankInitials(String name) {
            String[] words = name.split(" ");
            if (words.length > 1)
                return (words[0].substring(0, 1) + words[1].substring(0, 1)).toUpperCase(Locale.US);
            return name.substring(0, Math.min(2, name.length())).toUpperCase(Locale.US);
        }

        /** Account numbers and other plain numerals follow the app language's digit rules, matching
         *  how {@link BalanceData#toman} formats amounts. */
        String faDigits(String s) {
            return LocaleHelper.isPersian(MainActivity.this)
                ? HistoryActivity.faDigitsString(s) : s;
        }

        /** One card row in the bank list. Every bank entry — whether a one-account bank or a single
         *  account of a multi-account bank — is its own flat card, so accounts read exactly like
         *  separate banks. Geometry is in dp relative to the bank section's top edge (352), so a
         *  row's screen position is {@code row.top - scrollY}. */
        private static final class BankRow {
            static final int SINGLE = 0;
            final int kind;
            final Bank bank;
            final String bankName;
            final String key;
            final long amount;
            final boolean excluded;
            final float top, height;
            BankRow(int kind, Bank bank, String bankName, String key, long amount, boolean excluded,
                    float top, float height) {
                this.kind = kind;
                this.bank = bank;
                this.bankName = bankName;
                this.key = key;
                this.amount = amount;
                this.excluded = excluded;
                this.top = top;
                this.height = height;
            }
        }

        /** Lays out the bank cards: one full card per bank entry — a one-account bank contributes a
         *  single card, a multi-account bank one card per account, with no aggregate header. Every
         *  card is drawn and hit-tested identically, so an account behaves like a bank of its own.
         *  Re-derived on every call (the lists are tiny) so drawing and touch always agree. */
        java.util.List<BankRow> rows() {
            java.util.List<BankRow> out = new java.util.ArrayList<>();
            float cursor = 352;
            for (java.util.List<Bank> block : BalanceData.groupedForDisplay(banks, excluded, sortMode)) {
                for (Bank b : block) {
                    String key = BalanceData.storageKey(b.name, b.account);
                    out.add(new BankRow(BankRow.SINGLE, b, b.name, key, b.amount,
                        excluded.contains(key), cursor, 82));
                    cursor += 96;
                }
            }
            bankListHeight = cursor - 352;
            return out;
        }

        /** Maps a y position in bank-section space (already including any scroll offset) to the card
         *  row it lands on, or {@code null} when it falls into the gap between cards. */
        BankRow rowAt(float y) {
            for (BankRow r : rows())
                if (y >= r.top && y < r.top + r.height) return r;
            return null;
        }

        /** The compact sort button label shown next to the "Banks" header; the arrow shows direction,
         *  so re-tapping the sort dialog's matching option reads as reversing that direction. */
        String sortLabel() {
            switch (sortMode) {
                case BalanceData.SORT_BALANCE_HIGH:
                    return getString(R.string.sort_label_balance) + " \u2193";
                case BalanceData.SORT_BALANCE_LOW:
                    return getString(R.string.sort_label_balance) + " \u2191";
                case BalanceData.SORT_DATE_RECENT:
                    return getString(R.string.sort_label_date) + " \u2193";
                case BalanceData.SORT_DATE_OLDEST:
                    return getString(R.string.sort_label_date) + " \u2191";
                default:
                    return getString(R.string.action_sort);
            }
        }

        /** Draws the lock icon at the top-right corner of the app bar. */
        void drawLockIcon(Canvas c, int color) {
            float cx = lockCx();
            float cy = 54;
            if (lockIcon == null) {
                lockIcon = getContext().getDrawable(R.drawable.ic_lock).mutate();
            }
            lockIcon.setTint(color);
            int half = 12;
            lockIcon.setBounds((int) (cx - half), (int) (cy - half), (int) (cx + half), (int) (cy + half));
            lockIcon.draw(c);
        }

        float lockCx() {
            return isRtl() ? 40 : getWidth() / d - 40;
        }

        /** The centre of the eye/mask toggle in the balance card (top-right, mirrored in RTL). */
        float eyeCx() {
            return isRtl() ? 60 : getWidth() / d - 60;
        }

        private boolean iconHit(float x, float y, float cx) {
            return y >= 28 && y <= 80 && Math.abs(x - cx) <= 24;
        }

        /** A generous target around the eye: the glyph itself is small, so allow a wider band
         *  inside the balance card and a little slack on the inner (non-edge) side. */
        boolean eyeHit(float x, float y) {
            boolean rtl = isRtl();
            if (y < 135 || y > 185) return false;
            float cx = eyeCx();
            return rtl ? (x >= cx - 15 && x <= cx + 30) : (x >= cx - 30 && x <= cx + 15);
        }

        /** The app-bar control under a tap: lock, eye (mask), or nothing, each with its own 24dp
         *  hit radius so the two never overlap. */
        int iconId(float x, float y) {
            if (iconHit(x, y, lockCx())) return ICON_LOCK;
            if (eyeHit(x, y)) return ICON_EYE;
            return ICON_NONE;
        }

        /** Picks a sort. Selecting the active category again reverses its direction, which the dialog
         *  rows communicate explicitly ("… — tap again to reverse"). Excluded banks stay at the bottom
         *  of the list in every mode. */
        void showSortDialog() {
            boolean balHigh = sortMode == BalanceData.SORT_BALANCE_HIGH;
            boolean balLow = sortMode == BalanceData.SORT_BALANCE_LOW;
            boolean dateRecent = sortMode == BalanceData.SORT_DATE_RECENT;
            boolean dateOldest = sortMode == BalanceData.SORT_DATE_OLDEST;
            String balanceLabel = balHigh ? getString(R.string.sort_balance_high_reverse)
                : balLow ? getString(R.string.sort_balance_low_reverse)
                : getString(R.string.sort_balance_prompt);
            String dateLabel = dateRecent ? getString(R.string.sort_date_recent_reverse)
                : dateOldest ? getString(R.string.sort_date_oldest_reverse)
                : getString(R.string.sort_date_prompt);
            String[] options = { balanceLabel, dateLabel };
            showDialog(new android.app.AlertDialog.Builder(MainActivity.this)
                .setTitle(getString(R.string.sort_dialog_title))
                .setItems(options, (dialogInterface, which) -> {
                    if (which == 0) sortMode = balHigh ? BalanceData.SORT_BALANCE_LOW
                        : BalanceData.SORT_BALANCE_HIGH;
                    else sortMode = dateRecent ? BalanceData.SORT_DATE_OLDEST
                        : BalanceData.SORT_DATE_RECENT;
                    BalanceData.setSort(MainActivity.this, sortMode);
                    invalidate();
                })
                .create());
        }

        void copyBalance(String label, long value) {
            if (hidden) {
                Toast.makeText(MainActivity.this, getString(R.string.toast_unmask_to_copy), Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            long shown = CurrencyHelper.CURRENCY_TOMAN.equals(CurrencyHelper.currency(MainActivity.this))
                ? value / 10 : value;
            String amount = Long.toString(shown);
            clipboard.setPrimaryClip(ClipData.newPlainText(label, amount));
            Toast.makeText(MainActivity.this, getString(R.string.toast_copied_balance, label), Toast.LENGTH_SHORT).show();
            // Sensitive numbers must not linger in the system clipboard (other apps can read it):
            // clear it again once the paste window has passed, unless the user copied something else
            // in the meantime (then that newer clip is left alone).
            if (clearClipRunnable != null) handler.removeCallbacks(clearClipRunnable);
            clearClipRunnable = () -> {
                clearClipRunnable = null;
                CharSequence current = clipboard.hasPrimaryClip()
                    && clipboard.getPrimaryClip() != null
                    && clipboard.getPrimaryClip().getItemCount() > 0
                    ? clipboard.getPrimaryClip().getItemAt(0).getText() : null;
                if (amount.equals(String.valueOf(current))) {
                    if (android.os.Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip();
                    else clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
                }
            };
            handler.postDelayed(clearClipRunnable, CLIP_CLEAR_MS);
        }

        /** Opens the row menu for one entry. {@code key} is that entry's {@code bank|account} storage
         *  key, so exclude/include affects only the tapped account, never its siblings. */
        void showBankMenu(String key, String bankName, long amount) {
            String displayName = BankRules.displayName(MainActivity.this, bankName);
            boolean isExcluded = excluded.contains(key);
            String[] options = {
                getString(isExcluded ? R.string.action_include : R.string.action_exclude),
                getString(R.string.action_copy_balance)
            };
            showDialog(new android.app.AlertDialog.Builder(MainActivity.this)
                .setTitle(displayName)
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        BalanceData.toggleExcluded(MainActivity.this, key);
                        excluded.clear();
                        excluded.addAll(BalanceData.getExcluded(MainActivity.this));
                        recalcTotal();
                        invalidate();
                        BalanceWidgetProvider.push(MainActivity.this);
                        Toast.makeText(MainActivity.this,
                            getString(excluded.contains(key)
                                ? R.string.toast_excluded : R.string.toast_included),
                            Toast.LENGTH_SHORT).show();
                    } else {
                        copyBalance(displayName, amount);
                    }
                })
                .create());
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            boolean rtl = isRtl();
            int top = insetsTop, bottom = insetsBottom;
            if (top == 0 && bottom == 0 && android.os.Build.VERSION.SDK_INT >= 23
                    && getRootWindowInsets() != null) {
                top = getRootWindowInsets().getSystemWindowInsetTop();
                bottom = getRootWindowInsets().getSystemWindowInsetBottom();
            }
            float x = e.getX() / d, y = (e.getY() - top) / d,
                h = (getHeight() - top - bottom) / d;
            if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
            velocityTracker.addMovement(e);
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                hideIndicator();
                scroller.forceFinished(true);
                velocityTracker.clear();
                lastY = y; downY = y; dragging = false;
                downIcon = iconId(x, y);
                lockArmed = downIcon == ICON_LOCK;
                eyeArmed = downIcon == ICON_EYE;
                totalArmed = downIcon == ICON_NONE && y >= 120 && y <= 270;
                lockProbeFired = false;
                eyeProbeFired = false;
                totalProbeFired = false;
                bankArmed = false;
                bankProbeFired = false;
                bankProbeRow = null;
                handler.removeCallbacks(lockLongProbe);
                handler.removeCallbacks(eyeLongProbe);
                handler.removeCallbacks(totalLongProbe);
                handler.removeCallbacks(bankLongProbe);
                if (lockArmed) handler.postDelayed(lockLongProbe, 480);
                else if (eyeArmed) handler.postDelayed(eyeLongProbe, 500);
                else if (totalArmed) handler.postDelayed(totalLongProbe, 500);
                else if (y >= 352 && y < byForTouch(h)
                        && (rtl ? x >= 56 : x <= getWidth() / d - 56)) {
                    // On a bank or account row, off the 3-dot menu: a long-press copies that row's
                    // balance, mirroring the total card.
                    BankRow row = rowAt(y + scrollY);
                    if (row != null) {
                        bankProbeRow = row;
                        bankArmed = true;
                        handler.postDelayed(bankLongProbe, 500);
                    }
                }
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_MOVE) {
                if (scrollY == 0 && downY < 360 && y > downY)
                    startPull(y - downY, PULL_TRIGGER);
                if (Math.abs(y - lastY) > 3) {
                    dragging = true;
                    scroller.forceFinished(true);
                    handler.removeCallbacks(lockLongProbe);
                    handler.removeCallbacks(eyeLongProbe);
                    handler.removeCallbacks(totalLongProbe);
                    handler.removeCallbacks(bankLongProbe);
                    lockArmed = false;
                    eyeArmed = false;
                    totalArmed = false;
                    bankArmed = false;
                    bankProbeRow = null;
                    downIcon = ICON_NONE;
                    rows();
                    scrollY = Math.max(0, Math.min(
                        Math.max(0, bankListHeight - (h - 440)),
                        scrollY + lastY - y));
                    lastY = y;
                    invalidate();
                }
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_CANCEL) {
                hideIndicator();
                velocityTracker.recycle();
                velocityTracker = null;
                scroller.forceFinished(true);
                // A cancelled gesture must behave like a lift that triggers nothing: drop every
                // pending long-press probe and disarm so nothing fires after the touch is gone.
                handler.removeCallbacks(lockLongProbe);
                handler.removeCallbacks(eyeLongProbe);
                handler.removeCallbacks(totalLongProbe);
                handler.removeCallbacks(bankLongProbe);
                lockProbeFired = false;
                eyeProbeFired = false;
                totalProbeFired = false;
                bankProbeFired = false;
                lockArmed = false;
                eyeArmed = false;
                totalArmed = false;
                bankArmed = false;
                bankProbeRow = null;
                return true;
            }
            if (e.getAction() != MotionEvent.ACTION_UP) return true;
            handler.removeCallbacks(lockLongProbe);
            handler.removeCallbacks(eyeLongProbe);
            handler.removeCallbacks(totalLongProbe);
            handler.removeCallbacks(bankLongProbe);
            if (lockProbeFired) { lockProbeFired = false; lockArmed = false; return true; }
            if (eyeProbeFired) { eyeProbeFired = false; eyeArmed = false; return true; }
            if (totalProbeFired) { totalProbeFired = false; totalArmed = false; return true; }
            if (bankProbeFired) {
                bankProbeFired = false;
                bankArmed = false;
                bankProbeRow = null;
                return true;
            }
            if (dragging) {
                handleDragRelease(downY, y);
                return true;
            }
            if (y > footerY - 20 && y < footerY + 24) {
                if (x >= footerAboutStart - 10 && x <= footerAboutEnd + 10) {
                    startActivity(new Intent(MainActivity.this, AboutActivity.class));
                } else if (x >= footerLangStart - 10 && x <= footerLangEnd + 10) {
                    MainActivity.this.regionLanguageDialog();
                } else if (x >= footerBackupStart - 10 && x <= footerBackupEnd + 10) {
                    MainActivity.this.dataDialog();
                } else if (x >= footerReportStart - 10 && x <= footerReportEnd + 10) {
                    startActivity(new Intent(MainActivity.this, ScanDiagnosticsActivity.class));
                }
            } else if (downIcon == ICON_LOCK) {
                MainActivity.this.onLockTap();
            } else if (downIcon == ICON_EYE) {
                hidden = !hidden;
                MainActivity.this.getSharedPreferences(BalanceData.PREFS_PREF, MODE_PRIVATE)
                    .edit().putBoolean(BalanceData.KEY_HIDDEN, hidden).apply();
                invalidate();
            } else if (y >= 120 && y <= 270) {
                startActivity(new Intent(MainActivity.this, HistoryActivity.class));
            } else if (y > 290 && y < 350 && (rtl ? x < 150 : x > getWidth() / d - 150)) {
                showSortDialog();
            } else if (y >= 352 && y < byForTouch(h)) {
                if (banks.isEmpty()) {
                    // No balances at all: the empty card is the app's main entry point again. Without
                    // SMS permission (e.g. after a permanent denial, which no runtime re-request can
                    // lift) it deep-links into the app's system settings; otherwise it retries the scan.
                    if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
                        openSmsSettings();
                    else refresh();
                    return true;
                }
                BankRow row = rowAt(y + scrollY);
                if (row != null) {
                    boolean onMenu = rtl
                        ? x >= 16 && x <= 56
                        : x >= getWidth() / d - 56 && x <= getWidth() / d - 16;
                    if (onMenu) {
                        showBankMenu(row.key, row.bankName, row.amount);
                    } else {
                        Intent history = new Intent(MainActivity.this, HistoryActivity.class);
                        history.putExtra(HistoryActivity.EXTRA_BANK, row.bankName);
                        if (row.bank != null && row.bank.account != null)
                            history.putExtra(HistoryActivity.EXTRA_ACCOUNT, row.bank.account);
                        startActivity(history);
                    }
                }
            }
            performClick();
            return true;
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        float byForTouch(float h) { return h - 74; }
    }
}
