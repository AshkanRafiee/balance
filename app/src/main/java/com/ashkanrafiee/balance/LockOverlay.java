package com.ashkanrafiee.balance;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.CancellationSignal;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The full-screen lock the app shows over the balance and history screens while the session is
 * locked. Reused by both protected activities through the same instance API.
 *
 * <p>Supports two entrance modes, following the stored choice: a numeric three-by-four keypad with
 * a masked digit display for PINs, and a password field with the soft keyboard for passwords. The
 * two share one layout: a small lock badge, title and hint at the top, then — vertically centered —
 * either the PIN digits (with the keypad in a rounded card pinned just above the navigation area)
 * or the compact password cluster, which stays mid-screen instead of spreading over empty space.
 * Nothing sits under the system bars, and the whole page scrolls as one when the display is too
 * short for the controls. When a fingerprint was bound on a capable device, opening the lock starts
 * the system {@code BiometricPrompt} automatically, with the keypad (or password field) underneath
 * as the fallback; a fingerprint row under the input re-opens the prompt at any time (or on Android
 * 8, opens the legacy fingerprint dialog). All colors resolve from the theme resources, so the
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

    private final int bg, panel, fg, muted, accent, negative, hero;
    private TextView title, subtitle, error, dots;
    private TextView pwTitle, pwSubtitle, pwError;
    private ImageView headerBadge;
    private final LinearLayout pinPad;
    private LinearLayout padCard;
    private final LinearLayout columnView;
    private final LinearLayout passwordEntry;
    private LinearLayout pwHeader;
    private final EditText passwordInput;
    private final TextView fpRow;
    private final TextView cancel;
    private TextView unlockLabel, padCheckLabel;
    private ProgressBar unlockSpin, padCheckSpin;

    private final StringBuilder pinBuffer = new StringBuilder();
    private boolean pinMode = true;
    private boolean verifyMode = false;
    private boolean busy = false;
    private boolean fpPromptAllowed = true;
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
        hero = res(R.color.hero);
        setBackgroundColor(bg);
        setClickable(true);
        setFocusable(true);
        setOnApplyWindowInsetsListener((v, ins) -> {
            int top = 0, bottom = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets x = ins.getInsets(android.view.WindowInsets.Type.systemBars());
                top = x.top;
                bottom = x.bottom;
            } else {
                top = ins.getSystemWindowInsetTop();
                bottom = ins.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top + dp(20), 0, bottom + dp(30));
            return ins;
        });

        // Root column: a compact header at the top, a flexible centered band holding the digit
        // display or the password cluster, and the keypad card pinned to the bottom of the band.
        // Generous insets keep every control clear of the status and navigation bars; the column
        // scrolls as a whole when a very small screen cannot fit the whole page.
        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        addView(scroll, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout column = new LinearLayout(ctx);
        columnView = column;
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(column, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildHeader(column);

        // The centered band: PIN digits float at the true center of the page; in password mode the
        // input cluster takes the same spot, so it is compact and mid-screen instead of wasted space.
        FrameLayout mid = new FrameLayout(ctx);
        column.addView(mid, new LinearLayout.LayoutParams(-1, 0, 1f));

        dots = text("", 32, fg);
        dots.setGravity(Gravity.CENTER);
        dots.setLetterSpacing(.18f);
        mid.addView(dots, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        passwordEntry = new LinearLayout(ctx);
        passwordEntry.setOrientation(LinearLayout.VERTICAL);
        passwordEntry.setGravity(Gravity.CENTER_HORIZONTAL);
        passwordEntry.setPadding(dp(40), 0, dp(40), 0);
        buildPasswordHeader(passwordEntry);
        mid.addView(passwordEntry, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        passwordInput = new EditText(ctx);
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
        passwordInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        passwordInput.setTextColor(fg);
        passwordInput.setHintTextColor(muted);
        passwordInput.setTextSize(17);
        passwordInput.setBackground(rounded(panel, 16));
        passwordInput.setPadding(dp(20), dp(16), dp(20), dp(16));
        passwordInput.setSingleLine(true);
        passwordInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        passwordInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { submit(); return true; }
            return false;
        });
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(-1, -2);
        fieldLp.topMargin = dp(18);
        passwordEntry.addView(passwordInput, fieldLp);

        // The unlock button doubles as a busy indicator: while the code is checked, its label is
        // swapped for a small spinner so the page never appears frozen.
        FrameLayout unlock = new FrameLayout(ctx);
        unlock.setBackground(rounded(accent, 16));
        unlock.setMinimumHeight(dp(52));
        unlock.setOnClickListener(v -> submit());
        unlockLabel = text(getString(R.string.lock_action_unlock), 16, Color.WHITE);
        unlockLabel.setTypeface(null, Typeface.BOLD);
        unlockLabel.setGravity(Gravity.CENTER);
        unlockLabel.setPadding(dp(16), dp(14), dp(16), dp(14));
        unlock.addView(unlockLabel, new FrameLayout.LayoutParams(-1, -1));
        unlockSpin = spin(22);
        unlock.addView(unlockSpin, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        unlockSpin.setVisibility(GONE);
        LinearLayout.LayoutParams unlockLp = new LinearLayout.LayoutParams(-1, -2);
        unlockLp.topMargin = dp(14);
        passwordEntry.addView(unlock, unlockLp);

        // The keypad in its rounded card, pinned to the bottom just above the navigation area.
        padCard = new LinearLayout(ctx);
        padCard.setOrientation(LinearLayout.VERTICAL);
        padCard.setGravity(Gravity.CENTER_HORIZONTAL);
        padCard.setPadding(dp(20), dp(14), dp(20), dp(14));
        padCard.setBackground(rounded(panel, 28));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.setMargins(dp(20), dp(20), dp(20), 0);
        column.addView(padCard, cardLp);

        pinPad = new LinearLayout(ctx);
        pinPad.setOrientation(LinearLayout.VERTICAL);
        padCard.addView(pinPad, new LinearLayout.LayoutParams(-1, -2));
        buildPad();

        fpRow = text(getString(R.string.lock_fingerprint_action), 15, accent);
        fpRow.setGravity(Gravity.CENTER);
        fpRow.setCompoundDrawablePadding(dp(10));
        android.graphics.drawable.Drawable fpIcon = ctx.getDrawable(R.drawable.ic_fingerprint).mutate();
        fpIcon.setTintList(ColorStateList.valueOf(accent));
        fpRow.setCompoundDrawablesRelativeWithIntrinsicBounds(fpIcon, null, null, null);
        fpRow.setPadding(dp(16), dp(14), dp(16), dp(14));
        fpRow.setMinHeight(dp(52));
        fpRow.setBackground(rounded(hero, 18));
        fpRow.setOnClickListener(v -> startFingerprint());
        LinearLayout.LayoutParams fpLp = new LinearLayout.LayoutParams(-1, -2);
        fpLp.setMargins(dp(48), dp(18), dp(48), 0);
        columnView.addView(fpRow, fpLp);
        fpRow.setVisibility(GONE);

        cancel = text(getString(R.string.lock_cancel), 15, accent);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(24), dp(12), dp(24), dp(12));
        cancel.setMinHeight(dp(44));
        cancel.setOnClickListener(v -> {
            if (cancelListener != null) cancelListener.run();
        });
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(-2, -2);
        cancelLp.topMargin = dp(6);
        columnView.addView(cancel, cancelLp);
        cancel.setVisibility(GONE);
    }

    /** The badge, title, subtitle and error header, a small lock-branded brand at the top of the page. */
    private void buildHeader(LinearLayout root) {
        headerBadge = new ImageView(ctx);
        headerBadge.setImageResource(R.drawable.ic_lock);
        headerBadge.setColorFilter(accent);
        headerBadge.setBackground(oval(panel));
        headerBadge.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.addView(headerBadge, new LinearLayout.LayoutParams(dp(96), dp(96)));

        title = text(getString(R.string.lock_title), 22, fg);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-2, -2);
        titleLp.topMargin = dp(14);
        root.addView(title, titleLp);

        subtitle = text("", 14, muted);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.setMargins(dp(48), dp(6), dp(48), 0);
        root.addView(subtitle, subLp);

        error = text("", 13, negative);
        error.setGravity(Gravity.CENTER);
        error.setVisibility(GONE);
        LinearLayout.LayoutParams errLp = new LinearLayout.LayoutParams(-1, -2);
        errLp.setMargins(dp(40), dp(10), dp(40), 0);
        root.addView(error, errLp);
    }

    /** The compact badge/title/message header shown inside the password cluster, so the whole
     *  entrance — heading, field and button — reads as one tight unit centered on the page. */
    private void buildPasswordHeader(LinearLayout root) {
        pwHeader = new LinearLayout(ctx);
        pwHeader.setOrientation(LinearLayout.VERTICAL);
        pwHeader.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView badge = new ImageView(ctx);
        badge.setImageResource(R.drawable.ic_lock);
        badge.setColorFilter(accent);
        badge.setBackground(oval(panel));
        badge.setPadding(dp(20), dp(20), dp(20), dp(20));
        pwHeader.addView(badge, new LinearLayout.LayoutParams(dp(76), dp(76)));

        pwTitle = text(getString(R.string.lock_title), 20, fg);
        pwTitle.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-2, -2);
        titleLp.topMargin = dp(10);
        pwHeader.addView(pwTitle, titleLp);

        pwSubtitle = text("", 13, muted);
        pwSubtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.setMargins(dp(24), dp(4), dp(24), 0);
        pwHeader.addView(pwSubtitle, subLp);

        pwError = text("", 13, negative);
        pwError.setGravity(Gravity.CENTER);
        pwError.setVisibility(GONE);
        LinearLayout.LayoutParams errLp = new LinearLayout.LayoutParams(-1, -2);
        errLp.setMargins(dp(16), dp(8), dp(16), 0);
        pwHeader.addView(pwError, errLp);

        root.addView(pwHeader, new LinearLayout.LayoutParams(-1, -2));
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
        // A previous prompt may have left the entry disabled (or a fingerprint session pending);
        // always start every appearance from a clean, fully interactive state.
        busy = false;
        cancelFingerprint();
        setEntryEnabled(true);
        pinMode = LockManager.isPinMode(ctx);
        int titleRes = verifyMode ? R.string.lock_verify_title : R.string.lock_title;
        int subRes = verifyMode
            ? (pinMode ? R.string.lock_verify_subtitle_pin : R.string.lock_verify_subtitle_password)
            : (pinMode ? R.string.lock_subtitle_pin : R.string.lock_subtitle_password);
        title.setText(getString(titleRes));
        subtitle.setText(getString(subRes));
        pwTitle.setText(getString(titleRes));
        pwSubtitle.setText(getString(subRes));
        int pinVis = pinMode ? VISIBLE : GONE;
        headerBadge.setVisibility(pinVis);
        title.setVisibility(pinVis);
        subtitle.setVisibility(pinVis);
        error.setVisibility(pinVis);
        pwHeader.setVisibility(pinMode ? GONE : VISIBLE);
        passwordInput.setHint(getString(R.string.lock_password_entrance_hint));
        padCard.setVisibility(pinMode ? VISIBLE : GONE);
        dots.setVisibility(pinMode ? VISIBLE : GONE);
        passwordEntry.setVisibility(pinMode ? GONE : VISIBLE);
        if (!pinMode) {
            passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            passwordInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
        }
        cancel.setVisibility(verifyMode ? VISIBLE : GONE);
        clearInput();
        hideError();
        applyFingerprintRow();
    }

    private void reveal() {
        pinMode = LockManager.isPinMode(ctx);
        fpPromptAllowed = true;
        setVisibility(VISIBLE);
        if (!pinMode) {
            passwordInput.requestFocus();
            post(() -> {
                if (getVisibility() == VISIBLE && fpPromptAllowed) {
                    InputMethodManager ime = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (ime != null) ime.showSoftInput(passwordInput, InputMethodManager.SHOW_IMPLICIT);
                }
            });
        }
        applyFingerprintRow();
        // When a fingerprint is usable, unlock straight away: the system dialog wraps the keypad
        // underneath, and its negative-button ("Use PIN or password") plus the fingerprint row are
        // the fallback if the print cannot be read. Only when the owning activity stays in the
        // foreground — the overlay is also revealed as the app heads to the background, and no
        // prompt may be launched from a paused activity.
        postDelayed(() -> {
            if (getVisibility() == VISIBLE && !busy && fpPromptAllowed
                    && LockManager.fpStatus(ctx) == LockManager.FP_OK) {
                startFingerprint();
            }
        }, 350);
    }

    /** While false, {@link #reveal()} must not pop the IME or launch a fingerprint prompt. The
     *  activity switches this off as soon as it pauses (see {@code onPause}), after revealing the
     *  overlay so the outgoing frame shows the lock entrance rather than the data; a fresh reveal
     *  (e.g. on the way back in, while resumed) turns it back on. */
    void setAutoFingerprintEnabled(boolean enabled) {
        fpPromptAllowed = enabled;
    }

    private void applyFingerprintRow() {
        boolean usable = !busy && LockManager.fpStatus(ctx) == LockManager.FP_OK;
        fpRow.setVisibility(usable ? VISIBLE : GONE);
        fpRow.setText(getString(R.string.lock_fingerprint_action));
        // In password mode the fingerprint option lives inside the password cluster so it sits
        // close to the unlock button; in PIN mode it stays under the keypad.
        if (!pinMode && fpRow.getParent() != passwordEntry) {
            ((ViewGroup) fpRow.getParent()).removeView(fpRow);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(12);
            passwordEntry.addView(fpRow, lp);
        } else if (pinMode && fpRow.getParent() != columnView) {
            ((ViewGroup) fpRow.getParent()).removeView(fpRow);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(dp(48), dp(18), dp(48), 0);
            int idx = columnView.indexOfChild(padCard);
            if (idx >= 0) columnView.addView(fpRow, idx + 1, lp);
            else columnView.addView(fpRow, lp);
        }
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
        pwError.setText(getString(res));
        pwError.setVisibility(VISIBLE);
    }

    private void hideError() {
        error.setVisibility(GONE);
        pwError.setVisibility(GONE);
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

    /** Swaps the current confirm control's label for a small spinner while a code is verified, so
     *  the (deliberately slow) PBKDF2 check reads as progress instead of a frozen page. */
    private void setLoading(boolean loading) {
        if (pinMode) {
            padCheckLabel.setVisibility(loading ? GONE : VISIBLE);
            padCheckSpin.setVisibility(loading ? VISIBLE : GONE);
        } else {
            unlockLabel.setVisibility(loading ? GONE : VISIBLE);
            unlockSpin.setVisibility(loading ? VISIBLE : GONE);
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
        setLoading(true);
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
        setLoading(false);
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
        busy = true;
        setEntryEnabled(false);
        hideError();
        fpCancel = LockManager.startFingerprint(ctx, new LockManager.FpCallback() {
            @Override public void onFpSucceeded() {
                busy = false;
                success();
            }

            @Override public void onFpFailed(int errorRes) {
                busy = false;
                setEntryEnabled(true);
                if (errorRes != -1) showError(errorRes);
            }
        });
        if (fpCancel == null) {
            busy = false;
            setEntryEnabled(true);
        }
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
        // Numerals keep their familiar left-to-right reading order in every language, so the
        // keypad must never mirror itself under an RTL layout direction.
        pinPad.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        char[][] keys = {
            {'1', '2', '3'},
            {'4', '5', '6'},
            {'7', '8', '9'},
            {'\u232B', '0', '\u2713'},
        };
        for (char[] row : keys) {
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (char k : row) {
                final char key = k;
                View b = key == '\u2713' ? buildCheckKey() : buildTextKey(String.valueOf(k));
                b.setOnClickListener(v -> padTapped(key));
                line.addView(b, new LinearLayout.LayoutParams(0, dp(58), 1f));
            }
            pinPad.addView(line);
        }
    }

    private View buildTextKey(String label) {
        TextView k = new TextView(ctx);
        k.setText(label);
        k.setTextSize(26);
        k.setGravity(Gravity.CENTER);
        k.setTextColor(fg);
        k.setTypeface(null, Typeface.NORMAL);
        k.setBackground(rippleMask());
        return k;
    }

    /** The confirm key: an accent circle that also doubles as a busy indicator, trading its glyph
     *  for a spinner while the entered code is checked. */
    private View buildCheckKey() {
        FrameLayout f = new FrameLayout(ctx);
        f.setBackground(oval(accent));
        padCheckLabel = text("\u2713", 28, Color.WHITE);
        padCheckLabel.setGravity(Gravity.CENTER);
        f.addView(padCheckLabel, new FrameLayout.LayoutParams(-1, -1));
        padCheckSpin = spin(22);
        f.addView(padCheckSpin, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        padCheckSpin.setVisibility(GONE);
        return f;
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
            if (pinBuffer.length() < 6) pinBuffer.append(key);
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

    /** A small indeterminate spinner for the confirm controls, tinted for the surface it sits on. */
    private ProgressBar spin(int sizeDip) {
        ProgressBar p = new ProgressBar(ctx);
        p.setIndeterminateTintList(ColorStateList.valueOf(Color.WHITE));
        int s = dp(sizeDip);
        p.setLayoutParams(new ViewGroup.LayoutParams(s, s));
        return p;
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

    private GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    /** A soft rounded ripple for the keypad keys, which themselves stay background-free. */
    private RippleDrawable rippleMask() {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(dp(20));
        mask.setColor(Color.TRANSPARENT);
        int tint = (accent & 0x00FFFFFF) | 0x1A000000;
        return new RippleDrawable(ColorStateList.valueOf(tint), null, mask);
    }
}