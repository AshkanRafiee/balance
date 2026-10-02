package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.ashkanrafiee.balance.parser.PackDocument;
import com.ashkanrafiee.balance.parser.Rules;
import com.ashkanrafiee.balance.parser.PackWriter;
import com.ashkanrafiee.balance.parser.Parser;

import org.junit.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * What a reader's highlights have to become. Each test builds a draft the way the wizard does --
 * highlight, choose, build -- and then reads the example back through the real engine, because a
 * rule that looks right and reads the wrong number is worse than no rule.
 */
public class RuleDraftTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Tehran");
    private static final Instant ARRIVAL = Instant.parse("2026-09-30T10:15:00Z");

    /** A two-line Iranian bank SMS: a debit with a remaining balance, as most Iranian banks send
     *  it. The reader highlights the amount, the balance and the date, and nothing else. */
    private static final String BODY = "برداشت ۱۲۰,۰۰۰ ریال\nمانده حساب: 4,500,000 ریال\n1405/07/09 12:41";

    private static RuleDraft movement() {
        RuleDraft draft = new RuleDraft();
        draft.sender = "+982000320000";
        draft.bankName = "Test Bank";
        draft.body = BODY;
        draft.shape = RuleDraft.Shape.MOVEMENT;
        draft.direction = "DEBIT";
        draft.highlight(BODY.indexOf("۱۲۰,۰۰۰"), BODY.indexOf("۱۲۰,۰۰۰") + "۱۲۰,۰۰۰".length(),
                RuleDraft.Role.AMOUNT);
        draft.highlight(BODY.indexOf("4,500,000"),
                BODY.indexOf("4,500,000") + "4,500,000".length(), RuleDraft.Role.BALANCE);
        draft.highlight(BODY.indexOf("1405/07/09"), BODY.indexOf("1405/07/09") + 10,
                RuleDraft.Role.DATE);
        return draft;
    }

    private static Parser.Result read(RuleDraft draft) {
        if (!draft.ready()) fail("draft problems: " + draft.problems());
        PackDocument document = PackDocument.decode(draft.document());
        assertEquals(document, PackDocument.decode(json(PackWriter.write(document))));
        Parser parser = new Parser(document.templates());
        Parser.Result result = parser.parse(
                new Parser.Message("sms:1", draft.sender, draft.body, ARRIVAL, ZONE));
        if (result.status() != Parser.Status.PARSED) {
            throw new AssertionError("status " + result.status() + " diagnostics "
                    + result.diagnostics());
        }
        return result;
    }

    private static Map<String, Object> json(String text) {
        try {
            return PlatformRuleJson.read(new java.io.ByteArrayInputStream(
                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static RuleDraft.Problem problem(RuleDraft draft, RuleDraft.Code code) {
        for (RuleDraft.Problem problem : draft.problems()) {
            if (problem.code == code) return problem;
        }
        fail("expected " + code + ", got " + draft.problems());
        return null;
    }

    @Test
    public void readerHighlightsReadTheirOwnExample() {
        Parser.Result result = read(movement());
        assertEquals(Parser.Status.PARSED, result.status());
        assertEquals(1, result.facts().size());
        Parser.Fact fact = result.facts().get(0);
        assertEquals(Rules.Kind.POSTED_MOVEMENT, fact.kind());
        assertEquals(Rules.Currency.IRR, fact.money().currency());
        // ۱۲۰,۰۰۰ rial, read through Persian digits and a comma group: the builder must not read
        // the comma as a decimal point and turn a withdrawal into one rial and twenty pence. A
        // withdrawal is negative in the ledger, which is the app's existing convention.
        assertEquals(-120_000L, fact.money().minorUnits());
        // The reader highlighted the date without the clock time next to it, so the rule reports
        // the day it knows and says so rather than borrowing precision it does not have.
        assertEquals(Parser.Precision.DAY, fact.time().precision());
        // The date the reader highlighted is the date the rule reads back, checked against the
        // app's own calendar rather than a hand-computed one.
        LocalDate parsed = LocalDate.ofInstant(fact.time().instant(), ZONE);
        JalaliCalendar jalali = JalaliCalendar.fromGregorian(parsed.getYear(), parsed.getMonthValue(),
                parsed.getDayOfMonth());
        assertEquals(1405, jalali.year);
        assertEquals(7, jalali.month);
        assertEquals(9, jalali.day);
        assertFalse(fact.time().fallback());
    }

    @Test
    public void aGregorianDateWithATimeIsReadAsTheReaderSaid() {
        RuleDraft draft = new RuleDraft();
        draft.sender = "+982000320000";
        draft.bankName = "Test Bank";
        draft.body = "Withdrawal 120,000 IRR\n2026/09/01 12:41";
        draft.shape = RuleDraft.Shape.MOVEMENT;
        draft.direction = "DEBIT";
        int amount = draft.body.indexOf("120,000");
        draft.highlight(amount, amount + "120,000".length(), RuleDraft.Role.AMOUNT);
        String stamp = "2026/09/01 12:41";
        int date = draft.body.indexOf(stamp);
        draft.highlight(date, date + stamp.length(), RuleDraft.Role.DATE);
        // Two facts about a date the builder cannot read off the digits: which calendar it is
        // written in, and whether a time sits next to it. Both are the reader's to say, and both
        // change what comes back.
        draft.calendar = RuleDraft.Calendar.GREGORIAN;
        draft.withTime = true;
        Parser.Result result = read(draft);
        assertEquals(Parser.Status.PARSED, result.status());
        Parser.Fact fact = result.facts().get(0);
        assertEquals(Parser.Precision.MINUTE, fact.time().precision());
        assertEquals(2026, LocalDate.ofInstant(fact.time().instant(), ZONE).getYear());
    }

    @Test
    public void bothShapeProducesBalanceAndMovement() {
        RuleDraft draft = movement();
        draft.shape = RuleDraft.Shape.BOTH;
        Parser.Result result = read(draft);
        assertEquals(Parser.Status.PARSED, result.status());
        assertEquals(2, result.facts().size());
        Parser.Fact balance = result.facts().get(0);
        assertEquals(Rules.Kind.BOOKED_BALANCE, balance.kind());
        assertEquals(4_500_000L, balance.money().minorUnits());
        // Both outputs come from one message, which is how Iranian banks send a debit and the
        // remaining balance together.
        assertEquals(1, result.facts().stream()
                .filter(f -> f.kind() == Rules.Kind.POSTED_MOVEMENT).count());
    }

    @Test
    public void currencyTheMessageNamesWins() {
        // A reader should not have to know that a toman is a tenth of a rial for the rule to read
        // the number the bank meant.
        RuleDraft draft = new RuleDraft();
        draft.sender = "+982000320000";
        draft.bankName = "Test Bank";
        draft.body = "برداشت 12,000,000 تومان\n1405/07/09";
        draft.shape = RuleDraft.Shape.MOVEMENT;
        int start = draft.body.indexOf("12,000,000");
        draft.highlight(start, start + 10, RuleDraft.Role.AMOUNT);
        int date = draft.body.indexOf("1405/07/09");
        draft.highlight(date, date + 10, RuleDraft.Role.DATE);
        Parser.Result result = read(draft);
        assertEquals(Rules.Currency.IRR, result.facts().get(0).money().currency());
        assertEquals(-120_000_000L, result.facts().get(0).money().minorUnits());
    }

    /** Every bound the core enforces is checked here, because a rule the builder calls ready but
     *  the core rejects leaves the reader pressing Install and being told only that it failed. */
    @Test
    public void aSenderTheCoreWouldRefuseStopsTheDraft() {
        RuleDraft draft = movement();
        draft.sender = "+98".repeat(Rules.MAX_SENDER);
        assertTrue(draft.sender.length() > Rules.MAX_SENDER);
        problem(draft, RuleDraft.Code.SENDER_TOO_LONG);
    }

    @Test
    public void aBankNameTheCoreWouldRefuseStopsTheDraft() {
        RuleDraft draft = movement();
        draft.bankName = "B".repeat(Rules.MAX_SENDER + 1);
        problem(draft, RuleDraft.Code.BANK_TOO_LONG);
    }

    @Test
    public void aMessageWithTooManyLinesStopsTheDraft() {
        RuleDraft draft = movement();
        draft.body = "برداشت ۱۲۰,۰۰۰ ریال\nمانده حساب: 4,500,000 ریال\n1405/07/09 12:41\n"
                + "خط\n".repeat(Rules.MAX_LINES);
        problem(draft, RuleDraft.Code.TOO_MANY_LINES);
    }

    /** The screen refuses an exclusion the rule cannot be written with, so the list never shows a
     *  guard that would sit there looking like protection while the rule read the message anyway. */
    @Test
    public void anExclusionFromTheExampleIsRefused() {
        RuleDraft draft = movement();
        // Words from another message are exactly what an exclusion is for, so they are accepted.
        assertTrue(draft.exclusionUsable("رمز یک‌بار مصرف"));
        assertTrue(draft.exclusionUsable("OTP"));
        // Words the example contains are refused: such a guard would make the rule reject the very
        // message it was built from, and the reader would be told their rule does not work.
        assertFalse(draft.exclusionUsable("مانده حساب"));
        assertTrue(draft.inExample("مانده حساب"));
        assertFalse(draft.inExample("OTP"));
        assertFalse(draft.exclusionUsable(""));
    }

    /** The catch-all guard must stay short enough for the core's work budget, or a rule written
     *  from a big message gives up on ordinary ones after it. */
    @Test
    public void theCatchAllGuardStaysShort() {
        RuleDraft draft = movement();
        draft.exclusions.clear();
        PackDocument document = PackDocument.decode(draft.document());
        String literal = (String) document.templates().get(0).guards().get(0).literal();
        assertTrue("guard was " + literal.length(), literal.length() <= 48);
    }

    @Test
    public void missingSenderStopsTheDraft() {
        RuleDraft draft = movement();
        draft.sender = "  ";
        problem(draft, RuleDraft.Code.NO_SENDER);
    }

    @Test
    public void missingAmountStopsTheDraft() {
        RuleDraft draft = movement();
        draft.clear(RuleDraft.Role.AMOUNT);
        problem(draft, RuleDraft.Code.MISSING_AMOUNT);
    }

    @Test
    public void balanceShapeNeedsABalance() {
        RuleDraft draft = movement();
        draft.shape = RuleDraft.Shape.BALANCE;
        draft.clear(RuleDraft.Role.BALANCE);
        problem(draft, RuleDraft.Code.MISSING_BALANCE);
    }

    @Test
    public void aValueAtTheStartOfALineIsRefused() {
        RuleDraft draft = new RuleDraft();
        draft.sender = "+982000320000";
        draft.bankName = "Test Bank";
        draft.body = "4,500,000 ریال مانده حساب";
        draft.shape = RuleDraft.Shape.BALANCE;
        draft.highlight(0, 9, RuleDraft.Role.BALANCE);
        // The rule would have to read from the start of the line with nothing to anchor it.
        problem(draft, RuleDraft.Code.NO_PREFIX);
    }

    @Test
    public void aHighlightThatIsNotANumberIsRefused() {
        RuleDraft draft = movement();
        draft.highlight(BODY.indexOf("مانده"), BODY.indexOf("مانده") + 5, RuleDraft.Role.AMOUNT);
        RuleDraft.Problem problem = problem(draft, RuleDraft.Code.NOT_NUMERIC);
        assertEquals("مانده", problem.detail);
    }

    @Test
    public void aHighlightAcrossTwoLinesIsRefused() {
        RuleDraft draft = movement();
        int start = BODY.indexOf("۱۲۰,۰۰۰");
        draft.highlight(start, BODY.indexOf("4,500,000") + 2, RuleDraft.Role.AMOUNT);
        problem(draft, RuleDraft.Code.CROSSES_LINE);
    }

    @Test
    public void overlappingHighlightsAreRefused() {
        RuleDraft draft = movement();
        int start = BODY.indexOf("۱۲۰,۰۰۰");
        draft.highlight(start, start + 4, RuleDraft.Role.AMOUNT);
        draft.highlight(start + 2, start + 9, RuleDraft.Role.BALANCE);
        draft.shape = RuleDraft.Shape.BOTH;
        problem(draft, RuleDraft.Code.OVERLAP);
    }

    @Test
    public void aDateWithoutSeparatorsIsRefused() {
        RuleDraft draft = new RuleDraft();
        draft.sender = "+982000320000";
        draft.bankName = "Test Bank";
        draft.body = "برداشت 12,000 ریال\n14050709";
        draft.shape = RuleDraft.Shape.MOVEMENT;
        int amount = draft.body.indexOf("12,000");
        draft.highlight(amount, amount + 6, RuleDraft.Role.AMOUNT);
        int date = draft.body.indexOf("14050709");
        draft.highlight(date, date + 8, RuleDraft.Role.DATE);
        // 14050709 could be read as a year, a month and a day in several orders, and the builder
        // will not guess which one the bank meant.
        problem(draft, RuleDraft.Code.DATE_NO_SEPARATOR);
    }

    @Test
    public void aDateOnItsOwnLineIsReadFromThatLine() {
        RuleDraft draft = movement();
        // The example puts the date at the start of the last line, which is the common layout. The
        // rule reads that line rather than reaching across the break, so a longer balance line
        // above cannot push the captured text over the field limit.
        assertEquals(2, draft.body.substring(0, draft.anchor(RuleDraft.Role.DATE).start)
                .chars().filter(c -> c == '\n').count());
        Parser.Result result = read(draft);
        assertEquals(Parser.Precision.DAY, result.facts().get(0).time().precision());
    }

    @Test
    public void aDateWithWordsBeforeItIsAnchoredByThem() {
        RuleDraft draft = new RuleDraft();
        draft.sender = "+982000320000";
        draft.bankName = "Test Bank";
        draft.body = "مانده حساب: 4,500,000 ریال در تاریخ 1405/07/09";
        draft.shape = RuleDraft.Shape.BALANCE;
        int balance = draft.body.indexOf("4,500,000");
        draft.highlight(balance, balance + 9, RuleDraft.Role.BALANCE);
        int date = draft.body.indexOf("1405/07/09");
        draft.highlight(date, date + 10, RuleDraft.Role.DATE);
        Parser.Result result = read(draft);
        assertEquals(4_500_000L, result.facts().get(0).money().minorUnits());
        LocalDate parsed = LocalDate.ofInstant(result.facts().get(0).time().instant(), ZONE);
        assertEquals(1405, JalaliCalendar.fromGregorian(parsed.getYear(), parsed.getMonthValue(),
                parsed.getDayOfMonth()).year);
    }

    @Test
    public void unsupportedIsAnExplicitOut() {
        RuleDraft draft = movement();
        draft.shape = RuleDraft.Shape.UNSUPPORTED;
        // Nothing else is demanded: the reader said no, and no rule is written for them.
        assertTrue(draft.problems().isEmpty());
        assertFalse(draft.ready());
        try {
            draft.document();
            fail("expected refusal");
        } catch (IllegalStateException expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void anUnbuiltDraftThrowsRatherThanWritingSomething() {
        RuleDraft draft = new RuleDraft();
        draft.sender = "x";
        draft.body = "nothing highlighted";
        assertFalse(draft.ready());
        try {
            draft.document();
            fail("expected refusal");
        } catch (IllegalStateException expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void exclusionsBecomeGuards() {
        RuleDraft draft = movement();
        // An exclusion is written as given. It narrows a rule that already cannot claim an
        // unrelated message, so nothing about it has to be provable against the example -- which is
        // the point, because the messages a reader wants skipped are the ones they never pasted.
        draft.exclusions.add("مانده");
        draft.exclusions.add("12:41");
        draft.exclusions.add("رمز یک‌بار مصرف");
        Map<?, ?> template = (Map<?, ?>) ((List<?>) draft.document().get("templates")).get(0);
        List<?> guards = (List<?>) template.get("guards");
        // The two words the example itself contains are the ones that cannot be written: a guard
        // the example fails would be a rule that refuses the message it was built from.
        assertEquals(2, guards.size());
        assertEquals(Boolean.TRUE, ((Map<?, ?>) guards.get(1)).get("excluded"));
    }

    /** The safety boundary is the positive guard, and it comes from the example. An exclusion the
     *  example fails is not written; one it passes is written and narrows the rule. */
    @Test
    public void anExclusionDoesNotStopTheRuleReadingItsOwnExample() {
        RuleDraft draft = movement();
        draft.exclusions.clear();
        draft.exclusions.add("رمز یک‌بار مصرف");
        // read() fails the test unless the rule parses the example, which is the whole point: an
        // exclusion naming another message must leave this one readable.
        Parser.Result result = read(draft);
        assertEquals(1, result.facts().size());
        assertEquals(-120_000L, result.facts().get(0).money().minorUnits());
    }

    @Test
    public void aRuleBuiltFromOneExampleMustRejectAnotherBanksWording() {
        RuleDraft draft = movement();
        PackDocument document = PackDocument.decode(draft.document());
        Parser parser = new Parser(document.templates());
        // A different message from the same sender must not be claimed by this rule.
        Parser.Result other = parser.parse(new Parser.Message("sms:2", draft.sender,
                "خوش آمدید به بانک تست\nمانده حساب: 9,900,000 ریال", ARRIVAL, ZONE));
        assertEquals(Parser.Status.NO_MATCH, other.status());
        assertEquals(0, other.facts().size());
    }

    @Test
    public void varyingDigitsWidensOnlyWhenTheReaderAsksForIt() {
        // The example's own length is the default: a rule looks exactly as far as it needs to,
        // because a rule that looks too far can pick up a neighbouring number.
        RuleDraft tight = movement();
        int tightWidth = amountMaxLength(tight);

        RuleDraft loose = movement();
        loose.varyingDigits = true;
        int looseWidth = amountMaxLength(loose);

        assertTrue("the wider rule really does look further: " + tightWidth + " -> " + looseWidth,
                looseWidth > tightWidth);
    }

    /** The maxLength the generated rule gives its amount, read back out of the document rather
     *  than recomputed, so the test measures what a user would actually install. */
    private static int amountMaxLength(RuleDraft draft) {
        PackDocument document = PackDocument.decode(draft.document());
        Rules.Output output = document.templates().get(0).outputs().get(0);
        return output.money().amount().maxLength();
    }
}
