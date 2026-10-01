package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.ashkanrafiee.balance.parser.Parser;
import com.ashkanrafiee.balance.parser.Rules;

import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The five-outcome tally: why a message was read, read in part, not read, deliberately not read, or
 * never claimed at all.
 *
 * <p>The funnel {@link ScanDiagnosticsTest} covers answers "did the scan take anything from this";
 * this class covers "and if not, what was it instead", which is the question a reader actually has
 * when a balance they expect never appears. It runs against the real engine, the real prefs and the
 * real inbox — the outcomes are only meaningful if the reader's own bank-recognition choice and the
 * packs' own diagnostics decide them, so nothing here is stubbed.
 */
@RunWith(AndroidJUnit4.class)
public class ScanDiagnosticsOutcomeTest {

    /** A legacy Iranian bank: read by the tables that shipped before packs, so a clean read through
     *  them is what proves the legacy path claims no per-field verdict. */
    private static final String TEJARAT = "Tejarat";
    private static final String TEJARAT_SENDER = "5000973189";
    private static final String TEJARAT_BALANCE = "موجودی شما: 1,250,000 تومان";
    /** A community pack, read by a shipped pack: a statement it resolves part of the way. */
    private static final String CARTABCC = "CartaBCC";
    /** The same bank's advertisement: a supported sender, a layout no pack guards. */
    private static final String CARTABCC_AD = "CartaBCC: la tua carta e attiva.";
    /** Neither a pack nor a legacy bank, so it is unknown whatever the reader decides. */
    private static final String STRANGER = "ADBBANK";
    private static final String STRANGER_BODY = "card purchase 45,000 T";

    private Context ctx;

