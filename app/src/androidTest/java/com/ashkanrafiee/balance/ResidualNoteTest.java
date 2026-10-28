package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Focused tests for residual note identity and the shared metadata facade. */
@RunWith(AndroidJUnit4.class)
public class ResidualNoteTest {

    private static final long FROM = 1_000_000_000L;
    private static final long TO = 1_000_100_000L;

    private Context context;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MetadataStore.reset(context, true);
    }

    @After public void tearDown() throws Exception {
        MetadataStore.reset(context, true);
    }

    private static Residual residual(long amount, int movements) {
        return new Residual("Tejarat", "9102", FROM, TO, amount, movements);
    }

    @Test public void noteKey_usesOnlyTheBracketingIdentity() {
        Residual first = residual(-500_000L, 1);
        Residual changedDerivedValues = residual(700_000L, 12);

        assertEquals(BalanceData.noteKey(first), BalanceData.noteKey(changedDerivedValues));
        assertEquals(first.noteKey(), BalanceData.noteKey(first));
        assertTrue(BalanceData.noteKey(first).startsWith("r:"));
        assertFalse(BalanceData.noteKey(first).equals(BalanceData.noteKey(
            new Residual("Tejarat", "9102", FROM, TO + 1, -500_000L, 1))));
        assertFalse(BalanceData.noteKey(first).equals(BalanceData.noteKey(
            new Residual("Tejarat", "9103", FROM, TO, -500_000L, 1))));
    }

    @Test public void noteKey_isNamespacedAwayFromTransactionNotes() {
        Residual residual = residual(-500_000L, 1);
        Transaction transaction = new Transaction("Tejarat", "9102", TO, -500_000L, "sig");

        assertFalse(BalanceData.noteKey(residual).equals(BalanceData.noteKey(transaction)));
    }

    @Test public void note_roundTripsThroughTheSharedMetadataStore() {
        Residual residual = residual(-500_000L, 1);

        assertNull(BalanceData.getNote(context, residual));
        BalanceData.setNote(context, residual, "unaccounted rent");
        assertEquals("unaccounted rent", BalanceData.getNote(context, residual));
        assertEquals("unaccounted rent",
            BalanceData.readNotes(context).get(BalanceData.noteKey(residual)));

        BalanceData.setNote(context, residual, "   ");
        assertNull(BalanceData.getNote(context, residual));
    }

    @Test public void reset_keepsResidualNotesUnlessNotesAreRequested() {
        Residual residual = residual(-500_000L, 1);
        BalanceData.setNote(context, residual, "keep this gap");

        BalanceData.reset(context, false);
        assertEquals("keep this gap", BalanceData.getNote(context, residual));

        BalanceData.reset(context, true);
        assertNull(BalanceData.getNote(context, residual));
    }
}
