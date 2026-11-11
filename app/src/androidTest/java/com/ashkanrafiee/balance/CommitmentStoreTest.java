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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.time.ZoneId;
import java.time.Instant;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Encrypted commitment rows, strict migration, atomic writes and bounded cursors. */
@RunWith(AndroidJUnit4.class)
public class CommitmentStoreTest {
    private Context context;
    private TimeZone originalZone;
    private static final ZoneId FIXTURE_ZONE = ZoneId.of("America/New_York");

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(FIXTURE_ZONE));
        CommitmentStore.clear(context);
    }

    @After public void tearDown() throws Exception {
        try { CommitmentStore.clear(context); }
        finally { TimeZone.setDefault(originalZone); }
    }

    @Test public void onceDefinitionsKeepNoSettlementMarks() throws Exception {
        // A one-time definition's state is its done flag alone: paid/unpaid dates handed in
        // with it (e.g. from an old backup) must not become settlement rows that a later
        // edit to recurring would resurrect, and settling must drop any leftovers.
        long day = Commitment.startOfDay(System.currentTimeMillis());
        Commitment once = new Commitment("once-marks", "One", -10L, Commitment.ONCE, day, null,
            false, Collections.singletonList(day), 0,
            Collections.singletonList(civilDay(day, 1)), false, 0);
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
        for (int i = 1; i <= 2501; i++) paid.add(civilDay(start, i));
        List<Long> unpaid = new ArrayList<>();
        for (int i = 3001; i <= 5501; i++) unpaid.add(civilDay(start, i));
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
        assertTrue(stored.isSettled(civilDay(start, 2501)));
        assertFalse(stored.isSettled(civilDay(start, 3001)));
    }

    @Test public void undoUnderLegacyWatermarkStoresOnlyOneOverride() throws Exception {
        long start = Commitment.startOfDay(System.currentTimeMillis());
        Commitment source = new Commitment("watermark", "legacy", -10, Commitment.DAILY,
            start, null, false, null, civilDay(start, 10), false, 0);
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .putString(BalanceData.KEY_COMMITMENTS,
                new org.json.JSONObject().put(BalanceData.KEY_COMMITMENTS,
                    new org.json.JSONArray().put(source.toJson())).toString())
            .commit();

        assertTrue(CommitmentStore.isSettled(context, source.id, civilDay(start, 2)));
        assertTrue(CommitmentStore.undo(context, source.id, civilDay(start, 2)));
        assertFalse(CommitmentStore.isSettled(context, source.id, civilDay(start, 2)));
        assertTrue(CommitmentStore.isSettled(context, source.id, civilDay(start, 3)));
        Commitment stored = CommitmentStore.get(context, source.id);
        assertEquals(1, stored.unpaid.size());
        assertEquals(civilDay(start, 2), stored.unpaid.get(0).longValue());
        assertEquals(0, stored.paid.size());
        assertFalse(context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE)
            .contains(BalanceData.KEY_COMMITMENTS));
    }

    @Test public void legacyMigrationPreservesEverySettlementDate() throws Exception {
        long start = Commitment.startOfDay(System.currentTimeMillis());
        List<Long> paid = new ArrayList<>();
        for (int i = 1; i <= 2001; i++) paid.add(civilDay(start, i));
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
            1, Commitment.ONCE, civilDay(start, i), null, false, null, false, 0));
        Commitment recurring = new Commitment("settlements", "settlements", 1, Commitment.DAILY,
            start, null, false, null, false, 0);
        definitions.add(recurring);
        assertTrue(CommitmentStore.replace(context, definitions));
        List<Long> expectedDates = new ArrayList<>();
        for (int i = 1; i <= 61; i++) expectedDates.add(civilDay(start, i));
        CommitmentStore.runInTransaction(context, editor -> {
            for (long date : expectedDates) assertTrue(editor.settle(recurring.id, date));
            return null;
        });

        int definitionsSeen = 0;
        List<String> expectedIds = new ArrayList<>();
        for (Commitment definition : definitions) expectedIds.add(definition.id);
        List<String> actualIds = new ArrayList<>();
        Set<String> uniqueIds = new HashSet<>();
        int definitionPages = 0;
        long definitionCursor = 0;
        CommitmentStore.DefinitionPage page = CommitmentStore.pageDefinitions(context, 0, 11);
        while (true) {
            assertTrue("definition paging must terminate", ++definitionPages <= (74 + 10) / 11);
            assertFalse("each fixture page must contain definitions", page.rows.isEmpty());
            assertTrue(page.rows.size() <= 11);
            assertTrue("definition cursor must advance", page.nextId > definitionCursor);
            definitionCursor = page.nextId;
            for (Commitment definition : page.rows) {
                assertTrue("duplicate definition " + definition.id, uniqueIds.add(definition.id));
                actualIds.add(definition.id);
            }
            definitionsSeen += page.rows.size();
            if (!page.hasMore) break;
            page = CommitmentStore.pageDefinitions(context, page, 11);
        }
        assertEquals(74, definitionsSeen);
        assertEquals(74, uniqueIds.size());
        assertEquals("definitions retain insertion order", expectedIds, actualIds);
        assertEquals((74 + 10) / 11, definitionPages);

        int settlementsSeen = 0;
        int settlementPages = 0;
        long settlementCursor = 0;
        List<Long> actualDates = new ArrayList<>();
        Set<Long> uniqueDates = new HashSet<>();
        CommitmentStore.SettlementPage settlements = CommitmentStore.windowPage(context,
            recurring.id, start, civilDay(start, 100), 0, 13);
        while (true) {
            assertTrue("settlement paging must terminate", ++settlementPages <= (61 + 12) / 13);
            assertFalse("each fixture page must contain settlements", settlements.rows.isEmpty());
            assertTrue(settlements.rows.size() <= 13);
            assertTrue("settlement cursor must advance", settlements.nextId > settlementCursor);
            for (CommitmentStore.Settlement settlement : settlements.rows) {
                assertTrue("settlement row IDs must increase", settlement.id > settlementCursor);
                settlementCursor = settlement.id;
                assertEquals(recurring.id, settlement.definitionId);
                assertTrue(settlement.settled);
                assertTrue("duplicate settlement date " + settlement.date,
                    uniqueDates.add(settlement.date));
                actualDates.add(settlement.date);
            }
            assertEquals(settlementCursor, settlements.nextId);
            settlementsSeen += settlements.rows.size();
            if (!settlements.hasMore) break;
            settlements = CommitmentStore.pageSettlements(context, settlements, 13);
        }
        assertEquals(61, settlementsSeen);
        assertEquals(61, uniqueDates.size());
        assertEquals("row order retains the fixture's exact civil dates", expectedDates, actualDates);
        assertEquals((61 + 12) / 13, settlementPages);
    }

    @Test public void getExposesImmutablePagedSettlementList() throws Exception {
        long start = Commitment.startOfDay(System.currentTimeMillis());
        List<Long> paid = new ArrayList<>();
        for (int i = 1; i <= 257; i++) paid.add(civilDay(start, i));
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
        CommitmentStore.settle(context, definition.id, civilDay(start, 10));
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

    /** Civil days, not elapsed 24-hour blocks: these fixtures cross New York DST transitions. */
    private static long civilDay(long start, int days) {
        return Instant.ofEpochMilli(start).atZone(FIXTURE_ZONE).toLocalDate().plusDays(days)
            .atStartOfDay(FIXTURE_ZONE).toInstant().toEpochMilli();
    }
}
