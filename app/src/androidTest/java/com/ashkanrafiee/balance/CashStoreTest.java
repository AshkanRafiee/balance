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
            assertNull(CashStore.parseAmount(context, String.join("", Collections.nCopies(150, "9"))));
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

    @Test public void previewExplainsBalanceEffectAndHasNoSideEffects() throws Exception {
        long date = System.currentTimeMillis() - 60_000;
        CashStore.setBalance(context, 30);
        String revision = CashStore.revision(context);
        CashStore.Preview older = CashStore.preview(context, date, -40, null);
        assertFalse(older.affectsBalance);
        assertFalse(older.insufficient);
        assertEquals(30L, older.resultingBalance);
        assertEquals(revision, CashStore.revision(context));
        assertTrue(CashStore.read(context).transactions.isEmpty());

        clear(context);
        CashStore.Preview deposit = CashStore.preview(context, date, 10, null);
        assertTrue(deposit.affectsBalance);
        assertEquals(0L, deposit.currentBalance);
        assertEquals(10L, deposit.resultingBalance);
        CashStore.add(context, date, 10);
        CashStore.Preview withdrawal = CashStore.preview(context, date + 1, -10, null);
        assertEquals(10L, withdrawal.currentBalance);
        assertEquals(0L, withdrawal.resultingBalance);
    }

    @Test public void optionalNoteIsAvailableThroughNormalAnnotationsAndInvalidSaveLeavesNone()
            throws Exception {
        long date = System.currentTimeMillis() - 60_000;
        java.util.Map<String, String> previousNotes = BalanceData.readNotes(context);
        try {
            CashStore.add(context, date, -10, "must not be saved");
            fail("withdrawal with no cash");
        } catch (CashStore.InsufficientCashException expected) {}
        assertEquals(previousNotes, BalanceData.readNotes(context));
        Transaction deposit = CashStore.add(context, date, 10, " lunch ");
        try {
            assertEquals("lunch", BalanceData.getNote(context, deposit));
            Transaction edited = CashStore.update(context, deposit, date, 20);
            assertEquals("lunch", BalanceData.getNote(context, edited));
            assertTrue(CashStore.remove(context, edited));
            assertEquals(previousNotes, BalanceData.readNotes(context));
        } finally {
            MetadataStore.setAnnotations(context, deposit, null, Collections.emptyList());
        }
    }

    @Test public void editCanReplaceOrClearAnnotationsWithoutChangingTheMovement() throws Exception {
        long date = System.currentTimeMillis() - 60_000;
        CashStore.Annotation added = new CashStore.Annotation("", Collections.singletonList("food"));
        Transaction transaction = CashStore.add(context, date, 10, added);
        try {
            assertEquals(Collections.singletonList("food"), BalanceData.getTags(context, transaction));
            assertNull(BalanceData.getNote(context, transaction));
            CashStore.Annotation replacement = new CashStore.Annotation("Lunch",
                Collections.singletonList("home"));
            CashStore.update(context, transaction, date, 10, added, replacement);
            assertEquals("Lunch", BalanceData.getNote(context, transaction));
            assertEquals(Collections.singletonList("home"), BalanceData.getTags(context, transaction));
            try {
                CashStore.update(context, transaction, date, 10, added,
                    new CashStore.Annotation("", Collections.emptyList()));
                fail("stale annotations must not overwrite the saved edit");
            } catch (CashStore.StaleCashTransactionException expected) {}
            CashStore.update(context, transaction, date, 10, replacement,
                new CashStore.Annotation("", Collections.emptyList()));
            assertNull(BalanceData.getNote(context, transaction));
            assertTrue(BalanceData.getTags(context, transaction).isEmpty());
            assertEquals(transaction.sig, CashStore.read(context).transactions.get(0).sig);
            assertEquals(10L, CashStore.read(context).balance);
        } finally {
            CashStore.remove(context, transaction);
        }
    }

    @Test public void snapshotCacheDoesNotMaskReplacementOrReadForCancelledPreview() {
        CashStore.setBalance(context, 30);
        CashStore.State original = CashStore.read(context);
        assertTrue(original == CashStore.read(context));
        CashStore.setBalance(context, 45);
        assertEquals(45L, CashStore.read(context).balance);
        assertTrue(original != CashStore.read(context));

        assertTrue(DataGeneration.dataPrefs(context).edit()
            .putString(CashStore.DATA, "invalid").commit());
        try {
            CashStore.preview(context, System.currentTimeMillis(), 10, null, () -> false);
            fail("superseded preview should not read the ledger");
        } catch (java.util.concurrent.CancellationException expected) {}
        try {
            CashStore.read(context);
            fail("cached state must not mask a changed encrypted payload");
        } catch (IllegalStateException expected) {}
    }

    @Test public void shortfallPreviewUsesChronologicalBalanceAndSuggestsFundingBeforeDebit() {
        long date = System.currentTimeMillis() - 60_000;
        CashStore.add(context, date + 2_000, 100);
        CashStore.Preview preview = CashStore.preview(context, date, -40, null);
        assertTrue(preview.insufficient);
        assertEquals(100L, preview.currentBalance);
        assertEquals(0L, preview.availableAtShortfall);
        assertEquals(40L, preview.requiredAtShortfall);
        assertEquals(date, preview.shortfallDate);
        assertEquals(40L, preview.shortfall);
        assertTrue(preview.recoveryDate < date);

        CashStore.add(context, preview.recoveryDate, preview.shortfall);
        CashStore.Preview funded = CashStore.preview(context, date, -40, null);
        assertFalse(funded.insufficient);
        CashStore.add(context, date, -40);
        assertEquals(funded.resultingBalance, CashStore.read(context).balance);
    }

    @Test public void editPreservesAnnotationsAndRejectsStaleOrOverdrawingChanges() throws Exception {
        long date = System.currentTimeMillis() - 60_000;
        Transaction deposit = CashStore.add(context, date, 50);
        Transaction withdrawal = CashStore.add(context, date + 1, -30);
        assertTrue(MetadataStore.setCurrentAnnotations(context, withdrawal, "lunch",
            Collections.singletonList("food")));
        try {
            try {
                CashStore.update(context, deposit, date, 10);
                fail("changing the deposit would overdraw the cash balance");
            } catch (CashStore.InsufficientCashException expected) {}
            assertEquals(20L, CashStore.read(context).balance);

            CashStore.Preview preview = CashStore.preview(context, date + 2, 20, withdrawal);
            Transaction edited = CashStore.update(context, withdrawal, date + 2, 20);
            assertEquals(withdrawal.sig, edited.sig);
            assertEquals(date + 2, edited.date);
            assertEquals(20L, edited.amount);
            assertEquals(preview.resultingBalance, CashStore.read(context).balance);
            assertEquals(70L, preview.resultingBalance);
            assertEquals(2, CashStore.read(context).transactions.size());
            assertEquals("lunch", BalanceData.getNote(context, edited));
            assertEquals(Collections.singletonList("food"), BalanceData.getTags(context, edited));

            try {
                CashStore.update(context, withdrawal, date + 3, -10);
                fail("stale snapshot should not overwrite the saved edit");
            } catch (CashStore.StaleCashTransactionException expected) {}
        } finally {
            MetadataStore.setAnnotations(context, withdrawal, null, Collections.emptyList());
        }
    }

    @Test public void editingAcrossBalanceAdjustmentAppliesOrRemovesOnlyTheActiveMovement()
            throws Exception {
        long cutoff = System.currentTimeMillis() - 60_000;
        assertEquals(CashStore.RestoreResult.IMPORTED, CashStore.restoreData(context,
            "{\"countedAmount\":100,\"countedAt\":" + cutoff + ",\"updatedAt\":" + cutoff
                + ",\"movements\":[]}"));
        Transaction movement = CashStore.add(context, cutoff, -70);
        assertEquals(100L, CashStore.read(context).balance);
        CashStore.Preview preview = CashStore.preview(context, cutoff + 1, -70, movement);
        assertTrue(preview.affectsBalance);
        assertEquals(30L, preview.resultingBalance);
        Transaction updated = CashStore.update(context, movement, cutoff + 1, -70);
        assertEquals(30L, CashStore.read(context).balance);
        preview = CashStore.preview(context, cutoff, -70, updated);
        assertTrue(preview.affectsBalance);
        assertEquals(100L, preview.resultingBalance);
        CashStore.update(context, updated, cutoff, -70);
        assertEquals(100L, CashStore.read(context).balance);
    }
}
