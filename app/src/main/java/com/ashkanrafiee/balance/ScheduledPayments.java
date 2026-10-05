package com.ashkanrafiee.balance;

import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Schedule generation, bounded history queries, summaries, and amount parsing for planned payments. */
final class ScheduledPayments {
    static final int MAX_PLANS = 200;

    private ScheduledPayments() {}

    /**
     * Returns occurrences in a civil-day interval. Open-ended plans are bounded by the documented
     * {@link ScheduledDate} planning horizon, not by an arbitrary number such as 5,000. Sequence
     * indexes remain stable because they are calculated directly from the original anchor date.
     */
    static List<PaymentOccurrence> occurrences(List<ScheduledPayment> plans, long fromOrdinal,
            long toOrdinal) {
        if (fromOrdinal > toOrdinal) throw new IllegalArgumentException("Invalid occurrence range");
        List<PaymentOccurrence> out = new ArrayList<>();
        if (plans == null) return out;
        for (ScheduledPayment plan : plans) {
            if (plan == null || plan.archived) continue;
            int first = firstSequenceOnOrAfter(plan, fromOrdinal);
            int last = lastSequenceOnOrBefore(plan, toOrdinal);
            if (first > last) continue;
            for (int sequence = first; sequence <= last; sequence++) {
                ScheduledDate date = occurrenceDate(plan, sequence);
                // The upper bound already accounts for the stop cutoff. Keep this check explicit so
                // a future metadata change cannot accidentally expose stopped rows.
                if (plan.stoppedAfter != null && date.compareTo(plan.stoppedAfter) > 0) break;
                out.add(new PaymentOccurrence(plan, sequence, date));
            }
        }
        sortOccurrences(out);
        return out;
    }

    static List<PaymentOccurrence> occurrences(List<ScheduledPayment> plans, ScheduledDate from,
            ScheduledDate to) {
        if (from == null || to == null || from.compareTo(to) > 0)
            throw new IllegalArgumentException("Invalid occurrence range");
        return occurrences(plans, from.ordinal(), to.ordinal());
    }

    /** A finite-window page for month/history UIs; offset is applied after stable date ordering. */
    static List<PaymentOccurrence> occurrencesPage(List<ScheduledPayment> plans, ScheduledDate from,
            ScheduledDate to, int offset, int limit) {
        if (offset < 0 || limit <= 0) throw new IllegalArgumentException("Invalid occurrence page");
        List<PaymentOccurrence> all = occurrences(plans, from, to);
        if (offset >= all.size()) return new ArrayList<>();
        int remaining = all.size() - offset;
        int end = limit >= remaining ? all.size() : offset + limit;
        return new ArrayList<>(all.subList(offset, end));
    }

    /** Direct anchor-based date lookup. Kept package-visible for the planner UI and tests. */
    static ScheduledDate occurrenceDate(ScheduledPayment p, int sequence) {
        if (p == null || sequence < 0 || sequence > maxSequence(p))
            throw new IllegalArgumentException("Bad occurrence");
        ScheduledDate first = p.firstDate();
        if (sequence == 0 || p.frequency == ScheduledPayment.Frequency.ONCE) return first;
        if (p.frequency == ScheduledPayment.Frequency.WEEKLY)
            return first.plusDays(Math.multiplyExact(sequence, 7));
        if (p.frequency == ScheduledPayment.Frequency.MONTHLY) {
            long monthIndex = (long) first.year * 12L + first.month - 1L + sequence;
            int year = Math.toIntExact(Math.floorDiv(monthIndex, 12L));
            int month = (int) Math.floorMod(monthIndex, 12L) + 1;
            int day = Math.min(first.day, p.calendar.daysInMonth(year, month));
            return new ScheduledDate(p.calendar, year, month, day);
        }
        int year = Math.addExact(first.year, sequence);
        int day = Math.min(first.day, p.calendar.daysInMonth(year, first.month));
        return new ScheduledDate(p.calendar, year, first.month, day);
    }

