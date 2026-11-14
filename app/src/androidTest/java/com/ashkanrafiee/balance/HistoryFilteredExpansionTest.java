package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.LocaleList;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Narrowed history opens populated days, without widening the ordinary history default. */
@RunWith(AndroidJUnit4.class)
public class HistoryFilteredExpansionTest {
    private static final String BANK = "Mellat";
    private static final String ACCOUNT = "99900045678";
    private static final long TIMEOUT_MS = 60_000L;

    private Context context;
    private SharedPreferences prefs;
    private ActivityScenario<HistoryActivity> scenario;
    private File exportFile;
    private CountDownLatch renderRelease;
    private int originalRegion;
    private String originalLanguage;
    private LocaleList originalLocales;
    private Set<String> originalExcluded;
    private boolean hadExpand, originalExpand, hadMigration, originalMigration;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = context.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE);
        originalRegion = RegionHelper.region(context);
        originalLanguage = LocaleHelper.currentTag(context);
        originalLocales = LocaleList.getDefault();
        originalExcluded = BalanceData.getExcluded(context);
        hadExpand = prefs.contains(BalanceData.KEY_EXPAND_ALL_HISTORY);
        originalExpand = BalanceData.getExpandAllHistory(context);
        hadMigration = prefs.contains(BalanceData.KEY_EXPAND_ALL_DEBUG_MIGRATED);
        originalMigration = prefs.getBoolean(BalanceData.KEY_EXPAND_ALL_DEBUG_MIGRATED, false);
        BalanceData.reset(context, true);
        RegionHelper.setRegion(context, RegionHelper.REGION_INTERNATIONAL);
        LocaleHelper.setLanguage(context, "en");
        // Finish first-open migration before storing fixtures: changing this preference can
        // schedule history invalidation, and a first activity must not reset the test's setup.
        BalanceData.migrateExpandAllToDebugMenu(context);
        assertTrue(prefs.edit()
            .putBoolean(BalanceData.KEY_EXPAND_ALL_HISTORY, false)
            .putBoolean(BalanceData.KEY_EXPAND_ALL_DEBUG_MIGRATED, true).commit());
        HistoryWarmup.invalidate();
    }

    @After public void tearDown() {
        // Always unblock workers before waiting for idle, including after a failed assertion.
        if (renderRelease != null) renderRelease.countDown();
        HistoryActivity.setRenderGateForTest(null, null);
        try {
            if (scenario != null) {
                try {
                    await(activity -> HistoryActivity.idleForTest(activity)
                            && !((Boolean) field(activity, "exportRunning")),
                        "history workers before fixture cleanup");
                } finally {
                    scenario.close();
                }
            }
        } finally {
            try {
                BalanceData.reset(context, true);
            } finally {
                HistoryWarmup.invalidate();
                if (exportFile != null && exportFile.exists()) exportFile.delete();
                RegionHelper.setRegion(context, originalRegion);
                LocaleHelper.setLanguage(context, originalLanguage);
                BalanceData.setExcluded(context, originalExcluded);
                SharedPreferences.Editor restore = prefs.edit();
                if (hadExpand) restore.putBoolean(
                    BalanceData.KEY_EXPAND_ALL_HISTORY, originalExpand);
                else restore.remove(BalanceData.KEY_EXPAND_ALL_HISTORY);
                if (hadMigration) restore.putBoolean(
                    BalanceData.KEY_EXPAND_ALL_DEBUG_MIGRATED, originalMigration);
                else restore.remove(BalanceData.KEY_EXPAND_ALL_DEBUG_MIGRATED);
                restore.commit();
                LocaleList.setDefault(originalLocales);
            }
        }
    }

    @Test public void freshAccountScope_opensEightSparseDaysAndOnlyTheirParentsWithBoundedRows()
            throws Exception {
        List<CalDate> dates = sparseDays();
        List<Transaction> stored = deposits(dates, 129);
        long today = noon(CalDate.today(false));
        // Neither a newer account at this bank nor the same account at another bank may
        // consume a slot in this account's initial page window.
        stored.add(new Transaction(BANK, "other-account", today, 900L, "other-account", null));
        stored.add(new Transaction("Saman", ACCOUNT, today, 800L, "other-bank", null));
        assertTrue(BalanceData.writeTransactions(context, stored));
        openHistory(true, true);

        scenario.onActivity(activity -> {
            assertEquals(138L, result(activity).movementCount);
            assertEquals("collapsed days still contribute to the complete summary",
                dayKeys(dates), result(activity).daySummaries.keySet());
            assertEquals(13_808_256L, result(activity).total);
            assertExpansion(activity, dates.subList(0, 8));
            Map<String, HistoryReader.DayRows> pages = pages(activity);
            HistoryReader.DayRows newest = pages.get(dates.get(0).key());
            assertEquals("one busy day still has a single 128-row page", 128, newest.rows.size());
            assertTrue("the remaining movement stays page-addressable", newest.hasMore);
            assertFalse(newest.hasPrevious);
            for (HistoryReader.DayRows page : pages.values()) {
                assertTrue("every eager page respects the existing row cap", page.rows.size() <= 128);
                for (Transaction transaction : page.transactions) {
                    assertEquals(BANK, transaction.bank);
                    assertEquals(ACCOUNT, transaction.account);
                }
            }
            assertEquals("rows, not just summaries, must be built for all eight days",
                135, clockTimes(activity.getWindow().getDecorView()));
        });
        assertExportCount(138);
    }

    @Test public void freshBankScope_opensOldPopulatedDaysWithoutAnAccountSelection() {
        List<CalDate> dates = sparseDays().subList(0, 3);
        List<Transaction> stored = deposits(dates, 1);
        stored.add(new Transaction("Saman", ACCOUNT, noon(CalDate.today(false)),
            900L, "different-bank", null));
        assertTrue(BalanceData.writeTransactions(context, stored));
        openHistory(true, false);

        scenario.onActivity(activity -> {
            assertEquals(3L, result(activity).movementCount);
            assertExpansion(activity, dates);
            assertEquals(3, clockTimes(activity.getWindow().getDecorView()));
        });
    }

    @Test public void jalaliAccountScope_opensNewestEightAcrossNowruzAndOlderMonths() {
        RegionHelper.setRegion(context, RegionHelper.REGION_IRAN);
        List<CalDate> gregorian = Arrays.asList(
            CalDate.of(2024, 3, 20), CalDate.of(2024, 3, 19),
            CalDate.of(2023, 9, 23), CalDate.of(2023, 8, 23),
            CalDate.of(2023, 6, 22), CalDate.of(2023, 3, 21),
            CalDate.of(2023, 3, 20), CalDate.of(2022, 9, 23),
            CalDate.of(2022, 3, 21), CalDate.of(2021, 3, 21));
        List<CalDate> jalali = new ArrayList<>();
        for (CalDate date : gregorian) {
            jalali.add(CalDate.fromGregorian(date.year, date.month, date.day, true));
        }
        assertEquals("1403/1/1", jalali.get(0).key());
        assertEquals("1402/12/29", jalali.get(1).key());
        assertTrue(BalanceData.writeTransactions(context, deposits(gregorian, 1)));
        openHistory(true, true);
        scenario.onActivity(activity -> {
            assertTrue(result(activity).iranCalendar);
            assertEquals(10L, result(activity).movementCount);
            assertEquals(dayKeys(jalali), result(activity).daySummaries.keySet());
            assertExpansion(activity, jalali.subList(0, 8));
            assertEquals(8, clockTimes(activity.getWindow().getDecorView()));
        });
    }

    @Test public void residualOnlySearch_opensItsOlderMatchingDayWithoutMovementMatches() {
        List<CalDate> dates = sparseDays();
        CalDate residualDay = dates.get(9);
        long base = noon(residualDay);
        List<Transaction> stored = deposits(dates, 1);
        stored.add(new Transaction(BANK, ACCOUNT, base - 2_000L,
            -30_000_000L, 290_000_000L, "gap-before", null));
        stored.add(new Transaction(BANK, ACCOUNT, base - 1_000L,
            -5_000_000L, 283_000_000L, "gap-after", null));
        List<Residual> residuals = Residual.between(stored);
        assertEquals(1, residuals.size());
        assertTrue(BalanceData.writeTransactions(context, stored));
        String query = "uniquely searchable old gap";
        BalanceData.setNote(context, residuals.get(0), query);
        openHistory(true, true);
        scenario.onActivity(activity -> {
            assertFalse("the old residual starts outside the eight-day preview",
                pages(activity).containsKey(residualDay.key()));
            ((EditText) field(activity, "searchInput")).setText(query);
            invoke(activity, "flushSearch");
        });
        await(HistoryActivity::idleForTest, "the residual-only search result");
        scenario.onActivity(activity -> {
            assertEquals(0L, result(activity).movementCount);
            assertEquals(1L, result(activity).residualCount);
            assertExpansion(activity, java.util.Collections.singletonList(residualDay));
            HistoryReader.DayRows page = pages(activity).get(residualDay.key());
            assertTrue(page.transactions.isEmpty());
            assertEquals(1, page.rows.size());
            assertEquals(1, page.residuals.size());
            assertEquals(-2_000_000L, page.residuals.get(0).amount);
            assertNotNull("the matching residual's note must be rendered, not just its summary",
                find(activity.getWindow().getDecorView(), view ->
                    view instanceof TextView && !(view instanceof EditText)
                        && query.contentEquals(((TextView) view).getText())));
        });
    }

    @Test public void emptyFreshAccountScope_expandsWhenMatchingDataArrives() {
        openHistory(true, true);
        scenario.onActivity(activity -> {
            assertEquals(0L, result(activity).movementCount);
            assertTrue(pages(activity).isEmpty());
        });

        List<CalDate> dates = sparseDays();
        assertTrue(BalanceData.writeTransactions(context, deposits(dates, 1)));
        scenario.onActivity(activity -> invoke(activity, "render"));
        await(activity -> HistoryActivity.idleForTest(activity)
                && result(activity) != null && result(activity).movementCount == 10L,
            "the first populated result of the initially empty scope");
        scenario.onActivity(activity -> {
            assertExpansion(activity, dates.subList(0, 8));
            assertEquals(8, clockTimes(activity.getWindow().getDecorView()));
        });
    }

    @Test public void clearingFreshBankAndAccountScope_restoresRecentDaysAbsentFromScopedResult() {
        List<CalDate> dates = sparseDays();
        List<Transaction> stored = deposits(dates, 1);
        CalDate today = CalDate.today(false);
        stored.add(new Transaction("Saman", "other-account", noon(today),
            900L, "unfiltered-today", null));
        assertTrue(BalanceData.writeTransactions(context, stored));
        openHistory(true, true);
        scenario.onActivity(activity -> {
            assertExpansion(activity, dates.subList(0, 8));
            // Same selection update and render as the bank picker's Clear action, without
            // tying this baseline regression to asynchronous dialog-choice population.
            ((Set<?>) field(activity, "selectedBanks")).clear();
            ((Set<?>) field(activity, "selectedAccounts")).clear();
            setField(activity, "accountBank", null);
            invoke(activity, "render");
        });
        await(HistoryActivity::idleForTest, "clearing the launch scope");
        scenario.onActivity(activity -> {
            assertExpansion(activity, java.util.Collections.singletonList(today));
            assertEquals(11L, result(activity).movementCount);
            assertEquals(1, clockTimes(activity.getWindow().getDecorView()));
        });
    }

    @Test public void changedDirection_replacesOldExpandedPagesWithNewestEightAndStartsAtTop()
            throws Exception {
        List<CalDate> dates = sparseDays();
        List<Transaction> stored = deposits(dates, 1);
        // The first filter opens the oldest eight days. They also have deposits, so an
        // incorrect union with old expansions would fill the cap before the newest two.
        for (int i = 2; i < dates.size(); i++) {
            stored.add(new Transaction(BANK, ACCOUNT, noon(dates.get(i)) + 1_000L,
                -200L, "withdrawal-" + i, null));
        }
        assertTrue(BalanceData.writeTransactions(context, stored));
        openHistory(false, false);
        clickDirection(R.string.history_filter_withdrawals);
        scenario.onActivity(activity -> {
            assertEquals(8L, result(activity).movementCount);
            assertExpansion(activity, dates.subList(2, 10));
        });
        scrollDown();

        clickDirection(R.string.history_filter_deposits);
        awaitLayoutFrame();
        scenario.onActivity(activity -> {
            assertEquals(10L, result(activity).movementCount);
            assertExpansion(activity, dates.subList(0, 8));
            assertEquals("a genuinely changed scope owns a new top position",
                0, scroll(activity).getScrollY());
            assertEquals(8, clockTimes(activity.getWindow().getDecorView()));
        });
    }

    @Test public void sameScopeRefreshAndRecreation_preserveManualGroupsAndScroll() throws Exception {
        List<CalDate> dates = sparseDays();
        List<Transaction> stored = deposits(dates, 64);
        assertTrue(BalanceData.writeTransactions(context, stored));
        openHistory(true, true);
        scenario.onActivity(activity -> assertExpansion(activity, dates.subList(0, 8)));

        // Exercise actual independent headers, leaving the busy newest day open so that
        // a nonzero scroll offset remains representable after all three manual collapses.
        tapGroup(HistoryActivity.DAY_TAG, 1);
        tapGroup(HistoryActivity.MONTH_TAG, 2);
        tapGroup(HistoryActivity.YEAR_TAG, 1);
        Set<String> years = expansion("expandedYears");
        Set<String> months = expansion("expandedMonths");
        Set<String> days = expansion("expandedDays");
        assertFalse(days.contains(dates.get(1).key()));
        assertFalse(months.contains(monthKey(dates.get(2))));
        assertFalse(years.contains(String.valueOf(dates.get(4).year)));
        int offset = scrollDown();

        // Reapplying the same real control must not look like a new narrowing.
        clickDirection(R.string.history_filter_all);
        assertPreserved(years, months, days, offset);

        HistoryReader.Result[] previous = {null};
        scenario.onActivity(activity -> previous[0] = result(activity));
        BalanceData.setNote(context, stored.get(0), "same scope refreshed note");
        scenario.onActivity(activity -> invoke(activity, "render"));
        await(activity -> HistoryActivity.idleForTest(activity)
                && result(activity) != previous[0],
            "the same-scope metadata refresh");
        assertPreserved(years, months, days, offset);

        HistoryWarmup.invalidate();
        scenario.recreate();
        await(HistoryActivity::idleForTest, "the recreated scoped history");
        assertPreserved(years, months, days, offset);

        // Older saved screens have seeded manual groups but no expansion-scope identity.
        // Null restores exactly like an absent Bundle key: adopt, rather than reopen, it.
        scenario.onActivity(activity -> setField(activity, "narrowingExpandedFor", null));
        scenario.recreate();
        await(HistoryActivity::idleForTest, "a legacy scoped restoration");
        assertPreserved(years, months, days, offset);
    }

    @Test public void openingNinthDayDuringRefresh_replansWithoutDroppingItsLoadedRows()
            throws Exception {
        List<CalDate> dates = sparseDays();
        List<Transaction> stored = deposits(dates, 1);
        assertTrue(BalanceData.writeTransactions(context, stored));
        openHistory(true, true);
        scenario.onActivity(activity -> assertExpansion(activity, dates.subList(0, 8)));

        CountDownLatch renderStarted = new CountDownLatch(1);
        renderRelease = new CountDownLatch(1);
        HistoryActivity.setRenderGateForTest(renderStarted, renderRelease);
        try {
            BalanceData.setNote(context, stored.get(0), "refresh racing with ninth day");
            scenario.onActivity(activity -> invoke(activity, "render"));
            assertTrue("the refresh must pause after capturing the original eight-day plan",
                renderStarted.await(10, TimeUnit.SECONDS));

            // The ninth day is in the first unopened year. Its real headers load its page
            // independently while the aggregate refresh is blocked outside the store lock.
            tapGroup(HistoryActivity.YEAR_TAG, 2);
            tapGroup(HistoryActivity.MONTH_TAG, 8);
            tapGroup(HistoryActivity.DAY_TAG, 8);
            String ninth = dates.get(8).key();
            String evicted = dates.get(7).key();
            await(activity -> pages(activity).containsKey(ninth)
                    && !pages(activity).containsKey(evicted)
                    && expansion(activity, "expandedDays").contains(ninth),
                "the ninth day's page and eviction before the refresh is released");
            Set<String> years = expansion("expandedYears");
            Set<String> months = expansion("expandedMonths");
            Set<String> days = expansion("expandedDays");
            assertEquals(8, days.size());
            assertFalse(days.contains(evicted));

            renderRelease.countDown();
            await(HistoryActivity::idleForTest, "the refresh replanned from the user's day choice");
            scenario.onActivity(activity -> {
                assertEquals("refresh must retain the manually opened year", years,
                    expansion(activity, "expandedYears"));
                assertEquals("refresh must retain the manually opened month", months,
                    expansion(activity, "expandedMonths"));
                assertEquals("same-scope refresh must retain the new day selection", days,
                    expansion(activity, "expandedDays"));
                assertEquals("every expanded day must still have a resident page",
                    days, pages(activity).keySet());
                assertEquals("opening a ninth day must not raise the page cap",
                    8, pages(activity).size());
                HistoryReader.DayRows page = pages(activity).get(ninth);
                assertNotNull("the user's newly opened page must survive the old render", page);
                assertEquals(1, page.transactions.size());
                assertEquals(stored.get(8).sig, page.transactions.get(0).sig);
                assertEquals("the new day must retain its actual rendered movement",
                    8, clockTimes(activity.getWindow().getDecorView()));
                assertEquals(10L, result(activity).movementCount);
            });
        } finally {
            renderRelease.countDown();
            HistoryActivity.setRenderGateForTest(null, null);
        }
    }

    @Test public void clearingLastFilterAfterRecreation_restoresUnfilteredManualGroups()
            throws Exception {
        CalDate today = CalDate.today(false);
        List<CalDate> currentMonth = new ArrayList<>();
        for (int day = today.day; day >= 1; day--) {
            currentMonth.add(CalDate.of(today.year, today.month, day));
        }
        List<CalDate> allDates = new ArrayList<>(currentMonth);
        allDates.addAll(sparseDays());
        assertTrue(BalanceData.writeTransactions(context, deposits(allDates, 1)));
        openHistory(false, false);

        Set<String> recentCalendarDays = dayKeys(currentMonth.subList(0,
            Math.min(7, currentMonth.size())));
        scenario.onActivity(activity -> {
            assertEquals("unfiltered history keeps the current-month recent-seven rule",
                recentCalendarDays, expansion(activity, "expandedDays"));
            assertEquals(recentCalendarDays, pages(activity).keySet());
            assertEquals(singleton(String.valueOf(today.year)),
                expansion(activity, "expandedYears"));
            assertEquals(singleton(monthKey(today)), expansion(activity, "expandedMonths"));
        });
        tapGroup(HistoryActivity.DAY_TAG, 0);
        Set<String> years = expansion("expandedYears");
        Set<String> months = expansion("expandedMonths");
        Set<String> days = expansion("expandedDays");

        clickDirection(R.string.history_filter_deposits);
        scenario.onActivity(activity -> assertExpansion(activity, allDates.subList(0, 8)));
        // The pre-filter baseline must be saved too, not only the active scope's groups.
        scenario.recreate();
        await(HistoryActivity::idleForTest, "the recreated active filter");
        clickDirection(R.string.history_filter_all);
        awaitLayoutFrame();
        scenario.onActivity(activity -> {
            assertEquals(years, expansion(activity, "expandedYears"));
            assertEquals(months, expansion(activity, "expandedMonths"));
            assertEquals("clearing the final filter must not undo the manual day collapse",
                days, expansion(activity, "expandedDays"));
            assertEquals(days, pages(activity).keySet());
            assertEquals(days.size(), clockTimes(activity.getWindow().getDecorView()));
        });
    }

    private void openHistory(boolean bank, boolean account) {
        Intent intent = new Intent(context, HistoryActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (bank) intent.putExtra(HistoryActivity.EXTRA_BANK, BANK);
        if (account) intent.putExtra(HistoryActivity.EXTRA_ACCOUNT, ACCOUNT);
        scenario = ActivityScenario.launch(intent);
        await(activity -> HistoryActivity.idleForTest(activity) && result(activity) != null,
            "the initial history scan and render");
    }

    private void clickDirection(int label) {
        scenario.onActivity(activity -> {
            View control = find(activity.getWindow().getDecorView(), view ->
                view instanceof TextView && view.isClickable()
                    && activity.getString(label).contentEquals(((TextView) view).getText()));
            assertNotNull("the direction segment must expose its real control", control);
            assertTrue(control.performClick());
        });
        await(HistoryActivity::idleForTest, "the selected direction's completed render");
    }

    private void assertExportCount(int expected) throws Exception {
        exportFile = File.createTempFile("filtered-expansion-", ".csv", context.getCacheDir());
        scenario.onActivity(activity -> {
            try {
                Method method = HistoryActivity.class.getDeclaredMethod("writeExport", Uri.class);
                method.setAccessible(true);
                method.invoke(activity, Uri.fromFile(exportFile));
            } catch (ReflectiveOperationException error) {
                throw new AssertionError("could not start the production history export", error);
            }
        });
        await(activity -> !((Boolean) field(activity, "exportRunning")),
            "the complete scoped CSV export");
        int lines = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(exportFile), StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) lines++;
        }
        assertEquals("export includes the busy day's remaining row and both collapsed old days",
            expected + 1, lines);
        assertTrue("the test export must be removed", exportFile.delete());
        exportFile = null;
    }

    private void tapGroup(String tag, int index) {
        scenario.onActivity(activity -> {
            List<View> groups = new ArrayList<>();
            collectTagged(activity.getWindow().getDecorView(), tag, groups);
            assertTrue("the requested group must be rendered: " + tag, groups.size() > index);
            // Production group cards put their own header first; searching recursively
            // instead could accidentally tap an inner day's header.
            View header = ((ViewGroup) groups.get(index)).getChildAt(0);
            assertTrue("the group's own header must be clickable", header.isClickable());
            assertTrue(header.performClick());
        });
    }

    private int scrollDown() throws Exception {
        await(activity -> laidOut(scroll(activity)) && scrollRange(scroll(activity)) > 0,
            "a scrollable history layout");
        int[] offset = {0};
        scenario.onActivity(activity -> {
            ScrollView scroll = scroll(activity);
            scroll.scrollTo(0, Math.min(scrollRange(scroll), scroll.getHeight() * 2));
            offset[0] = scroll.getScrollY();
            assertTrue("fixture must allow a meaningful nonzero offset", offset[0] > 0);
        });
        awaitLayoutFrame();
        return offset[0];
    }

    private void assertPreserved(Set<String> years, Set<String> months, Set<String> days,
            int offset) throws Exception {
        await(activity -> HistoryActivity.idleForTest(activity) && laidOut(scroll(activity)),
            "settled history content");
        awaitLayoutFrame();
        // Recreation restores on pre-draw; inspect only after any posted restoration ran.
        CountDownLatch posted = new CountDownLatch(1);
        scenario.onActivity(activity -> scroll(activity).post(posted::countDown));
        assertTrue(posted.await(10, TimeUnit.SECONDS));
        scenario.onActivity(activity -> {
            assertEquals("manual year collapse must survive", years,
                expansion(activity, "expandedYears"));
            assertEquals("manual month collapse must survive", months,
                expansion(activity, "expandedMonths"));
            assertEquals("manual day collapse must survive", days,
                expansion(activity, "expandedDays"));
            assertEquals("unchanged scope must retain its scroll owner",
                offset, scroll(activity).getScrollY());
        });
    }

    private static void assertExpansion(HistoryActivity activity, List<CalDate> opened) {
        Set<String> years = new LinkedHashSet<>();
        Set<String> months = new LinkedHashSet<>();
        for (CalDate date : opened) {
            years.add(String.valueOf(date.year));
            months.add(monthKey(date));
        }
        Set<String> days = dayKeys(opened);
        assertEquals("only the newest matching populated days are materialized",
            days, pages(activity).keySet());
        assertEquals("every chosen day is open", days, expansion(activity, "expandedDays"));
        assertEquals("unneeded years must stay collapsed", years,
            expansion(activity, "expandedYears"));
        assertEquals("unneeded months must stay collapsed", months,
            expansion(activity, "expandedMonths"));
    }

    private Set<String> expansion(String name) {
        Set<String> copy = new LinkedHashSet<>();
        scenario.onActivity(activity -> copy.addAll(expansion(activity, name)));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> expansion(HistoryActivity activity, String name) {
        return (Set<String>) field(activity, name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, HistoryReader.DayRows> pages(HistoryActivity activity) {
        return (Map<String, HistoryReader.DayRows>) field(activity, "loadedDayPages");
    }

    private static HistoryReader.Result result(HistoryActivity activity) {
        return (HistoryReader.Result) field(activity, "historyResult");
    }

    private static Object field(HistoryActivity activity, String name) {
        try {
            Field field = HistoryActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(activity);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("could not read history state: " + name, error);
        }
    }

    private static void setField(HistoryActivity activity, String name, Object value) {
        try {
            Field field = HistoryActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(activity, value);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("could not set history state: " + name, error);
        }
    }

    private static void invoke(HistoryActivity activity, String name) {
        try {
            Method method = HistoryActivity.class.getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(activity);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("could not invoke history action: " + name, error);
        }
    }

    /** Ten populated days, separated by months and years and entirely outside today's year. */
    private static List<CalDate> sparseDays() {
        int year = CalDate.today(false).year - 2;
        List<CalDate> days = new ArrayList<>();
        days.add(CalDate.of(year, 11, 20));
        days.add(CalDate.of(year, 9, 13));
        days.add(CalDate.of(year, 6, 4));
        days.add(CalDate.of(year, 1, 9));
        days.add(CalDate.of(year - 1, 11, 20));
        days.add(CalDate.of(year - 1, 8, 2));
        days.add(CalDate.of(year - 1, 4, 12));
        days.add(CalDate.of(year - 1, 1, 3));
        days.add(CalDate.of(year - 2, 6, 5));
        days.add(CalDate.of(year - 3, 5, 6));
        return days;
    }

    private static List<Transaction> deposits(List<CalDate> dates, int newestCount) {
        List<Transaction> stored = new ArrayList<>();
        for (int day = 0; day < dates.size(); day++) {
            int count = day == 0 ? newestCount : 1;
            for (int row = 0; row < count; row++) {
                stored.add(new Transaction(BANK, ACCOUNT, noon(dates.get(day)) - row * 1_000L,
                    100_000L + row, "filtered-expansion-" + day + "-" + row, null));
            }
        }
        return stored;
    }

    private static long noon(CalDate date) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(date.year, date.month - 1, date.day, 12, 0, 0);
        return calendar.getTimeInMillis();
    }

    private static Set<String> dayKeys(List<CalDate> dates) {
        Set<String> keys = new LinkedHashSet<>();
        for (CalDate date : dates) keys.add(date.key());
        return keys;
    }

    private static String monthKey(CalDate date) {
        return date.year + "/" + date.month;
    }

    private static Set<String> singleton(String value) {
        Set<String> set = new LinkedHashSet<>();
        set.add(value);
        return set;
    }

    private static int clockTimes(View view) {
        int count = view instanceof TextView
            && ((TextView) view).getText().toString().matches("\\d{1,2}:\\d{2}") ? 1 : 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) count += clockTimes(group.getChildAt(i));
        }
        return count;
    }

    private static View find(View view, Predicate<View> condition) {
        if (condition.test(view)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), condition);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void collectTagged(View view, String tag, List<View> out) {
        if (tag.equals(view.getTag())) out.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectTagged(group.getChildAt(i), tag, out);
            }
        }
    }

    private static ScrollView scroll(HistoryActivity activity) {
        ScrollView scroll = (ScrollView) find(activity.getWindow().getDecorView(),
            view -> view instanceof ScrollView);
        assertNotNull("history must expose its production scroll container", scroll);
        return scroll;
    }

    private static boolean laidOut(ScrollView scroll) {
        return scroll.isLaidOut() && !scroll.isLayoutRequested() && scroll.getHeight() > 0
            && scroll.getChildCount() == 1 && scroll.getChildAt(0).isLaidOut()
            && !scroll.getChildAt(0).isLayoutRequested();
    }

    private static int scrollRange(ScrollView scroll) {
        if (scroll.getChildCount() == 0) return 0;
        return Math.max(0, scroll.getChildAt(0).getHeight() - scroll.getHeight()
            + scroll.getPaddingTop() + scroll.getPaddingBottom());
    }

    private void awaitLayoutFrame() throws Exception {
        CountDownLatch frame = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            ScrollView scroll = scroll(activity);
            scroll.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        scroll.getViewTreeObserver().removeOnPreDrawListener(this);
                        frame.countDown();
                        return true;
                    }
                });
            scroll.requestLayout();
            scroll.invalidate();
        });
        assertTrue("history must complete its requested layout frame",
            frame.await(10, TimeUnit.SECONDS));
    }

    private void await(Predicate<HistoryActivity> condition, String what) {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        do {
            boolean[] ready = {false};
            scenario.onActivity(activity -> ready[0] = condition.test(activity));
            if (ready[0]) return;
            SystemClock.sleep(25L);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Timed out waiting for " + what);
    }
}
