package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.util.List;

/**
 * Systematic history-scan tests against the real SMS provider (instrumented, no UI).
 *
 * Every test starts from a clean inbox + clean prefs, so both the full-scan (history watermark 0)
 * and incremental-scan states are fully deterministic. Messages are seeded with explicit dates
 * through the com.ashkanrafiee.smsinject helper app, exactly like {@link BalanceScanTest}.
 */
@RunWith(AndroidJUnit4.class)
public class HistoryScanTest {

    private static final long T = 1_000_000_000L;

    private static final String TEJARAT_WITHDRAWAL =
        "*\u0628\u0627\u0646\u06A9 \u062A\u062C\u0627\u0631\u062A* \n"
        + "\u062D\u0633\u0627\u0628: 0135399698887 \n"
        + "\u0628\u0631\u062F\u0627\u0634\u062A: 70,014,000 \u0631\u06CC\u0627\u0644 \n"
        + "\u0627\u0632 \u0637\u0631\u06CC\u0642: \u0633\u0627\u0645\u0627\u0646\u0647 \u067E\u0644 (\u067E\u0631\u062F\u0627\u062E\u062A \u0644\u062D\u0638\u0647 \u0627\u06CC)  \n"
        + "\u0645\u0627\u0646\u062F\u0647: 1,209,288 \u0631\u06CC\u0627\u0644 \n"
        + "1405/06/07\n20:16";
    private static final String TEJARAT_DEPOSIT =
        "*\u0628\u0627\u0646\u06A9 \u062A\u062C\u0627\u0631\u062A* \n"
        + "\u062D\u0633\u0627\u0628: 0135399698887 \n"
        + "\u0648\u0627\u0631\u06CC\u0632: 115,000,000 \u0631\u06CC\u0627\u0644 \n"
        + "\u0627\u0632 \u0637\u0631\u06CC\u0642: \u0633\u0627\u0645\u0627\u0646\u0647 \u067E\u0644 (\u067E\u0631\u062F\u0627\u062E\u062A \u0644\u062D\u0638\u0647 \u0627\u06CC)  \n"
        + "\u0645\u0627\u0646\u062F\u0647: 361,919,288 \u0631\u06CC\u0627\u0644 \n"
        + "1405/06/06\n00:08";
    private static final String BLU_WITHDRAWAL =
        "\u0628\u0644\u0648\n"
        + "\u0628\u0631\u062F\u0627\u0634\u062A \u067E\u0648\u0644\n"
        + "\u0627\u0634\u06A9\u0627\u0646 \u0639\u0632\u06CC\u0632\u060C 400,000 \u0631\u06CC\u0627\u0644 \u0627\u0632 \u062D\u0633\u0627\u0628 \u0634\u0645\u0627 \u067E\u0631\u06CC\u062F.\n"
        + "\u0645\u0648\u062C\u0648\u062F\u06CC: 57,086,241 \u0631\u06CC\u0627\u0644\n"
        + "\u06F2\u06F3:\u06F2\u06F8\n"
        + "\u06F1\u06F4\u06F0\u06F5.\u06F0\u06F6.\u06F1\u06F5";
    private static final String PARSIAN_WITHDRAWAL =
        "30103348179608\n"
        + "\u0645\u0628\u0644\u063A:500,000-\n"
        + "\u0645\u0627\u0646\u062F\u0647:1,076,220\n"
        + "05/26\n08:22";
    private static final String RESALAT_WITHDRAWAL_1 =
        "-200,000,000  \n"
        + "06/22_20:37 \n"
        + "\u0645\u0627\u0646\u062F\u0647: 2,279,545,033";
    private static final String RESALAT_WITHDRAWAL_2 =
        "-40,000  \n"
        + "06/22_20:37 \n"
        + "\u0645\u0627\u0646\u062F\u0647: 2,279,505,033";

    private Context ctx;

