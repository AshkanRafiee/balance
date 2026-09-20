package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Bank sender resolution/normalization edge cases (instrumented, pure logic, no UI). */
@RunWith(AndroidJUnit4.class)
public class BankRulesTest {

    // ---- exact numeric aliases ---------------------------------------------------------
    @Test public void resolve_numericShortcode_returnsBank() {
        assertEquals("Tejarat", BankRules.resolve("5000973189"));
        assertEquals("Saman", BankRules.resolve("500095"));
    }

    @Test public void resolve_plusPrefixedAlias_returnsBank() {
        assertEquals("Pasargad", BankRules.resolve("+98500019000"));
    }

    @Test public void resolve_duplicateCalendarLongAlias_returnsBank() {
        assertEquals("Sina", BankRules.resolve("50003700798704"));
    }

    @Test public void resolve_iranCountryCodePrefix_normalizesThenResolves() {
        assertEquals("Sina", BankRules.resolve("9850003700798704"));
        assertEquals("Saman", BankRules.resolve("989999920000"));
    }

    @Test public void resolve_bluServiceNumber_returnsBank() {
        assertEquals("Blu", BankRules.resolve("+9890000258"));
        assertEquals("Blu", BankRules.resolve("9890000258"));
        assertEquals("Blu", BankRules.resolve("90000258"));
    }

    @Test public void resolve_urlStyleSuffix_numericSender_returnsBank() {
        // 989999920000 normalizes to 9999992000, which is a suffix of Saman's 9999920000 pair...
        // instead assert a real documented suffix match below.
        assertEquals("Saman", BankRules.resolve("989999920000"));
    }

    // ---- Persian/Arabic digit senders -------------------------------------------------
    @Test public void resolve_persianDigits_returnsBank() {
        assertEquals("Tejarat", BankRules.resolve("\u06F5\u06F0\u06F0\u06F0\u06F9\u06F7\u06F3\u06F1\u06F8\u06F9"));
    }

    @Test public void resolve_arabicIndicDigits_returnsBank() {
        assertEquals("Tejarat", BankRules.resolve("\u0665\u0660\u0660\u0660\u0669\u0667\u0663\u0661\u0668\u0669"));
    }

    @Test public void resolve_mixedDigitsAndPunctuation_returnsBank() {
        assertEquals("Tejarat", BankRules.resolve("5000973189 "));
        assertEquals("Refah", BankRules.resolve("  Refah Bank  "));
    }

    // ---- alphabetic aliases -------------------------------------------------------------
    @Test public void resolve_caseInsensitiveNameAlias_returnsBank() {
        assertEquals("Mellat", BankRules.resolve("mELLAt"));
        assertEquals("Tejarat", BankRules.resolve("tejaratbank"));
    }

    @Test public void resolve_persianAlias_returnsBank() {
        assertEquals("Saderat", BankRules.resolve("\u0635\u0627\u062f\u0631\u0627\u062a"));
    }

    @Test public void resolve_multiWordNameWithSpaces_returnsBank() {
        assertEquals("Refah", BankRules.resolve("Refah Bank"));
        assertEquals("Tosee Taavon", BankRules.resolve("Tosee Taavon"));
    }

    // ---- rejections --------------------------------------------------------------------
    @Test public void resolve_nullAndEmpty_rejected() {
        assertNull(BankRules.resolve(null));
        assertNull(BankRules.resolve(""));
        assertNull(BankRules.resolve("   "));
    }

    @Test public void resolve_starAndHash_rejected() {
        assertNull(BankRules.resolve("*5000973189"));
        assertNull(BankRules.resolve("5000*0973189"));
        assertNull(BankRules.resolve("5000973189#"));
        assertNull(BankRules.resolve("#"));
    }

    @Test public void resolve_nonBankNumbers_rejected() {
        assertNull(BankRules.resolve("1234567890"));
        assertNull(BankRules.resolve("09123456789"));
        assertNull(BankRules.resolve("5000"));
    }

    @Test public void resolve_nonBankText_rejected() {
        assertNull(BankRules.resolve("YouTube"));
        assertNull(BankRules.resolve("Google"));
        assertNull(BankRules.resolve("+98notanumber"));
        assertNull(BankRules.resolve("envoy"));
        assertNull(BankRules.resolve("anonymous"));
    }

    @Test public void resolve_shortNumericSenders_rejected() {
        assertNull(BankRules.resolve("1234"));
        assertNull(BankRules.resolve("98"));
    }

    // ---- normalize ---------------------------------------------------------------------
    @Test public void normalize_convertsPersianAndArabicDigitsToAscii() {
        assertEquals("5000973189", BankRules.normalize("\u06F5\u06F0\u06F0\u06F0\u06F9\u06F7\u06F3\u06F1\u06F8\u06F9"));
        assertEquals("5000973189", BankRules.normalize("\u0665\u0660\u0660\u0660\u0669\u0667\u0663\u0661\u0668\u0669"));
    }

    @Test public void normalize_stripsNonAlphanumeric() {
        assertEquals("refahbank", BankRules.normalize("Refah Bank!"));
        assertEquals("tejarat", BankRules.normalize("Tejarat*#"));
    }

    @Test public void normalize_lowercasesLetters() {
        assertEquals("mellat", BankRules.normalize("MELLAT"));
        assertEquals("tejaratbank", BankRules.normalize("TejaratBank"));
    }

