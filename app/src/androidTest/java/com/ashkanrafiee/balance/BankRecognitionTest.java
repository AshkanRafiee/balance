package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The reader's switch over which banks Balance reads, end to end on a real device: the real inbox,
 * the real scan, the real store.
 *
 * <p>Two properties are under test, and they pull against each other, which is why they belong in
 * one class. Turning a bank off has to take effect — no balance, no movement, no history, no report
 * prompt, for a bank whose messages are still sitting in the inbox. Turning it back on has to bring
 * those same messages back, which it cannot do from the watermark alone, because the messages the
 * choice kept unread are older than the watermark. And none of it may touch what was already
 * recorded: the switch is a choice about the future, not a deletion.
 */
@RunWith(AndroidJUnit4.class)
public class BankRecognitionTest {

    /** A legacy Iranian bank: read by the tables that shipped before packs, so it proves the choice
     *  is enforced on that path too and not only on the packs. */
    private static final String MELLAT = "Mellat";
    private static final String MELLAT_SENDER = "+9815560001";
    private static final String MELLAT_TRANSFER =
        "برداشت100,000,000 مانده 77,222,945";
    /** A second withdrawal whose stated balance is the first one's plus its own amount, so the two
     *  are not the reversed transfer/fee pair the balance chain reconciles and the newer message
     *  really is the newer balance. */
    private static final String MELLAT_WITHDRAWAL =
        "برداشت1,000,000 مانده 178,222,945";

    /** A community pack, read by a shipped pack. */
    private static final String CARTABCC = "CartaBCC";
    private static final String CARTABCC_SPEND =
        "Hai richiesto una spesa di EUR 66,80 alle ore 22:29 del giorno 27/09"
        + " con CartaBCC *557 presso RAMEN BAR XXX LE.";

    /** Neither a pack nor a legacy bank, so it is unknown whatever the reader decides. */
    private static final String STRANGER = "ADBBANK";

    private static final long T = 1_000_000_000L;

    private Context ctx;