    @Before public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(android.Manifest.permission.READ_SMS);
        exec("pm grant " + ctx.getPackageName() + " android.permission.READ_SMS");
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
        clearInbox();
    }

    @After public void tearDown() throws Exception {
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
        exec("am start -n com.ashkanrafiee.smsinject/.MainActivity -e action clear");
        // Clearing is async across processes: wait until the inbox is actually empty so the next
        // test never sees a leftover row, regardless of device load.
        long deadline = System.currentTimeMillis() + 15_000;
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
        exec("am start -n com.ashkanrafiee.smsinject/.MainActivity -e sender " + sender
                + " -e body64 " + b64 + " -e base " + base);
        awaitSms(sender, body);
    }

    /** Polls the real inbox until the exact seeded message is visible, so that the scan that follows
     *  in the same test is deterministic even when the system is slow. */
    private void awaitSms(String sender, String body) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
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
        fail("seeded SMS did not arrive in time: sender=" + sender);
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE);
    }

    private long historyWatermark() {
        return prefs().getLong(BalanceData.KEY_HISTORY_THROUGH, 0);
    }

    private static final String DEPOSIT =
        "\u0645\u0628\u0644\u063A 200,000 \u0631\u06CC\u0627\u0644 \u0628\u0647 \u062D\u0633\u0627\u0628 \u0634\u0645\u0627 \u0648\u0627\u0631\u06CC\u0632 \u0634\u062F\u060C \u0645\u0648\u062C\u0648\u062F\u06CC: 3,000,000 \u0631\u06CC\u0627\u0644";
    private static final String WITHDRAWAL =
        "\u062E\u0631\u06CC\u062F \u0628\u0647 \u0645\u0628\u0644\u063A 120,000 \u0631\u06CC\u0627\u0644 \u0627\u0646\u062C\u0627\u0645 \u0634\u062F\u060C \u0645\u0648\u062C\u0648\u062F\u06CC: 1,000,000 \u0631\u06CC\u0627\u0644";

    // ============================================================
    // Full first scan
    // ============================================================

    @Test public void fullFirstScan_capturesEveryMovementAcrossBanks() throws Exception {
        seed("500095", DEPOSIT + " \u0645\u0627\u0644 A", T);            // Saman +200k
        seed("b.pasargad", WITHDRAWAL, T + 500);                       // Pasargad -120k
        seed("5000973189", "\u0645\u0648\u062C\u0648\u062F\u06CC \u062D\u0633\u0627\u0628 \u0634\u0645\u0627: 1,000,000 \u0631\u06CC\u0627\u0644", T + 1000); // balance-only -> no txn

        int added = BalanceData.scanHistory(ctx);

        assertEquals(2, added);
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(2, txs.size());
        assertEquals(-120000L, txs.get(0).amount);  // newest first (T+500)
        assertEquals(200000L, txs.get(1).amount);   // oldest last (T)
        assertEquals(T + 1000, historyWatermark());
    }

    @Test public void fullFirstScan_oldMovementsBeyondBalanceTail_areStillCaptured() throws Exception {
        // The balance scan stops per bank at the newest message; history must reach back further
        // and pick up the older movements too.
        seed("500095", DEPOSIT + " \u0645\u0627\u0644 A", T);            // old movement Saman
        seed("500095", "\u0645\u0648\u062C\u0648\u062F\u06CC \u062D\u0633\u0627\u0628 \u0634\u0645\u0627: 5,000,000 \u0631\u06CC\u0627\u0644", T + 10000); // newest balance-only
        seed("b.pasargad", WITHDRAWAL, T + 300);                       // older movement Pasargad

        int added = BalanceData.scanHistory(ctx);

        assertEquals(2, added);
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(2, txs.size());
        assertEquals(-120000L, txs.get(0).amount);
        assertEquals(200000L, txs.get(1).amount);
    }

    // ============================================================
    // Incremental scans
    // ============================================================

    @Test public void incremental_onlySeesNewMessages_messagesWhileClosedAreCaught() throws Exception {
        seed("500095", DEPOSIT + " \u0645\u0627\u0644 A", T + 1000);
        assertEquals(1, BalanceData.scanHistory(ctx));

        // Multiple messages arrive while the app is "closed"; one refresh catches them all.
        seed("500095", DEPOSIT.replace("3,000,000", "3,200,000") + " \u0645\u0627\u0644 B", T + 5000);
        seed("500095", WITHDRAWAL + " \u062F\u0648\u0645", T + 6000);

        int added = BalanceData.scanHistory(ctx);
        assertEquals(2, added);
        assertEquals(3, BalanceData.readTransactions(ctx).size());
    }

    @Test public void incremental_nothingNew_addsNothing() throws Exception {
        seed("500095", DEPOSIT + " \u0645\u0627\u0644 A", T + 1000);
        BalanceData.scanHistory(ctx);

        int added = BalanceData.scanHistory(ctx);

        assertEquals(0, added);
        assertEquals(1, BalanceData.readTransactions(ctx).size());
    }

    @Test public void twoMessagesSameBank_areBothRecorded() throws Exception {
        seed("500095", DEPOSIT + " \u0645\u0627\u0644 A", T + 1000);
        seed("500095", DEPOSIT.replace("200,000", "500,000") + " \u0645\u0627\u0644 B", T + 2000);

        int added = BalanceData.scanHistory(ctx);

        assertEquals(2, added);
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(500000L, txs.get(0).amount);
        assertEquals(200000L, txs.get(1).amount);
    }

    // ============================================================
    // Real-world bank formats (Tejarat / Blu / Parsian)
    // ============================================================

    @Test public void realBankFormats_everyMovementAcrossAllThreeBanks() throws Exception {
        seed("TejaratBank", TEJARAT_WITHDRAWAL, T);
        seed("+989999987641", BLU_WITHDRAWAL, T + 500);
        seed("PARSIANBANK", PARSIAN_WITHDRAWAL, T + 1000);
        seed("TejaratBank", TEJARAT_DEPOSIT, T + 1500);

        int added = BalanceData.scanHistory(ctx);

        assertEquals(4, added);
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(4, txs.size());
        assertEquals(115000000L, txs.get(0).amount);   // Tejarat deposit (newest)
        assertEquals(-500000L, txs.get(1).amount);     // Parsian
        assertEquals(-400000L, txs.get(2).amount);     // Blu
        assertEquals(-70014000L, txs.get(3).amount);   // Tejarat withdrawal (oldest)
    }

    @Test public void realBankFormat_resalat_bareSignedAmounts_areRecorded() throws Exception {
        seed("2000474701", RESALAT_WITHDRAWAL_1, T);
        seed("2000474701", RESALAT_WITHDRAWAL_2, T + 500);

        int added = BalanceData.scanHistory(ctx);

        assertEquals(2, added);
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(2, txs.size());
        assertEquals(-40000L, txs.get(0).amount);        // newest first
        assertEquals(-200000000L, txs.get(1).amount);    // oldest last
    }

    @Test public void realBankFormat_resalat_duplicateDelivery_countsOnce() throws Exception {
        seed("2000474701", RESALAT_WITHDRAWAL_1, T);
        seed("2000474701", RESALAT_WITHDRAWAL_1, T + 500);

        int added = BalanceData.scanHistory(ctx);

        assertEquals(1, added);
        assertEquals(1, BalanceData.readTransactions(ctx).size());
    }

    // ============================================================
    // Exact-duplicate messages are one entry
    // ============================================================

    @Test public void exactDuplicateMessage_deliveredTwice_countsOnce() throws Exception {
        seed("500095", DEPOSIT + " \u0645\u0627\u0644 A", T + 1000);
        seed("500095", DEPOSIT + " \u0645\u0627\u0644 A", T + 1100);

        int added = BalanceData.scanHistory(ctx);

        assertEquals(1, added);
        assertEquals(1, BalanceData.readTransactions(ctx).size());
        int again = BalanceData.scanHistory(ctx);
        assertEquals(0, again);
        assertEquals(1, BalanceData.readTransactions(ctx).size());
    }

    @Test public void sameAmountDifferentMessage_stillTwoEntries() throws Exception {
        // Two genuinely different movements of the same amount on the same day: the fingerprints must
        // differ because each message reports a different resulting balance.
        seed("500095", DEPOSIT + " \u06A9\u0627\u0631\u062A 1234", T + 1000);
        seed("500095", DEPOSIT.replace("3,000,000", "3,200,000") + " \u06A9\u0627\u0631\u062A 5678", T + 1500);

        int added = BalanceData.scanHistory(ctx);

        assertEquals(2, added);
        assertEquals(2, BalanceData.readTransactions(ctx).size());
    }

    @Test public void sameAmountMovements_sameDay_bothCapturedWhenBalanceMoves() throws Exception {
        // Two genuine deposits of the same value, same day, same bank: the resulting balances differ,
        // so both are real history and neither may be swallowed by dedup.
        seed("500095",
            "\u0645\u0628\u0644\u063A 200,000 \u0631\u06CC\u0627\u0644 \u0648\u0627\u0631\u06CC\u0632 \u0634\u062F\u060C \u0645\u0648\u062C\u0648\u062F\u06CC: 1,000,000 \u0631\u06CC\u0627\u0644", T + 1000);
        seed("500095",
            "\u0645\u0628\u0644\u063A 200,000 \u0631\u06CC\u0627\u0644 \u0648\u0627\u0631\u06CC\u0632 \u0634\u062F\u060C \u0645\u0648\u062C\u0648\u062F\u06CC: 1,200,000 \u0631\u06CC\u0627\u0644", T + 1000);

        int added = BalanceData.scanHistory(ctx);

        assertEquals(2, added);
        assertEquals(2, BalanceData.readTransactions(ctx).size());
    }

    @Test public void bankDoubleDelivery_sameBalance_dedupedDespiteDifferentRef() throws Exception {
        // A bank that mistakenly sends a purchase SMS twice (once per card line in the same message,
        // or a redelivery) keeps the same resulting balance; the fingerprint must collapse them even
        // though the volatile reference/card text differs.
        seed("b.pasargad",
            "\u0645\u0628\u0644\u063A 200,000 \u0631\u06CC\u0627\u0644 \u0628\u0627 \u06A9\u0627\u0631\u062A 1234 \u062E\u0631\u06CC\u062F \u0634\u062F\u060C \u0645\u0648\u062C\u0648\u062F\u06CC: 1,000,000 \u0631\u06CC\u0627\u0644", T + 1000);
        seed("b.pasargad",
            "\u0645\u0628\u0644\u063A 200,000 \u0631\u06CC\u0627\u0644 \u0628\u0627 \u06A9\u0627\u0631\u062A 9876 \u062E\u0631\u06CC\u062F \u0634\u062F\u060C \u0645\u0648\u062C\u0648\u062F\u06CC: 1,000,000 \u0631\u06CC\u0627\u0644", T + 1100);

        int added = BalanceData.scanHistory(ctx);

        assertEquals(1, added);
        assertEquals(1, BalanceData.readTransactions(ctx).size());
    }

    @Test public void legacySiglessEntries_areNotDuplicatedOnRescan() throws Exception {
        // Simulates history written by the previous version (no fingerprints): a fresh scan must
        // dedupe against it via the bank|date|amount fallback instead of adding a copy.
        seed("500095", DEPOSIT, T + 1000);
        List<Transaction> legacy = new java.util.ArrayList<>();
        legacy.add(new Transaction("Saman", T + 1000, 200000L, null));
        BalanceData.writeTransactions(ctx, legacy);

        int added = BalanceData.scanHistory(ctx);

        assertEquals(0, added);
        assertEquals(1, BalanceData.readTransactions(ctx).size());
    }

    // ============================================================
    // Independence from the balance scan
    // ============================================================

    @Test public void balanceScan_leavesTransactionsAndHistoryWatermarkUntouched() throws Exception {
        seed("500095", DEPOSIT, T + 1000);

        java.util.LinkedHashMap<String, Bank> saved = new java.util.LinkedHashMap<>();
        BalanceData.scanSms(ctx, saved);

        assertEquals(0, BalanceData.readTransactions(ctx).size());
        assertEquals(0, historyWatermark());

        int added = BalanceData.scanHistory(ctx);
        assertEquals(1, added);
        assertEquals(1, BalanceData.readTransactions(ctx).size());
    }

    // ============================================================
    // Hard reset
    // ============================================================

    @Test public void hardReset_clearsHistory_andNextScanRebuildsFromInbox() throws Exception {
        seed("500095", DEPOSIT, T + 1000);
        BalanceData.scanHistory(ctx);
        assertEquals(1, BalanceData.readTransactions(ctx).size());

        BalanceData.reset(ctx);

        assertEquals(0, BalanceData.readTransactions(ctx).size());
        int rebuilt = BalanceData.scanHistory(ctx);
        assertEquals(1, rebuilt);
        assertEquals(1, BalanceData.readTransactions(ctx).size());
    }
}