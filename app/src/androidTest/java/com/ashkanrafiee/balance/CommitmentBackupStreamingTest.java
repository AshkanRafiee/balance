package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

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
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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

    @Test public void framedRestoreDoesNotReinterpretScheduleInDestinationCalendarOrZone()
            throws Exception {
        TimeZone originalZone = TimeZone.getDefault();
        int originalRegion = RegionHelper.region(context);
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tehran"));
            RegionHelper.setRegion(context, RegionHelper.REGION_IRAN);
            long first = Commitment.millisOf(1405, 6, 31, CalendarSystem.JALALI);
            long paid = Commitment.millisOf(1405, 7, 30, CalendarSystem.JALALI);
            Commitment c = Commitment.create("Loan", -123L, Commitment.MONTHLY, first, null, true, 0);
            CommitmentStore.upsert(context, c);
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
            org.junit.Assert.assertTrue(restored.hasOccurrence(paid));
            org.junit.Assert.assertTrue(restored.isSettled(paid));
            assertEquals(Collections.singletonList(paid), restored.paid);
        } finally {
            TimeZone.setDefault(originalZone);
            RegionHelper.setRegion(context, originalRegion);
        }
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
