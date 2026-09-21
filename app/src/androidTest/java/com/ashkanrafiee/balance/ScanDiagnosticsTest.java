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
import java.util.List;

/**
 * Tests for the scan diagnostics classifier: splitting an SMS-inbox snapshot into recognized banks
 * and unrecognized senders (counts, newest sample), and assembling the contributor report that
 * feeds the CONTRIBUTING.md issue flow.
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

    // ---- analyze: counts ----

    @Test public void analyze_mixedSenders_countsRecognizedMessages() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{TEJARAT, "balance 1000", 1000L},
            new Object[]{SAMAN, "balance 2000", 2000L},
            new Object[]{"+98unknown1", "otp 123456", 3000L}));
        assertEquals(3, s.messages);
        assertEquals(2, s.recognizedMessages);
        assertEquals(1, s.skippedMessages);
    }

    @Test public void analyze_allRecognized_noSkippedSenders() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{TEJARAT, "balance 1000", 1000L},
            new Object[]{SAMAN, "balance 2000", 2000L}));
        assertEquals(0, s.skippedMessages);
        assertTrue(s.senders.isEmpty());
        assertEquals(2, s.banks.size());
    }

    @Test public void analyze_allSkipped_noBanks() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{"+98unknown1", "hi", 1000L},
            new Object[]{"+98unknown2", "hi", 2000L}));
        assertEquals(2, s.skippedMessages);
        assertTrue(s.banks.isEmpty());
        assertEquals(2, s.senders.size());
    }

    @Test public void analyze_emptyInbox_zeroCounts() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows());
        assertEquals(0, s.messages);
        assertEquals(0, s.recognizedMessages);
        assertEquals(0, s.skippedMessages);
        assertTrue(s.banks.isEmpty());
        assertTrue(s.senders.isEmpty());
    }

    // ---- analyze: segmentation and ordering ----

    @Test public void analyze_groupsBanksByResolvedName_sortedByCount() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{TEJARAT, "b", 1000L},
            new Object[]{SAMAN, "b", 2000L},
            new Object[]{TEJARAT, "b", 3000L}));
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
        assertEquals(2, s.senders.size());
        assertEquals("+98frequent", s.senders.get(0).sender);
        assertEquals(3, s.senders.get(0).messages);
        assertEquals("+98rare", s.senders.get(1).sender);
        assertEquals(1, s.senders.get(1).messages);
    }

    // ---- reportText / senderReport / stored samples ----

    @Test public void reportText_includesSenderCountAndNewestSample() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{"+98unknown", "maskan 12,000 Rial", 1000L}));
        String txt = ScanDiagnostics.reportText(s);
        assertNotNull(txt);
        assertTrue(txt.contains("+98unknown"));
        assertTrue(txt.contains("maskan 12,000 Rial"));
        assertTrue(txt.contains("1 message"));
    }

    @Test public void reportText_allRecognized_returnsNull() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{TEJARAT, "balance 1000", 1000L}));
        assertNull(ScanDiagnostics.reportText(s));
    }

    @Test public void reportText_emptySummary_returnsNull() {
        assertNull(ScanDiagnostics.reportText(ScanDiagnostics.analyze(rows())));
    }

    @Test public void reportText_pluralMessages() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{"+98x", "a", 1000L},
            new Object[]{"+98x", "b", 2000L}));
        assertTrue(ScanDiagnostics.reportText(s).contains("2 messages"));
    }

    @Test public void storedMessages_newestFirst_andCapped() {
        // analyze() preserves the inbox stream, which the screen reads newest-first.
        List<Object[]> r = new ArrayList<>();
        for (int i = ScanDiagnostics.MAX_SAMPLES_PER_SENDER + 4; i >= 0; i--)
            r.add(new Object[]{"+98bulk", "msg " + i, 1000L + i});
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(r);
        ScanDiagnostics.SenderHit h = s.senders.get(0);
        assertEquals(ScanDiagnostics.MAX_SAMPLES_PER_SENDER + 5, h.messages);
        assertEquals(ScanDiagnostics.MAX_SAMPLES_PER_SENDER, h.stored.size());
        assertEquals("msg " + (ScanDiagnostics.MAX_SAMPLES_PER_SENDER + 4), h.stored.get(0).body);
    }

    @Test public void storedMessages_keepsNewestMessageAsTheSampleForReport() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{"+98x", "newest", 2000L},
            new Object[]{"+98x", "older", 1000L}));
        ScanDiagnostics.SenderHit h = s.senders.get(0);
        assertEquals(2, h.stored.size());
        assertEquals("newest", h.stored.get(0).body);
        assertEquals("older", h.stored.get(1).body);
        assertTrue(ScanDiagnostics.reportText(s).contains("newest"));
    }

    @Test public void nullBodyOrSender_tolerated() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{TEJARAT, null, 1000L},
            new Object[]{null, null, 2000L}));
        assertEquals(1, s.recognizedMessages);
        assertEquals(1, s.skippedMessages);
        assertEquals("", s.senders.get(0).stored.get(0).body);
    }

    // ---- senderReport: the per-sender chooser text ----

    @Test public void senderReport_selectedSubset_containsOnlyThoseMessages() {
        ScanDiagnostics.Summary s = ScanDiagnostics.analyze(rows(
            new Object[]{"+98x", "first msg", 1000L},
            new Object[]{"+98x", "second msg", 2000L},
            new Object[]{"+98x", "third msg", 3000L}));
        ScanDiagnostics.SenderHit h = s.senders.get(0);
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

    @Test public void screen_seededMix_countsRecognizedAndListsSkippedSenders() throws Exception {
        seed(TEJARAT, "موجودی شما: 1,250,000 تومان", 1_710_000_000_000L);
        seed(UNKNOWN_1, "card purchase 45,000 T", 1_710_000_001_000L);
        seed(UNKNOWN_1, "transfer 10,500 T", 1_710_000_002_000L);
        seed(UNKNOWN_2, "otp 123456", 1_710_000_003_000L);
        ScanDiagnosticsActivity act = launch();
        try {
            String all = waitFor(act, ctx.getString(R.string.scan_diag_skipped_senders));
            assertTrue(all.contains(ctx.getString(R.string.scan_diag_recognized)));
            assertTrue(all.contains("Tejarat"));
            assertTrue(all.contains(UNKNOWN_1));
            assertTrue(all.contains(UNKNOWN_2));
            assertTrue(all.contains(ctx.getString(R.string.scan_diag_copy_report)));
            assertTrue(all.contains(ctx.getString(R.string.scan_diag_email_report)));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(act::finish);
        }
    }

    /** Launches the per-sender chooser with two sample messages and checks it renders both with the
     *  multi-selection controls, without sending anything. */
    @Test public void senderScreen_listsMessagesAndOffersSelectionButDoesNotSend() throws Exception {
        android.content.Intent i = new android.content.Intent(ctx, SenderShareActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SenderShareActivity.EXTRA_SENDER, UNKNOWN_1)
                .putStringArrayListExtra(SenderShareActivity.EXTRA_MESSAGES,
                    new ArrayList<>(java.util.Arrays.asList("sample one", "sample two")));
        SenderShareActivity act = (SenderShareActivity) InstrumentationRegistry.getInstrumentation()
                .startActivitySync(i);
        try {
            String all = waitFor(act, ctx.getString(R.string.sender_share_send));
            assertTrue(all.contains(UNKNOWN_1));
            assertTrue(all.contains("sample one"));
            assertTrue(all.contains("sample two"));
            assertTrue(all.contains(ctx.getString(R.string.sender_share_select_none)));
            assertTrue(all.contains(ctx.getString(R.string.sender_share_copy)));
            assertTrue(all.contains(ctx.getString(R.string.sender_share_send)));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(act::finish);
        }
    }
}