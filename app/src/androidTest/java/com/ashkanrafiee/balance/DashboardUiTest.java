package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.view.Gravity;
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

import java.util.LinkedHashMap;
import java.util.Set;
import java.util.HashSet;

/** Regression tests for the native dashboard composition.
 *
 * <p>These tests intentionally inspect semantic controls instead of pixels or canvas geometry. A
 * redesign must keep the dashboard understandable to a screen reader and keyboard user while the
 * balance store, masking rules and bank/account scope continue to come from the existing domain
 * layer.
 */
@RunWith(AndroidJUnit4.class)
public final class DashboardUiTest {
    private Context context;
    private ActivityScenario<MainActivity> scenario;
    private LinkedHashMap<String, Bank> originalBalances;
    private Set<String> originalExcluded;
    private boolean originalOnboarding;
    private boolean originalShowCommitments;
    private boolean originalHidden;
    private boolean originalAutoHide;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalBalances = BalanceData.read(context);
        originalExcluded = new HashSet<>(BalanceData.getExcluded(context));
        originalOnboarding = BalanceData.isOnboardingSeen(context);
        originalShowCommitments = BalanceData.getShowCommitments(context);
        originalHidden = BalanceData.isHidden(context);
        originalAutoHide = BalanceData.isAutoHide(context);
        LocaleHelper.setLanguage(context, "en");
        CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_RIAL);
        RegionHelper.setRegion(context, RegionHelper.REGION_INTERNATIONAL);
        BalanceData.setOnboardingSeen(context, true);
        BalanceData.setShowCommitments(context, false);
        setHidden(false);
        BalanceData.setAutoHide(context, false);
        BalanceData.setExcluded(context, new HashSet<>());
        grantSms();
        BalanceData.write(context, new LinkedHashMap<>());
    }

    @After public void tearDown() {
        if (scenario != null) scenario.close();
        BalanceData.write(context, originalBalances);
        BalanceData.setExcluded(context, originalExcluded);
        BalanceData.setOnboardingSeen(context, originalOnboarding);
        BalanceData.setShowCommitments(context, originalShowCommitments);
        setHidden(originalHidden);
        BalanceData.setAutoHide(context, originalAutoHide);
    }

    @Test public void emptyDashboard_exposesNativeActionsAndGuidance() {
        launch();
        scenario.onActivity(activity -> {
            assertNotNull(activity.findViewById(R.id.dashboard_screen));
            assertNotNull(activity.findViewById(R.id.dashboard_total_card));
            assertNotNull(activity.findViewById(R.id.dashboard_mask_button));
            assertNotNull(activity.findViewById(R.id.dashboard_refresh_button));
            assertNotNull(activity.findViewById(R.id.dashboard_lock_button));
            assertNotNull(activity.findViewById(R.id.dashboard_action_dock));
            View empty = activity.findViewById(R.id.dashboard_empty_state);
            View bankList = activity.findViewById(R.id.dashboard_bank_list);
            assertTrue("dashboard exposes either its empty state or its native bank list",
                empty != null || bankList != null);
            if (empty != null) {
                assertTrue("empty state is actionable", empty.isClickable());
                assertNotNull(findText(activity.getWindow().getDecorView(),
                    activity.getString(R.string.dashboard_no_accounts_title)));
            }
        });
    }

    @Test public void savedBank_isRenderedAsSemanticRowAndMaskHidesPrivateFields() {
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        saved.put("Tejarat|1234", new Bank("Tejarat", 987_000L, System.currentTimeMillis(),
            "5000", "1234"));
        BalanceData.write(context, saved);
        launch();
        await(() -> findText(currentRoot(), "Tejarat") != null
            && findText(currentRoot(), "••••••") == null, 10_000);

        scenario.onActivity(activity -> {
            View list = activity.findViewById(R.id.dashboard_bank_list);
            assertNotNull("saved accounts use the native bank list", list);
            assertTrue("bank rows remain actionable", list instanceof ViewGroup
                && ((ViewGroup) list).getChildCount() > 0);
            assertTrue(hasTextContaining(activity.getWindow().getDecorView(), "1234"));
            activity.findViewById(R.id.dashboard_mask_button).performClick();
        });
        await(() -> findText(currentRoot(), "••••••") != null, 5_000);
        scenario.onActivity(activity -> {
            assertNotNull("masked account is still represented", findText(
                activity.getWindow().getDecorView(), "••••••"));
            assertFalse("the readable account must disappear while masked",
                hasTextContaining(activity.getWindow().getDecorView(), "1234"));
        });
    }

    @Test public void dashboardControlsHaveLocalizedDescriptionsAndLargeTargets() {
        launch();
        scenario.onActivity(activity -> {
            View mask = activity.findViewById(R.id.dashboard_mask_button);
            View refresh = activity.findViewById(R.id.dashboard_refresh_button);
            View lock = activity.findViewById(R.id.dashboard_lock_button);
            assertNotNull(mask.getContentDescription());
            assertNotNull(refresh.getContentDescription());
            assertNotNull(lock.getContentDescription());
            int min = (int) (48 * activity.getResources().getDisplayMetrics().density + .5f);
            assertTrue(mask.getMinimumWidth() >= min);
            assertTrue(refresh.getMinimumWidth() >= min);
            assertTrue(lock.getMinimumWidth() >= min);
        });
    }

    @Test public void changingCurrencyImmediatelyUpdatesVisibleAmountAndUnit() {
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        saved.put("Tejarat", new Bank("Tejarat", 1_000L, System.currentTimeMillis(), "5000"));
        BalanceData.write(context, saved);
        CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_TOMAN);
        launch();
        await(() -> hasText(currentRoot(), "100") && hasText(currentRoot(),
            context.getString(R.string.unit_toman)), 10_000);
        scenario.onActivity(activity -> {
            CurrencyHelper.setCurrency(activity, CurrencyHelper.CURRENCY_RIAL);
            activity.refreshCurrencyPresentation();
        });
        await(() -> hasText(currentRoot(), "1,000") && hasText(currentRoot(),
            context.getString(R.string.unit_rial)), 5_000);
    }

    @Test public void persianDashboardPlacesBrandOnTheReadingSide() {
        LocaleHelper.setLanguage(context, "fa");
        launch();
        await(() -> findText(currentRoot(), context.getString(R.string.app_name)) != null, 10_000);
        scenario.onActivity(activity -> {
            TextView title = findText(activity.getWindow().getDecorView(),
                activity.getString(R.string.app_name));
            TextView subtitle = findText(activity.getWindow().getDecorView(),
                activity.getString(R.string.subtitle_offline_bank_balances));
            assertNotNull("RTL dashboard keeps its brand title", title);
            assertNotNull("RTL dashboard keeps its brand subtitle", subtitle);
            assertEquals(View.LAYOUT_DIRECTION_RTL, title.getLayoutDirection());
            assertEquals(View.LAYOUT_DIRECTION_RTL, subtitle.getLayoutDirection());
            int titleGravity = title.getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK;
            int subtitleGravity = subtitle.getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK;
            assertTrue(titleGravity == Gravity.END || titleGravity == Gravity.RIGHT);
            assertTrue(subtitleGravity == Gravity.END || subtitleGravity == Gravity.RIGHT);
            ViewGroup brand = (ViewGroup) title.getParent();
            assertTrue("the brand remains on the right half in RTL", title.getRight()
                > brand.getWidth() / 2);
        });
    }

    private void launch() {
        scenario = ActivityScenario.launch(MainActivity.class);
    }

    private void grantSms() {
        try {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .grantRuntimePermission(context.getPackageName(), Manifest.permission.READ_SMS);
        } catch (Exception ignored) {
            // A platform image may grant or restrict SMS outside the normal runtime-permission path.
        }
    }

    private void setHidden(boolean hidden) {
        context.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(BalanceData.KEY_HIDDEN, hidden).commit();
    }

    private View currentRoot() {
        final View[] root = {null};
        if (scenario != null) scenario.onActivity(a -> root[0] = a.getWindow().getDecorView());
        return root[0];
    }

    private static void await(Check check, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (check.ok()) return;
            try { Thread.sleep(40); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertTrue("dashboard did not settle", check.ok());
    }

    private interface Check { boolean ok(); }

    private static TextView findText(View view, String value) {
        if (view instanceof TextView && value.equals(((TextView) view).getText().toString()))
            return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), value);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean hasText(View view, String value) {
        return findText(view, value) != null;
    }

    private static boolean hasTextContaining(View view, String value) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(value))
            return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                if (hasTextContaining(group.getChildAt(i), value)) return true;
        }
        return false;
    }
}
