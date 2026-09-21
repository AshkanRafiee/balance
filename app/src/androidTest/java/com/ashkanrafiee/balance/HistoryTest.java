package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Transaction amount extraction and Persian (Jalali) calendar logic (instrumented, pure logic). */
@RunWith(AndroidJUnit4.class)
public class HistoryTest {

    // ---- transaction extraction: deposits ----------------------------------------------
    @Test public void txn_deposit_variz() {
        assertEquals(5000000L, (long) BalanceData.extractTransaction(
            "\u0645\u0628\u0644\u063A 5,000,000 \u0631\u06CC\u0627\u0644 \u0628\u0647 \u062D\u0633\u0627\u0628 \u0634\u0645\u0627 \u0648\u0627\u0631\u06CC\u0632 \u0634\u062F\u060C \u0645\u0648\u062C\u0648\u062F\u06CC: 10,000,000 \u0631\u06CC\u0627\u0644"));
    }

    @Test public void txn_deposit_beHesab() {
        assertEquals(1200000L, (long) BalanceData.extractTransaction(
            "\u0645\u0628\u0644\u063A 1,200,000 \u0631\u06CC\u0627\u0644 \u0628\u0647 \u062D\u0633\u0627\u0628 \u0634\u0645\u0627 \u0648\u0627\u0631\u062F \u0634\u062F\u060C \u0645\u0648\u062C\u0648\u062F\u06CC: 40,000,000 \u0631\u06CC\u0627\u0644"));
    }

    @Test public void txn_deposit_persianDigits() {
        assertEquals(250000L, (long) BalanceData.extractTransaction(
            "\u0645\u0628\u0644\u063A \u06F2\u06F5\u06F0\u06F0\u06F0\u06F0 \u0631\u06CC\u0627\u0644 \u0648\u0627\u0631\u06CC\u0632\u060C \u0645\u0648\u062C\u0648\u062F\u06CC: 1,000,000 \u0631\u06CC\u0627\u0644"));
    }

    // ---- transaction extraction: withdrawals -------------------------------------------
    @Test public void txn_withdrawal_kharid() {
        assertEquals(-1200000L, (long) BalanceData.extractTransaction(
            "\u062E\u0631\u06CC\u062F \u0628\u0647 \u0645\u0628\u0644\u063A 1,200,000 \u0631\u06CC\u0627\u0644\u060C \u0645\u0648\u062C\u0648\u062F\u06CC: 5,000,000 \u0631\u06CC\u0627\u0644"));
    }

    @Test public void txn_withdrawal_bardasht() {
        assertEquals(-200000L, (long) BalanceData.extractTransaction(
            "\u0645\u0628\u0644\u063A 200,000 \u0631\u06CC\u0627\u0644 \u0628\u0631\u062F\u0627\u0634\u062A\u060C \u0645\u0648\u062C\u0648\u062F\u06CC: 800,000 \u0631\u06CC\u0627\u0644"));
    }

    @Test public void txn_withdrawal_entegal() {
        assertEquals(-3000000L, (long) BalanceData.extractTransaction(
            "\u0627\u0646\u062A\u0642\u0627\u0644 \u0648\u062C\u0647 \u0628\u0647 \u0645\u0628\u0644\u063A 3,000,000 \u0631\u06CC\u0627\u0644\u060C \u0645\u0648\u062C\u0648\u062F\u06CC: 7,000,000 \u0631\u06CC\u0627\u0644"));
    }

    // ---- transaction extraction: must not misfire --------------------------------------
    @Test public void txn_balanceOnly_noAmountLabel_isNull() {
        assertNull(BalanceData.extractTransaction(
            "\u0645\u0648\u062C\u0648\u062F\u06CC \u062D\u0633\u0627\u0628 \u0634\u0645\u0627: 5,000,000 \u0631\u06CC\u0627\u0644"));
    }

    @Test public void txn_noDirectionKeyword_isNull() {
        assertNull(BalanceData.extractTransaction(
            "\u0645\u0628\u0644\u063A 5,000 \u0631\u06CC\u0627\u0644 \u062A\u0646\u0647\u0627\u060C \u0645\u0648\u062C\u0648\u062F\u06CC: 1,000,000 \u0631\u06CC\u0627\u0644"));
    }

    // ---- transaction extraction: finality ----------------------------------------------
    @Test public void txn_movementWithoutBalance_isNull() {
        // Amount + direction but no resulting balance: an OTP/payment prompt or unconfirmed state,
        // the movement did not necessarily settle — never count it as history.
        assertNull(BalanceData.extractTransaction(
            "\u0645\u0628\u0644\u063A 200,000 \u0631\u06CC\u0627\u0644 \u062E\u0631\u06CC\u062F \u0627\u0646\u062C\u0627\u0645 \u0634\u062F"));
        assertNull(BalanceData.extractTransaction(
            "\u067E\u0631\u062F\u0627\u062E\u062A \u0628\u0647 \u0645\u0628\u0644\u063A 1,200,000 \u0631\u06CC\u0627\u0644 \u062F\u0631 \u0627\u0646\u062A\u0638\u0627\u0631 \u062A\u0627\u06CC\u06CC\u062F"));
    }

    @Test public void txn_otp_isNull() {
        assertNull(BalanceData.extractTransaction(
            "\u0631\u0645\u0632 \u0648\u0631\u0648\u062F: \u0645\u0628\u0644\u063A 123456"));
    }

    @Test public void txn_nullOrBlank_isNull() {
        assertNull(BalanceData.extractTransaction(null));
        assertNull(BalanceData.extractTransaction(""));
    }

    @Test public void txn_ambiguousBothKeywords_isNull() {
        assertNull(BalanceData.extractTransaction(
            "\u0645\u0628\u0644\u063A 100 \u0648\u0627\u0631\u06CC\u0632 \u0648 \u0628\u0631\u062F\u0627\u0634\u062A"));
    }

    // ---- real-world bank formats (from Tejarat / Blu / Parsian SMS) --------------------
    @Test public void txn_tejarat_bardashtLabel() {
        assertEquals(-70014000L, (long) BalanceData.extractTransaction(
            "*\u0628\u0627\u0646\u06A9 \u062A\u062C\u0627\u0631\u062A* \n"
            + "\u062D\u0633\u0627\u0628: 01351234567890 \n"
            + "\u0628\u0631\u062F\u0627\u0634\u062A: 70,014,000 \u0631\u06CC\u0627\u0644 \n"
            + "\u0627\u0632 \u0637\u0631\u06CC\u0642: \u0633\u0627\u0645\u0627\u0646\u0647 \u067E\u0644 (\u067E\u0631\u062F\u0627\u062E\u062A \u0644\u062D\u0638\u0647 \u0627\u06CC)  \n"
            + "\u0645\u0627\u0646\u062F\u0647: 1,209,288 \u0631\u06CC\u0627\u0644 \n"
            + "1405/06/07\n20:16"));
    }

