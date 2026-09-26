package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;

/**
 * The time a bank message states, which is what places a late message in the period it belongs to.
 *
 * <p>The layouts here are the ones Iranian banks actually send, taken from a survey of real bank
 * messages rather than invented for the test: a full Persian year ({@code 1405/06/07}), the same
 * with a seconds clock ({@code 14:07:22}), dots instead of slashes, a two-digit year in front
 * ({@code 05/06/30-08:53}), a day glued to a clock ({@code 0620-21:16}), a year-less date beside an
 * underscore ({@code 06/29_20:56}), a Gregorian year, and the layout with no timestamp at all.
 */
@RunWith(AndroidJUnit4.class)
public class MessageDateTest {

    /** A fixed arrival — 10 Tir 1405 at noon, later than every date the fixtures state — so nothing
     *  here depends on the day the test happens to run. */
    private static final long ARRIVAL = at(2026, 10, 1, 12, 0);

    private static long at(int y, int m, int d, int h, int min) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, m - 1, d, h, min, 0);
        return c.getTimeInMillis();
    }

    private static long persian(int jy, int jm, int jd, int h, int min) {
        int[] g = JalaliCalendar.of(jy, jm, jd).toGregorian();
        return at(g[0], g[1], g[2], h, min);
    }

    private static long daysBefore(long millis) {
        return Math.round((ARRIVAL - millis) / 86400000.0);
    }

    // ====================================================================
    // A stated full year: the calendar is read off the digits, not guessed
    // ====================================================================

    @Test public void tejaratStyle_fullPersianYearAndClock() {
        String body = "*\u0628\u0627\u0646\u06A9 \u062A\u062C\u0627\u0631\u062A*\n"
            + "\u062D\u0633\u0627\u0628: 01351234567890\n"
            + "\u0628\u0631\u062F\u0627\u0634\u062A: 70,014,000 \u0631\u06CC\u0627\u0644\n"
            + "\u0645\u0627\u0646\u062F\u0647: 1,209,288 \u0631\u06CC\u0627\u0644\n"
            + "1405/06/07\n20:16";
        assertEquals(persian(1405, 6, 7, 20, 16), MessageDate.eventTime(body, ARRIVAL, CalendarSystem.JALALI));
    }

    @Test public void bluStyle_persianYearWithDotsAndPersianDigits() {
        String body = "\u0628\u0644\u0648\n\u0628\u0631\u062F\u0627\u0634\u062A \u067E\u0648\u0644\n"
            + "\u0627\u0634\u06A9\u0627\u0646 \u0639\u0632\u06CC\u0632\u06CC\u060C 400,000 \u0631\u06CC\u0627\u0644 "
            + "\u0627\u0632 \u062D\u0633\u0627\u0628 \u0634\u0645\u0627 \u067E\u0631\u06CC\u062F.\n"
            + "\u0645\u0648\u062C\u0648\u062F\u06CC: 57,086,241 \u0631\u06CC\u0627\u0644\n"
            + "\u06F2\u06F3:\u06F2\u06F8\n\u06F1\u06F4\u06F0\u06F5.\u06F6.\u06F1\u06F5";
        assertEquals(persian(1405, 6, 15, 23, 28), MessageDate.eventTime(body, ARRIVAL, CalendarSystem.JALALI));
    }

    @Test public void aClockWithSeconds_isReadFromItsHourNotItsTrailingPair() {
        // Tejarat and Parsian write "14:07:22". Taking the trailing "07:22" would file the message
        // seven hours late — enough to move it into the next day, and out of the bracket it closes.
        String body = "\u0628\u0631\u062F\u0627\u0634\u062A: 70,014,000 \u0631\u06CC\u0627\u0644\n"
            + "\u0645\u0627\u0646\u062F\u0647: 1,209,288 \u0631\u06CC\u0627\u0644\n"
            + "1405/06/30\n14:07:22";
        assertEquals(persian(1405, 6, 30, 14, 7), MessageDate.eventTime(body, ARRIVAL, CalendarSystem.JALALI));
    }

    @Test public void gregorianYear_isReadAsGregorianNotPersian() {
        // 2026 can only be a Gregorian year, so the same shape must not be read as Persian.
        String body = "balance 500,000\n2026-09-01\n20:37";
        assertEquals(at(2026, 9, 1, 20, 37), MessageDate.eventTime(body, ARRIVAL, CalendarSystem.JALALI));
    }

    // ====================================================================
    // Year-less and two-digit-year layouts
    // ====================================================================

    @Test public void mellatStyle_twoDigitYearInFrontOfTheDate() {
        // "05/06/30-08:53" is 6/30 of this Persian year, not a 5th month. Read as a month it would
        // land seven weeks early, straight into the wrong month of the breakdown.
        String body = "\u0628\u0631\u062F\u0627\u0634\u062A 500,000,000 \u0631\u06CC\u0627\u0644\n"
            + "\u0645\u0627\u0646\u062F\u0647: 2,000,000,000 \u0631\u06CC\u0627\u0644\n"
            + "05/06/30-08:53";
        assertEquals(persian(1405, 6, 30, 8, 53), MessageDate.eventTime(body, ARRIVAL, CalendarSystem.JALALI));
    }

    @Test public void parsianStyle_shortDateThenClock() {
        String body = "30101234567890\n"
            + "\u0645\u0628\u0644\u063A:500,000-\n"
            + "\u0645\u0627\u0646\u062F\u0647:1,076,220\n"
            + "06/26\n08:22";
        assertEquals(persian(1405, 6, 26, 8, 22), MessageDate.eventTime(body, ARRIVAL, CalendarSystem.JALALI));
    }

    @Test public void resalatStyle_shortDateUnderscoreClock() {
        String body = "-200,000,000  \n06/29_20:56 \n"
            + "\u0645\u0627\u0646\u062F\u0647: 2,279,545,033";
        assertEquals(persian(1405, 6, 29, 20, 56), MessageDate.eventTime(body, ARRIVAL, CalendarSystem.JALALI));
    }

    @Test public void melliStyle_compactDayGluedToClock() {
        String body = "\u0627\u0646\u062A\u0642\u0627\u0644\u06CC:100,000,000-\n"
            + "\u062D\u0633\u0627\u0628:10001\n"
            + "\u0645\u0627\u0646\u062F\u0647:77,222,945\n"
            + "0620-23:13";
        assertEquals(persian(1405, 6, 20, 23, 13), MessageDate.eventTime(body, ARRIVAL, CalendarSystem.JALALI));
    }

    @Test public void yearlessDate_inTheFuture_stepsBackToLastYear() {
        // 12 Dey is later in the Persian year than 5 Tir, so it can only have happened last year.
        String body = "\u0645\u0628\u0644\u063A:500,000-\n\u0645\u0627\u0646\u062F\u0647:1,076,220\n12/09\n08:22";
        // An arrival in Farvardan, the one time of year a Dey message can only be last year's.
        // Note what the travel window does to the other case: from an Esfand arrival, reaching a
        // believable Dey means stepping back a full year, which no late message does, so the
        // arrival stands instead. The cap and the step-back have to agree, or one undoes the other.
        long arrival = at(2026, 4, 5, 8, 0);
        long at = MessageDate.eventTime(body, arrival, CalendarSystem.JALALI);
        assertEquals(persian(1404, 12, 9, 8, 22), at);
        assertTrue("and it must be in the past", at < arrival);
    }

    @Test public void yearlessDate_withoutAClock_isNotBelieved() {
        // "1.5" is far more likely to be a number than a date, so a year-less shape with no clock
        // beside it is left alone and the arrival time stands.
        String body = "\u0645\u0628\u0644\u063A:1.5 \u0645\u0644\u06CC\u0648\u0646\n\u0645\u0627\u0646\u062F\u0647:900";
        assertEquals(ARRIVAL, MessageDate.eventTime(body, ARRIVAL, CalendarSystem.JALALI));
    }

    // ====================================================================
    // The calendar the bank declares
    // ====================================================================

    @Test public void yearlessDate_isReadInTheCalendarTheBankDeclares() {
        // 6/29 is an ordinary day in both calendars and they are about three months apart, so the
        // same message lands in a different month depending on which one the bank writes in. This is
        // why the year-less layouts cannot be allowed to assume: the digits say nothing.
        String body = "-200,000,000  \n06/29_20:56 \n"
            + "مانده: 2,279,545,033";
        assertEquals(persian(1405, 6, 29, 20, 56),
            MessageDate.eventTime(body, ARRIVAL, CalendarSystem.JALALI));
        // Read as Gregorian it would be 2026-06-29, which is 94 days before the arrival and so
        // outside the window a late message can travel: the arrival stands rather than the date
        // being believed. Rejection, not a second opinion on which month the bank meant.
        assertEquals(ARRIVAL, MessageDate.eventTime(body, ARRIVAL, CalendarSystem.GREGORIAN));
    }

    @Test public void aGregorianBank_yearlessDateIsBelieved() {
        // The other direction: a bank that writes Gregorian dates has its year-less date believed,
        // which it would not if every such message were read as Persian.
        String body = "balance 1,234.56\n09/28\n20:56";
        assertEquals(at(2026, 9, 28, 20, 56),
            MessageDate.eventTime(body, ARRIVAL, CalendarSystem.GREGORIAN));
    }

    @Test public void twoDigitYear_isReadInTheCalendarTheBankDeclares() {
        // 26 is 1426 to an Iranian bank and 2026 to a Gregorian one, so this layout needs the
        // declaration as much as the year-less one does.
        String body = "balance 500,000\n26/09/28-20:56";
        assertEquals(at(2026, 9, 28, 20, 56),
            MessageDate.eventTime(body, ARRIVAL, CalendarSystem.GREGORIAN));
        assertEquals(ARRIVAL, MessageDate.eventTime(body, ARRIVAL, CalendarSystem.JALALI));
    }

    @Test public void aGregorianDayThatDoesNotExist_isDropped_notRolledIntoTheNextMonth() {
        // February never has a 30th. A lenient Calendar would turn 2026-02-30 into 2026-03-02 and
        // file the movement on a day the bank never mentioned, so the arrival is set close enough
        // that the rolled-over date would otherwise be perfectly believable.
        long arrival = at(2026, 3, 10, 8, 0);
        assertEquals(arrival,
            MessageDate.eventTime("balance 500,000\n2026-02-30\n08:00", arrival, CalendarSystem.GREGORIAN));
        // The same day it does exist is still believed, so the rejection above is the missing day
        // and not the layout.
        assertEquals(at(2026, 2, 28, 8, 0),
            MessageDate.eventTime("balance 500,000\n2026-02-28\n08:00", arrival, CalendarSystem.GREGORIAN));
    }

    @Test public void aMessageThatStatesNoDateAtAll_keepsTheArrival_inEitherCalendar() {
        // Plenty of bank messages state no time at all, and the calendar a bank declares must not
        // change that: with no date to read there is nothing for the calendar to disagree about, so
        // the arrival stands whichever one the bank writes in.
        String body = "مانده: 1,076,220\nبرداشت: 500,000";
        assertEquals(ARRIVAL, MessageDate.eventTime(body, ARRIVAL, CalendarSystem.JALALI));
        assertEquals(ARRIVAL, MessageDate.eventTime(body, ARRIVAL, CalendarSystem.GREGORIAN));
        // A body with nothing date-shaped in it at all behaves the same way.
        String bare = "مانده: 1,076,220";
        assertEquals(ARRIVAL, MessageDate.eventTime(bare, ARRIVAL, CalendarSystem.JALALI));
        assertEquals(ARRIVAL, MessageDate.eventTime(bare, ARRIVAL, CalendarSystem.GREGORIAN));
        assertEquals(ARRIVAL, MessageDate.eventTime(null, ARRIVAL, CalendarSystem.GREGORIAN));
    }

    @Test public void aDateWithNoClock_landsOnThatDayAtMidnight_inEitherCalendar() {
        // A day but no time is still a day: the movement is filed on the day the bank named rather
        // than pulled towards the middle of it, and the declared calendar decides which day that is.
        // Both dates sit inside the travel window, or the arrival would rightly stand instead.
        assertEquals(persian(1405, 6, 7, 0, 0),
            MessageDate.eventTime("مانده: 5,000,000\n1405/06/07", ARRIVAL, CalendarSystem.JALALI));
        assertEquals(at(2026, 9, 25, 0, 0),
            MessageDate.eventTime("balance 5,000,000\n2026-09-25", ARRIVAL, CalendarSystem.GREGORIAN));
    }

    // ====================================================================
    // Fallback
    // ====================================================================

    @Test public void mellatStyle_statesNoTime_soArrivalStands() {
        String body = "\u0628\u0631\u062F\u0627\u0634\u062A100,000,000 \u0645\u0627\u0646\u062F\u0647 77,222,945";
        assertEquals(ARRIVAL, MessageDate.eventTime(body, ARRIVAL, CalendarSystem.JALALI));
    }

    @Test public void noBody_fallsBackToArrival() {
        assertEquals(ARRIVAL, MessageDate.eventTime(null, ARRIVAL, CalendarSystem.JALALI));
    }

    // ====================================================================
    // Nothing that merely looks like a date may move a movement
    // ====================================================================

    @Test public void amountsAndReferences_areNotDates() {
        assertEquals(ARRIVAL,
            MessageDate.eventTime("\u0645\u0628\u0644\u063A 1,405,060,700 \u0631\u06CC\u0627\u0644", ARRIVAL, CalendarSystem.JALALI));
        assertEquals(ARRIVAL, MessageDate.eventTime("ref 1234-5678-9012", ARRIVAL, CalendarSystem.JALALI));
        assertEquals(ARRIVAL, MessageDate.eventTime("card 6219-8610-1234-5678", ARRIVAL, CalendarSystem.JALALI));
    }

    @Test public void anIban_isNotADate() {
        // Bank Pasargad prints an IBAN whose digit groups read like a date at a glance.
        String body = "\u0628\u0631\u062F\u0627\u0634\u062A\n220.8000.12583840.1\n"
            + "\u0645\u0627\u0646\u062F\u0647: 1,000,000 \u0631\u06CC\u0627\u0644";
        assertEquals(ARRIVAL, MessageDate.eventTime(body, ARRIVAL, CalendarSystem.JALALI));
    }

    @Test public void aDateOlderThanTheTravelWindow_isNotBackDated() {
        // The bound that keeps one unverifiable message from rewriting history. A body time is the
        // bank's own claim, so it is believed only over the span in which a message genuinely
        // travels late. Two months back is a claim about a period, not a delivery delay, and
        // accepting it would let a re-sent or forged message reopen a settled bracket.
        long arrival = at(2026, 10, 1, 12, 0);
        String body = "\u0645\u0627\u0646\u062F\u0647: 5,000,000 \u0631\u06CC\u0627\u0644\n1405/05/01\n10:00";
        assertTrue("the fixture must be older than the window to be worth testing",
            persian(1405, 5, 1, 10, 0) < arrival - 45L * 86400000L);
        assertEquals("so the arrival stands and no movement is moved in time",
            arrival, MessageDate.eventTime(body, arrival, CalendarSystem.JALALI));
    }

    @Test public void aDateFarOlderThanAnyRealStatement_isDroppedNotReinterpreted() {
        // 1394 is a statement from a decade ago. The message clearly states a date, so that date is
        // disbelieved and the arrival stands — it must not be re-read as this year's 1/2.
        String body = "\u0645\u0627\u0646\u062F\u0647: 5,000,000 \u0631\u06CC\u0627\u0644\n1394/01/02\n10:00";
        assertEquals(ARRIVAL, MessageDate.eventTime(body, ARRIVAL, CalendarSystem.JALALI));
    }

    @Test public void aDateInTheFuture_isDroppedNotReinterpreted() {
        // 1405/12/30 has not happened yet; believing it would file the movement ahead of every
        // statement the bank has actually sent.
        String body = "\u0645\u0627\u0646\u062F\u0647: 5,000,000 \u0631\u06CC\u0627\u0644\n1405/12/30\n10:00";
        assertEquals(ARRIVAL, MessageDate.eventTime(body, ARRIVAL, CalendarSystem.JALALI));
    }

    @Test public void aNonLeapEsfand30_isRejected_becauseTheDayDoesNotExist() {
        // 1405 ends at Esfand 29, not 30. The arrival is set past the claimed date, so the missing
        // day is the only possible reason to turn it down \u2014 not its being too far ahead.
        long arrival = persian(1405, 12, 30, 10, 0) + 12L * 86400000L;
        String body = "\u0645\u0627\u0646\u062F\u0647: 5,000,000 \u0631\u06CC\u0627\u0644\n1405/12/30\n10:00";
        assertEquals(arrival, MessageDate.eventTime(body, arrival, CalendarSystem.JALALI));
    }

    @Test public void aLeapEsfand30_isAccepted() {
        // 1403 is a leap year, so its Esfand 30 is a real day and must be read, not rejected. The
        // arrival is set just after it, since no leap Esfand 30 falls near the usual test arrival.
        long esfand30 = persian(1403, 12, 30, 10, 0);
        String body = "\u0645\u0627\u0646\u062F\u0647: 5,000,000 \u0631\u06CC\u0627\u0644\n1403/12/30\n10:00";
        assertEquals(esfand30, MessageDate.eventTime(body, esfand30 + 86400000L, CalendarSystem.JALALI));
    }

    @Test public void aDateOnlyMessage_landsOnThatDayAtMidnight() {
        String body = "\u0645\u0627\u0646\u062F\u0647: 5,000,000 \u0631\u06CC\u0627\u0644\n1405/06/07";
        assertEquals(persian(1405, 6, 7, 0, 0), MessageDate.eventTime(body, ARRIVAL, CalendarSystem.JALALI));
    }

    // ====================================================================
    // The reason the class exists
    // ====================================================================

    @Test public void aLateMessage_isFiledInItsOwnPeriod_notInTheArrivalDay() {
        // The point of reading the stated time: a message that only turns up weeks later must be
        // dated by the money, not by the notification. Without this the movement lands outside the
        // bracket it belongs to, and the unaccounted-money detector reports a gap for money the
        // app is already holding.
        String body = "\u0628\u0631\u062F\u0627\u0634\u062A: 10,000,000 \u0631\u06CC\u0627\u0644\n"
            + "\u0645\u0627\u0646\u062F\u0647: 90,000,000 \u0631\u06CC\u0627\u0644\n"
            + "1405/06/03\n11:30";
        long event = MessageDate.eventTime(body, ARRIVAL, CalendarSystem.JALALI);
        assertEquals(persian(1405, 6, 3, 11, 30), event);
        assertTrue("the movement must predate its own arrival by weeks, not minutes",
            daysBefore(event) > 30);
    }
}
