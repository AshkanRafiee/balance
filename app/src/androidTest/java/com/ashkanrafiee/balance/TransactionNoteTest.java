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

import java.util.Arrays;
import java.util.Map;

/**
 * Tests for per-transaction notes ({@link BalanceData}): they are stored separately from the dedup
 * fingerprints (so they never perturb messageSig/contentHash/txIdentityKey), they follow the same
 * physical transaction everywhere via the parse-independent content digest, blank text removes a
 * note, and a hard reset only drops them when explicitly requested.
 */
@RunWith(AndroidJUnit4.class)
public class TransactionNoteTest {

    private static final long T = 1_000_000_000L;

    private Context ctx;

    @Before public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() {
        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
    }

    private Transaction tx(String bank, String account, String sig, String content) {
        return new Transaction(bank, account, T, 500_000L, sig, content);
    }

    @Test public void note_savedAndRead() {
        Transaction t = tx("Tejarat", null, "sig-A", "content-A");
        assertNull(BalanceData.getNote(ctx, t));
        BalanceData.setNote(ctx, t, "picked up from the cashier");
        assertEquals("picked up from the cashier", BalanceData.getNote(ctx, t));
    }

    @Test public void note_keyedByContent_notByAccountOrBank() {
        // The same physical message re-parsed under a new account/bank fingerprint keeps one note.
        Transaction fresh = tx("Melli", "1110000222", "sig-NEW", "content-same");
        Transaction legacy = tx("Melli", null, "sig-OLD", "content-same");
        assertEquals(BalanceData.noteKey(fresh), BalanceData.noteKey(legacy));

        BalanceData.setNote(ctx, fresh, "one note");
        assertEquals("one note", BalanceData.getNote(ctx, legacy));

        Transaction other = tx("Tejarat", null, "sig-B", "content-OTHER");
        assertFalse(BalanceData.noteKey(fresh).equals(BalanceData.noteKey(other)));
        assertNull(BalanceData.getNote(ctx, other));
    }

    @Test public void note_neverTouchesDedupFingerprints() {
        Transaction t = tx("Tejarat", "9102", "sig-A", "content-A");
        String identity = BalanceData.txIdentityKey(t);
        String key = BalanceData.noteKey(t);
        // The note key is content-based, i.e. detached from the amount/balance/account fingerprint.
        assertFalse(key.equals(identity));

        BalanceData.setNote(ctx, t, "a note");
        assertEquals(identity, BalanceData.txIdentityKey(t));
        assertEquals(key, BalanceData.noteKey(t));

        BalanceData.setNote(ctx, t, null);
        assertEquals(identity, BalanceData.txIdentityKey(t));
    }

    @Test public void note_legacyWithoutContent_keysByIdentity() {
        Transaction legacy = new Transaction("Tejarat", null, T, -500_000L, "sig-L");
        assertEquals(BalanceData.txIdentityKey(legacy), BalanceData.noteKey(legacy));
    }

    @Test public void note_blankOrNull_removes() {
        Transaction t = tx("Tejarat", null, "sig-A", "content-A");
        BalanceData.setNote(ctx, t, "keep");
        BalanceData.setNote(ctx, t, "   ");
        assertNull(BalanceData.getNote(ctx, t));

        BalanceData.setNote(ctx, t, "keep again");
        BalanceData.setNote(ctx, t, null);
        assertNull(BalanceData.getNote(ctx, t));
        assertTrue(BalanceData.readNotes(ctx).isEmpty());
    }

    @Test public void note_overlong_isCapped() {
        Transaction t = tx("Tejarat", null, "sig-A", "content-A");
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < 600; i++) buf.append('a');
        BalanceData.setNote(ctx, t, buf.toString());
        String stored = BalanceData.getNote(ctx, t);
        assertTrue(stored.length() <= BalanceData.MAX_NOTE_LENGTH);
    }

    @Test public void note_persistsInTheEncryptedStore() {
        Transaction t = tx("Tejarat", null, "sig-A", "content-A");
        BalanceData.setNote(ctx, t, "stored off-device safe");
        Map<String, String> notes = BalanceData.readNotes(ctx);
        assertEquals("stored off-device safe", notes.get(BalanceData.noteKey(t)));

        String serialized;
        try {
            serialized = BalanceData.serializeNotes(notes);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        Map<String, String> round = BalanceData.deserializeNotes(serialized);
        assertEquals("stored off-device safe", round.get(BalanceData.noteKey(t)));
    }

    @Test public void reset_keepsNotesByDefault_deletesWhenRequested() {
        // A reset wipes balances and transactions so the next scan rebuilds from the inbox; the note
        // map is exactly what lets a note reattach to the same physical SMS afterwards. So the reset
        // must keep the map unless the user opted into deleting it.
        Transaction tA = tx("Tejarat", null, "sig-A", "content-A");
        Transaction tB = tx("Melli", null, "sig-B", "content-B");
        BalanceData.writeTransactions(ctx, Arrays.asList(tA, tB));
        BalanceData.setNote(ctx, tA, "survivor");

        BalanceData.reset(ctx, false);
        assertEquals("survivor", BalanceData.readNotes(ctx).get(BalanceData.noteKey(tA)));
        assertFalse(BalanceData.readNotes(ctx).isEmpty());

        BalanceData.setNote(ctx, tB, "doomed");
        BalanceData.reset(ctx, true);
        assertNull(BalanceData.readNotes(ctx).get(BalanceData.noteKey(tB)));
        assertNull(BalanceData.readNotes(ctx).get(BalanceData.noteKey(tA)));
        assertTrue(BalanceData.readNotes(ctx).isEmpty());
    }
}