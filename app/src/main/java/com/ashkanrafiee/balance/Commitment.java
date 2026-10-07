package com.ashkanrafiee.balance;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;
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

    /** Bounds applied at the write boundary (mirroring the tag store): names trim and cap, the
     *  amount must be non-zero and bounded, the end must not precede the start, the settled-day
     *  list caps so a hostile backup cannot bloat it, and the store itself caps how many
     *  commitments one device keeps, so one runaway import cannot bloat the encrypted store or
     *  the alarm table. */
    static final int MAX_NAME_LENGTH = 64;
    static final long MAX_AMOUNT = 999_999_999_999L;
    static final long MAX_REMIND_BEFORE_MS = 365L * 86400000L;
    static final int MAX_COMMITMENTS = 500;
    static final int MAX_SETTLED_DAYS = 2000;

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
    /** Compatibility with the first commitment build, which stored one recurring watermark.
     *  New marks always use {@link #paid}; this is retained only so an existing user's old
     *  settled history does not suddenly reappear as unpaid after upgrading. */
    final long legacyPaidThrough;
    /** Whether a reminder is scheduled for the next unsettled occurrence. */
    final boolean remind;
    /** How far before the due moment the reminder fires. */
    final long remindBeforeMs;

    Commitment(String id, String name, long amount, int frequency, long start, Long end,
            boolean done, List<Long> paid, boolean remind, long remindBeforeMs) {
        this(id, name, amount, frequency, start, end, done, paid, 0, remind, remindBeforeMs);
    }

    Commitment(String id, String name, long amount, int frequency, long start, Long end,
            boolean done, List<Long> paid, long legacyPaidThrough, boolean remind,
            long remindBeforeMs) {
        this.id = id;
        this.name = name;
        this.amount = amount;
        this.frequency = frequency;
        this.start = start;
        this.end = end;
        this.done = done;
        this.paid = paid == null
            ? java.util.Collections.<Long>emptyList()
            : java.util.Collections.unmodifiableList(new ArrayList<>(paid));
        this.legacyPaidThrough = Math.max(0, legacyPaidThrough);
        this.remind = remind;
        this.remindBeforeMs = remindBeforeMs;
    }

    /** A fresh commitment with a stable identity, normalized the way the store keeps it. */
    static Commitment create(String name, long amount, int frequency, long start, Long end,
            boolean remind, long remindBeforeMs) {
        return normalized(new Commitment(UUID.randomUUID().toString(), name, amount, frequency,
            start, end, false, null, remind, remindBeforeMs));
    }

    /** The write-boundary form: trims and caps the name, clamps the amount, lead time and
     *  frequency into range, drops an end that precedes the start, and keeps the settled-day
     *  list to positive days within its cap. Returns null when there is nothing worth keeping
     *  (a blank name or a zero amount), so the store never holds one. */
    static Commitment normalized(Commitment c) {
        if (c == null || c.id == null || c.id.isEmpty()) return null;
        String name = c.name == null ? "" : c.name.trim();
        if (name.length() > MAX_NAME_LENGTH) name = name.substring(0, MAX_NAME_LENGTH).trim();
        if (name.isEmpty()) return null;
        if (c.amount == 0 || Math.abs(c.amount) > MAX_AMOUNT) return null;
        int frequency = c.frequency < ONCE || c.frequency > YEARLY ? ONCE : c.frequency;
        if (c.start <= 0) return null;
        Long end = c.end;
        if (end != null && end < startOfDay(c.start)) end = null;
        List<Long> paid = new ArrayList<>();
        if (c.paid != null) {
            for (Long day : c.paid) {
                if (day == null || day <= 0 || paid.contains(day)) continue;
                paid.add(day);
            }
            paid.sort(null);
            // The recent marks are the live ones (undo, reminders); the oldest fall off first.
            while (paid.size() > MAX_SETTLED_DAYS) paid.remove(0);
        }
        long remindBefore = Math.max(0, Math.min(c.remindBeforeMs, MAX_REMIND_BEFORE_MS));
        return new Commitment(c.id, name, c.amount, frequency, c.start, end,
            frequency == ONCE && c.done, paid, c.legacyPaidThrough, c.remind, remindBefore);
    }

    /** Whether the occurrence due at {@code dateMs} (an occurrence this class expanded) is
     *  settled: a finished one-time commitment, or a recurring due the user marked. */
    boolean isSettled(long dateMs) {
        if (frequency == ONCE) return done;
        return paid.contains(dateMs) || (legacyPaidThrough > 0 && dateMs <= legacyPaidThrough);
    }

    /** True for money the user pays out, false for money the user receives. */
    boolean isPayment() {
        return amount < 0;
    }

    JSONObject toJson() {
        JSONObject e = new JSONObject();
        try {
            e.put("id", id);
            e.put("name", name);
            e.put("amount", amount);
            e.put("freq", frequency);
            e.put("start", start);
            if (end != null) e.put("end", end.longValue());
            if (done) e.put("done", true);
            if (paid != null && !paid.isEmpty()) {
                org.json.JSONArray settled = new org.json.JSONArray();
                for (Long day : paid) settled.put(day.longValue());
                e.put("paid", settled);
            }
            if (legacyPaidThrough > 0) e.put("paidThrough", legacyPaidThrough);
            if (remind) e.put("remind", true);
            if (remindBeforeMs > 0) e.put("remindBefore", remindBeforeMs);
        } catch (Exception ex) {
            return new JSONObject();
        }
        return e;
    }

    static Commitment fromJson(JSONObject e) {
        try {
            String id = e.optString("id", null);
            String name = e.optString("name", null);
            if (id == null || id.isEmpty() || name == null) return null;
            long amount = e.getLong("amount");
            int frequency = e.optInt("freq", ONCE);
            long start = e.getLong("start");
            Long end = e.has("end") && !e.isNull("end") ? e.getLong("end") : null;
            List<Long> paid = new ArrayList<>();
            org.json.JSONArray settled = e.optJSONArray("paid");
            if (settled != null) {
                for (int i = 0; i < settled.length(); i++) {
                    long day = settled.optLong(i, 0);
                    if (day > 0) paid.add(day);
                }
            }
            long legacyPaidThrough = e.optLong("paidThrough", 0);
            return normalized(new Commitment(id, name, amount, frequency, start, end,
                e.optBoolean("done", false), paid, legacyPaidThrough,
                e.optBoolean("remind", false), e.optLong("remindBefore", 0)));
        } catch (Exception ex) {
            return null;
        }
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
        if (c == null || cal == null || toMs < fromMs) return out;
        int[] anchor = civilDay(c.start, cal);
        int[] end = c.end == null ? null : civilDay(c.end, cal);
        int[] cursor = {anchor[0], anchor[1], anchor[2]};
        if (c.frequency == ONCE) {
            long at = millisOf(cursor[0], cursor[1], cursor[2], cal);
            if (at >= startOfDay(fromMs) && at <= startOfDay(toMs)) out.add(at);
            return out;
        }
        int[] last = civilDay(toMs, cal);
        while (compare(cursor, last) <= 0) {
            if (end != null && compare(cursor, end) > 0) break;
            long at = millisOf(cursor[0], cursor[1], cursor[2], cal);
            if (at >= startOfDay(fromMs)) out.add(at);
            step(cursor, c.frequency, cal, anchor[2]);
        }
        return out;
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
