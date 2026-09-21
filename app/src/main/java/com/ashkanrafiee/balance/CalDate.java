package com.ashkanrafiee.balance;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;

/**
 * An immutable calendar date (year, month, day) in whichever calendar system the app currently
 * uses: the Persian (Jalali) calendar for the Iran region, or the Gregorian calendar for the
 * International region. The system a date is interpreted in is carried explicitly by every
 * conversion (the {@code iran} flag), never assumed, so neither system is baked into the type.
 *
 * <p>Field-by-field operations (comparison, day equality, group keys) are identical across both
 * systems — only the conversions and the month lengths differ — which is why one value type
 * serves both.
 */
final class CalDate {
    final int year;
    final int month;
    final int day;

    private CalDate(int year, int month, int day) {
        this.year = year;
        this.month = month;
        this.day = day;
    }

    /** A date from its directly-known components. Callers must supply a real calendar day; the
     *  components are expected to come from a validated source, exactly like {@link
     *  JalaliCalendar#of}. */
    static CalDate of(int year, int month, int day) {
        return new CalDate(year, month, day);
    }

    /** Today's date in the given system, from the device clock. */
    static CalDate today(boolean iran) {
        Calendar c = Calendar.getInstance(Locale.getDefault());
        return fromGregorian(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH), iran);
    }

    /** Yesterday's date in the given system, from the device clock. */
    static CalDate yesterday(boolean iran) {
        Calendar c = Calendar.getInstance(Locale.getDefault());
        c.add(Calendar.DAY_OF_MONTH, -1);
        return fromGregorian(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH), iran);
    }

    /** Converts a (proleptic) Gregorian date to the given system. */
    static CalDate fromGregorian(int gYear, int gMonth, int gDay, boolean iran) {
        if (iran) {
            JalaliCalendar j = JalaliCalendar.fromGregorian(gYear, gMonth, gDay);
            return new CalDate(j.year, j.month, j.day);
        }
        return new CalDate(gYear, gMonth, gDay);
    }

    /** The corresponding (proleptic) Gregorian {@code {year, month, day}}. Gregorian dates map to
     *  themselves; Persian dates map through the arithmetic Jalali conversion. */
    int[] toGregorian(boolean iran) {
        if (iran) return JalaliCalendar.of(year, month, day).toGregorian();
        return new int[]{year, month, day};
    }

    /** Number of days in the given month of {@code year}, in the given system. */
    static int daysInMonth(int year, int month, boolean iran) {
        if (iran) return JalaliCalendar.daysInMonth(year, month);
        return new GregorianCalendar(year, month - 1, 1).getActualMaximum(Calendar.DAY_OF_MONTH);
    }

    /** Whether both dates name the same day, regardless of system (used to tag today/yesterday). */
    boolean sameDay(CalDate o) {
        return o != null && year == o.year && month == o.month && day == o.day;
    }

    /** Chronological order of two dates of the same system, compared field by field. */
    int compare(CalDate o) {
        if (year != o.year) return Integer.compare(year, o.year);
        if (month != o.month) return Integer.compare(month, o.month);
        return Integer.compare(day, o.day);
    }

    /** Stable group/expansion key "{year}/{month}/{day}". */
    String key() {
        return year + "/" + month + "/" + day;
    }

    /** The year band the month picker may navigate, per system: Persian history is meaningful
     *  roughly 1100-1700; Gregorian 1900-2100 already covers any SMS-era date. */
    static int minYear(boolean iran) {
        return iran ? 1100 : 1900;
    }

    static int maxYear(boolean iran) {
        return iran ? 1700 : 2100;
    }

    /** Column index (leading column = 0) of this date's weekday: Saturday-first for the Iran
     *  region, Monday-first (ISO) for International. */
    static int weekdayIndex(CalDate d, boolean iran) {
        int[] g = d.toGregorian(iran);
        Calendar c = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(g[0], g[1] - 1, g[2]);
        if (iran) return (c.get(Calendar.DAY_OF_WEEK) - Calendar.SATURDAY + 7) % 7;
        return (c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7;
    }
}