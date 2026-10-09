package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded history projection parity and residual compatibility tests. */
@RunWith(AndroidJUnit4.class)
public class HistoryReaderTest {
    private static final String BANK = "Mellat";
    private static final String ACCOUNT = "111";
    private static final long DAY = 86_400_000L;
    private Context context;
    private String originalLanguage;
    private String originalCurrency;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BalanceData.reset(context, true);
        originalLanguage = LocaleHelper.currentTag(context);
        originalCurrency = CurrencyHelper.currency(context);
        LocaleHelper.setLanguage(context, "en");
        CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_RIAL);
    }

    @After public void tearDown() {
        LocaleHelper.setLanguage(context, originalLanguage);
        CurrencyHelper.setCurrency(context, originalCurrency);
        BalanceData.reset(context, true);
    }

    @Test public void summary_matchesHistoryFiltersAndTotals_forPageSizesOneAndTwo() throws Exception {
        long newest = epoch(2026, 9, 15);
        List<Transaction> stored = Arrays.asList(
            new Transaction(BANK, ACCOUNT, newest - 2 * DAY, 30L, null, "a", null),
            new Transaction("Saman", ACCOUNT, newest - DAY, 500L, null, "b", null),
            new Transaction(BANK, ACCOUNT, newest, -10L, null, "c", null),
            new Transaction(BANK, "222", newest, 70L, null, "d", null),
            // Store order is deliberately not chronological.
            new Transaction(BANK, ACCOUNT, newest - 3 * DAY, -4L, null, "e", null));
        assertTrue(BalanceData.writeTransactions(context, stored));

        HistoryActivity.Filter filter = new HistoryActivity.Filter(
            HistoryActivity.DIR_ALL, HistoryActivity.RANGE_CUSTOM,
            CalDate.fromGregorian(2026, 9, 12, false),
            CalDate.fromGregorian(2026, 9, 15, false));
        List<Transaction> scope = HistoryActivity.filterByAccount(
            HistoryActivity.filterByBank(stored, BANK), ACCOUNT);
        List<Transaction> expected = HistoryActivity.applyFilters(scope, filter, false);
        HistoryActivity.Lists expectedLists = HistoryActivity.buildLists(expected, false);
        String requestedDay = CalDate.fromGregorian(2026, 9, 15, false).key();

        for (int pageSize : new int[]{1, 2}) {
            HistoryReader.Request request = new HistoryReader.Request(false, pageSize, BANK,
                ACCOUNT, filter, "", null, Collections.singleton(requestedDay), 1, 4);
            HistoryReader.Result result = HistoryReader.summary(context, request);

            assertFalse(result.residualsComplete);
            assertEquals(expectedLists.total, result.total);
            assertEquals(expected.size(), result.movementCount);
            assertEquals(expectedLists.years.get(0).sum, result.years.get(0).stats.sum);
            assertEquals(expectedLists.years.get(0).months.get(0).sum,
                result.years.get(0).months.get(0).stats.sum);
            assertEquals(1, result.requestedDayRows.get(requestedDay).transactions.size());
            assertEquals(-10L, result.requestedDayRows.get(requestedDay).transactions.get(0).amount);
            assertTrue(result.requestedDayRows.containsKey(requestedDay));
        }
    }

    @Test public void reference_preservesResidualOrderAndHistoryFilterPipeline() throws Exception {
        long first = epoch(2026, 9, 10);
        List<Transaction> stored = Arrays.asList(
            new Transaction(BANK, ACCOUNT, first + 2 * DAY, -5L, 283L, "later", null),
            new Transaction("Saman", ACCOUNT, first + DAY, 900L, 999L, "other-bank", null),
            new Transaction(BANK, ACCOUNT, first, -30L, 290L, "first", null));
        assertTrue(BalanceData.writeTransactions(context, stored));

        HistoryActivity.Filter filter = new HistoryActivity.Filter(
            HistoryActivity.DIR_WITHDRAWAL, HistoryActivity.RANGE_ALL, null, null);
        HistoryReader.Request request = new HistoryReader.Request(false, 1, BANK, ACCOUNT,
            filter, "", null, null, 0, 0);
        HistoryReader.Result result = HistoryReader.reference(context, request);

        List<Transaction> scope = HistoryActivity.filterByAccount(
            HistoryActivity.filterByBank(stored, BANK), ACCOUNT);
        List<Residual> expectedResiduals = HistoryActivity.applyResidualFilters(
            Residual.between(scope), filter, false);
        List<Transaction> expectedTransactions = HistoryActivity.applyFilters(scope, filter, false);
        HistoryActivity.Lists expectedLists = HistoryActivity.buildLists(expectedTransactions,
            expectedResiduals, false);

        assertEquals(expectedTransactions.size(), result.visibleTransactions().size());
        assertEquals(expectedResiduals.size(), result.visibleResiduals().size());
        assertEquals(-2L, result.visibleResiduals().get(0).amount);
        assertEquals(expectedLists.total, result.total);
        assertEquals(expectedLists.years.get(0).n, result.years.get(0).stats.movementCount);
    }

    @Test public void summary_usesPointMetadataLookupsAfterAliasMigration() throws Exception {
        Transaction old = new Transaction(BANK, ACCOUNT, epoch(2026, 9, 15), 10L, null, null, null);
        Transaction current = new Transaction(BANK, ACCOUNT, epoch(2026, 9, 15), 10L,
            null, "new-signature", "new-content");
        assertTrue(BalanceData.writeTransactions(context, Collections.singletonList(current)));

        String oldKey = BalanceData.noteKey(old);
        String newKey = BalanceData.noteKey(current);
        assertTrue(MetadataStore.setNote(context, oldKey, "groceries"));
        assertTrue(MetadataStore.setTags(context, oldKey, Collections.singletonList("Food")));
        Map<String, String> alias = new LinkedHashMap<>();
        alias.put(oldKey, newKey);
        assertTrue(MetadataStore.migrateKeys(context, alias));

        HistoryReader.Request search = new HistoryReader.Request(false, 2, BANK, ACCOUNT,
            HistoryActivity.Filter.ALL, "groceries", null, null, 0, 0);
        HistoryReader.Result searched = HistoryReader.summary(context, search);
        assertEquals(1L, searched.movementCount);

        HistoryReader.Request tags = new HistoryReader.Request(false, 2, BANK, ACCOUNT,
            HistoryActivity.Filter.ALL, "", Collections.singletonList("food"), null, 0, 0);
        assertEquals(1L, HistoryReader.summary(context, tags).movementCount);
    }

    @Test public void summary_reportsResidualLimitationAndDoesNotPretendFullRowsExist() throws Exception {
        assertTrue(BalanceData.writeTransactions(context, Collections.singletonList(
            new Transaction(BANK, ACCOUNT, epoch(2026, 9, 10), 1L, null, "one", null))));
        HistoryReader.Result result = HistoryReader.summary(context,
            HistoryReader.Request.all(false, 1));

        assertFalse(result.residualsComplete);
        assertTrue(result.limitation.contains("does not calculate residuals"));
        try {
            result.visibleTransactions();
            fail("summary must not expose an unbounded transaction list");
        } catch (UnsupportedOperationException expected) {
            // Explicit API limitation is the contract.
        }
        try {
            result.visibleResiduals();
            fail("summary must not claim residual parity");
        } catch (UnsupportedOperationException expected) {
            // Explicit API limitation is the contract.
        }
    }

    @Test public void summaryWithResiduals_streamsDateOrderedResidualsIntoTotals() throws Exception {
        long first = epoch(2026, 9, 10);
        List<Transaction> stored = Arrays.asList(
            new Transaction(BANK, ACCOUNT, first + 2 * DAY, -5L, 283L, "later", null),
            new Transaction(BANK, ACCOUNT, first, -30L, 290L, "first", null));
        assertTrue(BalanceData.writeTransactions(context, stored));
        HistoryActivity.Filter filter = new HistoryActivity.Filter(
            HistoryActivity.DIR_WITHDRAWAL, HistoryActivity.RANGE_ALL, null, null);
        HistoryReader.Request request = new HistoryReader.Request(false, 1, BANK, ACCOUNT,
            filter, "", null, null, 0, 0);

        HistoryReader.Result result = HistoryReader.summaryWithResiduals(context, request);
        HistoryReader.Result reference = HistoryReader.reference(context, request);
        assertTrue(result.residualsComplete);
        assertEquals(reference.total, result.total);
        assertEquals(reference.movementCount, result.movementCount);
        assertEquals(-37L, result.total);
    }

    @Test public void summaryConversionKeepsAggregatesAndOnlyRequestedDayRows() throws Exception {
        long newer = epoch(2026, 9, 15);
        List<Transaction> stored = Arrays.asList(
            new Transaction(BANK, ACCOUNT, newer - DAY, 30L, null, "older", null),
            new Transaction(BANK, ACCOUNT, newer, -10L, null, "newer", null));
        assertTrue(BalanceData.writeTransactions(context, stored));
        String requestedDay = CalDate.fromGregorian(2026, 9, 15, false).key();
        HistoryReader.Request request = new HistoryReader.Request(false, 1, BANK, ACCOUNT,
            HistoryActivity.Filter.ALL, "", null, Collections.singleton(requestedDay), 64, 128);

        HistoryReader.Result result = HistoryReader.summaryWithResiduals(context, request);
        HistoryActivity.Lists lists = HistoryActivity.listsFromSummary(result);

        assertEquals(result.total, lists.total);
        assertEquals(result.movementCount, lists.years.get(0).n);
        HistoryActivity.DayGroup selected = lists.years.get(0).months.get(0).days.get(0);
        HistoryActivity.DayGroup collapsed = lists.years.get(0).months.get(0).days.get(1);
        assertEquals(1, selected.txs.size());
        assertEquals(1, selected.lines.size());
        assertTrue(collapsed.txs.isEmpty());
        assertTrue(collapsed.lines.isEmpty());
    }

    @Test public void requestedDayRows_pageBeyondInitialBatchIsReachable() throws Exception {
        long date = epoch(2026, 9, 15);
        List<Transaction> transactions = Arrays.asList(
            new Transaction(BANK, ACCOUNT, date - 1_000L, 2L, null, "two", null),
            // Store order is deliberately opposite the timeline's newest-first order.
            new Transaction(BANK, ACCOUNT, date, 1L, null, "one", null),
            new Transaction(BANK, ACCOUNT, date - 2_000L, 3L, null, "three", null));
        assertTrue(BalanceData.writeTransactions(context, transactions));
        String key = CalDate.fromGregorian(2026, 9, 15, false).key();
        HistoryReader.Request request = new HistoryReader.Request(false, 1, BANK, ACCOUNT,
            HistoryActivity.Filter.ALL, "", null, Collections.singleton(key), 1, 1);
        HistoryReader.Result result = HistoryReader.summary(context, request);
        try {
            HistoryReader.DayRows first = result.requestedDayRows.get(key);
            assertEquals(1, first.rows.size());
            assertEquals(1L, first.transactions.get(0).amount);
            assertTrue(first.hasMore);

            HistoryReader.DayRows second = HistoryReader.readPage(context, result, key,
                first.lastCursor, false, 1);
            assertEquals(1, second.rows.size());
            assertEquals(2L, second.transactions.get(0).amount);
            assertTrue(second.hasPrevious);

            HistoryReader.DayRows third = HistoryReader.readPage(context, result, key,
                second.lastCursor, false, 1);
            assertEquals(1, third.rows.size());
            assertEquals(3L, third.transactions.get(0).amount);
            assertFalse(third.hasMore);

            HistoryReader.DayRows back = HistoryReader.readPage(context, result, key,
                third.firstCursor, true, 1);
            assertEquals(2L, back.transactions.get(0).amount);
            assertTrue(back.hasMore);
        } finally {
            result.close();
        }
    }

    @Test public void moreThan128RowsAndMoreThan64DaysKeepCompleteTotalsAndPages() throws Exception {
        long newest = epoch(2026, 9, 15);
        List<Transaction> transactions = new java.util.ArrayList<>();
        for (int i = 0; i < 130; i++) {
            transactions.add(new Transaction(BANK, ACCOUNT, newest - i * 1_000L,
                i + 1L, null, "dense" + i, null));
        }
        // Add seventy distinct days to exercise the header/summary path beyond the old day cap.
        for (int day = 1; day <= 70; day++) {
            transactions.add(new Transaction(BANK, ACCOUNT, newest - day * DAY,
                10_000L + day, null, "day" + day, null));
        }
        assertTrue(BalanceData.writeTransactions(context, transactions));

        String denseDay = CalDate.fromGregorian(2026, 9, 15, false).key();
        HistoryReader.Request request = new HistoryReader.Request(false, 2, BANK, ACCOUNT,
            HistoryActivity.Filter.ALL, "", null, Collections.singleton(denseDay), 64, 128);
        HistoryReader.Result result = HistoryReader.summary(context, request);
        try {
            assertEquals(200L, result.movementCount);
            assertTrue(result.daySummaries.size() > 64);
            HistoryReader.DayRows first = result.requestedDayRows.get(denseDay);
            assertEquals(128, first.rows.size());
            assertTrue(first.hasMore);
            HistoryReader.DayRows last = HistoryReader.readPage(context, result, denseDay,
                first.lastCursor, false, 128);
            assertEquals(2, last.rows.size());
            assertFalse(last.hasMore);
            assertEquals(1L, first.transactions.get(0).amount);
            assertEquals(130L, last.transactions.get(1).amount);
        } finally {
            result.close();
        }
    }

    @Test public void searchAcrossMoreThan64DaysKeepsEveryHeaderAndRemoteDayAccessible()
            throws Exception {
        long newest = epoch(2026, 9, 15);
        List<Transaction> transactions = new java.util.ArrayList<>();
        for (int day = 0; day < 70; day++) {
            transactions.add(new Transaction(BANK, ACCOUNT, newest - day * DAY,
                50L + day, null, "needle-" + day, null));
        }
        assertTrue(BalanceData.writeTransactions(context, transactions));
        Calendar oldestCalendar = Calendar.getInstance();
        oldestCalendar.setTimeInMillis(newest);
        oldestCalendar.add(Calendar.DAY_OF_YEAR, -69);
        String oldest = CalDate.fromGregorian(oldestCalendar.get(Calendar.YEAR),
            oldestCalendar.get(Calendar.MONTH) + 1, oldestCalendar.get(Calendar.DAY_OF_MONTH), false)
            .key();
        HistoryReader.Request request = new HistoryReader.Request(false, 1, BANK, ACCOUNT,
            HistoryActivity.Filter.ALL, "MELLAT", null, null, 64, 1);
        HistoryReader.Result result = HistoryReader.summary(context, request);
        try {
            assertEquals(70, result.daySummaries.size());
            int headers = 0;
            for (HistoryReader.YearSummary year : result.years) {
                for (HistoryReader.MonthSummary month : year.months) headers += month.days.size();
            }
            assertEquals(70, headers);
            HistoryReader.DayRows page = HistoryReader.readPage(context, result, oldest,
                null, false, 1);
            assertEquals(1, page.transactions.size());
            assertEquals(119L, page.transactions.get(0).amount);
        } finally {
            result.close();
        }
    }

    @Test public void summaryWithResiduals_reportsLoadingGapsAndBuildingInOrder() throws Exception {
        assertTrue(BalanceData.writeTransactions(context, Collections.singletonList(
            new Transaction(BANK, ACCOUNT, epoch(2026, 9, 10), 1L, null, "one", null))));
        List<Integer> stages = new ArrayList<>();
        WorkProgress progress = new WorkProgress() {
            @Override public void stage(int stageResId) { stages.add(stageResId); }
            @Override public void progress(long done, long total) { }
        };
        HistoryReader.Result result = HistoryReader.summaryWithResiduals(context,
            HistoryReader.Request.all(false, 1), progress);
        try {
            assertEquals(Arrays.asList(R.string.history_stage_loading, R.string.history_stage_gaps,
                R.string.history_stage_building), stages);
            assertTrue(result.residualsComplete);
        } finally {
            result.close();
        }
    }

    @Test public void summaryWithResiduals_overLimit_matchesInMemoryPath() throws Exception {
        // The store total pushes the summary past the in-memory residual bound, so the small
        // Saman scope below must still come back through the bounded external walk.
        long base = epoch(2026, 9, 1);
        List<Transaction> stored = new ArrayList<>(HistoryReader.MEMORY_RESIDUAL_LIMIT + 3);
        for (int i = 0; i <= HistoryReader.MEMORY_RESIDUAL_LIMIT; i++) {
            stored.add(new Transaction("Filler", "999", base + i, 1L, null));
        }
        long first = epoch(2026, 9, 10);
        Transaction saman1 = new Transaction("Saman", "222", first, -30L, 290L, "s1", null);
        Transaction saman2 = new Transaction("Saman", "222", first + 2 * DAY, -5L, 283L, "s2",
            null);
        stored.add(saman1);
        stored.add(saman2);
        assertTrue(BalanceData.writeTransactions(context, stored));

        List<Residual> expected = Residual.between(Arrays.asList(saman1, saman2));
        expected.sort((a, b) -> Long.compare(b.toDate, a.toDate));
        String day1 = CalDate.fromGregorian(2026, 9, 10, false).key();
        String day2 = CalDate.fromGregorian(2026, 9, 12, false).key();
        HistoryReader.Request request = new HistoryReader.Request(false, 256, "Saman", "222",
            HistoryActivity.Filter.ALL, "", null, Arrays.asList(day1, day2), 2, 128);
        HistoryReader.Result result = HistoryReader.summaryWithResiduals(context, request);

        assertTrue(result.residualsComplete);
        assertEquals(2L, result.movementCount);
        List<Residual> actual = new ArrayList<>();
        for (HistoryReader.DayRows rows : result.requestedDayRows.values()) {
            actual.addAll(rows.residuals);
        }
        actual.sort((a, b) -> Long.compare(b.toDate, a.toDate));
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).bank, actual.get(i).bank);
            assertEquals(expected.get(i).toDate, actual.get(i).toDate);
            assertEquals(expected.get(i).amount, actual.get(i).amount);
        }
        result.close();
    }

    private static long epoch(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day, 12, 0, 0);
        return calendar.getTimeInMillis();
    }
}
