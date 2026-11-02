package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.Manifest;
import android.content.Context;
import android.database.Cursor;
import android.provider.Telephony;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Reparse matching must not use one clockless delivery to merge distinct retained events. */
@RunWith(AndroidJUnit4.class)
public class HistoryReparseAmbiguityTest {
    private static final long T = 1_000_000_000L;
    private static final String SENDER = "500095";
    private static final String BODY =
        "مبلغ 200,000 ریال به حساب شما واریز شد، موجودی: 3,000,000 ریال";
    private Context context;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .grantRuntimePermission(context.getPackageName(), Manifest.permission.READ_SMS);
        exec("cmd appops set " + context.getPackageName()
            + " android:read_restricted_messages allow");
        clearInbox();
        BalanceData.reset(context, true);
        HistoryWarmup.invalidate();
    }

    @After public void tearDown() throws Exception {
        clearInbox();
        BalanceData.reset(context, true);
        HistoryWarmup.invalidate();
    }

    @Test public void midpointDelivery_doesNotConsumeEitherDistinctRetainedEvent() throws Exception {
        Transaction first = movement(T);
        Transaction later = movement(T + 240_000L);
        assertFalse("the retained events are not duplicate deliveries",
            BalanceData.sameMovement(first, later));
        retain(first, later);
        annotate(first, "first event", "first");
        annotate(later, "later event", "later");
        seed(T + 120_000L);

        forceFullScan();

        List<Transaction> saved = BalanceData.readTransactions(context);
        assertEquals("a midpoint delivery cannot erase either distinct retained event", 3, saved.size());
        assertEvent(saved, first, "first event", "first");
        assertEvent(saved, later, "later event", "later");
        Transaction midpoint = find(saved, movement(T + 120_000L));
        assertNotNull("the newly parsed message must also be retained", midpoint);
        assertNull("ambiguous source notes must not move to the midpoint", BalanceData.getNote(context, midpoint));
        assertTrue("ambiguous source tags must not move to the midpoint",
            BalanceData.getTags(context, midpoint).isEmpty());

        forceFullScan();
        saved = BalanceData.readTransactions(context);
        assertEquals("another full scan must not consume the retained neighbors", 3, saved.size());
        assertEvent(saved, first, "first event", "first");
        assertEvent(saved, later, "later event", "later");
        assertNull(BalanceData.getNote(context, find(saved, movement(T + 120_000L))));
    }

    @Test public void physicalSourceProof_claimsOnlyItsObservedRetainedEvent() throws Exception {
        Transaction first = movement(T);
        Transaction later = movement(T + 240_000L);
        retain(first, later);
        annotate(first, "source-proven event", "source");
        annotate(later, "different retained event", "retained");
        long arrival = T + 120_000L;
        seed(arrival);
        SourceStore.Source source = SourceStore.capture(context, SENDER, BODY, arrival, 0);
        assertNotNull(source);
        assertTrue(SourceStore.observeTransaction(context, source, 0, first));

        forceFullScan();

        List<Transaction> saved = BalanceData.readTransactions(context);
        assertEquals("physical source proof must not consume an unrelated bounded-family neighbor",
            2, saved.size());
        assertNull("the observed old parse is replaced", find(saved, first));
        assertEvent(saved, movement(arrival), "source-proven event", "source");
        assertEvent(saved, later, "different retained event", "retained");
        assertNull(BalanceData.getNote(context, first));
    }

    @Test public void legacyMidpointDelivery_preservesDistinctEventsAndTheirSharedAnnotations()
            throws Exception {
        Transaction first = legacyMovement(T);
        Transaction later = legacyMovement(T + 240_000L);
        assertEquals("legacy occurrences share a content-based annotation key",
            BalanceData.noteKey(first), BalanceData.noteKey(later));
        retain(first, later);
        annotate(first, "shared legacy note", "shared");
        seed(T + 120_000L);

        for (int pass = 0; pass < 2; pass++) {
            forceFullScan();
            List<Transaction> saved = BalanceData.readTransactions(context);
            assertEquals("a shared legacy annotation key is not proof of a shared event",
                3, saved.size());
            assertEvent(saved, first, "shared legacy note", "shared");
            assertEvent(saved, later, "shared legacy note", "shared");
            Transaction midpoint = find(saved, movement(T + 120_000L));
            assertNotNull(midpoint);
            assertNull("the ambiguous fresh event must not inherit the shared legacy note",
                BalanceData.getNote(context, midpoint));
            assertTrue(BalanceData.getTags(context, midpoint).isEmpty());
        }
    }

    @Test public void identicalLegacyCopies_canStillCollapseIntoOneRedeliveredEvent() throws Exception {
        Transaction first = legacyMovement(T);
        Transaction duplicate = legacyMovement(T);
        retain(first, duplicate);
        assertEquals("the fixture really retains two identical old parses", 2,
            BalanceData.readTransactions(context).size());
        annotate(first, "one legacy event", "kept");
        seed(T + 60_000L);

        forceFullScan();

        List<Transaction> saved = BalanceData.readTransactions(context);
        assertEquals("genuinely identical old copies may still be reconciled", 1, saved.size());
        assertEvent(saved, movement(T + 60_000L), "one legacy event", "kept");
        assertNull(BalanceData.getNote(context, first));
    }

    @Test public void retainedMidpointBetweenFreshEvents_isNotAssignedToEitherNeighbor() throws Exception {
        Transaction retained = movement(T + 120_000L);
        retain(retained);
        annotate(retained, "retained midpoint", "retained");
        seed(T);
        seed(T + 240_000L);

        forceFullScan(2);

        List<Transaction> saved = BalanceData.readTransactions(context);
        assertEquals("two fresh neighbors do not identify which one replaces the retained event",
            3, saved.size());
        assertEvent(saved, retained, "retained midpoint", "retained");
        for (long date : new long[]{T, T + 240_000L}) {
            Transaction fresh = find(saved, movement(date));
            assertNotNull(fresh);
            assertNull(BalanceData.getNote(context, fresh));
            assertTrue(BalanceData.getTags(context, fresh).isEmpty());
        }
    }

    @Test public void uniqueBoundedRedelivery_stillReplacesItsRetainedParse() throws Exception {
        Transaction first = movement(T);
        retain(first);
        annotate(first, "one real event", "kept");
        seed(T + 60_000L);

        forceFullScan();

        List<Transaction> saved = BalanceData.readTransactions(context);
        assertEquals("an unambiguous short-window redelivery is still one event", 1, saved.size());
        assertEvent(saved, movement(T + 60_000L), "one real event", "kept");
        assertNull(BalanceData.getNote(context, first));
    }

    @Test public void exactQualifiedIdentity_doesNotConsumeAnotherRetainedOccurrence() throws Exception {
        Transaction first = movement(T);
        Transaction later = movement(T + 240_000L);
        retain(first, later);
        annotate(first, "exact event", "exact");
        annotate(later, "deleted-source event", "retained");
        seed(T);

        forceFullScan();

        List<Transaction> saved = BalanceData.readTransactions(context);
        assertEquals(2, saved.size());
        assertEvent(saved, first, "exact event", "exact");
        assertEvent(saved, later, "deleted-source event", "retained");
    }

    private static Transaction movement(long date) {
        Transaction transaction = BalanceData.parseMovement("Saman", SENDER, BODY, date, false, 0);
        assertNotNull(transaction);
        assertTrue("use actual qualified clockless scanner signatures",
            transaction.sig.startsWith("e2:") && transaction.sig.contains(":a:"));
        assertEquals(date, transaction.date);
        assertEquals(200_000L, transaction.amount);
        return transaction;
    }

    private static Transaction legacyMovement(long date) {
        Transaction parsed = movement(date);
        return new Transaction(parsed.bank, parsed.account, parsed.date, parsed.amount, parsed.balance,
            BalanceData.messageSig(SENDER, BODY), parsed.content);
    }

    private void retain(Transaction... transactions) {
        assertTrue(BalanceData.writeTransactions(context, Arrays.asList(transactions)));
    }

    private void annotate(Transaction transaction, String note, String tag) {
        BalanceData.setNote(context, transaction, note);
        BalanceData.setTags(context, transaction, Collections.singletonList(tag));
    }

    private void assertEvent(List<Transaction> saved, Transaction expected, String note, String tag) {
        Transaction retained = find(saved, expected);
        assertNotNull("the event at " + expected.date + " must remain", retained);
        assertEquals(note, BalanceData.getNote(context, retained));
        assertEquals(Collections.singletonList(tag), BalanceData.getTags(context, retained));
    }

    private static Transaction find(List<Transaction> saved, Transaction expected) {
        for (Transaction transaction : saved)
            if (expected.date == transaction.date && expected.sig.equals(transaction.sig)) return transaction;
        return null;
    }

    private void forceFullScan() {
        forceFullScan(1);
    }

    private void forceFullScan(int expectedMovements) {
        assertTrue(context.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE)
            .edit().putInt(BalanceData.KEY_HISTORY_SCHEMA, 1).commit());
        assertEquals("every inbox movement must be parsed", expectedMovements, BalanceData.scanHistory(context));
        assertEquals(BalanceData.HISTORY_SCHEMA,
            context.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE)
                .getInt(BalanceData.KEY_HISTORY_SCHEMA, -1));
    }

    private void seed(long arrival) throws Exception {
        String encoded = android.util.Base64.encodeToString(BODY.getBytes(StandardCharsets.UTF_8),
            android.util.Base64.NO_WRAP);
        exec("am broadcast -n com.ashkanrafiee.smsinject/.SeedReceiver -a com.ashkanrafiee.smsinject.SEED"
            + " -e sender " + SENDER + " -e body64 " + encoded + " -e base " + arrival);
        await(() -> {
            try (Cursor cursor = context.getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE},
                    null, null, null)) {
                if (cursor != null) while (cursor.moveToNext()) {
                    if (SENDER.equals(cursor.getString(0)) && BODY.equals(cursor.getString(1))
                            && arrival == cursor.getLong(2)) return true;
                }
                return false;
            }
        }, "the explicitly dated midpoint/redelivery SMS");
    }

    private void clearInbox() throws Exception {
        exec("am broadcast -n com.ashkanrafiee.smsinject/.SeedReceiver -a com.ashkanrafiee.smsinject.CLEAR");
        await(() -> {
            try (Cursor cursor = context.getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{Telephony.Sms._ID}, null, null, null)) {
                return cursor != null && !cursor.moveToFirst();
            }
        }, "the isolated emulator inbox to clear");
    }

    private static void exec(String command) throws Exception {
        android.os.ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation().executeShellCommand(command);
        try (InputStream stream = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[2048];
            while (stream.read(buffer) >= 0) { }
        }
    }

    private interface Check { boolean done() throws Exception; }

    private static void await(Check check, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline) {
            if (check.done()) return;
            Thread.sleep(100);
        }
        fail("timed out waiting for " + what);
    }
}
