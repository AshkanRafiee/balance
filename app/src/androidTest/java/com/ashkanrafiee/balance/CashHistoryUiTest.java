package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.List;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CashHistoryUiTest {
    private Context context;
    private ActivityScenario<HistoryActivity> scenario;
    private String previousCurrency;
    private boolean previouslyEnabled;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        previousCurrency = CurrencyHelper.currency(context);
        previouslyEnabled = CashStore.isEnabled(context);
        BalanceData.reset(context, true);
        CashStoreTest.clear(context);
        CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_TOMAN);
        CashStore.setEnabled(context, true);
    }

    @After public void tearDown() {
        if (scenario != null) scenario.close();
        BalanceData.reset(context, true);
        CashStoreTest.clear(context);
        CurrencyHelper.setCurrency(context, previousCurrency);
        CashStore.setEnabled(context, previouslyEnabled);
    }

    @Test public void totalCanOpenEmptyCashScopeThenAddAndConfirmRemoval() {
        launch(null);
        await(activity -> findByDescription(activity, R.string.cash_history_add) != null);
        scenario.onActivity(activity -> {
            View chip = findByDescription(activity, activity.getString(
                R.string.history_filter_tags_accessibility, activity.getString(R.string.cash_label)));
            assertNotNull(chip);
            assertTrue(chip.performClick());
        });
        await(activity -> find(root(activity), v -> hasText(v,
            activity.getString(R.string.cash_history_empty))) != null);
        long beforeEditor = System.currentTimeMillis();
        scenario.onActivity(activity ->
            assertTrue(findByDescription(activity, R.string.cash_history_add).performClick()));
        await(activity -> currentDialog(activity) != null);
        scenario.onActivity(activity -> {
            AlertDialog editor = currentDialog(activity);
            EditText amount = (EditText) find(editor.getWindow().getDecorView(),
                v -> v instanceof EditText);
            assertNotNull(amount);
            amount.setText("0");
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertEquals(activity.getString(R.string.cash_history_invalid_amount), amount.getError());
            amount.setText("25");
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        });
        await(activity -> currentDialog(activity) == null && movement(activity) != null);
        Transaction added = CashStore.read(context).transactions.get(0);
        assertEquals(250, added.amount);
        assertTrue(added.date >= beforeEditor && added.date <= System.currentTimeMillis());

        requestRemoval();
        scenario.onActivity(activity -> currentDialog(activity)
            .getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        await(activity -> currentDialog(activity) == null);
        assertEquals(250, CashStore.read(context).balance);

        requestRemoval();
        scenario.onActivity(activity -> currentDialog(activity)
            .getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        await(activity -> currentDialog(activity) == null && movement(activity) == null);
        assertTrue(CashStore.read(context).transactions.isEmpty());
        assertEquals(0, CashStore.read(context).balance);
    }

    @Test public void bankAndDisabledCashScopesDoNotOfferManualAdd() {
        launch("Mellat");
        await(activity -> findByDescription(activity, R.string.history_export) != null
            && HistoryActivity.idleForTest(activity));
        scenario.onActivity(activity ->
            assertNull(findByDescription(activity, R.string.cash_history_add)));
        scenario.close();

        CashStore.setEnabled(context, false);
        launch(null);
        await(activity -> findByDescription(activity, R.string.history_export) != null
            && HistoryActivity.idleForTest(activity));
        scenario.onActivity(activity -> {
            assertNull(findByDescription(activity, R.string.cash_history_add));
            assertNull(findByDescription(activity, activity.getString(
                R.string.history_filter_tags_accessibility, activity.getString(R.string.cash_label))));
        });
    }

    private void launch(String bank) {
        Intent intent = new Intent(context, HistoryActivity.class);
        if (bank != null) intent.putExtra(HistoryActivity.EXTRA_BANK, bank);
        scenario = ActivityScenario.launch(intent);
    }

    private void requestRemoval() {
        scenario.onActivity(activity -> {
            assertTrue(movement(activity).performClick());
            assertTrue(currentDialog(activity).getButton(AlertDialog.BUTTON_NEUTRAL).performClick());
        });
        await(activity -> {
            AlertDialog dialog = currentDialog(activity);
            return dialog != null && hasText(dialog.getButton(AlertDialog.BUTTON_POSITIVE),
                activity.getString(R.string.cash_history_remove));
        });
    }

    private void await(Predicate<HistoryActivity> condition) {
        long deadline = SystemClock.uptimeMillis() + 20_000;
        do {
            boolean[] matched = {false};
            scenario.onActivity(activity -> matched[0] = condition.test(activity));
            if (matched[0]) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Cash history did not reach the expected state");
    }

    private static View movement(HistoryActivity activity) {
        return find(root(activity), view -> {
            CharSequence description = view.getContentDescription();
            return description != null
                && description.toString().contains(activity.getString(R.string.cash_history_row_hint));
        });
    }

    private static boolean hasText(View view, String text) {
        return view instanceof TextView && text.contentEquals(((TextView) view).getText());
    }

    private static View root(HistoryActivity activity) {
        return activity.getWindow().getDecorView();
    }

    private static AlertDialog currentDialog(HistoryActivity activity) {
        List<AlertDialog> dialogs = HistoryActivity.shownDialogsForTest(activity);
        for (int i = dialogs.size() - 1; i >= 0; i--) {
            if (dialogs.get(i).isShowing()) return dialogs.get(i);
        }
        return null;
    }

    private static View findByDescription(HistoryActivity activity, int resource) {
        return findByDescription(activity, activity.getString(resource));
    }

    private static View findByDescription(HistoryActivity activity, String description) {
        return find(root(activity), view -> view.getContentDescription() != null
            && description.contentEquals(view.getContentDescription()));
    }

    private static View find(View root, Predicate<View> predicate) {
        if (predicate.test(root)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), predicate);
                if (found != null) return found;
            }
        }
        return null;
    }
}
