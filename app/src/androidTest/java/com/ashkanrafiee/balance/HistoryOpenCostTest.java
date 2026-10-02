package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * What opening the history screen costs, as a bound rather than as a number to look at.
 *
 * <p>The breakdown is drawn into nested {@code LinearLayout}s inside a {@code ScrollView}: no view
 * recycling and no culling, so every {@code addView} re-measures the whole tree. The size of what
 * opens is therefore the cost of opening it, which is what these tests measure.
 *
 * <p>These are guards against the two ways this screen got slow before: building rows in batches
 * while scrolling, and building a whole year the moment one level was opened. Both showed up as an
 * open that took seconds and as a view tree that kept growing while the user merely scrolled.
 */
@RunWith(AndroidJUnit4.class)
public class HistoryOpenCostTest {

    private Context ctx;

    private static final String BANK = "Mellat";
    private static final String ACCOUNT = "111";
    private static final long DAY = 86400000L;

    /** A generous ceiling for a busy year on a loaded emulator, chosen to sit far below the tens of
     *  seconds a batched or cascading open took, and far above what a single pass costs. */
    private static final long OPEN_BUDGET_MS = 20_000L;

    @Before public void setUp() {
        finishAnyResumedHistory();
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BalanceData.setExpandAllHistory(ctx, false);
    }

    @After public void tearDown() {
        finishAnyResumedHistory();
        BalanceData.setExpandAllHistory(ctx, false);
        BalanceData.reset(ctx, true);
    }

