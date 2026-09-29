package com.ashkanrafiee.balance;

import static org.junit.Assert.assertFalse;
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
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The history screen's figures when an account holds more than one currency. Rials and dollars are
 * not summable, so every summary and every group header must show one figure per currency with its
 * own code — and, above all, must never show the sum of the two, which is the one number here that
 * no bank ever stated.
 */
@RunWith(AndroidJUnit4.class)
public class HistoryMixedCurrencyUiTest {

    private static final long RIALS = 4_000_000L;
    private static final long CENTS = 250_000L;

    private Context ctx;
    private String originalCurrency;

    @Before public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalCurrency = CurrencyHelper.currency(ctx);
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CURRENCY_TOMAN);
        // A lock screen left standing by anything else would sit on top of the history and its own
        // keypad text would be read as the history's, so a test about figures starts without one.
        LockManager.disable(ctx);
        finishEverything();
    }

    @After public void tearDown() {
        CurrencyHelper.setCurrency(ctx, originalCurrency);
        finishEverything();
    }

    @Test public void aTwoCurrencyHistory_showsEachFigureWithItsOwnCodeAndNeverTheirSum() throws Exception {
        long now = todayNoon();
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Saman", null, now, RIALS, null, "a", "a"));
        txs.add(new Transaction("Saman", null, now, CENTS, null, "b", "b", "USD"));
        FinancialTestStore.writeTransactions(ctx, txs);

        String rialFigure = signed(CurrencyHelper.amount(ctx, "IRR", RIALS));
        String centFigure = signed(CurrencyHelper.amount(ctx, "USD", CENTS));
        List<String> texts = launchAndReadTexts(rialFigure, centFigure);
        assertTrue("the rial figure must be on screen, got " + texts,
            texts.stream().anyMatch(t -> t.contains(rialFigure)));
        assertTrue("the dollar figure must be on screen, got " + texts,
            texts.stream().anyMatch(t -> t.contains(centFigure)));
        // Both codes are named, so a reader can tell whose money each line counts.
        assertTrue("the rial's own code must label its line, got " + texts,
            texts.stream().anyMatch(t -> t.contains("IRR")));
        assertTrue("the foreign code must label its line, got " + texts,
            texts.stream().anyMatch(t -> t.contains("USD")));

        // The figure that must never appear: the two added together, in either currency.
        String mergedRials = signed(CurrencyHelper.amount(ctx, "IRR", RIALS + CENTS));
        String mergedDollars = signed(CurrencyHelper.amount(ctx, "USD", RIALS + CENTS));
        assertFalse("a rial and a dollar must never be summed into one rial figure, got " + texts,
            texts.stream().anyMatch(t -> t.contains(mergedRials)));
        assertFalse("nor into one dollar figure, got " + texts,
            texts.stream().anyMatch(t -> t.contains(mergedDollars)));
    }

    @Test public void aTwoCurrencyYearHeader_speaksEachFigureWithItsCode() throws Exception {
        long now = todayNoon();
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Saman", null, now, RIALS, null, "a", "a"));
        txs.add(new Transaction("Saman", null, now, CENTS, null, "b", "b", "USD"));
        FinancialTestStore.writeTransactions(ctx, txs);

        String rialFigure = signed(CurrencyHelper.amount(ctx, "IRR", RIALS));
        String centFigure = signed(CurrencyHelper.amount(ctx, "USD", CENTS));
        List<String> spoken = launchAndReadContentDescriptions(rialFigure, centFigure);
        assertTrue("the year header must speak the rial figure, got " + spoken,
            spoken.stream().anyMatch(t -> t.contains(rialFigure)));
        assertTrue("the year header must speak the dollar figure, got " + spoken,
            spoken.stream().anyMatch(t -> t.contains(centFigure)));
        assertTrue("and must name the foreign code, got " + spoken,
            spoken.stream().anyMatch(t -> t.contains("USD")));
    }

    @Test public void aOneCurrencyHistory_stillShowsTheSingleNetItAlwaysDid() throws Exception {
        long now = todayNoon();
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("Saman", null, now, RIALS, null, "a", "a"));
        txs.add(new Transaction("Saman", null, now, 1_000_000L, null, "b", "b"));
        FinancialTestStore.writeTransactions(ctx, txs);

        String net = signed(CurrencyHelper.amount(ctx, "IRR", RIALS + 1_000_000L));
        List<String> texts = launchAndReadTexts(net);
        assertTrue("a single-currency history keeps its one net figure, got " + texts,
            texts.stream().anyMatch(t -> t.contains(net)));
        // Nothing is labelled, because there is nothing to tell apart.
        assertFalse("a single-currency history has no reason to label its figure, got " + texts,
            texts.stream().anyMatch(t -> t.contains("IRR")));
    }

    /** The signed form the screen draws: a leading plus, or the true minus sign. */
    private String signed(String magnitude) {
        return "+" + magnitude;
    }

    private long todayNoon() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 12);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private List<String> launchAndReadTexts(String... mustContain) throws Exception {
        return collect(false, mustContain);
    }

    private List<String> launchAndReadContentDescriptions(String... mustContain) throws Exception {
        return collect(true, mustContain);
    }

    /** Launches the screen and reads it back once it holds {@code mustContain}, so an assertion
     *  never runs against a half-drawn list or a leftover screen. */
    private List<String> collect(boolean descriptions, String... mustContain) throws Exception {
        Intent i = new Intent(ctx, HistoryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        InstrumentationRegistry.getInstrumentation().startActivitySync(i);
        // The header is built before the history list arrives, so waiting for the export action
        // proves the screen is up, and waiting for the figure proves the list has been drawn into
        // it. Anything read before that would be the skeleton or a previous test's leftovers.
        long deadline = System.currentTimeMillis() + 25_000;
        List<String> out = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            if (headerDrawn()) {
                out = readOnMain(descriptions);
                if (holds(out, mustContain)) return out;
            }
            Thread.sleep(150);
        }
        return out;
    }

    private static boolean holds(List<String> texts, String... needles) {
        for (String needle : needles)
            if (texts.stream().noneMatch(t -> t.contains(needle))) return false;
        return true;
    }

    private boolean headerDrawn() {
        AtomicReference<Boolean> hit = new AtomicReference<>(Boolean.FALSE);
        String want = ctx.getString(R.string.history_export);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (!(a instanceof HistoryActivity)) continue;
                if (hasExportAction(a.getWindow().getDecorView(), want)) hit.set(Boolean.TRUE);
            }
        });
        return Boolean.TRUE.equals(hit.get());
    }

    private static boolean hasExportAction(View v, String want) {
        if (want.equals(v.getContentDescription())) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++)
                if (hasExportAction(g.getChildAt(i), want)) return true;
        }
        return false;
    }

    private List<String> readOnMain(boolean descriptions) {
        AtomicReference<List<String>> out = new AtomicReference<>(new ArrayList<>());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            List<String> found = new ArrayList<>();
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (!(a instanceof HistoryActivity)) continue;
                walk(a.getWindow().getDecorView(), found, descriptions);
            }
            out.set(found);
        });
        return out.get();
    }

    /** Collects the drawn text, or every spoken description — the header's summary lives on the
     *  header's own container, not on any one of its children. */
    private void walk(View v, List<String> into, boolean descriptions) {
        CharSequence s = descriptions ? v.getContentDescription()
            : (v instanceof TextView ? ((TextView) v).getText() : null);
        if (s != null && s.length() > 0) into.add(s.toString());
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), into, descriptions);
        }
    }

    private void finishEverything() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Stage s : new Stage[]{Stage.RESUMED, Stage.PAUSED, Stage.STOPPED,
                    Stage.CREATED, Stage.STARTED}) {
                for (Activity a : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(s)) {
                    try { a.finish(); } catch (Exception ignored) { }
                }
            }
        });
    }
}
