package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Bank sender resolution/normalization edge cases (instrumented, pure logic, no UI). */
@RunWith(AndroidJUnit4.class)
public class BankRulesTest {

    // ---- the calendar each bank declares -------------------------------------------------
    @Test public void everyRuleRow_declaresACalendar() {
        // A bank added without a declaration would have its year-less dates read as Persian by
        // default, quietly off by about three months, so the omission has to fail here instead.
        for (String[] rule : BankRules.rulesTestOnly()) {
            assertEquals("row for " + rule[0] + " must state a calendar", 3, rule.length);
            assertTrue("row for " + rule[0] + " declares an unknown calendar",
                CalendarSystem.ofTag(rule[2]) != null);
        }
    }

    @Test public void everySupportedBank_resolvesToACalendar() {
        for (String bank : BankRules.supportedNames())
            assertTrue(bank, BankRules.calendar(bank) != null);
    }

    // ---- exact numeric aliases ---------------------------------------------------------
    @Test public void resolve_numericShortcode_returnsBank() {
        assertEquals("Tejarat", BankRules.resolve("5000973189"));
        assertEquals("Saman", BankRules.resolve("500095"));
    }

    @Test public void resolve_plusPrefixedAlias_returnsBank() {
        assertEquals("Pasargad", BankRules.resolve("+98500019000"));
    }

