package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Telephony;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/**
 * Opt-in, destructive emulator experiment; run alone, never alongside another storage/scan test.
 * Requires the installed smsinject helper and -e scanExperiment true. Three samples at each size.
 * Ten synthetic Tejarat accounts each have one withdrawal repeated 10 or 100 times, with distinct
 * arrival timestamps. This measures 100/1000 inbox rows with intentionally collapsed duplicate
 * signatures: exactly ten balances and ten transactions, NOT 1000 unique financial histories.
 * No noise, other banks, long per-account chains, cold-process or growing-history coverage.
 * Full means fresh isolated stores for every sample; the immediately following incremental pair
 * uses those stores and the unchanged inbox. Balance always precedes history (shared window cache).
 * Timings cover synchronous scan calls, including their persistence scheduling, but exclude seeding,
 * assertions, read-back and the explicit disk flush used for XML byte counts. Caches/JIT stay warm;
 * sample one includes first-use costs. XML sizes are encrypted file lengths, never file contents.
 * The cooperative 180s work budget excludes helper/provider waits; it cannot interrupt a scan or
 * shell command. Budget exhaustion reports incomplete and still cleans up, without a timing failure.
 */
@RunWith(AndroidJUnit4.class)
public class ParserScanExperimentTest {
    private static final String PREFIX = "parser_scan_experiment_";
    private static final String HELPER = "am broadcast -n com.ashkanrafiee.smsinject/.SeedReceiver"
            + " -a com.ashkanrafiee.smsinject.";
    private static final long BASE = 1_000_000_000L;
    private static final long STEP = 1000L;
    private static final int DISTINCT = 10;
    private static final int SAMPLES = 3;
    private static final long BUDGET_MS = 180_000L;
    private static final String[] STORES = {BalanceData.PREFS_PREF, BalanceData.PREFS_DATA};

    private Instrumentation instrumentation;
    private UiAutomation automation;
    private Context context;
    private long started;
    private long helperWaitMs;
    private int completedPairs;