    @Test public void txn_tejarat_varizLabel_pardakhtWord_isDeposit() {
        // "پرداخت لحظه ای" is a payment-method name, not a withdrawal keyword; the amount written
        // right after "واریز:" must decide the direction instead of the keyword sets.
        assertEquals(115000000L, (long) BalanceData.extractTransaction(
            "*\u0628\u0627\u0646\u06A9 \u062A\u062C\u0627\u0631\u062A* \n"
            + "\u062D\u0633\u0627\u0628: 01351234567890 \n"
            + "\u0648\u0627\u0631\u06CC\u0632: 115,000,000 \u0631\u06CC\u0627\u0644 \n"
            + "\u0627\u0632 \u0637\u0631\u06CC\u0642: \u0633\u0627\u0645\u0627\u0646\u0647 \u067E\u0644 (\u067E\u0631\u062F\u0627\u062E\u062A \u0644\u062D\u0638\u0647 \u0627\u06CC)  \n"
            + "\u0645\u0627\u0646\u062F\u0647: 361,919,288 \u0631\u06CC\u0627\u0644 \n"
            + "1405/06/06\n00:08"));
    }

    @Test public void txn_blu_bareRialAmount() {
        assertEquals(-400000L, (long) BalanceData.extractTransaction(
            "\u0628\u0644\u0648\n"
            + "\u0628\u0631\u062F\u0627\u0634\u062A \u067E\u0648\u0644\n"
            + "\u0627\u0634\u06A9\u0627\u0646 \u0639\u0632\u06CC\u0632\u060C 400,000 \u0631\u06CC\u0627\u0644 \u0627\u0632 \u062D\u0633\u0627\u0628 \u0634\u0645\u0627 \u067E\u0631\u06CC\u062F.\n"
            + "\u0645\u0648\u062C\u0648\u062F\u06CC: 57,086,241 \u0631\u06CC\u0627\u0644\n"
            + "\u06F2\u06F3:\u06F2\u06F8\n"
            + "\u06F1\u06F4\u06F0\u06F5.\u06F0\u06F6.\u06F1\u06F5"));
    }

    @Test public void txn_parsian_trailingMinusSign() {
        assertEquals(-500000L, (long) BalanceData.extractTransaction(
            "30101234567890\n"
            + "\u0645\u0628\u0644\u063A:500,000-\n"
            + "\u0645\u0627\u0646\u062F\u0647:1,076,220\n"
            + "05/26\n08:22"));
    }

    // ---- real-world bank formats (Resalat bare signed amounts) -----------------------
    @Test public void txn_resalat_bareSignedAmount_withdrawal() {
        assertEquals(-200000000L, (long) BalanceData.extractTransaction(
            "-200,000,000  \n"
            + "06/22_20:37 \n"
            + "\u0645\u0627\u0646\u062F\u0647: 2,279,545,033"));
    }

    @Test public void txn_resalat_bareSignedAmount_secondWithdrawal() {
        assertEquals(-40000L, (long) BalanceData.extractTransaction(
            "-40,000  \n"
            + "06/22_20:37 \n"
            + "\u0645\u0627\u0646\u062F\u0647: 2,279,505,033"));
    }

    @Test public void txn_resalat_bareSignedAmount_deposit() {
        assertEquals(15000000L, (long) BalanceData.extractTransaction(
            "+15,000,000\n"
            + "06/22_20:37\n"
            + "\u0645\u0627\u0646\u062F\u0647: 2,300,000,000"));
    }

    @Test public void txn_resalat_dateLineBeforeSignedAmount() {
        assertEquals(-200000000L, (long) BalanceData.extractTransaction(
            "1405/06/22 20:37\n"
            + "-200,000,000\n"
            + "\u0645\u0627\u0646\u062F\u0647: 2,279,545,033"));
    }

    @Test public void txn_resalat_persianDigits() {
        assertEquals(-200000000L, (long) BalanceData.extractTransaction(
            "-\u06F2\u06F0\u06F0\u060C\u06F0\u06F0\u06F0\u060C\u06F0\u06F0\u06F0\n"
            + "\u06F0\u06F6/\u06F2\u06F2_\u06F2\u06F0:\u06F3\u06F7 \n"
            + "\u0645\u0627\u0646\u062F\u0647: \u06F2\u060C\u06F2\u06F7\u06F9\u060C\u06F5\u06F4\u06F5\u060C\u06F0\u06F3\u06F3"));
    }

    @Test public void txn_signedAmountWithoutResultingBalance_isNull() {
        assertNull(BalanceData.extractTransaction("-200,000,000 \u062E\u0631\u06CC\u062F \u0627\u0646\u062C\u0627\u0645 \u0634\u062F"));
        assertNull(BalanceData.extractTransaction("-200,000,000\n06/22_20:37"));
    }

    // ---- real-world bank formats (Melli labeled amounts with a trailing sign) ----------
    @Test public void txn_melli_transferWithdrawal_trailingMinus() {
        assertEquals(-1000000L, (long) BalanceData.extractTransaction(
            "\u0627\u0646\u062A\u0642\u0627\u0644\u06CC:1,000,000-\n"
            + "\u062D\u0633\u0627\u0628:10001\n"
            + "\u0645\u0627\u0646\u062F\u0647:208,405\n"
            + "0629-17:23"));
    }

    @Test public void txn_melli_transferDeposit_trailingPlus() {
        assertEquals(1000000L, (long) BalanceData.extractTransaction(
            "\u0627\u0646\u062A\u0642\u0627\u0644\u06CC:1,000,000+\n"
            + "\u062D\u0633\u0627\u0628:10002\n"
            + "\u0645\u0627\u0646\u062F\u0647:1,070,622\n"
            + "0629-17:23"));
    }

    @Test public void txn_melli_onlinePurchase_trailingMinus() {
        assertEquals(-7600000L, (long) BalanceData.extractTransaction(
            "\u062E\u0631\u06CC\u062F\u0627\u06CC\u0646\u062A\u0631\u0646\u062A\u06CC:7,600,000-\n"
            + "\u062D\u0633\u0627\u0628:10002\n"
            + "\u0645\u0627\u0646\u062F\u0647:220,112\n"
            + "0620-23:13"));
    }

    @Test public void txn_melli_posDeposit_keywordOfOppositeKind_isDeposit() {
        // "حواله" is a withdrawal keyword, but the trailing "+" marks a deposit; the sign must win.
        assertEquals(7700000L, (long) BalanceData.extractTransaction(
            "\u062D\u0648\u0627\u0644\u0647 \u067E\u0644:7,700,000+\n"
            + "\u062D\u0633\u0627\u0628:10002\n"
            + "\u0645\u0627\u0646\u062F\u0647:7,820,112\n"
            + "0620-23:12"));
    }

    @Test public void txn_melli_labeledAmountWithoutResultingBalance_isNull() {
        // The label and sign alone are not final: without the stated balance it must be rejected.
        assertNull(BalanceData.extractTransaction(
            "\u0627\u0646\u062A\u0642\u0627\u0644\u06CC:1,000,000-\n\u062D\u0633\u0627\u0628:10001"));
    }

