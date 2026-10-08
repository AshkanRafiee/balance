package com.ashkanrafiee.balance;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.time.LocalDate;
import org.json.JSONObject;

/** A user-defined commitment: money the user will pay or receive, once or on a schedule.
 *
 *  <p>The name is the user's own words ("rent", "mom's loan", "music subscription") — the app
 *  never categorizes, it only provides the grounding: the schedule, the per-month and total
 *  figures and the reminders. The amount is signed in rials: negative is money the user pays
 *  out, positive is money the user receives.
 *
 *  <p>One-time commitments settle with {@link #done}; recurring ones settle per occurrence in
 *  {@link #paid}, the exact due days the user marked, so marking a later due never settles an
 *  older one. A null {@link #end} is open-ended; setting one later (or clearing it) only moves
 *  the window the occurrences below expand into.
 *
 *  <p>Occurrences expand in whole civil days in the viewing calendar (a monthly commitment due
 *  on the 31st lands on the last day of short months), so daylight-saving transitions can
 *  never shift or duplicate one. What is stored stays calendar-free epoch millis; the viewing
 *  calendar only decides how those millis read as months. */
final class Commitment {
    static final int ONCE = 0;
    static final int DAILY = 1;
    static final int WEEKLY = 2;
    static final int MONTHLY = 3;
    static final int YEARLY = 4;

    /** Historical limits, retained for source compatibility only. Not retention limits. */
    @Deprecated static final int MAX_NAME_LENGTH = 64;
    @Deprecated static final int MAX_ID_LENGTH = 128;
    @Deprecated static final long MAX_AMOUNT = 999_999_999_999L;
    @Deprecated static final long MAX_REMIND_BEFORE_MS = 365L * 86400000L;
    @Deprecated static final int MAX_COMMITMENTS = 500;
    @Deprecated static final int MAX_SETTLED_DAYS = 2000;
    @Deprecated static final long MAX_TOTAL_OCCURRENCES = 100_000L;

    interface OccurrenceVisitor { void visit(long at); }

    final String id;
    final String name;
    final long amount;
    final int frequency;
    /** Anchor of the schedule as epoch millis; the first due reads off its civil day. */
    final long start;
    /** Last due day as epoch millis, or null while open-ended. Inclusive. */
    final Long end;
    /** A one-time commitment that was paid or received. Only meaningful for {@link #ONCE}. */
    final boolean done;
    /** Recurring due days the user marked settled, as start-of-day millis. */
    final List<Long> paid;
    /** Exact dates reopened under the legacy watermark. Overrides both kinds of paid marks. */
    final List<Long> unpaid;
    /** Compatibility with the first commitment build, which stored one recurring watermark.
     *  New marks always use {@link #paid}; this is retained only so an existing user's old
     *  settled history does not suddenly reappear as unpaid after upgrading. */
    final long legacyPaidThrough;
    /** Whether a reminder is scheduled for the next unsettled occurrence. */
    final boolean remind;
    /** How far before the due moment the reminder fires. */
    final long remindBeforeMs;

    /** An immutable store-backed list. contains() must perform an individual-date lookup. */
    abstract static class SettlementList extends AbstractList<Long> {}

    private static List<Long> freeze(List<Long> dates) {
        if (dates == null) return Collections.emptyList();
        if (dates instanceof SettlementList) return dates;
        return Collections.unmodifiableList(new ArrayList<>(dates));
    }

    Commitment(String id, String name, long amount, int frequency, long start, Long end,
            boolean done, List<Long> paid, boolean remind, long remindBeforeMs) {
        this(id, name, amount, frequency, start, end, done, paid, 0, remind, remindBeforeMs);
    }

    Commitment(String id, String name, long amount, int frequency, long start, Long end,
            boolean done, List<Long> paid, long legacyPaidThrough, boolean remind,
            long remindBeforeMs) {
        this(id, name, amount, frequency, start, end, done, paid, legacyPaidThrough,
            null, remind, remindBeforeMs);
    }

    Commitment(String id, String name, long amount, int frequency, long start, Long end,
            boolean done, List<Long> paid, long legacyPaidThrough, List<Long> unpaid,
            boolean remind, long remindBeforeMs) {
        this.id = id;
        this.name = name;
        this.amount = amount;
        this.frequency = frequency;
        this.start = start;
        this.end = end;
        this.done = done;
        this.paid = freeze(paid);
        this.unpaid = freeze(unpaid);
        this.legacyPaidThrough = legacyPaidThrough;
        this.remind = remind;
        this.remindBeforeMs = remindBeforeMs;
    }

