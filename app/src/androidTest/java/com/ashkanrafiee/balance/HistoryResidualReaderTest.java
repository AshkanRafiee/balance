package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Field-for-field parity tests for the disk-backed residual reader. */
@RunWith(AndroidJUnit4.class)
public class HistoryResidualReaderTest {
    private static final String MELLAT = "Mellat";
    private static final String SADERAT = "Saderat";
    private static final long DAY = 86_400_000L;
    private Context context;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BalanceData.reset(context, true);
        assertTemporaryFilesRemoved();
    }

    @After public void tearDown() {
        BalanceData.reset(context, true);
        assertTemporaryFilesRemoved();
    }

    @Test public void pageSizesAndStoreOrder_matchResidualBetween_fieldForField() throws Exception {
        long d = epoch(2026, 9, 10);
        List<Transaction> stored = Arrays.asList(
            new Transaction(MELLAT, "1", d + 3 * DAY, -5L, 283L, "late", null),
            new Transaction(SADERAT, "9", d + DAY, 7L, 407L, "other", null),
            new Transaction(MELLAT, "1", d, -30L, 300L, "open", null),
            new Transaction(MELLAT, "2", d, 4L, 40L, "second", null),
            new Transaction(SADERAT, "9", d, 0L, 410L, "other-open", null),
            new Transaction(MELLAT, "1", d + 2 * DAY, -5L, 285L, "close", null),
            new Transaction(MELLAT, "2", d + DAY, -10L, 25L, "second-close", null));
        assertTrue(BalanceData.writeTransactions(context, stored));

        List<Residual> expected = Residual.between(stored);
        assertTrue(expected.size() >= 3);
        for (int pageSize : new int[]{1, 2, 5}) {
            List<Residual> actual = HistoryResidualReader.read(context, null, null, pageSize, 20);
            assertResidualsEqual(expected, actual);
        }
    }

    @Test public void sameTimestampAndMultipleClosers_matchTheReferenceWalk() throws Exception {
        long d = epoch(2026, 9, 10);
        List<Transaction> stored = Arrays.asList(
            new Transaction(MELLAT, "1", d, 0L, 100L, "open", null),
            new Transaction(MELLAT, "1", d + DAY, -10L, 80L, "closer-a", null),
            new Transaction(MELLAT, "1", d + DAY, -5L, 75L, "closer-b", null),
            new Transaction(MELLAT, "1", d + 2 * DAY, -5L, null, "unbalanced", null),
            new Transaction(MELLAT, "1", d + 3 * DAY, 0L, 50L, "new-open", null),
            new Transaction(MELLAT, "1", d + 4 * DAY, -2L, 45L, "new-close", null),
            new Transaction(MELLAT, "2", d, 0L, 10L, "account-open", null),
            // No-balance movements at the opening instant are excluded, and every movement at
            // a single closing instant is included, even when the closer is first in store order.
            new Transaction(MELLAT, "2", d, 999L, null, "opening-companion", null),
            new Transaction(MELLAT, "2", d + DAY, 5L, 20L, "account-close", null),
            new Transaction(MELLAT, "2", d + DAY, 2L, null, "closing-companion", null),
            // Ambiguous first anchors leave the slot unanchored until a later sole balance.
            new Transaction(MELLAT, "3", d, 0L, 100L, "initial-a", null),
            new Transaction(MELLAT, "3", d, 0L, 200L, "initial-b", null),
            new Transaction(MELLAT, "3", d + DAY, 0L, 50L, "initial-reanchor", null),
            new Transaction(MELLAT, "3", d + 2 * DAY, 0L, 40L, "initial-close", null));
        assertTrue(BalanceData.writeTransactions(context, stored));

        List<Residual> expected = Residual.between(stored);
        assertEquals(3, expected.size());
        for (int pageSize : new int[]{1, 2, 4}) {
            assertResidualsEqual(expected,
                HistoryResidualReader.read(context, MELLAT, null, pageSize, 20));
        }
    }

    @Test public void balanceLessRowsAndOverflow_matchTheReferenceWalk() throws Exception {
        long d = epoch(2026, 9, 10);
        List<Transaction> stored = Arrays.asList(
            new Transaction(MELLAT, "1", d, 0L, 100L, "open", null),
            new Transaction(MELLAT, "1", d + DAY, 5L, null, "balance-less", null),
            new Transaction(MELLAT, "1", d + 2 * DAY, -5L, 20L, "close", null),
            new Transaction(MELLAT, "2", d, 0L, 0L, "overflow-open", null),
            new Transaction(MELLAT, "2", d + DAY, Long.MAX_VALUE, null, "max", null),
            new Transaction(MELLAT, "2", d + DAY, 1L, null, "overflow", null),
            new Transaction(MELLAT, "2", d + 2 * DAY, 0L, 0L, "overflow-close", null),
            new Transaction(MELLAT, "2", d + 3 * DAY, 0L, 100L, "late-open", null),
            new Transaction(MELLAT, "2", d + 4 * DAY, 0L, 90L, "late-close", null));
        assertTrue(BalanceData.writeTransactions(context, stored));

        assertResidualsEqual(Residual.between(stored),
            HistoryResidualReader.read(context, MELLAT, null, 2, 20));
    }

    @Test public void consumerFailure_isPropagatedAndAllTemporaryFilesAreRemoved() throws Exception {
        long d = epoch(2026, 9, 10);
        assertTrue(TransactionStore.replace(context, Arrays.asList(
            new Transaction(MELLAT, "1", d, 0L, 100L, "a", null),
            new Transaction(MELLAT, "1", d + DAY, 0L, 90L, "b", null),
            new Transaction(MELLAT, "1", d + 2 * DAY, 0L, 80L, "c", null))));
        final IOException expected = new IOException("stop");
        final int[] delivered = {0};
        try {
            HistoryResidualReader.forEach(context, null, null, 1, residual -> {
                assertStagingFilesExist();
                if (++delivered[0] == 2) throw expected;
            });
            fail("consumer failure must be propagated");
        } catch (IOException actual) {
            assertSame(expected, actual);
        }
        assertEquals(2, delivered[0]);
        assertTemporaryFilesRemoved();
    }

    @Test public void boundedRead_failsInsteadOfTruncating() throws Exception {
        long d = epoch(2026, 9, 10);
        List<Transaction> stored = Arrays.asList(
            new Transaction(MELLAT, "1", d, 0L, 100L, "a", null),
            new Transaction(MELLAT, "1", d + DAY, 0L, 90L, "b", null));
        assertTrue(BalanceData.writeTransactions(context, stored));
        try {
            HistoryResidualReader.read(context, MELLAT, "1", 1, 0);
            fail("bounded result must not truncate");
        } catch (HistoryResidualReader.ResultLimitExceededException expected) {
            assertTrue(expected.getMessage().contains("result limit"));
        }
        assertTemporaryFilesRemoved();
        assertResidualsEqual(Residual.between(stored),
            HistoryResidualReader.read(context, MELLAT, "1", 1, 1));
    }

    @Test public void multiPassExternalSort_matchesDateThenJavaKeyOrder() throws Exception {
        List<Transaction> stored = new ArrayList<>();
        // More than 8*8 residuals with pageSize 1 requires several fixed-fan-in merge passes.
        for (int i = 0; i < 83; i++) {
            String bank = i % 3 == 0 ? SADERAT : MELLAT;
            String account = "account-" + i;
            stored.add(new Transaction(bank, account, -100, 0, 1_000L, "open-" + i, null));
            stored.add(new Transaction(bank, account, i % 2 == 0 ? 100 : 200, 0,
                800L - i, "close-" + i, null));
        }
        // Java compares UTF-16 units, which differs from SQLite UTF-8 BINARY order for these keys.
        for (String bank : Arrays.asList("Unicode\uE000", "Unicode\uD800\uDC00")) {
            stored.add(new Transaction(bank, null, -100, 0, 1_000L, bank + "-open", null));
            stored.add(new Transaction(bank, null, 100, 0, 500L, bank + "-close", null));
        }
        Collections.shuffle(stored, new Random(341));
        assertTrue(TransactionStore.replace(context, stored));
        List<Residual> expected = Residual.between(stored);
        assertEquals(85, expected.size());
        for (int pageSize : new int[]{1, 2, 7, 256}) {
            List<Residual> actual = new ArrayList<>();
            HistoryResidualReader.forEach(context, null, null, pageSize, actual::add);
            assertResidualsEqual(expected, actual);
            assertTemporaryFilesRemoved();
        }
    }

    @Test public void scopeIsAppliedBeforeDetection_withIndependentBankAndAccountRestrictions()
            throws Exception {
        List<Transaction> stored = Arrays.asList(
            new Transaction(MELLAT, "1", 1, 0, 100L, "a", null),
            new Transaction(MELLAT, "1", 3, -10, 50L, "b", null),
            new Transaction(SADERAT, "1", 1, 0, 100L, "c", null),
            new Transaction(SADERAT, "1", 3, 10, 200L, "d", null),
            new Transaction(MELLAT, null, 1, 0, 100L, "e", null),
            new Transaction(MELLAT, null, 3, 0, 70L, "f", null),
            new Transaction(MELLAT, "", 1, 0, 100L, "g", null),
            new Transaction(MELLAT, "", 3, 0, 60L, "h", null));
        assertTrue(TransactionStore.replace(context, stored));
        String[][] scopes = {{null, null}, {MELLAT, null}, {MELLAT, "1"}, {null, "1"},
            {MELLAT, ""}, {"absent", null}, {null, "absent"}};
        for (String[] scope : scopes) {
            List<Transaction> scoped = new ArrayList<>();
            for (Transaction transaction : stored) {
                if (scope[0] != null && !scope[0].equals(transaction.bank)) continue;
                if (scope[1] != null && !scope[1].equals(transaction.account)) continue;
                scoped.add(transaction);
            }
            assertResidualsEqual(Residual.between(scoped),
                HistoryResidualReader.read(context, scope[0], scope[1], 1, 20));
        }
    }

    @Test public void lateRowsAreResorted_andRemovePreviouslyProvenResiduals() throws Exception {
        List<Transaction> stored = new ArrayList<>(Arrays.asList(
            new Transaction(MELLAT, "1", 10, -30, 290L, "first", null),
            new Transaction(MELLAT, "1", 30, -5, 283L, "last", null)));
        assertTrue(TransactionStore.replace(context, stored));
        List<Residual> before = HistoryResidualReader.read(context, null, null, 1, 10);
        assertResidualsEqual(Residual.between(stored), before);
        assertEquals(-2L, before.get(0).amount);

        Transaction late = new Transaction(MELLAT, "1", 20, -2, 288L, "late", null);
        stored.add(late);
        assertEquals(1L, TransactionStore.merge(context, visitor -> visitor.accept(late)));
        List<Residual> after = HistoryResidualReader.read(context, null, null, 1, 10);
        assertResidualsEqual(Residual.between(stored), after);
        assertTrue(after.isEmpty());
    }

    @Test public void exactArithmetic_suppressesIntermediateOverflowAndRecoversAtNextBalance()
            throws Exception {
        List<Transaction> stored = Arrays.asList(
            new Transaction(MELLAT, "sum-underflow", 1, 0, 0L, "a", null),
            new Transaction(MELLAT, "sum-underflow", 2, Long.MIN_VALUE, null, "b", null),
            new Transaction(MELLAT, "sum-underflow", 3, -1, null, "c", null),
            new Transaction(MELLAT, "sum-underflow", 4, Long.MAX_VALUE, null, "d", null),
            new Transaction(MELLAT, "sum-underflow", 5, 0, 0L, "e", null),
            new Transaction(MELLAT, "sum-underflow", 6, 0, 10L, "f", null),
            // The first subtraction overflows, although subtracting the movement afterwards
            // would bring the mathematical gap back into range. The reference still suppresses it.
            new Transaction(MELLAT, "balance-difference", 1, 0, -1L, "g", null),
            new Transaction(MELLAT, "balance-difference", 2, 1, Long.MAX_VALUE, "h", null),
            new Transaction(MELLAT, "balance-difference", 3, 0, Long.MAX_VALUE - 1, "i", null),
            new Transaction(MELLAT, "final-subtraction", 1, 0, 0L, "j", null),
            new Transaction(MELLAT, "final-subtraction", 2, -1, Long.MAX_VALUE, "k", null),
            new Transaction(MELLAT, "final-subtraction", 3, 0, Long.MAX_VALUE - 1, "l", null),
            new Transaction(MELLAT, "recovery", 1, 0, 100L, "m", null),
            new Transaction(MELLAT, "recovery", 2, Long.MAX_VALUE, null, "n", null),
            new Transaction(MELLAT, "recovery", 2, 1, null, "o", null),
            new Transaction(MELLAT, "recovery", 3, 0, 100L, "p", null),
            new Transaction(MELLAT, "recovery", 4, 0, 90L, "q", null));
        assertTrue(TransactionStore.replace(context, stored));
        List<Residual> expected = Residual.between(stored);
        assertEquals(4, expected.size());
        for (int pageSize : new int[]{1, 2, 64}) {
            assertResidualsEqual(expected,
                HistoryResidualReader.read(context, null, null, pageSize, 10));
        }
    }

    @Test public void sameTimeOrdinalOrder_isPreservedForRunningSumOverflow() throws Exception {
        for (boolean overflowFirst : new boolean[]{true, false}) {
            List<Transaction> stored = new ArrayList<>();
            stored.add(new Transaction(MELLAT, "1", 1, 0, 0L, "open", null));
            stored.add(new Transaction(MELLAT, "1", 2, Long.MAX_VALUE, null, "max", null));
            stored.add(new Transaction(MELLAT, "1", 2, overflowFirst ? 1 : -Long.MAX_VALUE,
                null, "second", null));
            stored.add(new Transaction(MELLAT, "1", 2, overflowFirst ? -Long.MAX_VALUE : 1,
                null, "third", null));
            stored.add(new Transaction(MELLAT, "1", 3, 0, 5L, "closer", null));
            assertTrue(TransactionStore.replace(context, stored));
            List<Residual> expected = Residual.between(stored);
            assertEquals(overflowFirst ? 0 : 1, expected.size());
            assertResidualsEqual(expected,
                HistoryResidualReader.read(context, null, null, 1, 10));
        }
    }

    @Test public void hugeTimestampGroup_spansPagesWithoutLosingItsSingleCloser() throws Exception {
        List<Transaction> stored = new ArrayList<>();
        stored.add(new Transaction(MELLAT, "1", Long.MIN_VALUE, 77, 1_000L, "open", null));
        stored.add(new Transaction(MELLAT, "1", Long.MAX_VALUE, -10, 100L, "closer", null));
        for (int i = 0; i < 257; i++) {
            stored.add(new Transaction(MELLAT, "1", Long.MAX_VALUE, -1, null,
                "same-time-" + i, null));
        }
        assertTrue(TransactionStore.replace(context, stored));
        List<Residual> expected = Residual.between(stored);
        assertEquals(258, expected.get(0).movements);
        assertEquals(-633L, expected.get(0).amount);
        for (int pageSize : new int[]{1, 7, 128}) {
            assertResidualsEqual(expected,
                HistoryResidualReader.read(context, null, null, pageSize, 10));
        }
    }

    @Test public void malformedSourcePayload_failsBeforeEmissionAndCleansStaging() throws Exception {
        assertTrue(TransactionStore.replace(context, Arrays.asList(
            new Transaction(MELLAT, "1", 1, 0, 100L, "open", null),
            new Transaction(MELLAT, "1", 2, 0, 80L, "close", null))));
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                DataGeneration.context(context).getDatabasePath(TransactionStore.DB_NAME).getPath(),
                null, SQLiteDatabase.OPEN_READWRITE)) {
            ContentValues values = new ContentValues();
            // Valid legacy encryption around an invalid JSON row exercises the strict parser.
            values.put("payload", BalanceData.encryptStorePayload(
                "{\"transactions\":[{\"bank\":\"Mellat\",\"amount\":1}]}"));
            assertEquals(1, db.update(TransactionStore.TABLE, values, "ordinal=?",
                new String[]{"1"}));
        }
        final int[] delivered = {0};
        try {
            HistoryResidualReader.forEach(context, null, null, 1, residual -> delivered[0]++);
            fail("malformed source must fail rather than report a partial history");
        } catch (Exception expected) {
            assertEquals("missing transaction field", expected.getMessage());
        }
        assertEquals(0, delivered[0]);
        assertTemporaryFilesRemoved();
    }

    @Test public void consumerMayReenterSourceStore_andStagedSnapshotDoesNotChange() throws Exception {
        List<Transaction> stored = Arrays.asList(
            new Transaction(MELLAT, "1", 1, 0, 100L, "a", null),
            new Transaction(MELLAT, "1", 2, 0, 90L, "b", null),
            new Transaction(MELLAT, "1", 3, 0, 80L, "c", null));
        assertTrue(TransactionStore.replace(context, stored));
        List<Residual> actual = new ArrayList<>();
        HistoryResidualReader.forEach(context, null, null, 1, residual -> {
            actual.add(residual);
            if (actual.size() == 1) assertTrue(TransactionStore.replace(context,
                Collections.emptyList()));
        });
        assertResidualsEqual(Residual.between(stored), actual);
        assertEquals(0L, TransactionStore.count(context));
    }

    @Test public void stagingHasOnlyOpaqueIndexesOrdinalsDatesAndEncryptedPayloads() throws Exception {
        assertTrue(TransactionStore.replace(context, Arrays.asList(
            new Transaction(MELLAT, "private-account", 1, 0, 100L, "a", "body-a"),
            new Transaction(MELLAT, "private-account", 2, 0, 80L, "b", "body-b"))));
        final int[] delivered = {0};
        HistoryResidualReader.forEach(context, null, null, residual -> {
            delivered[0]++;
            assertStagingFilesExist();
            File database = null;
            for (File file : temporaryFiles()) {
                if (file.getName().endsWith(".db")) database = file;
            }
            assertTrue(database != null);
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(database.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY)) {
                List<String> allowed = Arrays.asList("slot", "date", "ordinal", "payload",
                    "run_ordinal", "position_ordinal");
                for (String table : Arrays.asList("residual_transactions", "residual_states",
                        "residual_candidates", "residual_runs_a", "residual_runs_b")) {
                    try (Cursor columns = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
                        while (columns.moveToNext())
                            assertTrue(allowed.contains(columns.getString(1)));
                    }
                    try (Cursor rows = db.query(table, new String[]{"slot", "payload"}, null,
                            null, null, null, null)) {
                        while (rows.moveToNext()) {
                            assertTrue(rows.getString(0).matches("[A-Za-z0-9+/]{43}="));
                            assertTrue(rows.getString(1).startsWith(EncryptedRowCodec.PREFIX));
                        }
                    }
                }
            }
        });
        assertEquals(1, delivered[0]);
        assertTemporaryFilesRemoved();
    }

    @Test public void emptyHistoryAndNoBalanceAnchors_produceNoResiduals() throws Exception {
        assertTrue(HistoryResidualReader.read(context, null, null, 1, 0).isEmpty());
        assertTrue(TransactionStore.replace(context, Arrays.asList(
            new Transaction(MELLAT, "1", 1, -50, null, "before", null),
            new Transaction(MELLAT, "1", 2, -50, 100L, "sole-balance", null),
            new Transaction(MELLAT, "1", 3, -500, null, "after", null))));
        assertTrue(HistoryResidualReader.read(context, null, null, 1, 0).isEmpty());
        assertTemporaryFilesRemoved();
    }

    private void assertTemporaryFilesRemoved() {
        assertEquals("staging database, sidecars and wrapped key must be deleted", 0,
            temporaryFiles().size());
    }

    private void assertStagingFilesExist() {
        boolean database = false;
        boolean wrappedKey = false;
        for (File file : temporaryFiles()) {
            if (file.getName().endsWith(".db")) database = true;
            if (file.getName().endsWith(".key")) wrappedKey = true;
        }
        assertTrue("callback must observe an active staging database", database);
        assertTrue("callback must observe a disposable wrapped key", wrappedKey);
    }

    private List<File> temporaryFiles() {
        File directory = context.getNoBackupFilesDir();
        File[] files = directory == null ? null : directory.listFiles((parent, name) ->
            name.startsWith("history-residual-") || name.startsWith("history-residual-key-"));
        return files == null ? Collections.emptyList() : Arrays.asList(files);
    }

    private static void assertResidualsEqual(List<Residual> expected, List<Residual> actual) {
        assertEquals("residual count", expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            Residual a = expected.get(i);
            Residual b = actual.get(i);
            assertEquals("bank " + i, a.bank, b.bank);
            assertEquals("account " + i, a.account, b.account);
            assertEquals("fromDate " + i, a.fromDate, b.fromDate);
            assertEquals("toDate " + i, a.toDate, b.toDate);
            assertEquals("amount " + i, a.amount, b.amount);
            assertEquals("movements " + i, a.movements, b.movements);
            assertEquals("key " + i, a.key(), b.key());
        }
    }

    private static long epoch(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day, 12, 0, 0);
        return calendar.getTimeInMillis();
    }
}
