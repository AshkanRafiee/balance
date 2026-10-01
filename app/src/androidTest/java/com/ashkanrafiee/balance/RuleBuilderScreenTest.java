package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.ashkanrafiee.balance.parser.LocalPackStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * H3: the screen that turns one bank message into a rule, and the two things that must be true of
 * it.
 *
 * <p>First, that it does what the builder claims: what the reader highlighted is what the installed
 * pack reads, and the pack is on the device afterwards. Second, that it says no when it cannot: a
 * message the reader calls unreadable produces nothing, and a rule that does not read the example
 * is not installed however complete the form looks.
 *
 * <p>The privacy claim is checked from the outside as well: the screen keeps the message off saved
 * instance state and holds the draft sealed, so a draft left on the device is not a readable file.
 */
@RunWith(AndroidJUnit4.class)
public class RuleBuilderScreenTest {

    private static final String SENDER = "+989120000000";
    private static final String BANK = "Builder Bank";
    private static final String BODY =
            "برداشت ۱۲۰,۰۰۰ ریال\nمانده حساب: 4,500,000 ریال\n1405/07/09 12:41";

    private Context ctx;
    private RuleDraftStore store;
    /** The instance this test opened. Finishing a builder is asynchronous, so for a moment after a
     *  launch the outgoing activity is still resumed next to the new one; addressing the tree by
     *  "whatever is resumed" would then let a helper type into the wrong draft. */
    private RuleBuilderActivity screen;

