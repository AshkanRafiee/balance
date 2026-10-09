package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

import java.util.ArrayList;
import java.util.List;

/** Encrypted, uncapped source evidence and immutable parser observations. */
@RunWith(AndroidJUnit4.class)
public class SourceStoreTest {
    private Context context;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SourceStore.clear(context);
    }

    @After public void tearDown() {
        SourceStore.clear(context);
    }

    @Test public void sameBodyAtDifferentArrivalsIsRetainedAndExactCaptureIsIdempotent()
            throws Exception {
        SourceStore.Source first = SourceStore.capture(context, "Refah", "same body", 100L, 7);
        SourceStore.Source second = SourceStore.capture(context, "Refah", "same body", 101L, 7);
        SourceStore.Source duplicate = SourceStore.capture(context, "Refah", "same body", 100L, 7);

        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first.id, second.id);
        assertEquals(first.id, duplicate.id);
        assertEquals(2L, SourceStore.sourceCount(context));
        assertEquals(first.contentIdentity, second.contentIdentity);
    }

    @Test public void knownUnparsedBodiesAreRetainedButUnknownSendersAreNot() throws Exception {
        SourceStore.Source retained = SourceStore.capture(context, "Refah", "not a bank parse", 1L, 9);
        SourceStore.Source ignored = SourceStore.capture(context, "unknown-sender", "private", 2L, 9);

        assertNotNull(retained);
        assertEquals("Refah", retained.bank);
        assertNull(ignored);
        assertEquals(1L, SourceStore.sourceCount(context));
    }

    @Test public void olderParserRevisionsAndDistinctInterpretationsRemainImmutable() throws Exception {
        SourceStore.Source source = SourceStore.capture(context, "Refah", "parsed body", 10L, 1);
        Transaction oldParse = new Transaction("Refah", "account", 11L, -100L, 900L,
            "old-signature", "old-content");
        Transaction newParse = new Transaction("Refah", "account", 11L, -200L, 800L,
            "new-signature", "new-content");

        assertTrue(SourceStore.observeTransaction(context, source, 1, oldParse));
        assertFalse(SourceStore.observeTransaction(context, source, 1, oldParse));
        assertTrue(SourceStore.observeTransaction(context, source, 2, newParse));
        assertTrue(SourceStore.observeBalance(context, source, 1, "Refah", "account", 11L, 900L));
        assertTrue(SourceStore.observeBalance(context, source, 2, "Refah", "account", 11L, 800L));

        assertEquals(2L, SourceStore.transactionObservationCount(context));
        assertEquals(2L, SourceStore.balanceObservationCount(context));

        List<Integer> revisions = new ArrayList<>();
        SourceStore.forEachTransaction(context, 8, row -> revisions.add(row.parserRevision));
        assertEquals(2, revisions.size());
        assertEquals(Integer.valueOf(1), revisions.get(0));
        assertEquals(Integer.valueOf(2), revisions.get(1));
    }

    @Test public void sourceAndObservationsHaveNoPlaintextSenderOrBodyInRows() throws Exception {
        String sender = "Refah-PRIVATE-SENDER";
        String body = "PRIVATE RAW SMS BODY 93b1f2";
        // The sender is intentionally a known alias with extra formatting only if the resolver can
        // recognize it; use the canonical alias for the persisted-source assertion.
        sender = "Refah";
        SourceStore.Source source = SourceStore.capture(context, sender, body, 20L, 3);
        assertNotNull(source);
        SourceStore.observeTransaction(context, source, 3,
            new Transaction("Refah", null, 20L, -5L, 95L, "PRIVATE-SIG", "PRIVATE-CONTENT"));
        SourceStore.observeBalance(context, source, 3, "Refah", null, 20L, 95L);

        SQLiteDatabase db = SQLiteDatabase.openDatabase(context.getDatabasePath(SourceStore.DB_NAME)
            .getPath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            assertNoPlaintext(db, SourceStore.TABLE, body);
            assertNoPlaintext(db, SourceStore.TABLE, sender);
            assertNoPlaintext(db, SourceStore.TRANSACTION_TABLE, "PRIVATE-SIG");
            assertNoPlaintext(db, SourceStore.BALANCE_TABLE, "Refah");
        } finally {
            db.close();
        }
    }

    @Test public void failedBatchRollsBackSourcesAndObservationsTogether() throws Exception {
        boolean failed = false;
        try {
            SourceStore.runInTransaction(context, editor -> {
                SourceStore.Source source = editor.capture("Refah", "batch body", 30L, 4);
                editor.observeTransaction(source, 4,
                    new Transaction("Refah", null, 30L, 1L, 101L, "sig", "content"));
                editor.capture("Refah", "second body", 31L, 4);
                throw new Exception("abort scan batch");
            });
        } catch (Exception expected) {
            failed = true;
        }
        assertTrue(failed);
        assertEquals(0L, SourceStore.sourceCount(context));
        assertEquals(0L, SourceStore.transactionObservationCount(context));
    }

    @Test public void pagesAndVisitorsHandleMoreThanOnePageWithoutALifetimeCap() throws Exception {
        final int total = SourceStore.MAX_PAGE_SIZE + 37;
        SourceStore.runInTransaction(context, editor -> {
            for (int i = 0; i < total; i++)
                editor.capture("Refah", "body-" + i, 1_000L + i, 5);
            return null;
        });

        int visited = 0;
        SourceStore.SourcePage page = SourceStore.pageSources(context, -1, 37);
        while (true) {
            assertTrue(page.rows.size() <= 37);
            visited += page.rows.size();
            if (!page.hasMore) break;
            page = SourceStore.pageSources(context, page, 37);
        }
        assertEquals(total, visited);

        final int[] streamed = {0};
        SourceStore.forEachSource(context, 37, source -> streamed[0]++);
        assertEquals(total, streamed[0]);
    }

    @Test public void corruptPayloadFailsAndDatabaseRemainsForRecovery() throws Exception {
        SourceStore.capture(context, "Refah", "corrupt-me", 40L, 6);
        SQLiteDatabase db = SQLiteDatabase.openDatabase(context.getDatabasePath(SourceStore.DB_NAME)
            .getPath(), null, SQLiteDatabase.OPEN_READWRITE);
        try {
            ContentValues values = new ContentValues();
            values.put("payload", "not-an-encrypted-source-row");
            assertEquals(1, db.update(SourceStore.TABLE, values, "id=?", new String[]{"1"}));
        } finally {
            db.close();
        }

        boolean failed = false;
        try {
            SourceStore.sourceCount(context);
        } catch (Exception expected) {
            failed = true;
        }
        assertTrue(failed);
        assertTrue(context.getDatabasePath(SourceStore.DB_NAME).isFile());
    }

    private void assertNoPlaintext(SQLiteDatabase db, String table, String value) {
        try (Cursor cursor = db.query(table, null, null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    if (!cursor.isNull(i)) assertFalse(String.valueOf(cursor.getString(i)).contains(value));
                }
            }
        }
    }
}
