package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tests for the scan diagnostics classifier: splitting an SMS-inbox snapshot into (a) content that
 * parsed into balances per bank, (b) senders of a known bank whose message layout did not parse, and
 * (c) wholly unknown senders — with counts, newest samples, and selection-aware report text.
 */
@RunWith(AndroidJUnit4.class)
public class ScanDiagnosticsTest {

    // Recognized senders: bank rules resolve these (see BankRulesTest).
    private static final String TEJARAT = "5000973189";
    private static final String SAMAN = "500095";

    private static List<Object[]> rows(Object[]... in) {
        List<Object[]> list = new ArrayList<>();
        for (Object[] r : in) list.add(r);
        return list;
    }

    // ---- analyze: counts and buckets ----

    @Test public void analyze_mixed_bucketsMessages() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{TEJARAT, "balance 1000", 1000L},
            new Object[]{SAMAN, "balance 2000", 2000L},
            new Object[]{TEJARAT, "payment bill done", 3000L},
            new Object[]{"+98unknown1", "otp 123456", 4000L}));
        assertEquals(4, s.messages);
        assertEquals(2, s.parsedMessages);
        assertEquals(2, s.unparsedMessages());
        // The known-bank message with unparsed layout is a format gap too.
        assertEquals(1, s.unparsedSendersMessages);
        assertEquals(1, s.unknownSendersMessages);
    }

    @Test public void analyze_allParsed_noReportableSenders() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{TEJARAT, "balance 1000", 1000L},
            new Object[]{SAMAN, "balance 2000", 2000L}));
        assertEquals(0, s.unparsedMessages());
        assertTrue(s.unknownSenders.isEmpty());
        assertTrue(s.unparsedSenders.isEmpty());
        assertEquals(2, s.banks.size());
    }

    @Test public void analyze_allUnrecognized_noBanks() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{"+98unknown1", "hi", 1000L},
            new Object[]{"+98unknown2", "hi", 2000L}));
        assertEquals(2, s.unparsedMessages());
        assertTrue(s.banks.isEmpty());
        assertEquals(2, s.unknownSenders.size());
        assertTrue(s.unparsedSenders.isEmpty());
    }

    @Test public void analyze_knownBankSenderWithUnparsedContent_flaggedSeparately() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{TEJARAT, "some message without a balance word", 1000L}));
        assertEquals(0, s.parsedMessages);
        assertEquals(1, s.unparsedSenders.size());
        ScanDiagnostics.SenderHit h = s.unparsedSenders.get(0);
        assertEquals(TEJARAT, h.sender);
        assertEquals("Tejarat", h.bank);
        assertTrue(s.unknownSenders.isEmpty());
    }

    @Test public void analyze_emptyInbox_zeroCounts() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows());
        assertEquals(0, s.messages);
        assertEquals(0, s.parsedMessages);
        assertEquals(0, s.unparsedMessages());
        assertTrue(s.banks.isEmpty());
        assertTrue(s.unknownSenders.isEmpty());
        assertTrue(s.unparsedSenders.isEmpty());
    }

    // ---- analyze: segmentation and ordering ----

    @Test public void analyze_groupsBanksByResolvedName_sortedByCount() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{TEJARAT, "balance 1000", 1000L},
            new Object[]{SAMAN, "balance 2000", 2000L},
            new Object[]{TEJARAT, "balance 3000", 3000L}));
        // Tejarat twice, Saman once -> Tejarat first.
        assertEquals(2, s.banks.size());
        ScanDiagnostics.BankHit top = s.banks.get(0);
        assertEquals("Tejarat", top.bank);
        assertEquals(2, top.messages);
    }

    @Test public void analyze_skippedSenders_sortedByCountDescending() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{"+98rare", "one", 1000L},
            new Object[]{"+98frequent", "a", 2000L},
            new Object[]{"+98frequent", "b", 2100L},
            new Object[]{"+98frequent", "c", 2200L}));
        assertEquals(2, s.unknownSenders.size());
        assertEquals("+98frequent", s.unknownSenders.get(0).sender);
        assertEquals(3, s.unknownSenders.get(0).messages);
        assertEquals("+98rare", s.unknownSenders.get(1).sender);
        assertEquals(1, s.unknownSenders.get(1).messages);
    }

    @Test public void problemSenders_knownBankSendersFirst_thenUnknown() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{TEJARAT, "unparsed layout here", 1000L},
            new Object[]{"+98unknown", "hi", 2000L}));
        List<ScanDiagnostics.SenderHit> all = ScanDiagnostics.problemSenders(s);
        assertEquals(2, all.size());
        assertEquals("Tejarat", all.get(0).bank);
        assertNull(all.get(1).bank);
    }

    // ---- reportText / senderReport / stored samples ----

    @Test public void reportText_selection_includesSenderCountAndNewestSample() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{"+98unknown", "maskan 12,000 Rial", 1000L}));
        String txt = ScanDiagnostics.reportText(ScanDiagnostics.problemSenders(s));
        assertNotNull(txt);
        assertTrue(txt.contains("+98unknown"));
        assertTrue(txt.contains("maskan 12,000 Rial"));
        assertTrue(txt.contains("1 message"));
    }

    @Test public void reportText_knownBankSender_namesTheBank() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{TEJARAT, "bill paid", 1000L}));
        String txt = ScanDiagnostics.reportText(ScanDiagnostics.problemSenders(s));
        assertTrue(txt.contains("Tejarat"));
        assertTrue(txt.contains(TEJARAT));
    }

    @Test public void reportText_emptySelection_headersOnlyNoNull() {
        String txt = ScanDiagnostics.reportText(new ArrayList<>());
        assertNotNull(txt);
        assertTrue(txt.contains("## Bank SMS formats Balance could not parse"));
    }

    @Test public void reportText_embedsDeviceModelAndAndroidVersion() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{"+98x", "a 1000", 1000L}));
        String txt = ScanDiagnostics.reportText(s.unknownSenders);
        assertTrue(txt.contains("Device:"));
        assertTrue(txt.contains(android.os.Build.MANUFACTURER));
        assertTrue(txt.contains(android.os.Build.MODEL));
        assertTrue(txt.contains("Android " + android.os.Build.VERSION.RELEASE));
        assertTrue(txt.contains(String.valueOf(android.os.Build.VERSION.SDK_INT)));
        if (!android.os.Build.DISPLAY.trim().isEmpty())
            assertTrue(txt.contains(android.os.Build.DISPLAY.trim()));
    }

    @Test public void reportText_embedsRomLineWhenFirmwareIsKnown() {
        // The report must carry a ROM line exactly when the device exposes a readable ro.* prop:
        // read one back through the same reflection the app uses so the test cannot drift.
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{"+98x", "a 1000", 1000L}));
        String txt = ScanDiagnostics.reportText(s.unknownSenders);
        String knownProp = firstNonEmptyRomProp();
        if (knownProp != null) {
            assertTrue("a recognized ROM must be named: " + txt, txt.contains("ROM: "));
        }
    }

    private static String firstNonEmptyRomProp() {
        String[] props = {
            "ro.mi.os.version.name", "ro.miui.ui.version.name",
            "ro.build.version.emui", "ro.build.version.oneui",
            "ro.vendor.build.version.sem_oneui", "ro.oxygen.version",
            "ro.build.version.oplusrom", "ro.build.version.coloros",
            "ro.build.version.realmeui", "ro.xos.version",
            "ro.hios.version", "ro.vivo.os.build.display.id",
            "ro.lineage.version", "ro.crDroid.version",
            "ro.evolution.version", "ro.havoc.version",
            "ro.dotos.version", "ro.modversion",
        };
        for (String key : props) {
            String v = readSysProp(key);
            if (v != null && !v.trim().isEmpty()) return key;
        }
        return null;
    }

    // Mirrors ScanDiagnostics.sysProp (the report gets its ROM info through the same path).
    private static String readSysProp(String key) {
        try {
            java.lang.reflect.Method get = Class.forName("android.os.SystemProperties")
                .getMethod("get", String.class);
            return (String) get.invoke(null, key);
        } catch (Throwable t) {
            return null;
        }
    }

    @Test public void reportText_knownBankAndUnknown_union() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{TEJARAT, "layout that failed", 1000L},
            new Object[]{"+98x", "unknown one", 2000L}));
        // Pick only the unknown sender -> the known-bank entry must not leak in.
        String txt = ScanDiagnostics.reportText(s.unknownSenders);
        assertTrue(txt.contains("+98x"));
        assertTrue(!txt.contains(TEJARAT));
    }

    @Test public void reportText_pluralMessages() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{"+98x", "a", 1000L},
            new Object[]{"+98x", "b", 2000L}));
        assertTrue(ScanDiagnostics.reportText(s.unknownSenders).contains("2 messages"));
    }

    @Test public void senderReport_marksThePickedIssueTypes() {
        List<ScanDiagnostics.Message> sel = new ArrayList<>();
        sel.add(new ScanDiagnostics.Message("lay out 1,000 Toman", 1000L));
        String txt = ScanDiagnostics.senderReport("+98Saman", 3, sel,
            java.util.Arrays.asList(ScanDiagnostics.ISSUE_ACCOUNT, ScanDiagnostics.ISSUE_NUMBER));
        assertTrue(txt.contains("Issue type(s): Account detection, Sender number detection"));
        assertTrue(txt.contains("+98Saman"));
        assertTrue(txt.contains("3 message"));
        assertTrue(!txt.contains("Balance detection"));
    }

    @Test public void senderReport_noIssues_omitsTheLine() {
        List<ScanDiagnostics.Message> sel = new ArrayList<>();
        sel.add(new ScanDiagnostics.Message("lay out 1,000 Toman", 1000L));
        String txt = ScanDiagnostics.senderReport("+98Saman", 1, sel);
        assertTrue(!txt.contains("Issue type(s)"));
        assertTrue(txt.contains("1 message"));
    }

    @Test public void storedMessages_newestFirst_andCapped() {
        // analyze() preserves the inbox stream, which the screen reads newest-first.
        List<Object[]> r = new ArrayList<>();
        for (int i = ScanDiagnostics.MAX_SAMPLES_PER_SENDER + 4; i >= 0; i--)
            r.add(new Object[]{"+98bulk", "msg " + i, 1000L + i});
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(r);
        ScanDiagnostics.SenderHit h = s.unknownSenders.get(0);
        assertEquals(ScanDiagnostics.MAX_SAMPLES_PER_SENDER + 5, h.messages);
        assertEquals(ScanDiagnostics.MAX_SAMPLES_PER_SENDER, h.stored.size());
        assertEquals("msg " + (ScanDiagnostics.MAX_SAMPLES_PER_SENDER + 4), h.stored.get(0).body);
    }

    @Test public void storedMessages_keepsNewestMessageAsTheSampleForReport() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{"+98x", "newest", 2000L},
            new Object[]{"+98x", "older", 1000L}));
        ScanDiagnostics.SenderHit h = s.unknownSenders.get(0);
        assertEquals(2, h.stored.size());
        assertEquals("newest", h.stored.get(0).body);
        assertEquals("older", h.stored.get(1).body);
        assertTrue(ScanDiagnostics.reportText(s.unknownSenders).contains("newest"));
    }

    @Test public void nullBodyOrSender_tolerated() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{TEJARAT, null, 1000L},
            new Object[]{null, null, 2000L}));
        assertEquals(0, s.parsedMessages);
        assertEquals(2, s.unparsedMessages());
        assertEquals(1, s.unparsedSenders.size());
        assertEquals(1, s.unknownSenders.size());
        assertTrue(ScanDiagnostics.problemSenders(s).get(0).stored.get(0).body != null);
    }

    // ---- senderReport: the per-sender chooser text ----

    @Test public void senderReport_selectedSubset_containsOnlyThoseMessages() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{"+98x", "first msg", 1000L},
            new Object[]{"+98x", "second msg", 2000L},
            new Object[]{"+98x", "third msg", 3000L}));
        ScanDiagnostics.SenderHit h = s.unknownSenders.get(0);
        List<ScanDiagnostics.Message> chosen = new ArrayList<>();
        chosen.add(h.stored.get(1)); // only "second msg"
        String txt = ScanDiagnostics.senderReport(h.sender, h.messages, chosen);
        assertTrue(txt.contains("+98x"));
        assertTrue(txt.contains("second msg"));
        assertTrue(!txt.contains("first msg"));
        assertTrue(!txt.contains("third msg"));
        assertTrue(txt.contains("3 messages in total"));
    }

    @Test public void senderReport_emptySelection_hasHeaderNoMessages() {
        String txt = ScanDiagnostics.senderReport("+98x", 2, new ArrayList<>());
        assertTrue(txt.contains("+98x"));
        assertTrue(txt.contains("2 messages in total"));
    }

    @Test public void senderReport_embedsDeviceModelAndAndroidVersion() {
        List<ScanDiagnostics.Message> sel = new ArrayList<>();
        sel.add(new ScanDiagnostics.Message("lay out 1,000 Toman", 0L));
        String txt = ScanDiagnostics.senderReport("+98Saman", 1, sel);
        assertTrue(txt.contains(android.os.Build.MANUFACTURER));
        assertTrue(txt.contains(android.os.Build.MODEL));
        assertTrue(txt.contains("Android " + android.os.Build.VERSION.RELEASE));
    }

    @Test public void senderSubject_namesTheSender() {
        assertTrue(ScanDiagnostics.senderSubject("+98x").contains("+98x"));
    }

    // ---- Screen-level smoke tests: the activity reads the real inbox and renders ----
    // The scan tests seed and clear the real SMS table through the smsinject helper app, exactly
    // like HistoryScanTest does, so the screen is verified against a deterministic inbox.

    private static final String UNKNOWN_1 = "ADBBANK";
    private static final String UNKNOWN_2 = "+9821OTP";

    private android.content.Context ctx;

    @Before public void setUpScreen() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(android.Manifest.permission.READ_SMS);
        exec("pm grant " + ctx.getPackageName() + " android.permission.READ_SMS");
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, android.content.Context.MODE_PRIVATE).edit().clear().commit();
        ctx.getSharedPreferences(BalanceData.PREFS_DATA, android.content.Context.MODE_PRIVATE).edit().clear().commit();
        clearInbox();
    }

    @After public void tearDownScreen() throws Exception {
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

    /** Starts the diagnostics screen and returns it, so the test can inspect its rendered views. */
    private ScanDiagnosticsActivity launch() {
        android.content.Intent i = new android.content.Intent(ctx, ScanDiagnosticsActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        return (ScanDiagnosticsActivity) InstrumentationRegistry.getInstrumentation()
                .startActivitySync(i);
    }

    /** All text rendered anywhere in the activity's window, joined by newlines. */
    private String screenText(android.app.Activity act) {
        final List<String> out = new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            collectTexts(act.getWindow().getDecorView(), out);
        });
        StringBuilder sb = new StringBuilder();
        for (String t : out) { sb.append(t).append('\n'); }
        return sb.toString();
    }

    private void collectTexts(android.view.View v, List<String> out) {
        if (v instanceof android.widget.TextView) out.add(((android.widget.TextView) v).getText().toString());
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

    @Test public void screen_emptyInbox_showsNothingToReport() throws Exception {
        ScanDiagnosticsActivity act = launch();
        try {
            String all = waitFor(act, ctx.getString(R.string.scan_diag_none_skipped));
            assertTrue(all.contains(ctx.getString(R.string.scan_diag_title)));
            assertTrue(all.contains(ctx.getString(R.string.scan_diag_none_skipped)));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(act::finish);
        }
    }

    @Test public void screen_seededMix_separatesRecognizedKnownBankAndUnknownSenders() throws Exception {
        seed(TEJARAT, "موجودی شما: 1,250,000 تومان", 1_710_000_000_000L);
        seed(TEJARAT, "پرداخت قبض انجام شد", 1_710_000_001_000L);
        seed(UNKNOWN_1, "card purchase 45,000 T", 1_710_000_002_000L);
        seed(UNKNOWN_1, "transfer 10,500 T", 1_710_000_003_000L);
        seed(UNKNOWN_2, "otp 123456", 1_710_000_004_000L);
        ScanDiagnosticsActivity act = launch();
        try {
            String all = waitFor(act, ctx.getString(R.string.scan_diag_unparsed_banks_title));
            assertTrue(all.contains(ctx.getString(R.string.scan_diag_recognized)));
            assertTrue(all.contains(ctx.getString(R.string.scan_diag_recognized_banks)));
            assertTrue(all.contains(ctx.getString(R.string.scan_diag_unparsed_banks_title)));
            assertTrue(all.contains(ctx.getString(R.string.scan_diag_skipped_senders)));
            assertTrue(all.contains("Tejarat")); // the parses card and the flagged bank line
            assertTrue(all.contains("پرداخت قبض انجام شد")); // the known-bank message that failed
            assertTrue(all.contains(UNKNOWN_1));
            assertTrue(all.contains(UNKNOWN_2));
            // Selection is not on this screen: it happens inside each sender's messages, so there
            // must be no checkboxes or whole-report buttons here.
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                List<android.widget.CheckBox> checks = new ArrayList<>();
                findChecks(act.getWindow().getDecorView(), checks);
                assertTrue("the diagnostics screen must not carry its own checkboxes", checks.isEmpty());
            });
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(act::finish);
        }
    }

    /** Launches the per-sender chooser with two sample messages and checks it renders both with the
     *  multi-selection controls, nothing preselected and counters at zero, without sending anything. */
    @Test public void senderScreen_listsMessagesAndOffersSelectionButDoesNotSend() throws Exception {
        android.content.Intent i = new android.content.Intent(ctx, SenderShareActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SenderShareActivity.EXTRA_SENDER, UNKNOWN_1)
                .putStringArrayListExtra(SenderShareActivity.EXTRA_MESSAGES,
                    new ArrayList<>(Arrays.asList("sample one", "sample two")));
        SenderShareActivity act = (SenderShareActivity) InstrumentationRegistry.getInstrumentation()
                .startActivitySync(i);
        try {
            String all = waitFor(act, ctx.getString(R.string.sender_share_send, 0));
            assertTrue(all.contains(UNKNOWN_1));
            assertTrue(all.contains("sample one"));
            assertTrue(all.contains("sample two"));
            assertTrue(all.contains(ctx.getString(R.string.sender_share_select_all)));
            assertTrue(all.contains(ctx.getString(R.string.sender_share_copy, 0)));
            assertTrue(all.contains(ctx.getString(R.string.sender_share_send, 0)));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                List<android.widget.CheckBox> checks = new ArrayList<>();
                findChecks(act.getWindow().getDecorView(), checks);
                for (android.widget.CheckBox c : checks) {
                    assertTrue("no message should be preselected", !c.isChecked());
                }
            });
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(act::finish);
        }
    }

    private void findChecks(android.view.View v, List<android.widget.CheckBox> out) {
        if (v instanceof android.widget.CheckBox) out.add((android.widget.CheckBox) v);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) findChecks(g.getChildAt(i), out);
        }
    }
}