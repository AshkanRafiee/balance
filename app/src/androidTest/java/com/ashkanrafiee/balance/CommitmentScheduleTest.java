package com.ashkanrafiee.balance;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Legacy timestamps are interpreted once, not guessed from an unknowable creation setting. */
@RunWith(AndroidJUnit4.class)
public class CommitmentScheduleTest {
    private Context context;
    private TimeZone originalZone;
    private int originalRegion;

    @Before public void before() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalZone = TimeZone.getDefault();
        originalRegion = RegionHelper.region(context);
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        RegionHelper.setRegion(context, RegionHelper.REGION_INTERNATIONAL);
        CommitmentStore.clear(context);
    }

    @After public void after() throws Exception {
        CommitmentStore.clear(context);
        RegionHelper.setRegion(context, originalRegion);
        TimeZone.setDefault(originalZone);
    }

    private static long day(int year, int month, int day, String zone) {
        return LocalDate.of(year, month, day).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli();
    }

    private static Commitment monthly(String zone, CalendarSystem calendar) {
        long first = day(2026, 1, 31, zone);
        return Commitment.create("Rent", -123L, Commitment.MONTHLY, first, null, true, 0)
            .freezeSchedule(calendar, ZoneId.of(zone));
    }

    @Test public void calendarPreferenceNeverReinterpretsFrozenMonthlySchedule() {
        Commitment c = monthly("UTC", CalendarSystem.GREGORIAN);
        long from = day(2026, 1, 1, "UTC"), to = day(2026, 4, 30, "UTC");
        List<Long> expected = Arrays.asList(day(2026, 1, 31, "UTC"), day(2026, 2, 28, "UTC"),
            day(2026, 3, 31, "UTC"), day(2026, 4, 30, "UTC"));
        assertEquals(expected, Commitment.occurrences(c, CalendarSystem.GREGORIAN, from, to));
        assertEquals(expected, Commitment.occurrences(c, CalendarSystem.JALALI, from, to));
        Commitment finite = Commitment.create("Rent", -123, Commitment.MONTHLY, c.start, to,
            false, 0).freezeSchedule(CalendarSystem.GREGORIAN, ZoneId.of("UTC"));
        assertEquals(Long.valueOf(-492), Commitment.totalAmount(finite, CalendarSystem.JALALI));
    }

    @Test public void deviceZoneChangesDoNotMoveIdentitiesOrPaidState() throws Exception {
        Commitment c = monthly("America/New_York", CalendarSystem.GREGORIAN);
        long paid = day(2026, 3, 31, "America/New_York");
        c = c.withSettlements(false, Collections.singletonList(paid), Collections.emptyList());
        CommitmentStore.upsert(context, c);
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
        RegionHelper.setRegion(context, RegionHelper.REGION_IRAN);
        Commitment restored = CommitmentStore.get(context, c.id);
        assertEquals("America/New_York", restored.schedule.zone.getId());
        assertEquals(CalendarSystem.GREGORIAN, restored.schedule.calendar);
        assertTrue(restored.hasOccurrence(paid));
        assertTrue(restored.isSettled(paid));
        assertFalse(restored.isSettled(day(2026, 2, 28, "America/New_York")));
        assertEquals(LocalDate.of(2026, 1, 31), restored.schedule.first);
    }

    @Test public void dailyScheduleCrossesDstAsCivilDaysWithoutDuplicateIdentity() {
        String zone = "America/New_York";
        long start = day(2026, 3, 7, zone);
        Commitment c = Commitment.create("Daily", 1, Commitment.DAILY, start, null, true, 0)
            .freezeSchedule(CalendarSystem.GREGORIAN, ZoneId.of(zone));
        java.util.ArrayList<Long> dues = new java.util.ArrayList<>();
        c.schedule.visit(c.frequency, start, day(2026, 3, 10, zone), dues::add, -1);
        assertEquals(4, dues.size());
        assertEquals(23L * 3600000L, dues.get(2) - dues.get(1));
        for (long due : dues) assertTrue(c.hasOccurrence(due));
    }

    @Test public void jalaliScheduleClampsAndCountUsesItsOwnCalendarAndZone() {
        ZoneId zone = ZoneId.of("Asia/Tehran");
        long start = Commitment.millisOf(1405, 6, 31, CalendarSystem.JALALI, zone);
        Commitment c = Commitment.create("Loan", -1, Commitment.MONTHLY, start, null, false, 0)
            .freezeSchedule(CalendarSystem.JALALI, zone);
        long expected = Commitment.millisOf(1405, 7, 30, CalendarSystem.JALALI, zone);
        assertEquals(expected, c.schedule.instant(c.schedule.at(1, c.frequency)));
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
        assertEquals(Long.valueOf(expected), Commitment.endForCount(start, 2, c.frequency,
            c.schedule.calendar, c.schedule.zone));
    }

    @Test public void legacyMigrationKeepsCivilPaidUnpaidDaysAndWatermark() throws Exception {
        long first = day(2026, 1, 31, "UTC"), second = day(2026, 2, 28, "UTC");
        // Old records have no original calendar or zone. Non-midnight imported marks still
        // name their current civil day; migration must not reopen that paid installment.
        Commitment legacy = new Commitment("legacy", "Loan", -7, Commitment.MONTHLY, first,
            null, false, Collections.singletonList(first + 3600000), second,
            Collections.singletonList(second + 3600000), true, 0);
        String payload = CommitmentStore.serialize(Collections.singletonList(legacy));
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .putString(BalanceData.KEY_COMMITMENTS, payload).commit();
        Commitment migrated = CommitmentStore.get(context, legacy.id);
        assertNotNull(migrated.schedule);
        assertEquals(Collections.singletonList(first), migrated.paid);
        assertEquals(Collections.singletonList(second), migrated.unpaid);
        assertTrue(migrated.isSettled(first));
        assertFalse(migrated.isSettled(second));
        assertEquals(second, migrated.legacyPaidThrough);
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
        RegionHelper.setRegion(context, RegionHelper.REGION_IRAN);
        migrated = CommitmentStore.get(context, legacy.id);
        assertTrue(migrated.hasOccurrence(second));
        assertTrue(migrated.isSettled(first));
        assertFalse(migrated.isSettled(second));
        assertEquals("UTC", migrated.schedule.zone.getId());
        assertEquals(CalendarSystem.GREGORIAN, migrated.schedule.calendar);
    }

    @Test public void conflictingLegacyCivilDaysFailWithoutDeletingSource() throws Exception {
        long first = day(2026, 1, 31, "UTC");
        Commitment legacy = new Commitment("conflict", "Loan", -7, Commitment.MONTHLY, first,
            null, false, Collections.singletonList(first), 0,
            Collections.singletonList(first + 3600000), false, 0);
        String payload = CommitmentStore.serialize(Collections.singletonList(legacy));
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .putString(BalanceData.KEY_COMMITMENTS, payload).commit();
        try {
            CommitmentStore.read(context);
            fail("conflicting civil states must not silently overwrite each other");
        } catch (IllegalStateException expected) { }
        assertEquals(payload, context.getSharedPreferences(BalanceData.PREFS_DATA,
            Context.MODE_PRIVATE).getString(BalanceData.KEY_COMMITMENTS, null));
    }

    @Test public void newJsonRetainsZoneCivilDatesAndCalendarAcrossRestore() throws Exception {
        Commitment c = monthly("Asia/Tehran", CalendarSystem.JALALI);
        JSONObject json = c.toJson();
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
        Commitment restored = Commitment.fromJson(json);
        assertNotNull(restored);
        assertEquals(c.schedule.toJson().toString(), restored.schedule.toJson().toString());
        assertEquals(c.schedule.instant(c.schedule.at(1, c.frequency)),
            restored.schedule.instant(restored.schedule.at(1, restored.frequency)));
        json.getJSONObject("schedule").put("zone", "not/a/zone");
        assertNull("invalid optional metadata must not fall back to legacy migration",
            Commitment.fromJson(json));
        json = c.toJson();
        json.getJSONObject("schedule").put("version", 2);
        assertNull(Commitment.fromJson(json));
    }
}
