package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/** UI contracts for the scheduled-payment editor and its top-level navigation. */
@RunWith(AndroidJUnit4.class)
public class ScheduledPaymentsUiTest {

    private static final long TIMEOUT_MS = 20_000L;

    private Context context;
    private ScheduledPaymentsActivity scheduled;
    private MainActivity main;
    private static volatile ScheduledPaymentsActivity trackedScheduled;
    private static volatile MainActivity trackedMain;

    private SavedPreference language;
    private SavedPreference currency;
    private SavedPreference region;
    private SavedPreference hidden;
    private SavedPreference autoHide;
    private SavedPreference onboarding;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        language = SavedPreference.capture(context, "balance_language", "language");
        currency = SavedPreference.capture(context, "balance_currency", "currency");
        region = SavedPreference.capture(context, "balance_region", "region");
        hidden = SavedPreference.capture(context, BalanceData.PREFS_PREF, BalanceData.KEY_HIDDEN);
        autoHide = SavedPreference.capture(context, BalanceData.PREFS_PREF, BalanceData.KEY_AUTO_HIDE);
        onboarding = SavedPreference.capture(context, BalanceData.PREFS_PREF,
            BalanceData.KEY_ONBOARDING_SEEN);

        finishOwnedActivities();
        trackedScheduled = null;
        trackedMain = null;
        // The planner is the only application store this class owns. In particular, do not reset
        // the transaction store: the UI tests must not alter the device's SMS-derived data.
        BalanceData.writeScheduledPayments(context, Collections.emptyList());

