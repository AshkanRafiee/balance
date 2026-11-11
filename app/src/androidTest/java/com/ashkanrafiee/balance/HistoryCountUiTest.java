package com.ashkanrafiee.balance;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

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
    private ActivityScenario<HistoryActivity> scenario;

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
        try {
            if (scenario != null) scenario.close();
        } finally {
            LocaleHelper.setLanguage(ctx, originalTag);
            CurrencyHelper.setCurrency(ctx, originalCurrency);
            BalanceData.reset(ctx, true);
        }
    }

    /** Three movements on one account and five on another, spanning five days. */
    private void storeTwoAccounts() {
        List<Transaction> txs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            txs.add(new Transaction(MELLAT, ONE, BASE - i * DAY, 10_000_000L + i, "a" + i, null));
        }
        for (int i = 0; i < 5; i++) {
            txs.add(new Transaction(MELLAT, TWO, BASE - i * DAY, 20_000_000L + i, "b" + i, null));
        }
        assertTrue(BalanceData.writeTransactions(ctx, txs));
    }

    private void launch(String account) {
        Intent i = new Intent(ctx, HistoryActivity.class);
        if (account != null) {
            i.putExtra(HistoryActivity.EXTRA_BANK, MELLAT)
             .putExtra(HistoryActivity.EXTRA_ACCOUNT, account);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        scenario = ActivityScenario.launch(i);
        // The export button belongs to the synchronous header, not the asynchronous result.
        // Require the accepted breakdown, but leave its actual count to each assertion below.
        await(() -> {
            boolean[] ready = {false};
            scenario.onActivity(activity -> ready[0] = HistoryActivity.idleForTest(activity)
                && find(activity.getWindow().getDecorView(), view -> view instanceof TextView
                    && ctx.getString(R.string.history_breakdown)
                        .contentEquals(((TextView) view).getText())) != null);
            return ready[0];
        }, 20_000);
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
        assertTrue(BalanceData.writeTransactions(ctx, new ArrayList<>(Arrays.asList(
            new Transaction(MELLAT, ONE, BASE, 10_000_000L, "only", null)))));
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
        final TextView[] found = {null};
        scenario.onActivity(activity -> {
            List<TextView> labels = new ArrayList<>();
            collect(activity.getWindow().getDecorView(), labels);
            for (TextView label : labels) {
                if (!activity.getString(R.string.history_breakdown).equals(label.getText().toString())) continue;
                ViewParent row = label.getParent();
                if (!(row instanceof ViewGroup)) continue;
                List<TextView> inRow = new ArrayList<>();
                collect((ViewGroup) row, inRow);
                for (TextView text : inRow) {
                    if (want.equals(text.getText().toString())) {
                        found[0] = text;
                        return;
                    }
                }
            }
        });
        return found[0];
    }

    private static void collect(View v, List<TextView> out) {
        if (v instanceof TextView) out.add((TextView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
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
        throw new AssertionError("timed out waiting for the accepted history breakdown");
    }
}