    @Test public void txn_melli_firstMovement_ofAnAccount_isRecordedExactly() {
        // The message states its own amount and sign, so the first movement of an account no longer
        // needs a previous balance to be recorded (previously the delta fallback dropped it).
        Transaction t = BalanceData.parseMovement("Melli", "9830009417",
            "\u0627\u0646\u062A\u0642\u0627\u0644\u06CC:1,000,000-\n"
            + "\u062D\u0633\u0627\u0628:10001\n"
            + "\u0645\u0627\u0646\u062F\u0647:208,405", 1L, false, 0);
        assertNotNull(t);
        assertEquals(-1000000L, t.amount);
        assertEquals("10001", t.account);
    }

    // ---- balance-delta fallback -------------------------------------------------------
    @Test public void delta_amountWhenRegexCannotParse_withdrawal() {
        Transaction t = BalanceData.parseMovement("Blu", "+989999987641",
            "\u0627\u0646\u062A\u0642\u0627\u0644 \u0648\u062C\u0647 \u0627\u0646\u062C\u0627\u0645 \u0634\u062F\n"
            + "\u0645\u0627\u0646\u062F\u0647: 1,800,000 \u0631\u06CC\u0627\u0644", 1L, true, 2_500_000L);
        assertEquals(-700000L, t.amount);
    }

    @Test public void delta_amountWhenRegexCannotParse_deposit() {
        Transaction t = BalanceData.parseMovement("Blu", "+989999987641",
            "\u0648\u0627\u0631\u06CC\u0632 \u0648\u062C\u0647 \u0627\u0632 \u0633\u0627\u0645\u0627\u0646\u0647\n"
            + "\u0645\u0648\u062C\u0648\u062F\u06CC: 2,000,000 \u0631\u06CC\u0627\u0644", 1L, true, 1_300_000L);
        assertEquals(700000L, t.amount);
    }

    @Test public void delta_noMovementKeyword_isNull() {
        assertNull(BalanceData.parseMovement("Blu", "+989999987641",
            "\u0645\u0648\u062C\u0648\u062F\u06CC: 5,000,000 \u0631\u06CC\u0627\u0644", 1L, true, 4_000_000L));
    }

    @Test public void delta_zeroDelta_isNull() {
        assertNull(BalanceData.parseMovement("Blu", "+989999987641",
            "\u0627\u0646\u062A\u0642\u0627\u0644 \u0648\u062C\u0647 \u0627\u0646\u062C\u0627\u0645 \u0634\u062F\n"
            + "\u0645\u0627\u0646\u062F\u0647: 1,800,000 \u0631\u06CC\u0627\u0644", 1L, true, 1_800_000L));
    }

    @Test public void delta_otp_isNull() {
        assertNull(BalanceData.parseMovement("Blu", "+989999987641",
            "\u062E\u0631\u06CC\u062F\n\u0645\u0628\u0644\u063A: 400,000\n\u0631\u0645\u0632: 826129\n"
            + "\u0645\u0648\u062C\u0648\u062F\u06CC: 57,086,241", 1L, true, 56_086_241L));
    }

    @Test public void delta_noPreviousBalance_isNull() {
        assertNull(BalanceData.parseMovement("Blu", "+989999987641",
            "\u0627\u0646\u062A\u0642\u0627\u0644 \u0648\u062C\u0647 \u0627\u0646\u062C\u0627\u0645 \u0634\u062F\n"
            + "\u0645\u0627\u0646\u062F\u0647: 1,800,000 \u0631\u06CC\u0627\u0644", 1L, false, 0));
    }

    // ---- Persian calendar: known anchors -----------------------------------------------
    @Test public void jalali_anchor_farvardin1_1403() {
        // 1403/01/01 (Nowruz) == 2024-03-20
        int[] g = JalaliCalendar.fromGregorian(2024, 3, 20).toGregorian();
        JalaliCalendar j = JalaliCalendar.fromGregorian(2024, 3, 20);
        assertEquals(1403, j.year);
        assertEquals(1, j.month);
        assertEquals(1, j.day);
        assertEquals(2024, g[0]);
        assertEquals(3, g[1]);
        assertEquals(20, g[2]);
    }

    @Test public void jalali_anchor_dey1_1399() {
        // 1399/10/11 == 2020-12-31 (a known jalaali library anchor)
        JalaliCalendar j = JalaliCalendar.fromGregorian(2020, 12, 31);
        assertEquals(1399, j.year);
        assertEquals(10, j.month);
        assertEquals(11, j.day);
    }

    @Test public void jalali_anchor_esfand_last_1403_leap() {
        // 1403 is a leap year: Esfand has 30 days, so 1403/12/30 == 2025-03-20
        assertEquals(30, JalaliCalendar.daysInMonth(1403, 12));
        JalaliCalendar j = JalaliCalendar.fromGregorian(2025, 3, 20);
        assertEquals(1403, j.year);
        assertEquals(12, j.month);
        assertEquals(30, j.day);
    }

    @Test public void jalali_nonLeapEsfand_has29Days() {
        assertFalse(JalaliCalendar.isLeap(1404));
        assertEquals(29, JalaliCalendar.daysInMonth(1404, 12));
    }

    @Test public void jalali_isLeap_1403() {
        assertTrue(JalaliCalendar.isLeap(1403));
        assertFalse(JalaliCalendar.isLeap(1404));
        assertFalse(JalaliCalendar.isLeap(1405));
    }

    @Test public void jalali_monthLengths_firstSix31_lastFive30() {
        for (int m = 1; m <= 6; m++) assertEquals(31, JalaliCalendar.daysInMonth(1403, m));
        for (int m = 7; m <= 11; m++) assertEquals(30, JalaliCalendar.daysInMonth(1403, m));
    }

    @Test public void jalali_roundTrip_knownDates() {
        int[][] cases = {
            {2000, 1, 1}, {1990, 6, 15}, {2023, 8, 23}, {1979, 2, 11},
            {2000, 3, 20}, {2025, 3, 20}, {1988, 3, 21}, {2030, 12, 1}
        };
        for (int[] g : cases) {
            JalaliCalendar j = JalaliCalendar.fromGregorian(g[0], g[1], g[2]);
            int[] back = j.toGregorian();
            assertEquals("gregorian round trip " + g[0] + "/" + g[1] + "/" + g[2],
                g[0], back[0]);
            assertEquals("gregorian round trip " + g[0] + "/" + g[1] + "/" + g[2],
                g[1], back[1]);
            assertEquals("gregorian round trip " + g[0] + "/" + g[1] + "/" + g[2],
                g[2], back[2]);
        }
    }

    // ---- history: year/month grouping -------------------------------------------