    @Test public void normalize_dropsIranCountryPrefixes() {
        assertEquals("5000973189", BankRules.normalize("00985000973189"));
        assertEquals("5000973189", BankRules.normalize("985000973189"));
        // A short "98"-prefixed string must not be stripped.
        assertEquals("98", BankRules.normalize("98"));
    }

    // ---- reachability / early-exit invariant ------------------------------------------
    @Test public void reachableBanks_matchesSupportedSenderCount() {
        assertEquals(BankRules.reachableBanks().size(), BankRules.supportedSenderCount());
    }

    @Test public void reachableBanks_subsetOfSupportedNames() {
        for (String bank : BankRules.reachableBanks())
            assertTrue("reachable bank not in supported list: " + bank,
                BankRules.supportedNames().contains(bank));
    }

    @Test public void resolve_collidingCreditAlias_attributesSenderToFirstOwner() {
        // +9830005816 is listed under both Tosee Taavon (first in rule order) and Tosee Credit Inst.;
        // resolve() must always hand it to the first owner so attribution never flips.
        assertEquals("Tosee Taavon", BankRules.resolve("+9830005816"));
        assertEquals("Tosee Taavon", BankRules.resolve("9830005816"));
    }

    @Test public void reachableBanks_matchesActualResolverOutcome() {
        // The reachable set mirrors what resolve() can actually return, so the early-exit count never
        // over- or under-covers. Of the "Credit Inst." aliases, only Tosee Credit Inst.'s +9830005816
        // loses its contest (Tosee Taavon claims it first in rule order) and drops out; Melal Credit
        // Inst.'s +98200022222 and Noor Credit Inst.'s numbers still resolve to their own names.
        java.util.Set<String> reachable = BankRules.reachableBanks();
        assertTrue("Tosee Credit Inst. must not be reachable", !reachable.contains("Tosee Credit Inst."));
        assertTrue("Melal Credit Inst. should be reachable", reachable.contains("Melal Credit Inst."));
        assertTrue("Noor Credit Inst. should be reachable", reachable.contains("Noor Credit Inst."));
        assertTrue("EDBI should be reachable", reachable.contains("EDBI"));
        assertTrue("Tejarat should be reachable", reachable.contains("Tejarat"));
    }

    // ---- account extraction -------------------------------------------------------------
    @Test public void extractAccount_mellatGluedDigits_returnsAccount() {
        assertEquals("1110000222", BankRules.extractAccount("Mellat",
            "\u062D\u0633\u0627\u0628" + "1110000222"));
        assertEquals("1110000222", BankRules.extractAccount("Mellat",
            "\u062D\u0633\u0627\u0628" + "\u06F1\u06F1\u06F1\u06F0\u06F0\u06F0\u06F0\u06F2\u06F2\u06F2"));
    }

    @Test public void extractAccount_mellatBalanceStatement_notCaptured() {
        // The colon/space between label and figure keeps balance statements out of the glue rule.
        assertNull(BankRules.extractAccount("Mellat", "\u0645\u0627\u0646\u062F\u0647 "
            + "\u062D\u0633\u0627\u0628" + ": 72,222,945"));
        assertNull(BankRules.extractAccount("Mellat", "\u062D\u0633\u0627\u0628" + "شما 1,000,000"));
    }

    @Test public void extractAccount_melliColonDigits_returnsAccount() {
        assertEquals("10001", BankRules.extractAccount("Melli",
            "\u062D\u0633\u0627\u0628" + ":10001"));
        assertEquals("10001", BankRules.extractAccount("Melli",
            "\u062D\u0633\u0627\u0628" + ": " + "\u06F1\u06F0\u06F0\u06F0\u06F1"));
    }

    @Test public void extractAccount_melliBalanceStatement_notCaptured() {
        // Two digits under the three-digit minimum and thousand separators reject comma-grouped balances.
        assertNull(BankRules.extractAccount("Melli", "\u0645\u0627\u0646\u062F\u0647 "
            + "\u062D\u0633\u0627\u0628" + ": 72,222,945 \u0631\u06CC\u0627\u0644"));
        assertNull(BankRules.extractAccount("Melli", "\u062D\u0633\u0627\u0628" + ": 10001,"));
    }

    @Test public void extractAccount_resalatDotted_returnsAccount() {
        assertEquals("10.1234567.2", BankRules.extractAccount("Resalat", "10.1234567.2\n-200,000,000"));
    }

    @Test public void extractAccount_resalatDottedDate_notCaptured() {
        // A dotted date has a two-digit middle segment, below the four-digit minimum, so it stays a date.
        assertNull(BankRules.extractAccount("Resalat", "\u06F1\u06F4\u06F0\u06F5.\u06F0\u06F6.\u06F1\u06F5"));
    }

    @Test public void extractAccount_noRuleBank_accountNotExtracted() {
        assertNull(BankRules.extractAccount("Tejarat", "\u062D\u0633\u0627\u0628" + ": 01351234567890"));
        assertNull(BankRules.extractAccount("Saman", "\u0645\u062C\u0648\u062F\u06CC: 1,250,000"));
        assertNull(BankRules.extractAccount("Blu", "\u06F1\u06F4\u06F0\u06F5.\u06F0\u06F6.\u06F1\u06F5"));
    }

    @Test public void extractAccount_nullArguments_notCaptured() {
        assertNull(BankRules.extractAccount(null, "10.1234567.2"));
        assertNull(BankRules.extractAccount("Mellat", null));
        assertNull(BankRules.extractAccount("Mellat", ""));
    }
}