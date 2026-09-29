package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The seam's end-to-end contract: with the engine active, a scan and a history scan must store
 *  exactly what the untouched legacy path stores for the same inbox. Runs each injector scenario
 *  twice from a wiped store — once with the seam off (today's behavior) and once with it on — and
 *  compares every stored balance and transaction field for field. Any message the engine reads
 *  differently from the legacy reducers shows up here as a mismatch. */
@RunWith(AndroidJUnit4.class)
public class SeamScanParityTest {

    private Context ctx;

    @Before public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(android.Manifest.permission.READ_SMS);
        exec("pm grant " + ctx.getPackageName() + " android.permission.READ_SMS");
        assertNotNull("the packaged engine must be loadable", EngineRules.load(ctx));
        wipe();
    }

    @After public void tearDown() throws Exception {
        EngineRules.get().setActive(false);
        clearInbox();
    }

    @Test public void everyBankScenario_storesIdenticallyWithTheSeamOn() throws Exception {
        // "demo" carries movements for every supported bank, "accounts" adds per-account splits,
        // "resalat" is a bare-signed-amount layout with no stated account.
        for (String[] scenario : new String[][]{{"demo", "13"}, {"accounts", "5"}, {"resalat", "3"}}) {
            Map<String, Row> legacy = run(scenario[0], Integer.parseInt(scenario[1]), false);
            Map<String, Row> packed = run(scenario[0], Integer.parseInt(scenario[1]), true);
            assertNotNull(scenario[0], legacy);
            assertSnapshots(scenario[0], legacy, packed);
            assertTrue(scenario[0] + ": the scenario actually stored something", legacy.size() > 2);
        }
    }

    /** One stored row, typed so nothing depends on a field separator. {@code timeA}/{@code timeB} are
     *  the event-time slots (a balance carries one, a transaction carries the stated balance too). */
    private static final class Row {
        final String text;
        final long timeA;
        final long timeB;
        Row(String text, long timeA, long timeB) {
            this.text = text;
            this.timeA = timeA;
            this.timeB = timeB;
        }
    }

    /** Wipes the store and the inbox, seeds the scenario, runs one scan and one history scan, and
     *  returns every stored balance and transaction as a comparable snapshot. */
    private Map<String, Row> run(String scenario, int expectedRows, boolean seam) throws Exception {
        wipe();
        seedTxScenario(scenario, expectedRows);
        EngineRules.get().setActive(seam);

        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        int matched = BalanceData.scanSms(ctx, saved);
        int added = BalanceData.scanHistory(ctx);

        Map<String, Row> snapshot = new TreeMap<>();
        snapshot.put("#matched", new Row(Integer.toString(matched), 0, 0));
        snapshot.put("#added", new Row(Integer.toString(added), 0, 0));
        for (Map.Entry<String, Bank> entry : new TreeMap<>(byKey(saved)).entrySet()) {
            Bank bank = entry.getValue();
            snapshot.put("balance:" + entry.getKey(), new Row(
                bank.name + "|" + bank.account + "|" + bank.amount + "|" + bank.sender,
                bank.date, 0));
        }
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        for (int i = 0; i < txs.size(); i++) {
            Transaction t = txs.get(i);
            snapshot.put("tx:" + i, new Row(
                t.bank + "|" + t.account + "|" + t.amount + "|" + t.balance + "|" + t.sig,
                t.date, 0));
        }
        assertEquals(scenario + (seam ? " (seam on)" : " (legacy)"), expectedRows, txs.size());
        return snapshot;
    }

    /** Compares two run snapshots: every field must match exactly, except the event times, which
     *  the injector seeds relative to the moment it runs, so the two runs legitimately differ by the
     *  seconds between them. A real date divergence is off by days, never by seconds, so a tight
     *  tolerance separates harness jitter from a movement filed on the wrong day. */
    private static void assertSnapshots(String scenario, Map<String, Row> legacy,
            Map<String, Row> packed) {
        assertEquals(scenario + ": the same rows must be stored", legacy.keySet(), packed.keySet());
        for (String row : legacy.keySet()) {
            Row left = legacy.get(row);
            Row right = packed.get(row);
            assertEquals(scenario + " " + row + ": every field but the time", left.text, right.text);
            assertTime(scenario, row + " time", left.timeA, right.timeA);
            assertTime(scenario, row + " time", left.timeB, right.timeB);
        }
    }

    private static void assertTime(String scenario, String row, long left, long right) {
        long delta = Math.abs(left - right);
        assertTrue(scenario + " " + row + ": event time moved by " + delta + "ms",
            delta <= TOLERANCE_MS);
    }

    private static final long TOLERANCE_MS = 2 * 60_000L;

    private static Map<String, Bank> byKey(LinkedHashMap<String, Bank> saved) {
        Map<String, Bank> out = new TreeMap<>();
        out.putAll(saved);
        return out;
    }

    private void wipe() throws Exception {
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
        FinancialTestStore.wipe(ctx);
        clearInbox();
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

    /** Runs an injector transaction scenario (action tx) and waits until all of its messages are in
     *  the real inbox, so the scan that follows is deterministic regardless of device load. */
    private void seedTxScenario(String scenario, int expectedRows) throws Exception {
        exec("am start -n com.ashkanrafiee.smsinject/.MainActivity -e action tx -e scenario " + scenario);
        long deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline) {
            try (android.database.Cursor c = ctx.getContentResolver().query(
                    android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{android.provider.Telephony.Sms._ID}, null, null, null)) {
                if (c != null && c.getCount() >= expectedRows) return;
            }
            Thread.sleep(150);
        }
        fail("scenario did not seed in time: " + scenario);
    }
}