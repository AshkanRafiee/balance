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
 * Tests for the channels a bank states ({@link BalanceData}): they are read out of the message by
 * {@link BankRules#extractChannel}, stored in their own map beside the user's notes and the bank's
 * reasons, merged additively so a later scan never overwrites what is already there or touches a
 * note, captioned in the app's language, and dropped by a reset because the next scan re-reads them
 * from the inbox.
 */
@RunWith(AndroidJUnit4.class)
public class TransactionChannelTest {

    private static final long T = 1_000_000_000L;

    /** Real channels, as {@link BankRules#extractChannel} returns them. */
    private static final String SHETAB = "شتاب";
    private static final String BRANCH = "شعبه";
    private static final String SEP = "سامانه پل (پرداخت لحظه ای)";

    /** A real Blu reason, to prove the three stores stay three stores. */
    private static final String TOPUP = "شارژ شدی";

    private Context ctx;
    private String originalTag;

    @Before public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalTag = LocaleHelper.currentTag(ctx);
        LocaleHelper.setLanguage(ctx, "en");
        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
        FinancialTestStore.wipe(ctx);
    }

    @After public void tearDown() throws Exception {
        LocaleHelper.setLanguage(ctx, originalTag);
        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
        FinancialTestStore.wipe(ctx);
    }

    private Transaction tx(String bank, String account, String sig, String content) {
        return new Transaction(bank, account, T, 500_000L, sig, content);
    }

    private Map<String, String> channelsFor(Transaction t, String channel) {
        Map<String, String> channels = new HashMap<>();
        channels.put(BalanceData.noteKey(t), channel);
        return channels;
    }

    private Map<String, String> reasonsFor(Transaction t, String reason) {
        Map<String, String> reasons = new HashMap<>();
        reasons.put(BalanceData.noteKey(t), reason);
        return reasons;
    }

    @Test public void channel_detectedByAScan_isReadableBack() throws Exception {
        Transaction t = tx("Tejarat", "01350000000", "sig-A", "content-A");
        assertTrue(BalanceData.readChannels(ctx).isEmpty());

        FinancialTestStore.mergeChannels(ctx, channelsFor(t, SHETAB));

        assertEquals(SHETAB, BalanceData.readChannels(ctx).get(BalanceData.noteKey(t)));
    }

    @Test public void channel_noteAndReason_liveSideBySide() throws Exception {
        // Three stores on purpose: what the user wrote, what the bank said it was for and how it
        // happened. A movement can have any combination and none of them stands in for another.
        Transaction t = tx("Tejarat", "01350000000", "sig-A", "content-A");
        FinancialTestStore.mergeChannels(ctx, channelsFor(t, SHETAB));
        FinancialTestStore.mergeReasons(ctx, reasonsFor(t, TOPUP));
        BalanceData.setNote(ctx, t, "my number");

        assertEquals(SHETAB, BalanceData.readChannels(ctx).get(BalanceData.noteKey(t)));
        assertEquals(TOPUP, BalanceData.readReasons(ctx).get(BalanceData.noteKey(t)));
        assertEquals("my number", BalanceData.readNotes(ctx).get(BalanceData.noteKey(t)));
        assertEquals(1, BalanceData.readChannels(ctx).size());
        assertEquals(1, BalanceData.readReasons(ctx).size());
        assertEquals(1, BalanceData.readNotes(ctx).size());
    }

    @Test public void channel_neverOverwritesTheUsersNote() throws Exception {
        // The whole reason for keeping channels out of the notes store: no scan, however it reads a
        // message, can put a word into a note the user wrote.
        Transaction t = tx("Tejarat", "01350000000", "sig-A", "content-A");
        BalanceData.setNote(ctx, t, "do not lose this");
        Map<String, String> notesBefore = BalanceData.readNotes(ctx);

        FinancialTestStore.mergeChannels(ctx, channelsFor(t, SHETAB));
        FinancialTestStore.mergeChannels(ctx, channelsFor(t, BRANCH));

        assertEquals("do not lose this", BalanceData.getNote(ctx, t));
        assertEquals(notesBefore, BalanceData.readNotes(ctx));
    }

    @Test public void channel_neverOverwritesTheStatedReason() throws Exception {
        // A bank that states both a reason and a channel is read twice, and neither read may land in
        // the other's store.
        Transaction t = tx("Tejarat", "01350000000", "sig-A", "content-A");
        FinancialTestStore.mergeChannels(ctx, channelsFor(t, SHETAB));
        Map<String, String> channelsBefore = BalanceData.readChannels(ctx);

        FinancialTestStore.mergeReasons(ctx, reasonsFor(t, TOPUP));

        assertEquals(channelsBefore, BalanceData.readChannels(ctx));
        assertEquals(SHETAB, BalanceData.readChannels(ctx).get(BalanceData.noteKey(t)));
        assertEquals(TOPUP, BalanceData.readReasons(ctx).get(BalanceData.noteKey(t)));
    }

    @Test public void note_neverOverwritesTheStatedChannel() throws Exception {
        Transaction t = tx("Tejarat", "01350000000", "sig-A", "content-A");
        FinancialTestStore.mergeChannels(ctx, channelsFor(t, SHETAB));

        BalanceData.setNote(ctx, t, "a note");
        BalanceData.setNote(ctx, t, null);

        assertEquals(SHETAB, BalanceData.readChannels(ctx).get(BalanceData.noteKey(t)));
    }

    @Test public void channel_survivesTheUserClearingTheirNote() throws Exception {
        // Clearing a note is the user saying "I have nothing to add", not "there was nothing here".
        Transaction t = tx("Tejarat", "01350000000", "sig-A", "content-A");
        FinancialTestStore.mergeChannels(ctx, channelsFor(t, SHETAB));
        BalanceData.setNote(ctx, t, "temp");

        BalanceData.setNote(ctx, t, null);

        assertNull(BalanceData.getNote(ctx, t));
        assertEquals(SHETAB, BalanceData.readChannels(ctx).get(BalanceData.noteKey(t)));
    }

    @Test public void channel_mergeIsAdditive_firstDetectionWins() throws Exception {
        // A later scan must never rewrite a channel already on record — the stored text is a fact about
        // the message, and merging also never removes a channel whose SMS is long gone.
        Transaction a = tx("Tejarat", "01350000000", "sig-A", "content-A");
        Transaction b = tx("Tejarat", "01350000000", "sig-B", "content-B");
        FinancialTestStore.mergeChannels(ctx, channelsFor(a, SHETAB));

        FinancialTestStore.mergeChannels(ctx, channelsFor(a, BRANCH));
        FinancialTestStore.mergeChannels(ctx, channelsFor(b, SEP));

        Map<String, String> channels = BalanceData.readChannels(ctx);
        assertEquals(SHETAB, channels.get(BalanceData.noteKey(a)));
        assertEquals(SEP, channels.get(BalanceData.noteKey(b)));
        assertEquals(2, channels.size());
    }

    @Test public void channel_mergeOfNothing_changesNothing() throws Exception {
        Transaction t = tx("Tejarat", "01350000000", "sig-A", "content-A");
        FinancialTestStore.mergeChannels(ctx, channelsFor(t, SHETAB));

        FinancialTestStore.mergeChannels(ctx, null);
        FinancialTestStore.mergeChannels(ctx, new HashMap<String, String>());

        assertEquals(SHETAB, BalanceData.readChannels(ctx).get(BalanceData.noteKey(t)));
        assertEquals(1, BalanceData.readChannels(ctx).size());
    }

    @Test public void channel_keyedByContent_notByAmountOrBank() throws Exception {
        // The channel must follow the same physical SMS the note does, including across a re-parse
        // that fills in an account number the older rules could not read.
        Transaction fresh = tx("Tejarat", "01351234567890", "sig-NEW", "content-same");
        Transaction legacy = tx("Tejarat", null, "sig-OLD", "content-same");
        assertEquals(BalanceData.noteKey(fresh), BalanceData.noteKey(legacy));

        FinancialTestStore.mergeChannels(ctx, channelsFor(fresh, SHETAB));

        assertEquals(SHETAB, BalanceData.readChannels(ctx).get(BalanceData.noteKey(legacy)));
    }

    @Test public void channel_followsLegacyReplacementThroughFullRebuild() throws Exception {
        // A channel recorded on a pre-content-digest entry lives under its legacy identity; when a full
        // re-scan re-parses the same SMS into a content-bearing entry, the migration carries it over
        // instead of leaving the row without the way the money moved.
        Transaction legacy = new Transaction("Tejarat", null, T, 500_000L, null, null);
        Transaction fresh = new Transaction("Tejarat", null, T, 500_000L, "sig-1", "content-A");
        assertFalse(BalanceData.noteKey(legacy).equals(BalanceData.noteKey(fresh)));

        FinancialTestStore.mergeChannels(ctx, channelsFor(legacy, SHETAB));
        Map<Transaction, Transaction> replaced = new HashMap<>();
        replaced.put(legacy, fresh);
        FinancialTestStore.migrateTransactionText(ctx, replaced);

        assertNull(BalanceData.readChannels(ctx).get(BalanceData.noteKey(legacy)));
        assertEquals(SHETAB, BalanceData.readChannels(ctx).get(BalanceData.noteKey(fresh)));
    }

    @Test public void channel_migrationNeverOverwritesTheLaterChannel() throws Exception {
        Transaction legacy = new Transaction("Tejarat", null, T, 500_000L, null, null);
        Transaction fresh = new Transaction("Tejarat", null, T, 500_000L, "sig-1", "content-A");
        FinancialTestStore.mergeChannels(ctx, channelsFor(legacy, SHETAB));
        FinancialTestStore.mergeChannels(ctx, channelsFor(fresh, BRANCH));
        Map<Transaction, Transaction> replaced = new HashMap<>();
        replaced.put(legacy, fresh);
        FinancialTestStore.migrateTransactionText(ctx, replaced);

        assertEquals(BRANCH, BalanceData.readChannels(ctx).get(BalanceData.noteKey(fresh)));
    }

    @Test public void channel_migrationLeavesTheNotesAndReasonsAlone() throws Exception {
        // All three stores migrate through one pass, so this pins that carrying a channel over never
        // reorders, drops or rewrites the note or the reason already under the new key.
        Transaction legacy = new Transaction("Tejarat", null, T, 500_000L, null, null);
        Transaction fresh = new Transaction("Tejarat", null, T, 500_000L, "sig-1", "content-A");
        BalanceData.setNote(ctx, legacy, "note on the legacy entry");
        BalanceData.setNote(ctx, fresh, "note on the fresh entry");
        FinancialTestStore.mergeReasons(ctx, reasonsFor(fresh, TOPUP));
        FinancialTestStore.mergeChannels(ctx, channelsFor(legacy, SHETAB));
        Map<Transaction, Transaction> replaced = new HashMap<>();
        replaced.put(legacy, fresh);
        FinancialTestStore.migrateTransactionText(ctx, replaced);

        assertEquals("note on the fresh entry", BalanceData.getNote(ctx, fresh));
        assertEquals(TOPUP, BalanceData.readReasons(ctx).get(BalanceData.noteKey(fresh)));
        assertEquals(SHETAB, BalanceData.readChannels(ctx).get(BalanceData.noteKey(fresh)));
    }

    @Test public void channel_persistsInTheEncryptedStore() throws Exception {
        Transaction t = tx("Tejarat", "01350000000", "sig-A", "content-A");
        FinancialTestStore.mergeChannels(ctx, channelsFor(t, SHETAB));

        // What reaches storage is ciphertext, not the bank's wording in the clear: this is the
        // store the notes and the reasons use too, and all are readable only through the app.
        assertFalse("channels must not reach the files in the clear",
            FinancialTestStore.rawStoreText(ctx).contains(SHETAB));
        assertEquals(SHETAB, BalanceData.readChannels(ctx).get(BalanceData.noteKey(t)));
    }

    @Test public void channel_storeRoundTripsThroughTheSharedSerializer() throws Exception {
        // Backup and restore speak the same serialized form the store itself does, so a channel
        // survives being written out and read back in one piece.
        Transaction t = tx("Tejarat", "01350000000", "sig-A", "content-A");
        FinancialTestStore.mergeChannels(ctx, channelsFor(t, SHETAB));

        String serialized;
        try {
            serialized = BalanceData.serializeTextMap(BalanceData.readChannels(ctx));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertEquals(SHETAB, BalanceData.deserializeTextMap(serialized).get(BalanceData.noteKey(t)));
    }

    @Test public void channel_isCaptionedInTheAppLanguage() throws Exception {
        // What is stored is the bank's own wording, so the caption follows the app's language at the
        // moment it is shown rather than being frozen into the store.
        assertEquals("Shetab", BankRules.channelCaption(ctx, SHETAB));
        assertEquals("Bank branch", BankRules.channelCaption(ctx, BRANCH));
        LocaleHelper.setLanguage(ctx, "fa");
        Context fa = LocaleHelper.wrap(ctx);
        assertEquals("شتاب", BankRules.channelCaption(fa, SHETAB));
        assertEquals("سامانه پل (پرداخت لحظه‌ای)", BankRules.channelCaption(fa, SEP));
    }

    @Test public void channel_storedTextThisBuildCannotCaption_showsNothing() throws Exception {
        // Storage is not the filter: a channel with no caption — one this build has never seen, or one
        // an older build wrote — simply has no caption, so nothing appears on the row.
        assertNull(BankRules.channelCaption(ctx, "درگاه اینترنتی"));
        assertNull(BankRules.channelCaption(ctx, ""));
        assertNull(BankRules.channelCaption(ctx, null));
    }

    @Test public void reset_dropsTheChannelsAndKeepsTheNotesByDefault() throws Exception {
        // The channels are the inbox's to state: a reset wipes the transactions so the next scan
        // re-reads every message, and the re-read brings the channels back with it. A note the user
        // typed has no such source, so it stays unless they asked for it to go.
        Transaction t = tx("Tejarat", "01350000000", "sig-A", "content-A");
        FinancialTestStore.writeTransactions(ctx, Arrays.asList(t));
        FinancialTestStore.mergeChannels(ctx, channelsFor(t, SHETAB));
        FinancialTestStore.mergeReasons(ctx, reasonsFor(t, TOPUP));
        BalanceData.setNote(ctx, t, "survivor");

        BalanceData.reset(ctx, false);

        assertTrue(BalanceData.readChannels(ctx).isEmpty());
        assertTrue(BalanceData.readReasons(ctx).isEmpty());
        assertEquals("survivor", BalanceData.readNotes(ctx).get(BalanceData.noteKey(t)));
    }

    @Test public void reset_deletingTheNotes_dropsBoth() throws Exception {
        Transaction t = tx("Tejarat", "01350000000", "sig-A", "content-A");
        FinancialTestStore.writeTransactions(ctx, Arrays.asList(t));
        FinancialTestStore.mergeChannels(ctx, channelsFor(t, SHETAB));
        BalanceData.setNote(ctx, t, "doomed");

        BalanceData.reset(ctx, true);

        assertTrue(BalanceData.readChannels(ctx).isEmpty());
        assertTrue(BalanceData.readNotes(ctx).isEmpty());
    }
}
