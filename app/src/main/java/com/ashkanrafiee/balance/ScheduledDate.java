package com.ashkanrafiee.balance;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * A date without a time of day, together with the calendar in which it was entered.
 *
 * <p>Planner dates deliberately have a finite, documented horizon.  The arithmetic Jalali
 * conversion used by the application is reliable well outside the range people plan payments in;
 * the horizon is still useful here because it makes an open-ended plan finite without inventing an
 * arbitrary occurrence count.  The Gregorian horizon is 1600..2500 and the corresponding Jalali
 * horizon is 979..1878.  The date picker uses the narrower user-facing 1100..1700 / 1721..2322
 * range, while persisted data is validated against this safe internal range as well.
 */
final class ScheduledDate implements Comparable<ScheduledDate> {
    static final int MIN_GREGORIAN_YEAR = 1600;
    static final int MAX_GREGORIAN_YEAR = 2500;
    static final int MIN_JALALI_YEAR = 979;
    static final int MAX_JALALI_YEAR = 1878;

    final CalendarSystem calendar;
    final int year;
    final int month;
    final int day;

    ScheduledDate(CalendarSystem calendar, int year, int month, int day) {
        if (calendar == null || !yearInPlanningRange(calendar, year) || month < 1 || month > 12
                || day < 1 || day > calendar.daysInMonth(year, month)) {
            throw new IllegalArgumentException("Invalid scheduled date");
        }
        this.calendar = calendar;
        this.year = year;
        this.month = month;
        this.day = day;
    }

    static ScheduledDate fromGregorian(int year, int month, int day, CalendarSystem calendar) {
        if (year < MIN_GREGORIAN_YEAR || year > MAX_GREGORIAN_YEAR
                || month < 1 || month > 12 || day < 1
                || day > CalendarSystem.GREGORIAN.daysInMonth(year, month)) {
            throw new IllegalArgumentException("Gregorian date outside planning range");
        }
        if (calendar == CalendarSystem.JALALI) {
            JalaliCalendar j = JalaliCalendar.fromGregorian(year, month, day);
            return new ScheduledDate(calendar, j.year, j.month, j.day);
        }
        return new ScheduledDate(calendar, year, month, day);
    }

    int[] toGregorian() {
        return calendar.toGregorian(year, month, day);
    }

    /** A monotonic day number used only for comparisons across calendar systems. */
    long ordinal() {
        int[] g = toGregorian();
        long a = (14L - g[1]) / 12L;
        long y = g[0] + 4800L - a;
        long m = g[1] + 12L * a - 3L;
        return g[2] + (153L * m + 2L) / 5L + 365L * y + y / 4L - y / 100L + y / 400L - 32045L;
    }

    ScheduledDate plusDays(int days) {
        int[] g = toGregorian();
        GregorianCalendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.setLenient(false);
        c.set(g[0], g[1] - 1, g[2]);
        c.add(Calendar.DAY_OF_MONTH, days);
        return fromGregorian(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH), calendar);
    }

    static ScheduledDate latest(CalendarSystem calendar) {
        if (calendar == CalendarSystem.JALALI) {
            int month = 12;
            return new ScheduledDate(calendar, MAX_JALALI_YEAR, month,
                calendar.daysInMonth(MAX_JALALI_YEAR, month));
        }
        return new ScheduledDate(calendar, MAX_GREGORIAN_YEAR, 12, 31);
    }

    private static boolean yearInPlanningRange(CalendarSystem calendar, int year) {
        if (calendar == CalendarSystem.JALALI)
            return year >= MIN_JALALI_YEAR && year <= MAX_JALALI_YEAR;
        return year >= MIN_GREGORIAN_YEAR && year <= MAX_GREGORIAN_YEAR;
    }

    String key() {
        return calendar.tag() + ":" + year + ":" + month + ":" + day;
    }

    @Override public int compareTo(ScheduledDate other) {
        return Long.compare(ordinal(), other.ordinal());
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof ScheduledDate)) return false;
        ScheduledDate d = (ScheduledDate) other;
        return calendar == d.calendar && year == d.year && month == d.month && day == d.day;
    }

    @Override public int hashCode() {
        return (((calendar.ordinal() * 10000) + year) * 100 + month) * 100 + day;
    }
}
