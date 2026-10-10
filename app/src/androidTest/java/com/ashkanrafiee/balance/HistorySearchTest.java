package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/** The history search: normalization, all-fields matching and the on-screen narrowing. */
@RunWith(AndroidJUnit4.class)
public class HistorySearchTest {

    private Context ctx;
    private String originalTag;
    private String originalCurrency;

    private static final String MELLAT = "Mellat";
    private static final String ONE = "111";
    private static final long DAY = 86400000L;
    private static final long BASE = 1788000000000L;

    @Before public void setUp() {
        finishAnyResumedHistory();
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalTag = LocaleHelper.currentTag(ctx);
        originalCurrency = CurrencyHelper.currency(ctx);
        LocaleHelper.setLanguage(ctx, "en");
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CURRENCY_RIAL);
    }

    @After public void tearDown() {
        LocaleHelper.setLanguage(ctx, originalTag);
        CurrencyHelper.setCurrency(ctx, originalCurrency);
        BalanceData.reset(ctx, true);
    }

    // ---- normalization ------------------------------------------------------------

    @Test public void normalize_foldsPersianDigitsToLatin() {
        assertEquals("123", HistoryActivity.normalizeSearch("۱۲۳"));
    }

    @Test public void normalize_foldsArabicDigitsAndLetters() {
        assertEquals("123", HistoryActivity.normalizeSearch("١٢٣"));
        assertEquals("کتابخانه", HistoryActivity.normalizeSearch("كتابخانه"));
    }

    @Test public void normalize_ignoresCaseAndGroupingSeparators() {
        assertEquals(HistoryActivity.normalizeSearch("Saman"),
            HistoryActivity.normalizeSearch("saman"));
        assertEquals("1000000", HistoryActivity.normalizeSearch("1,000,000"));
        assertEquals("1000000", HistoryActivity.normalizeSearch("۱٬۰۰۰٬۰۰۰"));
    }

    @Test public void normalize_dropsTheHalfSpace() {
        assertEquals("میشود", HistoryActivity.normalizeSearch("می‌شود"));
    }

    // ---- matching -----------------------------------------------------------------

    @Test public void matches_partialWord() {
        assertTrue(HistoryActivity.matchesSearch("Saman bank", "sam"));
    }

    @Test public void matches_everyWordMustAppearSomewhere() {
        assertTrue(HistoryActivity.matchesSearch("groceries saman", "groceries saman"));
        assertFalse(HistoryActivity.matchesSearch("groceries saman", "groceries mellat"));
    }

    @Test public void matches_blankQueryKeepsEverything() {
        assertTrue(HistoryActivity.matchesSearch("anything", null));
        assertTrue(HistoryActivity.matchesSearch("anything", ""));
        assertTrue(HistoryActivity.matchesSearch("anything", "   "));
    }

    @Test public void matches_digitsHoweverTheyWereTyped() {
        assertTrue(HistoryActivity.matchesSearch("۵۰۰٬۰۰۰", "500"));
        assertTrue(HistoryActivity.matchesSearch("500,000", "۵۰۰"));
    }

    @Test public void matches_halfSpaceHoweverEitherSideSpacedIt() {
        assertTrue(HistoryActivity.matchesSearch("می شود", "میشود"));
        assertTrue(HistoryActivity.matchesSearch("میشود", "می شود"));
    }

    // ---- haystacks ----------------------------------------------------------------

    @Test public void transactionHaystack_coversEveryField() {
        Transaction t = new Transaction(MELLAT, ONE, BASE, 10_000_000L, null, "s1", "c1");
        String hay = HistoryActivity.transactionSearchText(t, "Mellat", "groceries",
            "topup", "Phone top-up", "pos", "Point-of-sale terminal",
            "10,000,000", "Deposit", "August 26 2026", "20:16", "August", "2026/8/26");
        assertTrue(HistoryActivity.matchesSearch(hay, "mellat"));
        assertTrue(HistoryActivity.matchesSearch(hay, "111"));
        assertTrue(HistoryActivity.matchesSearch(hay, "10000000"));
        assertTrue(HistoryActivity.matchesSearch(hay, "groceries"));
        assertTrue(HistoryActivity.matchesSearch(hay, "top-up"));
        assertTrue(HistoryActivity.matchesSearch(hay, "point-of-sale"));
        assertTrue(HistoryActivity.matchesSearch(hay, "august"));
        assertTrue(HistoryActivity.matchesSearch(hay, "20:16"));
        assertFalse(HistoryActivity.matchesSearch(hay, "salary"));
    }

    @Test public void residualHaystack_coversItsFields() {
        Residual r = new Residual(MELLAT, ONE, BASE - DAY, BASE, -5_000_000L, 0);
        String hay = HistoryActivity.residualSearchText(r, "Mellat", "5,000,000",
            "Withdrawal", "Unaccounted", "August 26 2026", "20:16", "August", "2026/8/26");
        assertTrue(HistoryActivity.matchesSearch(hay, "mellat"));
        assertTrue(HistoryActivity.matchesSearch(hay, "5000000"));
        assertTrue(HistoryActivity.matchesSearch(hay, "unaccounted"));
        assertFalse(HistoryActivity.matchesSearch(hay, "groceries"));
    }

    // ---- list filtering -------------------------------------------------------------

    @Test public void filterBySearch_keepsMatchesInOrder() {
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction(MELLAT, ONE, BASE, 1L, null));
        txs.add(new Transaction("Saman", ONE, BASE, 2L, null));
        txs.add(new Transaction(MELLAT, ONE, BASE, 3L, null));
        List<Transaction> out = HistoryActivity.filterBySearch(txs, "mel", t -> t.bank);
        assertEquals(2, out.size());
        assertSame(txs.get(0), out.get(0));
        assertSame(txs.get(2), out.get(1));
    }

    @Test public void filterBySearch_blankQueryKeepsAll() {
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction(MELLAT, ONE, BASE, 1L, null));
        assertEquals(1, HistoryActivity.filterBySearch(txs, "  ", t -> t.bank).size());
    }

    // ---- on screen ------------------------------------------------------------------

    /** Two movements a day apart, told apart only by their notes. */
    private void storeTwoNotes() {
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction(MELLAT, ONE, BASE, 10_000_000L, null, "s1", "c1"));
        txs.add(new Transaction(MELLAT, ONE, BASE - DAY, -20_000_000L, null, "s2", "c2"));
        BalanceData.writeTransactions(ctx, txs);
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("c:c1", "groceries");
        notes.put("c:c2", "salary");
        BalanceData.writeNotes(ctx, notes);
    }

    private void launch() {
        Intent i = new Intent(ctx, HistoryActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        InstrumentationRegistry.getInstrumentation().startActivitySync(i);
        await(() -> findByDescription(ctx.getString(R.string.history_export)) != null, 20_000);
    }

    @Test public void search_narrowsTheCountToMatches() {
        storeTwoNotes();
        launch();
        // The heading is built by the background render, after the header launch() waits for —
        // asserting it instantly races the worker. Every other on-screen assertion below waits.
        await(() -> findCountInHeading(counted(2)) != null, 20_000);
        assertNotNull(findCountInHeading(counted(2)));
        setSearch("groceries");
        await(() -> findCountInHeading(counted(1)) != null, 20_000);
    }

    @Test public void search_clearRestoresEverything() {
        storeTwoNotes();
        launch();
        setSearch("groceries");
        await(() -> findCountInHeading(counted(1)) != null, 20_000);
        tapClear();
        await(() -> findCountInHeading(counted(2)) != null, 20_000);
    }

    @Test public void search_noMatchSaysWhatItLookedFor() {
        storeTwoNotes();
        launch();
        setSearch("zzz-no-such-word");
        await(() -> findText(ctx.getString(
            R.string.history_empty_search, "zzz-no-such-word")) != null, 20_000);
    }

    private void setSearch(final String q) {
        // Found from the test thread (which hops onto the main thread internally), then written
        // from the main thread: looking the field up from inside a main-thread write deadlocks on
        // the framework's own not-on-app-thread check.
        EditText in = findSearchInput();
        assertNotNull("the search field must be on screen", in);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> in.setText(q));
    }

    private void tapClear() {
        final View clear = findByDescription(ctx.getString(R.string.history_search_clear));
        assertNotNull("the clear button must be on screen", clear);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(clear::performClick);
    }

    /** The heading's wording, as the platform pluralises it for this locale. */
    private String counted(int n) {
        return ctx.getResources().getQuantityString(R.plurals.history_n_tx, n, n);
    }

    private TextView findCountInHeading(String want) {
        for (TextView label : texts()) {
            if (!ctx.getString(R.string.history_breakdown).equals(label.getText().toString())) continue;
            android.view.ViewParent row = label.getParent();
            if (!(row instanceof ViewGroup)) continue;
            List<TextView> inRow = new ArrayList<>();
            collect((ViewGroup) row, inRow);
            for (TextView t : inRow) if (want.equals(t.getText().toString())) return t;
        }
        return null;
    }

    private static TextView findText(String want) {
        for (TextView t : texts()) if (want.equals(t.getText().toString())) return t;
        return null;
    }

    private EditText findSearchInput() {
        String hint = ctx.getString(R.string.history_search_hint);
        for (TextView t : texts()) {
            if (t instanceof EditText && hint.equals(String.valueOf(t.getHint()))) return (EditText) t;
        }
        return null;
    }

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

    private static View findByDescription(String want) {
        final List<View> hits = new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : resumed()) {
                View hit = find(a.getWindow().getDecorView(),
                    v -> want.equals(v.getContentDescription()));
                if (hit != null) hits.add(hit);
            }
        });
        return hits.isEmpty() ? null : hits.get(0);
    }

    private static View find(View v, java.util.function.Predicate<View> m) {
        if (m.test(v)) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = find(g.getChildAt(i), m);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static void finishAnyResumedHistory() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (a instanceof HistoryActivity) a.finish();
            }
        });
    }

    private static List<Activity> resumed() {
        List<Activity> out = new ArrayList<>();
        for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)) {
            if (a instanceof HistoryActivity) out.add(a);
        }
        return out;
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
