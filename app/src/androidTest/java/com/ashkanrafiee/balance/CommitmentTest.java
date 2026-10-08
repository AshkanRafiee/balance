package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** The commitment model, its store and its recurrence engine (pure logic plus store round trip). */
@RunWith(AndroidJUnit4.class)
public class CommitmentTest {

    private Context ctx;

    @Before public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CommitmentStore.clear(ctx);
    }

    @After public void tearDown() throws Exception {
        CommitmentStore.clear(ctx);
    }

    private static Commitment commitment(String name, long amount, int frequency, long start) {
        return Commitment.create(name, amount, frequency, start, null, false, 0);
    }

    private static long day(long millis, int deltaDays) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(Commitment.startOfDay(millis));
        c.add(Calendar.DAY_OF_MONTH, deltaDays);
        return c.getTimeInMillis();
    }

    // ---- normalization ------------------------------------------------------------

    @Test public void normalized_rejectsBlankNameAndZeroAmount() {
        long start = Commitment.millisOf(2026, 10, 7, CalendarSystem.GREGORIAN);
        assertNull(Commitment.create("   ", -5000, Commitment.MONTHLY, start, null, false, 0));
        assertNull(Commitment.create("rent", 0, Commitment.MONTHLY, start, null, false, 0));
        assertNull(Commitment.create("rent", -5000, Commitment.MONTHLY, 0, null, false, 0));
    }

    @Test public void normalized_preservesValidNameAndAmount() {
        long start = Commitment.millisOf(2026, 10, 7, CalendarSystem.GREGORIAN);
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 100; i++) name.append('x');
        Commitment c = Commitment.create(name.toString(), -5000, Commitment.MONTHLY,
            start, null, false, 0);
        assertNotNull(c);
        assertEquals(100, c.name.length());
        assertNotNull(Commitment.create("rent", Commitment.MAX_AMOUNT + 1, Commitment.MONTHLY,
            start, null, false, 0));
    }

    @Test public void normalized_rejectsInvalidFrequencyAndEnd() {
        long start = Commitment.millisOf(2026, 10, 7, CalendarSystem.GREGORIAN);
        Commitment c = Commitment.create("rent", -5000, 99, start,
            Commitment.millisOf(2026, 10, 1, CalendarSystem.GREGORIAN), false, -5);
        assertNull(c);
        assertNotNull(Commitment.create("valid", Long.MIN_VALUE, Commitment.MONTHLY,
            start, null, false, 0));
    }

    @Test public void normalized_rejectsMalformedSettlementAndReminderState() {
        long start = Commitment.millisOf(2026, 10, 7, CalendarSystem.GREGORIAN);
        List<Long> invalidDate = new ArrayList<>();
        invalidDate.add(0L);
        Commitment malformedDate = new Commitment("bad-date", "bad", 1, Commitment.DAILY,
            start, null, false, invalidDate, false, 0);
        assertNull(Commitment.normalized(malformedDate));

        Commitment malformedLead = new Commitment("bad-lead", "bad", 1, Commitment.DAILY,
            start, null, false, null, false, -1);
        assertNull(Commitment.normalized(malformedLead));

        List<Long> paid = new ArrayList<>();
        paid.add(start);
        List<Long> unpaid = new ArrayList<>();
        unpaid.add(start);
        Commitment conflicting = new Commitment("conflict", "conflict", 1, Commitment.DAILY,
            start, null, false, paid, 0, unpaid, false, 0);
        assertNull(Commitment.normalized(conflicting));
    }

    // ---- JSON ---------------------------------------------------------------------

    @Test public void json_roundTripKeepsEveryField() throws Exception {
        long start = Commitment.millisOf(2026, 10, 7, CalendarSystem.GREGORIAN);
        Commitment c = Commitment.create("rent", -5000, Commitment.MONTHLY, start,
            Commitment.millisOf(2027, 10, 7, CalendarSystem.GREGORIAN), true, 86400000L);
        List<Commitment> back =
            BalanceData.deserializeCommitments(BalanceData.serializeCommitments(list(c)));
        assertEquals(1, back.size());
        Commitment r = back.get(0);
        assertEquals(c.id, r.id);
        assertEquals("rent", r.name);
        assertEquals(-5000, r.amount);
        assertEquals(Commitment.MONTHLY, r.frequency);
        assertEquals(start, r.start);
        assertEquals(Commitment.millisOf(2027, 10, 7, CalendarSystem.GREGORIAN),
            r.end.longValue());
        assertTrue(r.remind);
        assertEquals(86400000L, r.remindBeforeMs);
    }

    @Test public void json_dropsCorruptEntries() throws Exception {
        assertTrue(BalanceData.deserializeCommitments("{broken").isEmpty());
        assertTrue(BalanceData.deserializeCommitments("{\"nope\": []}").isEmpty());
    }

    @Test public void json_settledDaysRoundTrip() throws Exception {
        long start = Commitment.millisOf(2026, 10, 7, CalendarSystem.GREGORIAN);
        Commitment c = commitment("daily", -100, Commitment.DAILY, start);
        List<Long> paid = new ArrayList<>();
        paid.add(start);
        List<Long> unpaid = new ArrayList<>();
        unpaid.add(Commitment.millisOf(2026, 10, 8, CalendarSystem.GREGORIAN));
        Commitment marked = new Commitment(c.id, c.name, c.amount, c.frequency, c.start, c.end,
            false, paid, 0, unpaid, false, 0);
        List<Commitment> back =
            BalanceData.deserializeCommitments(BalanceData.serializeCommitments(list(marked)));
        assertEquals(1, back.size());
        assertEquals(paid, back.get(0).paid);
        assertEquals(unpaid, back.get(0).unpaid);
        assertTrue(back.get(0).isSettled(start));
        assertFalse(back.get(0).isSettled(unpaid.get(0)));
    }

    private static List<Commitment> list(Commitment c) {
        List<Commitment> out = new ArrayList<>();
        out.add(c);
        return out;
    }

    // ---- store --------------------------------------------------------------------

    @Test public void store_roundTripAndEmptyRemovesKey() {
        assertTrue(BalanceData.readCommitments(ctx).isEmpty());
        long start = Commitment.millisOf(2026, 10, 7, CalendarSystem.GREGORIAN);
        List<Commitment> commitments = new ArrayList<>();
        commitments.add(commitment("rent", -5000, Commitment.MONTHLY, start));
        commitments.add(commitment("salary", 9000, Commitment.MONTHLY, start));
        BalanceData.writeCommitments(ctx, commitments);
        List<Commitment> back = BalanceData.readCommitments(ctx);
        assertEquals(2, back.size());
        assertEquals("rent", back.get(0).name);
        assertEquals(9000, back.get(1).amount);
        BalanceData.writeCommitments(ctx, new ArrayList<Commitment>());
        assertTrue(BalanceData.readCommitments(ctx).isEmpty());
        assertFalse(ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE)
            .contains(BalanceData.KEY_COMMITMENTS));
    }

    // ---- occurrences ---------------------------------------------------------------

    @Test public void occurrences_onceInsideAndOutsideWindow() {
        long start = Commitment.millisOf(2026, 10, 7, CalendarSystem.GREGORIAN);
        Commitment c = commitment("once", -5000, Commitment.ONCE, start);
        List<Long> inside = Commitment.occurrences(c, CalendarSystem.GREGORIAN,
            Commitment.millisOf(2026, 10, 1, CalendarSystem.GREGORIAN),
            Commitment.millisOf(2026, 10, 31, CalendarSystem.GREGORIAN));
        assertEquals(1, inside.size());
        assertEquals(start, (long) inside.get(0));
        assertTrue(Commitment.occurrences(c, CalendarSystem.GREGORIAN,
            Commitment.millisOf(2026, 11, 1, CalendarSystem.GREGORIAN),
            Commitment.millisOf(2026, 11, 30, CalendarSystem.GREGORIAN)).isEmpty());
    }

    @Test public void occurrences_dailyStepsWholeDays() {
        long start = Commitment.millisOf(2026, 10, 30, CalendarSystem.GREGORIAN);
        Commitment c = commitment("daily", -100, Commitment.DAILY, start);
        List<Long> days = Commitment.occurrences(c, CalendarSystem.GREGORIAN,
            start, Commitment.millisOf(2026, 11, 2, CalendarSystem.GREGORIAN));
        assertEquals(4, days.size());
        assertEquals(Commitment.millisOf(2026, 11, 2, CalendarSystem.GREGORIAN),
            (long) days.get(3));
    }

    @Test public void occurrences_weeklyKeepsTheWeekday() {
        // 2026-10-05 is a Monday; the dues stay Mondays.
        long start = Commitment.millisOf(2026, 10, 5, CalendarSystem.GREGORIAN);
        Commitment c = commitment("weekly", -100, Commitment.WEEKLY, start);
        List<Long> weeks = Commitment.occurrences(c, CalendarSystem.GREGORIAN,
            start, Commitment.millisOf(2026, 10, 26, CalendarSystem.GREGORIAN));
        assertEquals(4, weeks.size());
        assertEquals(Commitment.millisOf(2026, 10, 19, CalendarSystem.GREGORIAN),
            (long) weeks.get(2));
    }

    @Test public void occurrences_monthlyClampsIntoShortMonths() {
        long start = Commitment.millisOf(2026, 1, 31, CalendarSystem.GREGORIAN);
        Commitment c = commitment("monthly", -100, Commitment.MONTHLY, start);
        List<Long> months = Commitment.occurrences(c, CalendarSystem.GREGORIAN,
            start, Commitment.millisOf(2026, 4, 30, CalendarSystem.GREGORIAN));
        assertEquals(4, months.size());
        assertEquals(Commitment.millisOf(2026, 2, 28, CalendarSystem.GREGORIAN),
            (long) months.get(1));
        assertEquals(Commitment.millisOf(2026, 3, 31, CalendarSystem.GREGORIAN),
            (long) months.get(2));
    }

    @Test public void occurrences_monthlyClampsJalaliShortMonths() {
        // Shahrivar has 31 days, Mehr 30: the 31st lands on Mehr 30th, then Aban 30th.
        int[] g = JalaliCalendar.of(1405, 6, 31).toGregorian();
        long start = Commitment.millisOf(g[0], g[1], g[2], CalendarSystem.GREGORIAN);
        Commitment c = commitment("monthly", -100, Commitment.MONTHLY, start);
        int[] gEnd = JalaliCalendar.of(1405, 8, 30).toGregorian();
        List<Long> months = Commitment.occurrences(c, CalendarSystem.JALALI, start,
            Commitment.millisOf(gEnd[0], gEnd[1], gEnd[2], CalendarSystem.GREGORIAN));
        assertEquals(3, months.size());
        int[] second = Commitment.civilDay(months.get(1), CalendarSystem.JALALI);
        assertEquals(1405, second[0]);
        assertEquals(7, second[1]);
        assertEquals(30, second[2]);
    }

    @Test public void occurrences_yearlyClampsLeapDay() {
        long start = Commitment.millisOf(2024, 2, 29, CalendarSystem.GREGORIAN);
        Commitment c = commitment("yearly", 100, Commitment.YEARLY, start);
        List<Long> years = Commitment.occurrences(c, CalendarSystem.GREGORIAN,
            start, Commitment.millisOf(2026, 12, 31, CalendarSystem.GREGORIAN));
        assertEquals(3, years.size());
        assertEquals(Commitment.millisOf(2025, 2, 28, CalendarSystem.GREGORIAN),
            (long) years.get(1));
    }

    @Test public void occurrences_endIsInclusiveAndOpenEndedContinues() {
        long start = Commitment.millisOf(2026, 10, 7, CalendarSystem.GREGORIAN);
        Commitment ended = Commitment.create("ended", -100, Commitment.DAILY, start,
            Commitment.millisOf(2026, 10, 9, CalendarSystem.GREGORIAN), false, 0);
        List<Long> days = Commitment.occurrences(ended, CalendarSystem.GREGORIAN,
            start, Commitment.millisOf(2026, 10, 31, CalendarSystem.GREGORIAN));
        assertEquals(3, days.size());
        Commitment open = commitment("open", -100, Commitment.DAILY, start);
        assertEquals(25, Commitment.occurrences(open, CalendarSystem.GREGORIAN,
            start, Commitment.millisOf(2026, 10, 31, CalendarSystem.GREGORIAN)).size());
    }

    @Test public void totalAmount_sumsEveryFiniteChildAndIgnoresSettlement() {
        long start = Commitment.millisOf(2026, 10, 7, CalendarSystem.GREGORIAN);
        Commitment c = Commitment.create("daily", -20, Commitment.DAILY, start,
            Commitment.millisOf(2026, 10, 9, CalendarSystem.GREGORIAN), false, 0);
        assertEquals(Long.valueOf(-60), Commitment.totalAmount(c, CalendarSystem.GREGORIAN));

        List<Long> paid = new ArrayList<>();
        paid.add(start);
        Commitment settled = new Commitment(c.id, c.name, c.amount, c.frequency, c.start, c.end,
            false, paid, c.remind, c.remindBeforeMs);
        assertEquals(Long.valueOf(-60), Commitment.totalAmount(settled, CalendarSystem.GREGORIAN));
    }

    @Test public void totalAmount_openEndedHasNoFiniteTotal() {
        long start = Commitment.millisOf(2026, 10, 7, CalendarSystem.GREGORIAN);
        Commitment c = commitment("open", 20, Commitment.DAILY, start);
        assertNull(Commitment.totalAmount(c, CalendarSystem.GREGORIAN));
    }

    @Test public void totalAmount_countsLongFiniteScheduleWithoutUiCap() {
        long start = Commitment.millisOf(2026, 10, 7, CalendarSystem.GREGORIAN);
        Commitment c = Commitment.create("long", 1, Commitment.DAILY, start,
            Long.MAX_VALUE, false, 0);
        Long total = Commitment.totalAmount(c, CalendarSystem.GREGORIAN);
        assertNotNull(total);
        assertTrue(total.longValue() > Commitment.MAX_TOTAL_OCCURRENCES);
    }

    @Test public void totalAmount_returnsNullOnlyWhenMultiplicationOverflows() {
        long start = Commitment.millisOf(2026, 10, 7, CalendarSystem.GREGORIAN);
        Commitment minOnce = Commitment.create("min", Long.MIN_VALUE, Commitment.ONCE, start,
            null, false, 0);
        assertEquals(Long.valueOf(Long.MIN_VALUE),
            Commitment.totalAmount(minOnce, CalendarSystem.GREGORIAN));

        Commitment overflow = Commitment.create("overflow", Long.MAX_VALUE, Commitment.DAILY,
            start, day(start, 1), false, 0);
        assertNull(Commitment.totalAmount(overflow, CalendarSystem.GREGORIAN));
    }

    // ---- settlement -----------------------------------------------------------------

    @Test public void nextDue_advancesPastMarkedDays() {
        long now = System.currentTimeMillis();
        long start = day(now, -10);
        Commitment c = commitment("daily", -100, Commitment.DAILY, start);
        assertEquals(day(now, 0), (long) Commitment.nextDue(c, CalendarSystem.GREGORIAN, now));
        List<Long> paid = new ArrayList<>();
        paid.add(day(now, 0));
        List<Commitment> stored = new ArrayList<>();
        stored.add(new Commitment(c.id, c.name, c.amount, c.frequency, c.start, c.end, false,
            paid, c.remind, c.remindBeforeMs));
        assertEquals(day(now, 1),
            (long) Commitment.nextDue(stored.get(0), CalendarSystem.GREGORIAN, now));
    }

    @Test public void markingLaterDue_leavesOlderDuesUnsettled() {
        long now = System.currentTimeMillis();
        Commitment c = commitment("daily", -100, Commitment.DAILY, day(now, -10));
        List<Long> paid = new ArrayList<>();
        paid.add(day(now, 0));
        Commitment marked = new Commitment(c.id, c.name, c.amount, c.frequency, c.start, c.end,
            false, paid, c.remind, c.remindBeforeMs);
        assertTrue(marked.isSettled(day(now, 0)));
        assertFalse(marked.isSettled(day(now, -5)));
        assertEquals(day(now, 1),
            (long) Commitment.nextDue(marked, CalendarSystem.GREGORIAN, now));
    }

    @Test public void nextDue_onceFinishesAndEndedScheduleEnds() {
        long now = System.currentTimeMillis();
        long start = day(now, -3);
        Commitment open = commitment("once", -100, Commitment.ONCE, start);
        assertEquals(start, (long) Commitment.nextDue(open, CalendarSystem.GREGORIAN, now));
        Commitment finished = new Commitment(open.id, open.name, open.amount, open.frequency,
            open.start, open.end, true, null, false, 0);
        assertNull(Commitment.nextDue(finished, CalendarSystem.GREGORIAN, now));
        Commitment ended = Commitment.create("ended", -100, Commitment.DAILY, day(now, -10),
            day(now, -5), false, 0);
        List<Long> paid = Commitment.occurrences(ended, CalendarSystem.GREGORIAN,
            day(now, -10), day(now, -5));
        assertNull(Commitment.nextDue(new Commitment(ended.id, ended.name, ended.amount,
            ended.frequency, ended.start, ended.end, false, paid, false, 0),
            CalendarSystem.GREGORIAN, now));
    }

    @Test public void reminderAt_respectsLeadTimeAndSwitch() {
        long now = System.currentTimeMillis();
        long start = day(now, 5);
        Commitment c = Commitment.create("rent", -5000, Commitment.MONTHLY, start, null,
            true, 2 * 86400000L);
        assertEquals(start - 2 * 86400000L,
            (long) Commitment.reminderAt(c, CalendarSystem.GREGORIAN, now));
        Commitment quiet = Commitment.create("rent", -5000, Commitment.MONTHLY, start, null,
            false, 2 * 86400000L);
        assertNull(Commitment.reminderAt(quiet, CalendarSystem.GREGORIAN, now));
        Commitment overdue = commitment("overdue", -5000, Commitment.ONCE, day(now, -2));
        Commitment reminding = new Commitment(overdue.id, overdue.name, overdue.amount,
            overdue.frequency, overdue.start, overdue.end, false, null, true, 86400000L);
        Long at = Commitment.reminderAt(reminding, CalendarSystem.GREGORIAN, now);
        assertNotNull(at);
        assertTrue(at >= now);
    }
}