    /** Epoch millis for a Gregorian date at local noon, in the device's default time zone. */
    private static long epoch(int gy, int gm, int gd) {
        Calendar c = Calendar.getInstance();
        c.set(gy, gm - 1, gd, 12, 0, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Epoch millis for a Persian date at local noon, converted through the calendar itself. */
    private static long epochJ(int jy, int jm, int jd) {
        int[] g = JalaliCalendar.of(jy, jm, jd).toGregorian();
        return epoch(g[0], g[1], g[2]);
    }

    @Test public void yearsAndMonths_splitAcrossMonthBoundary() {
        // Mid-August and mid-September 2026 fall in Mordad and Shahrivar 1405.
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Saman", epoch(2026, 8, 20), 5000000L));
        txs.add(new Transaction("Saman", epoch(2026, 9, 10), -2000000L));
        HistoryActivity.Lists lists = HistoryActivity.buildLists(txs);
        JalaliCalendar aug = JalaliCalendar.fromGregorian(2026, 8, 20);
        JalaliCalendar sep = JalaliCalendar.fromGregorian(2026, 9, 10);
        assertEquals(1, lists.years.size());
        HistoryActivity.YearGroup year = lists.years.get(0);
        assertEquals(1405, year.year);
        assertEquals(-2000000L + 5000000L, year.sum);
        assertEquals(5000000L, year.dep);
        assertEquals(-2000000L, year.wit);
        assertEquals(2, year.months.size());
        assertEquals(sep.month, year.months.get(0).month);
        assertEquals(aug.month, year.months.get(1).month);
        assertEquals(-2000000L, year.months.get(0).sum);
        assertEquals(5000000L, year.months.get(1).sum);
    }

    @Test public void yearsAndMonths_depWithSubtotalsPerMonth() {
        // Within one year/month, deposit and withdrawal subtotals must accumulate correctly.
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Saman", epoch(2026, 9, 10), 4000000L));
        txs.add(new Transaction("Saman", epoch(2026, 9, 10), -1000000L));
        txs.add(new Transaction("Saman", epoch(2026, 9, 11), 2000000L));
        HistoryActivity.Lists lists = HistoryActivity.buildLists(txs);
        HistoryActivity.YearGroup year = lists.years.get(0);
        HistoryActivity.MonthGroup month = year.months.get(0);
        assertEquals(6000000L, year.dep);
        assertEquals(-1000000L, year.wit);
        assertEquals(6000000L, month.dep);
        assertEquals(-1000000L, month.wit);
        assertEquals(2000000L, month.days.get(0).sum);
        assertEquals(3000000L, month.days.get(1).sum);
    }

    @Test public void yearsAndMonths_splitAcrossYearBoundary() {
        // 1404 is not a leap year, so its last day (Esfand 29 1404) is 2026-03-20 and
        // Farvardin 1 1405 (Nowruz) is 2026-03-21.
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Saman", epoch(2026, 3, 20), 1000000L));
        txs.add(new Transaction("Saman", epoch(2026, 3, 21), -500000L));
        HistoryActivity.Lists lists = HistoryActivity.buildLists(txs);
        assertEquals(2, lists.years.size());
        HistoryActivity.YearGroup y1405 = lists.years.get(0);
        assertEquals(1405, y1405.year);
        assertEquals(-500000L, y1405.sum);
        assertEquals(-500000L, y1405.wit);
        assertEquals(1, y1405.months.get(0).month);
        HistoryActivity.YearGroup y1404 = lists.years.get(1);
        assertEquals(1404, y1404.year);
        assertEquals(1000000L, y1404.sum);
        assertEquals(1000000L, y1404.dep);
        assertEquals(12, y1404.months.get(0).month);
    }

    @Test public void yearsAndMonths_groupDaysWithinMonth() {
        // Newest-first ordering: Gregorian 9/11 (one tx, Shahrivar 20) then 9/10 (two txs, Shahrivar 19).
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Saman", epoch(2026, 9, 10), 1000000L));
        txs.add(new Transaction("Saman", epoch(2026, 9, 10), 3000000L));
        txs.add(new Transaction("Saman", epoch(2026, 9, 11), 2000000L));
        HistoryActivity.Lists lists = HistoryActivity.buildLists(txs);
        assertEquals(1, lists.years.size());
        HistoryActivity.MonthGroup month = lists.years.get(0).months.get(0);
        assertEquals(6000000L, month.sum);
        assertEquals(2, month.days.size());
        assertEquals(20, month.days.get(0).date.day);
        assertEquals(1, month.days.get(0).txs.size());
        assertEquals(2000000L, month.days.get(0).sum);
        assertEquals(19, month.days.get(1).date.day);
        assertEquals(2, month.days.get(1).txs.size());
        assertEquals(4000000L, month.days.get(1).sum);
    }

    @Test public void sums_byPeriod() {
        // Two transactions dated today: net, deposit and withdrawal subtotals must match.
        // Withdrawal subtotals keep their sign (they are summed as signed amounts).
        Calendar c = Calendar.getInstance();
        long now = epoch(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Saman", now, 1000000L));
        txs.add(new Transaction("Saman", now, -300000L));
        HistoryActivity.Lists lists = HistoryActivity.buildLists(txs);
        assertEquals(700000L, lists.today);
        assertEquals(700000L, lists.month);
        assertEquals(700000L, lists.year);
        assertEquals(1000000L, lists.todayDep);
        assertEquals(-300000L, lists.todayWit);
        assertEquals(1000000L, lists.yearDep);
        assertEquals(-300000L, lists.yearWit);
        assertEquals(700000L, lists.years.get(0).sum);
        assertEquals(700000L, lists.years.get(0).months.get(0).sum);
    }

    @Test public void sums_totalCoversAllYears() {
        // The all-time total must span every year, not just the current one:
        // 1404 (Esfand 29, +1M) plus 1405 (Farvardin 1, -500k).
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Saman", epoch(2026, 3, 20), 1000000L));
        txs.add(new Transaction("Saman", epoch(2026, 3, 21), -500000L));
        HistoryActivity.Lists lists = HistoryActivity.buildLists(txs);
        assertEquals(500000L, lists.total);
        assertEquals(2, lists.years.size());
        assertEquals(500000L, lists.years.get(0).sum + lists.years.get(1).sum);
        // The current-year sum excludes past years, so it is not the total.
        assertEquals(-500000L, lists.year);
    }

    // ---- message fingerprints (exact-duplicate detection) -------------------------
    @Test public void messageSig_sameMessage_sameFingerprint() {
        String a = BalanceData.messageSig("500095",
            "\u0645\u0628\u0644\u063A 200,000 \u0631\u06CC\u0627\u0644 \u0628\u0647 \u062D\u0633\u0627\u0628 \u0634\u0645\u0627 \u0648\u0627\u0631\u06CC\u0632 \u0634\u062F");
        String b = BalanceData.messageSig("500095",
            "\u0645\u0628\u0644\u063A 200,000 \u0631\u06CC\u0627\u0644 \u0628\u0647 \u062D\u0633\u0627\u0628 \u0634\u0645\u0627 \u0648\u0627\u0631\u06CC\u0632 \u0634\u062F");
        assertEquals(a, b);
    }

    @Test public void messageSig_digitStyleDoesNotChangeFingerprint() {
        String ascii = BalanceData.messageSig("500095", "\u0645\u0628\u0644\u063A 200,000 \u0631\u06CC\u0627\u0644 \u0648\u0627\u0631\u06CC\u0632 \u0634\u062F");
        String farsi = BalanceData.messageSig("500095", "\u0645\u0628\u0644\u063A \u06F2\u06F0\u06F0\u060C\u06F0\u06F0\u06F0 \u0631\u06CC\u0627\u0644 \u0648\u0627\u0631\u06CC\u0632 \u0634\u062F");
        assertEquals(ascii, farsi);
    }

    @Test public void messageSig_whitespaceVariants_sameFingerprint() {
        String a = BalanceData.messageSig("500095", "\u0645\u0628\u0644\u063A 200,000 \u0631\u06CC\u0627\u0644\u0648\u0627\u0631\u06CC\u0632");
        String b = BalanceData.messageSig("500095", "\u0645\u0628\u0644\u063A  200,000  \u0631\u06CC\u0627\u0644\u0648\u0627\u0631\u06CC\u0632");
        assertEquals(a, b);
    }

    @Test public void messageSig_differentAmounts_differentFingerprint() {
        String a = BalanceData.messageSig("500095", "\u0645\u0628\u0644\u063A 200,000 \u0631\u06CC\u0627\u0644 \u0648\u0627\u0631\u06CC\u0632 \u0634\u062F");
        String b = BalanceData.messageSig("500095", "\u0645\u0628\u0644\u063A 300,000 \u0631\u06CC\u0627\u0644 \u0648\u0627\u0631\u06CC\u0632 \u0634\u062F");
        assertFalse(a.equals(b));
    }

    @Test public void messageSig_differentSender_differentFingerprint() {
        String a = BalanceData.messageSig("500095", "\u0645\u0628\u0644\u063A 200,000 \u0631\u06CC\u0627\u0644 \u0648\u0627\u0631\u06CC\u0632 \u0634\u062F");
        String b = BalanceData.messageSig("b.pasargad", "\u0645\u0628\u0644\u063A 200,000 \u0631\u06CC\u0627\u0644 \u0648\u0627\u0631\u06CC\u0632 \u0634\u062F");
        assertFalse(a.equals(b));
    }

    @Test public void messageSig_nullBody_isNull() {
        assertNull(BalanceData.messageSig("500095", null));
    }

    @Test public void messageSig_sameMovementSameBalance_dedupsAcrossRefAndTime() {
        // Two copies of the same delivery: identical amount + resulting balance, differing only in
        // the volatile trailing metadata (reference number). Must hash alike.
        String a = BalanceData.messageSig("b.pasargad",
            "\u0645\u0628\u0644\u063A 200,000 \u0631\u06CC\u0627\u0644 \u0628\u0627 \u06A9\u0627\u0631\u062A 1234 \u062E\u0631\u06CC\u062F \u0634\u062F\u060C \u0645\u0648\u062C\u0648\u062F\u06CC: 1,000,000 \u0631\u06CC\u0627\u0644");
        String b = BalanceData.messageSig("b.pasargad",
            "\u0645\u0628\u0644\u063A 200,000 \u0631\u06CC\u0627\u0644 \u0628\u0627 \u06A9\u0627\u0631\u062A 9876 \u062E\u0631\u06CC\u062F \u0634\u062F\u060C \u0645\u0648\u062C\u0648\u062F\u06CC: 1,000,000 \u0631\u06CC\u0627\u0644");
        assertEquals(a, b);
    }

    @Test public void messageSig_sameAmountDifferentBalance_isDistinct() {
        // Two genuine movements of the same value move the balance between them; they must NOT collide
        // even when the rest of the wording is identical.
        String a = BalanceData.messageSig("Saman",
            "\u0645\u0628\u0644\u063A 200,000 \u0631\u06CC\u0627\u0644 \u0648\u0627\u0631\u06CC\u0632 \u0634\u062F\u060C \u0645\u0648\u062C\u0648\u062F\u06CC: 1,000,000 \u0631\u06CC\u0627\u0644");
        String b = BalanceData.messageSig("Saman",
            "\u0645\u0628\u0644\u063A 200,000 \u0631\u06CC\u0627\u0644 \u0648\u0627\u0631\u06CC\u0632 \u0634\u062F\u060C \u0645\u0648\u062C\u0648\u062F\u06CC: 1,200,000 \u0631\u06CC\u0627\u0644");
        assertFalse(a.equals(b));
    }

    @Test public void messageSig_differentDirectionSameAbsBalance_isDistinct() {
        // A deposit and a refund can both leave the same balance; the direction is folded in only via
        // the message text when no balance appears, so these two are treated as separate movements.
        String a = BalanceData.messageSig("Saman",
            "\u0645\u0628\u0644\u063A 300,000 \u0631\u06CC\u0627\u0644 \u0648\u0627\u0631\u06CC\u0632 \u0634\u062F\u060C \u0645\u0648\u062C\u0648\u062F\u06CC: 1,000,000 \u0631\u06CC\u0627\u0644");
        String b = BalanceData.messageSig("Saman",
            "\u0645\u0628\u0644\u063A 300,000 \u0631\u06CC\u0627\u0644 \u0628\u0631\u062F\u0627\u0634\u062A \u0634\u062F\u060C \u0645\u0648\u062C\u0648\u062F\u06CC: 1,000,000 \u0631\u06CC\u0627\u0644");
        assertFalse(a.equals(b));
    }

    @Test public void txIdentityKey_prefersSignature_overLegacyTriple() {
        assertEquals("s:abc", BalanceData.txIdentityKey(new Transaction("Saman", 5, 100, "abc")));
        assertEquals("Saman|5|100", BalanceData.txIdentityKey(new Transaction("Saman", 5, 100)));
    }

    @Test public void txIdentityKey_appendsAccount_sameSlotMovementsStayDistinct() {
        // Sigless legacy entries for the same amount and moment on two different accounts of one
        // bank must not collide: the account number keeps the identity per bank|account slot.
        assertEquals("Mellat|5|100|1110000222",
            BalanceData.txIdentityKey(new Transaction("Mellat", "1110000222", 5, 100, null)));
        assertEquals("Mellat|5|100|1111111111",
            BalanceData.txIdentityKey(new Transaction("Mellat", "1111111111", 5, 100, null)));
        assertEquals("s:abc", BalanceData.txIdentityKey(
            new Transaction("Mellat", "1110000222", 5, 100, "abc")));
    }

    @Test public void buildLists_doesNotMutateInput() {
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Saman", epoch(2026, 9, 10), 1000000L));
        txs.add(new Transaction("Saman", epoch(2026, 8, 20), -500000L));
        List<Transaction> copy = new ArrayList<>(txs);
        HistoryActivity.buildLists(txs);
        assertEquals(copy, txs);
    }

    // ---- per-bank history filter ---------------------------------------------

    @Test public void filterByBank_isolatesOneBank_totalsMatch() {
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Saman", epoch(2026, 9, 10), 1000000L));
        txs.add(new Transaction("Mellat", epoch(2026, 9, 10), -500000L));
        txs.add(new Transaction("Saman", epoch(2026, 9, 11), 2000000L));
        List<Transaction> only = HistoryActivity.filterByBank(txs, "Saman");
        assertEquals(2, only.size());
        for (HistoryActivity.DayGroup g : HistoryActivity.buildLists(only).years.get(0).months.get(0).days) {
            for (Transaction t : g.txs) assertEquals("Saman", t.bank);
        }
        assertEquals(3000000L, HistoryActivity.buildLists(only).total);
    }

    @Test public void filterByBank_unknownBank_isEmpty() {
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Saman", epoch(2026, 9, 10), 1000000L));
        assertTrue(HistoryActivity.filterByBank(txs, "Sepah").isEmpty());
        assertTrue(HistoryActivity.filterByBank(new ArrayList<>(), "Saman").isEmpty());
    }

    @Test public void filterByBank_keepsInputUnchanged() {
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Saman", epoch(2026, 9, 10), 1000000L));
        txs.add(new Transaction("Mellat", epoch(2026, 9, 10), -500000L));
        List<Transaction> copy = new ArrayList<>(txs);
        HistoryActivity.filterByBank(txs, "Saman");
        assertEquals(copy, txs);
    }

    // ---- per-account history filter ---------------------------------------------

    @Test public void filterByAccount_isolatesOneAccount_totalsMatch() {
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Mellat", "1110000222", epoch(2026, 9, 10), 1000000L, null));
        txs.add(new Transaction("Mellat", "1111111111", epoch(2026, 9, 10), -500000L, null));
        txs.add(new Transaction("Mellat", "1110000222", epoch(2026, 9, 11), 2000000L, null));
        List<Transaction> only = HistoryActivity.filterByAccount(txs, "1110000222");
        assertEquals(2, only.size());
        for (Transaction t : only) assertEquals("1110000222", t.account);
        assertEquals(3000000L, HistoryActivity.buildLists(only).total);
    }

    @Test public void filterByAccount_unknownAccount_isEmpty() {
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Mellat", "1110000222", epoch(2026, 9, 10), 1000000L, null));
        assertTrue(HistoryActivity.filterByAccount(txs, "7777777777").isEmpty());
        assertTrue(HistoryActivity.filterByAccount(new ArrayList<>(), "1110000222").isEmpty());
    }

    @Test public void filterByAccount_keepsInputUnchanged() {
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Mellat", "1110000222", epoch(2026, 9, 10), 1000000L, null));
        List<Transaction> copy = new ArrayList<>(txs);
        HistoryActivity.filterByAccount(txs, "1110000222");
        assertEquals(copy, txs);
    }

    @Test public void filterByAccount_composesAfterBank() {
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Mellat", "1110000222", epoch(2026, 9, 10), 1000000L, null));
        txs.add(new Transaction("Mellat", "1111111111", epoch(2026, 9, 10), -500000L, null));
        List<Transaction> only = HistoryActivity.filterByAccount(
            HistoryActivity.filterByBank(txs, "Mellat"), "1110000222");
        assertEquals(1, only.size());
        assertEquals("Mellat", only.get(0).bank);
        assertEquals("1110000222", only.get(0).account);
    }

    // ---- history filters: direction --------------------------------------------------

    @Test public void filter_direction_depositsOnly() {
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Saman", epoch(2026, 9, 10), 1000000L));
        txs.add(new Transaction("Saman", epoch(2026, 9, 10), -300000L));
        HistoryActivity.Filter f = new HistoryActivity.Filter(HistoryActivity.DIR_DEPOSIT,
            HistoryActivity.RANGE_ALL, null, null);
        List<Transaction> out = HistoryActivity.applyFilters(txs, f);
        assertEquals(1, out.size());
        assertEquals(1000000L, out.get(0).amount);
    }

    @Test public void filter_direction_withdrawalsOnly() {
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Mellat", epoch(2026, 9, 10), 1000000L));
        txs.add(new Transaction("Mellat", epoch(2026, 9, 10), -300000L));
        txs.add(new Transaction("Mellat", epoch(2026, 9, 10), -700000L));
        HistoryActivity.Filter f = new HistoryActivity.Filter(HistoryActivity.DIR_WITHDRAWAL,
            HistoryActivity.RANGE_ALL, null, null);
        List<Transaction> out = HistoryActivity.applyFilters(txs, f);
        assertEquals(2, out.size());
        for (Transaction t : out) assertTrue(t.amount < 0);
    }

    @Test public void filter_direction_allKeepsEverything() {
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Saman", epoch(2026, 9, 10), 1000000L));
        txs.add(new Transaction("Saman", epoch(2026, 9, 10), -300000L));
        assertEquals(2, HistoryActivity.applyFilters(txs, HistoryActivity.Filter.ALL).size());
    }

    @Test public void filter_isActive_reflectsBounds() {
        HistoryActivity.Filter none = HistoryActivity.Filter.ALL;
        assertFalse(none.isActive());
        assertTrue(new HistoryActivity.Filter(HistoryActivity.DIR_WITHDRAWAL,
            HistoryActivity.RANGE_ALL, null, null).isActive());
        assertTrue(new HistoryActivity.Filter(HistoryActivity.DIR_ALL,
            HistoryActivity.RANGE_CUSTOM, CalDate.of(1405, 6, 1), null).isActive());
    }

    // ---- history filters: date range -------------------------------------------------

    @Test public void filter_dateRange_inclusiveBoundaries() {
        // Shahrivar 1405 runs 1405/6/1..1405/6/30; the 1405/7/1 transaction must fall outside.
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Saman", epochJ(1405, 6, 1), 1000000L));
        txs.add(new Transaction("Saman", epochJ(1405, 6, 15), -300000L));
        txs.add(new Transaction("Saman", epochJ(1405, 7, 1), 500000L));
        HistoryActivity.Filter f = new HistoryActivity.Filter(HistoryActivity.DIR_ALL,
            HistoryActivity.RANGE_CUSTOM, CalDate.of(1405, 6, 1), CalDate.of(1405, 6, 30));
        List<Transaction> out = HistoryActivity.applyFilters(txs, f);
        assertEquals(2, out.size());
        assertTrue(out.contains(txs.get(0)));
        assertTrue(out.contains(txs.get(1)));
    }

    @Test public void filter_dateRange_openEnds() {
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Saman", epochJ(1405, 5, 20), 1000000L));
        txs.add(new Transaction("Saman", epochJ(1405, 6, 1), -300000L));
        txs.add(new Transaction("Saman", epochJ(1404, 12, 29), 500000L));
        // From 1405/6/1 onward (no upper bound).
        HistoryActivity.Filter from = new HistoryActivity.Filter(HistoryActivity.DIR_ALL,
            HistoryActivity.RANGE_CUSTOM, CalDate.of(1405, 6, 1), null);
        List<Transaction> outFrom = HistoryActivity.applyFilters(txs, from);
        assertEquals(1, outFrom.size());
        assertEquals(-300000L, outFrom.get(0).amount);
        // Up to 1405/6/1 inclusive (no lower bound).
        HistoryActivity.Filter to = new HistoryActivity.Filter(HistoryActivity.DIR_ALL,
            HistoryActivity.RANGE_CUSTOM, null, CalDate.of(1405, 6, 1));
        assertEquals(3, HistoryActivity.applyFilters(txs, to).size());
    }

    @Test public void filter_directionAndDate_compose() {
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Saman", epochJ(1405, 6, 1), 1000000L));
        txs.add(new Transaction("Saman", epochJ(1405, 6, 2), -300000L));
        txs.add(new Transaction("Saman", epochJ(1405, 7, 1), 500000L));
        HistoryActivity.Filter f = new HistoryActivity.Filter(HistoryActivity.DIR_DEPOSIT,
            HistoryActivity.RANGE_CUSTOM, CalDate.of(1405, 6, 1), CalDate.of(1405, 6, 30));
        List<Transaction> out = HistoryActivity.applyFilters(txs, f);
        assertEquals(1, out.size());
        assertEquals(1000000L, out.get(0).amount);
    }

    @Test public void filter_afterBankFilter() {
        // The full pipeline: isolate a bank, then narrow by direction and date.
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Saman", epochJ(1405, 6, 1), 1000000L));
        txs.add(new Transaction("Saman", epochJ(1405, 6, 1), -300000L));
        txs.add(new Transaction("Mellat", epochJ(1405, 6, 5), 2000000L));
        txs.add(new Transaction("Saman", epochJ(1404, 12, 1), 500000L));
        HistoryActivity.Filter f = new HistoryActivity.Filter(HistoryActivity.DIR_WITHDRAWAL,
            HistoryActivity.RANGE_CUSTOM, CalDate.of(1405, 1, 1), null);
        List<Transaction> out = HistoryActivity.applyFilters(
            HistoryActivity.filterByBank(txs, "Saman"), f);
        assertEquals(1, out.size());
        assertEquals("Saman", out.get(0).bank);
        assertEquals(-300000L, out.get(0).amount);
    }

    @Test public void filter_totalsReflectFilter() {
        // The hero and period figures must be computed over the filtered set, not the full set.
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Saman", epochJ(1405, 6, 1), 1000000L));
        txs.add(new Transaction("Saman", epochJ(1405, 6, 1), -300000L));
        txs.add(new Transaction("Saman", epochJ(1404, 12, 1), 900000L));
        HistoryActivity.Filter f = new HistoryActivity.Filter(HistoryActivity.DIR_ALL,
            HistoryActivity.RANGE_CUSTOM, CalDate.of(1405, 1, 1), null);
        HistoryActivity.Lists lists = HistoryActivity.buildLists(HistoryActivity.applyFilters(txs, f));
        assertEquals(700000L, lists.total);
        assertEquals(1, lists.years.size());
        assertEquals(1405, lists.years.get(0).year);
    }

    @Test public void filter_doesNotMutateInput() {
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Saman", epoch(2026, 9, 10), 1000000L));
        txs.add(new Transaction("Saman", epoch(2026, 9, 10), -300000L));
        List<Transaction> copy = new ArrayList<>(txs);
        HistoryActivity.applyFilters(txs, new HistoryActivity.Filter(HistoryActivity.DIR_DEPOSIT,
            HistoryActivity.RANGE_CUSTOM, null, null));
        assertEquals(copy, txs);
    }

    // ---- history filters: date presets ----------------------------------------------

    @Test public void rangePreset_today_wrapsNow() {
        HistoryActivity.Filter f = HistoryActivity.rangePreset(HistoryActivity.Filter.ALL,
            HistoryActivity.RANGE_TODAY, JalaliCalendar.of(1405, 6, 13));
        assertEquals(HistoryActivity.RANGE_TODAY, f.rangePreset);
        assertEquals(1405, f.from.year);
        assertEquals(6, f.from.month);
        assertEquals(13, f.from.day);
        assertEquals(f.from.year, f.to.year);
        assertEquals(f.from.month, f.to.month);
        assertEquals(f.from.day, f.to.day);
    }

    @Test public void rangePreset_month_boundsFirstAndLastDay() {
        // Shahrivar (month 6) has 31 days; the preset must span exactly the month.
        HistoryActivity.Filter f = HistoryActivity.rangePreset(HistoryActivity.Filter.ALL,
            HistoryActivity.RANGE_MONTH, JalaliCalendar.of(1405, 6, 13));
        assertEquals(1405, f.from.year);
        assertEquals(6, f.from.month);
        assertEquals(1, f.from.day);
        assertEquals(1405, f.to.year);
        assertEquals(6, f.to.month);
        assertEquals(31, f.to.day);
    }

    @Test public void rangePreset_month_leapEsfand_gets30Days() {
        HistoryActivity.Filter f = HistoryActivity.rangePreset(HistoryActivity.Filter.ALL,
            HistoryActivity.RANGE_MONTH, JalaliCalendar.of(1403, 12, 10));
        assertEquals(30, f.to.day);
    }

    @Test public void rangePreset_year_boundsFarvardinToLastEsfand() {
        // 1404 is not a leap year, so the year ends on Esfand 29.
        HistoryActivity.Filter f = HistoryActivity.rangePreset(HistoryActivity.Filter.ALL,
            HistoryActivity.RANGE_YEAR, JalaliCalendar.of(1404, 7, 10));
        assertEquals(1404, f.from.year);
        assertEquals(1, f.from.month);
        assertEquals(1, f.from.day);
        assertEquals(1404, f.to.year);
        assertEquals(12, f.to.month);
        assertEquals(29, f.to.day);
    }

    @Test public void rangePreset_all_clearsBoundsKeepsDirection() {
        HistoryActivity.Filter set = new HistoryActivity.Filter(HistoryActivity.DIR_WITHDRAWAL,
            HistoryActivity.RANGE_CUSTOM, CalDate.of(1405, 6, 1), CalDate.of(1405, 6, 30));
        HistoryActivity.Filter cleared = HistoryActivity.rangePreset(set, HistoryActivity.RANGE_ALL,
            JalaliCalendar.of(1405, 6, 13));
        assertEquals(HistoryActivity.RANGE_ALL, cleared.rangePreset);
        assertNull(cleared.from);
        assertNull(cleared.to);
        assertEquals(HistoryActivity.DIR_WITHDRAWAL, cleared.direction);
    }

    // ---- Gregorian (International region) calendar -----------------------------------

    @Test public void gregorian_buildLists_wrapsTheYear() {
        // 2025-12-31 and 2026-01-01 are different Gregorian years and months.
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Saman", epoch(2025, 12, 31), 1000000L));
        txs.add(new Transaction("Saman", epoch(2026, 1, 1), -500000L));
        HistoryActivity.Lists lists = HistoryActivity.buildLists(txs, false);
        assertEquals(500000L, lists.total);
        assertEquals(2, lists.years.size());
        HistoryActivity.YearGroup y2026 = lists.years.get(0);
        assertEquals(2026, y2026.year);
        assertEquals(-500000L, y2026.sum);
        assertEquals(1, y2026.months.get(0).month);
        assertEquals(1, y2026.months.get(0).days.get(0).date.day);
        HistoryActivity.YearGroup y2025 = lists.years.get(1);
        assertEquals(2025, y2025.year);
        assertEquals(12, y2025.months.get(0).month);
        assertEquals(31, y2025.months.get(0).days.get(0).date.day);
    }

    @Test public void gregorian_filter_dateRange_inclusiveBoundaries() {
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Saman", epoch(2026, 2, 1), 1000000L));
        txs.add(new Transaction("Saman", epoch(2026, 2, 28), -300000L));
        txs.add(new Transaction("Saman", epoch(2026, 3, 1), 500000L));
        HistoryActivity.Filter f = new HistoryActivity.Filter(HistoryActivity.DIR_ALL,
            HistoryActivity.RANGE_CUSTOM, CalDate.of(2026, 2, 1), CalDate.of(2026, 2, 28));
        List<Transaction> out = HistoryActivity.applyFilters(txs, f, false);
        assertEquals(2, out.size());
        assertTrue(out.contains(txs.get(0)));
        assertTrue(out.contains(txs.get(1)));
    }

    @Test public void gregorian_rangePreset_leapFebruary_gets29Days() {
        HistoryActivity.Filter f = HistoryActivity.rangePreset(HistoryActivity.Filter.ALL,
            HistoryActivity.RANGE_MONTH, CalDate.of(2028, 2, 15), false);
        assertEquals(1, f.from.day);
        assertEquals(29, f.to.day);
    }

    @Test public void gregorian_rangePreset_nonLeapFebruary_gets28Days() {
        HistoryActivity.Filter f = HistoryActivity.rangePreset(HistoryActivity.Filter.ALL,
            HistoryActivity.RANGE_MONTH, CalDate.of(2026, 2, 15), false);
        assertEquals(28, f.to.day);
    }

    @Test public void gregorian_daysInMonth_leapAndNonLeap() {
        assertEquals(29, CalDate.daysInMonth(2028, 2, false));
        assertEquals(28, CalDate.daysInMonth(2026, 2, false));
        assertEquals(31, CalDate.daysInMonth(2026, 12, false));
        assertEquals(30, CalDate.daysInMonth(2026, 11, false));
    }

    @Test public void gregorian_conversions_roundTrip() {
        CalDate d = CalDate.fromGregorian(2026, 9, 21, false);
        assertEquals(2026, d.year);
        assertEquals(9, d.month);
        assertEquals(21, d.day);
        int[] g = d.toGregorian(false);
        assertEquals(2026, g[0]);
        assertEquals(9, g[1]);
        assertEquals(21, g[2]);
        // The same Gregorian day named in the Persian calendar converts back unchanged.
        CalDate j = CalDate.fromGregorian(2026, 9, 21, true);
        assertEquals(1405, j.year);
        assertEquals(6, j.month);
        assertEquals(30, j.day);
        int[] back = j.toGregorian(true);
        assertEquals(2026, back[0]);
        assertEquals(9, back[1]);
        assertEquals(21, back[2]);
    }

    // ---- history filters: custom-range calendar ----------------------------------

    @Test public void pickDay_firstTapSetsFrom() {
        CalDate[] picked = new CalDate[2];
        HistoryActivity.pickDay(picked, CalDate.of(1405, 6, 15));
        assertEquals(1405, picked[0].year);
        assertEquals(6, picked[0].month);
        assertEquals(15, picked[0].day);
        assertNull(picked[1]);
    }

    @Test public void pickDay_secondTapSetsTo() {
        CalDate[] picked = new CalDate[2];
        HistoryActivity.pickDay(picked, CalDate.of(1405, 6, 1));
        HistoryActivity.pickDay(picked, CalDate.of(1405, 6, 15));
        assertEquals(1, picked[0].day);
        assertEquals(15, picked[1].day);
    }

    @Test public void pickDay_toBeforeFromSwapsBounds() {
        CalDate[] picked = new CalDate[2];
        HistoryActivity.pickDay(picked, CalDate.of(1405, 6, 15));
        HistoryActivity.pickDay(picked, CalDate.of(1405, 6, 1));
        assertEquals(1, picked[0].day);
        assertEquals(15, picked[1].day);
        assertEquals(6, picked[0].month);
        assertEquals(6, picked[1].month);
        assertEquals(1405, picked[0].year);
        assertEquals(1405, picked[1].year);
    }

    @Test public void pickDay_tapWhileClosedStartsFreshFrom() {
        CalDate[] picked = new CalDate[2];
        HistoryActivity.pickDay(picked, CalDate.of(1405, 6, 1));
        HistoryActivity.pickDay(picked, CalDate.of(1405, 6, 15));
        HistoryActivity.pickDay(picked, CalDate.of(1405, 7, 1));
        assertEquals(1405, picked[0].year);
        assertEquals(7, picked[0].month);
        assertEquals(1, picked[0].day);
        assertNull(picked[1]);
    }

    @Test public void pickDay_sameDayTwiceClosesTheRange() {
        CalDate[] picked = new CalDate[2];
        CalDate d = CalDate.of(1405, 6, 1);
        HistoryActivity.pickDay(picked, d);
        HistoryActivity.pickDay(picked, d);
        assertEquals(1, picked[0].day);
        assertEquals(1, picked[1].day);
    }

    @Test public void weekdayIndex_saturdayIsTheLeadingColumn() {
        // Farvardin 1 1403 was Wednesday (index 4); Farvardin 4 was a Saturday (index 0).
        assertEquals(4, CalDate.weekdayIndex(CalDate.of(1403, 1, 1), true));
        assertEquals(0, CalDate.weekdayIndex(CalDate.of(1403, 1, 4), true));
        assertEquals(2, CalDate.weekdayIndex(CalDate.of(1403, 1, 6), true));
    }

    @Test public void weekdayIndex_advancesOnePerDay() {
        for (int d = 1; d <= 7; d++) {
            int expected = (4 + (d - 1)) % 7;
            assertEquals("Farvardin " + d + " 1403", expected,
                CalDate.weekdayIndex(CalDate.of(1403, 1, d), true));
        }
    }

    @Test public void weekdayIndex_international_mondayIsTheLeadingColumn() {
        // 2026-02-02 was a Monday (index 0); the preceding Sunday is index 6.
        assertEquals(0, CalDate.weekdayIndex(CalDate.of(2026, 2, 2), false));
        assertEquals(6, CalDate.weekdayIndex(CalDate.of(2026, 2, 1), false));
        assertEquals(1, CalDate.weekdayIndex(CalDate.of(2026, 2, 3), false));
    }

    // ---- Persian calendar numerals have no thousands grouping ----

    @Test public void faDigits_year_hasNoGroupingSeparator() {
        // A Jalali year like 1403 must read "۱۴۰۳", never "۱٬۴۰۳".
        assertEquals("\u06f1\u06f4\u06f0\u06f3", HistoryActivity.faDigits(1403));
        assertEquals("\u06f1\u06f4\u06f0\u06f5", HistoryActivity.faDigits(1405));
        assertEquals("\u06f1\u06f3\u06f9\u06f9", HistoryActivity.faDigits(1399));
    }

    @Test public void faDigits_day_hasNoGroupingSeparator() {
        assertEquals("\u06f1\u06f2", HistoryActivity.faDigits(12));
        assertEquals("\u06f5", HistoryActivity.faDigits(5));
        assertEquals("\u06f0", HistoryActivity.faDigits(0));
    }

    @Test public void faDigitsString_timeOnlyReplacesDigits() {
        assertEquals("\u06f1\u06f2:\u06f3\u06f4", HistoryActivity.faDigitsString("12:34"));
        assertEquals("-", HistoryActivity.faDigitsString("-"));
    }
}
