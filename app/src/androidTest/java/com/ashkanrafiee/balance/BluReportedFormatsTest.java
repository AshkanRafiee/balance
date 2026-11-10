package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;

/** Pure parser regressions: reported layouts with synthetic names, amounts and balances. */
@RunWith(AndroidJUnit4.class)
public class BluReportedFormatsTest {

    private static final String BANK = "Blu";
    private static final String SENDER = BluMessages.SENDER;
    private static final long ARRIVAL = persian(1405, 7, 8, 12, 0);

    @Test public void withdrawal_shortBalance_keepsMovementSeparate() {
        String body = "بلو\n"
            + "برداشت پول\n"
            + "کاربر نمونه عزیز، 135,000 ریال از حساب شما پرید.\n"
            + "موجودی: 742 ریال\n"
            + "۹:۱۴\n"
            + "۱۴۰۵.۰۷.۰۶";
        assertMovement(body, -135_000L, 742L, persian(1405, 7, 6, 9, 14));
    }

    @Test public void onlinePackage_spaceBeforePeriod_parsesWithdrawal() {
        String body = "بلو\n"
            + "آنلاین شدی\n"
            + "کاربر نمونه عزیز، 246,810 ریال بابت خرید بسته اینترنت از حساب شما پرید .\n"
            + "موجودی: 438,926 ریال\n"
            + "۹:۱۱\n"
            + "۱۴۰۵.۰۷.۰۶";
        assertMovement(body, -246_810L, 438_926L, persian(1405, 7, 6, 9, 11));
    }

    @Test public void instantTransfer_parsesWithdrawal() {
        String body = "بلو\n"
            + "انتقال پل\n"
            + "کاربر نمونه عزیز، 320,000 ریال از حساب شما پرید.\n"
            + "موجودی: 395,756 ریال\n"
            + "۲۳:۲۶\n"
            + "۱۴۰۵.۰۷.۰۵";
        assertMovement(body, -320_000L, 395_756L, persian(1405, 7, 5, 23, 26));
    }

    @Test public void disruptionCompensation_parsesDeposit() {
        String body = "بلو\n"
            + "پوزش بابت اختلال\n"
            + "کاربر نمونه عزیز، 320,000 ریال به حساب شما نشست.\n"
            + "موجودی: 715,756 ریال\n"
            + "۲۳:۳۵\n"
            + "۱۴۰۵.۰۷.۰۵";
        assertMovement(body, 320_000L, 715_756L, persian(1405, 7, 5, 23, 35));
    }

    @Test public void onlinePackage_singleDigitHour_parsesWithdrawal() {
        String body = "بلو\n"
            + "آنلاین شدی\n"
            + "کاربر نمونه عزیز، 157,430 ریال بابت خرید بسته اینترنت از حساب شما پرید .\n"
            + "موجودی: 624,315 ریال\n"
            + "۱:۱۱\n"
            + "۱۴۰۵.۰۷.۰۱";
        assertMovement(body, -157_430L, 624_315L, persian(1405, 7, 1, 1, 11));
    }

    @Test public void deposit_previousMonthEnd_parsesDeposit() {
        String body = "بلو\n"
            + "واریز پول\n"
            + "کاربر نمونه عزیز، 840,000 ریال به حساب شما نشست.\n"
            + "موجودی: 967,845 ریال\n"
            + "۲۰:۰۲\n"
            + "۱۴۰۵.۰۶.۳۱";
        assertMovement(body, 840_000L, 967_845L, persian(1405, 6, 31, 20, 2));
    }

    @Test public void trailingWithdrawalWord_doesNotCaptureBalanceAcrossLineBreak() {
        // The existing fixture ends its movement sentence with "برداشت.". The last label match
        // must not skip the line break and mistake the resulting balance for the movement.
        for (String newline : new String[]{"\n", "\r\n"}) {
            String body = BluMessages.WITHDRAWAL.replace("\n", newline);
            assertMovement(body, -6_666_000L, 12_345_683L, persian(1405, 6, 15, 23, 28));
        }
    }

    @Test public void trailingDepositWord_doesNotCaptureBalanceAcrossLineBreak() {
        String fixture = "بلو\n"
            + "واریز پول\n"
            + "کاربر نمونه عزیز، 7,777,000 ریال به حساب شما واریز.\n"
            + "موجودی: 13,456,789 ریال\n"
            + "۲۳:۲۸\n"
            + "۱۴۰۵.۰۶.۱۵";
        for (String newline : new String[]{"\n", "\r\n"}) {
            String body = fixture.replace("\n", newline);
            assertMovement(body, 7_777_000L, 13_456_789L, persian(1405, 6, 15, 23, 28));
        }
    }

    @Test public void movementLabels_cannotConsumeOnlyTheNextLineBalance() {
        for (String label : new String[]{"واریز", "برداشت"}) {
            for (String newline : new String[]{"\n", "\r\n", "\r"}) {
                assertNull(BalanceData.extractTransaction(label + newline
                    + "موجودی: 91,000 ریال"));
            }
        }
    }

    @Test public void nextLineRialAmount_remainsReadableWithoutCrossingLabels() {
        for (String newline : new String[]{"\n", "\r\n", "\r"}) {
            for (String label : new String[]{"واریز", "برداشت"}) {
                String body = label + ":" + newline + "43,000 ریال" + newline
                    + "موجودی: 91,000 ریال";
                long expected = label.equals("واریز") ? 43_000L : -43_000L;
                assertEquals(Long.valueOf(expected), BalanceData.extractTransaction(body));
            }
        }
    }

    private static void assertMovement(String body, long amount, long balance, long expectedTime) {
        assertEquals(BANK, BankRules.resolve(SENDER));
        assertNull(BankRules.extractAccount(BANK, body));
        assertEquals(Long.valueOf(amount), BalanceData.extractTransaction(body));
        assertEquals(balance, BalanceData.extract(BANK, body));

        long eventTime = MessageDate.eventTime(body, ARRIVAL, BankRules.calendar(BANK));
        assertEquals(expectedTime, eventTime);
        Transaction transaction = BalanceData.parseMovement(BANK, SENDER, body, eventTime, false, 0);
        assertNotNull(transaction);
        assertEquals(BANK, transaction.bank);
        assertNull(transaction.account);
        assertEquals(amount, transaction.amount);
        assertEquals(Long.valueOf(balance), transaction.balance);
        assertEquals(expectedTime, transaction.date);
    }

    private static long persian(int year, int month, int day, int hour, int minute) {
        int[] gregorian = JalaliCalendar.of(year, month, day).toGregorian();
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(gregorian[0], gregorian[1] - 1, gregorian[2], hour, minute, 0);
        return calendar.getTimeInMillis();
    }
}
