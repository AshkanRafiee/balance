package com.ashkanrafiee.balance;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
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
    private static final long DAY = 86400000L;

    /** A fixed "now" the stored movements sit relative to, so the screen's today/month/year sums and
     *  its day groups do not depend on the day the test happens to run. */
    private static final long BASE = 1788000000000L;

    @Before public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalTag = LocaleHelper.currentTag(ctx);
        originalCurrency = CurrencyHelper.currency(ctx);
        // Pin the language and unit so the amounts on screen are the ones written out below.
        LocaleHelper.setLanguage(ctx, "en");
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CURRENCY_RIAL);
    }

    @After public void tearDown() {
        if (scenario != null) scenario.close();
        LocaleHelper.setLanguage(ctx, originalTag);
        CurrencyHelper.setCurrency(ctx, originalCurrency);
        BalanceData.reset(ctx, true);
    }

    private androidx.test.core.app.ActivityScenario<HistoryActivity> scenario;

    /** Two statements whose balances prove a 2,000,000 rial withdrawal that never arrived. */
    private void storeAGap() {
        BalanceData.setExpandAllHistory(ctx, true);
        BalanceData.writeTransactions(ctx, new ArrayList<>(Arrays.asList(
            new Transaction(MELLAT, ACCOUNT, BASE - 4 * DAY, -30_000_000L, 290_000_000L, "a", null),
            new Transaction(MELLAT, ACCOUNT, BASE - 2 * DAY, -5_000_000L, 283_000_000L, "b", null))));
    }

    /** Statements that agree with the movements we hold, so nothing should be flagged. */
    private void storeNoGap() {
        BalanceData.setExpandAllHistory(ctx, true);
        BalanceData.writeTransactions(ctx, new ArrayList<>(Arrays.asList(
            new Transaction(MELLAT, ACCOUNT, BASE - 4 * DAY, -30_000_000L, 290_000_000L, "a", null),
            new Transaction(MELLAT, ACCOUNT, BASE - 2 * DAY, -7_000_000L, 283_000_000L, "b", null))));
    }

    private void launch() {
        Intent i = new Intent(ctx, HistoryActivity.class)
            .putExtra(HistoryActivity.EXTRA_BANK, MELLAT)
            .putExtra(HistoryActivity.EXTRA_ACCOUNT, ACCOUNT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        InstrumentationRegistry.getInstrumentation().startActivitySync(i);
        // The screen builds itself off the main thread, so wait for the export action, which only
        // exists once the history header is in place.
        await(() -> findByDescription(ctx.getString(R.string.history_export)) != null, 20_000);
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
        assertTrue("the row must be reachable by touch", row.isClickable());
    }

    @Test public void aDetectedGap_showsTheExplanationsButton() {
        // The breakdown heading carries a way into the longer explanation, and it only appears while
        // there is something to explain.
        storeAGap();
        launch();
        assertNotNull(findByDescription(ctx.getString(R.string.residual_explainer_cd)));
    }

    @Test public void aDetectedGap_tappingTheRow_survivesBuildingTheArithmetic() {
        // The dialog is the one place the app does the subtraction in front of the user, so a wrong
        // format argument would fail exactly there. Clicking for real is the only way to cover it.
        storeAGap();
        launch();
        final View row = findByDescriptionContaining("Unaccounted");
        assertNotNull(row);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> row.performClick());
        // The screen is still alive and the row is still there behind the dialog.
        await(() -> findByDescriptionContaining("Unaccounted") != null, 10_000);
    }

    @Test public void aDetectedGap_tappingTheExplanationsButton_survivesBuildingIt() {
        storeAGap();
        launch();
        final View ask = findByDescription(ctx.getString(R.string.residual_explainer_cd));
        assertNotNull(ask);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> ask.performClick());
        await(() -> findByDescription(ctx.getString(R.string.residual_explainer_cd)) != null, 10_000);
    }

    // -----------------------------------------------------------------------
    // Silence when the books agree
    // -----------------------------------------------------------------------

    @Test public void noGap_rendersNoUnaccountedRow() {
        storeNoGap();
        launch();
        assertTrue("a history that adds up must stay quiet",
            findByText(ctx.getString(R.string.residual_label)) == null);
    }

    @Test public void noGap_showsNoExplanationsButton() {
        // The button's absence is itself the reassurance, so it must not linger once a delayed
        // message has closed the last gap.
        storeNoGap();
        launch();
        assertTrue(findByDescription(ctx.getString(R.string.residual_explainer_cd)) == null);
    }

    @Test public void noGap_stillShowsTheMovementsThemselves() {
        storeNoGap();
        launch();
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

    /** Every TextView under the resumed activity's decor view, read on the main thread. */
    private static List<TextView> texts() {
        final List<TextView> out = new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : resumed()) collect(a.getWindow().getDecorView(), out);
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

    private static TextView findByText(String want) {
        for (TextView t : texts()) if (want.equals(t.getText().toString())) return t;
        return null;
    }

    private static TextView findByTextContaining(String want) {
        for (TextView t : texts()) {
            CharSequence s = t.getText();
            if (s != null && s.toString().contains(want)) return t;
        }
        return null;
    }

    private static View findByDescription(String want) {
        return findByDescription(v -> want.equals(v.getContentDescription()));
    }

    private static View findByDescriptionContaining(String want) {
        return findByDescription(v -> {
            CharSequence d = v.getContentDescription();
            return d != null && d.toString().contains(want);
        });
    }

    private interface Match { boolean ok(View v); }

    private static View findByDescription(Match m) {
        final List<View> hits = new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : resumed()) {
                View hit = find(a.getWindow().getDecorView(), m);
                if (hit != null) hits.add(hit);
            }
        });
        return hits.isEmpty() ? null : hits.get(0);
    }

    private static List<Activity> resumed() {
        List<Activity> out = new ArrayList<>();
        for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)) {
            if (a instanceof HistoryActivity) out.add(a);
        }
        return out;
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
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try { if (done.call()) return; } catch (Exception ignored) { }
            try { Thread.sleep(150); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("timed out waiting for the history screen");
    }
}
