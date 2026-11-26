package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class CashStoreTest {
    private Context context;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        clear(context);
    }

    @After public void tearDown() {
        clear(context);
    }

    static void clear(Context context) {
        assertTrue(DataGeneration.dataPrefs(context).edit()
            .remove(CashStore.DATA).remove(CashStore.REVISION).commit());
        assertTrue(context.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE)
            .edit().remove(CashStore.ENABLED).commit());
    }

    @Test public void recountUpdatesBalanceWithoutCreatingOrChangingHistory() {
        long date = System.currentTimeMillis() - 60_000;
        assertTrue(CashStore.isEnabled(context));
        assertFalse(CashStore.read(context).hasData());
        assertEquals(0L, CashStore.visibleBalance(context).date);

        Transaction deposit = CashStore.add(context, date, 400);
        assertEquals(400L, CashStore.read(context).balance);
        long beforeCount = System.currentTimeMillis();
        CashStore.setBalance(context, 215);
        CashStore.State counted = CashStore.read(context);
        assertEquals(215L, counted.balance);
        assertTrue(counted.updatedAt >= beforeCount);
        assertEquals(deposit.sig, counted.transactions.get(0).sig);
        Transaction backdated = CashStore.add(context, date, -650);
        assertEquals(215L, CashStore.read(context).balance);
        assertEquals(counted.updatedAt, CashStore.read(context).updatedAt);
        assertEquals(2, CashStore.read(context).transactions.size());
        assertTrue(CashStore.remove(context, backdated));
        assertTrue(CashStore.remove(context, deposit));
        assertEquals(215L, CashStore.read(context).balance);
        assertTrue(CashStore.read(context).transactions.isEmpty());
    }

    @Test public void withdrawalsAndDeletingFundingCannotOverdraw() throws Exception {
        long date = System.currentTimeMillis() - 60_000;
        assertEquals(CashStore.RestoreResult.IMPORTED, CashStore.restoreData(context,
            "{\"countedAmount\":100,\"countedAt\":" + (date - 1_000)
                + ",\"updatedAt\":" + (date - 1_000) + ",\"movements\":[]}"));
        Transaction deposit = CashStore.add(context, date, 50);
        Transaction withdrawal = CashStore.add(context, date + 1, -120);
        String revision = CashStore.revision(context);
        try {
            CashStore.add(context, date + 2, -31);
            fail("withdrawal over the running balance");
        } catch (CashStore.InsufficientCashException expected) {}
        try {
            CashStore.remove(context, deposit);
            fail("deleting the deposit would overdraw the wallet");
        } catch (CashStore.InsufficientCashException expected) {}
        assertEquals(revision, CashStore.revision(context));
        assertEquals(30L, CashStore.read(context).balance);
        assertTrue(CashStore.remove(context, withdrawal));
        assertTrue(CashStore.remove(context, deposit));
        assertEquals(100L, CashStore.read(context).balance);
    }

    @Test public void simultaneousWithdrawalsAreValidatedAgainstCommittedBalance() throws Exception {
        long date = System.currentTimeMillis() - 60_000;
        CashStore.add(context, date, 50);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(workers.submit(() -> {
                    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                    try {
                        CashStore.add(context, date + 1, -40);
                        return true;
                    } catch (CashStore.InsufficientCashException expected) {
                        return false;
                    }
                }));
            }
            start.countDown();
            assertNotEquals(results.get(0).get(10, TimeUnit.SECONDS),
                results.get(1).get(10, TimeUnit.SECONDS));
            assertEquals(10L, CashStore.read(context).balance);
            assertEquals(2, CashStore.read(context).transactions.size());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test public void hiddenCashKeepsDistinctTransactionsAndAnnotations() throws Exception {
        long date = System.currentTimeMillis() - 60_000;
        Transaction first = CashStore.add(context, date, 50);
        Transaction second = CashStore.add(context, date, 50);
        List<Transaction> visible = new ArrayList<>();
        try {
            assertNotEquals(BalanceData.noteKey(first), BalanceData.noteKey(second));
            assertTrue(MetadataStore.setCurrentAnnotations(context, first, "first",
                Collections.singletonList("cash test")));
            CashStore.setEnabled(context, false);
            assertNull(CashStore.visibleBalance(context));
            CashStore.forEachVisible(context, visible::add);
            assertTrue(visible.isEmpty());
            assertEquals(100L, CashStore.read(context).balance);
            CashStore.setEnabled(context, true);
            assertNotNull(CashStore.visibleBalance(context));
            assertEquals("first", BalanceData.getNote(context, first));
            assertTrue(CashStore.remove(context, second));
            assertEquals(50L, CashStore.read(context).balance);
            assertEquals("first", BalanceData.getNote(context, first));
        } finally {
            CashStore.setEnabled(context, true);
            CashStore.remove(context, first);
        }
        assertNull(BalanceData.getNote(context, first));
    }

    @Test public void amountEntryHonorsCurrencyPrecisionGroupingAndRange() {
        String currency = CurrencyHelper.currency(context);
        try {
            CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_TOMAN);
            assertEquals(Long.valueOf(10_245L), CashStore.parseAmount(context, "۱٬۰۲۴٫۵"));
            assertEquals("0.1", CashStore.formatAmountForInput(context, 1L));
            assertNull(CashStore.parseAmount(context, "1.05"));
            assertNull(CashStore.parseAmount(context, "12,5"));
            assertNull(CashStore.parseAmount(context, "-10"));
            assertEquals(Long.valueOf(0), CashStore.parseAmount(context, "۰"));
            CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_RIAL);
            assertEquals(Long.valueOf(Long.MAX_VALUE),
                CashStore.parseAmount(context, Long.toString(Long.MAX_VALUE)));
            assertNull(CashStore.parseAmount(context, "9223372036854775808"));
            assertNull(CashStore.parseAmount(context, "2.5"));
        } finally {
            CurrencyHelper.setCurrency(context, currency);
        }
    }

    @Test public void oldCashCountUsesTheSameStaleSettingAndSurvivesBankReset() throws Exception {
        long date = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8);
        assertEquals(CashStore.RestoreResult.IMPORTED, CashStore.restoreData(context,
            "{\"countedAmount\":100,\"countedAt\":" + date + ",\"updatedAt\":" + date
                + ",\"movements\":[]}"));
        Transaction movement = CashStore.add(context, date - 1, -25);
        int previousStaleDays = BalanceData.getStaleDays(context);
        try {
            BalanceData.setStaleDays(context, 7);
            assertTrue(BalanceData.isStale(context, CashStore.visibleBalance(context).date));
            BalanceData.reset(context, false);
            assertEquals(100L, CashStore.visibleBalance(context).amount);
            assertEquals(movement.sig, CashStore.read(context).transactions.get(0).sig);
            BalanceData.setStaleDays(context, 0);
            assertFalse(BalanceData.isStale(context, CashStore.visibleBalance(context).date));
        } finally {
            BalanceData.setStaleDays(context, previousStaleDays);
        }
    }
}
