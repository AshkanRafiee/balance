package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.function.Predicate;

@RunWith(AndroidJUnit4.class)
public final class DashboardCashTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private ActivityScenario<MainActivity> scenario;
    private String originalCash;
    private boolean originalCashEnabled;
    private boolean originalOnboarding;
    private boolean originalHidden;
    private boolean originalAutoHide;
    private String originalCurrency;
    private int originalStaleDays;

    @Before public void setUp() throws Exception {
        originalCash = CashStore.exportData(context);
        originalCashEnabled = CashStore.isEnabled(context);
        originalOnboarding = BalanceData.isOnboardingSeen(context);
        originalHidden = BalanceData.isHidden(context);
        originalAutoHide = BalanceData.isAutoHide(context);
        originalCurrency = CurrencyHelper.currency(context);
        originalStaleDays = BalanceData.getStaleDays(context);
        CashStoreTest.clear(context);
        CashStore.setEnabled(context, true);
        BalanceData.setOnboardingSeen(context, true);
        BalanceData.setAutoHide(context, false);
        BalanceData.setStaleDays(context, 3);
        preferences().edit().putBoolean(BalanceData.KEY_HIDDEN, false).commit();
        CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_TOMAN);
        instrumentation.getUiAutomation().grantRuntimePermission(
            context.getPackageName(), Manifest.permission.READ_SMS);
    }

    @After public void tearDown() throws Exception {
        if (scenario != null) scenario.close();
        CashStoreTest.clear(context);
        if (originalCash != null) CashStore.restoreData(context, originalCash);
        CashStore.setEnabled(context, originalCashEnabled);
        BalanceData.setOnboardingSeen(context, originalOnboarding);
        BalanceData.setAutoHide(context, originalAutoHide);
        BalanceData.setStaleDays(context, originalStaleDays);
        CurrencyHelper.setCurrency(context, originalCurrency);
        preferences().edit().putBoolean(BalanceData.KEY_HIDDEN, originalHidden).commit();
    }

    @Test public void countUpdatesBalanceWithoutHistoryAndMaskedRecountDoesNotPrefill() {
        launch();
        scenario.onActivity(activity -> {
            View card = activity.findViewById(R.id.dashboard_cash_card);
            assertNotNull(findText(card, activity.getString(R.string.cash_dashboard_not_counted)));
            clickText(card, activity.getString(R.string.cash_dashboard_count));
            AlertDialog count = dialog(activity);
            assertNotNull(findText(count.getWindow().getDecorView(),
                activity.getString(R.string.cash_dashboard_count_message)));
            EditText field = amountField(count);
            field.setText("-1");
            count.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertNotNull(field.getError());
            field.setText("142.1");
            count.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        });
        await(activity -> {
            TextView amount = activity.findViewById(R.id.dashboard_cash_amount);
            return dialog(activity) == null && activity.getString(R.string.cash_dashboard_value,
                CurrencyHelper.amountExact(activity, 1421), CurrencyHelper.label(activity))
                .contentEquals(amount.getText());
        });
        CashStore.State state = CashStore.read(context);
        assertEquals(1421, state.balance);
        assertTrue(state.countedAt > 0);
        assertTrue(state.transactions.isEmpty());

        scenario.onActivity(activity -> {
            clickText(activity.findViewById(R.id.dashboard_cash_card),
                activity.getString(R.string.cash_dashboard_count));
            assertEquals("142.1", amountField(dialog(activity)).getText().toString());
            dialog(activity).dismiss();
            activity.findViewById(R.id.dashboard_mask_button).performClick();
            TextView masked = activity.findViewById(R.id.dashboard_cash_amount);
            assertEquals("••••••", masked.getText().toString());
            clickText(activity.findViewById(R.id.dashboard_cash_card),
                activity.getString(R.string.cash_dashboard_count));
            assertEquals(0, amountField(dialog(activity)).length());
        });
        reviewHistory(CashStore.KEY);
    }

    @Test public void toggleRequiresConfirmationBothWaysAndReviewsTheIntendedHistory() {
        CashStore.setBalance(context, 86_000);
        launch();
        requestToggle(R.string.cash_dashboard_disable);
        reviewHistory(CashStore.KEY);
        assertTrue(CashStore.isEnabled(context));

        requestToggle(R.string.cash_dashboard_disable);
        scenario.onActivity(activity -> dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE)
            .performClick());
        await(activity -> activity.findViewById(R.id.dashboard_cash_card) == null);
        assertFalse(CashStore.isEnabled(context));
        assertEquals(86_000, CashStore.read(context).balance);

        requestToggle(R.string.cash_dashboard_enable);
        scenario.onActivity(activity -> dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE)
            .performClick());
        assertFalse(CashStore.isEnabled(context));

        requestToggle(R.string.cash_dashboard_enable);
        reviewHistory(null);
        await(activity -> activity.findViewById(R.id.dashboard_cash_card) != null);
        assertTrue(CashStore.isEnabled(context));
        assertEquals(86_000, CashStore.read(context).balance);
    }

    private void launch() {
        scenario = ActivityScenario.launch(MainActivity.class);
        await(activity -> activity.findViewById(R.id.dashboard_cash_card) != null);
    }

    private void requestToggle(int confirmationButton) {
        scenario.onActivity(activity -> clickText(activity.getWindow().getDecorView(),
            activity.getString(R.string.footer_display)));
        await(activity -> {
            TextView check = cashCheck(activity);
            return check != null && check.isEnabled();
        });
        scenario.onActivity(activity -> cashCheck(activity).performClick());
        await(activity -> {
            AlertDialog current = dialog(activity);
            return current != null && current.getButton(AlertDialog.BUTTON_POSITIVE) != null
                && activity.getString(confirmationButton)
                    .contentEquals(current.getButton(AlertDialog.BUTTON_POSITIVE).getText());
        });
    }

    private CheckBox cashCheck(MainActivity activity) {
        AlertDialog display = dialog(activity);
        return display == null ? null : (CheckBox) findText(display.getWindow().getDecorView(),
            activity.getString(R.string.cash_dashboard_display));
    }

    private void reviewHistory(String expectedScope) {
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
            HistoryActivity.class.getName(), null, false);
        try {
            scenario.onActivity(activity -> dialog(activity).getButton(AlertDialog.BUTTON_NEUTRAL)
                .performClick());
            Activity history = instrumentation.waitForMonitorWithTimeout(monitor, 10_000);
            assertNotNull("Review opens history", history);
            try {
                assertEquals(expectedScope, history.getIntent().getStringExtra(HistoryActivity.EXTRA_BANK));
            } finally {
                instrumentation.runOnMainSync(history::finish);
                instrumentation.waitForIdleSync();
            }
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }

    private void await(Predicate<MainActivity> condition) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        while (SystemClock.uptimeMillis() < deadline) {
            boolean[] ready = {false};
            scenario.onActivity(activity -> ready[0] = condition.test(activity));
            if (ready[0]) return;
            SystemClock.sleep(30);
        }
        fail("Timed out waiting for the cash dashboard");
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE);
    }

    private static AlertDialog dialog(MainActivity activity) {
        try {
            Field field = MainActivity.class.getDeclaredField("activeDialog");
            field.setAccessible(true);
            return (AlertDialog) field.get(activity);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static EditText amountField(AlertDialog dialog) {
        View field = find(dialog.getWindow().getDecorView(), view -> view instanceof EditText);
        assertNotNull("Cash amount field", field);
        return (EditText) field;
    }

    private static void clickText(View root, String text) {
        TextView button = findText(root, text);
        assertNotNull(text, button);
        assertTrue(button.performClick());
    }

    private static TextView findText(View root, String text) {
        return (TextView) find(root, view -> view instanceof TextView
            && text.contentEquals(((TextView) view).getText()));
    }

    private static View find(View root, Predicate<View> condition) {
        if (condition.test(root)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = find(group.getChildAt(i), condition);
                if (match != null) return match;
            }
        }
        return null;
    }
}
