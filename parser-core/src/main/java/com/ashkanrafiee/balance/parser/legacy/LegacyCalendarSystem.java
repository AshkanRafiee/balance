package com.ashkanrafiee.balance.parser.legacy;

import java.util.Calendar;

/** Legacy table tags: J = JALALI, G = GREGORIAN. Tags are case-sensitive; unknown/null is null. */
public enum LegacyCalendarSystem {
    JALALI, GREGORIAN;

    public String tag() { return this == JALALI ? "J" : "G"; }
    public static LegacyCalendarSystem ofTag(String tag) {
        for (LegacyCalendarSystem c : values()) if (c.tag().equals(tag)) return c;
        return null;
    }
    public boolean ownsYear(int year) {
        return this == JALALI ? year >= 1300 && year <= 1500 : year >= 1900 && year <= 2100;
    }
    public int daysInMonth(int year, int month) {
        if (this == JALALI) return LegacyJalaliCalendar.daysInMonth(year, month);
        switch (month) {
            case 2: return isLeapGregorian(year) ? 29 : 28;
            case 4: case 6: case 9: case 11: return 30;
            default: return 31;
        }
    }
    private static boolean isLeapGregorian(int year) {
        return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    }
    public int inferYear(long arrival, LegacyCalendarContext context) {
        Calendar c = context.newCalendar();
        c.setTimeInMillis(arrival);
        if (this != JALALI) return c.get(Calendar.YEAR);
        return LegacyJalaliCalendar.fromGregorian(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH)).year;
    }
    public int twoDigitYearBase() { return this == JALALI ? 1400 : 2000; }
    public int[] toGregorian(int year, int month, int day) {
        return this == JALALI ? LegacyJalaliCalendar.of(year, month, day).toGregorian()
            : new int[]{year, month, day};
    }
}