    /** Returns the exact sequence for a date, or -1 when the date is not an occurrence. */
    static int sequenceForDate(ScheduledPayment p, ScheduledDate date) {
        if (p == null || date == null || date.calendar != p.calendar) return -1;
        int sequence = firstSequenceOnOrAfter(p, date.ordinal());
        if (sequence > maxSequence(p)) return -1;
        return occurrenceDate(p, sequence).equals(date) ? sequence : -1;
    }

    /** First sequence whose date is at least the supplied ordinal, or max+1 when none exists. */
    static int firstSequenceOnOrAfter(ScheduledPayment p, long ordinal) {
        int high = maxSequence(p);
        if (high < 0) return 0;
        if (ordinal <= occurrenceDate(p, 0).ordinal()) return 0;
        if (ordinal > occurrenceDate(p, high).ordinal()) return high + 1;
        int low = 0;
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (occurrenceDate(p, middle).ordinal() < ordinal) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    /** Last sequence whose date is at most the supplied ordinal, or -1 when none exists. */
    static int lastSequenceOnOrBefore(ScheduledPayment p, long ordinal) {
        int high = maxSequence(p);
        if (high < 0) return -1;
        if (ordinal < occurrenceDate(p, 0).ordinal()) return -1;
        if (ordinal >= occurrenceDate(p, high).ordinal()) return high;
        int low = 0;
        while (low < high) {
            int middle = low + (high - low + 1) / 2;
            if (occurrenceDate(p, middle).ordinal() <= ordinal) low = middle;
            else high = middle - 1;
        }
        return low;
    }

    /** Highest valid sequence in the planning horizon after end/stop cutoffs are applied. */
    static int maxSequence(ScheduledPayment p) {
        if (p == null) throw new IllegalArgumentException("Plan required");
        ScheduledDate first = p.firstDate();
        if (p.frequency == ScheduledPayment.Frequency.ONCE) {
            return p.stoppedAfter != null && p.stoppedAfter.ordinal() < first.ordinal() ? -1 : 0;
        }
        int maximum;
        ScheduledDate latest = ScheduledDate.latest(p.calendar);
        long dayDistance = latest.ordinal() - first.ordinal();
        switch (p.frequency) {
            case WEEKLY:
                maximum = Math.toIntExact(Math.max(0L, dayDistance / 7L));
                break;
            case MONTHLY:
                maximum = Math.toIntExact(Math.max(0L,
                    (long) (latest.year - first.year) * 12L + latest.month - first.month));
                break;
            case YEARLY:
                maximum = Math.max(0, latest.year - first.year);
                break;
            default:
                maximum = 0;
        }
        if (p.endMode == ScheduledPayment.EndMode.COUNT)
            maximum = Math.min(maximum, p.occurrenceCount - 1);
        if (p.endMode == ScheduledPayment.EndMode.DATE)
            maximum = Math.min(maximum, lastSequenceIgnoringCutoff(p, p.endDate().ordinal()));
        if (p.stoppedAfter != null)
            maximum = Math.min(maximum, lastSequenceIgnoringCutoff(p, p.stoppedAfter.ordinal()));
        return maximum;
    }

    /** Highest sequence the civil-date horizon can ever name, ignoring editable end metadata. */
    static int stateSequenceLimit(ScheduledPayment p) {
        return horizonSequence(p);
    }

    /** Same as maxSequence, but excludes metadata cutoffs so binary search can inspect the horizon. */
    private static int lastSequenceIgnoringCutoff(ScheduledPayment p, long ordinal) {
        int horizon = horizonSequence(p);
        if (ordinal < occurrenceDateIgnoringCutoff(p, 0).ordinal()) return -1;
        if (ordinal >= occurrenceDateIgnoringCutoff(p, horizon).ordinal()) return horizon;
        int low = 0, high = horizon;
        while (low < high) {
            int middle = low + (high - low + 1) / 2;
            if (occurrenceDateIgnoringCutoff(p, middle).ordinal() <= ordinal) low = middle;
            else high = middle - 1;
        }
        return low;
    }

    private static int horizonSequence(ScheduledPayment p) {
        if (p.frequency == ScheduledPayment.Frequency.ONCE) return 0;
        ScheduledDate first = p.firstDate();
        ScheduledDate latest = ScheduledDate.latest(p.calendar);
        long days = latest.ordinal() - first.ordinal();
        switch (p.frequency) {
            case WEEKLY: return Math.toIntExact(Math.max(0L, days / 7L));
            case MONTHLY: return Math.toIntExact(Math.max(0L,
                (long) (latest.year - first.year) * 12L + latest.month - first.month));
            case YEARLY: return Math.max(0, latest.year - first.year);
            default: return 0;
        }
    }

    private static ScheduledDate occurrenceDateIgnoringCutoff(ScheduledPayment p, int sequence) {
        // Only this helper's bound calculation needs to see beyond an end; avoid constructing a
        // second mutable plan by duplicating the small date calculation.
        if (p.frequency == ScheduledPayment.Frequency.ONCE) return p.firstDate();
        ScheduledDate first = p.firstDate();
        if (p.frequency == ScheduledPayment.Frequency.WEEKLY)
            return first.plusDays(Math.multiplyExact(sequence, 7));
        if (p.frequency == ScheduledPayment.Frequency.MONTHLY) {
            long index = (long) first.year * 12L + first.month - 1L + sequence;
            int year = Math.toIntExact(Math.floorDiv(index, 12L));
            int month = (int) Math.floorMod(index, 12L) + 1;
            return new ScheduledDate(p.calendar, year, month,
                Math.min(first.day, p.calendar.daysInMonth(year, month)));
        }
        int year = Math.addExact(first.year, sequence);
        return new ScheduledDate(p.calendar, year, first.month,
            Math.min(first.day, p.calendar.daysInMonth(year, first.month)));
    }

    private static void sortOccurrences(List<PaymentOccurrence> out) {
        Collections.sort(out, new Comparator<PaymentOccurrence>() {
            @Override public int compare(PaymentOccurrence a, PaymentOccurrence b) {
                int c = a.date.compareTo(b.date);
                if (c != 0) return c;
                c = a.payment.title.compareToIgnoreCase(b.payment.title);
                if (c != 0) return c;
                c = a.payment.id.compareTo(b.payment.id);
                return c != 0 ? c : Integer.compare(a.sequence, b.sequence);
            }
        });
    }

    /** Pure summary calculation shared by the dashboard and planner screen. */
    static ScheduledPaymentSummary summary(List<ScheduledPayment> plans, ScheduledDate today,
            long includedTotal, boolean hasIncludedBalance) {
        if (today == null) throw new IllegalArgumentException("Today required");
        if (plans == null || plans.isEmpty()) return ScheduledPaymentSummary.empty();
        ScheduledDate monthStart = new ScheduledDate(today.calendar, today.year, today.month, 1);
        ScheduledDate monthEnd = new ScheduledDate(today.calendar, today.year, today.month,
            today.calendar.daysInMonth(today.year, today.month));
        long current = 0, overdue = 0, future = 0;
        int currentCount = 0, overdueCount = 0, futureCount = 0;
        int paidCount = 0, skippedCount = 0;
        boolean hasPlans = false;
        for (ScheduledPayment plan : plans) {
            if (plan == null || plan.archived) continue;
            hasPlans = true;
            int before = lastSequenceOnOrBefore(plan, monthStart.ordinal() - 1L);
            int monthFirst = firstSequenceOnOrAfter(plan, monthStart.ordinal());
            int monthLast = lastSequenceOnOrBefore(plan, monthEnd.ordinal());
            int futureFirst = firstSequenceOnOrAfter(plan, monthEnd.ordinal() + 1L);
            int maximum = maxSequence(plan);

            int overdueTotal = countRange(0, before);
            int overdueDone = completedInRange(plan, 0, before);
            int monthTotal = countRange(monthFirst, monthLast);
            int monthDone = completedInRange(plan, monthFirst, monthLast);
            int monthPaid = stateCount(plan, monthFirst, monthLast, ScheduledPayment.STATE_PAID);
            int monthSkipped = stateCount(plan, monthFirst, monthLast, ScheduledPayment.STATE_SKIPPED);
            int futureTotal = countRange(futureFirst, maximum);
            int futureDone = completedInRange(plan, futureFirst, maximum);

            overdueCount = safeCountAdd(overdueCount, overdueTotal - overdueDone);
            currentCount = safeCountAdd(currentCount, monthTotal - monthDone);
            futureCount = safeCountAdd(futureCount, futureTotal - futureDone);
            paidCount = safeCountAdd(paidCount, monthPaid);
            skippedCount = safeCountAdd(skippedCount, monthSkipped);
            overdue = safeAdd(overdue, safeMultiply(plan.amountRial, overdueTotal - overdueDone));
            current = safeAdd(current, safeMultiply(plan.amountRial, monthTotal - monthDone));
            future = safeAdd(future, safeMultiply(plan.amountRial, futureTotal - futureDone));
        }
        if (!hasPlans) return ScheduledPaymentSummary.empty();
        long remaining = hasIncludedBalance ? safeSubtract(includedTotal, current) : 0;
        return new ScheduledPaymentSummary(true, hasIncludedBalance, current, currentCount,
            overdue, overdueCount, remaining, paidCount, skippedCount, future, futureCount);
    }

    /** Context convenience for the dashboard; the pure overload is the source of truth. */
    static ScheduledPaymentSummary summary(Context context, long includedTotal, boolean hasIncludedBalance) {
        List<ScheduledPayment> plans = BalanceData.readScheduledPayments(context);
        CalendarSystem active = RegionHelper.isIran(context) ? CalendarSystem.JALALI : CalendarSystem.GREGORIAN;
        java.util.Calendar c = java.util.Calendar.getInstance();
        ScheduledDate today = ScheduledDate.fromGregorian(c.get(java.util.Calendar.YEAR),
            c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH), active);
        return summary(plans, today, includedTotal, hasIncludedBalance);
    }