    @Before public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(android.Manifest.permission.READ_SMS);
        exec("pm grant " + ctx.getPackageName() + " android.permission.READ_SMS");
        // The same activation the screens perform before they read the inbox, so no message is
        // classified against rules the dashboard is not using.
        EngineRules.activate(ctx);
        // The reader's choice is a preference and the scan remembers it in one, so both start as a
        // fresh install has them: nothing decided, nothing re-read.
        recognitionPrefs().edit().clear().commit();
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
        RecognitionHelper.refresh(ctx);
        clearInbox();
    }

    @After public void tearDown() throws Exception {
        clearInbox();
        recognitionPrefs().edit().clear().commit();
        RecognitionHelper.refresh(ctx);
    }

    private android.content.SharedPreferences recognitionPrefs() {
        return ctx.getSharedPreferences(RecognitionHelper.PREFS, Context.MODE_PRIVATE);
    }

    // ---- one message, one outcome ----

    @Test public void aMessageTheAppReads_isParsed() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{TEJARAT_SENDER, TEJARAT_BALANCE, System.currentTimeMillis()}));
        assertEquals(1, s.outcome(ScanDiagnostics.Outcome.PARSED).messages);
        assertEquals(TEJARAT, onlyName(s, ScanDiagnostics.Outcome.PARSED));
        // The funnel above still calls it recognized, exactly as it did before the tally existed.
        assertEquals(1, s.parsedMessages);
        assertEquals(0, s.outcome(ScanDiagnostics.Outcome.PARTIAL).messages);
    }

    @Test public void aStatementThatStatesNoAccount_isReadWhole() {
        // The community pack reads the amount, the currency and the day, and its rules ask for no
        // account -- a card statement that carries no account number is fully read, and calling it
        // "read in part" would put a doubt on every community message the app has ever stored.
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{CARTABCC, cartabccSpend(System.currentTimeMillis()), System.currentTimeMillis()}));
        assertEquals(1, s.outcome(ScanDiagnostics.Outcome.PARSED).messages);
        assertEquals(CARTABCC, onlyName(s, ScanDiagnostics.Outcome.PARSED));
        assertEquals(0, s.outcome(ScanDiagnostics.Outcome.PARTIAL).messages);
        assertEquals(1, s.parsedMessages);
        assertEquals(0, s.unparsedMessages());
    }

    @Test public void theDecisionNamesEachOutcome() {
        // "Read in part" is the one answer no shipped pack can give today: every official pack that
        // asks for an account requires it, and the community pack asks for none. It stays in the
        // answer a local pack can give, so the decision itself is pinned here rather than left to a
        // corpus that cannot reach it.
        assertEquals(ScanDiagnostics.Outcome.IGNORED,
            ScanDiagnostics.outcomeFor(true, true, true, true, true));
        assertEquals("a pack that read it and lost the account it asked for",
            ScanDiagnostics.Outcome.PARTIAL, ScanDiagnostics.outcomeFor(false, true, true, false, true));
        assertEquals("a pack that read it whole",
            ScanDiagnostics.Outcome.PARSED, ScanDiagnostics.outcomeFor(false, true, false, false, true));
        assertEquals("the legacy tables state no fields, so they are read or nothing",
            ScanDiagnostics.Outcome.PARSED, ScanDiagnostics.outcomeFor(false, false, false, true, true));
        assertEquals("a bank's layout the app does not read",
            ScanDiagnostics.Outcome.UNSUPPORTED, ScanDiagnostics.outcomeFor(false, false, false, false, true));
        assertEquals("no bank claims the sender",
            ScanDiagnostics.Outcome.UNKNOWN, ScanDiagnostics.outcomeFor(false, false, false, false, false));
    }

    @Test public void anAccountCountsAsLostOnlyWhenTheShipsOwnRulesAskedForIt() {
        EngineRules engine = EngineRules.activate(ctx);
        assertNotNull(engine);
        // A fact naming a shipped output that requires an account, stating none, did lose it...
        assertTrue("an output that requires an account",
            MessageFacts.accountLost(engine.covers(TEJARAT_SENDER).templates,
                accountlessFact("tejarat-withdrawal", "tejarat-withdraw-movement")));
        // ...and one naming an output that asks for none did not. Both answers come from the packs'
        // own declarations, which is the only place the difference exists.
        assertFalse("an output that asks for no account",
            MessageFacts.accountLost(engine.covers(CARTABCC).templates, accountlessFact("spend", "movement")));
        assertFalse("a fact naming nothing this pack declares",
            MessageFacts.accountLost(engine.covers(CARTABCC).templates,
                accountlessFact("spend", "no-such-output")));
    }

    @Test public void aKnownBankMessageTheAppReadsNothingFrom_isUnsupported() {
        // The sender is a supported bank's, so this is a layout the app has and this message is not
        // -- not a bank nobody has heard of.
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{CARTABCC, CARTABCC_AD, System.currentTimeMillis()}));
        assertEquals(1, s.outcome(ScanDiagnostics.Outcome.UNSUPPORTED).messages);
        assertEquals("named by its bank", CARTABCC,
            onlyName(s, ScanDiagnostics.Outcome.UNSUPPORTED));
        assertEquals(0, s.outcome(ScanDiagnostics.Outcome.UNKNOWN).messages);
        // Unchanged funnel: a known bank's unread message is a format gap, listed as a gap.
        assertEquals(1, s.unparsedSendersMessages);
        assertTrue(s.unknownSenders.isEmpty());
    }

    @Test public void aSenderNoBankClaims_isUnknown() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{STRANGER, STRANGER_BODY, System.currentTimeMillis()}));
        assertEquals(1, s.outcome(ScanDiagnostics.Outcome.UNKNOWN).messages);
        assertEquals("an unknown outcome can only be named by its sender", STRANGER,
            onlyName(s, ScanDiagnostics.Outcome.UNKNOWN));
        assertEquals(0, s.outcome(ScanDiagnostics.Outcome.UNSUPPORTED).messages);
    }

    @Test public void aBankTheReaderTurnedOff_isIgnored() {
        RecognitionHelper.setEnabled(ctx, CARTABCC, false);
        // Both messages from it: a statement the app would have read and an advertisement it would
        // not have. Turning the bank off is a choice about the bank, not about the message.
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{CARTABCC, cartabccSpend(System.currentTimeMillis()), System.currentTimeMillis()},
            new Object[]{CARTABCC, CARTABCC_AD, System.currentTimeMillis()}));
        assertEquals(2, s.outcome(ScanDiagnostics.Outcome.IGNORED).messages);
        assertEquals(CARTABCC, onlyName(s, ScanDiagnostics.Outcome.IGNORED));
        assertEquals("nothing else is claimed for a bank the reader declined", 0,
            s.outcome(ScanDiagnostics.Outcome.PARTIAL).messages
                + s.outcome(ScanDiagnostics.Outcome.UNSUPPORTED).messages
                + s.outcome(ScanDiagnostics.Outcome.UNKNOWN).messages);
        // Unchanged funnel: counted, named, and never listed as a gap in either direction.
        assertEquals(2, s.turnedOffMessages);
        assertTrue(s.unparsedSenders.isEmpty());
        assertTrue(s.unknownSenders.isEmpty());
        assertEquals(0, s.parsedMessages);
    }

    @Test public void aLegacyBankTheReaderTurnedOff_isIgnoredUnderTheNameTheChoiceIsKeyedBy() {
        // The reader's choice is stored by the app's own bank name, and a bank that has both a
        // legacy table and a pack must be found under that one name whichever path would have read
        // it -- otherwise a switched-off bank quietly comes back as a format gap.
        RecognitionHelper.setEnabled(ctx, TEJARAT, false);
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{TEJARAT_SENDER, TEJARAT_BALANCE, System.currentTimeMillis()}));
        assertEquals(1, s.outcome(ScanDiagnostics.Outcome.IGNORED).messages);
        assertEquals(TEJARAT, onlyName(s, ScanDiagnostics.Outcome.IGNORED));
        assertEquals(1, s.turnedOffMessages);
        assertEquals(0, s.parsedMessages);
    }

    // ---- the tally as a whole ----

    @Test public void everyMessageLandsInExactlyOneOutcome_andTheNamesAddUp() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{TEJARAT_SENDER, TEJARAT_BALANCE, System.currentTimeMillis()},
            new Object[]{CARTABCC, cartabccSpend(System.currentTimeMillis()), System.currentTimeMillis()},
            new Object[]{CARTABCC, CARTABCC_AD, System.currentTimeMillis()},
            new Object[]{STRANGER, STRANGER_BODY, System.currentTimeMillis()},
            new Object[]{STRANGER, "otp 123456", System.currentTimeMillis()},
            new Object[]{"+9821OTP", "otp 654321", System.currentTimeMillis()}));

        int total = 0;
        for (ScanDiagnostics.Tally t : s.outcomes()) {
            total += t.messages;
            int named = 0;
            for (ScanDiagnostics.NameCount n : t.names) named += n.messages;
            assertEquals("a tally's names account for all of its messages", t.messages, named);
            assertEquals("keys are the distinct names behind them", t.keys, t.names.size());
        }
        assertEquals("every message has exactly one outcome", s.messages, total);
        assertEquals("the legacy balance and the community statement, both read whole",
            2, s.outcome(ScanDiagnostics.Outcome.PARSED).messages);
        assertEquals(0, s.outcome(ScanDiagnostics.Outcome.PARTIAL).messages);
        assertEquals(1, s.outcome(ScanDiagnostics.Outcome.UNSUPPORTED).messages);
        assertEquals("nothing was switched off", 0,
            s.outcome(ScanDiagnostics.Outcome.IGNORED).messages);
        assertEquals("two strangers, three messages, one outcome", 3,
            s.outcome(ScanDiagnostics.Outcome.UNKNOWN).messages);
        assertEquals(2, s.outcome(ScanDiagnostics.Outcome.UNKNOWN).keys);
    }

    @Test public void aTallyOrdersItsNamesByMessageCount() {
        List<Object[]> inbox = new ArrayList<>();
        long base = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) inbox.add(new Object[]{STRANGER, "otp " + i, base - i});
        inbox.add(new Object[]{"+9821OTP", "otp 9", base - 10});
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(inbox);
        ScanDiagnostics.Tally unknown = s.outcome(ScanDiagnostics.Outcome.UNKNOWN);
        assertEquals(4, unknown.messages);
        assertEquals(2, unknown.keys);
        assertEquals(STRANGER, unknown.names.get(0).name);
        assertEquals(3, unknown.names.get(0).messages);
        assertEquals("+9821OTP", unknown.names.get(1).name);
        assertEquals(3, unknown.messagesOf(STRANGER));
        assertEquals(1, unknown.messagesOf("+9821OTP"));
        assertEquals("a name the inbox never held claims nothing", 0, unknown.messagesOf("nope"));
    }

    @Test public void everyOutcomeIsOffered_evenWhenTheInboxHoldsNoneOfIt() {
        // A screen draws one row per outcome, so an outcome with no messages must be readable and
        // empty rather than absent.
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{TEJARAT_SENDER, TEJARAT_BALANCE, System.currentTimeMillis()}));
        assertEquals(ScanDiagnostics.Outcome.values().length, s.outcomes().size());
        for (ScanDiagnostics.Tally t : s.outcomes()) {
            assertNotNull(t.outcome);
            assertTrue(t.outcome == ScanDiagnostics.Outcome.PARSED || t.messages == 0);
        }
        assertEquals(0, s.outcome(ScanDiagnostics.Outcome.IGNORED).messages);
        assertTrue(s.outcome(ScanDiagnostics.Outcome.IGNORED).names.isEmpty());
        assertEquals(0, s.outcome(ScanDiagnostics.Outcome.IGNORED).keys);
    }

    @Test public void anEmptyInbox_hasNoOutcomes() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows());
        assertEquals(0, s.messages);
        for (ScanDiagnostics.Tally t : s.outcomes()) assertEquals(0, t.messages);
    }

    @Test public void anUnreadableMessageStaysOutOfTheOutcomeItDoesNotBelongTo() {
        // A null body is not a message the app read, and not one it failed to read either: it is
        // counted once, as a gap, and named by its sender when no bank claims it.
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{TEJARAT_SENDER, null, System.currentTimeMillis()},
            new Object[]{null, null, System.currentTimeMillis()}));
        assertEquals(1, s.outcome(ScanDiagnostics.Outcome.UNSUPPORTED).messages);
        assertEquals(TEJARAT, onlyName(s, ScanDiagnostics.Outcome.UNSUPPORTED));
        assertEquals(1, s.outcome(ScanDiagnostics.Outcome.UNKNOWN).messages);
        assertEquals(2, s.messages);
    }

    // ---- the real inbox, end to end ----

    @Test public void aSeededInbox_isClassifiedTheSameWayAsTheRows() throws Exception {
        long now = System.currentTimeMillis();
        seed(TEJARAT_SENDER, TEJARAT_BALANCE, now);
        seed(CARTABCC, cartabccSpend(now), now - 1000);
        seed(CARTABCC, CARTABCC_AD, now - 2000);
        seed(STRANGER, STRANGER_BODY, now - 3000);
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(inboxRows());
        assertEquals(4, s.messages);
        assertEquals(2, s.outcome(ScanDiagnostics.Outcome.PARSED).messages);
        assertEquals(0, s.outcome(ScanDiagnostics.Outcome.PARTIAL).messages);
        assertEquals(1, s.outcome(ScanDiagnostics.Outcome.UNSUPPORTED).messages);
        assertEquals(1, s.outcome(ScanDiagnostics.Outcome.UNKNOWN).messages);
        assertEquals(0, s.outcome(ScanDiagnostics.Outcome.IGNORED).messages);
    }

    @Test public void aSeededMessageFromASwitchedOffBank_isIgnoredEndToEnd() throws Exception {
        long now = System.currentTimeMillis();
        seed(CARTABCC, cartabccSpend(now), now);
        seed(TEJARAT_SENDER, TEJARAT_BALANCE, now - 1000);
        assertEquals("both messages are read before the choice", 2,
            ScanDiagnostics.analyze(inboxRows()).outcome(ScanDiagnostics.Outcome.PARSED).messages);

        RecognitionHelper.setEnabled(ctx, CARTABCC, false);
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(inboxRows());
        assertEquals("a bank the reader turned off is not read, whatever it would have been",
            1, s.outcome(ScanDiagnostics.Outcome.IGNORED).messages);
        assertEquals(CARTABCC, onlyName(s, ScanDiagnostics.Outcome.IGNORED));
        assertEquals("the bank's other message is ignored with it", 1,
            s.outcome(ScanDiagnostics.Outcome.IGNORED).messages);
        assertEquals("the legacy bank is still read", 1,
            s.outcome(ScanDiagnostics.Outcome.PARSED).messages);
    }

    // ---- helpers ----

    /** A fact in the shape a pack leaves behind when it declared an account and published none:
     *  the money is there, the identity it belongs to is not. Built from the real records so the
     *  verdict is decided against the same types the engine publishes. */
    private static Parser.Fact accountlessFact(String templateId, String outputId) {
        return new Parser.Fact("bank", null, Rules.Kind.POSTED_MOVEMENT,
            new Parser.Money(Rules.Currency.IRR, -1000, 0), null,
            new Parser.EventTime(Instant.EPOCH, Parser.Precision.ARRIVAL, ZoneId.of("UTC"), true),
            Instant.EPOCH,
            new Parser.Provenance("seam", "pack", "1", templateId, outputId, "prototype", null));
    }

    /** A real reported card statement, with the day and month filled in from the clock so the
     *  message always states the day it is read on: the pack resolves a yearless date against the
     *  arrival, so a hard-coded date would stop anchoring once the clock moved past its lookback. */
    private static String cartabccSpend(long arrival) {
        java.time.ZonedDateTime day = java.time.Instant.ofEpochMilli(arrival)
            .atZone(java.time.ZoneId.of("Europe/Rome"));
        return "Hai richiesto una spesa di EUR 66,80 alle ore 22:29 del giorno "
            + String.format(java.util.Locale.ROOT, "%02d/%02d",
                day.getDayOfMonth(), day.getMonthValue())
            + " con CartaBCC *557 presso RAMEN BAR XXX LE.";
    }

    private static List<Object[]> rows(Object[]... in) {
        List<Object[]> list = new ArrayList<>();
        for (Object[] r : in) list.add(r);
        return list;
    }

    /** The single name behind an outcome, so a test says which one it meant. */
    private static String onlyName(ScanDiagnostics.Summary s, ScanDiagnostics.Outcome o) {
        ScanDiagnostics.Tally t = s.outcome(o);
        assertEquals("one name behind " + o, 1, t.names.size());
        return t.names.get(0).name;
    }

    /** The inbox exactly as the diagnostics screen reads it: newest first. */
    private List<Object[]> inboxRows() {
        List<Object[]> rows = new ArrayList<>();
        try (android.database.Cursor c = ctx.getContentResolver().query(
                android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                new String[]{android.provider.Telephony.Sms.ADDRESS, android.provider.Telephony.Sms.BODY,
                    android.provider.Telephony.Sms.DATE},
                null, null, android.provider.Telephony.Sms.DATE + " DESC")) {
            if (c != null) while (c.moveToNext())
                rows.add(new Object[]{c.getString(0), c.getString(1), c.getLong(2)});
        }
        return rows;
    }

    private void exec(String cmd) throws Exception {
        android.os.ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(cmd);
        try (InputStream is = new android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            byte[] buf = new byte[2048];
            while (is.read(buf) >= 0) { }
        }
        Thread.sleep(200);
    }

    private void clearInbox() throws Exception {
        exec("am broadcast -n com.ashkanrafiee.smsinject/.SeedReceiver -a com.ashkanrafiee.smsinject.CLEAR");
        long deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline) {
            try (android.database.Cursor c = ctx.getContentResolver().query(
                    android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{android.provider.Telephony.Sms._ID}, null, null, null)) {
                if (c == null || !c.moveToFirst()) return;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("SMS inbox did not clear in time");
    }

    private void seed(String sender, String body, long base) throws Exception {
        String b64 = android.util.Base64.encodeToString(
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        exec("am broadcast -n com.ashkanrafiee.smsinject/.SeedReceiver -a com.ashkanrafiee.smsinject.SEED"
                + " -e sender " + sender + " -e body64 " + b64 + " -e base " + base);
        long deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline) {
            try (android.database.Cursor c = ctx.getContentResolver().query(
                    android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{android.provider.Telephony.Sms.ADDRESS, android.provider.Telephony.Sms.BODY},
                    null, null, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        if (sender.equals(c.getString(0)) && body.equals(c.getString(1))) return;
                    }
                }
            }
            Thread.sleep(150);
        }
        throw new AssertionError("seeded SMS did not arrive in time: sender=" + sender);
    }
}
