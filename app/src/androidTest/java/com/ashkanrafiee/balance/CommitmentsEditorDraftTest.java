package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** The commitment editor keeps unfinished input across pauses: the notification-permission
 *  prompt, the screen going off and Home all pause (and dismiss) the editor, and the next
 *  resume must reopen it with everything still typed. Save/cancel/back keep dropping the
 *  draft, exactly as before. */
@RunWith(AndroidJUnit4.class)
public class CommitmentsEditorDraftTest {

    private Context ctx;
    private String originalLanguage;
    private String originalCurrency;
    private int originalRegion;
    private List<Commitment> originalCommitments;
    private ActivityScenario<CommitmentsActivity> scenario;

    @Before public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CommitmentStore.clear(ctx);
        originalLanguage = LocaleHelper.currentTag(ctx);
        originalCurrency = CurrencyHelper.currency(ctx);
        originalRegion = RegionHelper.region(ctx);
        originalCommitments = new ArrayList<>(BalanceData.readCommitments(ctx));

        LocaleHelper.setLanguage(ctx, "en");
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CURRENCY_RIAL);
        RegionHelper.setRegion(ctx, RegionHelper.REGION_INTERNATIONAL);
        BalanceData.writeCommitments(ctx, new ArrayList<Commitment>());
    }

    @After public void tearDown() throws Exception {
        if (scenario != null) scenario.close();
        BalanceData.writeCommitments(ctx, originalCommitments);
        LocaleHelper.setLanguage(ctx, originalLanguage);
        CurrencyHelper.setCurrency(ctx, originalCurrency);
        RegionHelper.setRegion(ctx, originalRegion);
    }

    @Test public void editor_pausedAway_reopensWithTypedInput() throws Exception {
        scenario = ActivityScenario.launch(CommitmentsActivity.class);
        scenario.onActivity(activity -> {
            openEditor(activity, null);
            AlertDialog editor = dialogField(activity, "activeDialog");
            assertNotNull("the editor must open", editor);
            editTextByHint(editor, nameHint()).setText("Dentist");
            editTextByHint(editor, amountHint()).setText("150000");
        });

        // Screen off, Home, or the permission prompt: the pause dismisses the editor but must
        // inventory what was typed.
        scenario.moveToState(Lifecycle.State.STARTED);
        scenario.onActivity(activity -> {
            assertNull("the editor must be dismissed while paused",
                dialogField(activity, "activeDialog"));
            CommitmentsActivity.Draft draft = draftField(activity);
            assertNotNull("the pause must snapshot the typed input", draft);
            assertEquals("Dentist", draft.name);
            assertEquals("150000", draft.amount);
        });

        scenario.moveToState(Lifecycle.State.RESUMED);
        scenario.onActivity(activity -> {
            AlertDialog editor = dialogField(activity, "activeDialog");
            assertNotNull("the editor must reopen after the pause", editor);
            assertEquals("Dentist",
                editTextByHint(editor, nameHint()).getText().toString());
            assertEquals("150000",
                editTextByHint(editor, amountHint()).getText().toString());
        });
    }

    @Test public void editor_cancelledAfterTyping_neverReopens() throws Exception {
        scenario = ActivityScenario.launch(CommitmentsActivity.class);
        scenario.onActivity(activity -> {
            openEditor(activity, null);
            AlertDialog editor = dialogField(activity, "activeDialog");
            assertNotNull("the editor must open", editor);
            editTextByHint(editor, nameHint()).setText("Abandoned");
        });
        // The custom click listeners are installed in onShow, which the dialog posts; click
        // from a later main-thread hop that it is guaranteed to precede.
        postClick(AlertDialog.BUTTON_NEGATIVE);
        // Button taps dismiss on the next main-loop turn; the pause below must see the dismissal,
        // or it would snapshot a form the user just discarded.
        awaitNoDialog();

        scenario.moveToState(Lifecycle.State.STARTED);
        scenario.onActivity(activity -> assertNull("a cancelled form leaves no draft",
            draftField(activity)));
        scenario.moveToState(Lifecycle.State.RESUMED);
        scenario.onActivity(activity -> assertNull("a cancelled form must not reopen",
            dialogField(activity, "activeDialog")));
    }

    @Test public void editor_seededDraft_savesEveryField() throws Exception {
        CommitmentsActivity.Draft seed = new CommitmentsActivity.Draft(null, "Gym", "200000",
            false, Commitment.WEEKLY, new String[]{"2026", "3", "2"}, false,
            new String[]{"2026", "9", "1"}, true, "2", 1);
        scenario = ActivityScenario.launch(CommitmentsActivity.class);
        scenario.onActivity(activity -> {
            openEditor(activity, seed);
            AlertDialog editor = dialogField(activity, "activeDialog");
            assertNotNull("the seeded editor must open", editor);
            assertEquals("Gym", editTextByHint(editor, nameHint()).getText().toString());
            assertEquals("200000", editTextByHint(editor, amountHint()).getText().toString());
            assertTrue("a seeded valid form must be savable",
                editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        });
        // Same onShow ordering as above: the save listener is only installed once the
        // posted onShow has run.
        postClick(AlertDialog.BUTTON_POSITIVE);
        awaitNoDialog();

        List<Commitment> saved = BalanceData.readCommitments(ctx);
        assertEquals(1, saved.size());
        Commitment c = saved.get(0);
        assertEquals("Gym", c.name);
        assertEquals(200_000L, c.amount);
        assertEquals(Commitment.WEEKLY, c.frequency);
        assertEquals("[2026, 3, 2]",
            java.util.Arrays.toString(Commitment.civilDay(c.start, CalendarSystem.GREGORIAN)));
        assertEquals("[2026, 9, 1]",
            java.util.Arrays.toString(Commitment.civilDay(c.end, CalendarSystem.GREGORIAN)));
        assertTrue(c.remind);
        assertEquals(2L * 3_600_000L, c.remindBeforeMs);

        // A saved form is done: a later pause must not resurrect it.
        scenario.moveToState(Lifecycle.State.STARTED);
        scenario.onActivity(activity -> assertNull("a saved form leaves no draft",
            draftField(activity)));
        scenario.moveToState(Lifecycle.State.RESUMED);
        scenario.onActivity(activity -> assertNull("a saved form must not reopen",
            dialogField(activity, "activeDialog")));
    }

    private void openEditor(CommitmentsActivity activity, CommitmentsActivity.Draft seed) {
        try {
            if (seed == null) {
                Method open = CommitmentsActivity.class.getDeclaredMethod(
                    "editorDialog", String.class);
                open.setAccessible(true);
                open.invoke(activity, (String) null);
            } else {
                Method open = CommitmentsActivity.class.getDeclaredMethod(
                    "editorDialog", String.class, CommitmentsActivity.Draft.class);
                open.setAccessible(true);
                open.invoke(activity, null, seed);
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String nameHint() {
        return LocaleHelper.wrap(ctx).getString(R.string.commitments_name_hint);
    }

    private String amountHint() {
        return LocaleHelper.wrap(ctx).getString(
            R.string.commitments_amount_hint, CurrencyHelper.label(ctx));
    }

    private static EditText editTextByHint(AlertDialog dialog, String hint) {
        List<EditText> out = new ArrayList<>();
        collectEdits(dialog.getWindow().getDecorView(), out);
        for (EditText e : out) {
            if (hint.equals(String.valueOf(e.getHint()))) return e;
        }
        throw new AssertionError("no editor field with hint <" + hint + ">");
    }

    private static void collectEdits(View v, List<EditText> out) {
        if (v instanceof EditText) out.add((EditText) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectEdits(g.getChildAt(i), out);
        }
    }

    /** Clicks the open editor's button from a fresh main-thread hop. The dialog posts its onShow
     *  (which installs the editor's own click listeners) when shown, so clicking synchronously
     *  after opening would hit the default dismiss instead of save. Call from the test thread. */
    private static void postClick(final int which) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (!(a instanceof CommitmentsActivity)) continue;
                AlertDialog dialog = dialogField((CommitmentsActivity) a, "activeDialog");
                if (dialog != null) dialog.getButton(which).performClick();
            }
        });
    }

    /** Button taps dismiss on the next main-loop turn; join that before pausing, or the pause
     *  would snapshot a form the tap just discarded. */
    private static void awaitNoDialog() {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            final boolean[] gone = {false};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                boolean any = false;
                for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED)) {
                    if (!(a instanceof CommitmentsActivity)) continue;
                    any = true;
                    if (dialogField((CommitmentsActivity) a, "activeDialog") == null)
                        gone[0] = true;
                }
                if (!any) gone[0] = true;
            });
            if (gone[0]) return;
            try { Thread.sleep(100); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("the editor dialog did not dismiss");
    }

    private static AlertDialog dialogField(CommitmentsActivity activity, String name) {
        try {
            Field field = CommitmentsActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return (AlertDialog) field.get(activity);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static CommitmentsActivity.Draft draftField(CommitmentsActivity activity) {
        try {
            Field field = CommitmentsActivity.class.getDeclaredField("pendingEditorDraft");
            field.setAccessible(true);
            return (CommitmentsActivity.Draft) field.get(activity);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
