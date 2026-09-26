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
import java.util.HashMap;
import java.util.Map;

/**
 * Tests for the reasons a bank states ({@link BalanceData}): they are read out of the message by
 * {@link BankRules#extractReason}, stored in their own map beside the user's notes, merged additively
 * so a later scan never overwrites what is already there or touches a note, captioned in the app's
 * language, and dropped by a reset because the next scan re-reads them from the inbox.
 */
@RunWith(AndroidJUnit4.class)
public class TransactionReasonTest {

    private static final long T = 1_000_000_000L;

    /** Real Blu event titles, as {@link BankRules#extractReason} returns them. */
    private static final String TOPUP = "شارژ شدی";
    private static final String BILL = "پرداخت قبض";
    private static final String TRANSFER_IN = "دریافت پل";

    private Context ctx;
    private String originalTag;

    @Before public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalTag = LocaleHelper.currentTag(ctx);
        LocaleHelper.setLanguage(ctx, "en");
        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() {
        LocaleHelper.setLanguage(ctx, originalTag);
        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
    }

    private Transaction tx(String bank, String account, String sig, String content) {
        return new Transaction(bank, account, T, 500_000L, sig, content);
    }

    private Map<String, String> reasonsFor(Transaction t, String reason) {
        Map<String, String> reasons = new HashMap<>();
        reasons.put(BalanceData.noteKey(t), reason);
        return reasons;
    }

    @Test public void reason_detectedByAScan_isReadableBack() {
        Transaction t = tx("Blu", null, "sig-A", "content-A");
        assertTrue(BalanceData.readReasons(ctx).isEmpty());

        BalanceData.mergeReasons(ctx, reasonsFor(t, TOPUP));

        assertEquals(TOPUP, BalanceData.readReasons(ctx).get(BalanceData.noteKey(t)));
    }

    @Test public void reason_andNote_liveSideBySide() {
        // The two are separate stores on purpose: a movement the bank explained and a movement the
        // user wrote about both keep their own text, and neither stands in for the other.
        Transaction t = tx("Blu", null, "sig-A", "content-A");
        BalanceData.mergeReasons(ctx, reasonsFor(t, TOPUP));
        BalanceData.setNote(ctx, t, "my number");

        assertEquals(TOPUP, BalanceData.readReasons(ctx).get(BalanceData.noteKey(t)));
        assertEquals("my number", BalanceData.getNote(ctx, t));
        assertEquals("my number", BalanceData.readNotes(ctx).get(BalanceData.noteKey(t)));
        assertEquals(1, BalanceData.readReasons(ctx).size());
        assertEquals(1, BalanceData.readNotes(ctx).size());
    }

    @Test public void reason_neverOverwritesTheUsersNote() {
        // The whole reason for keeping reasons out of the notes store: no scan, however it reads a
        // message, can put a word into a note the user wrote.
        Transaction t = tx("Blu", null, "sig-A", "content-A");
        BalanceData.setNote(ctx, t, "do not lose this");
        Map<String, String> notesBefore = BalanceData.readNotes(ctx);

        BalanceData.mergeReasons(ctx, reasonsFor(t, TOPUP));
        BalanceData.mergeReasons(ctx, reasonsFor(t, BILL));

        assertEquals("do not lose this", BalanceData.getNote(ctx, t));
        assertEquals(notesBefore, BalanceData.readNotes(ctx));
    }

    @Test public void note_neverOverwritesTheStatedReason() {
        Transaction t = tx("Blu", null, "sig-A", "content-A");
        BalanceData.mergeReasons(ctx, reasonsFor(t, TOPUP));

        BalanceData.setNote(ctx, t, "a note");
        BalanceData.setNote(ctx, t, null);

        assertEquals(TOPUP, BalanceData.readReasons(ctx).get(BalanceData.noteKey(t)));
    }

    @Test public void reason_survivesTheUserClearingTheirNote() {
        // Clearing a note is the user saying "I have nothing to add", not "there was nothing here".
        Transaction t = tx("Blu", null, "sig-A", "content-A");
        BalanceData.mergeReasons(ctx, reasonsFor(t, TOPUP));
        BalanceData.setNote(ctx, t, "temp");

        BalanceData.setNote(ctx, t, null);

        assertNull(BalanceData.getNote(ctx, t));
        assertEquals(TOPUP, BalanceData.readReasons(ctx).get(BalanceData.noteKey(t)));
    }

