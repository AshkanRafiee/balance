package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Encrypted commitment rows, strict migration, atomic writes and bounded cursors. */
@RunWith(AndroidJUnit4.class)
public class CommitmentStoreTest {
    private Context context;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CommitmentStore.clear(context);
    }

    @After public void tearDown() throws Exception {
        CommitmentStore.clear(context);
    }

    @Test public void onceDefinitionsKeepNoSettlementMarks() throws Exception {
        // A one-time definition's state is its done flag alone: paid/unpaid dates handed in
        // with it (e.g. from an old backup) must not become settlement rows that a later
        // edit to recurring would resurrect, and settling must drop any leftovers.
        long day = Commitment.startOfDay(System.currentTimeMillis());
        Commitment once = new Commitment("once-marks", "One", -10L, Commitment.ONCE, day, null,
            false, Collections.singletonList(day), 0,
            Collections.singletonList(day + 86400000L), false, 0);
        assertTrue(CommitmentStore.upsert(context, once));
        assertEquals("{\"paidThrough\":0,\"paid\":[],\"unpaid\":[]}", exportedSettlements("once-marks"));
        assertTrue(CommitmentStore.settle(context, "once-marks", day));
        assertTrue(CommitmentStore.get(context, "once-marks").done);
        assertEquals("{\"paidThrough\":0,\"paid\":[],\"unpaid\":[]}", exportedSettlements("once-marks"));
    }

    private String exportedSettlements(String id) throws Exception {
        StringWriter writer = new StringWriter();
        CommitmentStore.exportSettlements(context, id, writer);
        return writer.toString();
    }

    @Test public void definitionsAndSettlementRowsHaveNoLegacyCaps() throws Exception {
        long start = Commitment.startOfDay(System.currentTimeMillis());
        List<Commitment> definitions = new ArrayList<>();
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 5000; i++) longName.append('n');
        List<Long> paid = new ArrayList<>();
        for (int i = 1; i <= 2501; i++) paid.add(start + i * 86400000L);
        List<Long> unpaid = new ArrayList<>();
        for (int i = 3001; i <= 5501; i++) unpaid.add(start + i * 86400000L);
        for (int i = 0; i < 601; i++) {
            definitions.add(new Commitment("definition-" + i,
                i == 0 ? longName.toString() : "name-" + i,
                i == 0 ? Long.MAX_VALUE : i + 1L, Commitment.DAILY, start, null, false,
                i == 0 ? paid : null, 0, i == 0 ? unpaid : null, false, Long.MAX_VALUE));
        }

        assertTrue(CommitmentStore.replace(context, definitions));
        assertEquals(601, CommitmentStore.read(context).size());
        Commitment stored = CommitmentStore.get(context, "definition-0");
        assertEquals(longName.length(), stored.name.length());
        assertEquals(Long.MAX_VALUE, stored.amount);
        assertEquals(2501, stored.paid.size());
        assertEquals(2501, stored.unpaid.size());
        assertTrue(stored.isSettled(start + 2501 * 86400000L));
        assertFalse(stored.isSettled(start + 3001 * 86400000L));
    }

    @Test public void undoUnderLegacyWatermarkStoresOnlyOneOverride() throws Exception {
        long start = Commitment.startOfDay(System.currentTimeMillis());
        Commitment source = new Commitment("watermark", "legacy", -10, Commitment.DAILY,
            start, null, false, null, start + 10 * 86400000L, false, 0);
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .putString(BalanceData.KEY_COMMITMENTS,
                new org.json.JSONObject().put(BalanceData.KEY_COMMITMENTS,
                    new org.json.JSONArray().put(source.toJson())).toString())
            .commit();

        assertTrue(CommitmentStore.isSettled(context, source.id, start + 2 * 86400000L));
        assertTrue(CommitmentStore.undo(context, source.id, start + 2 * 86400000L));
        assertFalse(CommitmentStore.isSettled(context, source.id, start + 2 * 86400000L));
        assertTrue(CommitmentStore.isSettled(context, source.id, start + 3 * 86400000L));
        Commitment stored = CommitmentStore.get(context, source.id);
        assertEquals(1, stored.unpaid.size());
        assertEquals(start + 2 * 86400000L, stored.unpaid.get(0).longValue());
        assertEquals(0, stored.paid.size());
        assertFalse(context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE)
            .contains(BalanceData.KEY_COMMITMENTS));
    }

    @Test public void legacyMigrationPreservesEverySettlementDate() throws Exception {
        long start = Commitment.startOfDay(System.currentTimeMillis());
        List<Long> paid = new ArrayList<>();
        for (int i = 1; i <= 2001; i++) paid.add(start + i * 86400000L);
        Commitment source = new Commitment("legacy-large", "legacy", -10, Commitment.DAILY,
            start, null, false, paid, false, 0);
        String legacy = new org.json.JSONObject().put(BalanceData.KEY_COMMITMENTS,
            new org.json.JSONArray().put(source.toJson())).toString();
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .putString(BalanceData.KEY_COMMITMENTS, legacy).commit();

        CommitmentStore.read(context);
        Commitment migrated = CommitmentStore.get(context, source.id);
        assertEquals(2001, migrated.paid.size());
        assertEquals(paid.get(0), migrated.paid.get(0));
        assertEquals(paid.get(2000), migrated.paid.get(2000));
    }

    @Test public void malformedMigrationKeepsLegacySourceAndWritesNothing() throws Exception {
        String malformed = "{\"commitments\":[{\"id\":\"bad\",\"name\":\"x\"}]}";
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .putString(BalanceData.KEY_COMMITMENTS, malformed).commit();
        try {
            CommitmentStore.read(context);
            fail("malformed legacy data must fail migration");
        } catch (Exception expected) {
            // The source is deliberately left for a later compatible build or explicit clear.
        }
        assertEquals(malformed, context.getSharedPreferences(BalanceData.PREFS_DATA,
            Context.MODE_PRIVATE).getString(BalanceData.KEY_COMMITMENTS, null));
    }

    @Test public void malformedMigrationDoesNotReplaceExistingDefinitions() throws Exception {
        Commitment original = new Commitment("original", "original", 1, Commitment.ONCE,
            Commitment.startOfDay(System.currentTimeMillis()), null, false, null, false, 0);
        CommitmentStore.upsert(context, original);
        String malformed = "{\"commitments\":[{\"id\":\"bad\",\"amount\":1}]}";
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .putString(BalanceData.KEY_COMMITMENTS, malformed).commit();
        try {
            CommitmentStore.read(context);
            fail("malformed legacy data must fail migration");
        } catch (Exception expected) {
            // The valid database transaction must remain untouched.
        }
        assertEquals(malformed, context.getSharedPreferences(BalanceData.PREFS_DATA,
            Context.MODE_PRIVATE).getString(BalanceData.KEY_COMMITMENTS, null));
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .remove(BalanceData.KEY_COMMITMENTS).commit();
        assertEquals(original.id, CommitmentStore.get(context, original.id).id);
    }

    @Test public void callbackFailureRollsBackTheWholeTransaction() throws Exception {
        Commitment original = new Commitment("original", "original", 1, Commitment.ONCE,
            Commitment.startOfDay(System.currentTimeMillis()), null, false, null, false, 0);
        Commitment incoming = new Commitment("incoming", "incoming", 2, Commitment.ONCE,
            original.start, null, false, null, false, 0);
        CommitmentStore.upsert(context, original);
        try {
            CommitmentStore.runInTransaction(context, editor -> {
                editor.upsert(incoming);
                throw new Exception("intentional failure");
            });
            fail("transaction should fail");
        } catch (Exception expected) {
            // expected
        }
        assertTrue(CommitmentStore.get(context, original.id) != null);
        assertTrue(CommitmentStore.get(context, incoming.id) == null);
    }

    @Test public void missingTokenRecoversAndWrongTokenDoesNotDeleteExistingRows() throws Exception {
        Commitment original = new Commitment("owned", "owned", 1, Commitment.ONCE,
            Commitment.startOfDay(System.currentTimeMillis()), null, false, null, false, 0);
        CommitmentStore.upsert(context, original);
        String token = context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE)
            .getString(CommitmentStore.KEY_COMMITMENT_STORE_TOKEN, null);
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .remove(CommitmentStore.KEY_COMMITMENT_STORE_TOKEN).commit();
        assertTrue(CommitmentStore.get(context, original.id) != null);
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .putString(CommitmentStore.KEY_COMMITMENT_STORE_TOKEN, "wrong-token").commit();
        try {
            CommitmentStore.get(context, original.id);
            fail("a different owner must not expose or replace rows");
        } catch (Exception expected) {
            // The row remains available after the original token is restored.
        }
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .putString(CommitmentStore.KEY_COMMITMENT_STORE_TOKEN, token).commit();
        assertTrue(CommitmentStore.get(context, original.id) != null);
    }

    @Test public void explicitClearIsAllowedWhenOwnershipTokenIsMissing() throws Exception {
        Commitment original = new Commitment("owned", "owned", 1, Commitment.ONCE,
            Commitment.startOfDay(System.currentTimeMillis()), null, false, null, false, 0);
        CommitmentStore.upsert(context, original);
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .remove(CommitmentStore.KEY_COMMITMENT_STORE_TOKEN).commit();

        assertTrue(CommitmentStore.clear(context));
        assertTrue(CommitmentStore.get(context, original.id) == null);
    }

    @Test public void conflictingPaidAndUnpaidDatesFailWithoutLosingExistingState() throws Exception {
        long start = Commitment.startOfDay(System.currentTimeMillis());
        Commitment original = new Commitment("conflict", "original", 1, Commitment.DAILY,
            start, null, false, singleton(start), false, 0);
        CommitmentStore.upsert(context, original);
        Commitment conflicting = new Commitment(original.id, "changed", 2, Commitment.DAILY,
            start, null, false, singleton(start), 0, singleton(start), false, 0);
        try {
            CommitmentStore.upsert(context, conflicting);
            fail("conflicting settlement states must be rejected");
        } catch (Exception expected) {
            // The replacement is transactional.
        }
        Commitment stored = CommitmentStore.get(context, original.id);
        assertEquals("original", stored.name);
        assertTrue(stored.isSettled(start));
        assertTrue(stored.unpaid.isEmpty());
    }

    @Test public void definitionAndWindowPagesStayBounded() throws Exception {
        long start = Commitment.startOfDay(System.currentTimeMillis());
        List<Commitment> definitions = new ArrayList<>();
        for (int i = 0; i < 73; i++) definitions.add(new Commitment("page-" + i, "page",
            1, Commitment.ONCE, start + i * 86400000L, null, false, null, false, 0));
        Commitment recurring = new Commitment("settlements", "settlements", 1, Commitment.DAILY,
            start, null, false, null, false, 0);
        definitions.add(recurring);
        CommitmentStore.replace(context, definitions);
        for (int i = 1; i <= 61; i++) CommitmentStore.settle(context, recurring.id,
            start + i * 86400000L);

        int definitionsSeen = 0;
        CommitmentStore.DefinitionPage page = CommitmentStore.pageDefinitions(context, 0, 11);
        while (true) {
            assertTrue(page.rows.size() <= 11);
            definitionsSeen += page.rows.size();
            if (!page.hasMore) break;
            page = CommitmentStore.pageDefinitions(context, page, 11);
        }
        assertEquals(74, definitionsSeen);

        int settlementsSeen = 0;
        CommitmentStore.SettlementPage settlements = CommitmentStore.windowPage(context,
            recurring.id, start, start + 100 * 86400000L, 0, 13);
        while (true) {
            assertTrue(settlements.rows.size() <= 13);
            settlementsSeen += settlements.rows.size();
            if (!settlements.hasMore) break;
            settlements = CommitmentStore.pageSettlements(context, settlements, 13);
        }
        assertEquals(61, settlementsSeen);
    }

    @Test public void getExposesImmutablePagedSettlementList() throws Exception {
        long start = Commitment.startOfDay(System.currentTimeMillis());
        List<Long> paid = new ArrayList<>();
        for (int i = 1; i <= 257; i++) paid.add(start + i * 86400000L);
        Commitment definition = new Commitment("lazy", "lazy", 1, Commitment.DAILY,
            start, null, false, paid, false, 0);
        CommitmentStore.upsert(context, definition);

        Commitment stored = CommitmentStore.get(context, definition.id);
        assertTrue(stored.paid instanceof Commitment.SettlementList);
        assertEquals(paid.size(), stored.paid.size());
        assertEquals(paid.get(256), stored.paid.get(256));
        try {
            stored.paid.add(start);
            fail("settlement history must be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test public void lazySettlementIteratorRejectsAChangedStore() throws Exception {
        long start = Commitment.startOfDay(System.currentTimeMillis());
        Commitment definition = new Commitment("stale", "stale", 1, Commitment.DAILY,
            start, null, false, singleton(start), false, 0);
        CommitmentStore.upsert(context, definition);
        Iterator<Long> iterator = CommitmentStore.get(context, definition.id).paid.iterator();
        assertTrue(iterator.hasNext());
        CommitmentStore.settle(context, definition.id, start + 10 * 86400000L);
        try {
            iterator.next();
            fail("a lazy iterator must not combine revisions");
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    private static List<Long> singleton(long date) {
        List<Long> result = new ArrayList<>();
        result.add(date);
        return result;
    }
}
