package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Telephony;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pull-to-refresh on the history screen (instrumented, real UI gesture). A bank movement is seeded
 * into the real SMS inbox while the screen is already open; the screen's automatic SMS observer
 * only ever re-scans the <em>history</em>, never the balances, so a seeded bank can only surface in
 * {@link BalanceData#read} once the pull's own refresh action (scanSms + scanHistory, the same as
 * the dashboard) runs. The gesture is driven with real touch events, so the intercept and the
 * on-release arming are covered end to end.
 */
@RunWith(AndroidJUnit4.class)
public class HistoryPullRefreshTest {

    private static final String TEJARAT_DEPOSIT =
        "*\u0628\u0627\u0646\u06A9 \u062A\u062C\u0627\u0631\u062A* \n"
        + "\u062D\u0633\u0627\u0628: 01351234567890 \n"
        + "\u0648\u0627\u0631\u06CC\u0632: 115,000,000 \u0631\u06CC\u0627\u0644 \n"
        + "\u0627\u0632 \u0637\u0631\u06CC\u0642: \u0633\u0627\u0645\u0627\u0646\u0647 \u067E\u0644 (\u067E\u0631\u062F\u0627\u062E\u062A \u0644\u062D\u0638\u0647 \u0627\u06CC)  \n"
        + "\u0645\u0627\u0646\u062F\u0647: 361,919,288 \u0631\u06CC\u0627\u0644 \n"
        + "1405/06/06\n00:08";

    private Context ctx;

