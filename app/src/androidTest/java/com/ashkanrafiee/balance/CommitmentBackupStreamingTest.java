package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.JsonWriter;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/** Framed commitment export reuses one session while streaming both settlement kinds. */
@RunWith(AndroidJUnit4.class)
public class CommitmentBackupStreamingTest {
    private static final long T = 1_700_000_000_000L;
    private static final long DAY = 86_400_000L;
    private static final String PASSWORD = "commitment streaming test password";

    private Context context;
    private File backup;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DataGeneration.clearForTests(context);
        BalanceData.reset(context, true);
    }

    @After public void tearDown() {
        BalanceData.reset(context, true);
        DataGeneration.clearForTests(context);
        if (backup != null) backup.delete();
    }

    @Test public void recordExportUsesOneStoreAccessForMultipleSettlementPages() throws Exception {
        Commitment source = largeDefinition();
        Commitment empty = new Commitment("empty", "Empty history", 1L, Commitment.DAILY,
            T, null, false, Collections.emptyList(), false, 0);
        BalanceData.writeCommitments(context, Arrays.asList(source, empty));
        CountingContext counted = new CountingContext(context);
        StringWriter output = new StringWriter();
        JsonWriter json = new JsonWriter(output);

        CommitmentStore.writeJsonRecords(counted, json);
        json.flush();

        // Each access() resolves these preferences once. Count that boundary without adding
        // production counters or depending on Android's SQLiteOpenHelper opening implementation.
        assertEquals("export must reuse its session for every lazy iterator read",
            1, counted.dataPreferenceReads);
        JSONArray records = new JSONArray(output.toString());
        assertEquals(2, records.length());
        JSONObject exported = records.getJSONObject(0);
        assertEquals(source.id, exported.getString("id"));
        assertDates(source.paid, exported.getJSONArray("paid"));
        assertDates(source.unpaid, exported.getJSONArray("unpaid"));
        assertEquals(empty.id, records.getJSONObject(1).getString("id"));
        assertEquals(0, records.getJSONObject(1).getJSONArray("paid").length());
        assertEquals(0, records.getJSONObject(1).getJSONArray("unpaid").length());
    }

    @Test public void framedRoundTripPreservesMultiplePaidAndUnpaidPages() throws Exception {
        Commitment source = largeDefinition();
        BalanceData.writeCommitments(context, Collections.singletonList(source));
        backup = File.createTempFile("commitment-streaming-", ".bin", context.getCacheDir());
        BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD);
        BalanceData.reset(context, true);

        BackupManager.RestoreResult result = BackupManager.restore(context,
            Uri.fromFile(backup), PASSWORD);

        assertEquals(1, result.commitmentsAdded);
        Commitment restored = CommitmentStore.get(context, source.id);
        assertNotNull(restored);
        assertEquals(source.name, restored.name);
        assertEquals(source.amount, restored.amount);
        assertEquals(source.frequency, restored.frequency);
        assertEquals(source.start, restored.start);
        assertEquals(source.legacyPaidThrough, restored.legacyPaidThrough);
        assertTrue(restored.occurrenceAmounts.isEmpty());
        List<Long> paid = new ArrayList<>();
        List<Long> unpaid = new ArrayList<>();
        // Verify every exact date using bounded pages, not thousands of independent lazy reads.
        CommitmentStore.forEachSettlement(context, source.id, 256,
            row -> (row.settled ? paid : unpaid).add(row.date));
        Collections.sort(paid);
        Collections.sort(unpaid);
        assertEquals(source.paid, paid);
        assertEquals(source.unpaid, unpaid);
    }

    @Test public void framedRestorePreservesScheduleAndAmountsAcrossCalendarAndZone()
            throws Exception {
        TimeZone originalZone = TimeZone.getDefault();
        int originalRegion = RegionHelper.region(context);
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tehran"));
            RegionHelper.setRegion(context, RegionHelper.REGION_IRAN);
            long first = Commitment.millisOf(1405, 6, 31, CalendarSystem.JALALI);
            long paid = Commitment.millisOf(1405, 7, 30, CalendarSystem.JALALI);
            Commitment c = Commitment.create("Loan", -123L, Commitment.MONTHLY, first, null,
                true, 0, CalendarSystem.JALALI, ZoneId.systemDefault());
            long beforeStart = c.schedule.instant(c.schedule.first.minusDays(1));
            Map<Long, Long> amounts = new LinkedHashMap<>();
            amounts.put(paid, Long.MIN_VALUE);
            amounts.put(first, Long.MAX_VALUE); // Dormant: opposite the current direction.
            amounts.put(beforeStart, -789L); // Dormant: outside the current schedule.
            long formerInstant = paid + 3600000L; // An old midnight after a zone-rule change.
            amounts.put(formerInstant, -456L);
            CommitmentStore.upsert(context, c.withOccurrenceAmounts(amounts));
            CommitmentStore.settle(context, c.id, paid);
            Commitment before = CommitmentStore.get(context, c.id);
            String schedule = before.schedule.toJson().toString();
            backup = File.createTempFile("commitment-zone-", ".bin", context.getCacheDir());
            BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD);
            BalanceData.reset(context, true);
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            RegionHelper.setRegion(context, RegionHelper.REGION_INTERNATIONAL);
            assertEquals(1, BackupManager.restore(context, Uri.fromFile(backup), PASSWORD)
                .commitmentsAdded);
            Commitment restored = CommitmentStore.get(context, c.id);
            assertEquals(schedule, restored.schedule.toJson().toString());
            assertTrue(restored.hasOccurrence(paid));
            assertTrue(restored.isSettled(paid));
            assertFalse(restored.hasOccurrence(beforeStart));
            assertFalse(restored.hasOccurrence(formerInstant));
            assertNull(restored.occurrenceAmountOverride(formerInstant));
            assertEquals(Collections.singletonList(paid), restored.paid);
            assertEquals(amounts, restored.occurrenceAmounts);
        } finally {
            TimeZone.setDefault(originalZone);
            RegionHelper.setRegion(context, originalRegion);
        }
    }

    @Test public void restoreKeepsLocalOccurrenceEditsAndResetWhenAdvancingLegacyWatermark()
            throws Exception {
        long first = utcMidnight();
        long resetDue = first + DAY;
        long watermark = first + 2 * DAY;
        Commitment incoming = new Commitment("shared", "Backup loan", -100L, Commitment.DAILY,
            first, null, false, Collections.emptyList(), watermark, false, 0);
        incoming = incoming.withSchedule(
            CommitmentSchedule.capture(incoming, CalendarSystem.GREGORIAN, ZoneOffset.UTC));
        Map<Long, Long> amounts = new LinkedHashMap<>();
        amounts.put(first, -125L);
        amounts.put(resetDue, -150L);
        CommitmentStore.upsert(context, incoming.withOccurrenceAmounts(amounts));
        backup = File.createTempFile("commitment-watermark-", ".bin", context.getCacheDir());
        BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD);

        Commitment local = new Commitment(incoming.id, "Local loan", -200L, Commitment.DAILY,
            first, null, false, Collections.emptyList(), false, 0).withSchedule(incoming.schedule);
        amounts.put(first, -250L);
        CommitmentStore.upsert(context, local.withOccurrenceAmounts(amounts));
        assertTrue(CommitmentStore.setOccurrenceAmount(context, local.id, resetDue, true, null));
        assertEquals(Collections.singletonMap(first, -250L),
            CommitmentStore.get(context, local.id).occurrenceAmounts);

        BackupManager.RestoreResult result = BackupManager.restore(context,
            Uri.fromFile(backup), PASSWORD);

        assertEquals(0, result.commitmentsAdded);
        Commitment restored = CommitmentStore.get(context, local.id);
        assertEquals(local.name, restored.name);
        assertEquals(local.amount, restored.amount);
        assertEquals(watermark, restored.legacyPaidThrough);
        assertEquals(Collections.singletonMap(first, -250L), restored.occurrenceAmounts);
    }

    @Test public void explicitlyRestoredCommitmentGetsIncomingOccurrenceAmount()
            throws Exception {
        long due = utcMidnight();
        Commitment incoming = Commitment.create("Monthly reimbursement", 100L, Commitment.MONTHLY,
            due, null, false, 0, CalendarSystem.GREGORIAN, ZoneOffset.UTC);
        Map<Long, Long> amounts = Collections.singletonMap(due, 125L);
        CommitmentStore.upsert(context, incoming.withOccurrenceAmounts(amounts));
        backup = File.createTempFile("commitment-deleted-", ".bin", context.getCacheDir());
        Uri uri = Uri.fromFile(backup);
        BackupManager.createFramed(context, uri, PASSWORD);
        assertTrue(CommitmentStore.delete(context, incoming.id));

        assertEquals(0, BackupManager.restore(context, uri, PASSWORD).commitmentsAdded);
        assertNull(CommitmentStore.get(context, incoming.id));

        assertEquals(1, BackupManager.restore(context, uri, PASSWORD, null,
            BackupManager.allSections(), true).commitmentsAdded);
        assertEquals(amounts, CommitmentStore.get(context, incoming.id).occurrenceAmounts);
    }

    @Test public void framedRestoreRejectsMalformedOccurrenceAmountsBeforePublishing()
            throws Exception {
        long due = utcMidnight();
        Commitment first = Commitment.create("Valid staged commitment", 1L, Commitment.ONCE, due,
            null, false, 0, CalendarSystem.GREGORIAN, ZoneOffset.UTC);
        Commitment bad = Commitment.create("Invalid staged commitment", 1L, Commitment.DAILY,
            due, null, false, 0, CalendarSystem.GREGORIAN, ZoneOffset.UTC);
        String envelope = "{\"schema\":2,\"balances\":[],\"transactions\":[],"
            + "\"metadata\":{\"notes\":[],\"reasons\":[],\"channels\":[],\"tags\":[]},"
            + "\"commitments\":[" + first.definitionJson() + ",{";
        JSONObject badDefinition = bad.definitionJson();
        badDefinition.remove("occurrenceAmounts");
        String tail = "," + badDefinition.toString().substring(1) + "]}";
        String date = Long.toString(due);
        String[][] cases = {
            {"[[0,12]]", "invalid occurrence amount"},
            {"[[" + date + ",0]]", "invalid occurrence amount"},
            {"[[" + date + "]]", "expected integer"},
            {"[[" + date + ",12,13]]", "invalid occurrence amount"},
            {"[[" + (due + 1) + ",12]]", "invalid commitment definition"},
            {"[[" + date + ",12],[" + date + ",12]]", "duplicate occurrence amount date"},
            {"[[" + date + ",\"12\"]]", "expected integer"},
            {"[[" + date + ",9223372036854775808]]", "integer out of range"},
            {"[[9223372036854775808,12]]", "integer out of range"}
        };
        backup = File.createTempFile("commitment-malformed-", ".bin", context.getCacheDir());
        for (String[] entry : cases) {
            String payload = envelope + "\"occurrenceAmounts\":" + entry[0] + tail;
            try (FileOutputStream output = new FileOutputStream(backup)) {
                BackupFrames.write(
                    plaintext -> plaintext.write(payload.getBytes(StandardCharsets.UTF_8)),
                    output, PASSWORD.toCharArray());
            }
            try {
                BackupManager.restore(context, Uri.fromFile(backup), PASSWORD);
                fail("accepted invalid occurrence amounts: " + entry[0]);
            } catch (IllegalArgumentException expected) {
                assertEquals(entry[0], entry[1], expected.getMessage());
            }
            assertNull(CommitmentStore.get(context, first.id));
            assertNull(CommitmentStore.get(context, bad.id));
        }
    }

    private static long utcMidnight() {
        return T - Math.floorMod(T, DAY);
    }

    private static Commitment largeDefinition() {
        List<Long> paid = new ArrayList<>();
        List<Long> unpaid = new ArrayList<>();
        // Both kinds cross two 256-row boundaries; their dates interleave without overlapping.
        for (int i = 0; i < 513; i++) paid.add(T + 2L * i * DAY + 1);
        for (int i = 0; i < 515; i++) unpaid.add(T + (2L * i + 1) * DAY + 2);
        Commitment c = new Commitment("multi-page", "Long settlement history", -123L,
            Commitment.DAILY, T, null, false, paid, T + 10 * DAY, unpaid, false, 0);
        // This test exercises exact streaming, not legacy civil-day migration. A stable payload
        // retains even off-schedule imported marks verbatim, without truncating its history.
        return c.withSchedule(CommitmentSchedule.capture(c, CalendarSystem.GREGORIAN,
            java.time.ZoneId.systemDefault()));
    }

    private static void assertDates(List<Long> expected, JSONArray actual) throws Exception {
        assertEquals(expected.size(), actual.length());
        for (int i = 0; i < expected.size(); i++)
            assertEquals(expected.get(i).longValue(), actual.getLong(i));
    }

    private static final class CountingContext extends ContextWrapper {
        int dataPreferenceReads;

        CountingContext(Context base) { super(base); }

        @Override public Context getApplicationContext() { return this; }

        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            if (BalanceData.PREFS_DATA.equals(name)) dataPreferenceReads++;
            return super.getSharedPreferences(name, mode);
        }
    }
}