    @Test public void reason_mergeIsAdditive_firstDetectionWins() {
        // A later scan must never rewrite a reason already on record — the stored text is a fact
        // about the message, and merging also never removes a reason whose SMS is long gone.
        Transaction a = tx("Blu", null, "sig-A", "content-A");
        Transaction b = tx("Blu", null, "sig-B", "content-B");
        BalanceData.mergeReasons(ctx, reasonsFor(a, TOPUP));

        BalanceData.mergeReasons(ctx, reasonsFor(a, BILL));
        BalanceData.mergeReasons(ctx, reasonsFor(b, TRANSFER_IN));

        Map<String, String> reasons = BalanceData.readReasons(ctx);
        assertEquals(TOPUP, reasons.get(BalanceData.noteKey(a)));
        assertEquals(TRANSFER_IN, reasons.get(BalanceData.noteKey(b)));
        assertEquals(2, reasons.size());
    }

    @Test public void reason_mergeOfNothing_changesNothing() {
        Transaction t = tx("Blu", null, "sig-A", "content-A");
        BalanceData.mergeReasons(ctx, reasonsFor(t, TOPUP));

        BalanceData.mergeReasons(ctx, null);
        BalanceData.mergeReasons(ctx, new HashMap<String, String>());

        assertEquals(TOPUP, BalanceData.readReasons(ctx).get(BalanceData.noteKey(t)));
        assertEquals(1, BalanceData.readReasons(ctx).size());
    }

    @Test public void reason_keyedByContent_notByAmountOrBank() {
        // The reason must follow the same physical SMS the note does, including across a re-parse
        // that fills in an account number the older rules could not read.
        Transaction fresh = tx("Blu", "3810021456", "sig-NEW", "content-same");
        Transaction legacy = tx("Blu", null, "sig-OLD", "content-same");
        assertEquals(BalanceData.noteKey(fresh), BalanceData.noteKey(legacy));

        BalanceData.mergeReasons(ctx, reasonsFor(fresh, TOPUP));

        assertEquals(TOPUP, BalanceData.readReasons(ctx).get(BalanceData.noteKey(legacy)));
    }

    @Test public void reason_followsLegacyReplacementThroughFullRebuild() {
        // A reason recorded on a pre-content-digest entry lives under its legacy identity; when a full
        // re-scan re-parses the same SMS into a content-bearing entry, the migration carries it over
        // instead of leaving the row without the reason the bank gave.
        Transaction legacy = new Transaction("Blu", null, T, 500_000L, null, null);
        Transaction fresh = new Transaction("Blu", null, T, 500_000L, "sig-1", "content-A");
        assertFalse(BalanceData.noteKey(legacy).equals(BalanceData.noteKey(fresh)));

        BalanceData.mergeReasons(ctx, reasonsFor(legacy, TOPUP));
        Map<Transaction, Transaction> replaced = new HashMap<>();
        replaced.put(legacy, fresh);
        BalanceData.migrateTransactionText(ctx, replaced);

        assertNull(BalanceData.readReasons(ctx).get(BalanceData.noteKey(legacy)));
        assertEquals(TOPUP, BalanceData.readReasons(ctx).get(BalanceData.noteKey(fresh)));
    }

    @Test public void reason_migrationNeverOverwritesTheLaterReason() {
        Transaction legacy = new Transaction("Blu", null, T, 500_000L, null, null);
        Transaction fresh = new Transaction("Blu", null, T, 500_000L, "sig-1", "content-A");
        BalanceData.mergeReasons(ctx, reasonsFor(legacy, TOPUP));
        BalanceData.mergeReasons(ctx, reasonsFor(fresh, BILL));
        Map<Transaction, Transaction> replaced = new HashMap<>();
        replaced.put(legacy, fresh);
        BalanceData.migrateTransactionText(ctx, replaced);

        assertEquals(BILL, BalanceData.readReasons(ctx).get(BalanceData.noteKey(fresh)));
    }