    @Before public void empty() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Order matters: closing a builder writes its draft back on the way out, so the store can
        // only be emptied once nothing is left to write into it. Otherwise a draft from the
        // previous test is restored into this one and the reader starts from someone else's rule.
        finishAnyResumedBuilder();
        EngineRules.localStore(ctx).clear();
        store = new RuleDraftStore(ctx);
        store.clear();
        // The app lock covers every screen, and this device has it enabled from an earlier test, so
        // the overlay would sit over the builder and every tap would land on the keypad. These tests
        // are about the builder; the lock has its own tests.
        LockManager.disable(ctx);
    }

    @After public void tidy() {
        finishAnyResumedBuilder();
        store.clear();
    }

    // ---- what the screen does ----

    @Test public void showsTheMessageTheReaderPasted() {
        launch();
        assertNotNull("the pasted message must be on screen", messageField());
        assertEquals(BODY, messageField().getText().toString());
    }

    private void launch() {
        launch(BODY);
    }

    /** The same, with a message of the test's own -- used where the message itself is the thing
     *  being wrong. */
    private void launch(String body) {
        Intent i = new Intent(ctx, RuleBuilderActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        screen = (RuleBuilderActivity)
                InstrumentationRegistry.getInstrumentation().startActivitySync(i);
        await(() -> messageField() != null, 20_000);
        type(messageField(), body);
    }

    @Test public void everyStepIsOnScreen() {
        launch();
        StringBuilder onScreen = new StringBuilder();
        for (TextView t : texts()) onScreen.append("[").append(t.getText()).append("]");
        for (int step : new int[]{R.string.builder_section_message,
                R.string.builder_section_identity, R.string.builder_section_type,
                R.string.builder_section_highlights, R.string.builder_section_direction,
                R.string.builder_section_test}) {
            assertNotNull("missing " + ctx.getString(step) + " in " + onScreen,
                    text(ctx.getString(step)));
        }
    }

    

    @Test public void keepsAPausedDraftSoAnInterruptedRuleIsNotLost() {
        launch();
        type(messageField(), "message that was not finished");
        await(() -> store.present(), 10_000);
        // The draft survives on the device, sealed: the reader's half-built rule is the one thing
        // the builder must not lose when the screen is taken away mid-edit.
        assertNotNull(store.read());
        assertEquals("message that was not finished", store.read().get("body"));
    }

    @Test public void reopensTheHighlightsTheReaderChose() {
        launch();
        tap(ctx.getString(R.string.builder_shape_both));
        select(RuleDraft.Role.AMOUNT, "۱۲۰,۰۰۰");
        select(RuleDraft.Role.BALANCE, "4,500,000");
        // Leaving the screen writes the draft back; opening it again must put the reader where they
        // were, not at the start of a rule they had already partly built.
        screen = null;
        finishAnyResumedBuilder();
        launch();
        assertNotNull("the amount must still be chosen",
                text(ctx.getString(R.string.builder_selected, "۱۲۰,۰۰۰")));
        assertNotNull("the balance must still be chosen",
                text(ctx.getString(R.string.builder_selected, "4,500,000")));
    }


    @Test public void doesNotPutTheMessageInSavedInstanceState() {
        launch();
        type(messageField(), "a bank message with 120,000 in it");
        // Nothing here may be restorable from a bundle: raw bank text in saved state is what the
        // clipboard and screenshot protections elsewhere in the app exist to prevent.
        assertNull("no saved state carries the message",
                savedStateText("a bank message with 120,000 in it"));
    }

    @Test public void aMessageTheReaderCannotReadProducesNothing() {
        launch();
        tap(ctx.getString(R.string.builder_shape_unsupported));
        assertNotNull(text(ctx.getString(R.string.builder_unsupported_note)));
        // No install button, no test button: an unsupported draft offers nothing to do.
        assertNull(text(ctx.getString(R.string.builder_install)));
    }

    @Test public void anAmountThatIsNotANumberIsRefusedRatherThanInstalled() throws Exception {
        // A marketing SMS from the same sender: the reader highlights the word they thought held
        // the amount, and the builder has to say that is not something a rule can read.
        launch("تخفیف ویژه به همکاران ما");
        select(RuleDraft.Role.AMOUNT, "همکاران");
        fillBankAndSender();
        assertNotNull("the problem must be named before anything is offered",
                textContaining(ctx.getString(R.string.builder_problem_not_numeric, "همکاران")));
        tap(ctx.getString(R.string.builder_test));
        // Nothing to test, so nothing is tested and nothing is installed.
        assertEquals(0, EngineRules.localStore(ctx).snapshot().size());
    }

    @Test public void aRuleIsInstalledOnlyAsItWasTested() throws Exception {
        launch();
        tap(ctx.getString(R.string.builder_shape_both));
        select(RuleDraft.Role.AMOUNT, "۱۲۰,۰۰۰");
        select(RuleDraft.Role.BALANCE, "4,500,000");
        fillBankAndSender();
        tap(ctx.getString(R.string.builder_test));
        awaitVerdict();
        assertNotNull("the rule must read the example before it can be installed", worked());
        // Changing the rule after testing it invalidates the reading: a verdict belongs to the rule
        // it was taken from, and installing on the strength of one would put on the device a rule
        // that was never run against anything.
        tap(ctx.getString(R.string.builder_direction_credit));
        assertFalse("a verdict must not outlive the rule it was taken from", worked());
        assertFalse("and neither must the failure it replaced", notWorked());
        tap(ctx.getString(R.string.builder_install));
        assertEquals("nothing may be installed on a stale reading",
                0, EngineRules.localStore(ctx).snapshot().size());
        assertTrue("the draft survives a refused install", store.present());
    }

    @Test public void installingPutsTheRuleOnTheDevice() throws Exception {
        launch();
        // The builder only asks for the parts the chosen shape needs, so a balance is a question the
        // screen must be asked before it shows a row to answer it in.
        tap(ctx.getString(R.string.builder_shape_both));
        select(RuleDraft.Role.AMOUNT, "۱۲۰,۰۰۰");
        select(RuleDraft.Role.BALANCE, "4,500,000");
        select(RuleDraft.Role.DATE, "1405/07/09");
        fillBankAndSender();
        tap(ctx.getString(R.string.builder_test));
        awaitVerdict();
        assertNotNull("the rule must read the example before it can be installed", worked());
        tap(ctx.getString(R.string.builder_install));
        await(() -> !store.present(), 15_000);
        // The store now holds a pack, and the engine composed it.
        LocalPackStore local = EngineRules.localStore(ctx);
        assertEquals(1, local.snapshot().packs().size());
        EngineRules engine = EngineRules.activate(ctx);
        assertTrue("the installed pack must be composed into the engine",
                !engine.localBanks().isEmpty());
        assertTrue("the draft must be forgotten once it is installed", !store.present());
    }

    // ---- helpers ----

    /** Opens the builder with the example already in the message field, which is where the reader
     *  starts: they paste a message, and every test after this point is about the rule, not the
     *  typing. */
    /** The two verdicts, matched on their opening words so the assertions do not depend on how the
     *  rest of the sentence is worded in each locale. */
    private boolean worked() {
        return textStartingWith(headOf(R.string.builder_test_working)) != null;
    }

    private boolean notWorked() {
        return textStartingWith(headOf(R.string.builder_test_not_working)) != null;
    }

    /** The first few words of a verdict string, which say what happened rather than how. */
    private String headOf(int res) {
        String words = ctx.getString(res);
        int cut = words.indexOf(':');
        return cut > 0 ? words.substring(0, cut + 1) : words;
    }

    private TextView textContaining(String needle) {
        TextView found = null;
        for (TextView t : texts()) {
            if (t.getText().toString().contains(needle)) found = t;
        }
        return found;
    }

    private TextView textStartingWith(String prefix) {
        TextView found = null;
        for (TextView t : texts()) {
            if (t.getText().toString().startsWith(prefix)) found = t;
        }
        return found;
    }

    private EditText messageField() {
        return fieldWith(ctx.getString(R.string.builder_message_hint));
    }

    private EditText fieldWith(String hint) {
        EditText found = null;
        for (EditText e : edits()) {
            if (hint.equals(String.valueOf(e.getHint()))) found = e;
        }
        return found;
    }

    private void type(EditText field, String value) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> field.setText(value));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    /** Waits for a verdict, and says what was on screen when none arrived: a builder that cannot
     *  produce one is the failure this message has to explain. */
    private void awaitVerdict() {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (worked() || notWorked()) return;
            try { Thread.sleep(150); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        StringBuilder onScreen = new StringBuilder();
        for (TextView t : texts()) onScreen.append("[").append(t.getText()).append("]");
        throw new AssertionError("no verdict after testing; on screen: " + onScreen);
    }

    /** Selects a substring of the message the way a reader does: choose it, then press Set. The
     *  word is located in whatever is on screen, so a test may work from a message of its own. */
    private void select(RuleDraft.Role role, String word) {
        EditText field = messageField();
        assertNotNull("no message field to select in", field);
        int start = field.getText().toString().indexOf(word);
        assertTrue("the message on screen does not contain " + word, start >= 0);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            field.requestFocus();
            field.setSelection(start, start + word.length());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        // The button is addressed by role, not by its label: four rows carry the same word.
        View button = tagged(RuleBuilderActivity.TAG_SET + role.name());
        assertNotNull("no set button for " + role, button);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> button.performClick());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private View tagged(String tag) {
        final View[] found = new View[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : resumed()) found[0] = findTag(a.getWindow().getDecorView(), tag);
        });
        return found[0];
    }

    private static View findTag(View v, String tag) {
        if (tag.equals(v.getTag())) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = findTag(g.getChildAt(i), tag);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private void fillBankAndSender() {
        type(fieldWith(ctx.getString(R.string.builder_bank_hint)), BANK);
        type(fieldWith(ctx.getString(R.string.builder_sender_hint)), SENDER);
    }

    private void tap(String label) {
        TextView view = text(label);
        assertNotNull("no tappable row labelled " + label, view);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> view.performClick());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private TextView text(String label) {
        TextView found = null;
        for (TextView t : texts()) if (label.equals(t.getText().toString())) found = t;
        return found;
    }

    private List<TextView> texts() {
        List<TextView> out = new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : resumed()) collect(a.getWindow().getDecorView(), out);
        });
        return out;
    }

    private List<EditText> edits() {
        List<EditText> out = new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : resumed()) collectEdits(a.getWindow().getDecorView(), out);
        });
        return out;
    }

    private static void collect(View v, List<TextView> out) {
        if (v instanceof TextView) out.add((TextView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    private static void collectEdits(View v, List<EditText> out) {
        if (v instanceof EditText) out.add((EditText) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectEdits(g.getChildAt(i), out);
        }
    }

    /** What the platform would write into a saved-state bundle, asked of the view hierarchy rather
     *  than of the activity: this is the exact path a backgrounded screen's text takes, so the
     *  privacy claim is checked against what the platform does rather than against our own memory
     *  of having set a flag. */
    private String savedStateText(String needle) {
        final String[] found = new String[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : resumed()) {
                android.util.SparseArray<android.os.Parcelable> state =
                        new android.util.SparseArray<>();
                a.getWindow().getDecorView().saveHierarchyState(state);
                for (int i = 0; i < state.size(); i++) {
                    android.os.Parcelable value = state.valueAt(i);
                    if (value != null && value.toString().contains(needle)) {
                        found[0] = value.toString();
                    }
                }
            }
        });
        return found[0];
    }

    /** The builders this class currently cares about: the one it launched, or -- before the first
     *  launch, and in the teardown helpers -- every resumed one. */
    private List<Activity> resumed() {
        List<Activity> out = new ArrayList<>();
        if (screen != null) {
            out.add(screen);
        } else {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (a instanceof RuleBuilderActivity) out.add(a);
            }
        }
        return out;
    }

    private static void finishAnyResumedBuilder() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (a instanceof RuleBuilderActivity) a.finish();
            }
        });
    }

    private static void await(Callable<Boolean> done, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try { if (Boolean.TRUE.equals(done.call())) return; } catch (Exception ignored) { }
            try { Thread.sleep(150); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("timed out waiting for the builder screen");
    }
}