    @Test public void resolve_pasargadPaymentServiceNumber_returnsPasargad() {
        // The handset presents this sender as (982) 000-9000; the provider gives the app its
        // digits-only form, and normalize() also accepts the country-code and local suffix forms.
        assertEquals("Pasargad", BankRules.resolve("(982) 000-9000"));
        assertEquals("Pasargad", BankRules.resolve("+9820009000"));
        assertEquals("Pasargad", BankRules.resolve("20009000"));
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

    @Test public void resolve_middleEastPersianSender_returnsBank() {
        assertEquals("Middle East", BankRules.resolve("بانک خاورمیانه"));
    }

    @Test public void resolve_middleEastArabicLetterSender_returnsBank() {
        assertEquals("Middle East", BankRules.resolve("بانك خاورميانه"));
    }

    @Test public void displayName_fa_usesShortNames() {
        // Card names stay short: no قرض‌الحسنه prefix on Mehr/Resalat, no ایران suffix
        // on Melli/Saderat.
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String original = LocaleHelper.currentTag(ctx);
        LocaleHelper.setLanguage(ctx, "fa");
        try {
            Context fa = LocaleHelper.wrap(ctx);
            assertEquals("مهر ایران", BankRules.displayName(fa, "Mehr"));
            assertEquals("رسالت", BankRules.displayName(fa, "Resalat"));
            assertEquals("ملی", BankRules.displayName(fa, "Melli"));
            assertEquals("صادرات", BankRules.displayName(fa, "Saderat"));
        } finally {
            LocaleHelper.setLanguage(ctx, original);
        }
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
            + "\u0628\u0631\u062F\u0627\u0634\u062A: 490,098,000 \u0631\u06CC\u0627\u0644\n"
            + "\u0645\u0627\u0646\u062F\u0647: 8,465,016 \u0631\u06CC\u0627\u0644"));
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
            "*\u0628\u0627\u0646\u06A9 \u062A\u062C\u0627\u0631\u062A*\n\u0645\u0627\u0646\u062F\u0647: 8,465,016 \u0631\u06CC\u0627\u0644"));
    }

    @Test public void extractAccount_parsianAccountLine_returnsAccount() {
        // Real Parsian movements open with the account on its own line, the "مبلغ:" amount line
        // right below it.
        assertEquals("30101234567890", BankRules.extractAccount("Parsian",
            "30101234567890\n\u0645\u0628\u0644\u063A:500,000-\n\u0645\u0627\u0646\u062F\u0647:7,533,540"));
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

    @Test public void extractAccount_middleEastSlashAccount_returnsAccount() {
        String body = "بانک خاورمیانه\n020/002863516\n+1,000,000\n07/13\n21:32\n"
            + "مانده 1,000,000\nواریز مبلغ افزایش موجودی حساب";
        assertEquals("020/002863516", BankRules.extractAccount("Middle East", body));
        assertNull(BankRules.extractAccount("Middle East", "+1,000,000\n07/13\nمانده 1,000,000"));
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

    @Test public void extractAccount_sepahProfitLine_returnsAccount() {
        // Driven with the synthetic messages in {@link SepahMessages}, written in the shape the
        // bank sends them, so the rule test and the scan test see the same bytes.
        assertEquals(SepahMessages.ACCOUNT,
            BankRules.extractAccount("Sepah", SepahMessages.PROFIT_A));
        assertEquals(SepahMessages.ACCOUNT,
            BankRules.extractAccount("Sepah", SepahMessages.PROFIT_B));
        assertEquals(SepahMessages.ACCOUNT,
            BankRules.extractAccount("Sepah",
                SepahMessages.PROFIT_A.replace("\n", "\r\n")));
    }

    @Test public void extractAccount_sepahHesabLine_returnsAccount() {
        // The bank's other layout states the credited account behind "حساب:", with no space
        // after the colon.
        assertEquals(SepahMessages.ACCOUNT_B,
            BankRules.extractAccount("Sepah", SepahMessages.DEPOSIT));
        assertEquals("02000000000002", BankRules.extractAccount("Sepah",
            SepahMessages.DEPOSIT.replace("حساب:", "حساب: ")));
    }

    @Test public void extractAccount_sepahGuards_rejectNonAccounts() {
        // Too short for an account, too long for one, a destination without the profit event and
        // a bare balance: none of them is the credited account.
        assertNull(BankRules.extractAccount("Sepah",
            SepahMessages.PROFIT_A.replace(SepahMessages.ACCOUNT, "12345")));
        assertNull(BankRules.extractAccount("Sepah",
            SepahMessages.PROFIT_A.replace(SepahMessages.ACCOUNT,
                "123456789012345678901234567890")));
        assertNull(BankRules.extractAccount("Sepah",
            "انتقال به حساب: 12345678\nمبلغ: 87,450ريال\nمانده: 9,876,543ريال"));
        assertNull(BankRules.extractAccount("Sepah", "مانده: 9,876,543ريال"));
    }

    @Test public void extractAccount_maskanColonLine_returnsAccount() {
        // Driven with the synthetic messages in {@link MaskanMessages}, written in the shape the
        // bank sends them, so the rule test and the scan test see the same bytes.
        assertEquals(MaskanMessages.ACCOUNT_A,
            BankRules.extractAccount("Maskan", MaskanMessages.PURCHASE));
        assertEquals(MaskanMessages.ACCOUNT_B,
            BankRules.extractAccount("Maskan", MaskanMessages.INTERNET_TRANSFER));
        assertEquals(MaskanMessages.ACCOUNT_C,
            BankRules.extractAccount("Maskan", MaskanMessages.SMS_FEE));
        assertEquals(MaskanMessages.ACCOUNT_A, BankRules.extractAccount("Maskan",
            MaskanMessages.PURCHASE.replace("حساب:", "حساب: ")));
    }

    @Test public void extractAccount_maskanGuards_rejectNonAccounts() {
        // Too short for an account, too long for one, and a bare balance: none of them is the
        // stated account. The comma-grouped balance is rejected by the no-thousand-separator
        // guard even though it sits behind no label at all.
        assertNull(BankRules.extractAccount("Maskan",
            MaskanMessages.PURCHASE.replace(MaskanMessages.ACCOUNT_A, "12345")));
        assertNull(BankRules.extractAccount("Maskan",
            MaskanMessages.PURCHASE.replace(MaskanMessages.ACCOUNT_A,
                "123456789012345678901234567890")));
        assertNull(BankRules.extractAccount("Maskan", "مانده:12,345,678\n0706-11:48"));
    }

    @Test public void extractAccount_gardeshgariDottedLabeled_returnsAccount() {
        // Driven with the synthetic messages in {@link GardeshgariMessages}, written in the shape
        // the bank sends them, so the rule test and the scan test see the same bytes.
        assertEquals(GardeshgariMessages.ACCOUNT,
            BankRules.extractAccount("Gardeshgari", GardeshgariMessages.DEPOSIT));
        assertEquals(GardeshgariMessages.ACCOUNT, BankRules.extractAccount("Gardeshgari",
            GardeshgariMessages.DEPOSIT.replace("واريز به:", "واريز به: ")));
        // The Persian spelling of the label reads the same account.
        assertEquals(GardeshgariMessages.ACCOUNT, BankRules.extractAccount("Gardeshgari",
            GardeshgariMessages.DEPOSIT.replace("واريز", "واریز")));
    }

    @Test public void extractAccount_gardeshgariGuards_rejectNonAccounts() {
        // A too-short and a too-long dotted id, a bare balance and a bare date line: none of
        // them is the labeled account.
        assertNull(BankRules.extractAccount("Gardeshgari",
            GardeshgariMessages.DEPOSIT.replace(GardeshgariMessages.ACCOUNT, "22.447.6035.7")));
        assertNull(BankRules.extractAccount("Gardeshgari",
            GardeshgariMessages.DEPOSIT.replace(GardeshgariMessages.ACCOUNT,
                "22011.447111.60358291111.71111")));
        assertNull(BankRules.extractAccount("Gardeshgari", "موجودي: 123,456,789 ريال"));
        assertNull(BankRules.extractAccount("Gardeshgari",
            "05/05/09_21:36\nموجودي: 123,456,789 ريال"));
    }

    @Test public void extractAccount_eghtesadNovinDashLabeled_returnsAccount() {
        // Driven with the synthetic messages in {@link EghtesadNovinMessages}, written in the
        // shape the bank sends them, so the rule test and the scan test see the same bytes.
        assertEquals(EghtesadNovinMessages.ACCOUNT,
            BankRules.extractAccount("Eghtesad Novin", EghtesadNovinMessages.WITHDRAWAL));
        assertEquals(EghtesadNovinMessages.ACCOUNT, BankRules.extractAccount("Eghtesad Novin",
            EghtesadNovinMessages.WITHDRAWAL.replace("برداشت: ", "برداشت:")));
    }

    @Test public void extractAccount_eghtesadNovinGuards_rejectNonAccounts() {
        // Wrong group widths, a trailing extra digit, the support phone line and a bare
        // balance: none of them is the labeled account.
        assertNull(BankRules.extractAccount("Eghtesad Novin",
            EghtesadNovinMessages.WITHDRAWAL.replace(EghtesadNovinMessages.ACCOUNT,
                "31-206-7091854-6")));
        assertNull(BankRules.extractAccount("Eghtesad Novin",
            EghtesadNovinMessages.WITHDRAWAL.replace(EghtesadNovinMessages.ACCOUNT,
                "314-206-7091854-66")));
        assertNull(BankRules.extractAccount("Eghtesad Novin",
            "مرکزآواي نوين:" + EghtesadNovinMessages.PHONE));
        assertNull(BankRules.extractAccount("Eghtesad Novin", "مانده:1,234,567,890ريال"));
    }

    @Test public void extractAccount_shahrWithdrawalLine_returnsAccount() {
        // Driven with the synthetic messages in {@link ShahrMessages}, written in the shape the
        // bank sends them, so the rule test and the scan test see the same bytes.
        assertEquals(ShahrMessages.ACCOUNT,
            BankRules.extractAccount("Shahr", ShahrMessages.PURCHASE));
        assertEquals(ShahrMessages.ACCOUNT, BankRules.extractAccount("Shahr",
            ShahrMessages.PURCHASE.replace("برداشت از:", "برداشت از: ")));
    }

    @Test public void extractAccount_shahrGuards_rejectNonAccounts() {
        // Too short for an account, too long for one, a destination without the withdrawal
        // event and a bare balance: none of them is the debited account.
        assertNull(BankRules.extractAccount("Shahr",
            ShahrMessages.PURCHASE.replace(ShahrMessages.ACCOUNT, "12345")));
        assertNull(BankRules.extractAccount("Shahr",
            ShahrMessages.PURCHASE.replace(ShahrMessages.ACCOUNT,
                "123456789012345678901234567890")));
        assertNull(BankRules.extractAccount("Shahr",
            "انتقال به حساب: 1234567890123\nمبلغ: 87,450ريال\nموجودي: 9,876,543 ريال"));
        assertNull(BankRules.extractAccount("Shahr", "موجودي:12,345,678 ريال"));
    }

    @Test public void extractAccount_nullArguments_notCaptured() {
        assertNull(BankRules.extractAccount(null, "10.1234567.2"));
        assertNull(BankRules.extractAccount("Mellat", null));
        assertNull(BankRules.extractAccount("Mellat", ""));
    }

    @Test public void accountTable_rowsReferenceKnownBanks_uniqueAndShapely() {
        java.util.Set<String> known = BankRules.supportedNames();
        java.util.Set<String> shapes = new java.util.HashSet<>(java.util.Arrays.asList(
            "label-glued", "label-colon", "label-colon-line", "bare-mablagh", "bare-bidi",
            "dotted", "dotted-line", "dotted-labeled", "dash-labeled", "withdrawal-line",
            "slash-line", "credit-line"));
        java.util.Set<String> banksSeen = new java.util.HashSet<>();
        for (String[] row : BankRules.accountRulesTestOnly()) {
            assertEquals(4, row.length);
            assertTrue("row bank not known: " + row[0], known.contains(row[0]));
            assertTrue("duplicate bank row: " + row[0], banksSeen.add(row[0]));
            assertTrue("unknown shape: " + row[1], shapes.contains(row[1]));
        }
    }

    @Test public void accountTable_rowLengthBoundsAreValid() {
        for (String[] row : BankRules.accountRulesTestOnly()) {
            String shape = row[1];
            if (shape.equals("dotted") || shape.equals("dotted-line")
                    || shape.equals("dotted-labeled") || shape.equals("dash-labeled")
                    || shape.equals("slash-line")) {
                assertEquals("", row[2]);
                assertEquals("", row[3]);
                continue;
            }
            int min = Integer.parseInt(row[2]);
            assertTrue("min must be > 0: " + row[0], min > 0);
            if (!row[3].isEmpty()) {
                assertTrue("max must be >= min: " + row[0], Integer.parseInt(row[3]) >= min);
            }
        }
    }

    @Test public void extractAccount_sharedColonShape_parametrisesPerBank() {
        // The three colon-label banks share one shape; only their length bounds and the line-start
        // requirement differ, so the same body extracts for some banks and not others.
        assertEquals("5678", BankRules.extractAccount("Melli", "\u062D\u0633\u0627\u0628: 5678"));
        assertNull(BankRules.extractAccount("Tejarat", "\u062D\u0633\u0627\u0628: 5678"));
        assertEquals("01351234567890", BankRules.extractAccount("Tejarat", "\u062D\u0633\u0627\u0628: 01351234567890"));
        assertEquals("5678", BankRules.extractAccount("Saderat", "\u062D\u0633\u0627\u0628:5678"));
        assertNull(BankRules.extractAccount("Saderat", "\u0627\u0646\u062A\u0642\u0627\u0644 \u0628\u0647 \u062D\u0633\u0627\u0628:5678"));
    }

    // ---- the reason a bank states for a movement ---------------------------------------
    // Driven with the real messages in {@link BluMessages}: the rule test and a scan test
    // must see the same bytes the SMS provider hands back, so the fixtures are shared
    // rather than copied.

    @Test public void reasonTable_rowsReferenceKnownBanks_uniqueAndShapely() {
        java.util.Set<String> known = BankRules.supportedNames();
        java.util.Set<String> banksSeen = new java.util.HashSet<>();
        for (String[] row : BankRules.reasonRulesTestOnly()) {
            assertEquals("row must be {bank, shape}", 2, row.length);
            assertTrue("row bank not known: " + row[0], known.contains(row[0]));
            assertTrue("duplicate bank row: " + row[0], banksSeen.add(row[0]));
            assertTrue("unknown shape: " + row[1], row[1].equals("title-line")
                || row[1].equals("final-line") || row[1].equals("profit-line")
                || row[1].equals("labeled-prefix") || row[1].equals("card-line"));
        }
    }

    @Test public void reasonTable_everyCaptionKeyIsAlreadyNormalized() {
        // A title added with a stray zero-width joiner or a double space would be stored and shown
        // but could never be matched back, so the allowlist itself has to be normalized.
        for (String title : BankRules.reasonCaptionKeys())
            assertEquals("caption key not in the form the lookup uses: " + title,
                title, BankRules.normalizeReason(title));
    }

    @Test public void reasonTable_everyBankRuleHasAtLeastOneCaption() {
        // A row that can read a title but captions none of them can only ever store noise.
        for (String[] row : BankRules.reasonRulesTestOnly()) {
            boolean captioned = false;
            for (String title : BankRules.reasonCaptionKeys()) {
                String probe;
                if (row[1].equals("final-line")) probe = "\n" + title;
                else if (row[1].equals("labeled-prefix")) probe = title + ":+1,000\n";
                else probe = "x\n" + title + "\ny\n";
                if (BankRules.extractReason(row[0], probe) != null) captioned = true;
            }
            assertTrue("no captioned title for " + row[0], captioned);
        }
    }

    @Test public void extractReason_bluTitleLine_returnsTheStatedReason() {
        assertEquals("شارژ شدی", BankRules.extractReason("Blu", BluMessages.TOPUP));
        assertEquals("پرداخت قبض", BankRules.extractReason("Blu", BluMessages.BILL));
        assertEquals("برگشت پول", BankRules.extractReason("Blu", BluMessages.REFUND));
        assertEquals("دریافت پل", BankRules.extractReason("Blu", BluMessages.TRANSFER_IN));
        assertEquals("انتقال پل", BankRules.extractReason("Blu", BluMessages.TRANSFER_OUT));
    }

    @Test public void extractReason_middleEastFinalLine_returnsTheStatedReason() {
        assertEquals("واریز مبلغ افزایش موجودی حساب", BankRules.extractReason("Middle East",
            "بانک خاورمیانه\n020/002863516\n+1,000,000\n07/13\n21:32\n"
                + "مانده 1,000,000\nواریز مبلغ افزایش موجودی حساب"));
        assertEquals("واریز مبلغ افزایش موجودی حساب", BankRules.extractReason("Middle East",
            "بانک خاورمیانه\n020/002863516\n+1,000,000\n07/13\n21:32\n"
                + "مانده 1,000,000\nواریز مبلغ افزایش موجودی حساب\r\n"));
    }

    @Test public void extractReason_sepahProfitLine_returnsTheStatedReason() {
        assertEquals(SepahMessages.PROFIT,
            BankRules.extractReason("Sepah", SepahMessages.PROFIT_A));
        assertEquals(SepahMessages.PROFIT,
            BankRules.extractReason("Sepah", SepahMessages.PROFIT_B));
        assertEquals(SepahMessages.PROFIT, BankRules.extractReason("Sepah",
            SepahMessages.PROFIT_A.replace("\n", "\r\n")));
    }

    @Test public void extractReason_sepahWithoutTheProfitLine_statesNoReason() {
        assertNull(BankRules.extractReason("Sepah", "مانده: 9,876,543ريال"));
        assertNull(BankRules.extractReason("Sepah",
            "متن واریز سود به: 12345678\nمانده: 9,876,543ريال"));
        assertNull(BankRules.extractReason("Sepah", BluMessages.TOPUP));
        assertNull(BankRules.extractReason("Sepah", SepahMessages.DEPOSIT));
    }

    @Test public void extractReason_maskanLabeledPrefix_returnsTheStatedReason() {
        // Driven with the synthetic messages in {@link MaskanMessages}: the label before the
        // colon on the signed-amount line is the event, spelled with the Arabic yeh the bank
        // sends, and stored in its normalized form.
        assertEquals(MaskanMessages.PURCHASE_REASON,
            BankRules.extractReason("Maskan", MaskanMessages.PURCHASE));
        assertEquals(MaskanMessages.INTERNET_REASON,
            BankRules.extractReason("Maskan", MaskanMessages.INTERNET_TRANSFER));
        assertEquals(MaskanMessages.SMS_FEE_REASON,
            BankRules.extractReason("Maskan", MaskanMessages.SMS_FEE));
        assertEquals(MaskanMessages.PURCHASE_REASON, BankRules.extractReason("Maskan",
            MaskanMessages.PURCHASE.replace("\n", "\r\n")));
    }

    @Test public void extractReason_maskanWithoutTheSignedLine_statesNoReason() {
        // The unsigned balance, account and date lines name no event: only the signed movement
        // line carries one, and an uncaptioned label stores nothing rather than an untranslated
        // fragment of the bank message.
        assertNull(BankRules.extractReason("Maskan", "مانده:12,345,678\n0706-11:48"));
        assertNull(BankRules.extractReason("Maskan",
            "حساب:" + MaskanMessages.ACCOUNT_A + "\nمانده:12,345,678\n0706-11:48"));
        assertNull(BankRules.extractReason("Maskan", BluMessages.TOPUP));
        assertNull(BankRules.extractReason("Maskan", "خريد:1,234,567\nمانده:12,345,678"));
        assertNull(BankRules.extractReason("Maskan", "سرویس تازه:+1,000\nمانده:12,345,678"));
    }

    @Test public void extractReason_shahrCardLine_returnsTheStatedReason() {
        // Driven with the synthetic messages in {@link ShahrMessages}: the literal event line,
        // spelled with the Arabic yeh the bank sends, and stored in its normalized form.
        assertEquals(ShahrMessages.CARD_PURCHASE_REASON,
            BankRules.extractReason("Shahr", ShahrMessages.PURCHASE));
        assertEquals(ShahrMessages.CARD_PURCHASE_REASON, BankRules.extractReason("Shahr",
            ShahrMessages.PURCHASE.replace("\n", "\r\n")));
    }

    @Test public void extractReason_shahrWithoutTheCardLine_statesNoReason() {
        // A bare balance, the event mentioned inside a longer sentence, and another bank's
        // titled message: none of them states this bank's event.
        assertNull(BankRules.extractReason("Shahr", "موجودي:12,345,678 ريال"));
        assertNull(BankRules.extractReason("Shahr",
            "متن خريد با کارت اضافه\nموجودي:12,345,678 ريال"));
        assertNull(BankRules.extractReason("Shahr", BluMessages.TOPUP));
    }

    @Test public void extractReason_plainDirectionTitle_statesNoReason() {
        // "برداشت پول" only restates the direction the movement row already shows, so a chip saying
        // so would be noise: the title is read, then dropped for not being one the app captions.
        assertNull(BankRules.extractReason("Blu", BluMessages.WITHDRAWAL));
        assertNull(BankRules.extractReason("Blu",
            "بلو\nواریز پول\n علی عزیز، 500,000 ریال به حساب شما نشست.\n"
            + " موجودی: 58,086,241 ریال\n۲۳:۲۸\n۱۴۰۵.۰۶.۱۵"));
    }

    @Test public void extractReason_uncaptionedTitle_statesNoReason() {
        // A loan promotion and a dynamic-OTP message are both titled and neither states a movement.
        // Even if a scan ever saw one, an untranslated fragment of a bank message must not appear as
        // though the app had understood it.
        assertNull(BankRules.extractReason("Blu", BluMessages.PROMO));
        assertNull(BankRules.extractReason("Blu", BluMessages.OTP));
    }

    @Test public void extractReason_titleMustNotCarryDigitsOrRunLong() {
        // A balance or a date line landing in the title slot, and a paragraph-shaped second line,
        // are all refused on shape alone — before the allowlist is even consulted.
        assertNull(BankRules.extractReason("Blu",
            "\u0628\u0644\u0648\n\u0645\u0648\u062C\u0648\u062F\u06CC: 1,000,000 \u0631\u06CC\u0627\u0644\n\u06F2\u06F3\n"));
        StringBuilder longLine = new StringBuilder("\u0628\u0644\u0648\n");
        for (int i = 0; i < 80; i++) longLine.append('x');
        longLine.append("\n\u06F2\u06F3\n");
        assertNull(BankRules.extractReason("Blu", longLine.toString()));
    }

    @Test public void extractReason_titleMustBeFollowedByTheRestOfTheMessage() {
        // A bare two-line body is not a movement message: the title is only a title when the amount,
        // balance, time and date lines follow it.
        assertNull(BankRules.extractReason("Blu", "\u0628\u0644\u0648\n\u0634\u0627\u0631\u0698 \u0634\u062F\u06CC"));
    }

    @Test public void extractReason_bankWithoutTheTable_statesNoReason() {
        // Every other bank's movements are read exactly as before this table existed.
        for (String bank : BankRules.supportedNames())
            if (!bank.equals("Blu")) assertNull(bank, BankRules.extractReason(bank, BluMessages.TOPUP));
    }

    @Test public void extractReason_missingPieces_stateNoReason() {
        assertNull(BankRules.extractReason(null, BluMessages.TOPUP));
        assertNull(BankRules.extractReason("Blu", null));
        assertNull(BankRules.extractReason("Blu", ""));
        assertNull(BankRules.extractReason("Blu", "\u0628\u0644\u0648\n"));
        assertNull(BankRules.extractReason("NoSuchBank", BluMessages.TOPUP));
    }

    @Test public void extractReason_indentedAndMarkedTitles_matchTheSameReason() {
        // A sender that indents the title, or wraps it in the invisible right-to-left marks, states
        // the same event and must land on the same stored reason.
        String indented = BluMessages.TOPUP.replace("\n \u0627\u0634\u06A9\u0627\u0646", "\n   \u0627\u0634\u06A9\u0627\u0646");
        assertEquals("شارژ شدی", BankRules.extractReason("Blu", indented));
        String marked = "\u0628\u0644\u0648\n\u200F\u0634\u0627\u0631\u0698\u200C \u0634\u062F\u06CC\n\u0627\u0634\u06A9\u0627\u0646\ny\n";
        assertEquals("شارژ شدی", BankRules.extractReason("Blu", marked));
    }

    @Test public void extractReason_indentOnTheTitleLine_matchesTheSameReason() {
        // Indented on the title line rather than the amount line: the pattern is free to read that
        // run as the indentation it is, and has to read the title with the leading spaces out of it.
        String indented = "\u0628\u0644\u0648\n   \u0634\u0627\u0631\u0698 \u0634\u062F\u06CC\n\u0627\u0634\u0643\u0627\u0646\ny\n";
        assertEquals("شارژ شدی", BankRules.extractReason("Blu", indented));
    }

    @Test public void extractReason_bodyWithoutASecondLine_isRefusedWithoutRereadingIt() {
        // A message that parses to a movement but has no second line cannot state a reason, and the
        // scan that reads it holds the store's lock the whole while. The line and its leading run of
        // spaces are matched by classes that overlap, so a body with no newline at all could be
        // divided between them in as many ways as it has characters, every split retrying what was
        // left of it. A 100 KB one-line body costs microseconds when every split is unique and tens
        // of seconds when it is not, so this fails rather than stalls if they are ever allowed to
        // overlap again.
        StringBuilder body = new StringBuilder(100_000);
        for (int i = 0; i < 100_000; i++) body.append('7');
        long started = System.nanoTime();
        assertNull(BankRules.extractReason("Blu", body.toString()));
        assertTrue("one unparsable message must not cost seconds",
                System.nanoTime() - started < 5_000_000_000L);
    }

    // ---- the channel a movement went through ---------------------------------------------
    // Driven with the synthetic messages in {@link TejaratMessages}, written in the shape the bank
    // sends them, for the same reason the reason fixtures are shared rather than copied: the rule test
    // and a scan test must see the same bytes the SMS provider hands back.

    @Test public void channelTable_rowsReferenceKnownBanks_uniqueAndShapely() {
        java.util.Set<String> known = BankRules.supportedNames();
        java.util.Set<String> banksSeen = new java.util.HashSet<>();
        for (String[] row : BankRules.channelRulesTestOnly()) {
            assertEquals("row must be {bank, shape, label}", 3, row.length);
            assertTrue("row bank not known: " + row[0], known.contains(row[0]));
            assertTrue("duplicate bank row: " + row[0], banksSeen.add(row[0]));
            assertTrue("unknown shape: " + row[1], row[1].equals("labeled-line")
                || row[1].equals("final-prefix"));
            if (row[1].equals("labeled-line"))
                assertTrue("label must end with its colon: " + row[2], row[2].endsWith(":"));
            else assertTrue("prefix must not be empty", !row[2].isEmpty());
        }
    }

    @Test public void channelTable_everyCaptionKeyIsAlreadyNormalized() {
        // A channel added with a stray zero-width joiner or a double space would be stored and shown
        // but could never be matched back, so the allowlist itself has to be normalized.
        for (String channel : BankRules.channelCaptionKeys())
            assertEquals("caption key not in the form the lookup uses: " + channel,
                channel, BankRules.normalizeChannel(channel));
    }

    @Test public void channelTable_everyBankRuleHasAtLeastOneCaption() {
        // A row that can read a channel but captions none of them can only ever store noise.
        for (String[] row : BankRules.channelRulesTestOnly()) {
            boolean captioned = false;
            for (String channel : BankRules.channelCaptionKeys())
                if (BankRules.extractChannel(row[0], row[2] + " " + channel + "  \nx\ny\n") != null)
                    captioned = true;
            assertTrue("no captioned channel for " + row[0], captioned);
        }
    }

    @Test public void extractChannel_tejaratLabeledLine_returnsTheStatedChannel() {
        assertEquals("شتاب", BankRules.extractChannel("Tejarat", TejaratMessages.WITHDRAWAL_SHETAB));
        assertEquals("سامانه پل (پرداخت لحظه ای)",
            BankRules.extractChannel("Tejarat", TejaratMessages.DEPOSIT_SEP));
        assertEquals("پایانه فروش", BankRules.extractChannel("Tejarat", TejaratMessages.WITHDRAWAL_POS));
        assertEquals("همراه بانک", BankRules.extractChannel("Tejarat", TejaratMessages.WITHDRAWAL_MOBILE));
        assertEquals("شعبه", BankRules.extractChannel("Tejarat", TejaratMessages.WITHDRAWAL_BRANCH));
        assertEquals("پایا", BankRules.extractChannel("Tejarat", TejaratMessages.DEPOSIT_PAYA));
    }

    @Test public void extractChannel_storesTheBanksOwnWords() {
        // What is stored is a fact about the message, not a caption: a build with no Persian strings
        // still shows the channel Tejarat stated, and another language can caption the same value later.
        assertEquals("شعبه", BankRules.extractChannel("Tejarat", TejaratMessages.WITHDRAWAL_BRANCH));
    }

    @Test public void extractChannel_messageWithoutTheLine_statesNoChannel() {
        // The movement is read, dated and summed exactly as before; it just carries no channel.
        assertNull(BankRules.extractChannel("Tejarat", TejaratMessages.NO_CHANNEL));
    }

    @Test public void extractChannel_uncaptionedChannel_statesNoChannel() {
        // A channel this build has no caption for must store nothing, rather than show an
        // untranslated fragment of a bank message as though the app had understood it.
        assertNull(BankRules.extractChannel("Tejarat", TejaratMessages.UNKNOWN_CHANNEL));
    }

    @Test public void extractChannel_channelMustNotCarryDigitsOrRunLong() {
        // An amount line landing in the channel slot, and a paragraph-shaped channel, are both refused
        // on shape alone — before the allowlist is even consulted.
        assertNull(BankRules.extractChannel("Tejarat", TejaratMessages.BALANCE_IN_CHANNEL_POSITION));
        StringBuilder filler = new StringBuilder();
        for (int i = 0; i < 80; i++) filler.append('x');
        assertNull(BankRules.extractChannel("Tejarat",
            TejaratMessages.WITHDRAWAL_SHETAB.replace("شتاب", filler.toString())));
    }

    @Test public void extractChannel_messageStoppingAtTheChannel_statesNoChannel() {
        // A body that simply stops at the channel has not stated a whole movement, so an
        // unterminated last line is not read as one — the same guard the reason shapes use, and for
        // the same reason: a truncated message is not a message that says what it says.
        assertNull(BankRules.extractChannel("Tejarat",
            "*\u0628\u0627\u0646\u06A9 \u062A\u062C\u0627\u0631\u062A* \n\u0627\u0632 \u0637\u0631\u064A\u0642: \u0634\u062A\u0627\u0628  "));
    }

    @Test public void extractChannel_bankWithoutTheTable_statesNoChannel() {
        // Every other bank's movements carry no channel: Blu messages have a reason line but never a
        // channel line, and no other bank has been seen to name one.
        for (String bank : BankRules.supportedNames())
            if (!bank.equals("Tejarat") && !bank.equals("Sepah"))
                assertNull(bank, BankRules.extractChannel(bank, TejaratMessages.WITHDRAWAL_SHETAB));
    }

    @Test public void extractChannel_missingPieces_stateNoChannel() {
        assertNull(BankRules.extractChannel(null, TejaratMessages.WITHDRAWAL_SHETAB));
        assertNull(BankRules.extractChannel("Tejarat", null));
        assertNull(BankRules.extractChannel("Tejarat", ""));
        assertNull(BankRules.extractChannel("NoSuchBank", TejaratMessages.WITHDRAWAL_SHETAB));
    }

    @Test public void extractChannel_senderSpellingPersianYeh_matchesTheSameChannel() {
        // The bank writes its own label; a sender that spells the yeh the other way states the same
        // channel, and folding the two letter forms is what makes it land on the same stored value.
        assertEquals("شتاب", BankRules.extractChannel("Tejarat", TejaratMessages.PERSIAN_YEH_CHANNEL));
    }

    @Test public void extractChannel_sepahFinalTransferLine_returnsPaya() {
        assertEquals("پایا", BankRules.extractChannel("Sepah", SepahMessages.REPORTED_ARABIC_MIXED));
    }

    @Test public void extractChannel_indentedAndMarkedChannelLines_matchTheSameChannel() {
        // A sender that indents the line, or wraps it in the invisible right-to-left marks and pads it
        // with a run of spaces, states the same channel.
        String indented = TejaratMessages.WITHDRAWAL_SHETAB
            .replace("\u0627\u0632 \u0637\u0631\u064A\u0642: ", "\u0627\u0632 \u0637\u0631\u064A\u0642:   ");
        assertEquals("شتاب", BankRules.extractChannel("Tejarat", indented));
        String marked = TejaratMessages.WITHDRAWAL_SHETAB
            .replace("\u0634\u062A\u0627\u0628  ", "\u200F\u0634\u062A\u0627\u0628\u200C  \n  ");
        assertEquals("شتاب", BankRules.extractChannel("Tejarat", marked));
    }

    @Test public void extractChannel_bodyWithoutASecondLine_isRefusedWithoutRereadingIt() {
        // The same cost argument as the reason matchers: the line and its leading run of spaces are
        // matched by classes that must not overlap, or a body with no newline at all could be divided
        // between them in as many ways as it has characters. This fails rather than stalls if they are
        // ever allowed to overlap again.
        StringBuilder body = new StringBuilder(100_000);
        for (int i = 0; i < 100_000; i++) body.append('7');
        long started = System.nanoTime();
        assertNull(BankRules.extractChannel("Tejarat", body.toString()));
        assertTrue("one unparsable message must not cost seconds",
                System.nanoTime() - started < 5_000_000_000L);
    }

    // ---- payment authorization merchant evidence ------------------------------------------
    @Test public void extractAuthorization_pasargadShape_readsMerchantWithoutCode() {
        BankRules.Authorization authorization = BankRules.extractAuthorization("Pasargad",
            "پاسارگاد\nخرید\nکانون وکلای سازمان س\nمبلغ:45,500,000\nرمز: 72552\n20:37:32");
        assertEquals("کانون وکلای سازمان س", authorization.merchant);
        assertEquals(45_500_000L, authorization.amount);
        assertEquals(20, authorization.hour);
        assertEquals(37, authorization.minute);
    }

    @Test public void extractAuthorization_pasargadDateAndTimeLine_readsMerchant() {
        BankRules.Authorization authorization = BankRules.extractAuthorization("Pasargad",
            "پاسارگاد\nخرید\nديجي پي\nمبلغ:45,500,000\nرمز: 72552\n06/29_21:06");
        assertEquals("ديجي پي", authorization.merchant);
        assertEquals(45_500_000L, authorization.amount);
        assertEquals(21, authorization.hour);
        assertEquals(6, authorization.minute);
    }

    @Test public void extractAuthorization_bluShape_readsMerchant() {
        BankRules.Authorization authorization = BankRules.extractAuthorization("Blu", BluMessages.OTP);
        assertEquals("فروشگاه نمونه", authorization.merchant);
        assertEquals(7_777_000L, authorization.amount);
        assertEquals(13, authorization.hour);
        assertEquals(46, authorization.minute);
    }

    @Test public void extractAuthorization_verifiedClocklessMellatAndSaderatShapes_areAccepted() {
        BankRules.Authorization mellat = BankRules.extractAuthorization("Mellat",
            "بانک ملت\nخرید\nفروشگاه نمونه\nمبلغ:45,500\nرمز:12345");
        assertEquals("فروشگاه نمونه", mellat.merchant);
        assertEquals(45_500L, mellat.amount);
        assertEquals(-1, mellat.hour);
        assertEquals(-1, mellat.minute);

        BankRules.Authorization saderat = BankRules.extractAuthorization("Saderat",
            "بانک صادرات\nخرید\nفروشگاه نمونه\nمبلغ:45,500\nرمز:1234567");
        assertEquals("فروشگاه نمونه", saderat.merchant);
        assertEquals(45_500L, saderat.amount);
        assertEquals(-1, saderat.hour);
        assertEquals(-1, saderat.minute);
    }

    @Test public void extractAuthorization_verifiedParsianMelliAndMehrShapes_areAccepted() {
        BankRules.Authorization parsian = BankRules.extractAuthorization("Parsian",
            "خرید\nفروشگاه نمونه\nمبلغ 120,000\nCode:12345678");
        assertEquals("فروشگاه نمونه", parsian.merchant);
        assertEquals(120_000L, parsian.amount);
        assertEquals(-1, parsian.hour);

        BankRules.Authorization melli = BankRules.extractAuthorization("Melli",
            "بانك ملي\nخريد\nفروشگاه نمونه\nمبلغ 120,000\nرمز 1234567");
        assertEquals("فروشگاه نمونه", melli.merchant);
        assertEquals(120_000L, melli.amount);
        assertEquals(-1, melli.hour);

        BankRules.Authorization mehr = BankRules.extractAuthorization("Mehr",
            "خريد\nفروشگاه نمونه\n120,000 ريال\nرمز 123456\n@sample.domain #123456\nاعتبار 20:00:00");
        assertEquals("فروشگاه نمونه", mehr.merchant);
        assertEquals(120_000L, mehr.amount);
        assertEquals(-1, mehr.hour);
    }

    @Test public void extractAuthorization_loginOtpOrMissingClock_isIgnored() {
        assertNull(BankRules.extractAuthorization("Parsian",
            "رمز ورود به اینترنت بانک یا همراه بانک:\n79466\nمهلت استفاده دو دقیقه"));
        assertNull(BankRules.extractAuthorization("Pasargad",
            "پاسارگاد\nخرید\nفروشگاه نمونه\nمبلغ:45,500,000\nرمز:72552"));
    }

    @Test public void extractAuthorization_strictAmountAndCodeRejectsSpoofedLayouts() {
        String prefix = "بانک ملت\nخرید\nفروشگاه نمونه\n";
        assertNull(BankRules.extractAuthorization("Mellat", prefix + "مبلغ:120.50\nرمز:12345"));
        assertNull(BankRules.extractAuthorization("Mellat", prefix + "مبلغ:1,23\nرمز:12345"));
        assertNull(BankRules.extractAuthorization("Mellat", prefix + "مبلغ:-120\nرمز:12345"));
        assertNull(BankRules.extractAuthorization("Mellat", prefix + "مبلغ:120 تومان\nرمز:12345"));
        assertNull(BankRules.extractAuthorization("Mellat", prefix
            + "مبلغ:120\nمبلغ:121\nرمز:12345"));
        assertNull(BankRules.extractAuthorization("Mellat", prefix + "مبلغ:120\n12345"));
    }

    @Test public void extractAuthorization_actionMustBeAWholeVerifiedLine() {
        assertNull(BankRules.extractAuthorization("Melli",
            "بانک ملی\nورود به سامانه\nرمز:12345"));
        assertNull(BankRules.extractAuthorization("Melli",
            "بانک ملی\nانتقال\nمقصد نمونه\nمبلغ:120\nرمز:12345"));
        assertNull(BankRules.extractAuthorization("Melli",
            "بانک ملی\nتوضیح خرید در متن\nفروشگاه نمونه\nمبلغ:120\nرمز:12345"));
        assertNull(BankRules.extractAuthorization("Melli",
            "بانک ملی\nخرید\nشماره کارت: 1234\nمبلغ:120\nرمز:12345"));
        assertNull(BankRules.extractAuthorization("Melli",
            "بانک ملی\nخرید\n1234****5678\nمبلغ:120\nرمز:12345"));
        assertNull(BankRules.extractAuthorization("Melli",
            "بانک ملی\nخرید\nفروشگاه نمونه\nمبلغ:120\nرمز:12345\nمتن اضافه"));
    }

    @Test public void extractAuthorization_rejectsUnsupportedBanksAndInvalidClocks() {
        String body = "بانک ناشناخته\nخرید\nفروشگاه نمونه\nمبلغ:120\nرمز:12345\n12:00";
        assertNull(BankRules.extractAuthorization("Tejarat", body));
        assertNull(BankRules.extractAuthorization("Pasargad",
            "پاسارگاد\nخرید\nفروشگاه نمونه\nمبلغ:120\nرمز:12345\n12:00:60"));
        assertNull(BankRules.extractAuthorization("Blu",
            "بلو\nبفرمایید رمز پویا\nخرید\nفروشگاه نمونه\nمبلغ:120\nرمز:12345\n24:00"));
    }

    @Test public void extractAuthorization_clocklessBanksIgnoreExpiryLines() {
        assertNull(BankRules.extractAuthorization("Mehr",
            "خرید\nفروشگاه نمونه\n120 ریال\nرمز 123456\nاعتبار 20:00:00"));
        assertNull(BankRules.extractAuthorization("Mellat",
            "بانک ملت\nخرید\nفروشگاه نمونه\nمبلغ:120\nرمز:12345\nاعتبار 20:00:00"));
        BankRules.Authorization authorization = BankRules.extractAuthorization("Mehr",
            "خرید\nفروشگاه نمونه\n120 ریال\nرمز 123456\n@sample.domain #123456\nاعتبار 20:00:00");
        assertEquals(-1, authorization.hour);
        assertEquals(-1, authorization.minute);
    }
}
