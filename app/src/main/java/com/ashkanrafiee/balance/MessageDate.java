package com.ashkanrafiee.balance;

import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When the money moved, as distinct from when the phone was told about it.
 *
 * <p>Almost every bank states the time of the event inside the message, usually on the last line.
 * That is the only thing that can place a message in the right period, because a message that
 * arrives late carries an arrival timestamp of "now" — which would file an old movement under
 * today, hide it from the bracket it belongs to, and let the unaccounted-money detector report a
 * gap for money we already hold. So the stated time is the primary source and the arrival
 * timestamp is only the fallback.
 *
 * <p>A year written out is self-describing: a Persian year reads 13xx/14xx where a Gregorian one
 * reads 19xx/20xx, so those layouts need no declaration and an Iranian bank that states a Gregorian
 * date is still read correctly. The year-less layouts ({@code 05/26 08:22}, {@code 0620-21:16})
 * are not self-describing, and the two calendars run about three months apart, so the bank's
 * declared {@link CalendarSystem} decides how they are read and they take their year from the most
 * recent occurrence that is not in the future.
 *
 * <p>Every candidate is checked against the arrival time before it is believed, so a number that
 * merely looks like a date (an amount, a reference, a card number) can never move a movement to a
 * nonsense date: anything unparseable or implausible falls back to the arrival timestamp, which is
 * exactly what this class did before it existed.
 */
final class MessageDate {

    private MessageDate() {}

    /** How far ahead of the arrival a stated time may sit before it is disbelieved. Device clocks
     *  and carrier timestamps drift by minutes, not days. */
    private static final long FUTURE_SLACK_MS = 6 * 60 * 60 * 1000L;

    /** How far back a stated time may sit before it is disbelieved, and left at the arrival instead.
     *
     *  <p>This is the bound that keeps a message from rewriting history it has no standing to
     *  rewrite. The time in the body is the bank's own claim: a delayed message, a re-sent
     *  statement, a garbled body and a forged SMS are indistinguishable here, so the app trusts it
     *  only over the span in which a message genuinely travels late — hours of carrier delay, a
     *  phone out of coverage for a fortnight, a bank resending a monthly statement. Anything older
     *  than that is not a message that arrived late, it is a claim about a period the app has no
     *  evidence for, and believing it would let one unverifiable message reopen a settled bracket
     *  and make a real gap disappear. */
    private static final long MAX_AGE_MS = 45L * 24 * 60 * 60 * 1000L;

    /** A clock time, with or without seconds: {@code 20:16}, {@code 14:07:22}. The seconds are
     *  optional, so the hour is taken from the leftmost field either way — matching the trailing
     *  {@code 07:22} of {@code 14:07:22} would silently shift the message by seven hours. */
    private static final Pattern CLOCK = Pattern.compile(
        "(?<![0-9])([0-9]{1,2}):([0-9]{2})(?::([0-9]{2}))?(?![0-9])");

    /** A full year, so the calendar is not a guess: {@code 1405/06/07}, {@code 1405.6.15},
     *  {@code 2026-06-22}. Persian and Arabic digits are normalized before matching. */
    private static final Pattern FULL_YEAR_DATE = Pattern.compile(
        "(?<![0-9])([0-9]{4})[/.\\-]([0-9]{1,2})[/.\\-]([0-9]{1,2})(?![0-9])");

    /** A two-digit year in front, the way Bank Mellat writes it: {@code 05/06/30-08:53} is
     *  6/30 of this Persian year at 08:53. Read before {@link #SHORT_DATE}, which would take the
     *  leading {@code 05} for a month. */
    private static final Pattern TWO_DIGIT_YEAR_DATE = Pattern.compile(
        "(?<![0-9])([0-9]{2})[/.]([0-9]{1,2})[/.]([0-9]{1,2})(?![0-9])");

    /** Melli's layout, where the day and the clock are glued to the date: {@code 0620-21:16} is
     *  6/20 at 21:16. Tried before {@link #SHORT_DATE}, which would otherwise read the leading
     *  {@code 06} as a bare month. */
    private static final Pattern COMPACT_DATE = Pattern.compile(
        "(?<![0-9])([0-9]{2})([0-9]{2})[^0-9]{1,3}([0-9]{1,2}):([0-9]{2})(?![0-9])");

    /** A year-less Persian date: {@code 05/26}, {@code 6.15}. Only believed next to a clock, since
     *  on its own a shape like {@code 1.5} is just as likely to be a number. */
    private static final Pattern SHORT_DATE = Pattern.compile(
        "(?<![0-9])([0-9]{1,2})[/.]([0-9]{1,2})(?![0-9])");

