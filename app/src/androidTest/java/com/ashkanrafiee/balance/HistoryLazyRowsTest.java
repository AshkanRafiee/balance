package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A month is not read in one go. Its first batch of days is built when it opens, and the rest
 * arrive as the user reaches the bottom of the list, which is what keeps a year with a long tail of
 * movements from building every row at once.
 */
@RunWith(AndroidJUnit4.class)
public class HistoryLazyRowsTest {

    private Context ctx;
    private String originalTag;
    private String originalCurrency;

    private static final String MELLAT = "Mellat";
    private static final String ACCOUNT = "111";
    private static final long DAY = 86400000L;

    @Before public void setUp() {
        finishAnyResumedHistory();
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalTag = LocaleHelper.currentTag(ctx);
        originalCurrency = CurrencyHelper.currency(ctx);
        LocaleHelper.setLanguage(ctx, "en");
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CURRENCY_RIAL);
    }

    @After public void tearDown() {
        LocaleHelper.setLanguage(ctx, originalTag);
        CurrencyHelper.setCurrency(ctx, originalCurrency);
        BalanceData.reset(ctx, true);
    }

    /**
     * One movement on every day of the previous Jalali month.
     *
     * <p>A named month rather than "however many days back", because the budget applies per month
     * and a run that straddled a month boundary could be spread over two budgets and prove nothing.
     * The previous month also always exists and always has enough days to outrun one batch, so the
     * test behaves the same on the first of a month as on the last.
     */
    private int storeTheWholePreviousMonth() throws Exception {
        return storeTheWholePreviousMonth(1);
    }

    private int storeTheWholePreviousMonth(int perDay) throws Exception {
        CalDate now = CalDate.today(true);
        int year = now.month == 1 ? now.year - 1 : now.year;
        int month = now.month == 1 ? 12 : now.month - 1;
        int days = CalDate.daysInMonth(year, month, true);
        List<Transaction> txs = new ArrayList<>();
        for (int d = 1; d <= days; d++) {
            for (int k = 0; k < perDay; k++) {
                txs.add(new Transaction(MELLAT, ACCOUNT, middayOf(year, month, d) + k * 1000L,
                    1_000_000L + d * 10 + k, "sig" + d + "_" + k, null));
            }
        }
        FinancialTestStore.writeTransactions(ctx, txs);
        return days * perDay;
    }

    /** Noon on a Jalali date, in the device's own time zone. */
    private long middayOf(int year, int month, int day) {
        int[] g = CalDate.of(year, month, day).toGregorian(true);
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.clear();
        c.set(g[0], g[1] - 1, g[2], 12, 0, 0);
        return c.getTimeInMillis();
    }

    /** Opens the older year if the previous month happens to sit in one, so the test measures the
     *  budget rather than a year the user has not opened. */
    private void openOlderYearIfClosed() {
        View year = firstDescriptionEnding(ctx.getString(R.string.history_collapsed));
        if (year == null) return;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(year::performClick);
        await(() -> clockTimes() > 0, 10_000, "the older year to open");
    }

    private void launch() {
        Intent i = new Intent(ctx, HistoryActivity.class)
            .putExtra(HistoryActivity.EXTRA_BANK, MELLAT)
            .putExtra(HistoryActivity.EXTRA_ACCOUNT, ACCOUNT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        InstrumentationRegistry.getInstrumentation().startActivitySync(i);
        await(() -> findByDescription(ctx.getString(R.string.history_export)) != null, 20_000,
            "the history screen");
    }

    @Test public void aMonthTooCrowdedForOneBatch_isNotBuiltWhole() throws Exception {
        // A month with one movement on every day has more day groups than a single batch can hold,
        // so it opens part-built. This is the whole point of the budget, and it is what a user with
        // years of history would otherwise feel on every open.
        int days = storeTheWholePreviousMonth();
        launch();
        openOlderYearIfClosed();
        int built = clockTimes();
        assertTrue("some movements must be built, or the screen is useless: " + built, built > 0);
        assertTrue("a month of " + days + " movements must not be built in one go, but built "
            + built, built < days);
    }

    @Test public void reachingTheBottom_revealsMoreOfTheMonth() throws Exception {
        // The other half of the contract: the rows held back must actually arrive, and arrive
        // because the user scrolled. Without this the budget could simply be dropping movements.
        int rows = storeTheWholePreviousMonth(3);
        launch();
        openOlderYearIfClosed();
        int before = clockTimes();
        assertTrue("a crowded month must open part-built, but opened " + before + " of " + rows,
            before < rows);
        scrollToBottom();
        await(() -> clockTimes() > before, 10_000, "the bottom of the list to reveal more");
        assertTrue("scrolling to the bottom must reveal more than " + before,
            clockTimes() > before);
    }

    @Test public void revealingMore_keepsTheRowsAlreadyOnScreen() throws Exception {
        // A reveal must add rows, not re-make the ones above them. Rebuilding the month would
        // replace every view the user is reading, which is both the expensive way round and the one
        // that makes the list re-measure itself under a moving finger.
        storeTheWholePreviousMonth(3);
        launch();
        openOlderYearIfClosed();
        View first = firstClockRow();
        assertNotNull("expected movements on screen to begin with", first);
        int before = clockTimes();
        scrollToBottom();
        await(() -> clockTimes() > before, 10_000, "the bottom of the list to reveal more");
        assertSame("a row that was already on screen must not be rebuilt by a reveal",
            first, firstClockRow());
    }

    @Test public void theNextBatchStartsBeforeTheUserReachesTheBottom() throws Exception {
        // The batch is built as the user approaches the end of what is there, not on arrival at it,
        // so there is nothing left to wait for when they get there. Landing strictly inside the
        // prefetch window is the point: the rows below the fold are what make this different from
        // simply scrolling to the end.
        int total = storeTheWholePreviousMonth(3);
        launch();
        // The header exists even while the skeleton is still being shown.
        afterHistoryLayout(() -> { });
        openOlderYearIfClosed();
        int before = scrollNearButNotToTheBottom(total);
        await(() -> clockTimes() > before, 10_000, "the next batch to start on the way down");
    }

    @Test public void aCrowdedMonthStillFinishesFillingOut() throws Exception {
        // Scrolling down through a crowded month must eventually show the whole of it. A fill that
        // quietly stopped would leave the account short, with nothing on screen to say so.
        int rows = storeTheWholePreviousMonth(3);
        int days = rows / 3;
        launch();
        openOlderYearIfClosed();
        assertTrue("the month must open part-built to be worth testing", dayCards() < days);
        await(() -> {
            scrollToBottom();
            return dayCards() >= days;
        }, 60_000, "the month to fill out as the user scrolls through it");
        assertEquals("every day of the month must end up on screen", days, dayCards());
    }

    @Test public void aSmallMonthIsUnaffectedByTheBudget() throws Exception {
        // The budget must not cost anything on an ordinary month, which is nearly every month of
        // nearly every account. A month that fits is built whole, with nothing left to reveal.
        FinancialTestStore.writeTransactions(ctx, new ArrayList<>(java.util.Arrays.asList(
            new Transaction(MELLAT, ACCOUNT, System.currentTimeMillis(), 1_000_000L, "a", null),
            new Transaction(MELLAT, ACCOUNT, System.currentTimeMillis() - 2 * DAY, 1_000_001L, "b", null),
            new Transaction(MELLAT, ACCOUNT, System.currentTimeMillis() - 4 * DAY, 1_000_002L, "c", null))));
        launch();
        assertEquals("a month that fits must be built whole", 3, clockTimes());
    }

    @Test public void theFirstDayIsBuiltEvenWhenItExceedsTheBudget() throws Exception {
        // A single day holding more movements than a whole batch must still show, or a month could
        // open onto nothing at all and look broken.
        long now = System.currentTimeMillis();
        List<Transaction> txs = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            txs.add(new Transaction(MELLAT, ACCOUNT, now, 1_000_000L + i, "big" + i, null));
        }
        FinancialTestStore.writeTransactions(ctx, txs);
        launch();
        assertEquals("a single crowded day must be built whole", 60, clockTimes());
    }

    /**
     * Scrolls to a point still short of the bottom, but inside the distance the next batch starts at.
     *
     * <p>Stops inside the prefetch window so there are demonstrably rows left below the fold,
     * which is what separates this from the bottom-of-list reveal.
     */
    private static int scrollNearButNotToTheBottom(int total) throws Exception {
        final int[] before = {0};
        afterHistoryLayout(() -> {
            for (Activity a : resumed()) {
                android.widget.ScrollView sv = tallestScrollView(a.getWindow().getDecorView(), null);
                if (sv == null) continue;
                ViewGroup list = (ViewGroup) sv.getChildAt(0);
                if (list == null) continue;
                int viewport = sv.getHeight();
                int content = list.getHeight();
                List<TextView> rows = new ArrayList<>();
                collect(list, rows);
                for (TextView row : rows) if (isClock(row)) before[0]++;
                assertTrue("the month must open part-built: " + before[0] + " of " + total,
                    before[0] > 0 && before[0] < total);
                int range = content - viewport;
                assertTrue("the laid-out history must have room for a non-bottom scroll", range > 1);
                int prefetch = Math.max(700, Math.round(viewport * 1.5f));
                // A short scroll range can already be inside the prefetch window at the top.
                // Still make a real downward scroll, leaving content strictly below the fold.
                int remaining = Math.min(prefetch - 200, range / 2);
                int target = range - remaining;
                assertTrue("the test must not simply scroll to the bottom: target " + target
                    + " of " + content, target > 0 && target < range);
                assertTrue("the target must be strictly inside the prefetch window",
                    remaining > 0 && remaining < prefetch);
                assertEquals("the initial history must start at the top", 0, sv.getScrollY());
                sv.scrollTo(0, target);
                assertEquals("the scroll must reach the non-bottom target", target, sv.getScrollY());
                return;
            }
            throw new AssertionError("the history scroller was not found");
        });
        return before[0];
    }

    /** Run in pre-draw, after the real history (including an expanded year) has been laid out.
     * Row existence alone is insufficient: heights can still belong to the previous skeleton. */
    private static void afterHistoryLayout(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        final View[] root = {null};
        final ViewTreeObserver.OnPreDrawListener[] listener = {null};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            List<Activity> activities = resumed();
            assertEquals("expected one history screen", 1, activities.size());
            Activity activity = activities.get(0);
            root[0] = activity.getWindow().getDecorView();
            listener[0] = () -> {
                List<TextView> labels = new ArrayList<>();
                collect(root[0], labels);
                boolean loaded = false;
                String heading = activity.getString(R.string.history_breakdown);
                for (TextView label : labels) {
                    if (heading.contentEquals(label.getText())) loaded = true;
                }
                if (!loaded || root[0].isLayoutRequested()) return true;
                root[0].getViewTreeObserver().removeOnPreDrawListener(listener[0]);
                try { action.run(); } catch (Throwable t) { failure.set(t); }
                finally { done.countDown(); }
                return true;
            };
            root[0].getViewTreeObserver().addOnPreDrawListener(listener[0]);
            root[0].invalidate();
        });
        try {
            assertTrue("timed out waiting for real history layout", done.await(15, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError("history layout assertion failed", failure.get());
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (root[0].getViewTreeObserver().isAlive()) {
                    root[0].getViewTreeObserver().removeOnPreDrawListener(listener[0]);
                }
            });
        }
    }

    /** Scrolls the list as far down as it goes, the gesture the reveal is wired to.
     *
     *  <p>The screen has more than one scroller: the filter bar's chip strip scrolls sideways, and
     *  a plain search for the first one finds that instead of the history. The list is the tallest,
     *  so that is what gets scrolled. */
    private static void scrollToBottom() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : resumed()) {
                android.widget.ScrollView sv = tallestScrollView(a.getWindow().getDecorView(), null);
                if (sv != null) sv.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private static android.widget.ScrollView tallestScrollView(
        View v, android.widget.ScrollView best) {
        if (v instanceof android.widget.ScrollView) {
            android.widget.ScrollView sv = (android.widget.ScrollView) v;
            if (best == null || sv.getHeight() > best.getHeight()) best = sv;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) best = tallestScrollView(g.getChildAt(i), best);
        }
        return best;
    }

    /** How many movement rows are on screen, told apart by the clock time only a row carries. */
    private static int clockTimes() {
        return clockRows().size();
    }

    /** How many day cards are on screen, which is how much of the month has been built. Counted by
     *  the tag the screen puts on each one, since recognising a day header by its text would tie the
     *  test to the wording of the date. */
    private static int dayCards() {
        final int[] n = {0};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : resumed()) {
                countDayCards(a.getWindow().getDecorView(), n);
            }
        });
        return n[0];
    }

    private static void countDayCards(View v, int[] n) {
        if (HistoryActivity.DAY_TAG.equals(v.getTag())) n[0]++;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) countDayCards(g.getChildAt(i), n);
        }
    }

    /** The topmost movement row on screen, as a view, so a test can tell a kept row from a new one. */
    private static View firstClockRow() {
        List<TextView> all = texts();
        for (int i = 0; i < all.size(); i++) if (isClock(all.get(i))) return all.get(i);
        return null;
    }

    private static List<View> clockRows() {
        List<TextView> all = texts();
        List<View> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) if (isClock(all.get(i))) out.add(all.get(i));
        return out;
    }

    private static boolean isClock(TextView t) {
        CharSequence s = t.getText();
        return s != null && s.toString().matches("\\d{1,2}:\\d{2}");
    }

    private static List<TextView> texts() {
        final List<TextView> out = new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : resumed()) collect(a.getWindow().getDecorView(), out);
        });
        return out;
    }

    private static void collect(View v, List<TextView> out) {
        if (v instanceof TextView) out.add((TextView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    private static View firstDescriptionEnding(String ending) {
        final List<View> hits = new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : resumed()) {
                collect(a.getWindow().getDecorView(), v -> {
                    CharSequence d = v.getContentDescription();
                    return d != null && d.toString().endsWith(ending);
                }, hits);
            }
        });
        return hits.isEmpty() ? null : hits.get(0);
    }

    private static View findByDescription(String want) {
        final List<View> hits = new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : resumed()) {
                collect(a.getWindow().getDecorView(),
                    v -> want.equals(v.getContentDescription()), hits);
            }
        });
        assertNotNull("the history header never appeared", hits.isEmpty() ? null : hits.get(0));
        return hits.isEmpty() ? null : hits.get(0);
    }

    private static void collect(View v, java.util.function.Predicate<View> m, List<View> out) {
        if (m.test(v)) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), m, out);
        }
    }

    /**
     * Closes any history screen an earlier test left standing.
     *
     * <p>These tests read every resumed view, so one screen left over from a previous test would
     * contribute its rows to the next test's count. That passes when a class runs alone and fails in
     * a full suite, which is the worst way for a test to be wrong.
     */
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

    private static void await(Callable<Boolean> done, long timeoutMs, String what) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try { if (done.call()) return; } catch (Exception ignored) { }
            try { Thread.sleep(150); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}
