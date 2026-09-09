package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    @Test public void buildLists_doesNotMutateInput() {
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Saman", epoch(2026, 9, 10), 1000000L));
        txs.add(new Transaction("Saman", epoch(2026, 8, 20), -500000L));
        List<Transaction> copy = new ArrayList<>(txs);
        HistoryActivity.buildLists(txs);
        assertEquals(copy, txs);
    }
}