    private static int countRange(int first, int last) {
        if (first < 0 || last < first) return 0;
        return last - first + 1;
    }

    private static int completedInRange(ScheduledPayment plan, int first, int last) {
        if (first < 0 || last < first) return 0;
        int result = 0;
        for (java.util.Map.Entry<Integer, Integer> e : plan.states.entrySet()) {
            if (e.getKey() >= first && e.getKey() <= last && e.getValue() != ScheduledPayment.STATE_UNPAID)
                result = safeCountAdd(result, 1);
        }
        return result;
    }

    private static int stateCount(ScheduledPayment plan, int first, int last, int state) {
        if (first < 0 || last < first) return 0;
        int result = 0;
        for (java.util.Map.Entry<Integer, Integer> e : plan.states.entrySet()) {
            if (e.getKey() >= first && e.getKey() <= last && e.getValue() == state)
                result = safeCountAdd(result, 1);
        }
        return result;
    }

    private static int safeCountAdd(int a, int b) {
        if (b > 0 && a > Integer.MAX_VALUE - b) return Integer.MAX_VALUE;
        return a + b;
    }

    private static long safeMultiply(long amount, int count) {
        if (count <= 0 || amount <= 0) return 0;
        if (amount > Long.MAX_VALUE / count) return Long.MAX_VALUE;
        return amount * count;
    }

    static long safeAdd(long a, long b) {
        if (b > 0 && a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
        if (b < 0 && a < Long.MIN_VALUE - b) return Long.MIN_VALUE;
        return a + b;
    }

    static long safeSubtract(long a, long b) {
        if (b > 0 && a < Long.MIN_VALUE + b) return Long.MIN_VALUE;
        if (b < 0 && a > Long.MAX_VALUE + b) return Long.MAX_VALUE;
        return a - b;
    }

    /** Parses the number shown in the current display unit and stores positive raw rials. */
    static long parseAmountRial(String text, boolean toman) {
        if (text == null) throw new IllegalArgumentException("Amount required");
        String value = Digits.ascii(text).trim()
            .replace(",", "").replace("٬", "").replace(" ", "");
        if (value.isEmpty() || !value.matches("[0-9]+"))
            throw new IllegalArgumentException("Amount must be a whole number");
        long shown;
        try {
            shown = Long.parseLong(value);
            if (shown <= 0) throw new IllegalArgumentException("Amount must be positive");
            return toman ? Math.multiplyExact(shown, 10L) : shown;
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("Amount is too large");
        }
    }
}
