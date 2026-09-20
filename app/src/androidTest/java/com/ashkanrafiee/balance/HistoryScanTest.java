package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
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
        + "\u062D\u0633\u0627\u0628: 01351234567890 \n"
        + "\u0628\u0631\u062F\u0627\u0634\u062A: 70,014,000 \u0631\u06CC\u0627\u0644 \n"
        + "\u0627\u0632 \u0637\u0631\u06CC\u0642: \u0633\u0627\u0645\u0627\u0646\u0647 \u067E\u0644 (\u067E\u0631\u062F\u0627\u062E\u062A \u0644\u062D\u0638\u0647 \u0627\u06CC)  \n"
        + "\u0645\u0627\u0646\u062F\u0647: 1,209,288 \u0631\u06CC\u0627\u0644 \n"
        + "1405/06/07\n20:16";
    private static final String TEJARAT_DEPOSIT =
        "*\u0628\u0627\u0646\u06A9 \u062A\u062C\u0627\u0631\u062A* \n"
        + "\u062D\u0633\u0627\u0628: 01351234567890 \n"
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
        "30101234567890\n"
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
    private static final String MELLAT_TRANSFER =
        "\u0628\u0631\u062F\u0627\u0634\u062A100,000,000 \u0645\u0627\u0646\u062F\u0647 77,222,945";
    private static final String MELLAT_FEE =
        "\u0628\u0631\u062F\u0627\u0634\u062A10,000 \u0645\u0627\u0646\u062F\u0647 177,222,945";
    private static final String MELLAT_DELTA =
        "\u067E\u0631\u062F\u0627\u062E\u062A \u0627\u0646\u062C\u0627\u0645 \u0634\u062F\u060C \u0645\u0627\u0646\u062F\u0647 \u062D\u0633\u0627\u0628: 72,222,945";
    private static final String MELLI_ACCT_TRANSFER =
        "\u0627\u0646\u062A\u0642\u0627\u0644\u06CC:100,000,000-\n"
        + "\u062D\u0633\u0627\u0628:10001\n"
        + "\u0645\u0627\u0646\u062F\u0647:77,222,945\n"
        + "0620-21:16";
    private static final String MELLI_ACCT_FEE =
        "\u06A9\u0627\u0631\u0645\u0632\u062F:10,000-\n"
        + "\u062D\u0633\u0627\u0628:10001\n"
        + "\u0645\u0627\u0646\u062F\u0647:177,222,945\n"
        + "0620-21:17";

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
        // Broadcast to the injector's receiver rather than launching its activity: a receiver has no
        // window/launch lifecycle to race with the next seed under test-suite load.
        exec("am broadcast -n com.ashkanrafiee.smsinject/.SeedReceiver -a com.ashkanrafiee.smsinject.CLEAR");
        // Clearing is async across processes: wait until the inbox is actually empty so the next
        // test never sees a leftover row, regardless of device load.
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
        awaitSms(sender, body);
    }

    /** Polls the real inbox until the exact seeded message is visible, so that the scan that follows
     *  in the same test is deterministic even when the system is slow. */
    private void awaitSms(String sender, String body) throws Exception {
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
        fail("seeded SMS did not arrive in time: sender=" + sender);
    }

    /** Runs an injector transaction scenario (action tx) and waits until all of its messages are in the
     *  inbox. The injector writes the messages from its own process, so the wait makes the scan that
     *  follows deterministic. */
    private void seedTxScenario(String scenario, int expectedRows) throws Exception {
        exec("am start -n com.ashkanrafiee.smsinject/.MainActivity -e action tx -e scenario " + scenario);
        long deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline) {
            int count = 0;
            try (android.database.Cursor c = ctx.getContentResolver().query(
                    android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{android.provider.Telephony.Sms._ID}, null, null, null)) {
                if (c != null) while (c.moveToNext()) count++;
            }
            if (count >= expectedRows) return;
            Thread.sleep(150);
        }
        fail("tx scenario " + scenario + " messages did not arrive in time");
    }

    /** Waits until at least {@code count} rows with the given sender and body are in the inbox,
     *  for scenarios where several identical copies of one message are delivered. */
    private void awaitRows(String sender, String body, int count) throws Exception {
        long deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline) {
            try (android.database.Cursor c = ctx.getContentResolver().query(
                    android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{android.provider.Telephony.Sms.ADDRESS, android.provider.Telephony.Sms.BODY},
                    null, null, null)) {
                int found = 0;
                if (c != null) {
                    while (c.moveToNext()) {
                        if (sender.equals(c.getString(0)) && body.equals(c.getString(1))) found++;
                    }
                }
                if (found >= count) return;
            }
            Thread.sleep(150);
        }
        fail("fewer than " + count + " copies of the seeded message arrived: sender=" + sender);
    }

    private int amountsOf(List<Transaction> txs, long amount) {
        int n = 0;
        for (Transaction t : txs) if (t.amount == amount) n++;
        return n;
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

    @Test public void futureDatedMovement_neverFreezesHistoryScan() throws Exception {
        // A forged or clock-skewed message dated far in the future must not advance the history
        // watermark past every genuine message: dates are clamped to real time, so movements that
        // arrive afterwards are still captured on the next incremental scan.
        long future = System.currentTimeMillis() + 86_400_000L;
        seed("2000474701", RESALAT_WITHDRAWAL_1, future);

        assertEquals(1, BalanceData.scanHistory(ctx));
        assertTrue("history watermark must not leap into the future",
            historyWatermark() <= System.currentTimeMillis());

        long after = System.currentTimeMillis() + 60_000L;
        seed("TejaratBank", TEJARAT_DEPOSIT, after);

        assertEquals(1, BalanceData.scanHistory(ctx));
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(2, txs.size());
    }

    @Test public void rulesNowRecognizeAccount_storedAccountlessTwin_isNotDoubled() throws Exception {
        // A bank that used to be scanned without account rules may have left account-less entries whose
        // messages are still present. Once rules recognize the account, the same message re-parses with
        // an account, and without reconciliation the stored account-less twin and the fresh
        // account-bearing row would both surface as the same event. Same bank, same moment and
        // identical content must collapse into a single account-bearing entry.
        String sender = "TejaratBank";
        long date = T + 42;
        seed(sender, TEJARAT_DEPOSIT, date);

        // The state the older account-less rules would have left: the same movement stored without an
        // account and with its account-free fingerprint.
        String freeSig = BalanceData.messageSig(sender, TEJARAT_DEPOSIT);
        List<Transaction> oldRules = new java.util.ArrayList<>();
        oldRules.add(new Transaction("Tejarat", null, date, 115000000L, freeSig));
        BalanceData.writeTransactions(ctx, oldRules);

        int added = BalanceData.scanHistory(ctx);

        assertEquals(1, added);
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(1, txs.size());
        assertEquals(115000000L, txs.get(0).amount);
        assertEquals("01351234567890", txs.get(0).account);
    }

    @Test public void rulesChangingParseAndAccount_twinIsClaimedByContentDigest() throws Exception {
        // The strongest claim: a rules update that re-measures the same message's amount entirely
        // breaks both the fingerprint (different parsed values) and the amount match, but the
        // parse-independent content digest still proves the stored entry and the fresh parse are the
        // same physical message. The stored twin is written with a content digest (as entries are after
        // the feature ships); the claim drops it and keeps the fresh account-bearing parse.
        String sender = "TejaratBank";
        long date = T + 77;
        seed(sender, TEJARAT_DEPOSIT, date);

        String content = BalanceData.contentHash(sender, TEJARAT_DEPOSIT);
        List<Transaction> oldRules = new java.util.ArrayList<>();
        oldRules.add(new Transaction("Tejarat", null, date, 1L, "stale-fingerprint", content));
        BalanceData.writeTransactions(ctx, oldRules);

        int added = BalanceData.scanHistory(ctx);

        assertEquals(1, added);
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(1, txs.size());
        assertEquals(115000000L, txs.get(0).amount);
        assertEquals("01351234567890", txs.get(0).account);
    }

    @Test public void rulesNowRecognizeAccount_twinWithDifferentOldParse_isClaimedByAmount() throws Exception {
        // If the older rules parsed the same still-present message into a different looking content, its
        // fingerprint no longer matches the fresh account-bearing parse; same bank, same moment and same
        // amount is then the only reliable claim the account-less era affords.
        String sender = "TejaratBank";
        long date = T + 99;
        seed(sender, TEJARAT_DEPOSIT, date);

        // The old-rules parse misread some text, so its stored fingerprint differs from today's, but the
        // recorded amount and the movement's moment are the same event.
        String wrongParseSig = BalanceData.messageSig(sender, TEJARAT_DEPOSIT) + "x";
        List<Transaction> oldRules = new java.util.ArrayList<>();
        oldRules.add(new Transaction("Tejarat", null, date, 115000000L, wrongParseSig));
        BalanceData.writeTransactions(ctx, oldRules);

        int added = BalanceData.scanHistory(ctx);

        assertEquals(1, added);
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(1, txs.size());
        assertEquals(115000000L, txs.get(0).amount);
        assertEquals("01351234567890", txs.get(0).account);
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

    @Test public void realBankFormat_mehrIran_bareTrailingSignedAmounts_areRecorded() throws Exception {
        // Mehr Iran opens with the account digits alone (RTL bidi-wrapped) and writes its movement
        // as a bare amount with the sign trailing ("400,000-"), with the resulting balance on the
        // last line. Both must be attributed to the per-account slot.
        seed("B.QMEHRIRAN", "\u202A302601234567890123\u202C\n400,000-\n1405/6/29-20:30\n"
            + "\u0645\u0627\u0646\u062F\u0647:865,083", T);
        seed("B.QMEHRIRAN", "\u202A302601234567890123\u202C\n120,500+\n1405/6/29-20:31\n"
            + "\u0645\u0627\u0646\u062F\u0647:985,583", T + 1000);

        int added = BalanceData.scanHistory(ctx);

        assertEquals(2, added);
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(2, txs.size());
        long sum = 0;
        for (Transaction t : txs) {
            assertEquals("Mehr", t.bank);
            assertEquals("302601234567890123", t.account);
            sum += t.amount;
        }
        assertEquals(-279500L, sum);   // -400,000 + +120,500
    }

    @Test public void realBankFormat_pasargadDottedAccountMovements_areRecorded() throws Exception {
        // Pasargad opens with the four-part dotted account id alone and writes the amount with a
        // leading sign ("-508,000"), with the resulting balance on the last line.
        seed("B.Pasargad", "123.456.78901234.5\n-508,000\n06/29_21:06\n\u0645\u0627\u0646\u062F\u0647: 51,289", T);
        seed("B.Pasargad", "123.456.78901234.5\n+120,000\n06/30_09:14\n\u0645\u0627\u0646\u062F\u0647: 171,289", T + 1000);

        int added = BalanceData.scanHistory(ctx);

        assertEquals(2, added);
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(2, txs.size());
        long sum = 0;
        for (Transaction t : txs) {
            assertEquals("Pasargad", t.bank);
            assertEquals("123.456.78901234.5", t.account);
            sum += t.amount;
        }
        assertEquals(-388000L, sum);    // -508,000 + +120,000
    }

    @Test public void realBankFormat_saderatAccountLabelMovements_areRecorded() throws Exception {
        // Saderat writes "انتقال: <amount>-" and the account right after the "حساب:" label, with
        // the resulting balance in the line below.
        seed("BankSaderat", " \u0627\u0646\u062A\u0642\u0627\u0644: 500,000-\n \u062D\u0633\u0627\u0628:48203\n"
            + " \u0645\u0627\u0646\u062F\u0647:422,050\n 0629 - 21:00 ", T);
        seed("BankSaderat", " \u0627\u0646\u062A\u0642\u0627\u0644: 350,000+\n \u062D\u0633\u0627\u0628:48203\n"
            + " \u0645\u0627\u0646\u062F\u0647:772,050\n 0630 - 09:20 ", T + 1000);

        int added = BalanceData.scanHistory(ctx);

        assertEquals(2, added);
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(2, txs.size());
        long sum = 0;
        for (Transaction t : txs) {
            assertEquals("Saderat", t.bank);
            assertEquals("48203", t.account);
            sum += t.amount;
        }
        assertEquals(-150000L, sum);    // -500,000 + +350,000
    }

    @Test public void realBankFormat_resalat_duplicateDelivery_countsOnce() throws Exception {
        seed("2000474701", RESALAT_WITHDRAWAL_1, T);
        seed("2000474701", RESALAT_WITHDRAWAL_1, T + 500);

        int added = BalanceData.scanHistory(ctx);

        assertEquals(1, added);
        assertEquals(1, BalanceData.readTransactions(ctx).size());
    }

    @Test public void injectorTxScenario_resalatMovements_areRecorded() throws Exception {
        // The sms-injector's "resalat" scenario seeds only Resalat's bare-signed-amount layout, in
        // chronological order: -200M, then -40k, then a +5M deposit.
        seedTxScenario("resalat", 3);

        int added = BalanceData.scanHistory(ctx);

        assertEquals(3, added);
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(3, txs.size());
        assertEquals(5000000L, txs.get(0).amount);        // newest first
        assertEquals(-40000L, txs.get(1).amount);
        assertEquals(-200000000L, txs.get(2).amount);
    }

    @Test public void injectorTxScenario_demo_includesResalatMovements() throws Exception {
        // The shared "demo" scenario carries movements for every bank (incl. Resalat), so History
        // spans all supported senders instead of only the ones a single test seeds.
        seedTxScenario("demo", 13);

        int added = BalanceData.scanHistory(ctx);

        assertEquals(13, added);
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(13, txs.size());
        long resalatWithdrawals = 0;
        for (Transaction t : txs) {
            if ("Resalat".equals(t.bank) && t.amount < 0) resalatWithdrawals++;
        }
        assertEquals(2, resalatWithdrawals);
    }

    @Test public void injectorTxScenario_accounts_movementsAreSplitPerAccount() throws Exception {
        // The sms-injector's "accounts" scenario seeds account-bearing movements: three on Mellat
        // across two account numbers, one on Melli (حساب:10001) and one on a Resalat dotted account.
        // Each message states its account number, so history splits the bank into per-account rows
        // instead of one mixed chain.
        seedTxScenario("accounts", 5);

        int added = BalanceData.scanHistory(ctx);

        assertEquals(5, added);
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(5, txs.size());
        long mellatAcct1 = 0, mellatAcct2 = 0, melli = 0, resalat = 0;
        for (Transaction t : txs) {
            switch (t.bank) {
                case "Mellat":
                    if ("1110000222".equals(t.account)) mellatAcct1++;
                    else if ("1110000333".equals(t.account)) mellatAcct2++;
                    break;
                case "Melli": if ("10001".equals(t.account)) melli++; break;
                case "Resalat": if ("10.1234567.2".equals(t.account)) resalat++; break;
                default: break;
            }
        }
        assertEquals(2, mellatAcct1);   // deposit + withdrawal on the first account
        assertEquals(1, mellatAcct2);   // deposit on the second account
        assertEquals(1, melli);
        assertEquals(1, resalat);
    }

    @Test public void injectorTxScenario_melliLabeledSigns_recordedExactlyPerAccount() throws Exception {
        // The user-reported Melli layout: "<label>:<amount><sign>" with the sign trailing the number
        // and no "مبلغ:"/"واریز:" label or unit. The amount and its sign come from the message itself,
        // so the chronologically-first movement of each account is recorded exactly instead of being
        // dropped by the balance-delta (which needs a previous balance it cannot have for the first).
        String sender = "9830009417";
        seed(sender, "\u0627\u0646\u062A\u0642\u0627\u0644\u06CC:1,000,000-\n"
            + "\u062D\u0633\u0627\u0628:10001\n"
            + "\u0645\u0627\u0646\u062F\u0647:1,058,405\n"
            + "0629-17:09", T);
        seed(sender, "\u0627\u0646\u062A\u0642\u0627\u0644\u06CC:1,000,000-\n"
            + "\u062D\u0633\u0627\u0628:10001\n"
            + "\u0645\u0627\u0646\u062F\u0647:208,405\n"
            + "0629-17:23", T + 1000);
        seed(sender, "\u062F\u0631\u06CC\u0627\u0641\u062A \u06CC\u0627\u0631\u0627\u0646\u0647:7,700,000+\n"
            + "\u062D\u0633\u0627\u0628:10002\n"
            + "\u0645\u0627\u0646\u062F\u0647:7,820,112\n"
            + "0620-23:12", T + 2000);
        seed(sender, "\u062E\u0631\u06CC\u062F\u0627\u06CC\u0646\u062A\u0631\u0646\u062A\u06CC:7,600,000-\n"
            + "\u062D\u0633\u0627\u0628:10002\n"
            + "\u0645\u0627\u0646\u062F\u0647:220,112\n"
            + "0620-23:13", T + 3000);

        int added = BalanceData.scanHistory(ctx);

        assertEquals(4, added);
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(4, txs.size());
        long acct1 = 0, acct2 = 0;
        for (Transaction t : txs) {
            assertEquals("Melli", t.bank);
            if ("10001".equals(t.account)) acct1 += t.amount;
            else if ("10002".equals(t.account)) acct2 += t.amount;
            else fail("unexpected account " + t.account);
        }
        assertEquals(-2000000L, acct1);   // two -1,000,000 transfers on account 10001
        assertEquals(100000L, acct2);     // +7,700,000 deposit then -7,600,000 purchase on 10002
    }

    @Test public void tejaratAndParsianAccountMovements_splitPerAccount() throws Exception {
        // Tejarat (the "حساب:" line) and Parsian (the opening account line) also state their account
        // number in the real messages, so their histories split per account like Melli/Mellat.
        seed("TejaratBank", TEJARAT_WITHDRAWAL, T);
        seed("PARSIANBANK", PARSIAN_WITHDRAWAL, T + 1000);
        seed("TejaratBank", "*\u0628\u0627\u0646\u06A9 \u062A\u062C\u0627\u0631\u062A* \n"
            + "\u062D\u0633\u0627\u0628: 01351234567891 \n"
            + "\u0648\u0627\u0631\u06CC\u0632: 115,000,000 \u0631\u06CC\u0627\u0644 \n"
            + "\u0627\u0632 \u0637\u0631\u06CC\u0642: \u0633\u0627\u0645\u0627\u0646\u0647 \u067E\u0644 (\u067E\u0631\u062F\u0627\u062E\u062A \u0644\u062D\u0638\u0647 \u0627\u06CC)  \n"
            + "\u0645\u0627\u0646\u062F\u0647: 361,919,288 \u0631\u06CC\u0627\u0644 \n"
            + "1405/06/06\n00:08", T + 2000);

        int added = BalanceData.scanHistory(ctx);

        assertEquals(3, added);
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(3, txs.size());
        int tejaratA = 0, tejaratB = 0, parsian = 0;
        for (Transaction t : txs) {
            switch (t.bank) {
                case "Tejarat":
                    if ("01351234567890".equals(t.account)) tejaratA += t.amount;
                    else if ("01351234567891".equals(t.account)) tejaratB += t.amount;
                    else fail("unexpected Tejarat account " + t.account);
                    break;
                case "Parsian":
                    if ("30101234567890".equals(t.account)) parsian += t.amount;
                    else fail("unexpected Parsian account " + t.account);
                    break;
                default: fail("unexpected bank " + t.bank);
            }
        }
        assertEquals(-70014000L, tejaratA);
        assertEquals(115000000L, tejaratB);
        assertEquals(-500000L, parsian);
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

    @Test public void legacySiglessEntries_areMigratedOnUpgradeRescan() throws Exception {
        // History written by the previous version had no fingerprints. A full upgrade re-scan rebuilds
        // from the messages still in the inbox, so the legacy entry is replaced by the fingerprinted
        // parse of its own message — exactly one entry, never a copy added on top.
        seed("500095", DEPOSIT, T + 1000);
        List<Transaction> legacy = new java.util.ArrayList<>();
        legacy.add(new Transaction("Saman", T + 1000, 200000L, null));
        BalanceData.writeTransactions(ctx, legacy);

        int added = BalanceData.scanHistory(ctx);

        assertEquals(1, added);
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(1, txs.size());
        assertNotNull(txs.get(0).sig);
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
    // Reverse-arrival movements (fee before/after its transfer)
    // ============================================================

    @Test public void sameScan_reversedFeeBeforeTransfer_storesTrueOrder() throws Exception {
        // Resalat sent the fee (resulting balance 2,279,505,033) BEFORE the transfer it belongs to
        // (2,279,545,033). The fee is the true-newest event, but arrived first: the stored history
        // must follow the chain, not the arrival order.
        seed("2000474701", RESALAT_WITHDRAWAL_2, T);
        seed("2000474701", RESALAT_WITHDRAWAL_1, T + 500);

        int added = BalanceData.scanHistory(ctx);

        assertEquals(2, added);
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(2, txs.size());
        assertEquals(-40000L, txs.get(0).amount);          // fee (true newest)
        assertEquals(-200000000L, txs.get(1).amount);      // transfer (true oldest)
    }

    @Test public void sameScan_reversedTransferBeforeFee_currentBalanceIsTransfer() throws Exception {
        // Mellat sent the transfer (77,222,945) BEFORE its fee (177,222,945). Last balance is stored
        // for the chain-end bank and a later message must measure from it.
        seed("+9815560001", MELLAT_TRANSFER, T);
        seed("+9815560001", MELLAT_FEE, T + 500);

        int added = BalanceData.scanHistory(ctx);

        assertEquals(2, added);
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(2, txs.size());
        assertEquals(-100000000L, txs.get(0).amount);      // transfer (true newest)
        assertEquals(-10000L, txs.get(1).amount);          // fee (true oldest)
    }

    @Test public void splitScan_feeArrivesBeforeTransfer_feeStaysNewest() throws Exception {
        seed("2000474701", RESALAT_WITHDRAWAL_2, T);       // fee arrives first, scanned alone
        assertEquals(1, BalanceData.scanHistory(ctx));
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(1, txs.size());
        assertEquals(-40000L, txs.get(0).amount);

        seed("2000474701", RESALAT_WITHDRAWAL_1, T + 500); // transfer in a later scan
        int second = BalanceData.scanHistory(ctx);

        assertEquals(1, second);
        txs = BalanceData.readTransactions(ctx);
        assertEquals(2, txs.size());
        assertEquals(-40000L, txs.get(0).amount);          // fee stays the true-newest entry
        assertEquals(-200000000L, txs.get(1).amount);      // transfer sits below it
    }

    @Test public void splitScan_transferBeforeFee_transferStaysNewest() throws Exception {
        seed("+9815560001", MELLAT_TRANSFER, T + 1000);
        assertEquals(1, BalanceData.scanHistory(ctx));
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(1, txs.size());
        assertEquals(-100000000L, txs.get(0).amount);

        seed("+9815560001", MELLAT_FEE, T + 2000);
        int second = BalanceData.scanHistory(ctx);

        assertEquals(1, second);
        txs = BalanceData.readTransactions(ctx);
        assertEquals(2, txs.size());
        assertEquals(-100000000L, txs.get(0).amount);      // transfer (true newest) stays on top
        assertEquals(-10000L, txs.get(1).amount);          // fee placed below it
    }

    @Test public void splitScan_accountBearingFeeAndTransfer_feePlacedUnderTransfer() throws Exception {
        // Same reversed-pair arrival as splitScan_transferBeforeFee, but on Melli messages that state
        // account 10001. Window and chain fingerprints must fold the account so the fee is placed next
        // to its account-bearing stored sibling instead of being appended on top (the fee and transfer
        // arrive in reverse order, so a broken fingerprint leaves them back-to-front).
        String sender = "9830009417";
        seed(sender, MELLI_ACCT_TRANSFER, T + 1000);
        assertEquals(1, BalanceData.scanHistory(ctx));
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(1, txs.size());
        assertEquals(-100000000L, txs.get(0).amount);

        seed(sender, MELLI_ACCT_FEE, T + 2000);
        int second = BalanceData.scanHistory(ctx);

        assertEquals(1, second);
        txs = BalanceData.readTransactions(ctx);
        assertEquals(2, txs.size());
        assertEquals("10001", txs.get(0).account);
        assertEquals(-100000000L, txs.get(0).amount);      // transfer (true newest) stays on top
        assertEquals(-10000L, txs.get(1).amount);          // fee placed below it
    }

    @Test public void splitScan_deltaAfterReversedPair_measuredFromTrueNewestBalance() throws Exception {
        // The fee's stated balance (177M) must never seed the delta chain: after the reversed pair the
        // account's real value is the transfer's 77M, so a later delta message (72,222,945) records a
        // -5M movement, not -105M.
        seed("+9815560001", MELLAT_TRANSFER, T + 1000);
        assertEquals(1, BalanceData.scanHistory(ctx));
        seed("+9815560001", MELLAT_FEE, T + 2000);
        assertEquals(1, BalanceData.scanHistory(ctx));

        seed("+9815560001", MELLAT_DELTA, T + 3000);
        int third = BalanceData.scanHistory(ctx);

        assertEquals(1, third);
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(3, txs.size());
        assertEquals(-5000000L, txs.get(2).amount);        // 77,222,945 - 72,222,945
    }

    // ============================================================
    // Rules-version change (full re-scan)
    // ============================================================

    @Test public void rulesBump_reprocessesPresentMovement_correctingStaleEntry() throws Exception {
        // A message still in the inbox must be re-parsed under the current rules after a rules-version
        // change, replacing the stale entry the old rules recorded instead of being deduped against it.
        seed("500095", DEPOSIT, T + 1000);
        assertEquals(1, BalanceData.scanHistory(ctx));
        BalanceData.writeTransactions(ctx, java.util.Arrays.asList(
            new Transaction("Saman", T + 1000, 999_999L, "stale-sig")));
        prefs().edit().putInt(BalanceData.KEY_HISTORY_RULES_VERSION,
            BalanceData.HISTORY_RULES_VERSION - 1).commit();

        int added = BalanceData.scanHistory(ctx);

        assertEquals(1, added);
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(1, txs.size());
        assertEquals(200000L, txs.get(0).amount);
        assertFalse("stale-sig".equals(txs.get(0).sig));
        assertEquals(BalanceData.HISTORY_RULES_VERSION,
            prefs().getInt(BalanceData.KEY_HISTORY_RULES_VERSION, -1));
    }

    @Test public void rulesBump_reprocessesPresent_whileKeepingDeletedMessagesInHistory() throws Exception {
        // The deposit is deleted from the inbox before the rules bump: its transaction is an orphan
        // and must stay in history. The present withdrawal is re-processed. Nothing is lost, nothing
        // is doubled.
        seed("500095", DEPOSIT, T + 1000);
        seed("500095", WITHDRAWAL, T + 2000);
        assertEquals(2, BalanceData.scanHistory(ctx));
        assertEquals(2, BalanceData.readTransactions(ctx).size());

        clearInbox();
        seed("500095", WITHDRAWAL, T + 2000);
        prefs().edit().putInt(BalanceData.KEY_HISTORY_RULES_VERSION,
            BalanceData.HISTORY_RULES_VERSION - 1).commit();

        int added = BalanceData.scanHistory(ctx);

        assertEquals(1, added);
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(2, txs.size());
        assertEquals(-120000L, txs.get(0).amount);   // present withdrawal re-processed, newest first
        assertEquals(200000L, txs.get(1).amount);    // deleted deposit preserved as an orphan
        assertEquals(BalanceData.HISTORY_RULES_VERSION,
            prefs().getInt(BalanceData.KEY_HISTORY_RULES_VERSION, -1));
    }

    @Test public void rulesBump_emptyInbox_keepsAllStoredHistory() throws Exception {
        // Every message is deleted before the rules bump: all stored transactions are orphans and
        // must survive untouched.
        seed("500095", DEPOSIT, T + 1000);
        seed("500095", WITHDRAWAL, T + 2000);
        assertEquals(2, BalanceData.scanHistory(ctx));

        clearInbox();
        prefs().edit().putInt(BalanceData.KEY_HISTORY_RULES_VERSION,
            BalanceData.HISTORY_RULES_VERSION - 1).commit();

        int added = BalanceData.scanHistory(ctx);

        assertEquals(0, added);
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(2, txs.size());
        assertEquals(1, amountsOf(txs, 200000L));
        assertEquals(1, amountsOf(txs, -120000L));
    }

    @Test public void rulesBump_sameMomentSibling_neverDoubledOrDropped() throws Exception {
        // Two movements share the same (bank, date). One message is gone before the bump; the rebuild
        // must pair a present parse with its own stored twin by fingerprint — a (bank, date) budget
        // would claim the sibling in the wrong stored slot, dropping the orphan and doubling the live
        // movement.
        seed("500095", DEPOSIT, T);
        assertEquals(1, BalanceData.scanHistory(ctx));
        Transaction deposit = BalanceData.readTransactions(ctx).get(0);
        // Put the orphan first so a (bank, date)-keyed match would hit it.
        BalanceData.writeTransactions(ctx, java.util.Arrays.asList(
            new Transaction("Saman", T, 999_999L, "orphan-sig"), deposit));
        assertEquals(2, BalanceData.readTransactions(ctx).size());

        prefs().edit().putInt(BalanceData.KEY_HISTORY_RULES_VERSION,
            BalanceData.HISTORY_RULES_VERSION - 1).commit();
        int added = BalanceData.scanHistory(ctx);

        assertEquals(1, added);
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(2, txs.size());
        assertEquals(1, amountsOf(txs, 200000L));     // the surviving movement, once
        assertEquals(1, amountsOf(txs, 999_999L));    // the orphan, kept
    }

    @Test public void rulesBump_duplicateContentAcrossTimestamps_mergesToOneEntry() throws Exception {
        // The same movement content delivered twice collapses to one transaction (per fingerprint).
        // If the older copy is then deleted, the full re-scan must recognize the newer copy as the
        // same transaction instead of keeping the old entry as an orphan and adding a second one.
        seed("500095", DEPOSIT, T + 1000);
        seed("500095", DEPOSIT, T + 2000);
        awaitRows("500095", DEPOSIT, 2);
        assertEquals(1, BalanceData.scanHistory(ctx));
        assertEquals(1, BalanceData.readTransactions(ctx).size());

        clearInbox();
        seed("500095", DEPOSIT, T + 2000);            // only the newer copy remains
        prefs().edit().putInt(BalanceData.KEY_HISTORY_RULES_VERSION,
            BalanceData.HISTORY_RULES_VERSION - 1).commit();

        int added = BalanceData.scanHistory(ctx);

        assertEquals(1, added);
        List<Transaction> txs = BalanceData.readTransactions(ctx);
        assertEquals(1, txs.size());
        assertEquals(200000L, txs.get(0).amount);
        assertEquals(T + 2000, txs.get(0).date);
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