    /** A fresh commitment with a stable identity, normalized the way the store keeps it. */
    static Commitment create(String name, long amount, int frequency, long start, Long end,
            boolean remind, long remindBeforeMs) {
        return normalized(new Commitment(UUID.randomUUID().toString(), name, amount, frequency,
            start, end, false, null, remind, remindBeforeMs));
    }

    /** Valid nonzero signed long amounts, names, ids, positive dates and nonnegative lead times
     *  have no retention limits. Reject invalid definitions rather than truncating their data. */
    static Commitment normalized(Commitment c) {
        if (c == null || c.id == null || c.id.isEmpty()) return null;
        String name = c.name;
        if (name == null || name.trim().isEmpty()) return null;
        if (c.amount == 0 || c.frequency < ONCE || c.frequency > YEARLY) return null;
        int frequency = c.frequency;
        if (c.start <= 0) return null;
        Long end = c.end;
        if (end != null && end < startOfDay(c.start)) return null;
        if (c.legacyPaidThrough < 0 || c.remindBeforeMs < 0) return null;
        List<Long> paid = normalizeDates(c.paid);
        List<Long> unpaid = normalizeDates(c.unpaid);
        if (paid == null || unpaid == null) return null;
        if (!(paid instanceof SettlementList) && !(unpaid instanceof SettlementList)
                && !Collections.disjoint(paid, unpaid)) return null;
        return new Commitment(c.id, name, c.amount, frequency, c.start, end,
            c.done, paid, c.legacyPaidThrough, unpaid, c.remind, c.remindBeforeMs);
    }

    private static List<Long> normalizeDates(List<Long> dates) {
        if (dates instanceof SettlementList) return dates;
        TreeSet<Long> sorted = new TreeSet<>();
        if (dates != null) {
            for (Long date : dates) {
                if (date == null || date <= 0) return null;
                sorted.add(date);
            }
        }
        return new ArrayList<>(sorted);
    }

    /** Whether the occurrence due at {@code dateMs} (an occurrence this class expanded) is
     *  settled: a finished one-time commitment, or a recurring due the user marked. */
    boolean isSettled(long dateMs) {
        if (frequency == ONCE) return done;
        if (unpaid.contains(dateMs)) return false;
        return paid.contains(dateMs) || (legacyPaidThrough > 0 && dateMs <= legacyPaidThrough);
    }

    /** True for money the user pays out, false for money the user receives. */
    boolean isPayment() {
        return amount < 0;
    }

    /**
     * The signed total of every child in a finite series. A recurring commitment without an end
     * date has no finite total and returns {@code null}; its per-occurrence amount remains useful
     * to show in the management list. A finite total that cannot fit the stored amount type also
     * returns {@code null} rather than overflowing into a misleading value.
     */
    static Long totalAmount(Commitment c, CalendarSystem cal) {
        if (c == null || cal == null || c.end == null && c.frequency != ONCE) return null;
        try {
            long occurrences = countOccurrences(c, cal);
            if (occurrences <= 0) return null;
            return Math.multiplyExact(occurrences, c.amount);
        } catch (ArithmeticException overflow) {
            return null;
        }
    }

    /** Constant-space, constant-time count, including very long finite schedules. */
    private static long countOccurrences(Commitment c, CalendarSystem cal) {
        if (c.frequency == ONCE) return 1;
        int[] anchor = civilDay(c.start, cal);
        int[] last = civilDay(c.end, cal);
        if (compare(anchor, last) > 0) return 0;
        if (c.frequency == DAILY || c.frequency == WEEKLY) {
            long days = gregorianDay(c.end).toEpochDay() - gregorianDay(c.start).toEpochDay();
            return days / (c.frequency == WEEKLY ? 7 : 1) + 1;
        }
        long steps;
        int[] cursor;
        if (c.frequency == MONTHLY) {
            steps = (last[0] - (long) anchor[0]) * 12 + last[1] - anchor[1];
            cursor = monthCursor(anchor, steps, cal);
        } else {
            steps = last[0] - (long) anchor[0];
            cursor = new int[]{last[0], anchor[1],
                Math.min(anchor[2], cal.daysInMonth(last[0], anchor[1]))};
        }
        return steps + (compare(cursor, last) <= 0 ? 1 : 0);
    }