    @Before public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .adoptShellPermissionIdentity(android.Manifest.permission.READ_SMS);
        exec("pm grant " + ctx.getPackageName() + " android.permission.READ_SMS");
        // The choice is a preference and the scan remembers it in one, so both start empty here.
        recognitionPrefs().edit().clear().commit();
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
        RecognitionHelper.refresh(ctx);
        EngineRules.activate(ctx);
        FinancialTestStore.wipe(ctx);
        clearInbox();
    }

    @After public void tearDown() throws Exception {
        clearInbox();
        recognitionPrefs().edit().clear().commit();
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
        RecognitionHelper.refresh(ctx);
    }

    // ---- default: everything is read, and nothing has been decided yet ----

    @Test public void everyBankIsReadUntilTheReaderSaysOtherwise() throws Exception {
        // A fresh install must read every shipped bank, and must record that it has never been told
        // otherwise -- the empty choice has to equal what a device that never opened the settings
        // screen already holds, or the first scan would re-read the inbox for no reason.
        assertTrue(RecognitionHelper.isEnabled(MELLAT));
        assertTrue(RecognitionHelper.isEnabled(CARTABCC));
        assertEquals("a device that never opened the screen has decided nothing", 0,
            RecognitionHelper.revision());

        seed(MELLAT_SENDER, MELLAT_TRANSFER, T + 1000);
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        assertEquals(1, BalanceData.scanSms(ctx, saved));
        assertNotNull("a bank nobody turned off is still read", find(saved, MELLAT));
    }

    @Test public void aBankTheReaderNeverHeardOf_isUnreadWhateverTheChoice() throws Exception {
        seed(STRANGER, "card purchase 45,000 T", T + 1000);
        RecognitionHelper.setEnabled(ctx, CARTABCC, false);

        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        assertEquals(0, BalanceData.scanSms(ctx, saved));
        assertTrue(saved.isEmpty());
    }

    // ---- off: the choice holds on both read paths ----

    @Test public void turningOffALegacyBankStopsItBeingRead() throws Exception {
        seed(MELLAT_SENDER, MELLAT_TRANSFER, T + 1000);
        assertFalse(RecognitionHelper.setEnabled(ctx, MELLAT, false));
        assertFalse(RecognitionHelper.isEnabled(MELLAT));

        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        assertEquals("a bank the reader turned off contributes nothing",
            0, BalanceData.scanSms(ctx, saved));
        assertTrue(saved.isEmpty());
        // The same message through the reduction every screen reads, so no other path can be the way
        // around the choice.
        assertNull(MessageFacts.of(MELLAT_SENDER, MELLAT_TRANSFER, T + 1000).bank);
    }

    @Test public void turningOffACommunityBankStopsItBeingRead() throws Exception {
        seed(CARTABCC, CARTABCC_SPEND, T + 1000);
        assertFalse(RecognitionHelper.setEnabled(ctx, CARTABCC, false));

        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        assertEquals(0, BalanceData.scanSms(ctx, saved));
        assertTrue(saved.isEmpty());
        assertNull(MessageFacts.of(CARTABCC, CARTABCC_SPEND, T + 1000).bank);
    }

    @Test public void turningOffOneBankLeavesTheOthersReading() throws Exception {
        seed(MELLAT_SENDER, MELLAT_TRANSFER, T + 1000);
        seed(CARTABCC, CARTABCC_SPEND, T + 2000);
        RecognitionHelper.setEnabled(ctx, MELLAT, false);

        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        assertEquals(1, BalanceData.scanSms(ctx, saved));
        assertNull("the bank that was turned off", find(saved, MELLAT));
        assertNotNull("and the one that was not", find(saved, CARTABCC));
    }

    // ---- off: nothing recorded is touched ----

    @Test public void turningABankOffDeletesNothingItAlreadyRecorded() throws Exception {
        seed(MELLAT_SENDER, MELLAT_TRANSFER, T + 1000);
        LinkedHashMap<String, Bank> first = new LinkedHashMap<>();
        assertEquals(1, BalanceData.scanSms(ctx, first));
        Bank recorded = find(first, MELLAT);
        assertNotNull(recorded);
        long balance = recorded.amount;

        RecognitionHelper.setEnabled(ctx, MELLAT, false);
        LinkedHashMap<String, Bank> after = new LinkedHashMap<>();
        assertEquals("the re-scan reads nothing from it", 0, BalanceData.scanSms(ctx, after));

        // The scan reports what is stored, so this is the store: a bank that is no longer read still
        // holds the balance it had, from the message it was read from.
        Bank stillThere = find(after, MELLAT);
        assertNotNull("turning a bank off must not delete its balance", stillThere);
        assertEquals(balance, stillThere.amount);
        assertEquals(recorded.date, stillThere.date);
    }

    @Test public void turningABankOffKeepsTheHistoryItAlreadyRecorded() throws Exception {
        seed(MELLAT_SENDER, MELLAT_TRANSFER, T + 1000);
        assertEquals(1, BalanceData.scanHistory(ctx));
        assertEquals(1, storedHistory().size());

        RecognitionHelper.setEnabled(ctx, MELLAT, false);
        // A full rebuild is forced by the change of choice, which is the strictest case for the
        // promise: it re-derives history from the inbox and could have dropped the entry.
        assertEquals(0, BalanceData.scanHistory(ctx));
        List<Transaction> kept = storedHistory();
        assertEquals("a bank that is no longer read still has its history", 1, kept.size());
        assertEquals(MELLAT, kept.get(0).bank);
        assertEquals(-100_000_000L, kept.get(0).amount);
    }

    // ---- on again: the messages the choice skipped come back ----

    @Test public void turningABankBackOnReReadsTheInbox() throws Exception {
        // Turned off before the first scan, so nothing is stored and the scan that follows is free
        // to advance its watermark past this bank's messages -- which is exactly what makes the
        // re-enable below the hard case: nothing new has arrived, so nothing about the inbox looks
        // new, and only a decision to re-read it can bring the balance back.
        seed(MELLAT_SENDER, MELLAT_TRANSFER, T + 1000);
        RecognitionHelper.setEnabled(ctx, MELLAT, false);
        LinkedHashMap<String, Bank> off = new LinkedHashMap<>();
        assertEquals(0, BalanceData.scanSms(ctx, off));
        assertTrue(off.isEmpty());
        assertTrue("the scan moved on past a message it declined to read",
            FinancialTestStore.snapshot(ctx).scannedThrough() > 0);

        RecognitionHelper.setEnabled(ctx, MELLAT, true);
        LinkedHashMap<String, Bank> back = new LinkedHashMap<>();
        assertEquals(1, BalanceData.scanSms(ctx, back));
        Bank restored = find(back, MELLAT);
        assertNotNull("the bank is read again", restored);
        assertEquals(77_222_945L, restored.amount);
    }

    @Test public void turningABankOffAndBackOnAgainStillReReadsTheInbox() throws Exception {
        // The same bank turned off and on again returns the choice to exactly what it was, so a
        // scan that compared the choice itself would see "no change" and read nothing. What changed
        // is that a message arrived and was skipped while the choice stood.
        seed(MELLAT_SENDER, MELLAT_TRANSFER, T + 1000);
        seed(CARTABCC, CARTABCC_SPEND, T + 2000);
        assertEquals(2, BalanceData.scanSms(ctx, new LinkedHashMap<>()));

        RecognitionHelper.setEnabled(ctx, MELLAT, false);
        seed(MELLAT_SENDER, MELLAT_WITHDRAWAL, T + 3000);
        assertEquals("the withdrawal it sent while it was off is not read",
            0, BalanceData.scanSms(ctx, new LinkedHashMap<>()));

        RecognitionHelper.setEnabled(ctx, MELLAT, true);
        LinkedHashMap<String, Bank> back = new LinkedHashMap<>();
        assertEquals("the withdrawal it sent while it was off is read now", 1,
            BalanceData.scanSms(ctx, back));
        assertEquals(178_222_945L, find(back, MELLAT).amount);
    }

    @Test public void turningABankBackOnRecordsTheHistoryItSkipped() throws Exception {
        seed(MELLAT_SENDER, MELLAT_TRANSFER, T + 1000);
        RecognitionHelper.setEnabled(ctx, MELLAT, false);
        // Nothing is read while the bank is off, so nothing is in the history.
        assertEquals(0, BalanceData.scanHistory(ctx));
        assertTrue(storedHistory().isEmpty());

        RecognitionHelper.setEnabled(ctx, MELLAT, true);
        assertEquals("the movement it sent while it was off is now history", 1,
            BalanceData.scanHistory(ctx));
        List<Transaction> history = storedHistory();
        assertEquals(1, history.size());
        assertEquals(MELLAT, history.get(0).bank);
        assertEquals(-100_000_000L, history.get(0).amount);
    }

    @Test public void turningABankBackOffLeavesLaterMessagesOut() throws Exception {
        seed(MELLAT_SENDER, MELLAT_TRANSFER, T + 1000);
        assertEquals(1, BalanceData.scanSms(ctx, new LinkedHashMap<>()));
        RecognitionHelper.setEnabled(ctx, MELLAT, false);

        // A newer message from a bank the reader is no longer reading must not move the balance
        // forward, even though the inbox and the watermark both move past it.
        seed(MELLAT_SENDER, MELLAT_WITHDRAWAL, T + 2000);
        LinkedHashMap<String, Bank> after = new LinkedHashMap<>();
        BalanceData.scanSms(ctx, after);
        Bank kept = find(after, MELLAT);
        assertNotNull(kept);
        assertEquals("the balance stays where the last read message left it",
            77_222_945L, kept.amount);
    }

    // ---- the choice as the reader sees it ----

    @Test public void diagnosticsDoNotOfferToReportABankTheReaderTurnedOff() throws Exception {
        long now = System.currentTimeMillis();
        RecognitionHelper.setEnabled(ctx, MELLAT, false);
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(java.util.Arrays.asList(
            new Object[]{MELLAT_SENDER, MELLAT_TRANSFER, now},
            new Object[]{STRANGER, "card purchase 45,000 T", now}));

        assertEquals("a declined format is not a known bank's unparsed message", 0,
            s.unparsedSendersMessages);
        assertEquals("it is counted so the screen can say where those messages went",
            1, s.turnedOffMessages);
        assertEquals("the sender the app genuinely does not know is still a gap",
            1, s.unknownSenders.size());
    }

    @Test public void thePickerStillNamesABankTheReaderTurnedOff() throws Exception {
        // The picker is the way to report a format the app reads partly, so it must keep naming the
        // bank a sender belongs to even when the reader has stopped reading it -- and say it read
        // nothing, which is the truth.
        long now = System.currentTimeMillis();
        RecognitionHelper.setEnabled(ctx, MELLAT, false);
        List<ScanDiagnostics.SenderHit> senders = ScanDiagnostics.inboxSenders(
            java.util.Collections.singletonList(new Object[]{MELLAT_SENDER, MELLAT_TRANSFER, now}));
        assertEquals(1, senders.size());
        assertEquals(MELLAT, senders.get(0).bank);
        assertEquals(0, senders.get(0).read);
    }

    @Test public void screen_groupsTheBanksAndSaysWhereEachFormatCameFrom() throws Exception {
        BankRecognitionActivity act = launch();
        try {
            String all = waitFor(act, ctx.getString(R.string.recognition_group_official, "Iran"));
            assertTrue("the screen says what the switches do", all.contains(ctx.getString(R.string.recognition_note)));
            assertTrue("official formats are labelled as such", all.contains(ctx.getString(R.string.recognition_group_official, "Iran")));
            assertTrue("community formats are labelled as such", all.contains(ctx.getString(R.string.recognition_group_community, "Italy")));
            assertTrue(all.contains(MELLAT));
            assertTrue(all.contains(CARTABCC));
            // One switch per bank, all on, and nothing carrying a verdict about how well a bank is
            // read -- this screen is a choice, not a diagnosis.
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                List<android.widget.Switch> switches = new ArrayList<>();
                findSwitches(act.getWindow().getDecorView(), switches);
                EngineRules loaded = EngineRules.get();
                assertEquals("one switch per bank Balance knows",
                    loaded == null ? 0 : loaded.banksInOrder().size(), switches.size());
                for (android.widget.Switch s : switches) {
                    assertTrue("every bank starts on", s.isChecked());
                }
            });
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(act::finish);
        }
    }

    @Test public void screen_tappingABankTurnsItOffForTheScanToo() throws Exception {
        BankRecognitionActivity act = launch();
        try {
            waitFor(act, MELLAT);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                tapBankRow(act.getWindow().getDecorView(), MELLAT));
            assertFalse("the choice is stored by the tap itself",
                RecognitionHelper.isEnabled(MELLAT));
            assertTrue(recognitionPrefs().getStringSet(RecognitionHelper.KEY_DISABLED,
                java.util.Collections.emptySet()).contains(MELLAT));
            // The in-memory snapshot is what the next message is classified by, so it has to have
            // moved with the tap rather than waiting for the next scan to reload it.
            assertNull(MessageFacts.of(MELLAT_SENDER, MELLAT_TRANSFER, T + 1000).bank);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                android.view.View row = findRow(act.getWindow().getDecorView(), MELLAT);
                android.widget.Switch toggle = (android.widget.Switch) ((android.view.ViewGroup) row)
                    .getChildAt(1);
                assertTrue("the row says so", !toggle.isChecked());
                assertEquals(ctx.getString(R.string.recognition_off, MELLAT),
                    row.getContentDescription());
            });
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(act::finish);
        }
    }

    @Test public void theDiagnosticsLineReadsAsASentenceAboutOneMessage() throws Exception {
        // One message from a declined bank is a fact about a decision, and the line saying so has
        // to fit the number it states: "1 messages are from banks you turned off" would undo the
        // point of counting them apart from the funnel in the first place.
        seed(MELLAT_SENDER, MELLAT_TRANSFER, T + 1000);
        seed(STRANGER, "card purchase 45,000 T", T + 2000);
        RecognitionHelper.setEnabled(ctx, MELLAT, false);

        ScanDiagnosticsActivity act = (ScanDiagnosticsActivity) InstrumentationRegistry
            .getInstrumentation().startActivitySync(
                new android.content.Intent(ctx, ScanDiagnosticsActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            String one = ctx.getResources().getQuantityString(
                R.plurals.scan_diag_turned_off, 1, 1);
            String all = waitFor(act, one);
            assertTrue("the line is shown, worded for a single message", all.contains(one));
            assertFalse("and not worded for several",
                all.contains(ctx.getResources().getQuantityString(
                    R.plurals.scan_diag_turned_off, 2, 2)));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(act::finish);
        }
    }

    @Test public void theBanksRowInTheDisplayDialogOpensTheBanksScreen() throws Exception {
        // A switch nothing leads to is not a setting, so the way in is walked the way a reader walks
        // it: the Display dialog on the dashboard, the row in it, and the screen that row opens.
        android.app.Instrumentation ins = InstrumentationRegistry.getInstrumentation();
        AtomicReference<android.view.View> row = new AtomicReference<>();
        try (androidx.test.core.app.ActivityScenario<MainActivity> dashboard =
                androidx.test.core.app.ActivityScenario.launch(MainActivity.class)) {
            dashboard.onActivity(m -> {
                m.displayDialog();
                android.app.AlertDialog dialog = m.activeDialog();
                assertNotNull("the Display dialog is up", dialog);
                assertTrue("and it is showing", dialog.isShowing());
                row.set(findBanksRow(dialog.getWindow().getDecorView()));
            });
            assertNotNull("the dialog offers the banks row", row.get());

            android.app.Instrumentation.ActivityMonitor monitor = ins.addMonitor(
                BankRecognitionActivity.class.getName(), null, false);
            android.app.Activity opened;
            try {
                dashboard.onActivity(m -> row.get().performClick());
                opened = monitor.waitForActivityWithTimeout(15_000);
            } finally {
                ins.removeMonitor(monitor);
            }
            assertNotNull("tapping the row opens the banks screen", opened);
            try {
                String all = waitFor(opened, ctx.getString(R.string.recognition_note));
                assertTrue("and it is the screen with the switches",
                    all.contains(ctx.getString(R.string.recognition_entry)));
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(opened::finish);
            }
        }
    }

    /** A bank the reader turned off has to come back on from the same screen. The row is drawn once
     *  and its tap target is redrawn in place, so a listener that reads the flag it was drawn with
     *  answers the same thing twice and leaves the bank stuck off until the screen is reopened. */
    @Test public void aBankTurnedOffCanBeTurnedOnAgainFromTheSameScreen() throws Exception {
        RecognitionHelper.setEnabled(ctx, CARTABCC, true);
        RecognitionHelper.refresh(ctx);
        try (androidx.test.core.app.ActivityScenario<BankRecognitionActivity> screen =
                androidx.test.core.app.ActivityScenario.launch(BankRecognitionActivity.class)) {
            screen.onActivity(a -> {
                android.view.View row = bankRow(a.getWindow().getDecorView(), CARTABCC);
                assertNotNull("the banks screen lists " + CARTABCC, row);
                row.performClick();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertFalse("the first tap turned it off", RecognitionHelper.isEnabled(CARTABCC));

            screen.onActivity(a -> {
                android.view.View row = bankRow(a.getWindow().getDecorView(), CARTABCC);
                assertNotNull("the row is still there to tap again", row);
                row.performClick();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue("the second tap turned it back on, without reopening the screen",
                RecognitionHelper.isEnabled(CARTABCC));
        } finally {
            RecognitionHelper.setEnabled(ctx, CARTABCC, true);
        }
    }

    // ---- helpers ----

    /** The switch row for one bank, found by the state a reader would hear, which is the same for
     *  every bank and so cannot pick a row on its own: the bank's display name has to be in it. */
    private android.view.View bankRow(android.view.View v, String bank) {
        String name = BankRules.displayName(ctx, bank);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            String said = g.getContentDescription() == null ? "" : g.getContentDescription().toString();
            if (said.contains(name)) return g;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View found = bankRow(g.getChildAt(i), bank);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The dialog row that names the banks, found by the label a reader would be reading. */
    private android.view.View findBanksRow(android.view.View v) {
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            if (g.getChildCount() > 0 && g.getChildAt(0) instanceof android.widget.TextView
                    && ctx.getString(R.string.recognition_entry)
                        .equals(((android.widget.TextView) g.getChildAt(0)).getText().toString()))
                return g;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View found = findBanksRow(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Bank find(LinkedHashMap<String, Bank> banks, String bank) {
        for (Bank b : banks.values()) if (bank.equals(b.name)) return b;
        return null;
    }

    private List<Transaction> storedHistory() {
        return BalanceData.readTransactions(ctx);
    }

    private android.content.SharedPreferences recognitionPrefs() {
        return ctx.getSharedPreferences(RecognitionHelper.PREFS, Context.MODE_PRIVATE);
    }

    private BankRecognitionActivity launch() {
        android.content.Intent i = new android.content.Intent(ctx, BankRecognitionActivity.class)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        return (BankRecognitionActivity) InstrumentationRegistry.getInstrumentation()
            .startActivitySync(i);
    }

    private String screenText(android.app.Activity act) {
        final List<String> out = new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            collectTexts(act.getWindow().getDecorView(), out));
        StringBuilder sb = new StringBuilder();
        for (String t : out) sb.append(t).append('\n');
        return sb.toString();
    }

    private void collectTexts(android.view.View v, List<String> out) {
        if (v instanceof android.widget.TextView)
            out.add(((android.widget.TextView) v).getText().toString());
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectTexts(g.getChildAt(i), out);
        }
    }

    private String waitFor(android.app.Activity act, String needle) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String all = screenText(act);
            if (all.contains(needle)) return all;
            Thread.sleep(150);
        }
        return screenText(act);
    }

    private void findSwitches(android.view.View v, List<android.widget.Switch> out) {
        if (v instanceof android.widget.Switch) out.add((android.widget.Switch) v);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) findSwitches(g.getChildAt(i), out);
        }
    }

    /** Taps the row whose name text is {@code bank}, the way a reader would tap anywhere on it. */
    private void tapBankRow(android.view.View root, String bank) {
        android.view.View row = findRow(root, bank);
        assertNotNull("no row for " + bank, row);
        assertTrue("the row is the tap target", row.performClick());
    }

    private android.view.View findRow(android.view.View v, String bank) {
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            if (g.getChildCount() == 2 && g.getChildAt(0) instanceof android.widget.TextView
                    && bank.equals(((android.widget.TextView) g.getChildAt(0)).getText().toString()))
                return g;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View found = findRow(g.getChildAt(i), bank);
                if (found != null) return found;
            }
        }
        return null;
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
        fail("SMS inbox did not clear in time");
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
