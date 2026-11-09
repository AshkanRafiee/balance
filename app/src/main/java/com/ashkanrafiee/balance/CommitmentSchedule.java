package com.ashkanrafiee.balance;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Instant;
import org.json.JSONObject;

/** A recurrence's civil dates and home zone, independent of the display calendar/device zone.
 * Due instants remain the exact settlement and notification identities across travel and restore. */
final class CommitmentSchedule {
    final CalendarSystem calendar;
    final ZoneId zone;
    final LocalDate first;
    final LocalDate last;

    CommitmentSchedule(CalendarSystem calendar, ZoneId zone, LocalDate first, LocalDate last) {
        if (calendar == null || zone == null || first == null
                || last != null && last.isBefore(first))
            throw new IllegalArgumentException("invalid commitment schedule");
        this.calendar = calendar;
        this.zone = zone;
        this.first = first;
        this.last = last;
    }

    static CommitmentSchedule capture(Commitment c, CalendarSystem calendar, ZoneId zone) {
        return new CommitmentSchedule(calendar, zone, day(c.start, zone),
            c.end == null ? null : day(c.end, zone));
    }

    static LocalDate day(long instant, ZoneId zone) {
        return Instant.ofEpochMilli(instant).atZone(zone).toLocalDate();
    }

    long instant(LocalDate date) { return date.atStartOfDay(zone).toInstant().toEpochMilli(); }

    private int[] civil(LocalDate date) {
        if (calendar == CalendarSystem.GREGORIAN)
            return new int[]{date.getYear(), date.getMonthValue(), date.getDayOfMonth()};
        JalaliCalendar j = JalaliCalendar.fromGregorian(date.getYear(), date.getMonthValue(),
            date.getDayOfMonth());
        return new int[]{j.year, j.month, j.day};
    }

    private LocalDate date(int year, int month, int day) {
        int[] g = calendar.toGregorian(year, month, day);
        return LocalDate.of(g[0], g[1], g[2]);
    }

    LocalDate at(long index, int frequency) {
        if (frequency == Commitment.ONCE) return first;
        if (frequency == Commitment.DAILY || frequency == Commitment.WEEKLY)
            return first.plusDays(Math.multiplyExact(index,
                frequency == Commitment.WEEKLY ? 7L : 1L));
        int[] anchor = civil(first);
        long monthIndex = anchor[0] * 12L + anchor[1] - 1
            + Math.multiplyExact(index, frequency == Commitment.YEARLY ? 12L : 1L);
        int year = Math.toIntExact(Math.floorDiv(monthIndex, 12));
        int month = (int) Math.floorMod(monthIndex, 12) + 1;
        return date(year, month, Math.min(anchor[2], calendar.daysInMonth(year, month)));
    }

    private long indexAt(LocalDate date, int frequency) {
        if (frequency == Commitment.ONCE || date.isBefore(first)) return 0;
        if (frequency == Commitment.DAILY || frequency == Commitment.WEEKLY)
            return (date.toEpochDay() - first.toEpochDay())
                / (frequency == Commitment.WEEKLY ? 7 : 1);
        int[] a = civil(first), b = civil(date);
        long months = (b[0] - (long) a[0]) * 12 + b[1] - a[1];
        return frequency == Commitment.YEARLY ? months / 12 : months;
    }

    boolean contains(long due, int frequency) {
        LocalDate date = day(due, zone);
        return !date.isBefore(first) && (last == null || !date.isAfter(last))
            && instant(date) == due && at(indexAt(date, frequency), frequency).equals(date);
    }

    long count(int frequency) {
        if (frequency == Commitment.ONCE) return 1;
        long index = indexAt(last, frequency);
        return index + (at(index, frequency).isAfter(last) ? 0 : 1);
    }

    void visit(int frequency, long from, long to, Commitment.OccurrenceVisitor visitor, int max) {
        long index = indexAt(day(from, zone), frequency);
        int visited = 0;
        while (true) {
            LocalDate date = at(index, frequency);
            if (last != null && date.isAfter(last)) return;
            long due = instant(date);
            if (due > to) return;
            if (due >= from) {
                visitor.visit(due);
                if (max > 0 && ++visited >= max) return;
            }
            if (frequency == Commitment.ONCE) return;
            index = Math.incrementExact(index);
        }
    }

    JSONObject toJson() {
        try {
            JSONObject json = new JSONObject().put("version", 1).put("calendar", calendar.tag())
                .put("zone", zone.getId()).put("first", first.toString());
            if (last != null) json.put("last", last.toString());
            return json;
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    static CommitmentSchedule fromJson(JSONObject json) throws Exception {
        if (Commitment.exactLong(json.get("version")) != 1)
            throw new IllegalArgumentException("unsupported commitment schedule");
        // Do not coerce malformed values or let TimeZone silently replace an unknown ID with GMT.
        Object cal = json.get("calendar"), zone = json.get("zone"), first = json.get("first");
        if (!(cal instanceof String) || !(zone instanceof String) || !(first instanceof String))
            throw new IllegalArgumentException("invalid commitment schedule");
        Object last = json.has("last") && !json.isNull("last") ? json.get("last") : null;
        if (last != null && !(last instanceof String))
            throw new IllegalArgumentException("invalid commitment end day");
        return new CommitmentSchedule(CalendarSystem.ofTag((String) cal), ZoneId.of((String) zone),
            LocalDate.parse((String) first), last == null ? null : LocalDate.parse((String) last));
    }
}
