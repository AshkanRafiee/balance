package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.LocaleList;
import android.view.View;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class WidgetCashTest {
    private Context context;
    private LocaleList originalLocales;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalLocales = LocaleList.getDefault();
        reset();
    }

    @After public void tearDown() {
        try {
            reset();
        } finally {
            LocaleList.setDefault(originalLocales);
        }
    }

    private void reset() {
        LockManager.disable(context);
        LockManager.resetSessionForTest();
        DataGeneration.clearForTests(context);
        BalanceData.reset(context, true);
        // The SMS reset leaves cash data intact.
        assertTrue(DataGeneration.dataPrefs(context).edit().clear().commit());
        CashStore.setEnabled(context, true);
        BalanceData.setWidgetHidden(context, false);
        BalanceData.setSort(context, BalanceData.SORT_BALANCE_HIGH);
    }

    @Test public void enabledCashOnlyAppearsAfterRecordingEvenWhenAmountIsZero() {
        CashStore.setEnabled(context, true);
        assertTrue(BalanceWidgetService.widgetBanks(context).isEmpty());

        CashStore.setBalance(context, 0);
        List<Bank> rows = BalanceWidgetService.widgetBanks(context);
        assertEquals(1, rows.size());
        Bank cash = rows.get(0);
        assertEquals(CashStore.KEY, cash.name);
        assertEquals(0, cash.amount);
        assertTrue(cash.date > 0);

        CashStore.setEnabled(context, false);
        assertTrue(BalanceWidgetService.widgetBanks(context).isEmpty());
    }

    @Test public void cashIsSortedAndCountedWithSavedBanksWithoutSmsPermission() {
        LinkedHashMap<String, Bank> banks = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        banks.put("Mellat", new Bank("Mellat", 60_000, now, "5000"));
        banks.put("Saman", new Bank("Saman", 30_000, now, "5001"));
        banks.put("Melli", new Bank("Melli", 90_000, now, "5002"));
        assertTrue(BalanceData.write(context, banks));
        BalanceData.setExcluded(context, Collections.singleton("Melli"));
        CashStore.setEnabled(context, true);
        CashStore.setBalance(context, 40_000);

        Context withoutSms = denySms(context);
        Context themed = WidgetTheme.context(withoutSms);
        List<Bank> rows = BalanceWidgetService.widgetBanks(withoutSms);
        assertEquals(3, rows.size());
        assertEquals("Mellat", rows.get(0).name);
        assertEquals(CashStore.KEY, rows.get(1).name);
        assertEquals("Saman", rows.get(2).name);
        View header = BalanceWidgetProvider.buildViews(withoutSms, false).apply(context, null);
        assertEquals(CurrencyHelper.amount(themed, 130_000), text(header, R.id.widget_total));
        assertEquals(View.VISIBLE, header.findViewById(R.id.widget_hint).getVisibility());
        assertEquals(themed.getString(R.string.widget_permission_hint), text(header, R.id.widget_hint));

        CashStore.setEnabled(context, false);
        header = BalanceWidgetProvider.buildViews(withoutSms, false).apply(context, null);
        assertEquals(CurrencyHelper.amount(themed, 90_000), text(header, R.id.widget_total));
    }

    @Test public void recordedCashIsMaskedAndLockedLikeSavedBankRows() throws Exception {
        CashStore.setEnabled(context, true);
        CashStore.setBalance(context, 40_000);
        BalanceWidgetService.Factory factory = new BalanceWidgetService.Factory(context);
        factory.onDataSetChanged();
        assertEquals(1, factory.getCount());
        Context themed = WidgetTheme.context(context);
        View row = factory.getViewAt(0).apply(context, null);
        assertEquals(themed.getString(R.string.cash_label), text(row, R.id.bank_name));
        assertEquals(CurrencyHelper.amount(themed, 40_000), text(row, R.id.bank_amount));

        BalanceData.setWidgetHidden(context, true);
        View header = BalanceWidgetProvider.buildViews(context, false).apply(context, null);
        assertEquals("••••••", text(header, R.id.widget_total));
        assertEquals("••••••", text(factory.getViewAt(0).apply(context, null), R.id.bank_amount));

        BalanceData.setWidgetHidden(context, false);
        LockManager.enable(context, "1234", true, false);
        header = BalanceWidgetProvider.buildViews(context, false).apply(context, null);
        assertNull(header.findViewById(R.id.widget_total));
        row = factory.getViewAt(0).apply(context, null);
        assertNotNull(row.findViewById(R.id.widget_locked_msg));
        assertNull(row.findViewById(R.id.bank_amount));
    }

    @Test public void staleCashShowsItsAgeEvenWhenMasked() {
        int originalDays = BalanceData.getStaleDays(context);
        try {
            BalanceData.setStaleDays(context, 3);
            BalanceData.setWidgetHidden(context, true);
            long date = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8)
                - TimeUnit.HOURS.toMillis(1);
            Bank cash = new Bank(CashStore.KEY, 40_000, date, "");
            View row = BalanceWidgetService.bankViews(context, cash).apply(context, null);
            assertEquals(View.VISIBLE, row.findViewById(R.id.bank_account).getVisibility());
            assertEquals(WidgetTheme.context(context).getResources()
                .getQuantityString(R.plurals.stale_days, 8, 8), text(row, R.id.bank_account));
        } finally {
            BalanceData.setStaleDays(context, originalDays);
        }
    }

    @Test public void cashAndBankTotalRemainsExactBeyondLongRange() {
        LinkedHashMap<String, Bank> banks = new LinkedHashMap<>();
        banks.put("Mellat", new Bank("Mellat", Long.MAX_VALUE, System.currentTimeMillis(), "5000"));
        assertTrue(BalanceData.write(context, banks));
        CashStore.setBalance(context, 40_000);

        View header = BalanceWidgetProvider.buildViews(context, false).apply(context, null);
        assertEquals(CurrencyHelper.amountExact(WidgetTheme.context(context),
                new java.math.BigInteger("9223372036854815807")),
            text(header, R.id.widget_total));
    }

    private static Context denySms(Context context) {
        return new ContextWrapper(context) {
            @Override public int checkSelfPermission(String permission) {
                return Manifest.permission.READ_SMS.equals(permission)
                    ? PackageManager.PERMISSION_DENIED : super.checkSelfPermission(permission);
            }

            @Override public Context createConfigurationContext(Configuration configuration) {
                return denySms(super.createConfigurationContext(configuration));
            }
        };
    }

    private static String text(View root, int id) {
        return ((TextView) root.findViewById(id)).getText().toString();
    }
}