        LocaleHelper.setLanguage(context, "en");
        CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_RIAL);
        RegionHelper.setRegion(context, RegionHelper.REGION_INTERNATIONAL);
        setHidden(false);
        BalanceData.setAutoHide(context, false);
        BalanceData.setOnboardingSeen(context, true);
    }

    @After public void tearDown() {
        finishOwnedActivities();
        BalanceData.writeScheduledPayments(context, Collections.emptyList());

        // Use the public setters first so process-wide locale state is put back as well as the
        // preference values, then restore presence/absence exactly for preferences that were not
        // present before the test.
        LocaleHelper.setLanguage(context, stringValue(language, ""));
        CurrencyHelper.setCurrency(context, stringValue(currency, CurrencyHelper.CURRENCY_TOMAN));
        RegionHelper.setRegion(context, RegionHelper.REGION_IRAN);
        language.restore(context);
        currency.restore(context);
        region.restore(context);
        hidden.restore(context);
        autoHide.restore(context);
        onboarding.restore(context);
    }

    @Test public void editor_visibilityFollowsOneTimeAndRecurringChoices() {
        scheduled = launchScheduled();
        openEditor(null);
        AlertDialog editor = requireEditor();

        Spinner type = spinner(editor, "payment_type");
        Spinner repeat = spinner(editor, "payment_repeat");
        Spinner ending = spinner(editor, "payment_ending");
        assertTrue(enabled(editText(editor, "payment_title")));
        assertTrue(enabled(editText(editor, "payment_amount")));
        assertTrue(enabled(type));
        assertTrue(enabled(view(editor, "payment_first")));

        select(type, ScheduledPayment.Type.ONE_TIME.ordinal());
        await("one-time rows", () -> !isVisible(editor, "payment_repeat_row")
            && !isVisible(editor, "payment_ending_row"));
        assertFalse("the one-time frequency is fixed, not editable", enabled(repeat));

        select(type, ScheduledPayment.Type.SUBSCRIPTION.ordinal());
        assertEquals("subscriptions expose only recurring frequencies", 3, repeat.getAdapter().getCount());
        select(repeat, 1); // weekly, monthly, yearly — Does not repeat is not usable here.
        select(ending, ScheduledPayment.EndMode.DATE.ordinal());
        await("date ending", () -> isVisible(editor, "payment_repeat_row")
            && isVisible(editor, "payment_ending_row")
            && isVisible(editor, "payment_end_row")
            && !isVisible(editor, "payment_count"));
        assertTrue(enabled(repeat));
        assertTrue(enabled(ending));

        select(ending, ScheduledPayment.EndMode.COUNT.ordinal());
        await("count ending", () -> isVisible(editor, "payment_count")
            && !isVisible(editor, "payment_end_row"));

        select(type, ScheduledPayment.Type.DEBT.ordinal());
        select(repeat, ScheduledPayment.Frequency.ONCE.ordinal());
        await("non-recurring ending", () -> !isVisible(editor, "payment_ending_row")
            && !isVisible(editor, "payment_end_row") && !isVisible(editor, "payment_count"));
        dismissDialogs();
    }

    @Test public void editingTitleOnly_preservesStatesAndRawRialAmountAcrossCurrencyChange() {
        ScheduledPayment original = countPlan("edit-count-plan", "Old title", 12_345L);
        original.setState(0, ScheduledPayment.STATE_PAID);
        original.setState(1, ScheduledPayment.STATE_SKIPPED);
        BalanceData.writeScheduledPayments(context, Collections.singletonList(original));

        scheduled = launchScheduled();
        openEditor(original);
        AlertDialog editor = requireEditor();
        EditText amount = editText(editor, "payment_amount");
        String rawRialField = textOf(amount);
        assertTrue("the editor must show the stored amount in Rial", rawRialField.contains("12"));
        assertFalse("an active plan's first date cannot be changed", enabled(view(editor, "payment_first")));
        assertFalse("an active plan's frequency cannot be changed", enabled(spinner(editor, "payment_repeat")));
        assertTrue("ending metadata remains editable", enabled(spinner(editor, "payment_ending")));

        setText(editText(editor, "payment_title"), "Renamed title");
        // The editor field is already the user's raw displayed value. Changing the unit while the
        // dialog is open must not reinterpret that untouched value as Toman on save.
        CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_TOMAN);
        assertEquals("changing the unit must not rewrite an untouched editor field",
            rawRialField, textOf(editText(editor, "payment_amount")));
        clickPositive(editor);

        await("title-only save", () -> {
            List<ScheduledPayment> plans = BalanceData.readScheduledPayments(context);
            if (plans.size() != 1) return false;
            ScheduledPayment saved = plans.get(0);
            return "Renamed title".equals(saved.title) && saved.amountRial == 12_345L
                && original.states.equals(saved.states) && lastShowingDialog() == null;
        });
        ScheduledPayment saved = BalanceData.readScheduledPayments(context).get(0);
        assertEquals(ScheduledPayment.STATE_PAID, saved.state(0));
        assertEquals(ScheduledPayment.STATE_SKIPPED, saved.state(1));
        assertEquals(12_345L, saved.amountRial);
    }

    @Test public void stoppingFuturePayments_keepsRecordedStatesAndRemovesNextOccurrence() {
        CalendarSystem calendar = CalendarSystem.GREGORIAN;
        ScheduledDate today = today(calendar);
        ScheduledPayment original = new ScheduledPayment("stop-ui-plan", "Future rent",
            ScheduledPayment.Type.SUBSCRIPTION, 1_000L, calendar,
            today.plusDays(-14).year, today.plusDays(-14).month, today.plusDays(-14).day,
            ScheduledPayment.Frequency.WEEKLY, ScheduledPayment.EndMode.NEVER,
            0, 0, 0, 0);
        original.setState(0, ScheduledPayment.STATE_PAID);
        original.setState(1, ScheduledPayment.STATE_PAID);
        original.setState(2, ScheduledPayment.STATE_PAID);
        BalanceData.writeScheduledPayments(context, Collections.singletonList(original));

        scheduled = launchScheduled();
        openEditor(original);
        AlertDialog editor = requireEditor();
        TextView stop = textView(editor, "payment_stop");
        assertNotNull("a recurring plan with future occurrences must offer stopping", stop);
        assertEquals(context.getString(R.string.scheduled_stop_future), textOf(stop));

        click(stop);
        await("stop confirmation", () -> {
            AlertDialog current = lastShowingDialog();
            return current != null && current != editor;
        });
        AlertDialog confirmation = lastShowingDialog();
        assertNotNull(confirmation);
        clickPositive(confirmation);

        await("stopped plan", () -> {
            List<ScheduledPayment> plans = BalanceData.readScheduledPayments(context);
            if (plans.size() != 1 || plans.get(0).stoppedAfter == null) return false;
            List<PaymentOccurrence> occurrences = ScheduledPayments.occurrences(plans,
                Long.MIN_VALUE, Long.MAX_VALUE);
            boolean nextStillPresent = false;
            for (PaymentOccurrence occurrence : occurrences) {
                if (occurrence.sequence <= 2 && !occurrence.paid()) return false;
                if (occurrence.sequence == 3) nextStillPresent = true;
            }
            return !nextStillPresent;
        });

        ScheduledPayment stopped = BalanceData.readScheduledPayments(context).get(0);
        assertNotNull(stopped.stoppedAfter);
        assertEquals(ScheduledPayment.STATE_PAID, stopped.state(0));
        assertEquals(ScheduledPayment.STATE_PAID, stopped.state(1));
        assertEquals(ScheduledPayment.STATE_PAID, stopped.state(2));
        assertEquals(3, ScheduledPayments.occurrences(Collections.singletonList(stopped),
            Long.MIN_VALUE, Long.MAX_VALUE).size());
    }

    @Test public void navigation_hasFourItemsAndReturnsFromPaymentsToSettings() {
        main = (MainActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        trackedMain = main;
        await("main navigation", () -> navigation(main) != null);

        BottomNavigation mainNavigation = navigation(main);
        assertEquals(4, childCount(mainNavigation));
        click(childAt(mainNavigation, BottomNavigation.PAYMENTS));
        await("payments activity", () -> resumed(ScheduledPaymentsActivity.class) != null);
        scheduled = (ScheduledPaymentsActivity) resumed(ScheduledPaymentsActivity.class);

        BottomNavigation paymentsNavigation = navigation(scheduled);
        assertNotNull("payments must use the shared bottom navigation", paymentsNavigation);
        assertEquals(4, childCount(paymentsNavigation));
        assertEquals(BottomNavigation.PAYMENTS, selectedTab(paymentsNavigation));

        click(childAt(paymentsNavigation, BottomNavigation.SETTINGS));
        await("settings return", () -> resumed(MainActivity.class) != null
            && resumed(ScheduledPaymentsActivity.class) == null);
        main = (MainActivity) resumed(MainActivity.class);
        assertEquals(BottomNavigation.SETTINGS, selectedTab(navigation(main)));
        assertNotNull(findTextInActivity(main, context.getString(R.string.settings_heading)));
    }

    @Test public void hiddenBalances_maskPlannerContentAndBlockEditorUntilEyeIsTapped() {
        ScheduledPayment secret = new ScheduledPayment("hidden-ui-plan", "Private salary",
            ScheduledPayment.Type.ONE_TIME, 98_765L, CalendarSystem.GREGORIAN,
            today(CalendarSystem.GREGORIAN).year, today(CalendarSystem.GREGORIAN).month,
            today(CalendarSystem.GREGORIAN).day, ScheduledPayment.Frequency.ONCE,
            ScheduledPayment.EndMode.NEVER, 0, 0, 0, 0);
        BalanceData.writeScheduledPayments(context, Collections.singletonList(secret));
        setHidden(true);

        scheduled = launchScheduled();
        String shownAmount = CurrencyHelper.amount(context, secret.amountRial);
        await("masked planner", () -> {
            String allText = textContent(scheduled);
            return !allText.contains(secret.title) && !allText.contains(shownAmount);
        });
        assertEquals(context.getString(R.string.widget_action_mask),
            descriptionOf(findByDescription(scheduled, context.getString(R.string.widget_action_mask))));

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> scheduled.showEditor(secret));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertFalse("a hidden screen must not leave an editor dialog open", hasShowingDialog());

        click(findByDescription(scheduled, context.getString(R.string.widget_action_mask)));
        await("revealed planner", () -> {
            String allText = textContent(scheduled);
            return allText.contains(secret.title) && allText.contains(shownAmount)
                && !BalanceData.isHidden(context);
        });
    }

    // ---------------------------------------------------------------------
    // Editor and activity helpers
    // ---------------------------------------------------------------------

    private ScheduledPaymentsActivity launchScheduled() {
        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
            new Intent(context, ScheduledPaymentsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        scheduled = (ScheduledPaymentsActivity) activity;
        trackedScheduled = scheduled;
        await("scheduled activity", () -> navigation(scheduled) != null);
        return scheduled;
    }

    private void openEditor(ScheduledPayment existing) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> scheduled.showEditor(existing));
        await("editor dialog", () -> lastShowingDialog() != null
            && view(lastShowingDialog(), "payment_title") != null);
    }

    private AlertDialog requireEditor() {
        AlertDialog dialog = lastShowingDialog();
        assertNotNull("the editor dialog must be showing", dialog);
        return dialog;
    }

    private ScheduledPayment countPlan(String id, String title, long amount) {
        ScheduledDate first = today(CalendarSystem.GREGORIAN);
        return new ScheduledPayment(id, title, ScheduledPayment.Type.SUBSCRIPTION, amount,
            CalendarSystem.GREGORIAN, first.year, first.month, first.day,
            ScheduledPayment.Frequency.MONTHLY, ScheduledPayment.EndMode.COUNT,
            0, 0, 0, 5);
    }

    private ScheduledDate today(CalendarSystem calendar) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        return ScheduledDate.fromGregorian(now.get(java.util.Calendar.YEAR),
            now.get(java.util.Calendar.MONTH) + 1, now.get(java.util.Calendar.DAY_OF_MONTH), calendar);
    }

    private static void select(Spinner spinner, int position) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> spinner.setSelection(position));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static void click(View view) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(view::performClick);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static void clickPositive(AlertDialog dialog) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static void dismissDialogs() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity activity : resumedActivities()) {
                if (!(activity instanceof ScheduledPaymentsActivity)) continue;
                for (AlertDialog dialog : showingDialogsOnMain((ScheduledPaymentsActivity) activity)) dialog.dismiss();
            }
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static EditText editText(AlertDialog dialog, String tag) {
        return (EditText) view(dialog, tag);
    }

    private static Spinner spinner(AlertDialog dialog, String tag) {
        return (Spinner) view(dialog, tag);
    }

    private static TextView textView(AlertDialog dialog, String tag) {
        return (TextView) view(dialog, tag);
    }

    private static View view(AlertDialog dialog, String tag) {
        AtomicReference<View> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if (dialog != null && dialog.getWindow() != null)
                result.set(find(dialog.getWindow().getDecorView(), candidate -> tag.equals(candidate.getTag())));
        });
        return result.get();
    }

    private static boolean isVisible(AlertDialog dialog, String tag) {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View value = dialog == null || dialog.getWindow() == null ? null
                : find(dialog.getWindow().getDecorView(), candidate -> tag.equals(candidate.getTag()));
            result.set(value != null && value.getVisibility() == View.VISIBLE);
        });
        return result.get();
    }

    private static boolean enabled(View view) {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(
            view != null && view.isEnabled()));
        return result.get();
    }

    private static String textOf(TextView view) {
        AtomicReference<String> result = new AtomicReference<>("");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(
            view == null ? "" : view.getText().toString()));
        return result.get();
    }

    private static void setText(EditText view, String value) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> view.setText(value));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static String descriptionOf(View view) {
        AtomicReference<String> result = new AtomicReference<>("");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(
            view == null || view.getContentDescription() == null
                ? "" : view.getContentDescription().toString()));
        return result.get();
    }

    private static int childCount(ViewGroup view) {
        AtomicReference<Integer> result = new AtomicReference<>(0);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(view.getChildCount()));
        return result.get();
    }

    private static View childAt(ViewGroup view, int index) {
        AtomicReference<View> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(view.getChildAt(index)));
        return result.get();
    }

    private static int selectedTab(BottomNavigation navigation) {
        AtomicReference<Integer> result = new AtomicReference<>(-1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(navigation.selectedTab()));
        return result.get();
    }

    // ---------------------------------------------------------------------
    // Dialog reflection and view discovery
    // ---------------------------------------------------------------------

    private static AlertDialog lastShowingDialog() {
        for (Activity activity : resumedActivities()) {
            if (activity instanceof ScheduledPaymentsActivity) {
                AlertDialog dialog = lastShowingDialog((ScheduledPaymentsActivity) activity);
                if (dialog != null) return dialog;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<AlertDialog> showingDialogs(ScheduledPaymentsActivity activity) {
        AtomicReference<List<AlertDialog>> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            result.set(showingDialogsOnMain(activity)));
        return result.get() == null ? Collections.emptyList() : result.get();
    }

    @SuppressWarnings("unchecked")
    private static List<AlertDialog> showingDialogsOnMain(ScheduledPaymentsActivity activity) {
        try {
            Field field = ScheduledPaymentsActivity.class.getDeclaredField("dialogs");
            field.setAccessible(true);
            Object value = field.get(activity);
            if (!(value instanceof List<?>)) return Collections.emptyList();
            List<AlertDialog> result = new ArrayList<>();
            for (Object item : (List<?>) value) {
                if (item instanceof AlertDialog && ((AlertDialog) item).isShowing())
                    result.add((AlertDialog) item);
            }
            return result;
        } catch (ReflectiveOperationException ignored) {
            return Collections.emptyList();
        }
    }

    private static AlertDialog lastShowingDialog(ScheduledPaymentsActivity activity) {
        List<AlertDialog> dialogs = showingDialogs(activity);
        return dialogs.isEmpty() ? null : dialogs.get(dialogs.size() - 1);
    }

    private static boolean hasShowingDialog() {
        return lastShowingDialog() != null;
    }

    private static View findByDescription(Activity activity, String description) {
        AtomicReference<View> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(
            find(activity.getWindow().getDecorView(), candidate -> candidate.getContentDescription() != null
                && description.contentEquals(candidate.getContentDescription()))));
        return result.get();
    }

    private static TextView findTextInActivity(Activity activity, String text) {
        AtomicReference<TextView> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View found = find(activity.getWindow().getDecorView(), candidate ->
                candidate instanceof TextView && text.contentEquals(((TextView) candidate).getText()));
            if (found instanceof TextView) result.set((TextView) found);
        });
        return result.get();
    }

    private static String textContent(Activity activity) {
        AtomicReference<String> result = new AtomicReference<>("");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            StringBuilder all = new StringBuilder();
            collectText(activity.getWindow().getDecorView(), all);
            result.set(all.toString());
        });
        return result.get();
    }

    private interface Match { boolean accepts(View view); }

    private static View find(View root, Match match) {
        if (match.accepts(root)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), match);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void collectText(View root, StringBuilder out) {
        if (root instanceof TextView) out.append(((TextView) root).getText()).append('\n');
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) collectText(group.getChildAt(i), out);
        }
    }

    private static BottomNavigation navigation(Activity activity) {
        AtomicReference<BottomNavigation> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View found = find(activity.getWindow().getDecorView(), view -> view instanceof BottomNavigation);
            if (found instanceof BottomNavigation) result.set((BottomNavigation) found);
        });
        return result.get();
    }

    // ---------------------------------------------------------------------
    // Activity lifecycle and bounded polling
    // ---------------------------------------------------------------------

    private static List<Activity> resumedActivities() {
        AtomicReference<List<Activity>> snapshot = new AtomicReference<>();
        Runnable collect = () -> snapshot.set(new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)));
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) collect.run();
        else InstrumentationRegistry.getInstrumentation().runOnMainSync(collect);
        return snapshot.get();
    }

    private static Activity resumed(Class<? extends Activity> type) {
        for (Activity activity : resumedActivities()) {
            if (type.isInstance(activity)) return activity;
        }
        return null;
    }

    private static void finishOwnedActivities() {
        for (int pass = 0; pass < 5; pass++) {
            AtomicReference<Boolean> finished = new AtomicReference<>(false);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (Activity activity : resumedActivities()) {
                    if (activity instanceof MainActivity || activity instanceof ScheduledPaymentsActivity) {
                        finished.set(true);
                        activity.finish();
                    }
                }
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            if (!finished.get()) return;
            sleep(100);
        }
    }

    private static void await(String what, Callable<Boolean> condition) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.call()) return;
            } catch (Exception ignored) {
                // A background render may replace a view or storage snapshot between polls.
            }
            sleep(100);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while polling", e);
        }
    }

    // ---------------------------------------------------------------------
    // Preference and masking helpers
    // ---------------------------------------------------------------------

    private void setHidden(boolean value) {
        context.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(BalanceData.KEY_HIDDEN, value).commit();
    }

    private static String stringValue(SavedPreference preference, String fallback) {
        return preference.present && preference.value instanceof String
            ? (String) preference.value : fallback;
    }

    private static final class SavedPreference {
        final String file;
        final String key;
        final boolean present;
        final Object value;

        private SavedPreference(String file, String key, boolean present, Object value) {
            this.file = file;
            this.key = key;
            this.present = present;
            this.value = value;
        }

        static SavedPreference capture(Context context, String file, String key) {
            SharedPreferences preferences = context.getSharedPreferences(file, Context.MODE_PRIVATE);
            Map<String, ?> all = preferences.getAll();
            return new SavedPreference(file, key, all.containsKey(key), all.get(key));
        }

        @SuppressWarnings("unchecked")
        void restore(Context context) {
            SharedPreferences.Editor editor = context.getSharedPreferences(file, Context.MODE_PRIVATE).edit();
            if (!present) editor.remove(key);
            else if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof java.util.Set<?>)
                editor.putStringSet(key, (java.util.Set<String>) value);
            editor.commit();
        }
    }
}