    private static LocalDate gregorianDay(long millis) {
        int[] civil = civilDay(millis, CalendarSystem.GREGORIAN);
        return LocalDate.of(civil[0], civil[1], civil[2]);
    }

    JSONObject toJson() {
        return json(true);
    }

    /** Definition-only payload for SQLite; settlement arrays live in separate encrypted rows. */
    JSONObject definitionJson() {
        return json(false);
    }

    private JSONObject json(boolean includeSettlements) {
        JSONObject e = new JSONObject();
        try {
            e.put("id", id);
            e.put("name", name);
            e.put("amount", amount);
            e.put("freq", frequency);
            e.put("start", start);
            if (end != null) e.put("end", end.longValue());
            if (done) e.put("done", true);
            if (includeSettlements && !paid.isEmpty()) {
                org.json.JSONArray settled = new org.json.JSONArray();
                for (Long day : paid) settled.put(day.longValue());
                e.put("paid", settled);
            }
            if (includeSettlements && !unpaid.isEmpty()) {
                org.json.JSONArray reopened = new org.json.JSONArray();
                for (Long day : unpaid) reopened.put(day.longValue());
                e.put("unpaid", reopened);
            }
            if (legacyPaidThrough > 0) e.put("paidThrough", legacyPaidThrough);
            if (remind) e.put("remind", true);
            if (remindBeforeMs > 0) e.put("remindBefore", remindBeforeMs);
        } catch (Exception ex) {
            throw new IllegalStateException("commitment serialization failed", ex);
        }
        return e;
    }

    static Commitment fromJson(JSONObject e) {
        try {
            Object id = e.get("id"), name = e.get("name");
            if (!(id instanceof String) || !(name instanceof String)) return null;
            long amount = exactLong(e.get("amount"));
            long freq = e.has("freq") ? exactLong(e.get("freq")) : ONCE;
            if (freq < ONCE || freq > YEARLY) return null;
            long start = exactLong(e.get("start"));
            Long end = e.has("end") && !e.isNull("end") ? exactLong(e.get("end")) : null;
            List<Long> paid = datesFromJson(e, "paid");
            List<Long> unpaid = datesFromJson(e, "unpaid");
            long legacyPaidThrough = e.has("paidThrough") ? exactLong(e.get("paidThrough")) : 0;
            long lead = e.has("remindBefore") ? exactLong(e.get("remindBefore")) : 0;
            if (legacyPaidThrough < 0 || lead < 0) return null;
            return normalized(new Commitment((String) id, (String) name, amount, (int) freq,
                start, end, booleanFromJson(e, "done"), paid, legacyPaidThrough, unpaid,
                booleanFromJson(e, "remind"), lead));
        } catch (Exception ex) {
            return null;
        }
    }

    /** JSONObject.getLong coerces overflowing/fractional doubles; never use it for money/dates. */
    static long exactLong(Object value) {
        if (value instanceof Long || value instanceof Integer || value instanceof Short
                || value instanceof Byte) return ((Number) value).longValue();
        if (value instanceof String) return Long.parseLong((String) value);
        throw new IllegalArgumentException("expected exact long");
    }

    private static boolean booleanFromJson(JSONObject e, String key) throws Exception {
        if (!e.has(key)) return false;
        Object value = e.get(key);
        if (!(value instanceof Boolean)) throw new IllegalArgumentException("expected boolean");
        return (Boolean) value;
    }

    private static List<Long> datesFromJson(JSONObject e, String key) throws Exception {
        List<Long> dates = new ArrayList<>();
        if (!e.has(key)) return dates;
        org.json.JSONArray array = e.getJSONArray(key);
        for (int i = 0; i < array.length(); i++) {
            long date = exactLong(array.get(i));
            if (date <= 0) throw new IllegalArgumentException("invalid settlement date");
            dates.add(date);
        }
        return dates;
    }

    // ====================================================================
    // Occurrences
    // ====================================================================

