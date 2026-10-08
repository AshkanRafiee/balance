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

    @Test public void requestedDayRows_failInsteadOfSilentlyTruncating() throws Exception {
        long date = epoch(2026, 9, 15);
        assertTrue(BalanceData.writeTransactions(context, Arrays.asList(
            new Transaction(BANK, ACCOUNT, date, 1L, null, "one", null),
            new Transaction(BANK, ACCOUNT, date - 1_000L, 2L, null, "two", null))));
        String key = CalDate.fromGregorian(2026, 9, 15, false).key();
        HistoryReader.Request request = new HistoryReader.Request(false, 1, BANK, ACCOUNT,
            HistoryActivity.Filter.ALL, "", null, Collections.singleton(key), 1, 1);
        try {
            HistoryReader.summary(context, request);
            fail("a bounded expanded day must not silently omit its second row");
        } catch (UnsupportedOperationException expected) {
            assertTrue(expected.getMessage().contains("row limit"));
        }
    }

    private static long epoch(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day, 12, 0, 0);
        return calendar.getTimeInMillis();
    }
}
