package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.LocaleList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.LinkedHashMap;

/** Privacy and corruption boundaries for the home-screen widget's encrypted balance store. */
@RunWith(AndroidJUnit4.class)
public class WidgetStabilityTest {
    private Context context;
    private LocaleList originalLocales;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalLocales = LocaleList.getDefault();
        // Disposable, serialized test data; establish the privacy state instead of inheriting it.
        LockManager.disable(context);
        LockManager.resetSessionForTest();
        DataGeneration.clearForTests(context);
        BalanceData.reset(context, true);
        BalanceData.setWidgetHidden(context, false);
        BalanceData.setSort(context, BalanceData.SORT_BALANCE_HIGH);
        resetMaskDebounce();
    }

    @After public void tearDown() {
        try {
            LockManager.disable(context);
            LockManager.resetSessionForTest();
            BalanceData.reset(context, true);
            DataGeneration.clearForTests(context);
        } finally {
            BalanceData.setWidgetHidden(context, false);
            LocaleList.setDefault(originalLocales);
        }
    }

    @Test public void refreshFrames_areBoundedAndRotated() {
        Bitmap[] frames = BalanceWidgetProvider.refreshFrames(context);
        assertEquals(8, frames.length);
        assertTrue(frames[0].getWidth() <= 128);
        assertTrue(frames[0].getHeight() <= 128);
        assertFalse("successive refresh frames should rotate the icon",
            frames[0].sameAs(frames[1]));
    }

    @Test public void widgetMask_masksHeaderTotalAndRows() throws Exception {
        LinkedHashMap<String, Bank> saved = privacyBanks();
        assertTrue(BalanceData.write(context, saved));

        View unmasked = BalanceWidgetProvider.buildViews(context).apply(context, null);
        assertVisibleText(unmasked, R.id.widget_total, CurrencyHelper.amount(context, privateTotal(saved)));
        BalanceWidgetService.Factory factory = new BalanceWidgetService.Factory(context);
        factory.onDataSetChanged();
        assertVisibleRows(factory, saved);

        BalanceData.setWidgetHidden(context, true);
        try {
            View masked = BalanceWidgetProvider.buildViews(context).apply(context, null);
            assertVisibleText(masked, R.id.widget_total, "\u2022\u2022\u2022\u2022\u2022\u2022");
            assertPrivateValuesAbsent(masked, saved);

            // A loaded adapter snapshot must also obey a mask change without a reload.
            for (int i = 0; i < factory.getCount(); i++) {
                View row = factory.getViewAt(i).apply(context, null);
                assertVisibleText(row, R.id.bank_amount, "\u2022\u2022\u2022\u2022\u2022\u2022");
                assertVisibleText(row, R.id.bank_account, "\u2022\u2022\u2022\u2022\u2022\u2022");
                assertPrivateValuesAbsent(row, saved);
            }
        } finally {
            BalanceData.setWidgetHidden(context, false);
        }
    }

    @Test public void maskToggle_flipsStateAndStopsRefreshAnimation() throws Exception {
        setRefreshAnimation(true);
        int genBefore = maskToggleGen();
        BalanceData.setWidgetHidden(context, false);

        new BalanceWidgetProvider().onReceive(context,
            new android.content.Intent(BalanceWidgetProvider.ACTION_MASK));

        assertEquals(true, BalanceData.isWidgetHidden(context));
        assertEquals(false, refreshAnimation());
        assertEquals(genBefore + 1, maskToggleGen());
    }

    @Test public void maskToggle_rapidDoubleTap_countsAsOneToggle() throws Exception {
        BalanceData.setWidgetHidden(context, false);
        BalanceWidgetProvider provider = new BalanceWidgetProvider();
        android.content.Intent mask =
            new android.content.Intent(BalanceWidgetProvider.ACTION_MASK);
        provider.onReceive(context, mask);
        provider.onReceive(context, mask);

        assertEquals("a bounced tap must toggle exactly once",
            true, BalanceData.isWidgetHidden(context));
    }

    @Test public void headerOnlyViews_masksTotal() throws Exception {
        LinkedHashMap<String, Bank> saved = privacyBanks();
        assertTrue(BalanceData.write(context, saved));
        View unmasked = BalanceWidgetProvider.buildViews(context, false).apply(context, null);
        assertVisibleText(unmasked, R.id.widget_total, CurrencyHelper.amount(context, privateTotal(saved)));
        BalanceData.setWidgetHidden(context, true);
        try {
            View masked = BalanceWidgetProvider.buildViews(context, false)
                .apply(context, null);
            assertVisibleText(masked, R.id.widget_total, "\u2022\u2022\u2022\u2022\u2022\u2022");
            assertPrivateValuesAbsent(masked, saved);
        } finally {
            BalanceData.setWidgetHidden(context, false);
        }
    }

    @Test public void corruptEncryptedStore_summaryShowsUnknownAndReadableError() throws Exception {
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        saved.put("Tejarat", new Bank("Tejarat", 12_345L, 1000L, "5000"));
        assertTrue(BalanceData.write(context, saved));
        DataGeneration.dataPrefs(context).edit()
            .putString(BalanceData.KEY_BALANCES, "not-an-encrypted-balance").commit();

        View root = BalanceWidgetProvider.buildViews(context).apply(context, null);
        assertEquals("—", ((TextView) root.findViewById(R.id.widget_total)).getText().toString());
        assertNotEquals("0", ((TextView) root.findViewById(R.id.widget_total)).getText().toString());
        TextView hint = root.findViewById(R.id.widget_hint);
        assertEquals(View.VISIBLE, hint.getVisibility());
        assertEquals(LocaleHelper.wrap(context).getString(R.string.status_sms_unreadable),
            hint.getText().toString());
    }

    @Test public void corruptEncryptedStore_datasetShowsErrorRowInsteadOfEmptyState() throws Exception {
        LinkedHashMap<String, Bank> saved = privacyBanks();
        assertTrue(BalanceData.write(context, saved));
        BalanceWidgetService.Factory factory = new BalanceWidgetService.Factory(context);
        factory.onDataSetChanged();
        assertVisibleRows(factory, saved);

        assertTrue(DataGeneration.dataPrefs(context).edit()
            .putString(BalanceData.KEY_BALANCES, "not-an-encrypted-balance").commit());
        factory.onDataSetChanged();
        assertEquals(1, factory.getCount());
        View row = factory.getViewAt(0).apply(context, null);
        assertEquals(LocaleHelper.wrap(context).getString(R.string.status_sms_unreadable),
            ((TextView) row.findViewById(R.id.widget_empty)).getText().toString());
        assertPrivateValuesAbsent(row, saved);
        assertFalse(containsTextOrDescription(row, LocaleHelper.wrap(context).getString(R.string.widget_empty)));
    }

    @Test public void lockedWidget_hidesLoadedBalancesAndCorruptStoreError() throws Exception {
        LinkedHashMap<String, Bank> saved = privacyBanks();
        assertTrue(BalanceData.write(context, saved));
        View unmasked = BalanceWidgetProvider.buildViews(context).apply(context, null);
        assertVisibleText(unmasked, R.id.widget_total, CurrencyHelper.amount(context, privateTotal(saved)));
        BalanceWidgetService.Factory factory = new BalanceWidgetService.Factory(context);
        factory.onDataSetChanged();
        assertVisibleRows(factory, saved);

        LockManager.enable(context, "1234", true, false);
        assertTrue(DataGeneration.dataPrefs(context).edit()
            .putString(BalanceData.KEY_BALANCES, "not-an-encrypted-balance").commit());

        View root = BalanceWidgetProvider.buildViews(context).apply(context, null);
        assertLockedPrivate(root, saved);

        // getViewAt must recheck the lock even before onDataSetChanged drops its old snapshot.
        for (int i = 0; i < saved.size(); i++)
            assertLockedPrivate(factory.getViewAt(i).apply(context, null), saved);

        factory.onDataSetChanged();
        assertEquals(1, factory.getCount());
        View row = factory.getViewAt(0).apply(context, null);
        assertLockedPrivate(row, saved);
    }

    private static LinkedHashMap<String, Bank> privacyBanks() {
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        // Descending balance order; distinct row amounts, accounts and aggregate total.
        saved.put("Saman|84620579", new Bank("Saman", 9_876_540L, now, "5001", "84620579"));
        saved.put("Tejarat|73190482", new Bank("Tejarat", 1_234_560L, now, "5000", "73190482"));
        return saved;
    }

    private static long privateTotal(LinkedHashMap<String, Bank> saved) {
        long total = 0;
        for (Bank bank : saved.values()) total += bank.amount;
        return total;
    }

    private void assertVisibleRows(BalanceWidgetService.Factory factory, LinkedHashMap<String, Bank> saved) {
        assertEquals(saved.size(), factory.getCount());
        int i = 0;
        Context localized = LocaleHelper.wrap(context);
        for (Bank bank : saved.values()) {
            View row = factory.getViewAt(i++).apply(context, null);
            assertVisibleText(row, R.id.bank_amount, CurrencyHelper.amount(localized, bank.amount));
            String account = LocaleHelper.isPersian(localized)
                ? HistoryActivity.faDigitsString(bank.account) : bank.account;
            assertVisibleText(row, R.id.bank_account,
                localized.getString(R.string.account_label) + " " + account);
        }
    }

    private void assertLockedPrivate(View root, LinkedHashMap<String, Bank> saved) {
        Context localized = LocaleHelper.wrap(context);
        // The lock message is inflated from XML by the launcher-like (unwrapped) host context.
        assertVisibleText(root, R.id.widget_locked_msg,
            root.getContext().getString(R.string.widget_locked_message));
        assertPrivateValuesAbsent(root, saved);
        for (Bank bank : saved.values()) {
            assertFalse("Private bank in locked widget text or description",
                containsTextOrDescription(root, BankRules.displayName(localized, bank.name)));
        }
        assertFalse(containsTextOrDescription(root, localized.getString(R.string.status_sms_unreadable)));
        assertFalse(containsTextOrDescription(root, localized.getString(R.string.widget_empty)));
        assertFalse(containsTextOrDescription(root, "not-an-encrypted-balance"));
    }

    private void assertPrivateValuesAbsent(View root, LinkedHashMap<String, Bank> saved) {
        assertAmountAbsent(root, privateTotal(saved));
        for (Bank bank : saved.values()) {
            assertAmountAbsent(root, bank.amount);
            assertFalse("Private account in widget text or description",
                containsTextOrDescription(root, bank.account));
            assertFalse("Private localized account in widget text or description",
                containsTextOrDescription(root, HistoryActivity.faDigitsString(bank.account)));
        }
    }

    private static void assertAmountAbsent(View root, long amount) {
        assertFalse("Raw private amount in widget text or description",
            containsTextOrDescription(root, Long.toString(amount)));
        assertFalse("Raw localized private amount in widget text or description",
            containsTextOrDescription(root, HistoryActivity.faDigitsString(Long.toString(amount))));
        for (boolean toman : new boolean[]{false, true}) {
            for (boolean persian : new boolean[]{false, true}) {
                assertFalse("Formatted private amount in widget text or description",
                    containsTextOrDescription(root, CurrencyHelper.amount(toman, persian, amount)));
            }
        }
    }

    private static void assertVisibleText(View root, int id, String expected) {
        TextView text = root.findViewById(id);
        assertNotNull(text);
        for (View current = text; current != null;
                current = current.getParent() instanceof View ? (View) current.getParent() : null) {
            assertEquals(View.VISIBLE, current.getVisibility());
        }
        assertEquals(expected, text.getText().toString());
    }

    private static void setRefreshAnimation(boolean value) throws Exception {
        java.lang.reflect.Field field =
            BalanceWidgetProvider.class.getDeclaredField("refreshAnimation");
        field.setAccessible(true);
        Object lock = lockObject();
        synchronized (lock) {
            field.setBoolean(null, value);
        }
    }

    private static boolean refreshAnimation() throws Exception {
        java.lang.reflect.Field field =
            BalanceWidgetProvider.class.getDeclaredField("refreshAnimation");
        field.setAccessible(true);
        synchronized (lockObject()) {
            return field.getBoolean(null);
        }
    }

    private static int maskToggleGen() throws Exception {
        java.lang.reflect.Field field =
            BalanceWidgetProvider.class.getDeclaredField("maskToggleGen");
        field.setAccessible(true);
        synchronized (lockObject()) {
            return field.getInt(null);
        }
    }

    private static void resetMaskDebounce() throws Exception {
        java.lang.reflect.Field field =
            BalanceWidgetProvider.class.getDeclaredField("lastMaskToggleMs");
        field.setAccessible(true);
        synchronized (lockObject()) {
            field.setLong(null, 0L);
        }
    }

    private static Object lockObject() throws Exception {
        java.lang.reflect.Field field =
            BalanceWidgetProvider.class.getDeclaredField("WIDGET_UPDATE_LOCK");
        field.setAccessible(true);
        return field.get(null);
    }

    private static boolean containsTextOrDescription(View view, String expected) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(expected)) return true;
        CharSequence description = view.getContentDescription();
        if (description != null && description.toString().contains(expected)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                if (containsTextOrDescription(group.getChildAt(i), expected)) return true;
        }
        return false;
    }
}
