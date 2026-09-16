package com.ashkanrafiee.balance;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The full-screen lock the app shows over the balance and history screens while the session is
 * locked. Reused by both protected activities through the same instance API.
 *
 * <p>Supports two entrance modes, following the stored choice: a numeric four-by-four keypad with a
 * masked digit display for PINs, and a password field with the soft keyboard for passwords. When a
 * fingerprint was bound on a capable device, a fingerprint row sits under the input and presents the
 * system {@link FingerprintManager} dialog. All colors resolve from the theme resources, so the
 * screen follows light/dark like the rest of the app.
 *
 * <p>The same view doubles as the identity check before lock-settings changes (verify mode): it adds
 * a Cancel link and reports the successful unlock so the caller can proceed with the requested
 * change. Only the unlock {@link Runnable} is ever invoked after a verified identity.
 */
public final class LockOverlay extends FrameLayout {
    public interface UnlockListener {
        void onUnlocked();
    }

    private final Context ctx;
    private UnlockListener unlockListener;
    private Runnable cancelListener;

    private final int bg, panel, fg, muted, accent, negative;
    private final TextView title, subtitle, error, dots;
    private final LinearLayout pinPad;
    private final LinearLayout passwordEntry;
    private final EditText passwordInput;
    private final TextView fpRow;
    private final TextView cancel;

    private final StringBuilder pinBuffer = new StringBuilder();
    private boolean pinMode = true;
    private boolean verifyMode = false;
    private boolean busy = false;
    private CancellationSignal fpCancel;

