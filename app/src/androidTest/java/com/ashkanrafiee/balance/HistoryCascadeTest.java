package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

/**
 * How the breakdown opens: the current year, month and its days open by themselves, and from there
 * each level opens on its own. Opening a year shows its months and nothing more; opening a month
 * shows its days and nothing more.
 *
 * <p>The levels are kept independent deliberately. Cascading them meant one tap on a year built a
 * whole year's movements in a single pass, which is a screen that takes seconds to appear, and a
 * year of a busy account took long enough to be mistaken for a freeze. Asking for the whole
 * history is still possible, but only by choosing it in the hidden debug menu.
 */
@RunWith(AndroidJUnit4.class)
public class HistoryCascadeTest {

    private Context ctx;
    private String originalTag;
    private String originalCurrency;
    private ActivityScenario<HistoryActivity> scenario;

    private static final String MELLAT = "Mellat";
    private static final String ACCOUNT = "111";
    private static final long HOUR = 3600000L;

    @Before public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalTag = LocaleHelper.currentTag(ctx);
        originalCurrency = CurrencyHelper.currency(ctx);
        LocaleHelper.setLanguage(ctx, "en");
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CURRENCY_RIAL);
        BalanceData.setExpandAllHistory(ctx, false);
    }

    @After public void tearDown() {
        if (scenario != null) scenario.close();
        LocaleHelper.setLanguage(ctx, originalTag);
        CurrencyHelper.setCurrency(ctx, originalCurrency);
        BalanceData.setExpandAllHistory(ctx, false);
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
        scenario = ActivityScenario.launch(i);
        // The export action is built synchronously, before any history rows exist. Wait for the
        // actual movement row and settled catch-up, not a header that is present during loading.
        await(() -> {
            final boolean[] idle = {false};
            scenario.onActivity(activity -> idle[0] = HistoryActivity.idleForTest(activity));
            return idle[0] && clockTimes() > 0;
        }, 20_000, "today's movement row and the completed history render");
    }

    @Test public void openingTheCurrentYear_showsItsMonthsWithoutBuildingTheirRows() {
        // The regression this guards: the current year used to bring its months with it, and each of
        // those brought its days, so an ordinary open built a year of movements in one pass. Only a
        // built row carries a clock time, and a collapsed month still shows its own net, so counting
        // clock times is what tells built rows from collapsed ones.
        storeThisMonth();
        launch();
        assertEquals("only this month's movements are built on a plain open", 1, clockTimes());
        assertEquals("the year is on screen", 1, years().size());
        assertEquals("its two months are on screen", 2, months().size());
        assertEquals("only today's day is open with it", 1, days().size());
        assertNotNull("this month's movements are on screen", findByTextContaining("10,000,000"));

        // The other month opens to its days, and only then to its movements.
        tapGroup(collapsedHeader());
        await(() -> days().size() == 2, 10_000, "the older month to show its days");
        assertEquals("opening a month must not have built any of its movements", 1, clockTimes());
        tapGroup(days().get(1));
        await(() -> clockTimes() == 2, 10_000, "the older day to show its movements");
    }

    @Test public void openingAnEarlierYear_showsItsMonthsWithoutBuildingTheirRows() {
        // Nothing beyond the current year opens by itself, so an older year builds no rows at all
        // until the user opens it — and opening it stops at its months.
        storePastYear();
        launch();
        assertNotNull("today's movement must be there", findByTextContaining("10,000,000"));
        assertEquals("an unopened year must not have built any rows", 1, clockTimes());
        assertEquals("both years must be on screen, the older one closed", 2, years().size());
        assertEquals("only this year is open, so only its months are on screen", 1, months().size());

        tapGroup(years().get(1));
        await(() -> months().size() == 2, 10_000, "the older year to show its months");
        assertEquals("opening a year must not have built any of its movements", 1, clockTimes());
        assertEquals("and must not have built any of its days", 1, days().size());

        // And its movements are one tap at a time further away.
        tapGroup(collapsedHeader());
        await(() -> days().size() == 2, 10_000, "the older month to show its days");
        assertEquals("opening a month must not have built any of its movements", 1, clockTimes());
        tapGroup(days().get(1));
        await(() -> clockTimes() == 2, 10_000, "the older day to show its movements");
    }

    @Test public void askingToExpandEverything_opensEveryLevelAtOnce() {
        // The debug menu's switch is the one way to ask for the whole history, so it has to do
        // exactly that: with it on, everything is already on screen and nothing has to be tapped.
        // Mark the upgrade migration done first: on a fresh install the first activity start
        // would otherwise reset this opt-in back off.
        storeThisMonth();
        BalanceData.migrateExpandAllToDebugMenu(ctx);
        BalanceData.setExpandAllHistory(ctx, true);
        launch();
        assertNotNull("today's movement must be there", findByTextContaining("10,000,000"));
        assertNotNull("the other month's movement must be there without any tap",
            findByTextContaining("12,000,000"));
        assertEquals("both movements are on screen", 2, clockTimes());
    }

    /** Taps a group to open it. The tag identifies the card that holds the whole group, while the
     *  click listener belongs to the header row inside it, so the tap has to go to that header. */
    private void tapGroup(View group) {
        scenario.onActivity(activity -> {
            View header = firstClickable(group);
            assertNotNull("a group must have a header to tap", header);
            header.performClick();
        });
    }

    private static View firstClickable(View v) {
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = firstClickable(g.getChildAt(i));
                if (hit != null) return hit;
            }
        }
        return v.isClickable() ? v : null;
    }

    private List<String> texts() {
        final List<String> out = new ArrayList<>();
        scenario.onActivity(activity -> {
            List<TextView> labels = new ArrayList<>();
            collect(activity.getWindow().getDecorView(), labels);
            for (TextView label : labels) out.add(label.getText().toString());
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

    private String findByTextContaining(String want) {
        for (String text : texts()) {
            if (text.contains(want)) return text;
        }
        return null;
    }

    /** How many movement rows are on screen, told apart by the clock time only a row carries. */
    private int clockTimes() {
        int n = 0;
        for (String text : texts()) {
            if (text.matches("\\d{1,2}:\\d{2}")) n++;
        }
        return n;
    }

    /** The year cards on screen, in the order they were rendered (newest first). */
    private List<View> years() {
        return tagged(HistoryActivity.YEAR_TAG);
    }

    /** The month rows on screen, across every open year, in the order they were rendered. */
    private List<View> months() {
        return tagged(HistoryActivity.MONTH_TAG);
    }

    private List<View> tagged(String tag) {
        final List<View> out = new ArrayList<>();
        scenario.onActivity(activity -> collectTagged(activity.getWindow().getDecorView(), tag, out));
        return out;
    }

    private static void collectTagged(View v, String tag, List<View> out) {
        if (tag.equals(v.getTag())) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectTagged(g.getChildAt(i), tag, out);
        }
    }

    /** The day rows on screen, told apart by the tag rather than by their text. */
    private List<View> days() {
        return tagged(HistoryActivity.DAY_TAG);
    }

    /** The first collapsed group's header on screen, which is the next one a tap would open. */
    private View collapsedHeader() {
        String collapsed = ctx.getString(R.string.history_collapsed);
        View header = find(v -> {
            CharSequence d = v.getContentDescription();
            return d != null && d.toString().endsWith(collapsed);
        });
        assertNotNull("there must be something closed to open", header);
        return header;
    }

    private View find(Predicate<View> m) {
        final List<View> hits = new ArrayList<>();
        scenario.onActivity(activity -> collect(activity.getWindow().getDecorView(), m, hits));
        return hits.isEmpty() ? null : hits.get(0);
    }

    private static void collect(View v, Predicate<View> m, List<View> out) {
        if (m.test(v)) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), m, out);
        }
    }

    private static void await(Callable<Boolean> done, long timeoutMs, String what) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            try { if (done.call()) return; }
            catch (Exception e) { throw new AssertionError("history readiness check failed: " + what, e); }
            try { Thread.sleep(150); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}