    /** Every due day of this commitment inside {@code [fromMs, toMs]}, as start-of-day millis in
     *  the viewing calendar and in chronological order. Both bounds are civil days: anything due
     *  on the boundary day is included. Unbounded work is bounded by the caller asking for a
     *  window — an open-ended daily commitment expands to a handful of integers per day, so even
     *  a decade-wide window costs nothing. */
    static List<Long> occurrences(Commitment c, CalendarSystem cal, long fromMs, long toMs) {
        List<Long> out = new ArrayList<>();
        visitOccurrences(c, cal, fromMs, toMs, out::add);
        return out;
    }

    /** Visits due days without allocating a list, for screens that only need aggregates or a small
     *  visible window. */
    static void visitOccurrences(Commitment c, CalendarSystem cal, long fromMs, long toMs,
            OccurrenceVisitor visitor) {
        visitOccurrences(c, cal, fromMs, toMs, visitor, -1);
    }

    static void visitOccurrences(Commitment c, CalendarSystem cal, long fromMs, long toMs,
            OccurrenceVisitor visitor, int maxVisits) {
        if (c == null || cal == null || visitor == null || toMs < fromMs) return;
        int[] anchor = civilDay(c.start, cal);
        int[] end = c.end == null || c.end > toMs ? null : civilDay(c.end, cal);
        long fromDay = startOfDay(fromMs);
        long toDay = startOfDay(toMs);
        if (c.frequency == ONCE) {
            long at = millisOf(anchor[0], anchor[1], anchor[2], cal);
            if (at >= fromDay && at <= toDay) visitor.visit(at);
            return;
        }
        int[] cursor = firstCursor(c, cal, anchor, fromDay);
        int[] last = civilDay(toDay, cal);
        int visited = 0;
        while (compare(cursor, last) <= 0) {
            if (end != null && compare(cursor, end) > 0) break;
            long at = millisOf(cursor[0], cursor[1], cursor[2], cal);
            if (at >= fromDay && at <= toDay) {
                visitor.visit(at);
                if (maxVisits > 0 && ++visited >= maxVisits) return;
            }
            step(cursor, c.frequency, cal, anchor[2]);
        }
    }

    /** Positions the recurrence cursor at the first possible due day in the requested window. */
    private static int[] firstCursor(Commitment c, CalendarSystem cal, int[] anchor,
            long fromDay) {
        int[] cursor = {anchor[0], anchor[1], anchor[2]};
        long anchorAt = millisOf(anchor[0], anchor[1], anchor[2], cal);
        if (fromDay <= anchorAt) return cursor;
        int[] target = civilDay(fromDay, cal);
        if (c.frequency == DAILY || c.frequency == WEEKLY) {
            int stepDays = c.frequency == WEEKLY ? 7 : 1;
            long distance = gregorianDay(fromDay).toEpochDay() - gregorianDay(anchorAt).toEpochDay();
            long steps = (distance + stepDays - 1) / stepDays;
            return civilDay(dailyStepAt(anchor, cal, steps, stepDays), cal);
        }
        if (c.frequency == MONTHLY) {
            long months = (target[0] - (long) anchor[0]) * 12 + target[1] - anchor[1];
            if (months > 0) cursor = monthCursor(anchor, months, cal);
        } else if (c.frequency == YEARLY && target[0] > anchor[0]) {
            cursor[0] = target[0];
            cursor[2] = Math.min(anchor[2], cal.daysInMonth(cursor[0], cursor[1]));
        }
        while (millisOf(cursor[0], cursor[1], cursor[2], cal) < fromDay)
            step(cursor, c.frequency, cal, anchor[2]);
        return cursor;
    }

    private static long dailyStepAt(int[] anchor, CalendarSystem cal, long steps, int stepDays) {
        LocalDate day = gregorianDay(millisOf(anchor[0], anchor[1], anchor[2], cal))
            .plusDays(Math.multiplyExact(steps, stepDays));
        return millisOf(day.getYear(), day.getMonthValue(), day.getDayOfMonth(), CalendarSystem.GREGORIAN);
    }

    private static int[] monthCursor(int[] anchor, long months, CalendarSystem cal) {
        long index = anchor[0] * 12L + anchor[1] - 1 + months;
        int year = (int) (index / 12);
        int month = (int) (index % 12) + 1;
        return new int[]{year, month, Math.min(anchor[2], cal.daysInMonth(year, month))};
    }