    public LockOverlay(Context context) {
        super(context);
        ctx = context;
        bg = res(R.color.bg);
        panel = res(R.color.panel);
        fg = res(R.color.fg);
        muted = res(R.color.muted);
        accent = res(R.color.accent);
        negative = res(R.color.negative);
        setBackgroundColor(bg);
        setClickable(true);
        setFocusable(true);

        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setOnApplyWindowInsetsListener((v, ins) -> {
            int top = 0, bottom = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets x = ins.getInsets(android.view.WindowInsets.Type.systemBars());
                top = x.top;
                bottom = x.bottom;
            } else {
                top = ins.getSystemWindowInsetTop();
                bottom = ins.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top + dp(24), 0, bottom + dp(16));
            return ins;
        });
        FrameLayout.LayoutParams scrollLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        addView(scroll, scrollLp);

        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setLayoutParams(new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL));
        scroll.addView(content);

        ImageView lockIcon = new ImageView(ctx);
        lockIcon.setImageResource(R.drawable.ic_lock);
        lockIcon.setColorFilter(accent);
        content.addView(lockIcon, new LinearLayout.LayoutParams(dp(56), dp(56)));

        title = text(getString(R.string.lock_title), 22, fg);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-2, -2);
        titleLp.topMargin = dp(16);
        content.addView(title, titleLp);

        subtitle = text("", 14, muted);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(dp(280), -2);
        subLp.topMargin = dp(6);
        content.addView(subtitle, subLp);

        error = text("", 13, negative);
        error.setGravity(Gravity.CENTER);
        error.setVisibility(GONE);
        LinearLayout.LayoutParams errLp = new LinearLayout.LayoutParams(dp(280), -2);
        errLp.topMargin = dp(8);
        content.addView(error, errLp);

        // Masked digit display for the PIN keypad.
        dots = text("", 24, fg);
        dots.setGravity(Gravity.CENTER);
        dots.setLetterSpacing(.18f);
        GradientDrawable dotsBg = rounded(panel, 16);
        dots.setBackground(dotsBg);
        dots.setPadding(dp(28), dp(12), dp(28), dp(12));
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(-2, -2);
        dotsLp.topMargin = dp(28);
        content.addView(dots, dotsLp);

        pinPad = new LinearLayout(ctx);
        pinPad.setOrientation(LinearLayout.VERTICAL);
        pinPad.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(-2, -2);
        padLp.topMargin = dp(18);
        content.addView(pinPad, padLp);
        buildPad();

        passwordEntry = new LinearLayout(ctx);
        passwordEntry.setOrientation(LinearLayout.VERTICAL);
        passwordEntry.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams pwLp = new LinearLayout.LayoutParams(dp(280), -2);
        pwLp.topMargin = dp(28);
        content.addView(passwordEntry, pwLp);

        passwordInput = new EditText(ctx);
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        passwordInput.setTextColor(fg);
        passwordInput.setHintTextColor(muted);
        passwordInput.setTextSize(17);
        passwordInput.setBackground(rounded(panel, 14));
        passwordInput.setPadding(dp(18), dp(14), dp(18), dp(14));
        passwordInput.setSingleLine(true);
        passwordInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        passwordInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { submit(); return true; }
            return false;
        });
        passwordEntry.addView(passwordInput, new LinearLayout.LayoutParams(-1, -2));

        TextView unlock = text(getString(R.string.lock_action_unlock), 16, Color.WHITE);
        unlock.setGravity(Gravity.CENTER);
        GradientDrawable unlockBg = rounded(accent, 14);
        unlock.setBackground(unlockBg);
        unlock.setPadding(dp(24), dp(12), dp(24), dp(12));
        unlock.setOnClickListener(v -> submit());
        LinearLayout.LayoutParams unlockLp = new LinearLayout.LayoutParams(-2, -2);
        unlockLp.topMargin = dp(14);
        passwordEntry.addView(unlock, unlockLp);

        fpRow = text(getString(R.string.lock_fingerprint_action), 15, accent);
        fpRow.setGravity(Gravity.CENTER);
        fpRow.setCompoundDrawablePadding(dp(8));
        android.graphics.drawable.Drawable fpIcon = ctx.getDrawable(R.drawable.ic_fingerprint).mutate();
        fpIcon.setTintList(ColorStateList.valueOf(accent));
        fpRow.setCompoundDrawablesWithIntrinsicBounds(null, null, fpIcon, null);
        fpRow.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable fpBg = rounded(panel, 16);
        fpRow.setBackground(ripple(fpBg));
        fpRow.setOnClickListener(v -> startFingerprint());
        LinearLayout.LayoutParams fpLp = new LinearLayout.LayoutParams(-2, -2);
        fpLp.topMargin = dp(22);
        content.addView(fpRow, fpLp);
        fpRow.setVisibility(GONE);

        cancel = text(getString(R.string.lock_cancel), 15, accent);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(24), dp(12), dp(24), dp(12));
        cancel.setOnClickListener(v -> {
            if (cancelListener != null) cancelListener.run();
        });
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(-2, -2);
        cancelLp.topMargin = dp(14);
        content.addView(cancel, cancelLp);
        cancel.setVisibility(GONE);
    }

    // ====================================================================
    // Public API
    // ====================================================================

    public void setUnlockListener(UnlockListener l) {
        unlockListener = l;
    }

    public void setCancelListener(Runnable r) {
        cancelListener = r;
    }

    /** Shows the plain lock entrance (session locked). */
    public void showLock() {
        verifyMode = false;
        configure();
        reveal();
    }

    /** Shows the lock as an identity check ahead of a lock-settings change, with a Cancel link. */
    public void showVerify() {
        verifyMode = true;
        configure();
        reveal();
    }

    public void hide() {
        cancelFingerprint();
        if (getVisibility() != GONE) setVisibility(GONE);
        if (getWindowToken() != null) {
            InputMethodManager ime = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (ime != null) ime.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelFingerprint();
    }

    // ====================================================================
    // Internals
    // ====================================================================

    private void configure() {
        pinMode = LockManager.isPinMode(ctx);
        int titleRes = verifyMode ? R.string.lock_verify_title : R.string.lock_title;
        int subRes = verifyMode
            ? (pinMode ? R.string.lock_verify_subtitle_pin : R.string.lock_verify_subtitle_password)
            : (pinMode ? R.string.lock_subtitle_pin : R.string.lock_subtitle_password);
        title.setText(getString(titleRes));
        subtitle.setText(getString(subRes));
        passwordInput.setHint(getString(R.string.lock_password_hint));
        pinPad.setVisibility(pinMode ? VISIBLE : GONE);
        dots.setVisibility(pinMode ? VISIBLE : GONE);
        passwordEntry.setVisibility(pinMode ? GONE : VISIBLE);
        cancel.setVisibility(verifyMode ? VISIBLE : GONE);
        clearInput();
        hideError();
        applyFingerprintRow();
    }

    private void reveal() {
        pinMode = LockManager.isPinMode(ctx);
        setVisibility(VISIBLE);
        if (!pinMode) {
            passwordInput.requestFocus();
            post(() -> {
                if (getVisibility() == VISIBLE) {
                    InputMethodManager ime = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (ime != null) ime.showSoftInput(passwordInput, InputMethodManager.SHOW_IMPLICIT);
                }
            });
        }
        applyFingerprintRow();
    }

    private void applyFingerprintRow() {
        boolean usable = !busy && LockManager.fpStatus(ctx) == LockManager.FP_OK;
        fpRow.setVisibility(usable ? VISIBLE : GONE);
        fpRow.setText(getString(R.string.lock_fingerprint_action));
    }

    private void clearInput() {
        pinBuffer.setLength(0);
        passwordInput.setText("");
        renderDots();
    }

    private void renderDots() {
        int n = pinBuffer.length();
        if (n == 0) {
            dots.setTextColor(muted);
            dots.setText(getString(R.string.lock_pin_placeholder));
        } else {
            dots.setTextColor(fg);
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < n; i++) b.append('\u25CF');
            dots.setText(b.toString());
        }
    }

    private void showError(int res) {
        error.setText(getString(res));
        error.setVisibility(VISIBLE);
    }

    private void hideError() {
        error.setVisibility(GONE);
    }

    private void setEntryEnabled(boolean enabled) {
        for (int i = 0; i < pinPad.getChildCount(); i++) {
            View child = pinPad.getChildAt(i);
            if (child instanceof ViewGroup) setChildEnabled((ViewGroup) child, enabled);
            else child.setEnabled(enabled);
        }
        passwordInput.setEnabled(enabled);
        fpRow.setEnabled(enabled);
    }

    private void setChildEnabled(ViewGroup g, boolean enabled) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof ViewGroup) setChildEnabled((ViewGroup) c, enabled);
            else c.setEnabled(enabled);
        }
    }

    /** Verifies the entered code on a worker thread (PBKDF2 is deliberately slow). */
    private void submit() {
        if (busy || !LockManager.isEnabled(ctx)) return;
        String code = pinMode ? pinBuffer.toString() : passwordInput.getText().toString();
        if (code.isEmpty()) {
            showError(R.string.lock_validate_empty);
            return;
        }
        if (pinMode && !LockManager.validCode(code, true)) {
            showError(R.string.lock_validate_pin_length);
            return;
        }
        busy = true;
        setEntryEnabled(false);
        hideError();
        new Thread(() -> {
            boolean ok = LockManager.verify(ctx, code);
            final boolean result = ok;
            post(() -> finishAttempt(result));
        }).start();
    }

    private void finishAttempt(boolean ok) {
        busy = false;
        setEntryEnabled(true);
        if (ok) {
            success();
        } else {
            showError(R.string.lock_error_incorrect);
            clearInput();
        }
    }

    private void success() {
        cancelFingerprint();
        LockManager.unlockSession();
        hide();
        if (unlockListener != null) unlockListener.onUnlocked();
    }

    @SuppressWarnings("deprecation")
    private void startFingerprint() {
        if (busy) return;
        int status = LockManager.fpStatus(ctx);
        if (status == LockManager.FP_INVALIDATED) {
            showError(R.string.lock_error_fingerprint_expired);
            return;
        }
        if (status != LockManager.FP_OK) {
            showError(R.string.lock_error_fingerprint_unavailable);
            return;
        }
        FingerprintManager fm = LockManager.fingerprintManager(ctx);
        FingerprintManager.CryptoObject crypto = LockManager.fingerprintCrypto(ctx);
        if (fm == null || crypto == null) {
            showError(R.string.lock_error_fingerprint_unavailable);
            return;
        }
        busy = true;
        setEntryEnabled(false);
        hideError();
        fpCancel = new CancellationSignal();
        fm.authenticate(crypto, fpCancel, 0, new FingerprintManager.AuthenticationCallback() {
            @Override public void onAuthenticationSucceeded(FingerprintManager.AuthenticationResult result) {
                final boolean ok = LockManager.fingerprintSucceeded(ctx, result);
                post(() -> {
                    if (ok) success();
                    else {
                        busy = false;
                        setEntryEnabled(true);
                        showError(R.string.lock_error_fingerprint_expired);
                        clearInput();
                    }
                });
            }

            @Override public void onAuthenticationFailed() {
                post(() -> {
                    busy = false;
                    setEntryEnabled(true);
                    showError(R.string.lock_error_fingerprint_failed);
                });
            }

            @Override public void onAuthenticationError(int code, CharSequence msg) {
                post(() -> {
                    busy = false;
                    setEntryEnabled(true);
                    if (code != FingerprintManager.FINGERPRINT_ERROR_CANCELED
                            && code != FingerprintManager.FINGERPRINT_ERROR_USER_CANCELED) {
                        showError(R.string.lock_error_fingerprint_failed);
                    }
                });
            }
        }, null);
    }

    private void cancelFingerprint() {
        if (fpCancel != null) {
            try {
                fpCancel.cancel();
            } catch (Exception ignored) { }
            fpCancel = null;
        }
    }

    // ====================================================================
    // Keypad construction
    // ====================================================================

    private void buildPad() {
        char[][] keys = {
            {'1', '2', '3'},
            {'4', '5', '6'},
            {'7', '8', '9'},
            {'\u232B', '0', '\u2713'},
        };
        for (char[] row : keys) {
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_HORIZONTAL);
            for (char k : row) {
                final char key = k;
                TextView b = padKey(String.valueOf(k), k == '\u2713');
                b.setOnClickListener(v -> padTapped(key));
                line.addView(b);
            }
            pinPad.addView(line);
        }
    }

    private TextView padKey(String label, boolean submitKey) {
        TextView k = new TextView(ctx);
        k.setText(label);
        k.setTextSize(20);
        k.setGravity(Gravity.CENTER);
        k.setTextColor(submitKey ? Color.WHITE : fg);
        k.setTypeface(null, Typeface.NORMAL);
        GradientDrawable g = rounded(submitKey ? accent : panel, 14);
        k.setBackground(ripple(g));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), dp(52));
        lp.setMargins(dp(5), dp(6), dp(5), dp(6));
        k.setLayoutParams(lp);
        return k;
    }

    private void padTapped(char key) {
        if (busy) return;
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (key == '\u232B') {
            if (pinBuffer.length() > 0) pinBuffer.deleteCharAt(pinBuffer.length() - 1);
        } else if (key == '\u2713') {
            submit();
            return;
        } else {
            if (pinBuffer.length() < 8) pinBuffer.append(key);
        }
        hideError();
        renderDots();
    }

    // ====================================================================
    // Styling helpers
    // ====================================================================

    private int res(int r) {
        return ctx.getResources().getColor(r, ctx.getTheme());
    }

    private int dp(float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + .5f);
    }

    private String getString(int r) {
        return ctx.getString(r);
    }

    private TextView text(String s, float size, int color) {
        TextView v = new TextView(ctx);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setTypeface(null, Typeface.NORMAL);
        return v;
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    /** A touch-ripple that sits exactly over a rounded fill, matching the app's other buttons. */
    private RippleDrawable ripple(GradientDrawable bg) {
        int tint = (accent & 0x00FFFFFF) | 0x2E000000;
        return new RippleDrawable(ColorStateList.valueOf(tint), bg, bg);
    }
}