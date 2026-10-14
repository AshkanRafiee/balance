package com.ashkanrafiee.balance;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
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

/** The bank/account picker as the user meets it: narrowing to banks, combining accounts by
 *  AND across banks, and clearing back to the full history. */
@RunWith(AndroidJUnit4.class)
public class HistoryBankFilterUiTest {

    private Context ctx;
    private String originalTag;
    private String originalCurrency;

    private static final String MELLAT = "Mellat";
    private static final String SAMAN = "Saman";

    private static long now() {
        return System.currentTimeMillis();
    }

    @Before public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalTag = LocaleHelper.currentTag(ctx);
        originalCurrency = CurrencyHelper.currency(ctx);
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

    /** One movement per account, so no unaccounted row complicates the assertions. */
    private void storeThree() {
        BalanceData.writeTransactions(ctx, new ArrayList<>(Arrays.asList(
            new Transaction(MELLAT, "111", now() - 3_000L, -30_000_000L, 290_000_000L, "a", null),
            new Transaction(MELLAT, "222", now() - 2_000L, -5_000_000L, 285_000_000L, "b", null),
            new Transaction(SAMAN, "333", now() - 1_000L, 900_000L, 999_000_000L, "c", null))));
    }

    private void launch() {
        Intent i = new Intent(ctx, HistoryActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        scenario = androidx.test.core.app.ActivityScenario.launch(i);
        await(() -> findByDescription(ctx.getString(R.string.history_export)) != null, 20_000);
    }

    private void click(View v) {
        assertNotNull(v);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(v::performClick);
    }

    @Test public void bankAndAccountPickers_narrowAndClear() {
        storeThree();
        launch();
        // The full history is on screen first.
        await(() -> findByTextContaining("30,000,000") != null, 20_000);
        await(() -> findByTextContaining("5,000,000") != null, 20_000);
        await(() -> findByTextContaining("900,000") != null, 20_000);

        // Narrow to Saman only: the ID-less bank chip is gone, the name stays.
        await(() -> findByText(ctx.getString(R.string.history_filter_banks)) != null, 20_000);
        click(findByText(ctx.getString(R.string.history_filter_banks)));
        await(() -> findInDialog(ctx.getString(R.string.history_filter_banks_title)) != null,
            10_000);
        checkInDialog(BankRules.displayName(ctx, SAMAN));
        click(findInDialog(ctx.getString(R.string.history_filter_apply)));
        // The filter bar's own count proves the narrowed render landed; only then is asserting
        // an absence meaningful rather than a race with the previous render.
        await(() -> findByText(ctx.getString(R.string.history_filter_banks_count, 1)) != null,
            20_000);
        assertNotNull(findByTextContaining("900,000"));
        assertTrue(findByTextContaining("30,000,000") == null);
        assertTrue(findByTextContaining("5,000,000") == null);
        assertNotNull("the header names the single selected bank",
            findByText(BankRules.displayName(ctx, SAMAN)));
        assertTrue("no ID-less bank chip", findByText("Bank") == null);

        // With Saman selected only its account is offered.
        click(findByText(ctx.getString(R.string.history_filter_accounts)));
        await(() -> findInDialog(ctx.getString(R.string.history_filter_accounts_title)) != null,
            10_000);
        await(() -> findCheckInDialog(
            BankRules.displayName(ctx, SAMAN) + " — 333") != null, 30_000);
        assertTrue("other banks' accounts are not offered",
            findCheckInDialog(BankRules.displayName(ctx, MELLAT) + " — 111") == null);
        checkInDialog(BankRules.displayName(ctx, SAMAN) + " — 333");
        click(findInDialog(ctx.getString(R.string.history_filter_apply)));
        await(() -> findByText(ctx.getString(R.string.history_filter_accounts_count, 1)) != null,
            20_000);
        assertNotNull(findByTextContaining("900,000"));

        // Switching banks prunes the stale account: Saman's 333 drops, Mellat opens whole.
        click(findByText(ctx.getString(R.string.history_filter_banks_count, 1)));
        await(() -> findInDialog(ctx.getString(R.string.history_filter_banks_title)) != null,
            10_000);
        await(() -> findCheckInDialog(BankRules.displayName(ctx, MELLAT)) != null, 30_000);
        uncheckInDialog(BankRules.displayName(ctx, SAMAN));
        checkInDialog(BankRules.displayName(ctx, MELLAT));
        click(findInDialog(ctx.getString(R.string.history_filter_apply)));
        await(() -> findByTextContaining("30,000,000") != null, 20_000);
        assertNotNull(findByTextContaining("5,000,000"));
        assertTrue(findByTextContaining("900,000") == null);
        assertTrue("the pruned account selection is gone",
            findByText(ctx.getString(R.string.history_filter_accounts)) != null
                && findByText(ctx.getString(R.string.history_filter_accounts_count, 1)) == null);

        // Clearing the banks returns the full history.
        click(findByText(ctx.getString(R.string.history_filter_banks_count, 1)));
        await(() -> findInDialog(ctx.getString(R.string.history_filter_banks_title)) != null,
            10_000);
        click(findInDialog(ctx.getString(R.string.history_filter_banks_clear)));
        await(() -> findByTextContaining("30,000,000") != null, 20_000);
        await(() -> findByTextContaining("5,000,000") != null, 20_000);

        // One account on its own matches it wherever it lives.
        click(findByText(ctx.getString(R.string.history_filter_accounts)));
        await(() -> findInDialog(ctx.getString(R.string.history_filter_accounts_title)) != null,
            10_000);
        await(() -> findCheckInDialog(
            BankRules.displayName(ctx, MELLAT) + " — 111") != null, 30_000);
        checkInDialog(BankRules.displayName(ctx, MELLAT) + " — 111");
        click(findInDialog(ctx.getString(R.string.history_filter_apply)));
        await(() -> findByText(ctx.getString(R.string.history_filter_accounts_count, 1)) != null,
            20_000);
        assertNotNull(findByTextContaining("30,000,000"));
        assertTrue(findByTextContaining("5,000,000") == null);
        assertTrue(findByTextContaining("900,000") == null);
        assertNotNull("the header names the single account's bank",
            findByText(BankRules.displayName(ctx, MELLAT)));
        assertNotNull("the header carries the account chip",
            findByText(ctx.getString(R.string.account_label) + " 111"));

        // A second account from another bank joins the scope.
        click(findByText(ctx.getString(R.string.history_filter_accounts_count, 1)));
        await(() -> findInDialog(ctx.getString(R.string.history_filter_accounts_title)) != null,
            10_000);
        await(() -> findCheckInDialog(
            BankRules.displayName(ctx, SAMAN) + " — 333") != null, 30_000);
        checkInDialog(BankRules.displayName(ctx, SAMAN) + " — 333");
        click(findInDialog(ctx.getString(R.string.history_filter_apply)));
        await(() -> findByText(ctx.getString(R.string.history_filter_accounts_count, 2)) != null,
            20_000);
        assertNotNull(findByTextContaining("30,000,000"));
        assertNotNull(findByTextContaining("900,000"));
        assertTrue(findByTextContaining("5,000,000") == null);

        // Clearing the accounts returns the full history.
        click(findByText(ctx.getString(R.string.history_filter_accounts_count, 2)));
        await(() -> findInDialog(ctx.getString(R.string.history_filter_accounts_title)) != null,
            10_000);
        click(findInDialog(ctx.getString(R.string.history_filter_accounts_clear)));
        await(() -> findByTextContaining("5,000,000") != null, 20_000);
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

    /** Checks a dialog checkbox by label, atomically on the main thread: find, tap, verify
     *  checked. Retried until it sticks, since the choices populate asynchronously. */
    private static void checkInDialog(String want) {
        setCheckedInDialog(want, true);
    }

    /** Unchecks a dialog checkbox by label, retried like {@link #checkInDialog}. */
    private static void uncheckInDialog(String want) {
        setCheckedInDialog(want, false);
    }

    private static void setCheckedInDialog(String want, boolean checked) {
        await(() -> {
            final boolean[] done = {false};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                View root = dialogRoot();
                if (root == null) return;
                List<CheckBox> out = new ArrayList<>();
                collectChecks(root, out);
                for (CheckBox c : out) {
                    CharSequence s = c.getText();
                    if (s != null && want.equals(s.toString())) {
                        if (c.isChecked() != checked) c.performClick();
                        done[0] = c.isChecked() == checked;
                        return;
                    }
                }
            });
            return done[0];
        }, 30_000);
    }

