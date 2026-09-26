package com.ashkanrafiee.balance;

import java.util.Calendar;

/**
 * The calendar a bank writes its dates in.
 *
 * <p>Almost every date in a bank message is written in that bank's own calendar, and a date whose
 * year is written out is self-describing: a Persian year reads 13xx/14xx where a Gregorian one reads
 * 19xx/20xx. The layouts that state no year are not — {@code 06/29} is a perfectly ordinary day in
 * both calendars, and they are about three months apart, so reading one in the wrong calendar files
 * a movement in the wrong month and can strand a real residual. Nothing in the message itself can
 * settle it, so the bank says which calendar it uses and the year-less layouts are read in that one.
 *
 * <p>Every supported bank declares its calendar, so a bank outside Iran can be added by declaring
 * {@link #GREGORIAN} rather than by having its dates quietly assumed. Adding a third calendar means
 * adding a constant here and nothing else: the parser asks this type to validate a day, to infer a
 * year, and to convert to the Gregorian fields the rest of the app stores dates in.
 */
enum CalendarSystem {

    /** The Persian calendar, which Iranian banks write their dates in. */
    JALALI,

    /** The Gregorian calendar, for a bank that states its dates the way most of the world does. */
    GREGORIAN;

    /** The first year of this calendar's numbering that a bank could plausibly state. Iranian banks
     *  are a few centuries either side of 1400; a Gregorian one is nowhere near 2000. */
    private static final int JALALI_YEAR_FLOOR = 1300, JALALI_YEAR_CEILING = 1500;
    private static final int GREGORIAN_YEAR_FLOOR = 1900, GREGORIAN_YEAR_CEILING = 2100;

    /** The century a two-digit year is counted from: {@code 05} is 1405, or 2005. */
    private static final int JALALI_CENTURY = 1400, GREGORIAN_CENTURY = 2000;

    /** How a bank rule row declares this calendar, short enough to keep a rules table readable. */
    String tag() {
        return this == JALALI ? "J" : "G";
    }

    /** The calendar a rule row declares, or null when it declares neither. */
    static CalendarSystem ofTag(String tag) {
        for (CalendarSystem c : values()) if (c.tag().equals(tag)) return c;
        return null;
    }

    /**
     * Whether a stated four-digit year belongs to this calendar's numbering, so a year that plainly
     * came from somewhere else is not read as a date in this one.
     */
    boolean ownsYear(int year) {
        return this == JALALI
            ? year >= JALALI_YEAR_FLOOR && year <= JALALI_YEAR_CEILING
            : year >= GREGORIAN_YEAR_FLOOR && year <= GREGORIAN_YEAR_CEILING;
    }

    /**
     * The number of days in a month, so a day that does not exist is dropped rather than rolled over
     * into the next one. {@link Calendar} would happily turn February 30th into March 2nd on its own,
     * which would file a movement on a day the bank never mentioned.
     */
    int daysInMonth(int year, int month) {
        if (this == JALALI) return JalaliCalendar.daysInMonth(year, month);
        switch (month) {
            case 2: return isLeapGregorian(year) ? 29 : 28;
            case 4: case 6: case 9: case 11: return 30;
            default: return 31;
        }
    }

    /** A Gregorian year is a leap year when it divides by four, except centuries, except every fourth. */
    private static boolean isLeapGregorian(int year) {
        return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    }

    /**
     * The year a message that arrived at {@code arrival} most likely meant, for a layout that stated
     * a month and a day but no year: the year the arrival falls in, which
     * {@link MessageDate} then steps back if that day has not happened yet this year.
     */
    int inferYear(long arrival) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(arrival);
        if (this != JALALI) return c.get(Calendar.YEAR);
        return JalaliCalendar.fromGregorian(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH)).year;
    }

    /** The century a two-digit year counts from, so {@code 05/06/30} can be read at all. */
    int twoDigitYearBase() {
        return this == JALALI ? JALALI_CENTURY : GREGORIAN_CENTURY;
    }

    /** This calendar's year/month/day as the Gregorian fields the rest of the app stores dates in. */
    int[] toGregorian(int year, int month, int day) {
        return this == JALALI
            ? JalaliCalendar.of(year, month, day).toGregorian()
            : new int[]{year, month, day};
    }
}
