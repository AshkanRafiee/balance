package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import com.ashkanrafiee.balance.parser.Parser;

import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What the builder tells a reader before they install anything.
 *
 * <p>These run against the catalog this device actually ships and a real example message, because
 * the claims a builder reports are only true for the rules that are present at the moment: a test
 * that composed an empty parser would pass while telling a reader their sender is new when the
 * catalog already covers it.
 */
public class RuleDraftTesterTest {
    private static final String SENDER = "+982000320000";
    /** A sender the shipped catalog covers, so the claims path has something real to report. */
    private static final String SHIPPED_SENDER = "+98200036";
    private static final String BODY = "برداشت ۱۲۰,۰۰۰ ریال\nمانده حساب: 4,500,000 ریال\n1405/07/09 12:41";
    private static final long ARRIVAL = Instant.parse("2026-09-30T10:15:00Z").toEpochMilli();

    private EngineRules engine;

    @Before
    public void loadCatalog() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        EngineRules.get();
        engine = EngineRules.load(context);
    }

    private static RuleDraft draft(String sender) {
        RuleDraft draft = new RuleDraft();
        draft.sender = sender;
        draft.bankName = "Test Bank";
        draft.body = BODY;
        draft.shape = RuleDraft.Shape.MOVEMENT;
        draft.direction = "DEBIT";
        int amount = BODY.indexOf("۱۲۰,۰۰۰");
        draft.highlight(amount, amount + "۱۲۰,۰۰۰".length(), RuleDraft.Role.AMOUNT);
        int balance = BODY.indexOf("4,500,000");
        draft.highlight(balance, balance + "4,500,000".length(), RuleDraft.Role.BALANCE);
        int date = BODY.indexOf("1405/07/09");
        draft.highlight(date, date + 10, RuleDraft.Role.DATE);
        return draft;
    }

    @Test
    public void readsTheExampleTheReaderPasted() {
        RuleDraftTester.Verdict verdict =
                RuleDraftTester.test(draft(SENDER), engine, SENDER, BODY, ARRIVAL);
        assertTrue(verdict.reason, verdict.parsed);
        assertEquals(Parser.Status.PARSED, verdict.status);
        // The currency travels with the number: a screen cannot divide by a scale it was not told.
        assertEquals(List.of(new RuleDraftTester.Amount("IRR", -120000L)), verdict.amounts);
        assertTrue(verdict.issues.toString(), verdict.issues.isEmpty());
    }

    @Test
    public void reportsNothingCoveringAnUnknownSender() {
        RuleDraftTester.Verdict verdict =
                RuleDraftTester.test(draft(SENDER), engine, SENDER, BODY, ARRIVAL);
        assertTrue(verdict.claims.toString(), verdict.claims.isEmpty());
        assertFalse(verdict.needsConfirming());
    }

    @Test
    public void namesThePacksThatAlreadyClaimAShippedSender() {
        RuleDraftTester.Verdict verdict =
                RuleDraftTester.test(draft(SHIPPED_SENDER), engine, SHIPPED_SENDER, BODY, ARRIVAL);
        assertTrue(verdict.claims.toString(), verdict.claims.contains("ir.ansar"));
        assertTrue(verdict.needsConfirming());
        assertFalse("claims are named once", verdict.claims.size() != new java.util.HashSet<>(
                verdict.claims).size());
    }

    @Test
    public void anExclusionNamingTheExampleIsNotWrittenIntoTheRule() {
        RuleDraft draft = draft(SENDER);
        // A guard that excludes a line the example is on would mean no rule can claim the message
        // the reader pasted. Such an exclusion is dropped rather than written, so a draft that
        // somehow holds one still reads its own example instead of reporting a failure the reader
        // cannot act on.
        draft.exclusions.add("مانده حساب");
        RuleDraftTester.Verdict verdict =
                RuleDraftTester.test(draft, engine, SENDER, BODY, ARRIVAL);
        assertTrue(verdict.reason, verdict.parsed);
        assertEquals(Parser.Status.PARSED, verdict.status);
    }

    @Test
    public void anEmptyExampleIsNotReportedAsWorking() {
        // A draft's guard requires a word that is present in the message the reader highlighted
        // under, so a blank example cannot satisfy it: the verdict must be a failure, and the
        // engine's own word for it rather than a verdict the tester invented.
        RuleDraftTester.Verdict verdict =
                RuleDraftTester.test(draft(SENDER), engine, SENDER, "   ", ARRIVAL);
        assertFalse(verdict.parsed);
        assertEquals(Parser.Status.NO_MATCH, verdict.status);
        assertTrue(verdict.amounts.isEmpty());
    }

    @Test
    public void readsWithoutAnEngineLoaded() {
        // An engine that did not load must still let a reader test their own rule: the draft stands
        // on its own, and the builder falls back to testing it alone rather than claiming it
        // cannot be tested at all.
        RuleDraftTester.Verdict verdict =
                RuleDraftTester.test(draft(SENDER), null, SENDER, BODY, ARRIVAL);
        assertTrue(verdict.parsed);
        assertTrue(verdict.claims.isEmpty());
    }

    @Test
    public void aDraftThatCannotBeBuiltIsNotTested() {
        RuleDraft empty = new RuleDraft();
        empty.body = BODY;
        empty.sender = SENDER;
        empty.bankName = "Test Bank";
        Map<String, Object> nothing = Map.of();
        assertTrue(nothing.isEmpty());
        // Without a highlighted amount the draft is not buildable, and the tester must say the
        // document was rejected rather than parse it with no outputs at all.
        RuleDraftTester.Verdict verdict = RuleDraftTester.test(empty, engine, SENDER, BODY, ARRIVAL);
        assertEquals(Parser.Status.INVALID, verdict.status);
        assertEquals("document", verdict.reason);
        assertFalse(verdict.parsed);
    }
}