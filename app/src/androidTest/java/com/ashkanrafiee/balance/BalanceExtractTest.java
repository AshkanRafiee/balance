package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Balance amount extraction edge cases (instrumented, pure logic, no UI). */
@RunWith(AndroidJUnit4.class)
public class BalanceExtractTest {

    // ---- Persian balance messages ------------------------------------------------------
    @Test public void extract_mojudiHesab_withCommas() {
        assertEquals(1250000L, BalanceData.extract("\u0645\u0648\u062C\u0648\u062F\u06CC \u062D\u0633\u0627\u0628 \u0634\u0645\u0627: 1,250,000 \u0631\u06CC\u0627\u0644"));
    }

    @Test public void extract_mandeHesab_withCommas() {
        assertEquals(2400000L, BalanceData.extract("\u0645\u0627\u0646\u062F\u0647 \u062D\u0633\u0627\u0628 \u0634\u0645\u0627: 2,400,000 \u0631\u06CC\u0627\u0644"));
    }

    @Test public void extract_mojudi_withoutSeparators() {
        assertEquals(850000L, BalanceData.extract("\u0645\u0648\u062C\u0648\u062F\u06CC \u062D\u0633\u0627\u0628 \u0634\u0645\u0627 850000 \u0631\u06CC\u0627\u0644"));
    }

    @Test public void extract_mojudi_compact() {
        assertEquals(300000L, BalanceData.extract("\u0645\u0648\u062C\u0648\u062F\u06CC: 300,000"));
    }

    // ---- English balance messages ------------------------------------------------------
    @Test public void extract_englishAvailableBalance() {
        assertEquals(5000L, BalanceData.extract("Your available balance is 5,000"));
        assertEquals(123456789L, BalanceData.extract("Your balance is 123,456,789 USD"));
    }

    @Test public void extract_shortEnglishKeywordBal() {
        assertEquals(500L, BalanceData.extract("bal 500"));
    }

    // ---- Persian/Arabic digits ---------------------------------------------------------
    @Test public void extract_persianDigits_withThousandsSeparator() {
        assertEquals(1250000L, BalanceData.extract("\u0645\u0648\u062C\u0648\u062F\u06CC \u062D\u0633\u0627\u0628 \u0634\u0645\u0627: \u06F1\u066C\u06F2\u06F5\u06F0\u066C\u06F0\u06F0\u06F0 \u0631\u06CC\u0627\u0644"));
    }

    @Test public void extract_arabicIndicDigits() {
        assertEquals(5000L, BalanceData.extract("\u0645\u0648\u062C\u0648\u062F\u06CC: \u0665\u0660\u0660\u0660"));
    }

    // ---- OTP must never be parsed as a balance -----------------------------------------
    @Test public void extract_otpPersianRamz_rejected() {
        assertEquals(-1L, BalanceData.extract("\u0631\u0645\u0632 \u0648\u0631\u0648\u062F \u0634\u0645\u0627: 123456"));
    }

    @Test public void extract_otpPersianKodTaeed_rejected() {
        assertEquals(-1L, BalanceData.extract("\u06A9\u062F \u062A\u0627\u06CC\u06CC\u062F \u0627\u0631\u0633\u0627\u0644\u06CC: 4455"));
        assertEquals(-1L, BalanceData.extract("\u06A9\u062F  \u062A\u0623\u06CC\u06CC\u062F \u0648\u0631\u0648\u062F: 987"));
    }

    @Test public void extract_otpPersianPouya_rejected() {
        assertEquals(-1L, BalanceData.extract("\u067E\u0648\u06CC\u0627 \u0648\u0631\u0648\u062F \u062E\u0648\u062F: 1111"));
    }

    @Test public void extract_otpEnglishCodeAndOtp_rejected() {
        assertEquals(-1L, BalanceData.extract("Verification code: 44221"));
        assertEquals(-1L, BalanceData.extract("Your OTP is 1234"));
    }

    // ---- candidate selection -----------------------------------------------------------
    @Test public void extract_multipleCandidates_keepsLast() {
        assertEquals(200L, BalanceData.extract("\u0645\u0648\u062C\u0648\u062F\u06CC 100 \u062A\u0648\u0645\u0627\u0646\u060C \u0645\u0648\u062C\u0648\u062F\u06CC 200"));
    }

    // ---- malformed / garbage -----------------------------------------------------------
    @Test public void extract_noKeyword_returnsMinusOne() {
        assertEquals(-1L, BalanceData.extract("سلام چطوری 123"));
        assertEquals(-1L, BalanceData.extract(""));
        assertEquals(-1L, BalanceData.extract(null));
    }

    @Test public void extract_zero_returnsZero() {
        assertEquals(0L, BalanceData.extract("\u0645\u0648\u062C\u0648\u062F\u06CC: 0"));
    }

    @Test public void extract_decimalPoints_usesIntegerPart() {
        assertEquals(1L, BalanceData.extract("\u0645\u0648\u062C\u0648\u062F\u06CC: 1.5"));
    }

    @Test public void extract_negativeSign_lost() {
        assertEquals(500L, BalanceData.extract("\u0645\u0648\u062C\u0648\u062F\u06CC \u062D\u0633\u0627\u0628 \u0634\u0645\u0627: -500"));
    }

    @Test public void extract_numberTooFarFromKeyword_returnsMinusOne() {
        assertEquals(-1L, BalanceData.extract("\u0645\u0648\u062C\u0648\u062F\u06CC " + "\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0" + "999"));
    }

    // ---- digits ------------------------------------------------------------------------
    @Test public void digits_convertsPersian() {
        assertEquals("abc12def", BalanceData.digits("abc\u06F1\u06F2def"));
    }

    @Test public void digits_convertsArabicIndic() {
        assertEquals("091", BalanceData.digits("\u0660\u0669\u0661"));
    }

    @Test public void digits_mixesBothDigitSets() {
        assertEquals("1234", BalanceData.digits("\u06F1\u06F2\u0663\u0664"));
    }

    @Test public void digits_leavesOtherTextAlone() {
        assertEquals("", BalanceData.digits(""));
        assertEquals("سلام", BalanceData.digits("\u0633\u0644\u0627\u0645"));
    }
}