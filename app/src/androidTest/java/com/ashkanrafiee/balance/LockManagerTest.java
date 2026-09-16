package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Covers the in-app lock: PIN/password hashing, fingerprint binding state, and the app-wide
 *  session locking that re-engages whenever the last screen leaves the foreground. */
@RunWith(AndroidJUnit4.class)
public class LockManagerTest {

    private Context ctx;

    @Before public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
        LockManager.resetSessionForTest();
    }

    @After public void tearDown() {
        LockManager.disable(ctx);
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
        LockManager.resetSessionForTest();
    }

    @Test public void enableThenVerify_acceptsOnlyTheRightCode() {
        LockManager.enable(ctx, "1234", true, false);
        assertTrue(LockManager.isEnabled(ctx));
        assertTrue(LockManager.isPinMode(ctx));
        assertTrue(LockManager.verify(ctx, "1234"));
        assertFalse(LockManager.verify(ctx, "0000"));
    }

    @Test public void changeCode_invalidatesThePreviousCode() {
        LockManager.enable(ctx, "1234", true, false);
        LockManager.changeCode(ctx, "5678", true);
        assertTrue(LockManager.isPinMode(ctx));
        assertTrue(LockManager.verify(ctx, "5678"));
        assertFalse(LockManager.verify(ctx, "1234"));
    }

    @Test public void changeToPassword_togglesToPasswordMode() {
        LockManager.enable(ctx, "1234", true, false);
        LockManager.changeCode(ctx, "MyPassw0rd", false);
        assertFalse(LockManager.isPinMode(ctx));
        assertTrue(LockManager.verify(ctx, "MyPassw0rd"));
        assertFalse(LockManager.verify(ctx, "1234"));
    }

    /** A lock written by an older build at a different work factor must keep verifying at its own
     *  factor first, then be re-derived to the current factor in the background. */
    @Test public void legacyFactorLock_rehashesToTheCurrentFactorAfterVerify() {
        LockManager.enable(ctx, "1234", true, false);
        byte[] salt = new byte[16];
        byte[] legacy = pbkdf2("1234", salt, 30_000);
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit()
            .putString(LockManager.KEY_LOCK_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putInt(LockManager.KEY_LOCK_ITERATIONS, 30_000)
            .putString(LockManager.KEY_LOCK_HASH, Base64.encodeToString(legacy, Base64.NO_WRAP))
            .commit();
        assertTrue("Legacy factor must still verify", LockManager.verify(ctx, "1234"));
        assertFalse(LockManager.verify(ctx, "0000"));

        android.content.SharedPreferences p =
            ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE);
        long deadline = SystemClock.uptimeMillis() + 5000;
        int iterations;
        while ((iterations = p.getInt(LockManager.KEY_LOCK_ITERATIONS, 0))
                != LockManager.LOCK_ITERATIONS && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50);
        }
        assertEquals("The lock must be re-derived to the current factor", LockManager.LOCK_ITERATIONS, iterations);
        assertTrue(LockManager.verify(ctx, "1234"));
    }

    @Test public void disable_forgetsEverything() {
        LockManager.enable(ctx, "1234", true, false);
        LockManager.disable(ctx);
        assertFalse(LockManager.isEnabled(ctx));
        assertTrue("Disabled lock must never block", LockManager.verify(ctx, "whatever"));
        assertTrue(LockManager.verify(ctx, ""));
    }

    @Test public void verify_whenDisabled_alwaysSucceeds() {
        assertTrue(LockManager.verify(ctx, "anything"));
        assertTrue(LockManager.verify(ctx, null));
    }

    @Test public void validCode_enforcesLengthsAndDigits() {
        assertTrue(LockManager.validCode("1234", true));
        assertTrue(LockManager.validCode("123456", true));
        assertFalse(LockManager.validCode("123", true));
        assertFalse(LockManager.validCode("1234567", true));
        assertFalse("PINs are digits only", LockManager.validCode("12a4", true));
        assertFalse(LockManager.validCode("", true));
        assertTrue(LockManager.validCode("abcdef", false));
        assertTrue(LockManager.validCode("a1b2c3", false));
        assertFalse("Passwords need at least 6 chars", LockManager.validCode("abcde", false));
        assertFalse(LockManager.validCode(null, false));
    }

    @Test public void session_engagesWhenNoScreenIsForeground() {
        LockManager.enable(ctx, "1234", true, false);
        assertFalse("Setting the lock in a live session must not lock it", LockManager.isSessionLocked());
        LockManager.unlockSession();

        // First screen arriving after the app was backgrounded locks the session.
        LockManager.registerActivityStart(ctx);
        assertTrue("Reopening the app must lock", LockManager.isSessionLocked());

        // Navigating to a second screen (still foreground) must not re-lock.
        LockManager.unlockSession();
        LockManager.registerActivityStart(ctx);
        assertFalse("Second screen must not re-lock", LockManager.isSessionLocked());

        // Leaving one screen, with another still up, keeps the session open.
        assertFalse("One screen still foreground: stay open",
            LockManager.registerActivityStop());

        // The last screen leaving the foreground locks, ready for the next entry.
        assertTrue("Last screen gone: the stop reports the end of the session",
            LockManager.registerActivityStop());
        assertTrue("No screen left: session locks", LockManager.isSessionLocked());
    }

    /** When the app hands over to a system activity (the backup/restore file picker) the session
     *  must stay open; the moment the user returns the hold is consumed, so a genuine background
     *  after that locks again immediately rather than riding out the grace window. */
    @Test public void holdUnlock_keepsTheSessionOpenAcrossASystemPicker() {
        LockManager.enable(ctx, "1234", true, false);
        LockManager.unlockSession();
        LockManager.registerActivityStart(ctx);
        LockManager.unlockSession();

        // Launching the picker: the only screen stops but the session must not lock.
        LockManager.holdUnlock();
        assertFalse("Picker takeover must not lock the session",
            LockManager.registerActivityStop());
        assertFalse(LockManager.isSessionLocked());

        // Returning within the grace window stays open.
        LockManager.registerActivityStart(ctx);
        assertFalse("Returning from the picker must not lock within the grace window",
            LockManager.isSessionLocked());

        // The hold was consumed on return, so backgrounding right away locks again.
        assertTrue("A stop right after returning must end the session",
            LockManager.registerActivityStop());
        assertTrue("Session must lock as soon as the hold is consumed",
            LockManager.isSessionLocked());

        // Reopening asks for the code: the old behavior still holds on a real background.
        LockManager.unlockSession();
        LockManager.expireHoldForTest();
        LockManager.registerActivityStart(ctx);
        assertTrue("Reopening after a real background must lock", LockManager.isSessionLocked());
    }

    @Test public void fingerprint_state_isUnavailableWithoutBinding() {
        assertFalse(LockManager.isFingerprintEnabled(ctx));
        assertTrue(LockManager.fpStatus(ctx) == LockManager.FP_UNAVAILABLE);
    }

    @Test public void fingerprintBinding_togglesCleanly() {
        // Some devices (software-keymaster emulators without a fingerprint template) cannot
        // authorise a keystore key at all; the app must degrade gracefully either way.
        boolean canBind = LockManager.setFingerprintEnabled(ctx, true);
        assertTrue("Binding state must mirror the outcome", LockManager.isFingerprintEnabled(ctx) == canBind);
        assertTrue(LockManager.setFingerprintEnabled(ctx, false));
        assertFalse(LockManager.isFingerprintEnabled(ctx));
        assertTrue(LockManager.fpStatus(ctx) == LockManager.FP_UNAVAILABLE);
    }

    @Test public void disabling_removesTheFingerprintBinding() {
        LockManager.enable(ctx, "1234", true, true);
        LockManager.disable(ctx);
        assertFalse("Fingerprint material must be cleared with the lock", LockManager.isFingerprintEnabled(ctx));
        assertTrue(LockManager.fpStatus(ctx) == LockManager.FP_UNAVAILABLE);
    }

    /** While the lock is enabled the widget trades the balances for the lock glyph and message. */
    @Test public void widget_showsLockInsteadOfBalancesWhileEnabled() {
        Context c = LocaleHelper.wrap(ctx);
        LockManager.enable(ctx, "1234", true, false);
        RemoteViews views = BalanceWidgetProvider.buildViews(ctx);
        LinearLayout root = (LinearLayout) views.apply(ctx, new FrameLayout(ctx));
        List<String> texts = new ArrayList<>();
        collectText(root, texts);
        boolean hasIcon = hasImage(root);
        assertTrue(texts.contains(c.getString(R.string.widget_locked_message)));
        assertTrue("Locked widget must draw the lock glyph", hasIcon);

        LockManager.disable(ctx);
        views = BalanceWidgetProvider.buildViews(ctx);
        LinearLayout normal = (LinearLayout) views.apply(ctx, new FrameLayout(ctx));
        List<String> normalTexts = new ArrayList<>();
        collectText(normal, normalTexts);
        assertFalse("Unlocked widget hides the locked message",
            normalTexts.contains(c.getString(R.string.widget_locked_message)));
    }

    private static void collectText(LinearLayout root, List<String> out) {
        for (int i = 0; i < root.getChildCount(); i++) {
            android.view.View v = root.getChildAt(i);
            if (v instanceof TextView) out.add(((TextView) v).getText().toString());
            else if (v instanceof LinearLayout) collectText((LinearLayout) v, out);
        }
    }

    private static boolean hasImage(LinearLayout root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            android.view.View v = root.getChildAt(i);
            if (v instanceof ImageView) return true;
            if (v instanceof LinearLayout && hasImage((LinearLayout) v)) return true;
        }
        return false;
    }

    /** PBKDF2-SHA256 on the caller's behalf, to fabricate a lock hash at an arbitrary factor. */
    private static byte[] pbkdf2(String code, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(code.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}