package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;

/** Storage boundary for an editor that remains open while its original residual resolves. */
@RunWith(AndroidJUnit4.class)
public class ResidualEditorResolutionTest {
    private static final long FROM = 1_000_000_000L;
    private static final long TO = FROM + 300_000L;
    private static final String NOTE = "typed before the delayed message arrived";
    private Context context;
    private Residual gap;
    private Transaction opener, closer, missing;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BalanceData.reset(context, true);
        opener = movement(FROM, 100, 1000L, "opener");
        closer = movement(TO, -100, 400L, "closer");
        missing = movement(FROM + 60_000L, -500, 500L, "delayed");
        write(opener, closer);
        List<Residual> gaps = Residual.between(Arrays.asList(opener, closer));
        assertEquals(1, gaps.size());
        gap = gaps.get(0);
        assertEquals(-500L, gap.amount);
    }

    @After public void tearDown() {
        BalanceData.reset(context, true);
        HistoryWarmup.invalidate();
    }

    @Test public void saveAfterUniqueResolution_movesNoteAndCleansOldProvenance() {
        write(opener, missing, closer);
        BalanceData.setNote(context, gap, NOTE);
        assertEquals(NOTE, BalanceData.getNote(context, missing));
        assertNull(BalanceData.getNote(context, gap));
        assertFalse(BalanceData.readResidualNoteProvenance(context).containsKey(gap.noteKey()));
        assertFalse(DataGeneration.dataPrefs(context)
            .contains(BalanceData.KEY_PENDING_RESIDUAL_NOTE_MIGRATIONS));
    }

    @Test public void partialResolution_keepsNoteUnattached() {
        Transaction partial = movement(FROM + 60_000L, -200, 800L, "partial");
        write(opener, partial, closer);
        BalanceData.setNote(context, gap, NOTE);
        assertEquals(NOTE, BalanceData.getNote(context, gap));
        assertNull(BalanceData.getNote(context, partial));
        assertTrue(BalanceData.readResidualNoteProvenance(context).containsKey(gap.noteKey()));
    }

    @Test public void inconsistentIntermediateBalance_doesNotTurnAggregateClosureIntoProof() {
        Transaction inconsistent = movement(missing.date, missing.amount, 700L, "inconsistent");
        write(opener, inconsistent, closer);
        assertEquals("the intermediate balance splits the old gap into opposite gaps", 2,
            Residual.between(Arrays.asList(opener, inconsistent, closer)).size());
        BalanceData.setNote(context, gap, NOTE);
        assertEquals(NOTE, BalanceData.getNote(context, gap));
        assertNull(BalanceData.getNote(context, inconsistent));
    }

    @Test public void multipleResolvingMovements_areNotGuessedOntoOneRow() {
        Transaction first = movement(FROM + 60_000L, -200, 800L, "first");
        Transaction second = movement(FROM + 120_000L, -300, 500L, "second");
        write(opener, first, second, closer);
        assertTrue(Residual.between(Arrays.asList(opener, first, second, closer)).isEmpty());
        BalanceData.setNote(context, gap, NOTE);
        assertEquals(NOTE, BalanceData.getNote(context, gap));
        assertNull(BalanceData.getNote(context, first));
        assertNull(BalanceData.getNote(context, second));
    }

    @Test public void extraMovementsWithTheSameNetAmount_doNotIdentifyOneMissingEvent() {
        Transaction offset = movement(FROM + 120_000L, 10, 510L, "offset");
        Transaction opposite = movement(FROM + 180_000L, -10, 500L, "opposite");
        write(opener, missing, offset, opposite, closer);
        assertTrue(Residual.between(Arrays.asList(opener, missing, offset, opposite, closer)).isEmpty());
        BalanceData.setNote(context, gap, NOTE);
        assertEquals(NOTE, BalanceData.getNote(context, gap));
        assertNull(BalanceData.getNote(context, missing));
    }

    @Test public void ambiguousStatementOrder_keepsNoteUnattached() {
        // A retained legacy movement lacked its balance. Enriching it and adding the missing
        // movement at the same instant adds just one row, but cannot prove statement order.
        Transaction legacy = movement(missing.date, 100, null, "known");
        Transaction end = movement(TO, -100, 500L, "end");
        gap = Residual.between(Arrays.asList(opener, legacy, end)).get(0);
        assertEquals(-500L, gap.amount);
        Transaction enriched = movement(missing.date, 100, 600L, "known");
        write(opener, missing, enriched, end);
        BalanceData.setNote(context, gap, NOTE);
        assertEquals(NOTE, BalanceData.getNote(context, gap));
        assertNull(BalanceData.getNote(context, missing));
        assertNull(BalanceData.getNote(context, enriched));
    }

    @Test public void matchingAmountsOnTwoStatements_doNotIdentifyWhichMovementWasMissing() {
        Transaction legacy = movement(FROM + 60_000L, -500, null, "known");
        Transaction end = movement(TO, -100, -100L, "end");
        gap = Residual.between(Arrays.asList(opener, legacy, end)).get(0);
        assertEquals(-500L, gap.amount);
        Transaction enriched = movement(legacy.date, -500, 500L, "known");
        Transaction delayed = movement(FROM + 120_000L, -500, 0L, "delayed");
        write(opener, enriched, delayed, end);
        assertTrue(Residual.between(Arrays.asList(opener, enriched, delayed, end)).isEmpty());
        BalanceData.setNote(context, gap, NOTE);
        assertEquals(NOTE, BalanceData.getNote(context, gap));
        assertNull(BalanceData.getNote(context, enriched));
        assertNull(BalanceData.getNote(context, delayed));
    }

    @Test public void realMovementNote_isNotOverwrittenOrUsedToDiscardNewWords() {
        write(opener, missing, closer);
        BalanceData.setNote(context, missing, "independently edited movement");
        BalanceData.setNote(context, gap, NOTE);
        assertEquals("independently edited movement", BalanceData.getNote(context, missing));
        assertEquals(NOTE, BalanceData.getNote(context, gap));
        assertFalse(DataGeneration.dataPrefs(context)
            .contains(BalanceData.KEY_PENDING_RESIDUAL_NOTE_MIGRATIONS));
    }

    @Test public void clearFromStaleEditor_cancelsPendingAliasWithoutClearingMovementNote()
            throws Exception {
        BalanceData.setNote(context, gap, "old residual note");
        write(opener, missing, closer);
        BalanceData.setNote(context, missing, "real movement note");
        JSONObject pending = new JSONObject().put(gap.noteKey(), BalanceData.noteKey(missing));
        assertTrue(DataGeneration.dataPrefs(context).edit().putString(
            BalanceData.KEY_PENDING_RESIDUAL_NOTE_MIGRATIONS,
            BalanceData.encryptStorePayload(pending.toString())).commit());
        BalanceData.setNote(context, gap, "   ");
        assertNull(BalanceData.getNote(context, gap));
        assertEquals("real movement note", BalanceData.getNote(context, missing));
        assertFalse(BalanceData.readResidualNoteProvenance(context).containsKey(gap.noteKey()));
        assertFalse(DataGeneration.dataPrefs(context)
            .contains(BalanceData.KEY_PENDING_RESIDUAL_NOTE_MIGRATIONS));
    }

    @Test public void conflictedRetry_keepsBothNotesAndResidualProvenance() throws Exception {
        BalanceData.setNote(context, gap, NOTE);
        write(opener, missing, closer);
        queueAlias();
        // The original editor already saved its source text and retry route. Before that route
        // can finish, a separate edit gives the real movement its own independently written note.
        BalanceData.setNote(context, missing, "independent movement note");
        retryAliases();
        assertEquals("independent movement note", BalanceData.getNote(context, missing));
        assertEquals("a retry must not discard the residual's different saved words", NOTE,
            BalanceData.getNote(context, gap));
        assertTrue(BalanceData.readResidualNoteProvenance(context).containsKey(gap.noteKey()));
        assertFalse("a conflicted alias must not unexpectedly reattach on a later edit",
            DataGeneration.dataPrefs(context).contains(BalanceData.KEY_PENDING_RESIDUAL_NOTE_MIGRATIONS));

        BalanceData.setNote(context, missing, null);
        retryAliases();
        assertNull(BalanceData.getNote(context, missing));
        assertEquals(NOTE, BalanceData.getNote(context, gap));
    }

    @Test public void retryAfterMetadataAlreadyMoved_cleansOnlyTheCompletedRoute() throws Exception {
        BalanceData.setNote(context, gap, NOTE);
        write(opener, missing, closer);
        queueAlias();
        // Simulates a process dying after the atomic metadata migration but before queue cleanup.
        MetadataStore.migrateKeys(context, java.util.Collections.singletonMap(
            gap.noteKey(), BalanceData.noteKey(missing)));
        assertNull(BalanceData.getNote(context, gap));
        assertEquals(NOTE, BalanceData.getNote(context, missing));
        retryAliases();
        assertEquals(NOTE, BalanceData.getNote(context, missing));
        assertNull(BalanceData.getNote(context, gap));
        assertFalse(BalanceData.readResidualNoteProvenance(context).containsKey(gap.noteKey()));
        assertFalse(DataGeneration.dataPrefs(context)
            .contains(BalanceData.KEY_PENDING_RESIDUAL_NOTE_MIGRATIONS));
    }

    @Test public void competingResidualRetryRoutes_keepBothSourcesUnattached() throws Exception {
        BalanceData.setNote(context, gap, NOTE);
        Residual other = new Residual(gap.bank, gap.account, gap.fromDate - 1, gap.toDate,
            gap.amount, gap.movements);
        BalanceData.setNote(context, other, "a different original bracket");
        write(opener, missing, closer);
        JSONObject pending = new JSONObject().put(gap.noteKey(), BalanceData.noteKey(missing))
            .put(other.noteKey(), BalanceData.noteKey(missing));
        assertTrue(DataGeneration.dataPrefs(context).edit().putString(
            BalanceData.KEY_PENDING_RESIDUAL_NOTE_MIGRATIONS,
            BalanceData.encryptStorePayload(pending.toString())).commit());
        retryAliases();
        assertNull(BalanceData.getNote(context, missing));
        assertEquals(NOTE, BalanceData.getNote(context, gap));
        assertEquals("a different original bracket", BalanceData.getNote(context, other));
        assertTrue(BalanceData.readResidualNoteProvenance(context).containsKey(gap.noteKey()));
        assertTrue(BalanceData.readResidualNoteProvenance(context).containsKey(other.noteKey()));
        assertFalse(DataGeneration.dataPrefs(context)
            .contains(BalanceData.KEY_PENDING_RESIDUAL_NOTE_MIGRATIONS));
    }

    private void queueAlias() throws Exception {
        JSONObject pending = new JSONObject().put(gap.noteKey(), BalanceData.noteKey(missing));
        assertTrue(DataGeneration.dataPrefs(context).edit().putString(
            BalanceData.KEY_PENDING_RESIDUAL_NOTE_MIGRATIONS,
            BalanceData.encryptStorePayload(pending.toString())).commit());
    }

    private void retryAliases() throws Exception {
        java.lang.reflect.Method retry = BalanceData.class.getDeclaredMethod(
            "completePendingResidualNoteMigrations", Context.class, List.class);
        retry.setAccessible(true);
        // Production scan/setter callers own this monitor across the metadata read and migration.
        synchronized (BalanceData.class) {
            retry.invoke(null, context, Arrays.asList(opener, missing, closer));
        }
    }

    private void write(Transaction... transactions) {
        assertTrue(BalanceData.writeTransactions(context, Arrays.asList(transactions)));
    }

    private static Transaction movement(long date, long amount, Long balance, String signature) {
        return new Transaction("Tejarat", "9102", date, amount, balance, signature, null);
    }
}