    @Before public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(android.Manifest.permission.READ_SMS);
        exec("pm grant " + ctx.getPackageName() + " android.permission.READ_SMS");
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
        clearInbox();
    }

    @After public void tearDown() throws Exception {
        clearInbox();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Stage s : new Stage[]{Stage.RESUMED, Stage.PAUSED, Stage.STOPPED,
                    Stage.CREATED, Stage.STARTED}) {
                for (Activity a : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(s)) {
                    try { a.finish(); } catch (Exception ignored) { }
                }
            }
        });
    }

    @Test public void pullDownOverEmptyArea_refreshesTheWholeApp() throws Exception {
        // The pull starts over the hero card, which has no touch target: the ScrollView receives the
        // moves directly and arms the pull in onTouchEvent.
        launchAndSeed();
        final View list = scrollViewOf();
        pullDownAt(list.getHeight() * 0.10f, false);
        awaitTejaratBalance();
    }

    @Test public void pullDownOverYearCard_refreshesTheWholeApp() throws Exception {
        // The pull starts on a tap-able year card, so the gesture must be intercepted away from the
        // child (onInterceptTouchEvent) without firing the card's tap.
        launchAndSeed();
        waitUntil(() -> clickableCardCenter()[2] == 1, 20_000);
        pullDownAt(clickableCardCenter()[1], true);
        awaitTejaratBalance();
    }

    private void launchAndSeed() throws Exception {
        launch(HistoryActivity.class);
        waitUntil(() -> isResumed(HistoryActivity.class), 20_000);
        waitUntil(() -> scrollViewOf() != null, 20_000);

        // The movement lands only after the screen is open.
        seed("TejaratBank", TEJARAT_DEPOSIT, System.currentTimeMillis());
        // Let the automatic SMS observer finish its silent history re-scan; it never touches the
        // balances, so the bank can only surface once the pull's own refresh runs.
        sleep(1_500);
        assertTrue("no scan may run before the pull (the observer only scans history)",
            readBalances().isEmpty());
    }

    private void awaitTejaratBalance() throws Exception {
        waitUntil(() -> readBalances().size() == 1, 45_000);
        Bank tejarat = findBank("Tejarat", "01351234567890");
        assertNotNull("pull must scan the SMS inbox and surface the seeded bank", tejarat);
        assertEquals(361_919_288L, tejarat.amount);
    }

    /** Pulls the finger down across the history list from the given local y, releasing well past the
     *  arm threshold. When {@code originMustSurvive} is set, the pull cannot be re-anchored to fit
     *  inside the list — a too-small layout fails loudly instead of silently testing the wrong path. */
    private void pullDownAt(float yStart, boolean originMustSurvive) throws Exception {
        final View list = scrollViewOf();
        assertNotNull("history scroll view", list);
        final float d = list.getResources().getDisplayMetrics().density;
        final float pullPx = 130f * d;                       // well past the 55dp threshold
        float start = yStart;
        float end = start + pullPx;
        if (end > list.getHeight() - 1f) {
            if (originMustSurvive) {
                throw new IllegalStateException("pull origin at " + yStart + " cannot hold a 130dp "
                    + "pull inside a " + list.getHeight() + "px list");
            }
            start = Math.max(1f, list.getHeight() - 1f - pullPx);
            end = list.getHeight() - 1f;
        }
        final float x = list.getWidth() / 2f;
        long t = SystemClock.uptimeMillis();
        dispatch(list, MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, start, 0));
        final int steps = 14;
        for (int i = 1; i <= steps; i++) {
            t += 16;
            dispatch(list, MotionEvent.obtain(t, t, MotionEvent.ACTION_MOVE,
                x, start + (end - start) * i / steps, 0));
        }
        t += 16;
        dispatch(list, MotionEvent.obtain(t, t, MotionEvent.ACTION_UP, x, end, 0));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    /** The cell-relative Y of the list's first tap-able row (a year or month card), and whether one
     *  was found: {x, yInList, found}. Coordinate outside the list bounds fails loudly so a broken
     *  layout never silently tests the wrong path. */
    private int[] clickableCardCenter() {
        final int[] out = {0, 0, 0};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View list = null;
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (!(a instanceof HistoryActivity)) continue;
                list = findFirst(a.getWindow().getDecorView(), ScrollView.class);
                if (list != null) break;
            }
            if (list == null || list.getHeight() <= 1) return;
            View card = findClickable(list);
            if (card == null) return;
            int[] lc = new int[2], cc = new int[2];
            list.getLocationOnScreen(lc);
            card.getLocationOnScreen(cc);
            int y = cc[1] + card.getHeight() / 2 - lc[1];
            if (y < 1 || y > list.getHeight() - 1) {
                throw new IllegalStateException("tap-able card sits outside the list (" + y + " of "
                    + list.getHeight() + "): pull would test the wrong arming path");
            }
            out[1] = y;
            out[2] = 1;
        });
        return out;
    }

    private void dispatch(final View v, final MotionEvent e) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            v.dispatchTouchEvent(e);
            e.recycle();
        });
    }

    /** The history's single scrollable list, found when the resumed screen has laid it out. */
    private View scrollViewOf() {
        final AtomicReference<View> hit = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (!(a instanceof HistoryActivity)) continue;
                View root = a.getWindow().getDecorView();
                View found = findFirst(root, ScrollView.class);
                if (found != null) { hit.set(found); return; }
            }
        });
        return hit.get();
    }

    private static View findFirst(View v, Class<?> type) {
        if (type.isInstance(v)) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = findFirst(g.getChildAt(i), type);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /** The first tap-able row under the history list (a year or month card head). */
    private static View findClickable(View v) {
        if (v.isClickable()) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = findClickable(g.getChildAt(i));
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private LinkedHashMap<String, Bank> readBalances() {
        return BalanceData.read(ctx);
    }

    private Bank findBank(String name, String account) {
        for (Bank b : readBalances().values()) {
            if (name.equals(b.name)
                    && (account == null ? b.account == null : account.equals(b.account))) {
                return b;
            }
        }
        return null;
    }

    private void launch(Class<?> type) throws Exception {
        Intent i = new Intent(ctx, type)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        InstrumentationRegistry.getInstrumentation().startActivitySync(i);
    }

    private boolean isResumed(Class<?> type) {
        AtomicReference<Boolean> found = new AtomicReference<>(false);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED))
                if (type.isInstance(a)) { found.set(true); return; }
        });
        return found.get();
    }

    private boolean waitUntil(java.util.function.Supplier<Boolean> probe, long deadlineMs) throws Exception {
        long deadline = System.currentTimeMillis() + deadlineMs;
        while (System.currentTimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            if (Boolean.TRUE.equals(probe.get())) return true;
            sleep(150);
        }
        return Boolean.TRUE.equals(probe.get());
    }

    private void seed(String sender, String body, long base) throws Exception {
        String b64 = android.util.Base64.encodeToString(
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        exec("am broadcast -n com.ashkanrafiee.smsinject/.SeedReceiver -a com.ashkanrafiee.smsinject.SEED"
                + " -e sender " + sender + " -e body64 " + b64 + " -e base " + base);
        awaitSms(sender, body);
    }

    private void awaitSms(String sender, String body) throws Exception {
        long deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline) {
            try (android.database.Cursor c = ctx.getContentResolver().query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY},
                    null, null, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        if (sender.equals(c.getString(0)) && body.equals(c.getString(1))) return;
                    }
                }
            }
            sleep(150);
        }
        fail("seeded SMS did not arrive in time: sender=" + sender);
    }

    private void clearInbox() throws Exception {
        exec("am broadcast -n com.ashkanrafiee.smsinject/.SeedReceiver -a com.ashkanrafiee.smsinject.CLEAR");
        long deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline) {
            try (android.database.Cursor c = ctx.getContentResolver().query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{Telephony.Sms._ID}, null, null, null)) {
                if (c == null || !c.moveToFirst()) return;
            }
            sleep(150);
        }
        fail("SMS inbox did not clear in time");
    }

    private void exec(String cmd) throws Exception {
        android.os.ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(cmd);
        try (InputStream is = new android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            byte[] buf = new byte[2048];
            while (is.read(buf) >= 0) { }
        }
        sleep(200);
    }

    private static void sleep(long ms) throws Exception {
        Thread.sleep(ms);
    }
}