    /** The next unsettled due day: the earliest unsettled occurrence on or after today, or the
     *  earliest unsettled overdue one when everything ahead is settled. Null when nothing is
     *  left (a finished one-time commitment, or an ended schedule whose dues are all settled).
     *  Looks back at most a year for the overdue case and forward two, so a long-settled daily
     *  schedule does not expand its whole history to answer. */
    static Long nextDue(Commitment c, CalendarSystem cal, long nowMs) {
        if (c == null || cal == null) return null;
        long today = startOfDay(nowMs);
        if (c.frequency == ONCE) return c.done ? null : startOfDay(c.start);
        List<Long> ahead = occurrences(c, cal, today, today + 730L * 86400000L);
        for (long at : ahead) if (!c.isSettled(at)) return at;
        List<Long> behind = occurrences(c, cal, today - 366L * 86400000L, today - 1);
        Long overdue = null;
        for (long at : behind) if (!c.isSettled(at)) overdue = at;
        return overdue;
    }

    /** When the reminder for the next unsettled due fires, or null when reminders are off or
     *  nothing is left. A lead time that already passed (an overdue due, or editing the lead
     *  time late) fires imminently instead of never. */
    static Long reminderAt(Commitment c, CalendarSystem cal, long nowMs) {
        if (c == null || !c.remind) return null;
        Long due = nextDue(c, cal, nowMs);
        if (due == null) return null;
        long at = due - Math.max(0, c.remindBeforeMs);
        return Math.max(at, nowMs);
    }

    /** Advances a civil cursor by one schedule step. Month and year steps keep the anchor's day
     *  clamped into short months (the 31st lands on the 30th/28th), so the schedule never skips
     *  a month for starting on a long day. */
    private static void step(int[] cursor, int frequency, CalendarSystem cal, int anchorDay) {
        switch (frequency) {
            case DAILY:
                addDays(cursor, 1, cal);
                break;
            case WEEKLY:
                addDays(cursor, 7, cal);
                break;
            case MONTHLY: {
                int month = cursor[1] + 1;
                int year = cursor[0];
                if (month > 12) {
                    month = 1;
                    year++;
                }
                cursor[0] = year;
                cursor[1] = month;
                cursor[2] = Math.min(anchorDay, cal.daysInMonth(year, month));
                break;
            }
            default: {
                cursor[0]++;
                cursor[2] = Math.min(anchorDay, cal.daysInMonth(cursor[0], cursor[1]));
                break;
            }
        }
    }

    private static void addDays(int[] cursor, int n, CalendarSystem cal) {
        int day = cursor[2] + n;
        while (day > cal.daysInMonth(cursor[0], cursor[1])) {
            day -= cal.daysInMonth(cursor[0], cursor[1]);
            cursor[1]++;
            if (cursor[1] > 12) {
                cursor[1] = 1;
                cursor[0]++;
            }
        }
        cursor[2] = day;
    }

    private static int compare(int[] a, int[] b) {
        if (a[0] != b[0]) return a[0] < b[0] ? -1 : 1;
        if (a[1] != b[1]) return a[1] < b[1] ? -1 : 1;
        if (a[2] != b[2]) return a[2] < b[2] ? -1 : 1;
        return 0;
    }

    /** The civil (year, month, day) the given moment reads as in the viewing calendar. */
    static int[] civilDay(long millis, CalendarSystem cal) {
        Calendar g = Calendar.getInstance();
        g.setTimeInMillis(millis);
        int gy = g.get(Calendar.YEAR);
        int gm = g.get(Calendar.MONTH) + 1;
        int gd = g.get(Calendar.DAY_OF_MONTH);
        if (cal == CalendarSystem.JALALI) {
            JalaliCalendar j = JalaliCalendar.fromGregorian(gy, gm, gd);
            return new int[]{j.year, j.month, j.day};
        }
        return new int[]{gy, gm, gd};
    }

    /** Midnight starting the civil day in the viewing calendar, like the message dates. */
    static long millisOf(int year, int month, int day, CalendarSystem cal) {
        int[] g = cal == CalendarSystem.JALALI
            ? JalaliCalendar.of(year, month, day).toGregorian()
            : new int[]{year, month, day};
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(g[0], g[1] - 1, g[2], 0, 0, 0);
        return c.getTimeInMillis();
    }

    /** Midnight starting the moment's own day. */
    static long startOfDay(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
