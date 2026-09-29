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
        assertEquals(BalanceData.toman(ctx, 123450), CurrencyHelper.amount(ctx, 123450));
        java.text.NumberFormat us = java.text.NumberFormat.getNumberInstance(java.util.Locale.US);
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CURRENCY_RIAL);
        assertEquals(us.format(123450), CurrencyHelper.amount(ctx, 123450));
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CUSTOM_PREFIX + "KW");
        assertEquals(us.format(123450), CurrencyHelper.amount(ctx, 123450));
    }

    @Test public void rialAmountsKeepTheChosenDenominationAndLabel() {
        assertEquals(CurrencyHelper.amount(ctx, 123450), CurrencyHelper.amount(ctx, "IRR", 123450));
        assertEquals(CurrencyHelper.amount(ctx, 123450), CurrencyHelper.amount(ctx, null, 123450));
        assertEquals(CurrencyHelper.label(ctx), CurrencyHelper.label(ctx, "IRR"));
        assertEquals(CurrencyHelper.label(ctx), CurrencyHelper.label(ctx, null));
        // The toman is a rial denomination, so it never reaches a foreign currency.
        assertEquals("USD", CurrencyHelper.label(ctx, "USD"));
    }

    @Test public void foreignAmountsAreShownAtTheirOwnScaleUnconverted() {
        java.text.NumberFormat us = java.text.NumberFormat.getNumberInstance(java.util.Locale.US);
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CURRENCY_TOMAN);
        // 1234 minor units of USD is 12.34, and no rial/toman conversion may touch it.
        assertEquals(us.format(new java.math.BigDecimal("12.34")),
            CurrencyHelper.amount(ctx, "USD", 1234));
        assertEquals(us.format(new java.math.BigDecimal("20")),
            CurrencyHelper.amount(ctx, "USD", 2000));
        // KWD carries three decimals; an unknown code is shown raw rather than guessed at.
        assertEquals(us.format(new java.math.BigDecimal("1.234")),
            CurrencyHelper.amount(ctx, "KWD", 1234));
        assertEquals(us.format(7), CurrencyHelper.amount(ctx, "XYZ", 7));
        assertEquals(2, CurrencyHelper.scaleOf("USD"));
        assertEquals(3, CurrencyHelper.scaleOf("KWD"));
        assertEquals(0, CurrencyHelper.scaleOf("IRR"));
        assertEquals(0, CurrencyHelper.scaleOf("XYZ"));
    }
}