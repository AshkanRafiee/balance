package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

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
 * Systematic scan tests against the real SMS provider (LCB-style instrumented, no UI).
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
        Thread.sleep(700);
    }

    private void clearInbox() throws Exception {
        exec("am start -n com.ashkanrafiee.smsinject/.MainActivity -e action clear");
    }

    private void seed(String sender, String body, long base) throws Exception {
        String b64 = android.util.Base64.encodeToString(
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        exec("am start -n com.ashkanrafiee.smsinject/.MainActivity -e sender " + sender
                + " -e body64 " + b64 + " -e base " + base);
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
    // Permission handling
    // ============================================================

    @Test public void zz_scanWithoutSmsPermission_returnsZeroWithoutSideEffects() throws Exception {
        Context foreign = null;
        String[] candidates = {
            "com.android.systemui", "com.android.launcher3", "com.google.android.googlequicksearchbox"};
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