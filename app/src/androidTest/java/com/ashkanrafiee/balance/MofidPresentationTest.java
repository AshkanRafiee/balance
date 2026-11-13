package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.os.LocaleList;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.function.Predicate;

/** Product identity reaches rendered surfaces, not just the bank-name resource lookup. */
@RunWith(AndroidJUnit4.class)
public class MofidPresentationTest {
    private Context context;
    private String originalLanguage;
    private LocaleList originalLocales;
    private boolean originalOnboarding;
    private boolean originalCommitments;
    private Set<String> originalExcluded;
    private ActivityScenario<MainActivity> dashboard;
    private ActivityScenario<HistoryActivity> history;
    private Bank balance;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalLanguage = LocaleHelper.currentTag(context);
        originalLocales = LocaleList.getDefault();
        originalOnboarding = BalanceData.isOnboardingSeen(context);
        originalCommitments = BalanceData.getShowCommitments(context);
        originalExcluded = new HashSet<>(BalanceData.getExcluded(context));
        BalanceData.reset(context, true);
        BalanceData.setOnboardingSeen(context, true);
        BalanceData.setShowCommitments(context, false);
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .grantRuntimePermission(context.getPackageName(), Manifest.permission.READ_SMS);
        long now = System.currentTimeMillis();
        balance = new Bank("Mofid", 975L, now, "10008700");
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        saved.put("Mofid", balance);
        BalanceData.write(context, saved);
        assertTrue(BalanceData.writeTransactions(context, Collections.singletonList(
            new Transaction("Mofid", null, now, -25L, 975L,
                "mofid-presentation", "mofid-presentation-content"))));
    }

    @After public void tearDown() {
        if (history != null) history.close();
        if (dashboard != null) dashboard.close();
        try {
            BalanceData.reset(context, true);
            BalanceData.setExcluded(context, originalExcluded);
            BalanceData.setOnboardingSeen(context, originalOnboarding);
            BalanceData.setShowCommitments(context, originalCommitments);
        } finally {
            LocaleHelper.setLanguage(context, originalLanguage);
            LocaleList.setDefault(originalLocales);
        }
    }

    @Test public void englishProductLabel_reachesDashboardHistoryAndWidget() {
        verifyPresentation("en", "Mofid (Hami)");
    }

    @Test public void persianProductLabel_reachesDashboardHistoryAndWidget() {
        verifyPresentation("fa", "مفید (حامی)");
    }

    private void verifyPresentation(String language, String label) {
        LocaleHelper.setLanguage(context, language);
        dashboard = ActivityScenario.launch(MainActivity.class);
        await(dashboard, activity -> visibleLabel(
            activity.findViewById(R.id.dashboard_bank_list), label), "dashboard product label");
        dashboard.close();
        dashboard = null;

        // Combined history must name the product on the movement itself, not just in a header.
        history = ActivityScenario.launch(HistoryActivity.class);
        await(history, activity -> {
            if (!HistoryActivity.idleForTest(activity)) return false;
            View movement = find(activity.getWindow().getDecorView(), view -> {
                CharSequence description = view.getContentDescription();
                return view.isClickable() && description != null
                    && description.toString().contains(label)
                    && description.toString().contains(activity.getString(R.string.history_withdrawal));
            });
            return visibleLabel(movement, label);
        }, "history movement product label");

        // Apply the actual row RemoteViews as a launcher does, using an unwrapped context.
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View row = BalanceWidgetService.bankViews(context, balance).apply(context, null);
            TextView name = row.findViewById(R.id.bank_name);
            assertEquals(label, name.getText().toString());
            assertEquals(View.VISIBLE, name.getVisibility());
        });
    }

    private static boolean visibleLabel(View root, String label) {
        View name = find(root, view -> view instanceof TextView
            && label.contentEquals(((TextView) view).getText()));
        return name != null && name.isShown() && name.isAttachedToWindow()
            && name.isLaidOut() && !name.isLayoutRequested()
            && name.getWidth() > 0 && name.getHeight() > 0;
    }

    private static View find(View root, Predicate<View> matches) {
        if (root == null) return null;
        if (matches.test(root)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), matches);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <A extends Activity> void await(ActivityScenario<A> scenario,
            Predicate<A> ready, String description) {
        long deadline = SystemClock.uptimeMillis() + 20_000L;
        boolean[] settled = {false};
        do {
            scenario.onActivity(activity -> settled[0] = ready.test(activity));
            if (settled[0]) return;
            SystemClock.sleep(40L);
        } while (SystemClock.uptimeMillis() < deadline);
        assertTrue(description + " did not settle", settled[0]);
    }
}