    @Test public void realInboxScanExperiment() throws Exception {
        assumeTrue("scan_experiment_opt_in",
                "true".equals(InstrumentationRegistry.getArguments().getString("scanExperiment")));
        // Fail closed BEFORE permission grants, preference access, CLEAR or SEED. A generic model
        // name/fingerprint alone is not sufficient evidence that this is a disposable emulator.
        assertTrue("scan_experiment_emulator_only",
                "ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE));
        instrumentation = InstrumentationRegistry.getInstrumentation();
        automation = instrumentation.getUiAutomation();
        Context target = instrumentation.getTargetContext();
        // BalanceData's scan/read/write/window helpers use the supplied context, not a global
        // application context. Prefix every requested preference name, including future helpers.
        context = new ContextWrapper(target) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(PREFIX + name, mode);
            }

            @Override public Context getApplicationContext() { return this; }
        };
        started = SystemClock.elapsedRealtime();
        try {
            automation.adoptShellPermissionIdentity(Manifest.permission.READ_SMS);
            // scanSms/scanHistory explicitly check the target's runtime permission, so adopting
            // shell identity alone is not enough. Match the existing real-provider scan helpers.
            exec("pm grant " + target.getPackageName() + " android.permission.READ_SMS");
            assertEquals("read_sms_permission", PackageManager.PERMISSION_GRANTED,
                    context.checkSelfPermission(Manifest.permission.READ_SMS));
            for (int rows : new int[]{100, 1000}) {
                if (!withinBudget()) return;
                clearInbox();
                seedDataset(rows);
                for (int sample = 1; sample <= SAMPLES; sample++) {
                    if (!withinBudget()) return;
                    resetPreferences();
                    assertEquals(0L, watermark(BalanceData.KEY_SCANNED_THROUGH));
                    assertEquals(0L, watermark(BalanceData.KEY_HISTORY_THROUGH));
                    verifyInbox(rows);
                    if (!measurePair(rows, sample, true)) return;
                    verifyIncrementalState(rows);
                    if (!measurePair(rows, sample, false)) return;
                    verifyIncrementalState(rows);
                }
            }
            Bundle done = new Bundle();
            done.putString("scan_experiment", "complete");
            done.putInt("completed_pairs", completedPairs);
            instrumentation.sendStatus(0, done);
        } finally {
            try {
                clearInbox();
            } finally {
                try {
                    resetPreferences();
                } finally {
                    automation.dropShellPermissionIdentity();
                }
            }
        }
    }

    private boolean measurePair(int rows, int sample, boolean full) throws Exception {
        if (!withinBudget()) return false;
        LinkedHashMap<String, Bank> balances = new LinkedHashMap<>();
        long start = SystemClock.elapsedRealtimeNanos();
        int matched = BalanceData.scanSms(context, balances);
        long balanceNs = SystemClock.elapsedRealtimeNanos() - start;
        assertEquals("balance_matches", full ? DISTINCT : 0, matched);
        assertEquals("balance_size", DISTINCT, balances.size());
        if (!withinBudget()) return false;
        start = SystemClock.elapsedRealtimeNanos();
        int added = BalanceData.scanHistory(context);
        long historyNs = SystemClock.elapsedRealtimeNanos() - start;
        assertEquals("history_added", full ? DISTINCT : 0, added);
        assertEquals("persisted_balances", DISTINCT, BalanceData.read(context).size());
        int historySize = BalanceData.readTransactions(context).size();
        assertEquals("history_size", DISTINCT, historySize);
        // commit waits for the preceding apply writes; excluded from scan latency on purpose.
        for (String name : STORES) {
            assertTrue("flush_preferences", preferences(name).edit().commit());
        }
        Bundle result = new Bundle();
        result.putString("scan_experiment", "sample");
        result.putString("dataset", "ten_accounts_duplicate_movements");
        result.putString("mode", full ? "full_fresh_store" : "incremental_no_arrivals");
        result.putInt("inbox_rows", rows);
        result.putInt("sample", sample);
        result.putLong("balance_ns", balanceNs);
        result.putLong("history_ns", historyNs);
        result.putInt("balance_matches", matched);
        result.putInt("history_added", added);
        result.putInt("balance_size", balances.size());
        result.putInt("history_size", historySize);
        result.putLong("data_xml_bytes", xmlBytes(BalanceData.PREFS_DATA));
        result.putLong("preferences_xml_bytes", xmlBytes(BalanceData.PREFS_PREF));
        instrumentation.sendStatus(0, result);
        completedPairs++;
        return withinBudget();
    }

    private void verifyIncrementalState(int rows) {
        verifyInbox(rows);
        long newest = BASE + (rows - 1) * STEP;
        assertEquals("balance_watermark", newest, watermark(BalanceData.KEY_SCANNED_THROUGH));
        assertEquals("history_watermark", newest, watermark(BalanceData.KEY_HISTORY_THROUGH));
        SharedPreferences prefs = preferences(BalanceData.PREFS_PREF);
        assertEquals(BankRules.VERSION, prefs.getInt(BalanceData.KEY_RULES_VERSION, -1));
        assertEquals(BalanceData.HISTORY_RULES_VERSION,
                prefs.getInt(BalanceData.KEY_HISTORY_RULES_VERSION, -1));
        assertEquals(BalanceData.HISTORY_SCHEMA,
                prefs.getInt(BalanceData.KEY_HISTORY_SCHEMA, -1));
        assertEquals("no_arrivals", 0, count(Telephony.Sms.DATE + " > ?",
                new String[]{Long.toString(newest)}));
    }

    private void seedDataset(int rows) throws Exception {
        int copies = rows / DISTINCT;
        for (int i = 0; i < DISTINCT; i++) {
            String encoded = Base64.encodeToString(body(i).getBytes(StandardCharsets.UTF_8),
                    Base64.NO_WRAP);
            long base = BASE + i * copies * STEP;
            long start = SystemClock.elapsedRealtime();
            try {
                exec(HELPER + "SEED -e sender TejaratBank -e body64 " + encoded
                        + " -e base " + base + " -e count " + copies + " -e step " + STEP);
                awaitCount((i + 1) * copies);
            } finally {
                helperWaitMs += SystemClock.elapsedRealtime() - start;
            }
        }
        verifyInbox(rows);
    }

    private static String body(int index) {
        // Known Tejarat withdrawal shape from BalanceScanTest/HistoryScanTest, synthetic accounts.
        // No stated date: event time falls back to each row's deterministic arrival timestamp.
        return "*بانک تجارت*\nحساب: 0135123456789" + index
                + "\nبرداشت: 490,098,000 ریال\nمانده: 8,465,016 ریال";
    }

    private void verifyInbox(int rows) {
        assertEquals("inbox_rows", rows, count(null, null));
        int copies = rows / DISTINCT;
        for (int i = 0; i < DISTINCT; i++) {
            // Query only dates, using known synthetic text as selection arguments. Verify every
            // group and arrival again around scans, rather than trusting just the total count.
            try (Cursor cursor = context.getContentResolver().query(
                    Telephony.Sms.Inbox.CONTENT_URI, new String[]{Telephony.Sms.DATE},
                    Telephony.Sms.ADDRESS + " = ? AND " + Telephony.Sms.BODY + " = ?",
                    new String[]{"TejaratBank", body(i)}, Telephony.Sms.DATE + " ASC")) {
                assertNotNull("synthetic_group_cursor", cursor);
                assertEquals("synthetic_group_rows", copies, cursor.getCount());
                int copy = 0;
                while (cursor.moveToNext()) {
                    assertEquals("synthetic_arrival", BASE + (i * copies + copy) * STEP,
                            cursor.getLong(0));
                    copy++;
                }
            }
        }
    }

    private int count(String selection, String[] args) {
        try (Cursor cursor = context.getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI,
                new String[]{Telephony.Sms._ID}, selection, args, null)) {
            assertNotNull("sms_provider_cursor", cursor);
            return cursor.getCount();
        }
    }

    private void awaitCount(int expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 45_000L;
        do {
            if (count(null, null) == expected) return;
            Thread.sleep(150);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("sms_provider_count_timeout");
    }

    private void clearInbox() throws Exception {
        long start = SystemClock.elapsedRealtime();
        try {
            exec(HELPER + "CLEAR");
            awaitCount(0);
        } finally {
            helperWaitMs += SystemClock.elapsedRealtime() - start;
        }
    }

    private void exec(String command) throws Exception {
        ParcelFileDescriptor descriptor = automation.executeShellCommand(command);
        try (InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[2048];
            while (stream.read(buffer) != -1) { /* Drain without exposing shell output. */ }
        }
    }

    private SharedPreferences preferences(String name) {
        return context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    private long watermark(String key) {
        return preferences(BalanceData.PREFS_PREF).getLong(key, 0L);
    }

    private void resetPreferences() {
        boolean cleared = true;
        for (String name : STORES) {
            cleared &= preferences(name).edit().clear().commit();
        }
        assertTrue("reset_isolated_preferences", cleared);
    }

    private long xmlBytes(String name) {
        File xml = new File(new File(context.getApplicationInfo().dataDir, "shared_prefs"),
                PREFIX + name + ".xml");
        assertTrue("isolated_xml_exists", xml.isFile());
        return xml.length();
    }

    private boolean withinBudget() {
        long workMs = SystemClock.elapsedRealtime() - started - helperWaitMs;
        if (workMs < BUDGET_MS) return true;
        Bundle result = new Bundle();
        result.putString("scan_experiment", "incomplete_budget");
        result.putLong("work_ms", workMs);
        result.putInt("completed_pairs", completedPairs);
        instrumentation.sendStatus(0, result);
        return false;
    }
}