    private static CheckBox findCheckInDialog(String want) {
        for (CheckBox c : dialogChecks()) {
            CharSequence s = c.getText();
            if (s != null && want.equals(s.toString())) return c;
        }
        return null;
    }

    private static List<CheckBox> dialogChecks() {
        final List<CheckBox> out = new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View root = dialogRoot();
            if (root != null) collectChecks(root, out);
        });
        return out;
    }

    /** Exact-text search inside the latest shown dialog window. */
    private static TextView findInDialog(String want) {
        final List<TextView> out = new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View root = dialogRoot();
            if (root != null) collect(root, out);
        });
        for (TextView t : out) if (want.equals(t.getText().toString())) return t;
        return null;
    }

    /** The decor view of the latest dialog the resumed history screen shows, or null. Dialog
     *  windows live outside the activity's own decor view, so they need their own root. */
    private static View dialogRoot() {
        List<Activity> activities = resumed();
        if (activities.isEmpty()) return null;
        List<android.app.AlertDialog> dialogs =
            HistoryActivity.shownDialogsForTest((HistoryActivity) activities.get(0));
        if (dialogs.isEmpty() || dialogs.get(dialogs.size() - 1).getWindow() == null) return null;
        return dialogs.get(dialogs.size() - 1).getWindow().getDecorView();
    }

    private static void collectChecks(View v, List<CheckBox> out) {
        if (v instanceof CheckBox) out.add((CheckBox) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectChecks(g.getChildAt(i), out);
        }
    }

    private static View findByDescription(String want) {
        final List<View> hits = new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : resumed()) {
                View hit = find(a.getWindow().getDecorView(), v ->
                    want.equals(v.getContentDescription()));
                if (hit != null) hits.add(hit);
            }
        });
        return hits.isEmpty() ? null : hits.get(0);
    }

    private interface Match { boolean ok(View v); }

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