    @Test public void reason_migrationLeavesTheNotesAlone() {
        // Both stores migrate through one pass, so this pins that carrying a reason over never
        // reorders, drops or rewrites the note that was already under the new key.
        Transaction legacy = new Transaction("Blu", null, T, 500_000L, null, null);
        Transaction fresh = new Transaction("Blu", null, T, 500_000L, "sig-1", "content-A");
        BalanceData.setNote(ctx, legacy, "note on the legacy entry");
        BalanceData.setNote(ctx, fresh, "note on the fresh entry");
        BalanceData.mergeReasons(ctx, reasonsFor(legacy, TOPUP));
        Map<Transaction, Transaction> replaced = new HashMap<>();
        replaced.put(legacy, fresh);
        BalanceData.migrateTransactionText(ctx, replaced);

        assertEquals("note on the fresh entry", BalanceData.getNote(ctx, fresh));
        assertEquals(TOPUP, BalanceData.readReasons(ctx).get(BalanceData.noteKey(fresh)));
    }

    @Test public void reason_persistsInTheEncryptedStore() {
        Transaction t = tx("Blu", null, "sig-A", "content-A");
        BalanceData.mergeReasons(ctx, reasonsFor(t, TOPUP));

        // What sits in the preferences is ciphertext, not the bank's wording in the clear: this is
        // the store the notes use too, and both are readable only through the app.
        String stored = ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE)
            .getString(BalanceData.KEY_TX_REASONS, "");
        assertFalse("reasons must not sit in the prefs as plaintext", stored.contains(TOPUP));
        assertEquals(TOPUP, BalanceData.readReasons(ctx).get(BalanceData.noteKey(t)));
    }

    @Test public void reason_storeRoundTripsThroughTheSharedSerializer() throws Exception {
        // Backup and restore speak the same serialized form the store itself does, so a reason
        // survives being written out and read back in one piece.
        Transaction t = tx("Blu", null, "sig-A", "content-A");
        BalanceData.mergeReasons(ctx, reasonsFor(t, TOPUP));

        String serialized;
        try {
            serialized = BalanceData.serializeTextMap(BalanceData.readReasons(ctx));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertEquals(TOPUP, BalanceData.deserializeTextMap(serialized).get(BalanceData.noteKey(t)));
    }

    @Test public void reason_isCaptionedInTheAppLanguage() {
        // What is stored is the bank's own title, so the caption follows the app's language at the
        // moment it is shown rather than being frozen into the store.
        assertEquals("Phone top-up", BankRules.reasonCaption(ctx, TOPUP));
        assertEquals("Instant transfer in", BankRules.reasonCaption(ctx, TRANSFER_IN));
        LocaleHelper.setLanguage(ctx, "fa");
        Context fa = LocaleHelper.wrap(ctx);
        assertEquals("شارژ تلفن همراه", BankRules.reasonCaption(fa, TOPUP));
    }

    @Test public void reason_storedTextThisBuildCannotCaption_showsNothing() {
        // Storage is not the filter: a title with no caption — a promotion, or one from an older
        // build — simply has no caption, so nothing appears on the row.
        assertNull(BankRules.reasonCaption(ctx, "برای وام گرفتن وقت تنگه"));
        assertNull(BankRules.reasonCaption(ctx, ""));
        assertNull(BankRules.reasonCaption(ctx, null));
    }

    @Test public void reset_dropsTheReasonsAndKeepsTheNotesByDefault() {
        // The reasons are the inbox's to state: a reset wipes the transactions so the next scan
        // re-reads every message, and the re-read brings the reasons back with it. A note the user
        // typed has no such source, so it stays unless they asked for it to go.
        Transaction t = tx("Blu", null, "sig-A", "content-A");
        BalanceData.writeTransactions(ctx, Arrays.asList(t));
        BalanceData.mergeReasons(ctx, reasonsFor(t, TOPUP));
        BalanceData.setNote(ctx, t, "survivor");

        BalanceData.reset(ctx, false);

        assertTrue(BalanceData.readReasons(ctx).isEmpty());
        assertEquals("survivor", BalanceData.readNotes(ctx).get(BalanceData.noteKey(t)));
    }

    @Test public void reset_deletingTheNotes_dropsBoth() {
        Transaction t = tx("Blu", null, "sig-A", "content-A");
        BalanceData.writeTransactions(ctx, Arrays.asList(t));
        BalanceData.mergeReasons(ctx, reasonsFor(t, TOPUP));
        BalanceData.setNote(ctx, t, "doomed");

        BalanceData.reset(ctx, true);

        assertTrue(BalanceData.readReasons(ctx).isEmpty());
        assertTrue(BalanceData.readNotes(ctx).isEmpty());
    }
}
