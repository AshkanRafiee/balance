package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import android.view.MotionEvent;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Tests for the first-run introduction: it gates a fresh install, walks Welcome → Privacy → SMS
 *  access, lets the user skip, marks itself seen so it never returns, and adapts its last button to
 *  the SMS permission state. */
@RunWith(AndroidJUnit4.class)
public class OnboardingTest {

    private Context ctx;

    @Before public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
        LocaleHelper.setLanguage(ctx, "");
    }

    @After public void tearDown() throws Exception {
        exec("input keyevent 4");
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
        LocaleHelper.setLanguage(ctx, "");
        finishAll();
    }

    @Test public void firstLaunch_opensOnboardingFromMain() throws Exception {
        launch(MainActivity.class);
        assertTrue("the introduction must be foreground on a fresh install",
            waitUntil(() -> isResumed(OnboardingActivity.class), 20_000));
        assertOnScreen(OnboardingActivity.class, ctx.getString(R.string.onboarding_welcome_title));
    }

    @Test public void seenFlag_skipsOnboardingFromMain() throws Exception {
        BalanceData.setOnboardingSeen(ctx, true);
        grantReadSms();
        launch(MainActivity.class);
        sleep(2_000);
        assertFalse("a returning user must not see the introduction",
            isResumed(OnboardingActivity.class));
        assertTrue("the dashboard must be foreground for a returning user",
            isResumed(MainActivity.class));
    }

    @Test public void skip_marksSeenAndFinishes() throws Exception {
        launch(OnboardingActivity.class);
        assertOnScreen(OnboardingActivity.class, ctx.getString(R.string.onboarding_welcome_title));
        clickText(ctx.getString(R.string.onboarding_skip));
        assertTrue("skipping counts as having seen the introduction",
            waitUntil(() -> BalanceData.isOnboardingSeen(ctx), 5_000));
        assertTrue("skipping must finish the introduction", waitUntil(
            () -> finished(OnboardingActivity.class), 5_000));
    }

    @Test public void nextWalksAllPages_thenSkipFinishes() throws Exception {
        launch(OnboardingActivity.class);
        assertOnScreen(OnboardingActivity.class, ctx.getString(R.string.onboarding_welcome_title));

        clickText(ctx.getString(R.string.onboarding_next));
        assertOnScreen(OnboardingActivity.class, ctx.getString(R.string.onboarding_privacy_title));

        clickText(ctx.getString(R.string.onboarding_next));
        assertOnScreen(OnboardingActivity.class, ctx.getString(R.string.onboarding_sms_title));
        boolean allow = ctx.getString(R.string.onboarding_allow_sms).equals(primaryLabel());
        boolean cont = ctx.getString(R.string.onboarding_continue).equals(primaryLabel());
        assertTrue("the SMS page must offer to request access or continue (permission state "
                + (allow ? "ungranted" : cont ? "granted" : "unknown") + ")", allow || cont);
        assertOnScreen(OnboardingActivity.class, ctx.getString(R.string.onboarding_skip));

        clickText(ctx.getString(R.string.onboarding_skip));
        assertTrue(waitUntil(() -> BalanceData.isOnboardingSeen(ctx), 5_000));
        assertTrue(waitUntil(() -> finished(OnboardingActivity.class), 5_000));
    }

    @Test public void swipeLeft_advancesThroughPages() throws Exception {
        launch(OnboardingActivity.class);
        assertOnScreen(OnboardingActivity.class, ctx.getString(R.string.onboarding_welcome_title));

        swipeLeft();
        assertOnScreen(OnboardingActivity.class, ctx.getString(R.string.onboarding_privacy_title));

        swipeLeft();
        assertOnScreen(OnboardingActivity.class, ctx.getString(R.string.onboarding_sms_title));
    }

    @Test public void swipeRight_goesBackAPage() throws Exception {
        launch(OnboardingActivity.class);
        clickText(ctx.getString(R.string.onboarding_next));
        assertOnScreen(OnboardingActivity.class, ctx.getString(R.string.onboarding_privacy_title));

        swipeRight();
        assertOnScreen(OnboardingActivity.class, ctx.getString(R.string.onboarding_welcome_title));
    }

    @Test public void permissionGranted_showsContinueButton() throws Exception {
        grantReadSms();
        launch(OnboardingActivity.class);
        clickText(ctx.getString(R.string.onboarding_next));
        clickText(ctx.getString(R.string.onboarding_next));
        assertOnScreen(OnboardingActivity.class, ctx.getString(R.string.onboarding_sms_granted));
        clickText(ctx.getString(R.string.onboarding_continue));
        assertTrue(waitUntil(() -> BalanceData.isOnboardingSeen(ctx), 5_000));
        assertTrue(waitUntil(() -> finished(OnboardingActivity.class), 5_000));
    }

    @Test public void rtlParagraphFlowsRightToLeft() throws Exception {
        LocaleHelper.setLanguage(ctx, "fa");
        launch(OnboardingActivity.class);
        assertOnScreenLiteral(OnboardingActivity.class, R.string.onboarding_welcome_body);
        AtomicBoolean rtl = new AtomicBoolean();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (!(a instanceof OnboardingActivity)) continue;
                TextView tv = (TextView) findText(a.getWindow().getDecorView(),
                        a.getString(R.string.onboarding_welcome_body));
                if (tv != null && tv.getLayout() != null) {
                    rtl.set(tv.getLayout().getParagraphDirection(0)
                            == android.text.Layout.DIR_RIGHT_TO_LEFT);
                    return;
                }
            }
        });
        assertTrue("a Persian paragraph must flow right-to-left even when it starts with an "
                + "English word", rtl.get());
    }

    @Test public void rtlSwipeForwardIsToTheRight() throws Exception {
        LocaleHelper.setLanguage(ctx, "fa");
        launch(OnboardingActivity.class);
        assertOnScreenLiteral(OnboardingActivity.class, R.string.onboarding_welcome_title);

        swipeRight();
        assertOnScreenLiteral(OnboardingActivity.class, R.string.onboarding_privacy_title);

        swipeLeft();
        assertOnScreenLiteral(OnboardingActivity.class, R.string.onboarding_welcome_title);
    }

    @Test public void aboutScreen_offersReopenLink() throws Exception {
        launch(AboutActivity.class);
        assertOnScreen(AboutActivity.class, ctx.getString(R.string.about_show_intro_value));
    }

    // ---- helpers ----

    private void launch(Class<?> type) throws Exception {
        Intent i = new Intent(ctx, type)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        InstrumentationRegistry.getInstrumentation().startActivitySync(i);
    }

    private void grantReadSms() throws Exception {
        exec("pm grant " + ctx.getPackageName() + " android.permission.READ_SMS");
    }

    private void swipeLeft() throws Exception {
        swipe(0.8f, 0.2f);
    }

    private void swipeRight() throws Exception {
        swipe(0.2f, 0.8f);
    }

    /** Injects a horizontal drag across the screen's vertical centre via the instrumentation. */
    private void swipe(float fromFrac, float toFrac) {
        android.app.Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        android.content.res.Resources res = inst.getTargetContext().getResources();
        int from = (int) (res.getDisplayMetrics().widthPixels * fromFrac);
        int to = (int) (res.getDisplayMetrics().widthPixels * toFrac);
        int y = res.getDisplayMetrics().heightPixels / 2;
        long t = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, from, y, 0);
        inst.sendPointerSync(down);
        down.recycle();
        final int steps = 12;
        for (int i = 1; i <= steps; i++) {
            t += 16;
            MotionEvent m = MotionEvent.obtain(t, t, MotionEvent.ACTION_MOVE,
                from + (to - from) * i / steps, y, 0);
            inst.sendPointerSync(m);
            m.recycle();
        }
        t += 16;
        MotionEvent up = MotionEvent.obtain(t, t, MotionEvent.ACTION_UP, to, y, 0);
        inst.sendPointerSync(up);
        up.recycle();
    }

    private void exec(String cmd) throws Exception {
        android.os.ParcelFileDescriptor fd = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation().executeShellCommand(cmd);
        try (java.io.FileInputStream in = new java.io.FileInputStream(fd.getFileDescriptor())) {
            byte[] buf = new byte[4096];
            while (in.read(buf) > 0) { /* drain */ }
        }
        sleep(200);
    }

    private boolean isResumed(Class<?> type) {
        AtomicBoolean found = new AtomicBoolean();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED))
                if (type.isInstance(a)) { found.set(true); return; }
        });
        return found.get();
    }

    private boolean finished(Class<?> type) {
        AtomicBoolean found = new AtomicBoolean();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.DESTROYED))
                if (type.isInstance(a)) { found.set(true); return; }
        });
        return found.get();
    }

    private boolean waitUntil(java.util.function.BooleanSupplier probe, long deadlineMs) throws Exception {
        long deadline = System.currentTimeMillis() + deadlineMs;
        while (System.currentTimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            if (probe.getAsBoolean()) return true;
            sleep(100);
        }
        return probe.getAsBoolean();
    }

    private void assertOnScreen(Class<?> activityType, String text) throws Exception {
        assertTrue("expected '" + text + "' to be on screen (activity "
                + activityType.getSimpleName() + ")", waitUntil(() -> {
            if (!isResumed(activityType)) return false;
            AtomicBoolean found = new AtomicBoolean();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED)) {
                    if (activityType.isInstance(a) && hasText(a.getWindow().getDecorView(), text)) {
                        found.set(true);
                        break;
                    }
                }
            });
            return found.get();
        }, 15_000));
    }

    /** Resolves a string through the live activity (whose wrapped context honours the app's
     *  language override) and asserts it is on screen. */
    private void assertOnScreenLiteral(Class<?> activityType, int res) throws Exception {
        AtomicReference<String> resolved = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED))
                if (activityType.isInstance(a)) { resolved.set(a.getString(res)); return; }
        });
        String text = resolved.get();
        assertNotNull("activity " + activityType.getSimpleName() + " not resumed for string lookup", text);
        assertOnScreen(activityType, text);
    }

    private void clickText(String text) throws Exception {
        AtomicBoolean done = new AtomicBoolean();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                View v = findText(a.getWindow().getDecorView(), text);
                if (v != null) {
                    v.performClick();
                    done.set(true);
                    return;
                }
            }
            throw new IllegalStateException("clickable text not found: " + text);
        });
        assertTrue("clickable text not found: " + text, done.get());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private boolean hasText(View root, String text) {
        return findText(root, text) != null;
    }

    /** The primary button's current label on the final page — either the request or the continue
     *  label, whichever the permission state picked. */
    private String primaryLabel() {
        java.util.concurrent.atomic.AtomicReference<String> label = new java.util.concurrent.atomic.AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (!(a instanceof OnboardingActivity)) continue;
                for (String s : new String[]{ctx.getString(R.string.onboarding_allow_sms),
                        ctx.getString(R.string.onboarding_continue)}) {
                    View v = findText(a.getWindow().getDecorView(), s);
                    if (v != null) { label.set(s); return; }
                }
            }
        });
        return label.get() == null ? "" : label.get();
    }

    private View findText(View root, String text) {
        if (root instanceof TextView) {
            TextView tv = (TextView) root;
            if (tv.getVisibility() == View.VISIBLE && text.equals(tv.getText().toString())) return root;
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = findText(g.getChildAt(i), text);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private void finishAll() throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Stage s : new Stage[]{
                    Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.CREATED, Stage.STARTED}) {
                for (Activity a : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(s)) {
                    try {
                        a.finish();
                    } catch (Exception ignored) { }
                }
            }
        });
    }

    private static void sleep(long ms) throws Exception {
        Thread.sleep(ms);
    }
}