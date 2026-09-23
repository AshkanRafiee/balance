package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.util.LinkedHashMap;

/**
 * Systematic scan tests against the real SMS provider (instrumented, no UI).
 *
 * Every test starts from a clean inbox + clean prefs, so the full-scan and
 * incremental-scan states are fully deterministic. Test messages are seeded
 * with explicit dates through the com.ashkanrafiee.smsinject helper app, whose
 * MainActivity writes SMS rows with the exact DATE we supply.
 */
@RunWith(AndroidJUnit4.class)
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
public class BalanceScanTest {

    /** Arbitrary epoch for seeded test messages. */
    private static final long T = 1_000_000_000L;

    private static final String MELLAT_TRANSFER =
        "\u0628\u0631\u062F\u0627\u0634\u062A100,000,000 \u0645\u0627\u0646\u062F\u0647 77,222,945";
    private static final String MELLAT_FEE =
        "\u0628\u0631\u062F\u0627\u0634\u062A10,000 \u0645\u0627\u0646\u062F\u0647 177,222,945";
    private static final String RESALAT_TRANSFER =
        "-200,000,000  \n06/22_20:37 \n\u0645\u0627\u0646\u062F\u0647: 2,279,545,033";
    private static final String RESALAT_FEE =
        "-40,000  \n06/22_20:37 \n\u0645\u0627\u0646\u062F\u0647: 2,279,505,033";

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
        try (java.io.InputStream is = new android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
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

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE);
    }

    private long watermark() {
        return prefs().getLong(BalanceData.KEY_SCANNED_THROUGH, 0);
    }

    private int storedRulesVersion() {
        return prefs().getInt(BalanceData.KEY_RULES_VERSION, -1);
    }

    private static long date(Bank b) {
        return b.date;
    }

    private static long amount(Bank b) {
        return b.amount;
    }

    private static Bank find(LinkedHashMap<String, Bank> map, String bank) {
        return map.get(bank);
    }

    // ============================================================
    // Full-first-scan behaviour
    // ============================================================

    @Test public void freshInstall_fullScanFindsEverySeededBankAndPersists() throws Exception {
        seed("5000973189", "\u0645\u0648\u062C\u0648\u062F\u06CC \u062D\u0633\u0627\u0628 \u0634\u0645\u0627: 1,000,000 \u0631\u06CC\u0627\u0644", T + 1000);
        seed("500095", "available balance 5,000,000", T + 2000);

        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        int matched = BalanceData.scanSms(ctx, saved);

        assertEquals(2, matched);
        Bank tejarat = find(saved, "Tejarat");
        Bank saman = find(saved, "Saman");
        assertEquals(1000000L, amount(tejarat));
        assertEquals(T + 1000, date(tejarat));
        assertEquals(5000000L, amount(saman));
        assertEquals(T + 2000, date(saman));
        assertEquals(T + 2000, watermark());
        assertEquals(BankRules.VERSION, storedRulesVersion());

        LinkedHashMap<String, Bank> reread = BalanceData.read(ctx);
        assertEquals(2, reread.size());
        assertEquals(5000000L, amount(find(reread, "Saman")));
    }

    @Test public void fullScan_supersedesLegacyPlainSlot_whenMessagesAreReKeyedPerAccount() throws Exception {
        // Upgrade path from before account keys: the stored map holds a plain "Melli" slot carrying the
        // balance merged across accounts. A full re-scan that re-keys Melli messages per account must
        // drop that legacy slot, or the old merged balance is shown (and summed) beside the new
        // per-account rows.
        LinkedHashMap<String, Bank> legacy = new LinkedHashMap<>();
        legacy.put("Melli", new Bank("Melli", 1_058_405L, T, "9830009417", null));
        BalanceData.write(ctx, legacy);

        seed("9830009417", "\u0627\u0646\u062A\u0642\u0627\u0644\u06CC:87,925,688-\n"
            + "\u062D\u0633\u0627\u0628:10001\n"
            + "\u0645\u0627\u0646\u062F\u0647:1,058,405\n"
            + "0620-17:09", T + 1000);

        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        assertEquals(1, BalanceData.scanSms(ctx, saved));

        LinkedHashMap<String, Bank> after = BalanceData.read(ctx);
        assertEquals(1, after.size());
        assertNull(after.get("Melli"));
        Bank acct = after.get("Melli|10001");
        assertNotNull(acct);
        assertEquals(1_058_405L, acct.amount);
        assertEquals("10001", acct.account);
        assertEquals("9830009417", acct.sender);
    }

    @Test public void tejaratMessagesWithTwoAccounts_staySeparateEntries() throws Exception {
        // Tejarat movement messages state their account on the "حساب:" line. A single bank with two
        // accounts is no exception: each account keeps its own balance entry beside the other.
        seed("TejaratBank", "*\u0628\u0627\u0646\u06A9 \u062A\u062C\u0627\u0631\u062A*\n"
            + "\u062D\u0633\u0627\u0628: 01351234567890 \n"
            + "\u0628\u0631\u062F\u0627\u0634\u062A: 70,014,000 \u0631\u06CC\u0627\u0644 \n"
            + "\u0627\u0632 \u0637\u0631\u06CC\u0642: \u0633\u0627\u0645\u0627\u0646\u0647 \u067E\u0644 (\u067E\u0631\u062F\u0627\u062E\u062A \u0644\u062D\u0638\u0647 \u0627\u06CC)  \n"
            + "\u0645\u0627\u0646\u062F\u0647: 1,209,288 \u0631\u06CC\u0627\u0644 \n"
            + "1405/06/07\n20:16", T + 1000);
        seed("TejaratBank", "*\u0628\u0627\u0646\u06A9 \u062A\u062C\u0627\u0631\u062A*\n"
            + "\u062D\u0633\u0627\u0628: 01351234567890 \n"
            + "\u0648\u0627\u0631\u06CC\u0632: 15,000,000 \u0631\u06CC\u0627\u0644 \n"
            + "\u0627\u0632 \u0637\u0631\u06CC\u0642: \u0633\u0627\u0645\u0627\u0646\u0647 \u067E\u0644 (\u067E\u0631\u062F\u0627\u062E\u062A \u0644\u062D\u0638\u0647 \u0627\u06CC)  \n"
            + "\u0645\u0627\u0646\u062F\u0647: 16,209,288 \u0631\u06CC\u0627\u0644 \n"
            + "1405/06/07\n20:17", T + 2000);
        seed("TejaratBank", "*\u0628\u0627\u0646\u06A9 \u062A\u062C\u0627\u0631\u062A*\n"
            + "\u062D\u0633\u0627\u0628: 01351234567891 \n"
            + "\u0648\u0627\u0631\u06CC\u0632: 115,000,000 \u0631\u06CC\u0627\u0644 \n"
            + "\u0627\u0632 \u0637\u0631\u06CC\u0642: \u0633\u0627\u0645\u0627\u0646\u0647 \u067E\u0644 (\u067E\u0631\u062F\u0627\u062E\u062A \u0644\u062D\u0638\u0647 \u0627\u06CC)  \n"
            + "\u0645\u0627\u0646\u062F\u0647: 361,919,288 \u0631\u06CC\u0627\u0644 \n"
            + "1405/06/06\n00:08", T + 3000);

        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        assertEquals(2, BalanceData.scanSms(ctx, saved));   // one entry per account

        LinkedHashMap<String, Bank> after = BalanceData.read(ctx);
        assertEquals(2, after.size());
        Bank acct1 = after.get("Tejarat|01351234567890");
        Bank acct2 = after.get("Tejarat|01351234567891");
        assertNotNull(acct1);
        assertNotNull(acct2);
        assertEquals(16_209_288L, acct1.amount);      // account 1 latest balance of its pair
        assertEquals(361_919_288L, acct2.amount);
        assertEquals("01351234567890", acct1.account);
        assertEquals("01351234567891", acct2.account);
    }

    @Test public void parsianAccountOpeningLine_singleAccountEntry() throws Exception {
        // Parsian movement messages open with the account on its own line, so they land in a
        // per-account entry instead of a bank-wide slot.
        seed("PARSIANBANK", "30101234567890\n"
            + "\u0645\u0628\u0644\u063A:500,000-\n"
            + "\u0645\u0627\u0646\u062F\u0647:1,076,220\n"
            + "05/06\n06:12", T + 1000);

        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        assertEquals(1, BalanceData.scanSms(ctx, saved));

        LinkedHashMap<String, Bank> after = BalanceData.read(ctx);
        assertEquals(1, after.size());
        Bank acct = after.get("Parsian|30101234567890");
        assertNotNull(acct);
        assertEquals(1_076_220L, acct.amount);
        assertEquals("30101234567890", acct.account);
    }

    @Test public void mehrAccountOpeningLine_singleAccountEntry() throws Exception {
        // Mehr Iran movements open with the account digits alone (RTL bidi-wrapped), so their
        // balances land in a per-account entry instead of a bank-wide slot.
        seed("B.QMEHRIRAN", "\u202A302601234567890123\u202C\n400,000-\n1405/6/29-20:30\n"
            + "\u0645\u0627\u0646\u062F\u0647:865,083", T + 1000);

        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        assertEquals(1, BalanceData.scanSms(ctx, saved));

        LinkedHashMap<String, Bank> after = BalanceData.read(ctx);
        assertEquals(1, after.size());
        Bank acct = after.get("Mehr|302601234567890123");
        assertNotNull(acct);
        assertEquals(865_083L, acct.amount);
        assertEquals("302601234567890123", acct.account);
    }

    @Test public void pasargadDottedAccountLine_singleAccountEntry() throws Exception {
        // Pasargad movements open with the four-part dotted account id alone, so their balances land
        // in a per-account entry (dotted id kept verbatim) instead of a bank-wide slot.
        seed("B.Pasargad", "123.456.78901234.5\n-508,000\n06/29_21:06\n\u0645\u0627\u0646\u062F\u0647: 51,289",
            T + 1000);

        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        assertEquals(1, BalanceData.scanSms(ctx, saved));

        LinkedHashMap<String, Bank> after = BalanceData.read(ctx);
        assertEquals(1, after.size());
        Bank acct = after.get("Pasargad|123.456.78901234.5");
        assertNotNull(acct);
        assertEquals(51_289L, acct.amount);
        assertEquals("123.456.78901234.5", acct.account);
    }

    @Test public void saderatAccountLabel_singleAccountEntry() throws Exception {
        // Saderat movements state the account right after the "حساب:" label, so their balances land
        // in a per-account entry instead of a bank-wide slot.
        seed("BankSaderat", " \u0627\u0646\u062A\u0642\u0627\u0644: 500,000-\n \u062D\u0633\u0627\u0628:48203\n"
            + " \u0645\u0627\u0646\u062F\u0647:422,050\n 0629 - 21:00 ", T + 1000);

        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        assertEquals(1, BalanceData.scanSms(ctx, saved));

        LinkedHashMap<String, Bank> after = BalanceData.read(ctx);
        assertEquals(1, after.size());
        Bank acct = after.get("Saderat|48203");
        assertNotNull(acct);
        assertEquals(422_050L, acct.amount);
        assertEquals("48203", acct.account);
    }

    @Test public void freshInstallPersianDigitMessage_parsesValue() throws Exception {
        seed("5000973189",
                "\u0645\u0648\u062C\u0648\u062F\u06CC \u062D\u0633\u0627\u0628 \u0634\u0645\u0627: \u06F1\u066C\u06F2\u06F5\u06F0\u066C\u06F0\u06F0\u06F0 \u0631\u06CC\u0627\u0644",
                T + 1000);

        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        int matched = BalanceData.scanSms(ctx, saved);

        assertEquals(1, matched);
        assertEquals(1250000L, amount(find(saved, "Tejarat")));
    }

    @Test public void freshInstall_zeroBalance_isStoredNotSkipped() throws Exception {
        seed("5000973189", "\u0645\u0648\u062C\u0648\u062F\u06CC: 0", T + 1000);

        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        int matched = BalanceData.scanSms(ctx, saved);

        assertEquals(1, matched);
        assertEquals(0L, amount(find(saved, "Tejarat")));
        assertEquals(T + 1000, date(find(saved, "Tejarat")));
    }

    @Test public void freshInstall_emptyInbox_returnsZeroRecordsVersionButNoWatermark() throws Exception {
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        int matched = BalanceData.scanSms(ctx, saved);

        assertEquals(0, matched);
        assertEquals(0, saved.size());
        assertEquals(0, watermark());
        assertEquals(BankRules.VERSION, storedRulesVersion());
    }

    @Test public void freshInstall_otpAndPromoNeverTreatedAsBalance() throws Exception {
        seed("5000973189", "\u06A9\u062F \u062A\u0627\u06CC\u06CC\u062F \u0634\u0645\u0627: 55221", T + 2000);
        seed("5000973189", "\u062A\u062E\u0641\u06CC\u0641 \u0648\u06CC\u0698\u0647", T + 1900);
        seed("5000973189", "\u0645\u0648\u062C\u0648\u062F\u06CC: 666,000", T + 1600);

        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        int matched = BalanceData.scanSms(ctx, saved);

        assertEquals(1, matched);
        assertEquals(666000L, amount(find(saved, "Tejarat")));
        assertEquals(T + 1600, date(find(saved, "Tejarat")));
        assertEquals(T + 2000, watermark());
    }

    @Test public void freshInstall_sameBankManyMessages_keepsNewestOnly_matchCountsBankOnce() throws Exception {
        seed("5000973189", "\u0645\u0648\u062C\u0648\u062F\u06CC: 600,000", T + 5000);
        seed("5000973189", "\u0645\u0648\u062C\u0648\u062F\u06CC: 1,000,000", T + 4000);
        seed("5000973189", "\u0645\u0648\u062C\u0648\u062F\u06CC: 800,000", T + 4500);

        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        int matched = BalanceData.scanSms(ctx, saved);

        assertEquals(1, matched);
        assertEquals(600000L, amount(find(saved, "Tejarat")));
        assertEquals(T + 5000, date(find(saved, "Tejarat")));
    }

    @Test public void freshInstall_nonBankNoiseSendersWithBalanceBodies_skippedButWatermarkAdvances() throws Exception {
        seed("1234567890", "\u0645\u0648\u062C\u0648\u062F\u06CC \u062D\u0633\u0627\u0628 \u0634\u0645\u0627: 9,000,000 \u0631\u06CC\u0627\u0644", T + 1500);
        seed("YouTube", "Your available balance is 7,000", T + 1600);

        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        int matched = BalanceData.scanSms(ctx, saved);

        assertEquals(0, matched);
        assertEquals(0, saved.size());
        assertEquals(T + 1600, watermark());
    }

    // ============================================================
    // Incremental scans
    // ============================================================

    @Test public void incremental_onlyReadsRowsAboveWatermark() throws Exception {
        seed("5000973189", "\u0645\u0648\u062C\u0648\u062F\u06CC: 1,000,000", T + 1000);
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        BalanceData.scanSms(ctx, saved);

        seed("500095", "available balance 5,000,000", T + 2000);
        seed("5000973189", "\u0645\u0648\u062C\u0648\u062F\u06CC: 777,000", T + 500);

        int matched = BalanceData.scanSms(ctx, saved);

        assertEquals(1, matched);
        assertEquals(1_000_000L, amount(find(saved, "Tejarat")));
        assertEquals(T + 1000, date(find(saved, "Tejarat")));
        assertEquals(5_000_000L, amount(find(saved, "Saman")));
        assertEquals(T + 2000, watermark());
    }

    @Test public void incremental_nothingNew_returnsZeroAndAdvancesNothing() throws Exception {
        seed("5000973189", "\u0645\u0648\u062C\u0648\u062F\u06CC: 1,000,000", T + 1000);
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        BalanceData.scanSms(ctx, saved);

        int matched = BalanceData.scanSms(ctx, saved);

        assertEquals(0, matched);
        assertEquals(T + 1000, watermark());
        assertEquals(1_000_000L, amount(find(saved, "Tejarat")));
    }

    @Test public void incremental_twoBanksArrivedWhileAppClosed_oneRefreshFindsBoth() throws Exception {
        seed("5000973189", "\u0645\u0648\u062C\u0648\u062F\u06CC: 1,000,000", T + 1000);
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        BalanceData.scanSms(ctx, saved);

        seed("b.pasargad", "available balance 3,000,000", T + 3000);
        seed("500095", "available balance 9,000,000", T + 3500);

        int matched = BalanceData.scanSms(ctx, saved);

        assertEquals(2, matched);
        assertEquals(3, saved.size());
        assertNotNull(find(saved, "Pasargad"));
        assertEquals(3_000_000L, amount(find(saved, "Pasargad")));
        assertEquals(9_000_000L, amount(find(saved, "Saman")));
        assertEquals(T + 3500, watermark());
    }

    @Test public void incremental_sameDateAsWatermark_excludedByStrictUpperBound() throws Exception {
        seed("5000973189", "\u0645\u0648\u062C\u0648\u062F\u06CC: 1,000,000", T + 1000);
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        BalanceData.scanSms(ctx, saved);

        seed("5000973189", "\u0645\u0648\u062C\u0648\u062F\u06CC: 2,000,000", T + 1000);

        int matched = BalanceData.scanSms(ctx, saved);

        assertEquals(0, matched);
        assertEquals(1_000_000L, amount(find(saved, "Tejarat")));
        assertEquals(T + 1000, watermark());
    }

    @Test public void incremental_noMatchWindow_keepsOldValueButAdvancesWatermark() throws Exception {
        seed("5000973189", "\u0645\u0648\u062C\u0648\u062F\u06CC: 1,000,000", T + 1000);
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        BalanceData.scanSms(ctx, saved);

        seed("5000973189", "\u062A\u062E\u0641\u06CC\u0641 \u0648\u06CC\u0698\u0647", T + 2000);
        seed("5000973189", "\u0645\u06CC\u0644\u0627\u062F \u0645\u0628\u0627\u0631\u06A9", T + 3000);

        int matched = BalanceData.scanSms(ctx, saved);

        assertEquals(0, matched);
        assertEquals(1_000_000L, amount(find(saved, "Tejarat")));
        assertEquals(T + 1000, date(find(saved, "Tejarat")));
        assertEquals(T + 3000, watermark());

        int again = BalanceData.scanSms(ctx, saved);
        assertEquals(0, again);
        assertEquals(T + 3000, watermark());
    }

    // ============================================================
    // Reach-back and forced rescans
    // ============================================================

    @Test public void reachBack_newerSmsAboveWatermark_adoptedForSameBank() throws Exception {
        seed("5000973189", "\u0645\u0648\u062C\u0648\u062F\u06CC: 800,000", T + 4000);
        seed("5000973189", "\u062A\u062E\u0641\u06CC\u0641 \u0648\u06CC\u0698\u0647", T + 5000);
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        BalanceData.scanSms(ctx, saved);

        seed("5000973189", "\u0645\u0648\u062C\u0648\u062F\u06CC: 900,000", T + 7000);

        int matched = BalanceData.scanSms(ctx, saved);

        assertEquals(1, matched);
        assertEquals(900000L, amount(find(saved, "Tejarat")));
        assertEquals(T + 7000, date(find(saved, "Tejarat")));
        assertEquals(T + 7000, watermark());
    }

    @Test public void reachBack_freshInstall_findsOldestBalancePastNewerPromo() throws Exception {
        seed("5000973189", "promo \u06F5\u06F0\u06F0", T + 5000);
        seed("5000973189", "\u0645\u0648\u062C\u0648\u062F\u06CC: 400,000", T + 100);
        seed("5000973189", "\u0645\u0648\u062C\u0648\u062F\u06CC: 100,000", T);

        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        int matched = BalanceData.scanSms(ctx, saved);

        assertEquals(1, matched);
        assertEquals(400000L, amount(find(saved, "Tejarat")));
        assertEquals(T + 100, date(find(saved, "Tejarat")));
        assertEquals(T + 5000, watermark());
    }

    // ============================================================
    // Rules-version forced rescan
    // ============================================================

    @Test public void rulesVersionChange_forcesFullRescan_discoveringOlderBankBelowWatermark() throws Exception {
        seed("5000973189", "\u0645\u0648\u062C\u0648\u062F\u06CC: 1,000,000", T + 5000);
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        BalanceData.scanSms(ctx, saved);

        prefs().edit().putInt(BalanceData.KEY_RULES_VERSION, 0).commit();

        seed("500095", "available balance 6,000,000", T + 400);

        int matched = BalanceData.scanSms(ctx, saved);

        assertEquals(1, matched);
        assertEquals(6_000_000L, amount(find(saved, "Saman")));
        assertEquals(T + 400, date(find(saved, "Saman")));
        assertEquals(BankRules.VERSION, storedRulesVersion());
        assertEquals(T + 5000, watermark());
    }

    // ============================================================
    // Hard reset and forced rescans
    // ============================================================

    @Test public void resetDeletesSavedBalancesWatermarkAndRulesVersion() throws Exception {
        seed("5000973189", "\u0645\u0648\u062C\u0648\u062F\u06CC: 1,000,000", T + 1000);
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        BalanceData.scanSms(ctx, saved);
        assertEquals(1, saved.size());

        BalanceData.reset(ctx, false);

        assertEquals(0, BalanceData.read(ctx).size());
        assertEquals(0, watermark());
        assertEquals(-1, storedRulesVersion());
    }

    @Test public void resetThenRescan_rebuildsOnlyFromCurrentlyAvailableMessages() throws Exception {
        seed("5000973189", "\u0645\u0648\u062C\u0648\u062F\u06CC: 1,000,000", T + 1000);
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        BalanceData.scanSms(ctx, saved);
        assertEquals(1, saved.size());

        clearInbox();
        seed("b.pasargad", "available balance 5,000,000", T + 500);

        BalanceData.reset(ctx, false);
        saved = new LinkedHashMap<>();
        int matched = BalanceData.scanSms(ctx, saved);

        assertEquals(1, matched);
        assertEquals(1, saved.size());
        assertEquals(5_000_000L, amount(find(saved, "Pasargad")));
        assertNull(find(saved, "Tejarat"));
        assertEquals(T + 500, watermark());
        assertEquals(1, BalanceData.read(ctx).size());
    }

    @Test public void resetWithEmptyInbox_leavesStoreEmptyAndFullScanArmed() throws Exception {
        seed("5000973189", "\u0645\u0648\u062C\u0648\u062F\u06CC: 1,000,000", T + 1000);
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        BalanceData.scanSms(ctx, saved);
        assertEquals(1, saved.size());

        clearInbox();
        BalanceData.reset(ctx, false);
        saved = new LinkedHashMap<>();
        int matched = BalanceData.scanSms(ctx, saved);

        assertEquals(0, matched);
        assertEquals(0, saved.size());
        assertEquals(0, BalanceData.read(ctx).size());
        assertEquals(0, watermark());

        seed("5000973189", "\u0645\u0648\u062C\u0648\u062F\u06CC: 2,000,000", T + 400);
        int again = BalanceData.scanSms(ctx, saved);
        assertEquals(1, again);
        assertEquals(2_000_000L, amount(find(saved, "Tejarat")));
    }

    /** A rules bump that meets an empty inbox must not confirm the version: the stored balances
     *  could not be re-derived, so the rebuild has to be retried on the next open. */
    @Test public void rulesVersionReset_withEmptyInbox_keepsStaleBalancesButDoesNotConfirmTheVersion() throws Exception {
        seed("5000973189", "\u0645\u0648\u062C\u0648\u062F\u06CC: 1,000,000", T + 1000);
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        assertEquals(1, BalanceData.scanSms(ctx, saved));
        assertEquals(1_000_000L, amount(find(saved, "Tejarat")));
        assertEquals(BankRules.VERSION, storedRulesVersion());

        prefs().edit().putInt(BalanceData.KEY_RULES_VERSION, BankRules.VERSION - 1).commit();
        clearInbox();
        saved = new LinkedHashMap<>();
        int matched = BalanceData.scanSms(ctx, saved);

        assertEquals(0, matched);
        assertEquals("Stale balances survive an empty full scan", 1_000_000L,
            amount(find(saved, "Tejarat")));
        assertEquals("The version stays stale so the next open retries the rebuild",
            BankRules.VERSION - 1, storedRulesVersion());

        seed("5000973189", "\u0645\u0648\u062C\u0648\u062F\u06CC: 2,000,000", T + 2000);
        saved = new LinkedHashMap<>();
        int again = BalanceData.scanSms(ctx, saved);

        assertEquals(1, again);
        assertEquals(2_000_000L, amount(find(saved, "Tejarat")));
        assertEquals("A real rebuild confirms the rules version", BankRules.VERSION,
            storedRulesVersion());
    }

    // ============================================================
    // Reverse-arrival movements (fee + transfer)
    // ============================================================

    @Test public void reversal_mellatTransferArrivesBeforeFee_keepsTransferBalance() throws Exception {
        // The transfer (77,222,945) is the true-newest event but arrived first; the newest-arrived
        // fee (177,222,945) must not override it.
        seed("+9815560001", MELLAT_TRANSFER, T + 1000);
        seed("+9815560001", MELLAT_FEE, T + 2000);

        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        int matched = BalanceData.scanSms(ctx, saved);

        assertEquals(1, matched);
        assertEquals(77222945L, amount(find(saved, "Mellat")));
        assertEquals(T + 1000, date(find(saved, "Mellat")));
    }

    @Test public void reversal_resalatFeeArrivesBeforeTransfer_keepsFeeBalance() throws Exception {
        // The fee (2,279,505,033) is the true-newest event but arrived first; the newest-arrived
        // transfer (2,279,545,033) must not override it.
        seed("2000474701", RESALAT_FEE, T + 1000);
        seed("2000474701", RESALAT_TRANSFER, T + 2000);

        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        int matched = BalanceData.scanSms(ctx, saved);

        assertEquals(1, matched);
        assertEquals(2279505033L, amount(find(saved, "Resalat")));
        assertEquals(T + 1000, date(find(saved, "Resalat")));
    }

    @Test public void reversal_splitAcrossScans_feeArrivingLater_neverOverwritesTransferBalance() throws Exception {
        seed("+9815560001", MELLAT_TRANSFER, T + 1000);
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        assertEquals(1, BalanceData.scanSms(ctx, saved));
        assertEquals(77222945L, amount(find(saved, "Mellat")));

        // The fee belongs BEFORE the transfer: even though it arrives in a later scan, the account
        // balance stays at the transfer's value.
        seed("+9815560001", MELLAT_FEE, T + 2000);
        int second = BalanceData.scanSms(ctx, saved);

        assertEquals(0, second);
        assertEquals(77222945L, amount(find(saved, "Mellat")));
        assertEquals(T + 1000, date(find(saved, "Mellat")));
    }

    @Test public void reversal_accountBearingTransferArrivesBeforeFee_keepsTransferBalance() throws Exception {
        // Tejarat movements state their account, so every stored chain fingerprint is account-qualified.
        // The transfer is the true-newest event but arrived first; the newest-arrived fee must not
        // override it. This guards the account-qualified chain matching: the scan-side movement check
        // must fold the account into the signature exactly like the window merger did, or the chain is
        // never trusted and the arrival order (fee) wins.
        String transfer = "*\u0628\u0627\u0646\u06A9 \u062A\u062C\u0627\u0631\u062A*\n"
            + "\u062D\u0633\u0627\u0628: 01351234567890 \n"
            + "\u0628\u0631\u062F\u0627\u0634\u062A: 100,000,000 \u0631\u06CC\u0627\u0644 \n"
            + "\u0645\u0627\u0646\u062F\u0647: 77,222,945 \u0631\u06CC\u0627\u0644 \n"
            + "1405/06/07\n20:16";
        String fee = "*\u0628\u0627\u0646\u06A9 \u062A\u062C\u0627\u0631\u062A*\n"
            + "\u062D\u0633\u0627\u0628: 01351234567890 \n"
            + "\u0628\u0631\u062F\u0627\u0634\u062A: 10,000 \u0631\u06CC\u0627\u0644 \n"
            + "\u0645\u0627\u0646\u062F\u0647: 177,222,945 \u0631\u06CC\u0627\u0644 \n"
            + "1405/06/07\n20:17";
        seed("TejaratBank", transfer, T + 1000);
        seed("TejaratBank", fee, T + 2000);

        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        assertEquals(1, BalanceData.scanSms(ctx, saved));

        LinkedHashMap<String, Bank> after = BalanceData.read(ctx);
        assertEquals(1, after.size());
        Bank acct = after.get("Tejarat|01351234567890");
        assertNotNull(acct);
        assertEquals(77_222_945L, acct.amount);
        assertEquals(T + 1000, acct.date);
    }

    @Test public void futureDatedMessage_neverFreezesIncrementalScanning() throws Exception {
        // A forged or clock-skewed message dated far in the future must not push the scan watermark
        // past every genuine message: the watermark is clamped to real time, so messages arriving
        // afterwards are still read on the next refresh.
        long future = System.currentTimeMillis() + 86_400_000L;
        seed("500095", "available balance 5,000,000", future);

        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        assertEquals(1, BalanceData.scanSms(ctx, saved));
        assertEquals(5_000_000L, amount(find(saved, "Saman")));
        assertTrue("watermark must not leap into the future", watermark() <= System.currentTimeMillis());

        long after = System.currentTimeMillis() + 60_000L;
        seed("5000973189",
            "\u0645\u0648\u062C\u0648\u062F\u06CC \u062D\u0633\u0627\u0628 \u0634\u0645\u0627: 2,222,222 \u0631\u06CC\u0627\u0644",
            after);

        // The future-dated row may be re-read on later scans (each re-clamp dates it to that scan's
        // "now"), but the genuine message that arrived in between must be scanned too. Without the
        // watermark clamp it never would be, because the watermark would sit a whole day ahead.
        assertTrue("a genuine message arriving later must still be scanned",
            BalanceData.scanSms(ctx, saved) >= 1);
        assertEquals(2_222_222L, amount(find(saved, "Tejarat")));
    }

    // ============================================================
    // Permission handling
    // ============================================================

    @Test public void zz_scanWithoutSmsPermission_returnsZeroWithoutSideEffects() throws Exception {
        Context foreign = null;
        String[] candidates = {
            "com.android.systemui", "com.android.launcher3", "com.google.android.googlequicksearchbox",
            "com.android.documentsui", "com.android.camera2", "com.android.printspooler",
            "com.android.settings", "com.android.deskclock", "com.android.phone",
            "com.android.providers.contacts"};
        for (String p : candidates) {
            try {
                Context c = ctx.createPackageContext(p, Context.CONTEXT_IGNORE_SECURITY);
                if (c.checkSelfPermission(android.Manifest.permission.READ_SMS)
                        != PackageManager.PERMISSION_GRANTED) {
                    foreign = c;
                    break;
                }
            } catch (Exception ignored) { }
        }
        org.junit.Assume.assumeTrue("no permission-less package available", foreign != null);

        long wmBefore = watermark();
        int verBefore = storedRulesVersion();
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();

        int matched = BalanceData.scanSms(foreign, saved);

        assertEquals(0, matched);
        assertEquals(0, saved.size());
        assertEquals(wmBefore, watermark());
        assertEquals(verBefore, storedRulesVersion());
    }
}