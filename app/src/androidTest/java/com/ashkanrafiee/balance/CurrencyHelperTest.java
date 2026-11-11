package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.LocaleList;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Covers the currency unit label: fixed presets, custom user text, and the toman default. */
@RunWith(AndroidJUnit4.class)
public class CurrencyHelperTest {

    private Context ctx;
    private String originalLanguage;
    private String originalCurrency;
    private LocaleList originalLocales;

    @Before public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalLanguage = ctx.getSharedPreferences("balance_language", Context.MODE_PRIVATE)
            .getString("language", null);
        originalCurrency = ctx.getSharedPreferences("balance_currency", Context.MODE_PRIVATE)
            .getString("currency", null);
        originalLocales = LocaleList.getDefault();
        LocaleHelper.setLanguage(ctx, "en");
        ctx.getSharedPreferences("balance_currency", Context.MODE_PRIVATE)
            .edit().remove("currency").commit();
    }

    @After public void tearDown() {
        try {
            SharedPreferences.Editor currency = ctx.getSharedPreferences(
                "balance_currency", Context.MODE_PRIVATE).edit();
            if (originalCurrency == null) currency.remove("currency");
            else currency.putString("currency", originalCurrency);
            currency.commit();
        } finally {
            try {
                SharedPreferences.Editor language = ctx.getSharedPreferences(
                    "balance_language", Context.MODE_PRIVATE).edit();
                if (originalLanguage == null) language.remove("language");
                else language.putString("language", originalLanguage);
                language.commit();
            } finally {
                LocaleList.setDefault(originalLocales);
            }
        }
    }

    @Test public void fixedIndex_roundTripsEveryCurrency() {
        assertEquals(0, CurrencyHelper.fixedIndex(CurrencyHelper.CURRENCY_TOMAN));
        assertEquals(1, CurrencyHelper.fixedIndex(CurrencyHelper.CURRENCY_RIAL));
        assertEquals(2, CurrencyHelper.fixedIndex(CurrencyHelper.CUSTOM_PREFIX + "Rupee"));
        for (int pos = 0; pos <= 1; pos++)
            assertEquals(pos, CurrencyHelper.fixedIndex(CurrencyHelper.fixedCurrency(pos)));
        assertEquals(CurrencyHelper.CURRENCY_TOMAN, CurrencyHelper.fixedCurrency(2));
        assertEquals(0, CurrencyHelper.fixedIndex(null));
    }

    @Test public void defaultIsToman() {
        assertEquals(CurrencyHelper.CURRENCY_TOMAN, CurrencyHelper.currency(ctx));
        assertFalse(CurrencyHelper.isCustom(ctx));
        assertEquals(ctx.getString(R.string.unit_toman), CurrencyHelper.label(ctx));
    }

    @Test public void fixedCurrencyCarriesItsOwnCode() {
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CURRENCY_RIAL);
        assertEquals(ctx.getString(R.string.unit_rial), CurrencyHelper.label(ctx));
        assertFalse(CurrencyHelper.isCustom(ctx));
    }

    @Test public void customCurrencyShowsTheTypedNameVerbatim() {
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CUSTOM_PREFIX + "Rupee");
        assertTrue(CurrencyHelper.isCustom(ctx));
        assertEquals("Rupee", CurrencyHelper.label(ctx));
        assertEquals("custom:Rupee", CurrencyHelper.currency(ctx));
    }

    @Test public void amount_dividesByTenOnlyForToman() {
        assertEquals("12,345", CurrencyHelper.amount(ctx, 123450));
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CURRENCY_RIAL);
        assertEquals("123,450", CurrencyHelper.amount(ctx, 123450));
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CUSTOM_PREFIX + "KW");
        assertEquals("123,450", CurrencyHelper.amount(ctx, 123450));
    }

    @Test public void amount_languageTransitions_refreshTheThreadFormatter() {
        boolean[] persian = {false, true, false};
        String[] toman = {"12,345", "۱۲٬۳۴۵", "12,345"};
        String[] rial = {"123,450", "۱۲۳٬۴۵۰", "123,450"};
        for (int i = 0; i < persian.length; i++) {
            assertEquals(toman[i], CurrencyHelper.amount(true, persian[i], 123450));
            assertEquals(rial[i], CurrencyHelper.amount(false, persian[i], 123450));
        }
    }
}
