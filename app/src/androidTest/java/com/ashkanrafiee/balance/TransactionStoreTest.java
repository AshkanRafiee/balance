package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Generation-safe paged transaction storage and migration from the legacy JSON value. */
@RunWith(AndroidJUnit4.class)
public class TransactionStoreTest {

    private Context context;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BalanceData.reset(context, true);
    }

    @After public void tearDown() {
        BalanceData.reset(context, true);
    }

    @Test public void pagedRoundTrip_preservesEveryTransactionField() {
        List<Transaction> input = new ArrayList<>();
        for (int i = 0; i < 2_050; i++) {
            input.add(new Transaction("Synthetic", i % 2 == 0 ? "account-" + i : null,
                2_000_000L + i, i % 3 == 0 ? -100_000L - i : 100_000L + i,
                i % 2 == 0 ? 9_000_000L + i : null,
                i % 3 == 0 ? "signature-" + i : null,
                i % 4 == 0 ? "content-" + i : null));
        }

        assertEquals(true, BalanceData.writeTransactions(context, input));
        List<Transaction> output = BalanceData.readTransactions(context);
        assertEquals(input.size(), output.size());
        assertTransactionEquals(input.get(0), output.get(0));
        assertTransactionEquals(input.get(1_000), output.get(1_000));
        assertTransactionEquals(input.get(2_049), output.get(2_049));
    }

    @Test public void legacyJson_migratesWithoutChangingTheRows() throws Exception {
        List<Transaction> input = new ArrayList<>();
        input.add(new Transaction("Synthetic", "account-1", 10L, -20L, 30L,
            "signature-1", "content-1"));
        input.add(new Transaction("Synthetic", null, 20L, 40L, null, null, "content-2"));
        String legacy = BalanceData.serializeTransactions(input);
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .putString(BalanceData.KEY_TRANSACTIONS, legacy).commit();

        List<Transaction> output = BalanceData.readTransactions(context);
        assertEquals(2, output.size());
        assertTransactionEquals(input.get(0), output.get(0));
        assertTransactionEquals(input.get(1), output.get(1));
        assertNull(context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE)
            .getString(BalanceData.KEY_TRANSACTIONS, null));
    }

    @Test public void replacingWithAShorterGeneration_removesTheOldTail() {
        List<Transaction> longList = new ArrayList<>();
        for (int i = 0; i < 1_501; i++)
            longList.add(new Transaction("Synthetic", 1_000L + i, 1L));
        assertEquals(true, BalanceData.writeTransactions(context, longList));

        List<Transaction> shortList = new ArrayList<>();
        shortList.add(new Transaction("Synthetic", 9_001L, -1L));
        shortList.add(new Transaction("Synthetic", 9_002L, 2L));
        assertEquals(true, BalanceData.writeTransactions(context, shortList));

        List<Transaction> output = BalanceData.readTransactions(context);
        assertEquals(2, output.size());
        assertEquals(9_001L, output.get(0).date);
        assertEquals(9_002L, output.get(1).date);
    }

    @Test public void emptyReplacementDoesNotResurrectLegacyRows() throws Exception {
        List<Transaction> legacyRows = java.util.Collections.singletonList(
            new Transaction("Synthetic", 4L, 5L));
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .putString(BalanceData.KEY_TRANSACTIONS, BalanceData.serializeTransactions(legacyRows))
            .commit();

        assertTrue(TransactionStore.replace(context, java.util.Collections.emptyList()));

        assertEquals(0, TransactionStore.read(context).size());
        assertNull(context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE)
            .getString(BalanceData.KEY_TRANSACTIONS, null));
    }

    @Test public void pageReadsOnlyTheRequestedWindow() throws Exception {
        List<Transaction> input = new ArrayList<>();
        for (int i = 0; i < 2_050; i++)
            input.add(new Transaction("Synthetic", 10_000L + i, i));
        assertEquals(true, BalanceData.writeTransactions(context, input));

        TransactionStore.Page first = TransactionStore.page(context, -1, 37);
        assertEquals(37, first.rows.size());
        assertEquals(10_036L, first.rows.get(36).date);
        assertEquals(true, first.hasMore);

        TransactionStore.Page second = TransactionStore.page(context, first.nextOrdinal, 37);
        assertEquals(37, second.rows.size());
        assertEquals(37, second.rows.get(0).amount);
    }

    @Test public void tokenMismatchOrRemovalDoesNotHideEncryptedRows() throws Exception {
        List<Transaction> input = new ArrayList<>();
        input.add(new Transaction("Synthetic", "account", 12L, -4L, 8L, "sig", "content"));
        assertTrue(TransactionStore.replace(context, input));

        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .putString(BalanceData.KEY_TRANSACTION_STORE_TOKEN, "different-owner").commit();
        assertTransactionEquals(input.get(0), TransactionStore.read(context).get(0));

        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .remove(BalanceData.KEY_TRANSACTION_STORE_TOKEN).commit();

        List<Transaction> output = TransactionStore.read(context);
        assertEquals(1, output.size());
        assertTransactionEquals(input.get(0), output.get(0));
    }

    @Test public void missingDatabaseWithAReceiptFailsClosed() throws Exception {
        assertTrue(TransactionStore.replace(context,
            java.util.Collections.singletonList(new Transaction("Synthetic", 1L, 2L))));
        assertTrue(context.deleteDatabase(TransactionStore.DB_NAME));

        boolean failed = false;
        try {
            TransactionStore.read(context);
        } catch (Exception expected) {
            failed = true;
        }
        assertTrue(failed);
    }

    @Test public void corruptPayloadFailsStrictlyInsteadOfReturningPartialRows() throws Exception {
        List<Transaction> input = new ArrayList<>();
        input.add(new Transaction("Synthetic", 1L, 2L));
        input.add(new Transaction("Synthetic", 2L, 3L));
        assertTrue(TransactionStore.replace(context, input));

        SQLiteDatabase db = SQLiteDatabase.openDatabase(
            context.getDatabasePath(TransactionStore.DB_NAME).getPath(), null,
            SQLiteDatabase.OPEN_READWRITE);
        try {
            ContentValues corrupt = new ContentValues();
            corrupt.put("payload", "not-an-encrypted-payload");
            assertEquals(1, db.update(TransactionStore.TABLE, corrupt, "ordinal=?",
                new String[]{"0"}));
        } finally {
            db.close();
        }

        boolean failed = false;
        try {
            TransactionStore.read(context);
        } catch (Exception expected) {
            failed = true;
        }
        assertTrue(failed);
    }

    @Test public void countValidatesPayloadsInsteadOfTrustingSqliteRowCount() throws Exception {
        assertTrue(TransactionStore.replace(context,
            java.util.Collections.singletonList(new Transaction("Synthetic", 1L, 2L))));

        SQLiteDatabase db = SQLiteDatabase.openDatabase(
            context.getDatabasePath(TransactionStore.DB_NAME).getPath(), null,
            SQLiteDatabase.OPEN_READWRITE);
        try {
            ContentValues corrupt = new ContentValues();
            corrupt.put("payload", "not-an-encrypted-payload");
            assertEquals(1, db.update(TransactionStore.TABLE, corrupt, "ordinal=?",
                new String[]{"0"}));
        } finally {
            db.close();
        }

        boolean failed = false;
        try {
            TransactionStore.count(context);
        } catch (Exception expected) {
            failed = true;
        }
        assertTrue(failed);
    }

    @Test public void failedReplacementRollsBackEveryDeletedRow() throws Exception {
        List<Transaction> oldRows = new ArrayList<>();
        oldRows.add(new Transaction("Synthetic", 10L, 1L));
        oldRows.add(new Transaction("Synthetic", 11L, 2L));
        assertTrue(TransactionStore.replace(context, oldRows));

        List<Transaction> replacement = new ArrayList<>();
        replacement.add(new Transaction("Synthetic", 20L, 3L));
        replacement.add(null);
        boolean failed = false;
        try {
            TransactionStore.replace(context, replacement);
        } catch (Exception expected) {
            failed = true;
        }
        assertTrue(failed);
        assertEquals(2, TransactionStore.count(context));
        assertEquals(10L, TransactionStore.read(context).get(0).date);
        assertEquals(11L, TransactionStore.read(context).get(1).date);
    }

    @Test public void pageContinuationRejectsAChangedSnapshot() throws Exception {
        List<Transaction> input = new ArrayList<>();
        for (int i = 0; i < 4; i++) input.add(new Transaction("Synthetic", i, i));
        assertTrue(TransactionStore.replace(context, input));
        TransactionStore.Page first = TransactionStore.page(context, -1, 2);
        assertTrue(first.hasMore);

        assertTrue(TransactionStore.replace(context,
            java.util.Collections.singletonList(new Transaction("Synthetic", 99L, 99L))));
        boolean failed = false;
        try {
            TransactionStore.page(context, first, 2);
        } catch (IllegalStateException expected) {
            failed = true;
        }
        assertTrue(failed);
    }

    @Test public void pageCursorRemainsLongForLargeOrdinals() throws Exception {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(
            context.getDatabasePath(TransactionStore.DB_NAME).getPath(), null,
            SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.CREATE_IF_NECESSARY);
        try {
            db.execSQL("CREATE TABLE transactions (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " ordinal INTEGER NOT NULL, payload TEXT NOT NULL)");
            db.execSQL("CREATE INDEX transactions_order ON transactions(ordinal)");
            db.execSQL("CREATE TABLE store_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            ContentValues ready = new ContentValues();
            ready.put("key", "ready");
            ready.put("value", "1");
            db.insertOrThrow("store_meta", null, ready);
            ContentValues owner = new ContentValues();
            owner.put("key", "owner");
            owner.put("value", "old-owner");
            db.insertOrThrow("store_meta", null, owner);
            for (int i = 0; i < 2; i++) {
                Transaction input = new Transaction("Synthetic", i + 1L, i + 1L);
                ContentValues row = new ContentValues();
                row.put("ordinal", 3_000_000_000L + i);
                row.put("payload", BalanceData.encryptStorePayload(
                    BalanceData.serializeTransactions(java.util.Collections.singletonList(input))));
                db.insertOrThrow("transactions", null, row);
            }
            db.setVersion(1);
        } finally {
            db.close();
        }

        TransactionStore.Page page = TransactionStore.page(context, -1, 1);
        assertEquals(3_000_000_000L, page.nextId);
        assertTrue(page.nextId > Integer.MAX_VALUE);
        TransactionStore.Page next = TransactionStore.page(context, page, 1);
        assertEquals(2L, next.rows.get(0).date);
    }

    @Test public void upgradesTheOriginalSchemaWithoutDroppingRows() throws Exception {
        Transaction input = new Transaction("Synthetic", "legacy-account", 7L, -8L, 9L,
            "legacy-signature", "legacy-content");
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(TransactionStore.DB_NAME).getPath(), null);
        try {
            db.execSQL("CREATE TABLE transactions (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " ordinal INTEGER NOT NULL, payload TEXT NOT NULL)");
            db.execSQL("CREATE INDEX transactions_order ON transactions(ordinal)");
            db.execSQL("CREATE TABLE store_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            ContentValues ready = new ContentValues();
            ready.put("key", "ready");
            ready.put("value", "1");
            db.insertOrThrow("store_meta", null, ready);
            ContentValues owner = new ContentValues();
            owner.put("key", "owner");
            owner.put("value", "old-owner");
            db.insertOrThrow("store_meta", null, owner);
            ContentValues row = new ContentValues();
            row.put("ordinal", 0L);
            row.put("payload", BalanceData.encryptStorePayload(
                BalanceData.serializeTransactions(java.util.Collections.singletonList(input))));
            db.insertOrThrow("transactions", null, row);
            db.setVersion(1);
        } finally {
            db.close();
        }

        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .putString(BalanceData.KEY_TRANSACTION_STORE_TOKEN, "different-owner").commit();

        List<Transaction> output = TransactionStore.read(context);
        assertEquals(1, output.size());
        assertTransactionEquals(input, output.get(0));
    }

    @Test public void upgradesAnEmptyOriginalSchemaAndMigratesItsLegacyRows() throws Exception {
        Transaction input = new Transaction("Synthetic", "legacy-account", 17L, 18L, null,
            null, null);
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(TransactionStore.DB_NAME).getPath(), null);
        try {
            db.execSQL("CREATE TABLE transactions (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " ordinal INTEGER NOT NULL, payload TEXT NOT NULL)");
            db.execSQL("CREATE INDEX transactions_order ON transactions(ordinal)");
            db.execSQL("CREATE TABLE store_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            ContentValues ready = new ContentValues();
            ready.put("key", "ready");
            ready.put("value", "1");
            db.insertOrThrow("store_meta", null, ready);
            ContentValues owner = new ContentValues();
            owner.put("key", "owner");
            owner.put("value", "old-owner");
            db.insertOrThrow("store_meta", null, owner);
            db.setVersion(1);
        } finally {
            db.close();
        }
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .putString(BalanceData.KEY_TRANSACTIONS,
                BalanceData.serializeTransactions(java.util.Collections.singletonList(input)))
            .commit();

        List<Transaction> output = TransactionStore.read(context);
        assertEquals(1, output.size());
        assertTransactionEquals(input, output.get(0));
        assertNull(context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE)
            .getString(BalanceData.KEY_TRANSACTIONS, null));
    }

    @Test public void clearRemovesTheDatabaseMarkerAndLegacyInputs() throws Exception {
        assertTrue(TransactionStore.replace(context,
            java.util.Collections.singletonList(new Transaction("Synthetic", 1L, 2L))));
        File legacyPage = new File(context.getFilesDir(), "transactions_page_v2_test_0");
        assertTrue(legacyPage.createNewFile());
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .putString(BalanceData.KEY_TRANSACTIONS, "legacy")
            .putString("transactions_manifest_v2", "legacy-manifest")
            .putString(BalanceData.KEY_TRANSACTION_STORE_TOKEN, "old-owner")
            .commit();

        TransactionStore.clear(context);

        assertFalse(context.getDatabasePath(TransactionStore.DB_NAME).exists());
        assertFalse(new File(context.getNoBackupFilesDir(),
            "balance_transactions.present").exists());
        assertFalse(legacyPage.exists());
        assertNull(context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE)
            .getString(BalanceData.KEY_TRANSACTIONS, null));
        assertFalse(context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE)
            .contains("transactions_manifest_v2"));
        assertFalse(context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE)
            .contains(BalanceData.KEY_TRANSACTION_STORE_TOKEN));
    }

    @Test public void mergeUsesOpaqueIndexesAndKeepsLocalRowsFirst() throws Exception {
        Transaction local = new Transaction("Synthetic", "local", 10L, 5L, null, "sig-1", "content-1");
        Transaction duplicate = new Transaction("Synthetic", "backup", 20L, 5L, null, "sig-1", null);
        Transaction incoming = new Transaction("Synthetic", "new", 30L, 7L, null, "sig-2", "content-2");
        assertTrue(TransactionStore.replace(context, java.util.Collections.singletonList(local)));
        final List<String> aliases = new ArrayList<>();
        TransactionStore.MergeResult result = TransactionStore.mergeResult(context,
            visitor -> {
                visitor.accept(duplicate);
                visitor.accept(incoming);
            }, (from, to) -> aliases.add(from + "=" + to));

        assertEquals(1L, result.added);
        assertEquals(1L, result.aliases);
        assertEquals(1, aliases.size());
        assertEquals(2L, TransactionStore.count(context));
        assertEquals("local", TransactionStore.read(context).get(0).account);
        assertEquals("new", TransactionStore.read(context).get(1).account);
    }

    @Test public void failedStreamingMergeLeavesTheOriginalSnapshot() throws Exception {
        Transaction old = new Transaction("Synthetic", 1L, 1L);
        assertTrue(TransactionStore.replace(context, java.util.Collections.singletonList(old)));
        boolean failed = false;
        try {
            TransactionStore.merge(context, visitor -> {
                visitor.accept(new Transaction("Synthetic", 2L, 2L));
                throw new Exception("source failed");
            });
        } catch (Exception expected) {
            failed = true;
        }
        assertTrue(failed);
        assertEquals(1L, TransactionStore.count(context));
        assertEquals(1L, TransactionStore.read(context).get(0).date);
    }

    private static void assertTransactionEquals(Transaction expected, Transaction actual) {
        assertEquals(expected.bank, actual.bank);
        assertEquals(expected.account, actual.account);
        assertEquals(expected.date, actual.date);
        assertEquals(expected.amount, actual.amount);
        assertEquals(expected.balance, actual.balance);
        assertEquals(expected.sig, actual.sig);
        assertEquals(expected.content, actual.content);
    }
}
