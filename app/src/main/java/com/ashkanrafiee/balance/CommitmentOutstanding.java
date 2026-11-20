package com.ashkanrafiee.balance;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Unsettled dues from the schedule anchor through the end of one display civil day. The
 * reporting horizon is finite even for an open schedule. Only sparse marks and amount edits,
 * never all elapsed days, need visiting to calculate the count and signed amount. */
final class CommitmentOutstanding {
    final long count;
    final BigInteger amount;
    private final Commitment c;
    private final CommitmentSchedule schedule;
    private final long through, occurrences, watermarked;

    CommitmentOutstanding(Commitment c, CalendarSystem displayCalendar, LocalDate lastDay) {
        this.c = c;
        // Before migration a series follows the displayed calendar and device zone. Interpret
        // its anchor the same way without moving any of its exact settlement identities.
        schedule = c.scheduleForDisplay(displayCalendar);
        // The requested day comes from a representable display timestamp, but the next home
        // midnight can fall beyond the last representable millisecond. Ends remain inclusive.
        Instant next = lastDay.plusDays(1).atStartOfDay(schedule.zone).toInstant();
        through = next.isAfter(Instant.ofEpochMilli(Long.MAX_VALUE))
            ? Long.MAX_VALUE : next.toEpochMilli() - 1;
        occurrences = countThroughInstant(through);
        if (occurrences == 0 || c.frequency == Commitment.ONCE) {
            watermarked = 0;
            count = c.done ? 0 : occurrences;
            amount = count == 0 ? BigInteger.ZERO : BigInteger.valueOf(c.amount);
            return;
        }
        watermarked = c.legacyPaidThrough > 0
            ? countThroughInstant(Math.min(through, c.legacyPaidThrough)) : 0;
        long unpaid = occurrences - watermarked;
        BigInteger regular = BigInteger.valueOf(c.amount);
        BigInteger adjustment = BigInteger.ZERO;
        // Initially everything after the watermark is outstanding and everything before it paid.
        // Apply sparse deltas, including cancelling an edited amount when its due is settled.
        for (Map.Entry<Long, Long> edit : c.occurrenceAmounts.entrySet()) {
            long date = edit.getKey();
            if (coveredByWatermark(date) || date > through || (edit.getValue() < 0) != c.isPayment()
                    || !schedule.contains(date, c.frequency)) continue;
            adjustment = adjustment.add(BigInteger.valueOf(edit.getValue()).subtract(regular)
                .multiply(BigInteger.valueOf(multiplicity(date))));
        }
        for (long date : c.paid) {
            if (coveredByWatermark(date) || date > through) continue;
            long n = multiplicity(date);
            unpaid -= n;
            if (n != 0 && c.occurrenceAmounts.containsKey(date))
                adjustment = adjustment.subtract(amountDelta(date, n, regular));
        }
        for (long date : c.unpaid) {
            if (!coveredByWatermark(date) || date > through) continue;
            long n = multiplicity(date);
            unpaid += n;
            if (n != 0 && c.occurrenceAmounts.containsKey(date))
                adjustment = adjustment.add(amountDelta(date, n, regular));
        }
        count = unpaid;
        amount = regular.multiply(BigInteger.valueOf(count)).add(adjustment);
    }

    private BigInteger amountDelta(long date, long n, BigInteger regular) {
        return BigInteger.valueOf(c.amountAt(date)).subtract(regular).multiply(BigInteger.valueOf(n));
    }

    private boolean coveredByWatermark(long due) {
        return c.legacyPaidThrough > 0 && due <= c.legacyPaidThrough;
    }

    private long countThroughInstant(long instant) {
        LocalDate day = CommitmentSchedule.day(instant, schedule.zone);
        // Some historical rollbacks occurred just AFTER midnight: tomorrow's first midnight
        // already passed even though the clock is back on the previous date.
        LocalDate next = day.plusDays(1);
        if (!next.atStartOfDay(schedule.zone).toInstant().isAfter(Instant.ofEpochMilli(instant)))
            day = next;
        return schedule.countThrough(day, c.frequency);
    }

    /** A mark only affects actual generated due instants. In a zone that skipped a whole civil
     * day, two daily dues can share one instant; the existing row and settlement rules apply to both. */
    private long multiplicity(long due) {
        return countThroughInstant(due) - countThroughInstant(due - 1);
    }

    /** Bounded newest candidates for either menu, including reopened dues far below a watermark.
     * Only explicit marks can interrupt a contiguous run; never walk years to skip paid history. */
    void visitLatest(boolean settled, int limit, Commitment.OccurrenceVisitor visitor) {
        if (limit <= 0 || (settled ? occurrences - count : count) == 0) return;
        if (c.frequency == Commitment.ONCE) {
            visitor.visit(schedule.instant(schedule.first));
            return;
        }
        if (settled) {
            limit -= visitExplicit(c.paid, false, limit, visitor);
            if (limit > 0) visitRun(watermarked, 0, c.unpaid, limit, visitor);
        } else {
            limit -= visitRun(occurrences, watermarked, c.paid, limit, visitor);
            if (limit > 0) visitExplicit(c.unpaid, true, limit, visitor);
        }
    }

    private int visitRun(long from, long stop, List<Long> exceptions, int limit,
            Commitment.OccurrenceVisitor visitor) {
        if (from == stop) return 0;
        Set<Long> skipped = exceptions.isEmpty() ? Collections.emptySet() : new HashSet<>(exceptions);
        int shown = 0;
        for (long i = from; i > stop;) {
            long due = schedule.instant(schedule.at(--i, c.frequency));
            if (!skipped.contains(due)) {
                visitor.visit(due);
                if (++shown == limit) break;
            }
        }
        return shown;
    }

    private int visitExplicit(List<Long> marks, boolean beforeWatermark, int limit,
            Commitment.OccurrenceVisitor visitor) {
        PriorityQueue<Long> newest = new PriorityQueue<>();
        for (long due : marks) {
            if (coveredByWatermark(due) != beforeWatermark || due > through) continue;
            for (long n = multiplicity(due); n > 0; n--) {
                newest.add(due);
                if (newest.size() > limit) newest.remove();
            }
        }
        // The screen merges and sorts the bounded candidates across commitments.
        for (long due : newest) visitor.visit(due);
        return newest.size();
    }
}
