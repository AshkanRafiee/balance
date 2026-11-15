package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Code results belong only to the still-current, foreground lock entrance. */
@RunWith(AndroidJUnit4.class)
public class LockOverlayTest {
    private Context ctx;
    private ActivityScenario<AboutActivity> scenario;
    private LockOverlay overlay;
    private final AtomicInteger unlocks = new AtomicInteger();

    @Before public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
        LockManager.resetSessionForTest();
        LockManager.enable(ctx, "1234", true, false);
        scenario = ActivityScenario.launch(AboutActivity.class);
        scenario.onActivity(activity -> {
            overlay = (LockOverlay) field(activity, "lockOverlay");
            overlay.setUnlockListener(unlocks::incrementAndGet);
            overlay.showLock();
            LockManager.lockSession();
        });
    }

    @After public void tearDown() {
        if (scenario != null) scenario.close();
        LockManager.disable(ctx);
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
        LockManager.resetSessionForTest();
    }

    @Test public void currentValidPinAndPasswordCompletion_unlockNormally() throws Exception {
        for (boolean pin : new boolean[]{true, false}) {
            String code = pin ? "1234" : "MyPassw0rd";
            LockManager.changeCode(ctx, code, pin);
            LockManager.lockSession();
            CountDownLatch unlocked = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                overlay.showLock();
                overlay.setUnlockListener(() -> {
                    unlocks.incrementAndGet();
                    unlocked.countDown();
                });
                if (pin) {
                    ((StringBuilder) field(overlay, "pinBuffer")).append(code);
                } else {
                    ((EditText) field(overlay, "passwordInput")).setText(code);
                }
                invoke(overlay, "submit", new Class<?>[0]);
            });
            assertTrue("The current valid code must complete", unlocked.await(15, TimeUnit.SECONDS));
            onMain(() -> {
                assertFalse(overlay.isShowing());
                assertFalse(LockManager.isSessionLocked());
            });
        }
        assertEquals("Each current result must notify exactly once", 2, unlocks.get());
    }

    @Test public void completionAfterPauseAndResume_cannotUnlockOrBlockRetry() {
        Runnable[] completion = new Runnable[1];
        scenario.onActivity(activity -> {
            // Verify mode starts in an unlocked session, so the activity's onPause does not call
            // showLock/setAutoFingerprintEnabled. The code check must observe the pause itself.
            LockManager.unlockSession();
            overlay.showVerify();
            completion[0] = delayedCompletion(overlay, true);
        });
        scenario.moveToState(Lifecycle.State.STARTED);
        onMain(() -> {
            LockManager.lockSession();
            completion[0].run();
            assertLocked();
            assertFalse("Pausing must release only the stale code UI", busy(overlay));
            ((StringBuilder) field(overlay, "pinBuffer")).append("1234");
            invoke(overlay, "submit", new Class<?>[0]);
            assertFalse("A queued input event must not start a paused code check", busy(overlay));
        });
        scenario.moveToState(Lifecycle.State.RESUMED);
        scenario.onActivity(activity -> {
            completion[0].run();
            assertLocked();
            delayedCompletion(overlay, true).run();
            assertFalse("A fresh attempt after resume must still work", overlay.isShowing());
            assertFalse(LockManager.isSessionLocked());
            assertEquals(1, unlocks.get());
        });
    }

    @Test public void completionAfterBackgroundGate_cannotUnlockWhenReenabled() {
        scenario.onActivity(activity -> {
            Runnable completion = delayedCompletion(overlay, true);
            overlay.setAutoFingerprintEnabled(false);
            completion.run();
            assertLocked();
            overlay.setAutoFingerprintEnabled(true);
            completion.run();
            assertLocked();
            assertFalse(busy(overlay));
        });
    }

    @Test public void validCodeAfterPauseWithoutStop_stillUnlocks() throws Exception {
        scenario.moveToState(Lifecycle.State.STARTED);
        scenario.moveToState(Lifecycle.State.RESUMED);
        CountDownLatch unlocked = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            assertFalse("The pause disabled only automatic fingerprint prompts",
                (Boolean) field(overlay, "fpPromptAllowed"));
            overlay.setUnlockListener(() -> {
                unlocks.incrementAndGet();
                unlocked.countDown();
            });
            ((StringBuilder) field(overlay, "pinBuffer")).append("1234");
            invoke(overlay, "submit", new Class<?>[0]);
        });
        assertTrue("Returning without onStop must not block code entry",
            unlocked.await(15, TimeUnit.SECONDS));
        onMain(() -> {
            assertFalse(overlay.isShowing());
            assertFalse(LockManager.isSessionLocked());
            assertEquals(1, unlocks.get());
        });
    }

    @Test public void completionAfterReconfigure_cannotUnlockNewVerifyEntrance() {
        scenario.onActivity(activity -> {
            Runnable completion = delayedCompletion(overlay, true);
            overlay.showVerify();
            completion.run();
            assertLocked();
            assertFalse(busy(overlay));
            assertEquals(View.GONE, ((View) field(overlay, "padCheckSpin")).getVisibility());
            assertTrue(((EditText) field(overlay, "passwordInput")).isEnabled());
        });
    }

    @Test public void staleFailure_cannotResetOrClearANewerCodeAttempt() {
        scenario.onActivity(activity -> {
            Runnable oldCompletion = delayedCompletion(overlay, false);
            overlay.showLock();
            ((StringBuilder) field(overlay, "pinBuffer")).append("1234");
            Runnable currentCompletion = delayedCompletion(overlay, true);
            oldCompletion.run();
            assertLocked();
            assertTrue("The current attempt must stay busy", busy(overlay));
            assertEquals("1234", field(overlay, "pinBuffer").toString());
            assertEquals(View.VISIBLE, ((View) field(overlay, "padCheckSpin")).getVisibility());
            assertFalse(((EditText) field(overlay, "passwordInput")).isEnabled());
            currentCompletion.run();
            currentCompletion.run();
            assertFalse(overlay.isShowing());
            assertFalse(LockManager.isSessionLocked());
            assertEquals("A completed attempt must be consumed once", 1, unlocks.get());
        });
    }

    @Test public void currentWrongCodeCompletion_preservesAttemptAccountingAndRetry() {
        Runnable[] completion = new Runnable[1];
        scenario.onActivity(activity -> {
            ((StringBuilder) field(overlay, "pinBuffer")).append("9999");
            completion[0] = delayedCompletion(overlay, false);
        });
        assertFalse(LockManager.verify(ctx, "9999"));
        scenario.onActivity(activity -> {
            completion[0].run();
            assertLocked();
            assertFalse(busy(overlay));
            assertEquals("", field(overlay, "pinBuffer").toString());
            assertEquals(LockManager.MAX_ATTEMPTS - 1, LockManager.attemptsRemaining(ctx));
            assertEquals(View.VISIBLE, ((View) field(overlay, "error")).getVisibility());
            assertEquals(View.GONE, ((View) field(overlay, "padCheckSpin")).getVisibility());
            assertTrue(((EditText) field(overlay, "passwordInput")).isEnabled());
            ((StringBuilder) field(overlay, "pinBuffer")).append("1234");
            completion[0] = delayedCompletion(overlay, true);
        });
        assertTrue(LockManager.verify(ctx, "1234"));
        scenario.onActivity(activity -> {
            completion[0].run();
            assertFalse(overlay.isShowing());
            assertFalse(LockManager.isSessionLocked());
            assertEquals(LockManager.MAX_ATTEMPTS, LockManager.attemptsRemaining(ctx));
            assertEquals(1, unlocks.get());
        });
    }

    @Test public void completionAfterHideAndReveal_cannotUnlockNewEntrance() {
        scenario.onActivity(activity -> {
            Runnable completion = delayedCompletion(overlay, true);
            overlay.hide();
            completion.run();
            assertTrue(LockManager.isSessionLocked());
            assertEquals(0, unlocks.get());
            overlay.showLock();
            completion.run();
            assertLocked();
            assertFalse(busy(overlay));
        });
    }

    @Test public void completionAfterDetachAndReattach_cannotUnlockOrBlockRetry() {
        scenario.onActivity(activity -> {
            Runnable completion = delayedCompletion(overlay, true);
            ViewGroup parent = (ViewGroup) overlay.getParent();
            ViewGroup.LayoutParams params = overlay.getLayoutParams();
            parent.removeView(overlay);
            assertFalse(overlay.isAttachedToWindow());
            completion.run();
            assertLocked();
            parent.addView(overlay, params);
            assertTrue(overlay.isAttachedToWindow());
            completion.run();
            assertLocked();
            assertFalse(busy(overlay));
            delayedCompletion(overlay, true).run();
            assertFalse(overlay.isShowing());
            assertFalse(LockManager.isSessionLocked());
            assertEquals(1, unlocks.get());
        });
    }

    @Test public void completionAfterActivityRecreation_cannotUnlockNewActivity() {
        Runnable[] completion = new Runnable[1];
        LockOverlay previous = overlay;
        scenario.onActivity(activity -> completion[0] = delayedCompletion(overlay, true));
        scenario.recreate();
        scenario.onActivity(activity -> {
            overlay = (LockOverlay) field(activity, "lockOverlay");
            assertNotSame(previous, overlay);
            completion[0].run();
            assertLocked();
        });
    }

    @Test public void staleSuccess_cannotDismissAReconfiguredCooldown() {
        Runnable[] completion = new Runnable[1];
        scenario.onActivity(activity -> completion[0] = delayedCompletion(overlay, true));
        for (int i = 0; i < LockManager.MAX_ATTEMPTS; i++) {
            assertFalse(LockManager.verify(ctx, "9999"));
        }
        scenario.onActivity(activity -> {
            overlay.showLock();
            completion[0].run();
            assertLocked();
            assertTrue(LockManager.cooldownRemainingMs(ctx) > 0);
            assertTrue("A stale completion must not release the cooldown", busy(overlay));
            assertFalse(((EditText) field(overlay, "passwordInput")).isEnabled());
            assertEquals(View.GONE, ((View) field(overlay, "padCheckSpin")).getVisibility());
        });
    }

    private void assertLocked() {
        assertTrue("The entrance must remain visible", overlay.isShowing());
        assertTrue("A stale result must not unlock the app-wide session", LockManager.isSessionLocked());
        assertEquals("A stale result must not notify the owner", 0, unlocks.get());
    }

    private static boolean busy(LockOverlay entrance) {
        return (Boolean) field(entrance, "busy");
    }

    /**
     * Use submit's actual start/finish boundary to deliver an already-verified worker result at an
     * exact lifecycle point. No PBKDF2 timing, sleeps, worker hooks or new dependencies are needed.
     */
    private static Runnable delayedCompletion(LockOverlay entrance, boolean ok) {
        int generation = (Integer) invoke(entrance, "beginCodeAttempt", new Class<?>[0]);
        return () -> invoke(entrance, "finishAttempt", new Class<?>[]{int.class, boolean.class},
            generation, ok);
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Object invoke(LockOverlay target, String name, Class<?>[] types, Object... args) {
        try {
            Method method = LockOverlay.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
}
