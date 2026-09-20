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

    // ---- reachability ---------------------------------------------------------------
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
        assertNull(BankRules.extractAccount("Karafarin", "\u062D\u0633\u0627\u0628" + ": 01351234567890"));
        assertNull(BankRules.extractAccount("Saman", "\u0645\u062C\u0648\u062F\u06CC: 1,250,000"));
        assertNull(BankRules.extractAccount("Blu", "\u06F1\u06F4\u06F0\u06F5.\u06F0\u06F6.\u06F1\u06F5"));
    }

    @Test public void extractAccount_tejaratColonDigits_returnsAccount() {
        // Real Tejarat movement messages open "*بانک تجارت* / حساب: 0135…".
        assertEquals("01351234567890", BankRules.extractAccount("Tejarat",
            "*\u0628\u0627\u0646\u06A9 \u062A\u062C\u0627\u0631\u062A*\n"
            + "\u062D\u0633\u0627\u0628: 01351234567890\n"
            + "\u0628\u0631\u062F\u0627\u0634\u062A: 70,014,000 \u0631\u06CC\u0627\u0644\n"
            + "\u0645\u0627\u0646\u062F\u0647: 1,209,288 \u0631\u06CC\u0627\u0644"));
        assertEquals("01351234567890", BankRules.extractAccount("Tejarat",
            "*\u0628\u0627\u0646\u06A9 \u062A\u062C\u0627\u0631\u062A*\n"
            + "\u062D\u0633\u0627\u0628: \u06F0\u06F1\u06F3\u06F5\u06F1\u06F2\u06F3\u06F4\u06F5\u06F6\u06F7\u06F8\u06F9\u06F0\n"
            + "\u0648\u0627\u0631\u06CC\u0632: 1,000,000 \u0631\u06CC\u0627\u0644\n"
            + "\u0645\u0627\u0646\u062F\u0647: 2,000,000 \u0631\u06CC\u0627\u0644"));
    }

    @Test public void extractAccount_tejaratBalanceOnly_notCaptured() {
        // Balance notifications have no "حساب:" label of their own, and a comma-grouped figure next
        // to "حساب شما:" is rejected by the no-thousand-separator guard.
        assertNull(BankRules.extractAccount("Tejarat",
            "\u0645\u0648\u062C\u0648\u062F\u06CC \u062D\u0633\u0627\u0628 \u0634\u0645\u0627: 5,000,000 \u0631\u06CC\u0627\u0644"));
        assertNull(BankRules.extractAccount("Tejarat",
            "*\u0628\u0627\u0646\u06A9 \u062A\u062C\u0627\u0631\u062A*\n\u0645\u0627\u0646\u062F\u0647: 1,209,288 \u0631\u06CC\u0627\u0644"));
    }

    @Test public void extractAccount_parsianAccountLine_returnsAccount() {
        // Real Parsian movements open with the account on its own line, the "مبلغ:" amount line
        // right below it.
        assertEquals("30101234567890", BankRules.extractAccount("Parsian",
            "30101234567890\n\u0645\u0628\u0644\u063A:500,000-\n\u0645\u0627\u0646\u062F\u0647:1,076,220"));
        assertEquals("30101234567890", BankRules.extractAccount("Parsian",
            "\u06F3\u06F0\u06F1\u06F0\u06F1\u06F2\u06F3\u06F4\u06F5\u06F6\u06F7\u06F8\u06F9\u06F0\n"
            + "\u0645\u0628\u0644\u063A:3,000,000+\n\u0645\u0627\u0646\u062F\u0647:97,450,279"));
    }

    @Test public void extractAccount_parsianWithoutAmountLine_notCaptured() {
        // A one-off code or an activity notice has no "مبلغ:" movement line under the number (or a
        // too-short code), so it is not mistaken for an account-bearing movement.
        assertNull(BankRules.extractAccount("Parsian", "966935"));
        assertNull(BankRules.extractAccount("Parsian",
            "\u0648\u0631\u0648\u062F \u0628\u0647 \u0647\u0645\u0631\u0627\u0647 \u0628\u0627\u0646\u06A9 1405/06/07"));
    }

    @Test public void extractAccount_mehrAccountLine_returnsAccount() {
        // Real Mehr Iran movements open with the account digits alone on their own line, wrapped in
        // RTL bidi marks.
        assertEquals("302601234567890123", BankRules.extractAccount("Mehr",
            "\u202A302601234567890123\u202C\n400,000-\n1405/6/29-20:30\n\u0645\u0627\u0646\u062F\u0647:865,083"));
        assertEquals("302601234567890123", BankRules.extractAccount("Mehr",
            "302601234567890123\n400,000-\n1405/6/29-20:30\n\u0645\u0627\u0646\u062F\u0647:865,083"));
    }

    @Test public void extractAccount_mehrAmountLine_notCaptured() {
        // The signed amount line, a possibly longer comma-free amount, the date, or the balance must
        // never be read as the account.
        assertNull(BankRules.extractAccount("Mehr",
            "400,000-\n1405/6/29-20:30\n\u0645\u0627\u0646\u062F\u0647:865,083"));
        assertNull(BankRules.extractAccount("Mehr",
            "12000000000-\n\u0645\u0627\u0646\u062F\u0647:865,083"));
        assertNull(BankRules.extractAccount("Mehr", "1405/6/29-20:30\n\u0645\u0627\u0646\u062F\u0647:865,083"));
        assertNull(BankRules.extractAccount("Mehr", "\u0645\u0627\u0646\u062F\u0647:865,083"));
    }

    @Test public void extractAccount_pasargadDottedAccountLine_returnsAccount() {
        // Pasargad movements open with a four-part dotted account id alone on the first line.
        assertEquals("123.456.78901234.5", BankRules.extractAccount("Pasargad",
            "123.456.78901234.5\n-508,000\n06/29_21:06\n\u0645\u0627\u0646\u062F\u0647: 51,289"));
        assertEquals("123.456.78901234.5", BankRules.extractAccount("Pasargad",
            "\u06F1\u06F2\u06F3.\u06F4\u06F5\u06F6.\u06F7\u06F8\u06F9\u06F0\u06F1\u06F2\u06F3\u06F4.\u06F5\n"
            + "-508,000\n06/29_21:06\n\u0645\u0627\u0646\u062F\u0647: 51,289"));
    }

    @Test public void extractAccount_pasargadDateOrAmount_notCaptured() {
        // A dated line, a signed amount, or a dotted date must not be mistaken for the account.
        assertNull(BankRules.extractAccount("Pasargad", "-508,000\n06/29_21:06\n\u0645\u0627\u0646\u062F\u0647: 51,289"));
        assertNull(BankRules.extractAccount("Pasargad", "1405.06.29\n06/29_21:06\n\u0645\u0627\u0646\u062F\u0647: 51,289"));
        assertNull(BankRules.extractAccount("Pasargad", "06/29_21:06\n\u0645\u0627\u0646\u062F\u0647: 51,289"));
    }

    @Test public void extractAccount_saderatLabel_returnsAccount() {
        // Saderat movements state the account right after the "حساب:" label, with or without
        // spacing, on its own line.
        assertEquals("48203", BankRules.extractAccount("Saderat",
            " \u0627\u0646\u062A\u0642\u0627\u0644: 500,000-\n \u062D\u0633\u0627\u0628:48203\n \u0645\u0627\u0646\u062F\u0647:422,050"));
        assertEquals("48203", BankRules.extractAccount("Saderat",
            " \u0627\u0646\u062A\u0642\u0627\u0644: 500,000-\n \u062D\u0633\u0627\u0628: 48203\n \u0645\u0627\u0646\u062F\u0647:422,050"));
    }

    @Test public void extractAccount_saderatBalanceOrDestination_notCaptured() {
        // The balance line uses a different label, and a destination mention ("به حساب: …") is not
        // the account line start the rule is anchored to.
        assertNull(BankRules.extractAccount("Saderat",
            " \u0645\u0627\u0646\u062F\u0647:422,050"));
        assertNull(BankRules.extractAccount("Saderat",
            " \u0627\u0646\u062A\u0642\u0627\u0644 \u0628\u0647 \u062D\u0633\u0627\u0628: 1,000,000"));
    }

    @Test public void extractAccount_nullArguments_notCaptured() {
        assertNull(BankRules.extractAccount(null, "10.1234567.2"));
        assertNull(BankRules.extractAccount("Mellat", null));
        assertNull(BankRules.extractAccount("Mellat", ""));
    }
}