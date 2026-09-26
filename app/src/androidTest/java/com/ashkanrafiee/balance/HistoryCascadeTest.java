package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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
 * How the breakdown opens: the current year, month and day open on their own, and from there each
 * level brings the one below it. The point of the cascade is that there is no longer a way to ask
 * for the whole history at once, so nothing can build every row in one go.
 */
@RunWith(AndroidJUnit4.class)
public class HistoryCascadeTest {

    private Context ctx;
    private String originalTag;
    private String originalCurrency;

    private static final String MELLAT = "Mellat";
    private static final String ACCOUNT = "111";
    private static final long HOUR = 3600000L;

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

    /** 35 days back always lands in a different Jalali month, whichever day the test runs on. */
    private static final long PREVIOUS_MONTH = 35L * 24 * HOUR;

    /** 400 days back always lands in an earlier Jalali year, since a Jalali year is 365 or 366 days. */
    private static final long PREVIOUS_YEAR = 400L * 24 * HOUR;

    /** One movement today, and one in an earlier month that nothing has opened. */
    private void storeThisMonth() {
        long now = System.currentTimeMillis();
        BalanceData.writeTransactions(ctx, new ArrayList<>(java.util.Arrays.asList(
            new Transaction(MELLAT, ACCOUNT, now, 10_000_000L, "now", null),
            new Transaction(MELLAT, ACCOUNT, now - PREVIOUS_MONTH, 12_000_000L, "earlier", null))));
    }

    /** One movement today, and one in an earlier year that nothing has opened. */
    private void storePastYear() {
        long now = System.currentTimeMillis();
        BalanceData.writeTransactions(ctx, new ArrayList<>(java.util.Arrays.asList(
            new Transaction(MELLAT, ACCOUNT, now, 10_000_000L, "now", null),
            new Transaction(MELLAT, ACCOUNT, now - PREVIOUS_YEAR, 34_000_000L, "lastyear", null))));
    }

    private void launch() {
        Intent i = new Intent(ctx, HistoryActivity.class)
            .putExtra(HistoryActivity.EXTRA_BANK, MELLAT)
            .putExtra(HistoryActivity.EXTRA_ACCOUNT, ACCOUNT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        InstrumentationRegistry.getInstrumentation().startActivitySync(i);
        await(() -> findByDescription(ctx.getString(R.string.history_export)) != null, 20_000, "the history screen");
    }

    @Test public void todaysMovements_areVisibleWithoutTouchingAnything() {
        // Whatever else changes, the freshest history has to be on screen the moment it opens.
        storeThisMonth();
        launch();
        assertNotNull("today's movement must be visible without any tap",
            findByTextContaining("10,000,000"));
    }

    @Test public void theCurrentYearArrivesWholeBecauseItsMonthsFollowIt() {
        // The visible consequence of dropping the expand-all switch: the year is now the unit. The
        // current year opens, so its months and their days come with it and the screen shows the
        // year rather than only today. Nothing here is built past the row budget, so this stays
        // cheap however long the year is.
        storeThisMonth();
        launch();
        assertNotNull("today's movement must be there", findByTextContaining("10,000,000"));
        assertNotNull("the rest of the current year must come with it",
            findByTextContaining("12,000,000"));
        assertEquals("both movements are on screen", 2, clockTimes());
    }

    @Test public void anEarlierYear_staysClosedSoItsRowsAreNeverBuilt() {
        // Nothing beyond the current year opens by itself, so an older year builds no rows at all
        // until the user opens it. The test counts clock times rather than amounts on purpose: a
        // collapsed month still shows its own net, so an amount on screen proves nothing. Only a
        // built row carries a clock time.
        storePastYear();
        launch();
        assertNotNull("today's movement must be there", findByTextContaining("10,000,000"));
        assertEquals("an unopened year must not have built any rows", 1, clockTimes());
        View year = descriptionEnding(ctx.getString(R.string.history_collapsed));
        assertNotNull("the earlier year must be on screen, closed", year);
        tap(year);
        await(() -> clockTimes() == 2, 10_000, "the older year to reveal its movements");
        assertEquals("opening a year must bring its months, and so its movements", 2, clockTimes());
    }

    @Test public void thereIsNoLongerAWayToAskForEverythingAtOnce() {
        // The Display menu used to carry an "expand all history" switch. With it gone, no single
        // choice opens every row, which is what used to make a long account take seconds.
        storeThisMonth();
        launch();
        assertNull("the expand-all setting must be gone from the app",
            viewWithDescriptionContaining("expand all"));
    }

    private void tap(View target) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(target::performClick);
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

    private static TextView findByText(String want) {
        for (TextView t : texts()) if (want.equals(t.getText().toString())) return t;
        return null;
    }

    private static TextView findByTextContaining(String want) {
        for (TextView t : texts()) {
            CharSequence s = t.getText();
            if (s != null && s.toString().contains(want)) return t;
        }
        return null;
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

    /** The first header whose accessible description ends with this expansion state. */
    private static View descriptionEnding(String ending) {
        return find(v -> {
            CharSequence d = v.getContentDescription();
            return d != null && d.toString().endsWith(ending);
        });
    }

    private static View findByDescription(String want) {
        return find(v -> want.equals(v.getContentDescription()));
    }

    private static View viewWithDescriptionContaining(String want) {
        return find(v -> {
            CharSequence d = v.getContentDescription();
            return d != null && d.toString().toLowerCase().contains(want);
        });
    }

    private static View find(java.util.function.Predicate<View> m) {
        final List<View> hits = new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : resumed()) collect(a.getWindow().getDecorView(), m, hits);
        });
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
