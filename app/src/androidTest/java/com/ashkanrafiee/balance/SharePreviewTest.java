package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.widget.CheckBox;
import android.widget.EditText;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The share preview: what the reader sees before a report leaves the device, and the rule context
 * the report now carries.
 *
 * <p>Three claims are under test. The preview shows exactly the text that will be sent, byte for
 * byte including line breaks, because a report whose formatting was silently tidied up is a report
 * the maintainer cannot check against a phone. Redaction keeps every number the same length, so the
 * redacted sample still exercises the rule — and keeps every line, so nothing about the layout is
 * lost either. And the rule context names rules by identity only: it must never carry an amount or
 * an account into a report the reader did not choose to include those in.
 */
@RunWith(AndroidJUnit4.class)
public class SharePreviewTest {

    /** A shipped bank's own layout, so the engine has a rule to consult and to name. */
    private static final String SENDER = "+9815560001";
    private static final String BODY = "برداشت100,000,000 مانده 77,222,945";
    private static final long ARRIVAL = Instant.parse("2026-08-30T06:00:00Z").toEpochMilli();

    private Context ctx;
    private EngineRules engine;

    @Before public void loadTheEngine() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        engine = EngineRules.activate(ctx);
        assertNotNull("the bundled engine loads", engine);
    }

    // ---- the redactor ----

    @Test public void redaction_keepsEveryLengthAndSeparator() {
        String redacted = Redactor.redact(BODY);
        assertEquals("the sample is exactly as long as the original", BODY.length(), redacted.length());
        for (int i = 0; i < BODY.length(); i++)
            assertEquals("position " + i + " keeps its script", digitScript(BODY.charAt(i)),
                    digitScript(redacted.charAt(i)));
        assertFalse("the account-scale numbers are gone", redacted.contains("100,000,000"));
        assertFalse("the balance is gone", redacted.contains("77,222,945"));
    }

    @Test public void redaction_leavesEverythingElseAlone() {
        String text = "Hai richiesto una spesa di EUR 66,80 alle ore 22:29\nsecond line 557";
        String redacted = Redactor.redact(text);
        assertEquals("the words, the spaces, the punctuation and the line break stay put",
                text.replaceAll("[0-9]", "#"), redacted.replaceAll("[0-9]", "#"));
        assertEquals("and the length does not move", text.length(), redacted.length());
    }

    @Test public void redaction_isStableSoAPreviewDoesNotShuffle() {
        assertEquals("the same sample redacts to the same text", Redactor.redact(BODY),
                Redactor.redact(BODY));
        assertTrue("a run of repeated digits does not become a run of the same digit",
                !Redactor.redact("0000000000").equals("0000000000"));
    }

    @Test public void redaction_keepsPersianDigitsInPersian() {
        String persian = "مانده ۱۲۳,۴۵۶";
        String redacted = Redactor.redact(persian);
        assertEquals("length and digits stay Persian", persian.length(), redacted.length());
        for (char c : redacted.toCharArray())
            assertFalse("no Persian digit became a Latin one", c >= '0' && c <= '9');
        assertFalse("and the number is still not the original", redacted.equals(persian));
        assertTrue("digits are present to be found", Redactor.hasDigits(redacted));
        assertFalse("text without digits has nothing to hide", Redactor.hasDigits("no digits here"));
    }

    /** The digit script a character belongs to, or none: what redaction must not change. */
    private static String digitScript(char c) {
        if (c >= '0' && c <= '9') return "latin";
        if (c >= '۰' && c <= '۹') return "persian";
        return "none";
    }

    // ---- the rule context ----

    @Test public void ruleContext_namesTheRuleThatReadTheMessage() {
        String context = RuleContext.forMessage(engine, SENDER, BODY, ARRIVAL);
        assertTrue("the report says the engine read the message", context.contains("parsed in full"));
        assertTrue("and names the pack, revision and template by identity",
                context.contains("ir.mellat r") && context.contains("mellat"));
    }

    @Test public void ruleContext_neverCarriesTheNumbersItDescribes() {
        String context = RuleContext.forMessage(engine, SENDER, BODY, ARRIVAL);
        assertFalse("no amount", context.contains("100,000,000"));
        assertFalse("no balance", context.contains("77,222,945"));
        assertFalse("no fragment of the body", context.contains("مانده"));
    }

    @Test public void ruleContext_saysNothingForASenderNoPackCovers() {
        assertEquals("an unknown sender gets no invented context", "",
                RuleContext.forMessage(engine, "+98unknownsender", BODY, ARRIVAL));
        assertEquals("and a failed engine says nothing either", "",
                RuleContext.forMessage(null, SENDER, BODY, ARRIVAL));
    }

    @Test public void ruleContext_separatesNoMatchFromAMatchThatFailed() {
        String unmatched = RuleContext.forMessage(engine, SENDER, "کاملاً ناشناخته ۱۲۳۴۵", ARRIVAL);
        assertTrue("a rule claimed the sender but nothing matched", unmatched.contains("no rule matched"));
        assertFalse("and it is not claimed as parsed", unmatched.contains("parsed in full"));
    }

    @Test public void reportHeader_namesTheEngineWithoutItsContents() {
        String header = RuleContext.header(engine);
        assertTrue("the engine is named", header.contains("prototype-1"));
        assertTrue("and how many rules are in play", header.contains("bundled packs"));
        assertEquals("a failed engine says nothing at all", "", RuleContext.header(null));
    }

    @Test public void theReportCarriesTheRuleContext() {
        List<ScanDiagnostics.Message> selected = List.of(new ScanDiagnostics.Message(BODY, ARRIVAL));
        String report = ScanDiagnostics.senderReport(SENDER, 1, selected,
                List.of(ScanDiagnostics.ISSUE_MOVEMENT), engine);
        assertTrue("the report names the engine", report.contains("prototype-1"));
        assertTrue("and the rule that read the sample", report.contains("ir.mellat r"));
        assertTrue("the sample itself is still there, because the reader chose it",
                report.contains(BODY));
    }

    // ---- the preview screen ----

    @Test public void thePreviewShowsExactlyTheTextThatWillBeSent() throws Exception {
        String report = "line one\n\nline two with 77,222,945\n";
        SharePreviewActivity screen = launch(report);
        try {
            String shown = editorOf(screen).getText().toString();
            assertEquals("the document is untouched, newlines and all", report, shown);
            assertTrue("and the keyboard is not offered to correct it",
                    (editorOf(screen).getInputType() & android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0);
            assertFalse("raw message text is never kept in saved state",
                    editorOf(screen).isSaveEnabled());
        } finally {
            finish(screen);
        }
    }

    @Test public void hidingTheNumbersKeepsTheShapeOfTheDocument() throws Exception {
        String report = "Sender `+9815560001`\nمانده 77,222,945\n";
        SharePreviewActivity screen = launch(report);
        try {
            EditText editor = editorOf(screen);
            CheckBox toggle = findCheckBox(screen);
            onMain(() -> toggle.setChecked(true));
            String hidden = onMainResult(editor::getText).toString();
            assertEquals("the layout is intact after redaction", report.length(), hidden.length());
            assertFalse("the account number is gone", hidden.contains("+9815560001"));
            assertFalse("the balance is gone", hidden.contains("77,222,945"));
            assertTrue("but the words are", hidden.contains("مانده"));

            onMain(() -> toggle.setChecked(false));
            assertEquals("un-hiding restores the reader's own text exactly", report,
                    onMainResult(editor::getText).toString());
        } finally {
            finish(screen);
        }
    }

    @Test public void thePreviewCanOpenWithTheNumbersAlreadyHidden() throws Exception {
        String report = "balance 77,222,945 today";
        SharePreviewActivity screen = launchRedacted(report);
        try {
            String shown = editorOf(screen).getText().toString();
            assertEquals("length preserved", report.length(), shown.length());
            assertFalse("the number is gone", shown.contains("77,222,945"));
        } finally {
            finish(screen);
        }
    }

    // ---- helpers ----

    private SharePreviewActivity launch(String report) {
        return launchWith(report, false);
    }

    private SharePreviewActivity launchRedacted(String report) {
        return launchWith(report, true);
    }

    private SharePreviewActivity launchWith(String report, boolean redacted) {
        Intent intent = new Intent(ctx, SharePreviewActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SharePreviewActivity.EXTRA_SUBJECT, "Balance: test")
                .putExtra(SharePreviewActivity.EXTRA_REPORT, report)
                .putExtra(SharePreviewActivity.EXTRA_REDACTED, redacted);
        return (SharePreviewActivity) InstrumentationRegistry.getInstrumentation()
                .startActivitySync(intent);
    }

    /** The document editor, found by type: the screen has exactly one and it has no resource id. */
    private EditText editorOf(SharePreviewActivity screen) {
        return (EditText) onMainResult(() -> find(screen.getWindow().getDecorView(), EditText.class));
    }

    private CheckBox findCheckBox(SharePreviewActivity screen) {
        return (CheckBox) onMainResult(() -> find(screen.getWindow().getDecorView(), CheckBox.class));
    }

    private <T extends android.view.View> T find(android.view.View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof android.view.ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = findOrNull(group.getChildAt(i), type);
                if (found != null) return found;
            }
        return null;
    }

    private <T extends android.view.View> T findOrNull(android.view.View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof android.view.ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = findOrNull(group.getChildAt(i), type);
                if (found != null) return found;
            }
        return null;
    }

    private List<String> screenText(android.app.Activity act) {
        List<String> out = new ArrayList<>();
        onMain(() -> collect(act.getWindow().getDecorView(), out));
        return out;
    }

    private void collect(android.view.View v, List<String> out) {
        if (v instanceof android.widget.TextView) out.add(((android.widget.TextView) v).getText().toString());
        if (v instanceof android.view.ViewGroup group) {
            android.view.ViewGroup g = (android.view.ViewGroup) group;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    private void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private Object onMainResult(java.util.concurrent.Callable<Object> action) {
        Object[] out = new Object[1];
        onMain(() -> {
            try {
                out[0] = action.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return out[0];
    }

    private void finish(android.app.Activity act) {
        onMain(act::finish);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
}