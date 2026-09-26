package com.ashkanrafiee.balance;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
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
 * The movement total above the breakdown. How much history there is was previously only knowable by
 * scrolling and counting, which matters because the cost of the breakdown below grows with it.
 */
@RunWith(AndroidJUnit4.class)
public class HistoryCountUiTest {

    private Context ctx;
    private String originalTag;
    private String originalCurrency;

    private static final String MELLAT = "Mellat";
    private static final String ONE = "111";
    private static final String TWO = "222";
    private static final long DAY = 86400000L;
    private static final long BASE = 1788000000000L;

    @Before public void setUp() {
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

    /** Three movements on one account and five on another, all inside the last two weeks. */
    private void storeTwoAccounts() {
        List<Transaction> txs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            txs.add(new Transaction(MELLAT, ONE, BASE - i * DAY, 10_000_000L + i, "a" + i, null));
        }
        for (int i = 0; i < 5; i++) {
            txs.add(new Transaction(MELLAT, TWO, BASE - i * DAY, 20_000_000L + i, "b" + i, null));
        }
        BalanceData.writeTransactions(ctx, txs);
    }

    private void launch(String account) {
        Intent i = new Intent(ctx, HistoryActivity.class);
        if (account != null) {
            i.putExtra(HistoryActivity.EXTRA_BANK, MELLAT)
             .putExtra(HistoryActivity.EXTRA_ACCOUNT, account);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        InstrumentationRegistry.getInstrumentation().startActivitySync(i);
        await(() -> findByDescription(ctx.getString(R.string.history_export)) != null, 20_000);
    }

    @Test public void theScreen_saysHowManyMovementsItIsShowing() {
        storeTwoAccounts();
        launch(ONE);
        assertNotNull("the count must be on screen, not only knowable by scrolling",
            findCountInHeading(counted(3)));
    }

    @Test public void theCount_followsTheAccountBeingViewed() {
        // The count has to describe what is on screen. A total taken from the whole store would read
        // "8 transactions" on an account that holds three, and quietly misreport every account the
        // user ever opens.
        storeTwoAccounts();
        launch(ONE);
        assertNotNull(findCountInHeading(counted(3)));
        assertNull("a single account must not claim the other account's movements",
            findCountInHeading(counted(8)));
    }

    @Test public void theCount_followsTheWholeHistoryWhenNothingIsFiltered() {
        storeTwoAccounts();
        launch(null);
        assertNotNull(findCountInHeading(counted(8)));
    }

    @Test public void theCount_saysTransactionInTheSingular() {
        BalanceData.writeTransactions(ctx, new ArrayList<>(Arrays.asList(
            new Transaction(MELLAT, ONE, BASE, 10_000_000L, "only", null))));
        launch(ONE);
        assertNotNull(findCountInHeading(counted(1)));
    }

    /** The heading's wording, as the platform pluralises it for this locale. */
    private String counted(int n) {
        return ctx.getResources().getQuantityString(R.plurals.history_n_tx, n, n);
    }

    /**
     * The count as it appears in the breakdown heading, looked for inside that heading only.
     *
     * <p>A month header also spells out "3 transactions", so searching the whole screen for the
     * wording passes even when the heading is wrong. These tests have to look at the heading.
     */
    private TextView findCountInHeading(String want) {
        for (TextView label : texts()) {
            if (!ctx.getString(R.string.history_breakdown).equals(label.getText().toString())) continue;
            ViewParent row = label.getParent();
            if (!(row instanceof ViewGroup)) continue;
            List<TextView> inRow = new ArrayList<>();
            collect((ViewGroup) row, inRow);
            for (TextView t : inRow) if (want.equals(t.getText().toString())) return t;
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