    /** Movements from the first of this month until today, so they all land in the one month that
     *  opens by itself. {@code perDay} well past what a single batch used to allow. */
    private void seedCurrentMonth(int perDay) {
        Calendar today = Calendar.getInstance();
        Calendar c = (Calendar) today.clone();
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 12);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        List<Transaction> txs = new ArrayList<>();
        int n = 0;
        for (long day = c.getTimeInMillis(); day <= today.getTimeInMillis(); day += DAY) {
            for (int k = 0; k < perDay; k++) {
                txs.add(new Transaction(BANK, ACCOUNT, day + k * 60_000L,
                    (k % 2 == 0 ? 1 : -1) * (100_000L + n * 37L), "sig" + (n++), null));
            }
        }
        BalanceData.writeTransactions(ctx, txs);
        seeded = txs.size();
        expected = txs.size();
    }

    /** How many movements the seeder wrote. */
    private int seeded;

    /** How many movement rows a plain open has to build, or -1 when it only has to settle: an open
     *  builds the month it opens, so a seeded year yields an unknown number of rows. */
    private int expected;

    /** {@code days} days of movements at roughly six a day, so every level of the breakdown has
     *  something in it and the account is far larger than one screen. */
    private void seedLastYear() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, -365);
        List<Transaction> txs = new ArrayList<>();
        int n = 0;
        for (int d = 0; d < 365; d++) {
            c.add(Calendar.DAY_OF_MONTH, 1);
            for (int k = 0; k < 6; k++) {
                txs.add(new Transaction(BANK, ACCOUNT, c.getTimeInMillis(),
                    (k % 2 == 0 ? 1 : -1) * (100_000L + n * 37L), "sig" + (n++), null));
            }
        }
        BalanceData.writeTransactions(ctx, txs);
        seeded = txs.size();
        expected = -1; // only this month is open, so the row count is the test's business
    }

    @Test public void aCrowdedMonthIsBuiltWholeOnOpen() {
        // The regression: rows were added in batches of a few dozen, so an ordinary open showed part
        // of this month and the rest arrived only as the user scrolled — which meant every row added
        // re-measured the whole tree. One open has to build the month, once.
        seedCurrentMonth(20);
        long openMs = openAndAwaitRows();
        assertEquals("a plain open must build every row of the month it opens", expected, clockTimes());
        assertTrue("a month of " + expected + " movements must open within " + OPEN_BUDGET_MS
            + "ms, took " + openMs + "ms", openMs < OPEN_BUDGET_MS);
    }

    @Test public void aBusyYearOpensQuicklyWithoutOpeningItsYears() {
        // A plain open builds this month only, so a year of history costs no more to open than the
        // month in it. Opening the older years too — which is what the cascade did — is what turned
        // this into a screen that never finished.
        seedLastYear();
        long openMs = openAndAwaitRows();
        assertTrue("a year of history must open within " + OPEN_BUDGET_MS + "ms, took " + openMs
            + "ms", openMs < OPEN_BUDGET_MS);
        // The whole point: a year of history is stored and an open builds the month it opens and
        // nothing else. A cascade would have built all " + seeded + " of them in that one pass.
        int rows = clockTimes();
        assertTrue("a plain open must not open the other years, built " + rows + " of " + seeded
            + " rows", rows < seeded / 10);
    }

    @Test public void eachOpenBuildsTheSameScreen() throws Exception {
        // A screen that keeps more views each time it is opened is a screen that is not being
        // rebuilt. Three opens of the same account have to agree.
        seedCurrentMonth(20);
        openAndAwaitRows();
        int first = viewCount();
        for (int i = 0; i < 2; i++) {
            finishAnyResumedHistory();
            Thread.sleep(400);
            openAndAwaitRows();
            int again = viewCount();
            assertEquals("each open must build the same screen, not a larger one", first, again);
        }
    }

    @Test public void scrollingBuildsNothing() {
        // Rows are built when a level opens, not when it is looked at. Scrolling to the bottom of a
        // busy month must not add a single view.
        seedCurrentMonth(20);
        openAndAwaitRows();
        int before = viewCount();
        scrollDownHard();
        assertEquals("scrolling must not build anything", before, viewCount());
    }

    /** Opens the screen and waits until every row of the open month is on it. */
    private long openAndAwaitRows() {
        Intent i = new Intent(ctx, HistoryActivity.class)
            .putExtra(HistoryActivity.EXTRA_BANK, BANK)
            .putExtra(HistoryActivity.EXTRA_ACCOUNT, ACCOUNT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        long t0 = SystemClock.uptimeMillis();
        InstrumentationRegistry.getInstrumentation().startActivitySync(i);
        awaitRows();
        return SystemClock.uptimeMillis() - t0;
    }

    /** Waits until the screen has settled.
     *
     *  <p>Where the seeder knows exactly how many rows the open month has to build, that count is the
     *  signal: it is what a batched open would have reached only after scrolling. Where it does not
     *  — an open builds the month it opens, so a seeded year yields an unknown number — the signal
     *  is the screen simply having stopped growing. */
    private void awaitRows() {
        long deadline = System.currentTimeMillis() + OPEN_BUDGET_MS * 2;
        int seen = -1, stable = 0;
        while (System.currentTimeMillis() < deadline) {
            int now = expected > 0 ? clockTimes() : viewCount();
            if (expected > 0 && now >= expected) return;
            if (now == seen && now > 0) {
                if (++stable >= 6) break;
            } else {
                stable = 0;
            }
            seen = now;
            try { Thread.sleep(25); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (expected > 0) {
            assertEquals("the open month must build all of its rows on open", expected, clockTimes());
        }
    }

    /** Drags the list the length of its content, in steps. */
    private void scrollDownHard() {
        for (int step = 0; step < 200; step++) {
            final int[] height = {0};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (Activity a : resumed()) {
                    View sv = findScroll(a.getWindow().getDecorView());
                    if (sv != null) height[0] = Math.max(height[0], sv.getHeight());
                }
            });
            if (height[0] <= 0) return;
            final int by = height[0];
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (Activity a : resumed()) {
                    View sv = findScroll(a.getWindow().getDecorView());
                    if (sv != null) sv.scrollBy(0, by);
                }
            });
            try { Thread.sleep(8); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static View findScroll(View v) {
        if (v instanceof android.widget.ScrollView) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = findScroll(g.getChildAt(i));
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private int viewCount() {
        AtomicInteger out = new AtomicInteger(0);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : resumed()) out.set(countViews(a.getWindow().getDecorView()));
        });
        return out.get();
    }

    private static int countViews(View v) {
        if (!(v instanceof ViewGroup)) return 1;
        ViewGroup g = (ViewGroup) v;
        int n = 0;
        for (int i = 0; i < g.getChildCount(); i++) n += countViews(g.getChildAt(i));
        return n + 1;
    }

    /** How many movement rows are on screen, told apart by the clock time only a row carries. */
    private int clockTimes() {
        AtomicInteger out = new AtomicInteger(0);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : resumed()) {
                collect(a.getWindow().getDecorView(), t -> {
                    CharSequence s = t.getText();
                    if (s != null && s.toString().matches("\\d{1,2}:\\d{2}")) out.incrementAndGet();
                });
            }
        });
        return out.get();
    }

    private static void collect(View v, Consumer<TextView> out) {
        if (v instanceof TextView) out.accept((TextView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    /** The year cards on screen, told apart by the tag rather than by their text. Every year is
     *  always on screen — they are closed, not absent — so this counts the top level, not what it
     *  has opened. */
    private static int yearCards() {
        final AtomicInteger out = new AtomicInteger(0);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : resumed()) {
                countTagged(a.getWindow().getDecorView(), HistoryActivity.YEAR_TAG, out);
            }
        });
        return out.get();
    }

    private static void countTagged(View v, String tag, AtomicInteger out) {
        if (tag.equals(v.getTag())) out.incrementAndGet();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) countTagged(g.getChildAt(i), tag, out);
        }
    }

    private static void finishAnyResumedHistory() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (a instanceof HistoryActivity) a.finish();
            }
        });
    }

    private static List<Activity> resumed() {
        List<Activity> out = new ArrayList<>();
        for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)) {
            if (a instanceof HistoryActivity) out.add(a);
        }
        return out;
    }
}