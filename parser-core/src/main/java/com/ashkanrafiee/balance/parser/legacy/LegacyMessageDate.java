package com.ashkanrafiee.balance.parser.legacy;

import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Isolated legacy date selection, including first-match, lenient DST and arrival fallback quirks. */
public final class LegacyMessageDate {
    private LegacyMessageDate() {}
    public static final long FUTURE_SLACK_MS = 6 * 60 * 60 * 1000L;
    public static final long MAX_AGE_MS = 45L * 24 * 60 * 60 * 1000L;
    private static final Pattern CLOCK = Pattern.compile(
        "(?<![0-9])([0-9]{1,2}):([0-9]{2})(?::([0-9]{2}))?(?![0-9])");
    private static final Pattern FULL_YEAR_DATE = Pattern.compile(
        "(?<![0-9])([0-9]{4})[/.\\-]([0-9]{1,2})[/.\\-]([0-9]{1,2})(?![0-9])");
    private static final Pattern TWO_DIGIT_YEAR_DATE = Pattern.compile(
        "(?<![0-9])([0-9]{2})[/.]([0-9]{1,2})[/.]([0-9]{1,2})(?![0-9])");
    private static final Pattern COMPACT_DATE = Pattern.compile(
        "(?<![0-9])([0-9]{2})([0-9]{2})[^0-9]{1,3}([0-9]{1,2}):([0-9]{2})(?![0-9])");
    private static final Pattern SHORT_DATE = Pattern.compile(
        "(?<![0-9])([0-9]{1,2})[/.]([0-9]{1,2})(?![0-9])");

    public static long eventTime(String body, long arrival, LegacyCalendarSystem cal,
                                 LegacyCalendarContext context) {
        if (body == null) return arrival;
        String s = LegacyDigits.ascii(body);
        int[] clock = clock(s);
        Matcher full = FULL_YEAR_DATE.matcher(s);
        if (full.find()) {
            int year = Integer.parseInt(full.group(1));
            LegacyCalendarSystem stated = LegacyCalendarSystem.JALALI.ownsYear(year) ? LegacyCalendarSystem.JALALI
                : LegacyCalendarSystem.GREGORIAN.ownsYear(year) ? LegacyCalendarSystem.GREGORIAN : null;
            if (stated == null) return arrival;
            return orArrival(build(stated, year, false, Integer.parseInt(full.group(2)),
                Integer.parseInt(full.group(3)), clock, arrival, context), arrival);
        }
        Matcher compact = COMPACT_DATE.matcher(s);
        if (compact.find()) {
            int h = Integer.parseInt(compact.group(3));
            int m = Integer.parseInt(compact.group(4));
            if (h > 23 || m > 59) return arrival;
            return orArrival(build(cal, 0, true, Integer.parseInt(compact.group(1)),
                Integer.parseInt(compact.group(2)), new int[]{h, m}, arrival, context), arrival);
        }
        Matcher twoDigitYear = TWO_DIGIT_YEAR_DATE.matcher(s);
        if (twoDigitYear.find() && clock != null) {
            int yy = Integer.parseInt(twoDigitYear.group(1));
            return orArrival(build(cal, cal.twoDigitYearBase() + yy, false,
                Integer.parseInt(twoDigitYear.group(2)), Integer.parseInt(twoDigitYear.group(3)),
                clock, arrival, context), arrival);
        }
        if (clock != null) {
            Matcher shortDate = SHORT_DATE.matcher(s);
            if (shortDate.find()) {
                return orArrival(build(cal, 0, true, Integer.parseInt(shortDate.group(1)),
                    Integer.parseInt(shortDate.group(2)), clock, arrival, context), arrival);
            }
        }
        return arrival;
    }

    private static int[] clock(String s) {
        Matcher m = CLOCK.matcher(s);
        while (m.find()) {
            int h = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            if (h <= 23 && min <= 59) return new int[]{h, min};
        }
        return null;
    }

    private static long build(LegacyCalendarSystem cal, int year, boolean inferYear, int month, int day,
                              int[] clock, long arrival, LegacyCalendarContext context) {
        if (month < 1 || month > 12 || day < 1) return -1;
        if (inferYear) year = cal.inferYear(arrival, context);
        if (day > cal.daysInMonth(year, month)) return -1;
        long at = at(cal.toGregorian(year, month, day), clock, context);
        // Deliberately no second day validation after the inferred-year rollback.
        if (inferYear && at > arrival + FUTURE_SLACK_MS) {
            at = at(cal.toGregorian(year - 1, month, day), clock, context);
        }
        if (at > arrival + FUTURE_SLACK_MS) return -1;
        if (at < arrival - MAX_AGE_MS) return -1;
        return at;
    }
    private static long orArrival(long built, long arrival) { return built > 0 ? built : arrival; }
    private static long at(int[] gregorian, int[] clock, LegacyCalendarContext context) {
        Calendar c = context.newCalendar();
        c.clear();
        c.set(gregorian[0], gregorian[1] - 1, gregorian[2],
            clock == null ? 0 : clock[0], clock == null ? 0 : clock[1], 0);
        return c.getTimeInMillis();
    }
}
