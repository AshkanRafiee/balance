package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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
import java.util.List;
import java.util.concurrent.Callable;

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
    private int storeTheWholePreviousMonth() {
        return storeTheWholePreviousMonth(1);
    }

    private int storeTheWholePreviousMonth(int perDay) {
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
        BalanceData.writeTransactions(ctx, txs);
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

    @Test public void aMonthTooCrowdedForOneBatch_isNotBuiltWhole() {
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

    @Test public void reachingTheBottom_revealsMoreOfTheMonth() {
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

    @Test public void aSmallMonthIsUnaffectedByTheBudget() {
        // The budget must not cost anything on an ordinary month, which is nearly every month of
        // nearly every account. A month that fits is built whole, with nothing left to reveal.
        BalanceData.writeTransactions(ctx, new ArrayList<>(java.util.Arrays.asList(
            new Transaction(MELLAT, ACCOUNT, System.currentTimeMillis(), 1_000_000L, "a", null),
            new Transaction(MELLAT, ACCOUNT, System.currentTimeMillis() - 2 * DAY, 1_000_001L, "b", null),
            new Transaction(MELLAT, ACCOUNT, System.currentTimeMillis() - 4 * DAY, 1_000_002L, "c", null))));
        launch();
        assertEquals("a month that fits must be built whole", 3, clockTimes());
    }

    @Test public void theFirstDayIsBuiltEvenWhenItExceedsTheBudget() {
        // A single day holding more movements than a whole batch must still show, or a month could
        // open onto nothing at all and look broken.
        long now = System.currentTimeMillis();
        List<Transaction> txs = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            txs.add(new Transaction(MELLAT, ACCOUNT, now, 1_000_000L + i, "big" + i, null));
        }
        BalanceData.writeTransactions(ctx, txs);
        launch();
        assertEquals("a single crowded day must be built whole", 60, clockTimes());
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
        int n = 0;
        for (TextView t : texts()) {
            CharSequence s = t.getText();
            if (s != null && s.toString().matches("\\d{1,2}:\\d{2}")) n++;
        }
        return n;
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
