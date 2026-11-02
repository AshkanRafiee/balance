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
        Transaction first = movement(missing.date, -500, 500L, "same-time-first");
        Transaction second = movement(missing.date, 0, 500L, "same-time-second");
        write(opener, first, second, closer);
        BalanceData.setNote(context, gap, NOTE);
        assertEquals(NOTE, BalanceData.getNote(context, gap));
        assertNull(BalanceData.getNote(context, first));
        assertNull(BalanceData.getNote(context, second));
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

    private void write(Transaction... transactions) {
        assertTrue(BalanceData.writeTransactions(context, Arrays.asList(transactions)));
    }

    private static Transaction movement(long date, long amount, Long balance, String signature) {
        return new Transaction("Tejarat", "9102", date, amount, balance, signature, null);
    }
}
