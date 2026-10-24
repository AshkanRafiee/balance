package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
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

import java.util.HashSet;
import java.util.LinkedHashMap;

/** Dashboard layout coverage at a system font scale where cards must reflow rather than overlap. */
@RunWith(AndroidJUnit4.class)
public final class DashboardLargeFontUiTest {
    private Context context;
    private ActivityScenario<MainActivity> scenario;
    private LinkedHashMap<String, Bank> originalBalances;
    private HashSet<String> originalExcluded;
    private boolean originalOnboarding;
    private boolean originalShowCommitments;
    private boolean originalHidden;
    private String originalLanguage;
    private String originalCurrency;
    private int originalRegion;
    private int originalStaleDays;
    private float originalFontScale;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalBalances = BalanceData.read(context);
        originalExcluded = new HashSet<>(BalanceData.getExcluded(context));
        originalOnboarding = BalanceData.isOnboardingSeen(context);
        originalShowCommitments = BalanceData.getShowCommitments(context);
        originalHidden = context.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE)
            .getBoolean(BalanceData.KEY_HIDDEN, false);
        originalLanguage = LocaleHelper.currentTag(context);
        originalCurrency = CurrencyHelper.currency(context);
        originalRegion = RegionHelper.region(context);
        originalStaleDays = BalanceData.getStaleDays(context);
        originalFontScale = context.getResources().getConfiguration().fontScale;

        shell("settings put system font_scale 2.0");
        SystemClock.sleep(500);
        LocaleHelper.setLanguage(context, "en");
        CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_TOMAN);
        RegionHelper.setRegion(context, RegionHelper.REGION_INTERNATIONAL);
        BalanceData.setOnboardingSeen(context, true);
        BalanceData.setShowCommitments(context, false);
        BalanceData.setStaleDays(context, 14);
        BalanceData.setExcluded(context, new HashSet<>());
        context.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(BalanceData.KEY_HIDDEN, false).commit();
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        saved.put("Pasargad|777.888.15563988.1", new Bank("Pasargad", 49_842_060_000L,
            System.currentTimeMillis() - 181L * 24 * 60 * 60 * 1000,
            "5000", "777.888.15563988.1"));
        BalanceData.write(context, saved);
        grantSms();
    }

    @After public void tearDown() throws Exception {
        if (scenario != null) scenario.close();
        BalanceData.write(context, originalBalances);
        BalanceData.setExcluded(context, originalExcluded);
        BalanceData.setOnboardingSeen(context, originalOnboarding);
        BalanceData.setShowCommitments(context, originalShowCommitments);
        BalanceData.setStaleDays(context, originalStaleDays);
        context.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(BalanceData.KEY_HIDDEN, originalHidden).commit();
        LocaleHelper.setLanguage(context, originalLanguage);
        CurrencyHelper.setCurrency(context, originalCurrency);
        RegionHelper.setRegion(context, originalRegion);
        shell("settings put system font_scale " + originalFontScale);
    }

    @Test public void largeFontBankCardKeepsMetadataInsideItsPillAndCard() {
        launch();
        await(() -> findText(currentRoot(), "181 days") != null, 10_000);
        scenario.onActivity(activity -> {
            ViewGroup list = (ViewGroup) activity.findViewById(R.id.dashboard_bank_list);
            assertNotNull(list);
            View card = list.getChildAt(0);
            assertNotNull(card);
            TextView stale = findText(card, "181 days");
            TextView account = findText(card, "Account 777.888.15563988.1");
            assertNotNull("stale metadata remains visible", stale);
            assertNotNull("account metadata remains visible", account);
            Rect cardBounds = globalBounds(card);
            Rect staleBounds = globalBounds(stale);
            Rect accountBounds = globalBounds(account);
            assertTrue("the stale pill must not collapse into a vertical border",
                staleBounds.width() > staleBounds.height());
            assertTrue("stale pill stays inside its card", cardBounds.contains(staleBounds));
            assertTrue("account text stays inside its card", cardBounds.contains(accountBounds));
        });
    }

    @Test public void largeFontLongTotalStaysOnOneReadableLine() {
        launch();
        String total = CurrencyHelper.amount(context, 49_842_060_000L);
        await(() -> findText(currentRoot(), total) != null, 10_000);
        scenario.onActivity(activity -> {
            TextView amount = findText(activity.getWindow().getDecorView(), total);
            assertNotNull(amount);
            assertEquals("the total must not split at a comma", 1, amount.getLineCount());
            Rect hero = globalBounds(activity.findViewById(R.id.dashboard_total_card));
            assertTrue("the total stays inside the hero card", hero.contains(globalBounds(amount)));
        });
    }

    @Test public void veryLargeFontStillKeepsTheCardReadable() throws Exception {
        shell("settings put system font_scale 3.0");
        SystemClock.sleep(500);
        launch();
        await(() -> findText(currentRoot(), "181 days") != null, 10_000);
        String total = CurrencyHelper.amount(context, 49_842_060_000L);
        await(() -> findText(currentRoot(), total) != null, 10_000);
        scenario.onActivity(activity -> {
            ViewGroup list = (ViewGroup) activity.findViewById(R.id.dashboard_bank_list);
            View card = list.getChildAt(0);
            TextView stale = findText(card, "181 days");
            TextView amount = findText(activity.getWindow().getDecorView(), total);
            assertNotNull(stale);
            assertNotNull(amount);
            assertTrue("very large text keeps the stale pill horizontal",
                globalBounds(stale).width() > globalBounds(stale).height());
            assertTrue("very large text keeps the stale pill in its card",
                globalBounds(card).contains(globalBounds(stale)));
            assertEquals("very large text still keeps the total together", 1,
                amount.getLineCount());
            assertEquals("very large text keeps every total digit visible", 0,
                amount.getLayout().getEllipsisCount(0));
            TextView unit = findText(activity.getWindow().getDecorView(), "Toman");
            assertNotNull(unit);
            assertEquals("very large text keeps the currency unit visible", 0,
                unit.getLayout().getEllipsisCount(0));
            TextView history = findText(activity.getWindow().getDecorView(),
                activity.getString(R.string.dashboard_open_history));
            assertNotNull(history);
            assertEquals("very large text keeps the hero action on one line", 0,
                history.getLayout().getEllipsisCount(0));
            assertTrue("very large text keeps the total in the hero",
                globalBounds(activity.findViewById(R.id.dashboard_total_card))
                    .contains(globalBounds(amount)));
        });
    }

    private void launch() {
        scenario = ActivityScenario.launch(MainActivity.class);
    }

    private void grantSms() {
        try {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .grantRuntimePermission(context.getPackageName(), Manifest.permission.READ_SMS);
        } catch (Exception ignored) { }
    }

    private void shell(String command) throws Exception {
        ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation().executeShellCommand(command);
        descriptor.close();
    }

    private View currentRoot() {
        final View[] root = {null};
        if (scenario != null) scenario.onActivity(a -> root[0] = a.getWindow().getDecorView());
        return root[0];
    }

    private static Rect globalBounds(View view) {
        Rect bounds = new Rect();
        view.getGlobalVisibleRect(bounds);
        return bounds;
    }

    private static void await(Check check, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (check.ok()) return;
            SystemClock.sleep(40);
        }
        assertTrue("dashboard did not settle", check.ok());
    }

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

    private interface Check { boolean ok(); }
}
