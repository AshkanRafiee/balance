package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.TimeZone;

/**
 * Independent, synthetic Phase 0 fixtures for observable legacy quirks, not desired new-rule
 * semantics. Expected winners, amounts, epochs and digests are literals, never generated from
 * production rule tables. Existing format, scan and note-persistence suites cover ordinary cases.
 * These tests use no inbox or preferences. Run serially: MessageDate uses the process default zone.
 */
@RunWith(AndroidJUnit4.class)
public class LegacyParserContractTest {
    // 2026-09-20 12:00:00 UTC. Date expectations below are independent Gregorian epoch anchors.
    private static final long ARRIVAL = 1_789_905_600_000L;
    private static final String SENDER = "BankMellat";
    private static final String MOVEMENT = "حساب00123456\nمبلغ:250-\nموجودی:750";
    // SHA-256, first 16 bytes, of the documented literal legacy folds (UTF-8):
    // BankMellat|00123456|-250|750
    private static final String SIGNATURE = "6df2732fb4869d05aed3ad845108fb4d";
    // BankMellat|حساب00123456 مبلغ:250- موجودی:750
    private static final String CONTENT = "cabc327c6f463076b4d97b2b2727b6ef";

    private TimeZone originalZone;

    @Before public void fixDateContext() {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @After public void restoreDateContext() {
        TimeZone.setDefault(originalZone);
    }

    @Test public void orderedCollisions_keepNamedLegacyWinners() {
        // Later exact aliases do not take precedence over the earlier declaration.
        assertEquals("Tosee Taavon", BankRules.resolve("+9830005816"));
        assertEquals("Middle East", BankRules.resolve("20004860"));
        assertEquals("Melli", BankRules.resolve("98700717"));
        // Non-colliding names still reach the banks whose numeric aliases lost.
        assertEquals("Bankino", BankRules.resolve("Bankino"));
        assertEquals("Post", BankRules.resolve("PostBank"));
        // Symmetric suffix matching is observable even for a shortened sender.
        assertEquals("Middle East", BankRules.resolve("04860"));
        assertNull(BankRules.resolve("4860"));
    }

    @Test public void senderNormalization_isNotTheIdentityNormalization() {
        assertEquals("Melli", BankRules.resolve("۰۰۹۸-۷۰۰۷۱۷"));
        assertEquals("Mellat", BankRules.resolve("Bank-Mellat"));
        assertNotEquals(BalanceData.contentHash(SENDER, MOVEMENT),
            BalanceData.contentHash("Bank-Mellat", MOVEMENT));
        assertNotEquals(BalanceData.messageSig(SENDER, MOVEMENT, "00123456"),
            BalanceData.messageSig("Bank-Mellat", MOVEMENT, "00123456"));
    }

    @Test public void balanceLetters_areNotFoldedEvenThoughContentIdentityFoldsThem() {
        String arabicYeh = "حساب00123456\nمبلغ:250-\nموجودي:750";
        assertEquals(-1L, BalanceData.extract(arabicYeh));
        assertNull(BalanceData.extractTransaction(arabicYeh));
        assertEquals(CONTENT, BalanceData.contentHash(SENDER, arabicYeh));
        // Without a recognized balance the signature falls back to normalized body identity.
        assertEquals(CONTENT, BalanceData.messageSig(SENDER, arabicYeh, "00123456"));
        assertEquals(SIGNATURE, BalanceData.messageSig(SENDER, MOVEMENT, "00123456"));
    }

    @Test public void invisibleMarks_areFieldSpecific_notGloballyDiscarded() {
        assertEquals("Mellat", BankRules.resolve("Bank\u200fMellat"));
        assertNull(BankRules.extractAccount("Mellat", "حساب\u200f00123456"));
        assertEquals("شارژ شدی", BankRules.extractReason("Blu", "بلو\nشارژ شد\u200fی\n250 ریال"));
        assertNotEquals(CONTENT, BalanceData.contentHash(SENDER, MOVEMENT + "\u200f"));
        // Reason captions do not fold Arabic yeh, whereas Tejarat channel captions do.
        assertNull(BankRules.extractReason("Blu", "بلو\nشارژ شدي\n250 ریال"));
        assertEquals("پایانه فروش", BankRules.extractChannel("Tejarat",
            "از طريق: پايانه فروش\nموجودی:750"));
    }

    @Test public void digitAndWhitespaceVariants_haveLiteralContentIdentity() {
        String variant = "  حساب۰۰۱۲۳۴۵۶\r\nمبلغ:٢٥٠-\t موجودی:۷۵۰  ";
        assertEquals("00123456", BankRules.extractAccount("Mellat", variant));
        assertEquals(CONTENT, BalanceData.contentHash(SENDER, variant));
        assertEquals(SIGNATURE, BalanceData.messageSig(SENDER, variant, "00123456"));
    }

    @Test public void accountShapes_preserveLeadingZerosAndRejectMasks() {
        assertEquals("00123456", BankRules.extractAccount("Mellat", MOVEMENT));
        assertEquals("001234", BankRules.extractAccount("Tejarat", "حساب:۰۰۱۲۳۴\nموجودی:750"));
        assertEquals("01.00012345.02", BankRules.extractAccount("Resalat", "سپرده ۰۱.۰۰۰۱۲۳۴۵.۰۲"));
        assertEquals("001.02.00012345.6", BankRules.extractAccount("Pasargad", "001.02.00012345.6"));
        assertNull(BankRules.extractAccount("Mellat", "حساب****1234"));
        assertNull(BankRules.extractAccount("Tejarat", "حساب:001,234"));
        assertNull(BankRules.extractAccount("Tejarat", "حساب:001234.5"));
        // A valid-looking account later in the body does not replace the first match.
        assertEquals("00123456", BankRules.extractAccount("Mellat", "حساب00123456 حساب00987654"));
    }

    @Test public void dottedWholeLineAccount_retainsMatchedTrailingWhitespace() {
        // The legacy whole-match result includes whitespace; trimming during a port changes keys.
        String account = "001.02.00012345.6 \r\n";
        assertEquals(account, BankRules.extractAccount("Pasargad", account));
        assertEquals("Pasargad|" + account, BalanceData.storageKey("Pasargad", account));
    }

    @Test public void moneyQuirks_flowThroughToStoredMovement() {
        Transaction t = BalanceData.parseMovement("Mellat", SENDER,
            "مبلغ:12.75-\nخرید\nموجودی:-1,234.99", ARRIVAL, false, 0L);
        assertNotNull(t);
        // Both fractions truncate; the balance loses its sign. The decimal also hides the trailing
        // amount sign, so the withdrawal keyword supplies direction for the truncated 12.
        assertEquals(-12L, t.amount);
        assertEquals(Long.valueOf(1_234L), t.balance);
        assertEquals(Long.valueOf(250L), BalanceData.extractTransaction("مبلغ:250+\nموجودی:750 تومان"));
        assertEquals(750L, BalanceData.extract("موجودی:750 تومان"));
    }

    @Test public void balanceDigitRemoval_erasesEqualFallbackAmountsButNotLabeledOnes() {
        assertNull(BalanceData.extractTransaction("خرید\n750 ریال\nموجودی:750"));
        assertNull(BalanceData.extractTransaction("-750\nموجودی:750"));
        assertEquals(Long.valueOf(-750L), BalanceData.extractTransaction("مبلغ:750-\nموجودی:750"));
    }

    @Test public void balanceDigitRemoval_rewritesSubstringsOfUnrelatedAmounts() {
        // Legacy fallback removes every occurrence of the balance digits, not only its field span.
        assertEquals(Long.valueOf(-1L), BalanceData.extractTransaction("خرید\n1750 ریال\nموجودی:750"));
        assertEquals(Long.valueOf(-1_750L), BalanceData.extractTransaction("مبلغ:1750-\nموجودی:750"));
    }

    @Test public void explicitAmountAndSign_winOverContradictoryPreviousBalanceAndWords() {
        Transaction t = BalanceData.parseMovement("Mellat", SENDER,
            "مبلغ:250+\nخرید\nموجودی:750", ARRIVAL, true, 1_000L);
        assertNotNull(t);
        assertEquals(250L, t.amount); // The balance delta would instead be -250.
        assertEquals(Long.valueOf(750L), t.balance);
    }

    @Test public void ambiguousDirection_canStillBecomeAnInferredMovement() {
        String body = "واریز و برداشت انجام شد\nموجودی:750";
        assertNull(BalanceData.extractTransaction(body));
        assertNull(BalanceData.parseMovement("Mellat", SENDER, body, ARRIVAL, false, 0L));
        Transaction t = BalanceData.parseMovement("Mellat", SENDER, body, ARRIVAL, true, 1_000L);
        assertNotNull(t);
        assertEquals(-250L, t.amount);
    }

    @Test public void inferredDirection_canContradictTheOnlyDirectionWord() {
        String body = "برداشت انجام شد\nموجودی:750";
        assertNull(BalanceData.extractTransaction(body));
        Transaction t = BalanceData.parseMovement("Mellat", SENDER, body, ARRIVAL, true, 500L);
        assertNotNull(t);
        assertEquals(250L, t.amount);
        // Inference is not folded into the signature: fallback signature equals content digest.
        assertEquals("1c9b5ceff962f8b17bf4f314abcacdb8", t.sig);
        assertEquals("1c9b5ceff962f8b17bf4f314abcacdb8", t.content);
        Transaction otherState = BalanceData.parseMovement("Mellat", SENDER, body, ARRIVAL, true, 1_000L);
        assertNotNull(otherState);
        assertEquals(-250L, otherState.amount);
        assertEquals(t.sig, otherState.sig);
        assertEquals(BalanceData.noteKey(t), BalanceData.noteKey(otherState));
    }

    @Test public void identityGoldenVectors_includeAccountAndKeepNoteContentSeparate() {
        Transaction t = BalanceData.parseMovement("Mellat", SENDER, MOVEMENT, ARRIVAL, false, 0L);
        assertNotNull(t);
        assertEquals("Mellat", t.bank);
        assertEquals("00123456", t.account);
        assertEquals(-250L, t.amount);
        assertEquals(Long.valueOf(750L), t.balance);
        assertEquals(ARRIVAL, t.date);
        assertEquals(SIGNATURE, t.sig);
        assertEquals(CONTENT, t.content);
        assertEquals("s:" + SIGNATURE, BalanceData.txIdentityKey(t));
        assertEquals("c:" + CONTENT, BalanceData.noteKey(t));
        Transaction redelivery = BalanceData.parseMovement("Mellat", SENDER,
            MOVEMENT + "\nپیگیری:42", ARRIVAL + 60_000L, false, 0L);
        assertNotNull(redelivery);
        assertEquals(SIGNATURE, redelivery.sig);
        assertNotEquals(CONTENT, redelivery.content);
        assertNotEquals(BalanceData.noteKey(t), BalanceData.noteKey(redelivery));
        assertNotEquals(SIGNATURE, BalanceData.messageSig(SENDER, MOVEMENT, "00987654"));
        Transaction legacy = new Transaction("Mellat", "00123456", ARRIVAL, -250L, null);
        assertEquals("Mellat|1789905600000|-250|00123456", BalanceData.noteKey(legacy));
    }

    @Test public void dateUsesFirstPlausibleClockAnywhere_andDiscardsSeconds() {
        assertEquals(1_789_892_220_000L, MessageDate.eventTime(
            "ساعت 25:99 سپس 08:17:59\n2026/09/20 11:30", ARRIVAL, CalendarSystem.JALALI));
        // Compact dates instead take their own adjacent clock, even with an earlier valid clock.
        assertEquals(1_789_892_220_000L, MessageDate.eventTime(
            "01:00\n0920-08:17", ARRIVAL, CalendarSystem.GREGORIAN));
    }

    @Test public void firstFullDateBlocksLaterValidDatesAndOtherLayouts() {
        assertEquals(ARRIVAL, MessageDate.eventTime(
            "2026/13/20 08:17\n2026/09/20 08:17", ARRIVAL, CalendarSystem.GREGORIAN));
        assertEquals(ARRIVAL, MessageDate.eventTime(
            "1800/09/20\n09/20 08:17", ARRIVAL, CalendarSystem.GREGORIAN));
        assertEquals(ARRIVAL, MessageDate.eventTime(
            "26/13/20 08:17\n09/20 08:17", ARRIVAL, CalendarSystem.GREGORIAN));
    }

    @Test public void datePlausibilityBounds_areInclusiveToTheMillisecond() {
        String oldest = "2026/08/06 12:00";
        assertEquals(1_786_017_600_000L, MessageDate.eventTime(oldest, ARRIVAL, CalendarSystem.GREGORIAN));
        assertEquals(ARRIVAL + 1L, MessageDate.eventTime(oldest, ARRIVAL + 1L, CalendarSystem.GREGORIAN));
        String newest = "2026/09/20 18:00";
        assertEquals(1_789_927_200_000L, MessageDate.eventTime(newest, ARRIVAL, CalendarSystem.GREGORIAN));
        assertEquals(ARRIVAL - 1L, MessageDate.eventTime(newest, ARRIVAL - 1L, CalendarSystem.GREGORIAN));
    }

    @Test public void fullYearAndDateOnlyUseDeviceZone_evenForAJalaliBank() {
        assertEquals(1_789_862_400_000L, MessageDate.eventTime(
            "2026/09/20", ARRIVAL, BankRules.calendar("Mellat")));
        assertEquals(1_789_892_220_000L, MessageDate.eventTime(
            "2026/09/20 08:17", ARRIVAL, BankRules.calendar("Mellat")));
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tehran"));
        assertEquals(1_789_879_620_000L, MessageDate.eventTime(
            "2026/09/20 08:17", ARRIVAL, BankRules.calendar("Mellat")));
    }

    @Test public void movementParserTakesResolvedDateFromCaller_notFromBody() {
        String body = MOVEMENT + "\n2026/09/20 08:17";
        long resolved = MessageDate.eventTime(body, ARRIVAL, BankRules.calendar("Mellat"));
        assertEquals(1_789_892_220_000L, resolved);
        Transaction arrivalDated = BalanceData.parseMovement("Mellat", SENDER, body, ARRIVAL, false, 0L);
        Transaction eventDated = BalanceData.parseMovement("Mellat", SENDER, body, resolved, false, 0L);
        assertNotNull(arrivalDated);
        assertNotNull(eventDated);
        assertEquals(ARRIVAL, arrivalDated.date);
        assertEquals(resolved, eventDated.date);
        assertEquals(arrivalDated.sig, eventDated.sig);
        assertEquals(arrivalDated.content, eventDated.content);
    }
}
