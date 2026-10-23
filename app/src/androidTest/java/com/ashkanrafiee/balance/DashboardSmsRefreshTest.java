package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.provider.Telephony;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;

/** Verifies that a new SMS updates the visible dashboard without a manual refresh. */
@RunWith(AndroidJUnit4.class)
public final class DashboardSmsRefreshTest {
    private static final String SENDER = "500095";
    private static final String FIRST = "available balance 5,000,000";
    private static final String SECOND = "available balance 6,000,000";

    private Context context;
    private ActivityScenario<MainActivity> scenario;
    private LinkedHashMap<String, Bank> originalBalances;
    private java.util.Set<String> originalExcluded;
    private boolean originalOnboarding;
    private boolean originalShowCommitments;
    private boolean originalHidden;
    private boolean originalAutoHide;
    private String originalLanguage;
    private String originalCurrency;
    private int originalRegion;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalBalances = BalanceData.read(context);
        originalExcluded = new HashSet<>(BalanceData.getExcluded(context));
        originalOnboarding = BalanceData.isOnboardingSeen(context);
        originalShowCommitments = BalanceData.getShowCommitments(context);
        originalHidden = BalanceData.isHidden(context);
        originalAutoHide = BalanceData.isAutoHide(context);
        originalLanguage = LocaleHelper.currentTag(context);
        originalCurrency = CurrencyHelper.currency(context);
        originalRegion = RegionHelper.region(context);

        LocaleHelper.setLanguage(context, "en");
        CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_RIAL);
        RegionHelper.setRegion(context, RegionHelper.REGION_INTERNATIONAL);
        BalanceData.setOnboardingSeen(context, true);
        BalanceData.setShowCommitments(context, false);
        setHidden(false);
        BalanceData.setAutoHide(context, false);
        BalanceData.setExcluded(context, new HashSet<>());
        BalanceData.reset(context, true);
        BalanceData.setOnboardingSeen(context, true);
        BalanceData.setShowCommitments(context, false);
        grantSms();
        exec("cmd appops set " + context.getPackageName()
            + " android:read_restricted_messages allow");
        clearInbox();
    }

    @After public void tearDown() throws Exception {
        if (scenario != null) {
            try {
                scenario.close();
            } catch (AssertionError activityDidNotDestroy) {
                // A scan callback can leave the scenario paused while its worker unwinds. Finish
                // the activity explicitly so the following provider cleanup cannot race the UI.
                try { scenario.onActivity(Activity::finish); } catch (Exception ignored) { }
            }
        }
        clearInbox();
        BalanceData.reset(context, true);
        BalanceData.write(context, originalBalances);
        BalanceData.setExcluded(context, originalExcluded);
        BalanceData.setOnboardingSeen(context, originalOnboarding);
        BalanceData.setShowCommitments(context, originalShowCommitments);
        setHidden(originalHidden);
        BalanceData.setAutoHide(context, originalAutoHide);
        LocaleHelper.setLanguage(context, originalLanguage);
        CurrencyHelper.setCurrency(context, originalCurrency);
        RegionHelper.setRegion(context, originalRegion);
    }

    @Test public void newMessageUpdatesDashboardWithoutRefreshTap() throws Exception {
        scenario = ActivityScenario.launch(MainActivity.class);
        await(() -> hasView(R.id.dashboard_empty_state), 20_000);

        long base = System.currentTimeMillis();
        seed(FIRST, base);
        await(() -> bankAmount() == 5_000_000L && hasView(R.id.dashboard_bank_list), 45_000);
        scenario.onActivity(activity -> {
            assertNotNull("the new bank is rendered in the native dashboard",
                activity.findViewById(R.id.dashboard_bank_list));
            assertTrue("the dashboard exposes the refreshed bank name",
                hasText(activity.getWindow().getDecorView(), "Saman"));
        });

        seed(SECOND, base + 2_000L);
        await(() -> bankAmount() == 6_000_000L, 45_000);
        assertEquals(6_000_000L, bankAmount());
    }

    private long bankAmount() {
        LinkedHashMap<String, Bank> banks = BalanceData.read(context);
        for (Bank bank : banks.values()) {
            if ("Saman".equals(bank.name)) return bank.amount;
        }
        return Long.MIN_VALUE;
    }

    private boolean hasView(int id) {
        final boolean[] found = {false};
        scenario.onActivity(activity -> found[0] = activity.findViewById(id) != null);
        return found[0];
    }

    private static boolean hasText(View view, String wanted) {
        if (view instanceof android.widget.TextView
                && wanted.equals(((android.widget.TextView) view).getText().toString())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasText(group.getChildAt(i), wanted)) return true;
            }
        }
        return false;
    }

    private void grantSms() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .grantRuntimePermission(context.getPackageName(), Manifest.permission.READ_SMS);
    }

    private void setHidden(boolean hidden) {
        context.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(BalanceData.KEY_HIDDEN, hidden).commit();
    }

    private void seed(String body, long base) throws Exception {
        String body64 = android.util.Base64.encodeToString(
            body.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        exec("am broadcast -n com.ashkanrafiee.smsinject/.SeedReceiver"
            + " -a com.ashkanrafiee.smsinject.SEED -e sender " + SENDER
            + " -e body64 " + body64 + " -e base " + base);
        awaitSms(body);
    }

    private void awaitSms(String body) throws Exception {
        long deadline = System.currentTimeMillis() + 45_000L;
        while (System.currentTimeMillis() < deadline) {
            try (android.database.Cursor cursor = context.getContentResolver().query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY},
                    null, null, null)) {
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        if (SENDER.equals(cursor.getString(0)) && body.equals(cursor.getString(1))) return;
                    }
                }
            }
            Thread.sleep(150L);
        }
        fail("seeded SMS did not arrive in time");
    }

    private void clearInbox() throws Exception {
        exec("am broadcast -n com.ashkanrafiee.smsinject/.SeedReceiver"
            + " -a com.ashkanrafiee.smsinject.CLEAR");
        long deadline = System.currentTimeMillis() + 45_000L;
        while (System.currentTimeMillis() < deadline) {
            try (android.database.Cursor cursor = context.getContentResolver().query(
                    Telephony.Sms.Inbox.CONTENT_URI, new String[]{Telephony.Sms._ID},
                    null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) return;
            }
            Thread.sleep(150L);
        }
        fail("SMS inbox did not clear in time");
    }

    private void exec(String command) throws Exception {
        android.os.ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation().executeShellCommand(command);
        try (InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[1024];
            while (input.read(buffer) >= 0) { }
        }
        Thread.sleep(200L);
    }

    private interface Check { boolean ok() throws Exception; }

    private void await(Check check, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (check.ok()) return;
            Thread.sleep(150L);
        }
        assertTrue("dashboard did not refresh in time", check.ok());
    }
}
