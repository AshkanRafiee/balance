package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

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

    @Before public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ctx.getSharedPreferences("balance_currency", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() {
        ctx.getSharedPreferences("balance_currency", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void defaultIsToman() {
        assertEquals(CurrencyHelper.CURRENCY_TOMAN, CurrencyHelper.currency(ctx));
        assertFalse(CurrencyHelper.isCustom(ctx));
        assertEquals(ctx.getString(R.string.unit_toman), CurrencyHelper.label(ctx));
    }

    @Test public void fixedCurrenciesCarryTheirOwnCode() {
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CURRENCY_RIAL);
        assertEquals(ctx.getString(R.string.unit_rial), CurrencyHelper.label(ctx));
        assertFalse(CurrencyHelper.isCustom(ctx));
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CURRENCY_USD);
        assertEquals("USD", CurrencyHelper.label(ctx));
        assertFalse(CurrencyHelper.isCustom(ctx));
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CURRENCY_EUR);
        assertEquals("EUR", CurrencyHelper.label(ctx));
    }

    @Test public void customCurrencyShowsTheTypedNameVerbatim() {
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CUSTOM_PREFIX + "Rupee");
        assertTrue(CurrencyHelper.isCustom(ctx));
        assertEquals("Rupee", CurrencyHelper.label(ctx));
        assertEquals("custom:Rupee", CurrencyHelper.currency(ctx));
    }

    @Test public void amount_dividesByTenOnlyForToman() {
        assertEquals(BalanceData.toman(ctx, 123450), CurrencyHelper.amount(ctx, 123450));
        java.text.NumberFormat us = java.text.NumberFormat.getNumberInstance(java.util.Locale.US);
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CURRENCY_RIAL);
        assertEquals(us.format(123450), CurrencyHelper.amount(ctx, 123450));
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CURRENCY_USD);
        assertEquals(us.format(123450), CurrencyHelper.amount(ctx, 123450));
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CURRENCY_EUR);
        assertEquals(us.format(99900000), CurrencyHelper.amount(ctx, 99900000));
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CUSTOM_PREFIX + "KW");
        assertEquals(us.format(123450), CurrencyHelper.amount(ctx, 123450));
    }
}