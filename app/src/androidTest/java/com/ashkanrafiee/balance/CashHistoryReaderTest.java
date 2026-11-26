package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

@RunWith(AndroidJUnit4.class)
public class CashHistoryReaderTest {
    private static final String BANK = "Mellat";
    private static final String ACCOUNT = "111";
    private Context context;
    private boolean originalCashEnabled;
    private long noon;
    private CalDate date;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalCashEnabled = CashStore.isEnabled(context);
        BalanceData.reset(context, true);
        clearCash();
        CashStore.setEnabled(context, true);

        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_MONTH, -1);
        yesterday.set(Calendar.HOUR_OF_DAY, 12);
        yesterday.set(Calendar.MINUTE, 0);
        yesterday.set(Calendar.SECOND, 0);
        yesterday.set(Calendar.MILLISECOND, 0);
        noon = yesterday.getTimeInMillis();
        date = CalDate.of(yesterday.get(Calendar.YEAR), yesterday.get(Calendar.MONTH) + 1,
            yesterday.get(Calendar.DAY_OF_MONTH));
    }

    @After public void tearDown() {
        HistoryWarmup.invalidate();
        try {
            clearCash();
        } finally {
            CashStore.setEnabled(context, originalCashEnabled);
            BalanceData.reset(context, true);
        }
    }

    private void clearCash() {
        synchronized (BalanceData.class) {
            assertTrue(DataGeneration.dataPrefs(context).edit()
                .remove(CashStore.DATA).remove(CashStore.REVISION).commit());
        }
    }

    @Test public void mixedHistoryPreservesBankGapsAndPagesCashByDate() throws Exception {
        seedBankGap();
        Transaction deposit = CashStore.add(context, noon + 1000, 50L);
        Transaction withdrawal = CashStore.add(context, noon + 2000, -8L);
        String day = date.key();
        HistoryReader.Request request = HistoryReader.Request.all(false, 1)
            .withRequestedDays(Collections.singleton(day), 1);

        try (HistoryReader.Result summary = HistoryReader.summaryWithResiduals(context, request);
                HistoryReader.Result reference = HistoryReader.reference(context, request);
                HistoryReader.Result withoutGaps = HistoryReader.summary(context, request)) {
            assertEquals(4L, summary.movementCount);
            assertEquals(5L, summary.total);
            assertEquals(50L, summary.totalDeposits);
            assertEquals(-45L, summary.totalWithdrawals);
            assertEquals(1L, summary.residualCount);
            assertEquals(4L, summary.daySummaries.get(day).stats.movementCount);
            assertEquals(5L, summary.daySummaries.get(day).stats.sum);
            assertEquals(summary.total, reference.total);
            assertEquals(summary.movementCount, reference.visibleTransactions().size());
            assertEquals(1, reference.visibleResiduals().size());
            assertEquals(BANK, reference.visibleResiduals().get(0).bank);
            assertEquals(-2L, reference.visibleResiduals().get(0).amount);
            assertEquals(7L, withoutGaps.total);

            HistoryReader.DayRows gapPage = summary.requestedDayRows.get(day);
            assertEquals(-2L, gapPage.residuals.get(0).amount);
            assertTrue(gapPage.hasMore);
            HistoryReader.DayRows closingPage = next(summary, gapPage);
            assertEquals("close", closingPage.transactions.get(0).sig);
            HistoryReader.DayRows withdrawalPage = next(summary, closingPage);
            assertEquals(withdrawal.sig, withdrawalPage.transactions.get(0).sig);
            assertNull(withdrawalPage.transactions.get(0).account);
            assertNull(withdrawalPage.transactions.get(0).balance);
            HistoryReader.DayRows depositPage = next(summary, withdrawalPage);
            assertEquals(deposit.sig, depositPage.transactions.get(0).sig);
            HistoryReader.DayRows openingPage = next(summary, depositPage);
            assertEquals("open", openingPage.transactions.get(0).sig);
            assertFalse(openingPage.hasMore);
        }
    }

    @Test public void cashScopeSupportsTagsSearchDirectionAndDateButHasNoAccount() throws Exception {
        seedBankGap();
        CashStore.add(context, noon, 50L);
        Transaction withdrawal = CashStore.add(context, noon + 1000, -8L);
        assertTrue(MetadataStore.setAnnotations(context, withdrawal, "Lunch at the park",
            Collections.singletonList("Food")));

        try (HistoryReader.Result bank = summary(BANK, ACCOUNT);
                HistoryReader.Result cash = summary(CashStore.KEY, null);
                HistoryReader.Result cashWithBankAccount = summary(CashStore.KEY, ACCOUNT)) {
            assertEquals(2L, bank.movementCount);
            assertEquals(-37L, bank.total);
            assertEquals(2L, cash.movementCount);
            assertEquals(42L, cash.total);
            assertEquals(0L, cash.residualCount);
            assertEquals(0L, cashWithBankAccount.movementCount);
        }

        HistoryActivity.Filter filter = new HistoryActivity.Filter(HistoryActivity.DIR_WITHDRAWAL,
            HistoryActivity.RANGE_CUSTOM, date, date);
        HistoryReader.Request request = new HistoryReader.Request(false, 1, CashStore.KEY, null,
            filter, "lunch park", Collections.singleton("food"),
            Collections.singleton(date.key()), 1, 1);
        try (HistoryReader.Result filtered = HistoryReader.summaryWithResiduals(context, request)) {
            assertEquals(1L, filtered.movementCount);
            assertEquals(-8L, filtered.total);
            HistoryReader.DayRows page = filtered.requestedDayRows.get(date.key());
            assertEquals(withdrawal.sig, page.transactions.get(0).sig);
            assertEquals("Lunch at the park",
                page.metadata.get(BalanceData.noteKey(withdrawal)).note);
        }
    }

    @Test public void cashChangesInvalidateWarmHistoryAndDisablingHidesTheLedger() throws Exception {
        seedBankGap();
        CashStore.add(context, noon, 50L);
        HistoryWarmup.warmNow(context);
        HistoryReader.RevisionStamp before;
        try (HistoryReader.Result warm = takeDefault()) {
            assertNotNull(warm);
            assertEquals(13L, warm.total);
            before = warm.revisionStamp;
        }

        CashStore.add(context, noon + 1000, 20L);
        try (HistoryReader.Result stale = takeDefault()) {
            assertNull(stale);
        }
        HistoryWarmup.warmNow(context);
        HistoryReader.RevisionStamp added;
        try (HistoryReader.Result warm = takeDefault()) {
            assertNotNull(warm);
            assertEquals(33L, warm.total);
            added = warm.revisionStamp;
            assertNotEquals(before.cash, added.cash);
            assertEquals(before.transactions, added.transactions);
            assertEquals(before.metadata, added.metadata);
        }

        CashStore.setEnabled(context, false);
        try (HistoryReader.Result stale = takeDefault()) {
            assertNull(stale);
        }
        try (HistoryReader.Result hidden = summary(null, null);
                HistoryReader.Result hiddenCash = summary(CashStore.KEY, null)) {
            assertNotEquals(added.cash, hidden.revisionStamp.cash);
            assertEquals(-37L, hidden.total);
            assertEquals(2L, hidden.movementCount);
            assertEquals(0L, hiddenCash.movementCount);
            assertTrue(hiddenCash.daySummaries.isEmpty());
        }
        CashStore.setEnabled(context, true);
        try (HistoryReader.Result visible = summary(null, null)) {
            assertEquals(33L, visible.total);
            assertEquals(4L, visible.movementCount);
        }
    }

    @Test public void editInvalidatesWarmHistoryAndMovesTheSameEntryToItsNewDay() throws Exception {
        Transaction original = CashStore.add(context, noon, 50L);
        HistoryWarmup.warmNow(context);
        Calendar previousDay = Calendar.getInstance();
        previousDay.setTimeInMillis(noon);
        previousDay.add(Calendar.DAY_OF_MONTH, -1);
        long yesterday = previousDay.getTimeInMillis();
        Transaction edited = CashStore.update(context, original, yesterday, 35L);
        try (HistoryReader.Result stale = takeDefault();
                HistoryReader.Result cash = summary(CashStore.KEY, null)) {
            assertNull(stale);
            assertEquals(1L, cash.movementCount);
            assertEquals(35L, cash.total);
            assertEquals(1, cash.daySummaries.size());
            String day = CalDate.of(previousDay.get(Calendar.YEAR),
                previousDay.get(Calendar.MONTH) + 1, previousDay.get(Calendar.DAY_OF_MONTH)).key();
            HistoryReader.DayRows rows = HistoryReader.readPage(context, cash, day, null, false, 1);
            assertEquals(original.sig, edited.sig);
            assertEquals(edited.sig, rows.transactions.get(0).sig);
            assertEquals(yesterday, rows.transactions.get(0).date);
        }
    }

    private void seedBankGap() {
        assertTrue(BalanceData.writeTransactions(context, Arrays.asList(
            new Transaction(BANK, ACCOUNT, noon, -30L, 290L, "open", null),
            new Transaction(BANK, ACCOUNT, noon + 2000, -5L, 283L, "close", null))));
    }

    private HistoryReader.Result summary(String bank, String account) throws Exception {
        return HistoryReader.summaryWithResiduals(context, new HistoryReader.Request(false, 1,
            bank, account, HistoryActivity.Filter.ALL, "", null, null, 0, 0));
    }

    private HistoryReader.DayRows next(HistoryReader.Result result, HistoryReader.DayRows page)
            throws Exception {
        return HistoryReader.readPage(context, result, page.key, page.lastCursor, false, 1);
    }

    private HistoryReader.Result takeDefault() {
        return HistoryWarmup.take((String) null, null, HistoryActivity.Filter.ALL, "",
            Collections.emptyList(), RegionHelper.isIran(context), context);
    }
}
