package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CommitmentOutstandingTest {
    private static final CalendarSystem G = CalendarSystem.GREGORIAN, J = CalendarSystem.JALALI;
    private static final ZoneId UTC = ZoneId.of("UTC");

    private static long day(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    @Test public void allOldOneOffAndDailyDuesCountButOnlyNewestFiftyAreListed() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate start = LocalDate.of(2014, 3, 2);
        LocalDate month = LocalDate.of(2026, 11, 1);
        long first = day(start, zone), now = day(month.plusDays(5), zone);
        Commitment daily = Commitment.create("daily", Long.MIN_VALUE, Commitment.DAILY,
            first, null, false, 0, G, zone);
        Commitment bill = Commitment.create("old bill", 91, Commitment.ONCE, first, null, false, 0);
        List<Commitment> items = Arrays.asList(bill, daily);
        long prior = ChronoUnit.DAYS.between(start, month);
        BigInteger pay = BigInteger.valueOf(Long.MIN_VALUE);
        CommitmentsActivity.Summary detail = CommitmentsActivity.summarize(items, G, now, 1);
        assertEquals(prior + 1, detail.overdueCount);
        assertEquals(pay.multiply(BigInteger.valueOf(prior)), detail.overduePay);
        assertEquals(50, detail.overdue.size());
        for (int i = 0; i < 50; i++)
            assertEquals(day(month.minusDays(i + 1), zone), detail.overdue.get(i).date);
        BigInteger throughNovember = pay.multiply(BigInteger.valueOf(prior + 30));
        assertEquals(throughNovember, detail.remainingPay());
        DashboardCommitments card = DashboardCommitments.summarize(items, G, now);
        assertEquals(throughNovember, card.remainingPay());
        assertEquals(BigInteger.valueOf(91), card.remainingReceive());

        detail = CommitmentsActivity.summarize(Collections.singletonList(bill), G, now, 1);
        assertEquals(first, detail.overdue.get(0).date);
        detail = CommitmentsActivity.summarize(Collections.singletonList(
            bill.withSettlements(true, null, null)), G, now, 1);
        assertEquals(0, detail.overdueCount);
        assertEquals(first, detail.settledMonths.get(0).rows.get(0).date);
    }

    @Test public void clampedJalaliDuesUseHomeZoneAndOnlyMatchingUnpaidEdits() {
        ZoneId home = ZoneId.of("Pacific/Kiritimati");
        long first = Commitment.millisOf(1390, 6, 31, J, home);
        long watermark = Commitment.millisOf(1405, 6, 31, J, home) + 3600000;
        long mehr = Commitment.millisOf(1405, 7, 30, J, home);
        long aban = Commitment.millisOf(1405, 8, 30, J, home);
        long azar = Commitment.millisOf(1405, 9, 30, J, home);
        Commitment c = new Commitment("rent", "rent", -10, Commitment.MONTHLY, first, azar,
            false, Arrays.asList(mehr, first + 2 * 86400000), watermark, Collections.singletonList(first),
            false, 0).freezeSchedule(J, home);
        Map<Long, Long> edits = new HashMap<>();
        edits.put(first, -91L);   // Reopened under the watermark.
        edits.put(mehr, -519L);   // Settled explicitly.
        edits.put(aban, -44L);
        edits.put(azar, 23L);     // Dormant opposite direction.
        edits.put(aban - 1000, -72L); // Dormant off-schedule instant.
        c = c.withOccurrenceAmounts(edits);
        List<Commitment> items = Collections.singletonList(c);
        long now = Commitment.millisOf(1405, 8, 2, J);
        CommitmentsActivity.Summary detail = CommitmentsActivity.summarize(items, J, now, 2);
        assertEquals(1, detail.overdueCount);
        assertEquals(first, detail.overdue.get(0).date);
        assertEquals(BigInteger.valueOf(-91), detail.overduePay);
        assertEquals(BigInteger.valueOf(-44), detail.thisMonthPay);
        assertEquals(BigInteger.valueOf(-145), detail.payTotal);
        assertEquals(BigInteger.valueOf(-135), DashboardCommitments.summarize(items, J, now)
            .remainingPay());
    }

    @Test public void arithmeticMatchesAnOccurrenceWalkForLegacyAndFrozenAnchors() {
        for (CalendarSystem calendar : new CalendarSystem[]{G, J}) {
            for (boolean frozen : new boolean[]{false, true}) {
                ZoneId zone = frozen ? ZoneId.of("America/New_York") : ZoneId.systemDefault();
                LocalDate first = LocalDate.of(2016, 1, 31), cutoff = LocalDate.of(2026, 2, 28);
                for (int freq : new int[]{Commitment.DAILY, Commitment.WEEKLY, Commitment.MONTHLY,
                        Commitment.YEARLY}) {
                    List<Long> paid = Collections.singletonList(day(first, zone));
                    Commitment c = new Commitment("series", "series", 77, freq, day(first, zone),
                        day(cutoff.plusDays(10), zone), false, paid, day(first.plusDays(100), zone),
                        Collections.singletonList(day(first.plusDays(7), zone)), false, 0);
                    if (frozen) c = c.freezeSchedule(calendar, zone);
                    List<Long> expected = new ArrayList<>();
                    for (long due : Commitment.occurrences(c, calendar,
                            day(first.minusDays(2), ZoneId.systemDefault()),
                            day(cutoff, ZoneId.systemDefault()))) {
                        if (!c.isSettled(due)) expected.add(due);
                    }
                    CommitmentOutstanding result = new CommitmentOutstanding(c, calendar, cutoff);
                    assertEquals(expected.size(), result.count);
                    assertEquals(BigInteger.valueOf(77L * expected.size()), result.amount);
                    List<Long> rows = new ArrayList<>();
                    result.visitLatest(false, 5, rows::add);
                    Collections.sort(rows);
                    assertEquals(expected.subList(Math.max(0, expected.size() - 5), expected.size()), rows);
                }
            }
        }
    }

    @Test(timeout = 5000) public void millionsOfElapsedDuesRequireOnlySparseMarksAndBoundedRows() {
        LocalDate first = LocalDate.of(1971, 1, 1), cutoff = LocalDate.of(200000, 1, 1);
        long anchor = day(first, UTC);
        long watermark = day(cutoff.minusDays(55), UTC) + 1;
        Commitment c = new Commitment("daily", "daily", 7, Commitment.DAILY, anchor, null, false,
            Collections.singletonList(day(cutoff, UTC)), watermark, Collections.singletonList(anchor),
            false, 0).freezeSchedule(G, UTC);
        CommitmentOutstanding result = new CommitmentOutstanding(c, G, cutoff);
        assertEquals(55, result.count); // 55 since watermark, minus paid today, plus one reopening.
        assertEquals(BigInteger.valueOf(385), result.amount);
        List<Long> rows = new ArrayList<>();
        result.visitLatest(false, 60, rows::add);
        assertEquals(55, rows.size());
        assertTrue(rows.contains(anchor));
        rows.clear();
        result.visitLatest(true, 8, rows::add);
        assertEquals(8, rows.size());
        assertTrue(rows.contains(day(cutoff, UTC)));
    }

    @Test public void crossingASkippedCivilDayKeepsBothRowsAndTheirSharedSettlementConsistent() {
        ZoneId home = ZoneId.of("Pacific/Kiritimati");
        LocalDate dec30 = LocalDate.of(1994, 12, 30), jan1 = dec30.plusDays(2);
        long first = day(dec30, home), shared = day(jan1, home);
        Commitment c = Commitment.create("daily", 10, Commitment.DAILY, first, shared, false,
            0, G, home).withOccurrenceAmounts(Collections.singletonMap(shared, 20L));
        long now = day(jan1.plusDays(4), ZoneId.systemDefault());
        for (boolean paid : new boolean[]{false, true}) {
            if (paid) c = c.withSettlements(false, Collections.singletonList(shared), null);
            List<Commitment> items = Collections.singletonList(c);
            BigInteger expected = BigInteger.valueOf(paid ? 10 : 50);
            assertEquals(expected, DashboardCommitments.summarize(items, G, now).remainingReceive());
            assertEquals(expected, CommitmentsActivity.summarize(items, G, now, 1).remainingReceive());
        }
    }
}
