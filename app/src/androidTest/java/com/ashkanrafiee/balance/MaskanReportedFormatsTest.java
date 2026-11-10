package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;

/** Anonymized reported layouts, exercised without touching the SMS provider or app stores. */
@RunWith(AndroidJUnit4.class)
public class MaskanReportedFormatsTest {

    private static final String BANK = "Maskan";
    private static final String SENDER = "+9850004920";
    private static final String ACCOUNT = "001000000001";
    private static final String[] MESSAGES = {
        "خودپرداز:\u200E-345,000\u200E\nحساب:" + ACCOUNT + "\nمانده:72,900\n0130-19:29",
        "خودپرداز:\u200E-2,345,000\u200E\nحساب:" + ACCOUNT + "\nمانده:4,567,000\n0127-01:29",
        "قبض:\u200E-34,000\u200E\nحساب:" + ACCOUNT + "\nمانده:4,533,000\n0127-01:24"
    };
    private static final long[] AMOUNTS = {-345_000L, -2_345_000L, -34_000L};
    private static final long[] BALANCES = {72_900L, 4_567_000L, 4_533_000L};
    private static final String[] DATES = {"0130-19:29", "0127-01:29", "0127-01:24"};
    private static final long[] EVENTS = {
        persian(1405, 1, 30, 19, 29),
        persian(1405, 1, 27, 1, 29),
        persian(1405, 1, 27, 1, 24)
    };
    private static final long ARRIVAL = persian(1405, 2, 1, 12, 0);

    @Test public void reportedAtmAndBillLayouts_preserveSignedMovementBalanceAccountAndDate() {
        for (int i = 0; i < MESSAGES.length; i++) {
            assertMovement(i, MESSAGES[i], ARRIVAL, EVENTS[i]);
        }
    }

    @Test public void persianDigitsWithLrmAndCrLf_preserveAllTransactionFields() {
        for (int i = 0; i < MESSAGES.length; i++) {
            assertMovement(i, localized(MESSAGES[i], '۰'), ARRIVAL, EVENTS[i]);
        }
    }

    @Test public void arabicDigitsWithLrmAndCrLf_preserveAllTransactionFields() {
        for (int i = 0; i < MESSAGES.length; i++) {
            assertMovement(i, localized(MESSAGES[i], '٠'), ARRIVAL, EVENTS[i]);
        }
    }

    @Test public void compactDateJustAfterNowruz_infersPreviousJalaliYear() {
        long arrival = persian(1405, 1, 1, 12, 0);
        long event = persian(1404, 12, 29, 19, 29);
        for (int i = 0; i < MESSAGES.length; i++) {
            String message = MESSAGES[i].replace(DATES[i], "1229-19:29");
            assertMovement(i, message, arrival, event);
        }
    }

    @Test public void reportedFarvardinDatesBeforeTheyOccur_fallBackToArrivalNearNowruz() {
        long arrival = persian(1405, 1, 1, 12, 0);
        // These days have not occurred in 1405; their 1404 occurrences are over 45 days old.
        for (int i = 0; i < MESSAGES.length; i++) {
            assertMovement(i, MESSAGES[i], arrival, arrival);
        }
    }

    @Test public void compactDates_at45DaysAreTrusted_beyond45DaysUseArrival() {
        long maxAge = 45L * 24 * 60 * 60 * 1000L;
        for (int i = 0; i < MESSAGES.length; i++) {
            long arrival = EVENTS[i] + maxAge;
            assertMovement(i, MESSAGES[i], arrival, EVENTS[i]);
            assertMovement(i, MESSAGES[i], arrival + 1, arrival + 1);
        }
    }

    private static void assertMovement(int index, String message, long arrival, long expectedDate) {
        String bank = BankRules.resolve(SENDER);
        assertEquals(BANK, bank);
        long event = MessageDate.eventTime(message, arrival, BankRules.calendar(bank));
        assertEquals(expectedDate, event);

        Transaction transaction = BalanceData.parseMovement(bank, SENDER, message, event, false, 0);
        assertNotNull(transaction);
        assertEquals(BANK, transaction.bank);
        assertEquals(ACCOUNT, transaction.account);
        assertEquals(AMOUNTS[index], transaction.amount);
        assertEquals(Long.valueOf(BALANCES[index]), transaction.balance);
        assertEquals(expectedDate, transaction.date);
    }

    private static String localized(String message, char zero) {
        StringBuilder result = new StringBuilder();
        for (char c : message.replace("\n", "\r\n").toCharArray()) {
            result.append(c >= '0' && c <= '9' ? (char) (zero + c - '0') : c);
        }
        return result.toString();
    }

    private static long persian(int year, int month, int day, int hour, int minute) {
        int[] gregorian = JalaliCalendar.of(year, month, day).toGregorian();
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(gregorian[0], gregorian[1] - 1, gregorian[2], hour, minute, 0);
        return calendar.getTimeInMillis();
    }
}
