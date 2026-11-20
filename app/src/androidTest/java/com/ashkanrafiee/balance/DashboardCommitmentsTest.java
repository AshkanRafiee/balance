package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public final class DashboardCommitmentsTest {
    @Test public void totalsMatchDetailAcrossCalendarBoundariesAndEditedSettlements() {
        CalendarSystem gregorian = CalendarSystem.GREGORIAN;
        ZoneId home = ZoneId.of("Pacific/Kiritimati");
        long first = Commitment.millisOf(2026, 2, 28, gregorian, home);
        long march = Commitment.millisOf(2026, 3, 28, gregorian, home);
        long april = Commitment.millisOf(2026, 4, 28, gregorian, home);
        Commitment monthly = Commitment.create("monthly", -100L, Commitment.MONTHLY, first,
            null, false, 0L, gregorian, home)
            .withOccurrenceAmounts(Collections.singletonMap(march, -500L))
            .withSettlements(false, Collections.singletonList(april), null);
        // Also exercises the overdue visit cap and sums too large for a signed long.
        Commitment oldDaily = Commitment.create("old daily", Long.MAX_VALUE, Commitment.DAILY,
            Commitment.millisOf(2016, 4, 1, gregorian, home), null, false, 0L, gregorian, home);
        List<Commitment> items = Arrays.asList(monthly, oldDaily);
        for (CalendarSystem cal : new CalendarSystem[]{gregorian, CalendarSystem.JALALI}) {
            for (int[] date : new int[][]{{3, 1}, {3, 21}, {3, 31}, {4, 1}, {4, 30}}) {
                long now = Commitment.millisOf(2026, date[0], date[1], gregorian);
                DashboardCommitments card = DashboardCommitments.summarize(items, cal, now);
                CommitmentsActivity.Summary detail = CommitmentsActivity.summarize(items, cal,
                    now, CommitmentsActivity.WINDOW_MONTHS);
                assertEquals(detail.remainingPay(), card.remainingPay());
                assertEquals(detail.remainingReceive(), card.remainingReceive());
            }
        }
    }
}