    /** The time of the event, or {@code arrival} when the message states none we can believe.
     *
     *  @param cal the calendar {@code body} is written in, for the layouts that do not state a year
     *             and so cannot say it themselves */
    static long eventTime(String body, long arrival, CalendarSystem cal) {
        if (body == null) return arrival;
        String s = Digits.ascii(body);
        int[] clock = clock(s);

        Matcher full = FULL_YEAR_DATE.matcher(s);
        if (full.find()) {
            int year = Integer.parseInt(full.group(1));
            // A four-digit year says which calendar it belongs to, so it is read as that one even
            // when the bank declares the other: an Iranian bank is known to state Gregorian dates.
            CalendarSystem stated = CalendarSystem.JALALI.ownsYear(year) ? CalendarSystem.JALALI
                : CalendarSystem.GREGORIAN.ownsYear(year) ? CalendarSystem.GREGORIAN : null;
            if (stated == null) return arrival;
            return orArrival(build(stated, year, false, month(full.group(2)),
                day(full.group(3)), clock, arrival), arrival);
        }

        // The year-less layouts are only trusted alongside a clock time, which is what every real
        // message of theirs carries and what no stray number does.
        Matcher compact = COMPACT_DATE.matcher(s);
        if (compact.find()) {
            int h = Integer.parseInt(compact.group(3));
            int m = Integer.parseInt(compact.group(4));
            if (h > 23 || m > 59) return arrival;
            return orArrival(build(cal, 0, true, month(compact.group(1)),
                day(compact.group(2)), new int[]{h, m}, arrival), arrival);
        }

        Matcher twoDigitYear = TWO_DIGIT_YEAR_DATE.matcher(s);
        if (twoDigitYear.find() && clock != null) {
            int yy = Integer.parseInt(twoDigitYear.group(1));
            return orArrival(build(cal, cal.twoDigitYearBase() + yy, false,
                month(twoDigitYear.group(2)), day(twoDigitYear.group(3)), clock, arrival), arrival);
        }

        if (clock != null) {
            Matcher shortDate = SHORT_DATE.matcher(s);
            if (shortDate.find()) {
                return orArrival(build(cal, 0, true, month(shortDate.group(1)),
                    day(shortDate.group(2)), clock, arrival), arrival);
            }
        }

        return arrival;
    }

    private static int month(String group) {
        return Integer.parseInt(group);
    }

    private static int day(String group) {
        return Integer.parseInt(group);
    }

    /** The first plausible clock time in the message as {@code {hour, minute}}, or null. */
    private static int[] clock(String s) {
        Matcher m = CLOCK.matcher(s);
        while (m.find()) {
            int h = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            if (h <= 23 && min <= 59) return new int[]{h, min};
        }
        return null;
    }

    /**
     * Turns calendar components into a timestamp, or returns -1 when they are not a real date or
     * the result cannot be true for a message that arrived at {@code arrival}.
     *
     * @param year the stated year, or 0 to infer the most recent one not in the future
     */
    private static long build(CalendarSystem cal, int year, boolean inferYear, int month, int day,
                              int[] clock, long arrival) {
        if (month < 1 || month > 12 || day < 1) return -1;
        if (inferYear) year = cal.inferYear(arrival);
        // Checked against the calendar the bank writes in, so a day that does not exist there is
        // dropped instead of being rolled into the next month by a lenient Calendar.
        if (day > cal.daysInMonth(year, month)) return -1;

        long at = at(cal.toGregorian(year, month, day), clock);
        // A year inferred from the arrival can overshoot into the future by a few months (today's
        // 5/26 has not happened yet this year), so step back a year before giving up.
        if (inferYear && at > arrival + FUTURE_SLACK_MS) {
            at = at(cal.toGregorian(year - 1, month, day), clock);
        }
        if (at > arrival + FUTURE_SLACK_MS) return -1;
        if (at < arrival - MAX_AGE_MS) return -1;
        return at;
    }

    /**
     * A date the message did state is either believed or dropped; it is never re-read as a
     * different layout. Reinterpreting the same digits after rejecting them is how a statement from
     * a decade ago turns into a plausible-looking date from this year.
     */
    private static long orArrival(long built, long arrival) {
        return built > 0 ? built : arrival;
    }

    /** Midnight is used when the message states a day but no clock, so a date-only message lands on
     *  that day rather than being pulled towards the middle of it. */
    private static long at(int[] gregorian, int[] clock) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(gregorian[0], gregorian[1] - 1, gregorian[2],
            clock == null ? 0 : clock[0], clock == null ? 0 : clock[1], 0);
        return c.getTimeInMillis();
    }
}
