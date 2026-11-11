package com.ashkanrafiee.balance;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * The unaccounted-money rows as the user actually meets them: that the screen builds them, that they
 * say something a person can act on, and that tapping them does not fail. The numbers behind these
 * rows are covered by {@link HistoryResidualTest}; what is only reachable by rendering the screen is
 * covered here.
 */
@RunWith(AndroidJUnit4.class)
public class HistoryResidualUiTest {

    private Context ctx;
    private String originalTag;
    private String originalCurrency;

    private static final String MELLAT = "Mellat";
    private static final String ACCOUNT = "111";
    /** The two statements sit inside the current day, because that is the day the
     *  screen opens by itself: the unaccounted row lives inside an open day, and a stored day in some
     *  other month would need expanding first. Noon keeps both statements on the same civil day
     *  even when this fixture runs just after midnight or at a calendar boundary. */
    private static long todayNoon() {
        Calendar date = Calendar.getInstance();
        date.set(Calendar.HOUR_OF_DAY, 12);
        date.set(Calendar.MINUTE, 0);
        date.set(Calendar.SECOND, 0);
        date.set(Calendar.MILLISECOND, 0);
        return date.getTimeInMillis();
    }

    @Before public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalTag = LocaleHelper.currentTag(ctx);
        originalCurrency = CurrencyHelper.currency(ctx);
        // Pin the language and unit so the amounts on screen are the ones written out below.
        LocaleHelper.setLanguage(ctx, "en");
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CURRENCY_RIAL);
    }

    @After public void tearDown() {
        try {
            if (scenario != null) scenario.close();
        } finally {
            LocaleHelper.setLanguage(ctx, originalTag);
            CurrencyHelper.setCurrency(ctx, originalCurrency);
            BalanceData.reset(ctx, true);
        }
    }

    private androidx.test.core.app.ActivityScenario<HistoryActivity> scenario;

    /** Two statements whose balances prove a 2,000,000 rial withdrawal that never arrived. */
    private void storeAGap() {
        long base = todayNoon();
        assertTrue(BalanceData.writeTransactions(ctx, new ArrayList<>(Arrays.asList(
            new Transaction(MELLAT, ACCOUNT, base - 2_000L, -30_000_000L, 290_000_000L, "a", null),
            new Transaction(MELLAT, ACCOUNT, base - 1_000L, -5_000_000L, 283_000_000L, "b", null)))));
    }

    /** Statements that agree with the movements we hold, so nothing should be flagged. */
    private void storeNoGap() {
        long base = todayNoon();
        assertTrue(BalanceData.writeTransactions(ctx, new ArrayList<>(Arrays.asList(
            new Transaction(MELLAT, ACCOUNT, base - 2_000L, -30_000_000L, 290_000_000L, "a", null),
            new Transaction(MELLAT, ACCOUNT, base - 1_000L, -7_000_000L, 283_000_000L, "b", null)))));
    }

    private void launch() {
        Intent i = new Intent(ctx, HistoryActivity.class)
            .putExtra(HistoryActivity.EXTRA_BANK, MELLAT)
            .putExtra(HistoryActivity.EXTRA_ACCOUNT, ACCOUNT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        scenario = androidx.test.core.app.ActivityScenario.launch(i);
        awaitMovementRows();
    }

    private void awaitMovementRows() {
        // The export header is synchronous and repeats this account label. Count only inside
        // the scroll container, where the two labels belong to actual movement rows, so neither
        // a header nor an empty cold body can satisfy an absence assertion's readiness check.
        await(() -> {
            boolean[] ready = {false};
            scenario.onActivity(activity -> {
                View content = find(activity.getWindow().getDecorView(),
                    view -> view instanceof ScrollView);
                if (content == null) return;
                List<TextView> labels = new ArrayList<>();
                collect(content, labels);
                String accountLabel = activity.getString(R.string.account_label) + " " + ACCOUNT;
                int movements = 0;
                for (TextView label : labels) {
                    if (accountLabel.equals(Digits.ascii(label.getText().toString()))) movements++;
                }
                ready[0] = HistoryActivity.idleForTest(activity) && movements == 2;
            });
            return ready[0];
        }, 20_000);
    }

    // -----------------------------------------------------------------------
    // The amber row
    // -----------------------------------------------------------------------

    @Test public void aDetectedGap_rendersAnUnaccountedRow() {
        storeAGap();
        launch();
        assertNotNull("the unaccounted label must reach the screen",
            findByText(ctx.getString(R.string.residual_label)));
        // The amount is shown with the same typographic minus every other movement uses, so the row
        // reads as one more line of the same list rather than as something foreign.
        assertNotNull("the gap's amount must be shown", findByTextContaining("2,000,000"));
        assertNotNull("the row's sign must match the movements'",
            findByTextContaining("\u22122,000,000"));
    }

    @Test public void aDetectedGap_explainsItselfInWordsOnTheRow() {
        // A bare number the user cannot interpret is worse than no number at all, so the row carries
        // the reason in plain language rather than leaving it entirely to the dialog.
        storeAGap();
        launch();
        assertNotNull(findByText(ctx.getString(R.string.residual_row_hint)));
    }

    @Test public void aDetectedGap_rowCarriesAnAccessibleDescription() {
        // The row is a button, so a screen reader has to be told what it is and why it is there; the
        // visible hint alone is not what gets announced.
        storeAGap();
        launch();
        View row = findByDescriptionContaining("Unaccounted");
        assertNotNull("the row needs a spoken description", row);
        scenario.onActivity(activity ->
            assertTrue("the row must be reachable by touch", row.isClickable()));
    }

    @Test public void aDetectedGap_showsTheExplanationsButton() {
        // The breakdown heading carries a way into the longer explanation, and it only appears while
        // there is something to explain.
        storeAGap();
        launch();
        assertNotNull(findByDescription(ctx.getString(R.string.residual_explainer_cd)));
    }

    @Test public void englishHistory_showsLatinQuestionMark() {
        // English keeps the Latin mark: both amber badges (the heading button and the row) are
        // exactly "?", and no Persian mark reaches the screen. The rows render after the header,
        // so wait for the mark instead of asserting on a half-built list.
        storeAGap();
        launch();
        await(() -> findByText("?") != null, 20_000);
        assertNotNull(findByText("?"));
        assertTrue(findByText("؟") == null);
    }

    @Test public void persianHistory_showsPersianQuestionMark() {
        // Only the breakdown heading button uses U+061F ARABIC QUESTION MARK, which mirrors
        // correctly in the RTL layout; the unaccounted row badges keep the Latin mark as before.
        LocaleHelper.setLanguage(ctx, "fa");
        storeAGap();
        Intent i = new Intent(ctx, HistoryActivity.class)
            .putExtra(HistoryActivity.EXTRA_BANK, MELLAT)
            .putExtra(HistoryActivity.EXTRA_ACCOUNT, ACCOUNT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        scenario = androidx.test.core.app.ActivityScenario.launch(i);
        awaitMovementRows();
        await(() -> findByText("؟") != null, 20_000);
        await(() -> findByText("?") != null, 20_000);
        TextView headingAsk = findByText("؟");
        assertNotNull(headingAsk);
        scenario.onActivity(activity -> assertTrue("the Persian mark must be the heading button",
            headingAsk.isClickable() && headingAsk.getContentDescription() != null));
        assertNotNull("the row badges keep the Latin mark", findByText("?"));
    }

    @Test public void aDetectedGap_tappingTheRow_survivesBuildingTheArithmetic() {
        // The dialog is the one place the app does the subtraction in front of the user, so a wrong
        // format argument would fail exactly there. Clicking for real is the only way to cover it.
        storeAGap();
        launch();
        AlertDialog dialog = openExplanation(v -> {
            CharSequence description = v.getContentDescription();
            return description != null && description.toString().contains("Unaccounted");
        });
        try {
            await(() -> dialogContains(dialog, ctx.getString(R.string.figure_calculation,
                "283,000,000", "290,000,000", "-5,000,000", "-2,000,000")), 10_000);
            assertTrue(dialogContains(dialog, ctx.getString(R.string.figure_title)));
            assertTrue(dialogContains(dialog, ctx.getString(R.string.figure_residual_caution)));
            assertTrue(dialogContains(dialog, ctx.getString(R.string.account_label) + " " + ACCOUNT));
        } finally {
            dismissExplanation(dialog);
        }
    }

    @Test public void aDetectedGap_showsItsSavedNoteWithoutChangingAmberMeaning() {
        storeAGap();
        Residual gap = Residual.between(BalanceData.readTransactions(ctx)).get(0);
        BalanceData.setNote(ctx, gap, "cash withdrawal to check");
        launch();

        assertNotNull("the saved residual note must be visible",
            findByText("cash withdrawal to check"));
        View row = findByDescriptionContaining("cash withdrawal to check");
        assertNotNull("the amber row must announce its note", row);
        scenario.onActivity(activity ->
            assertTrue("the row remains the explanation action", row.isClickable()));
    }

    @Test public void aDetectedGap_tappingTheExplanationsButton_survivesBuildingIt() {
        storeAGap();
        launch();
        AlertDialog dialog = openExplanation(v ->
            ctx.getString(R.string.residual_explainer_cd).contentEquals(
                v.getContentDescription() == null ? "" : v.getContentDescription()));
        try {
            assertTrue(dialogContains(dialog, ctx.getString(R.string.residual_explainer_title)));
            assertTrue(dialogContains(dialog, ctx.getString(R.string.residual_explainer_body)));
            assertTrue(dialogContains(dialog, "Unaccounted amounts: 1"));
            assertTrue(dialogContains(dialog, ACCOUNT));
            assertTrue(dialogContains(dialog, "\u22122,000,000"));
        } finally {
            dismissExplanation(dialog);
        }
    }

    // -----------------------------------------------------------------------
    // Silence when the books agree
    // -----------------------------------------------------------------------

    @Test public void noGap_keepsMovementsWithoutUnaccountedRowOrExplanationsButton() {
        storeNoGap();
        launch();
        assertTrue("a history that adds up must stay quiet",
            findByText(ctx.getString(R.string.residual_label)) == null);
        // The button's absence is itself the reassurance, so it must not linger once a delayed
        // message has closed the last gap.
        assertTrue(findByDescription(ctx.getString(R.string.residual_explainer_cd)) == null);
        assertNotNull("the movements must still be listed", findByTextContaining("7,000,000"));
    }

    // -----------------------------------------------------------------------
    // The explanation texts
    // -----------------------------------------------------------------------

    @Test public void theExplanationTexts_formatWithTheArgumentsTheScreenPasses() {
        // Both bodies are built with format arguments chosen at the call site, and a mismatch there
        // would throw only when a user happened to tap the row. Formatting the same way here pins
        // the contract down, and the output is checked for the numbers it promises to show.
        String detail = ctx.getString(R.string.residual_detail_body,
            "12 Shahrivar 1405", "14 Shahrivar 1405", "Mellat", "111");
        assertTrue(detail.contains("12 Shahrivar 1405"));
        assertTrue(detail.contains("14 Shahrivar 1405"));
        assertTrue(detail.contains("Mellat"));
        assertTrue(ctx.getString(R.string.residual_detail_title, "\u22122,000,000")
            .contains("2,000,000"));
        assertTrue(ctx.getString(R.string.residual_row_cd, "\u22122,000,000")
            .contains("2,000,000"));
        // The explainer takes no arguments, so it must not contain a stray placeholder.
        assertTrue(!ctx.getString(R.string.residual_explainer_body).contains("%"));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void collect(View v, List<TextView> out) {
        if (v instanceof TextView) out.add((TextView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    private TextView findByText(String want) {
        return (TextView) findByDescription(v -> v instanceof TextView
            && want.contentEquals(((TextView) v).getText()));
    }

    private TextView findByTextContaining(String want) {
        return (TextView) findByDescription(v -> v instanceof TextView
            && ((TextView) v).getText().toString().contains(want));
    }

    private View findByDescription(String want) {
        return findByDescription(v -> want.equals(v.getContentDescription()));
    }

    private View findByDescriptionContaining(String want) {
        return findByDescription(v -> {
            CharSequence d = v.getContentDescription();
            return d != null && d.toString().contains(want);
        });
    }

    private interface Match { boolean ok(View v); }

    private View findByDescription(Match m) {
        final View[] hit = {null};
        scenario.onActivity(activity -> hit[0] = find(activity.getWindow().getDecorView(), m));
        return hit[0];
    }

    private AlertDialog openExplanation(Match entry) {
        final AlertDialog[] opened = {null};
        scenario.onActivity(activity -> {
            List<AlertDialog> before = HistoryActivity.shownDialogsForTest(activity);
            assertTrue("no explanation should already be open", before.isEmpty());
            View action = find(activity.getWindow().getDecorView(), entry);
            assertNotNull("the explanation entry must exist", action);
            assertTrue("the explanation entry must handle its click", action.performClick());
            List<AlertDialog> after = HistoryActivity.shownDialogsForTest(activity);
            assertTrue("the click must show one new dialog", after.size() == 1);
            opened[0] = after.get(0);
            assertTrue("the new explanation must be showing", opened[0].isShowing());
        });
        return opened[0];
    }

    private boolean dialogContains(AlertDialog dialog, String expected) {
        final boolean[] contains = {false};
        scenario.onActivity(activity -> {
            assertTrue("the explanation must remain visible", dialog.isShowing());
            contains[0] = find(dialog.getWindow().getDecorView(), v -> v instanceof TextView
                && ((TextView) v).getText().toString().contains(expected)) != null;
        });
        return contains[0];
    }

    private void dismissExplanation(AlertDialog dialog) {
        scenario.onActivity(activity -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        await(() -> {
            final boolean[] dismissed = {false};
            scenario.onActivity(activity -> dismissed[0] = !dialog.isShowing()
                && !HistoryActivity.shownDialogsForTest(activity).contains(dialog));
            return dismissed[0];
        }, 10_000);
    }

    private static View find(View v, Match m) {
        if (m.ok(v)) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = find(g.getChildAt(i), m);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static void await(Callable<Boolean> done, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            try { if (done.call()) return; }
            catch (Exception e) { throw new AssertionError("history readiness check failed", e); }
            try { Thread.sleep(25); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("timed out waiting for the history screen");
    }
}
