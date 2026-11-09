package com.ashkanrafiee.balance;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class NotificationAmountsTest {
    private Context context;
    private String originalCurrency, originalLanguage;

    @Before public void before() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalCurrency = CurrencyHelper.currency(context);
        originalLanguage = LocaleHelper.currentTag(context);
        LocaleHelper.setLanguage(context, "en");
    }

    @After public void after() {
        CurrencyHelper.setCurrency(context, originalCurrency);
        LocaleHelper.setLanguage(context, originalLanguage);
    }

    @Test public void tomanKeepsEveryRialAndAnExplicitUnit() {
        CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_TOMAN);
        assertEquals("-0.1 " + CurrencyHelper.label(context), NotificationAmounts.format(context, -1));
        assertEquals("1,234.5 " + CurrencyHelper.label(context), NotificationAmounts.format(context, 12345));
        assertEquals("-922,337,203,685,477,580.8", NotificationAmounts.number(true, false, Long.MIN_VALUE));
        assertEquals("922,337,203,685,477,580.7", NotificationAmounts.number(true, false, Long.MAX_VALUE));
    }

    @Test public void rialAndCustomUnitsDoNotConvertOrHideTheUnit() {
        CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_RIAL);
        assertEquals("-12,345 " + CurrencyHelper.label(context), NotificationAmounts.format(context, -12345));
        CurrencyHelper.setCurrency(context, CurrencyHelper.CUSTOM_PREFIX + "credits");
        assertEquals("12,345 credits", NotificationAmounts.format(context, 12345));
    }

    @Test public void persianNumbersRetainTheFractionInsteadOfRoundingTinyAmountsToZero() {
        String persian = BalanceData.digits(NotificationAmounts.number(true, true, -1))
            .replace("\u066B", ".").replace("\u200E", "").replace("\u200F", "").replace("\u061C", "")
            .replace('\u2212', '-');
        assertEquals("-0.1", persian);
    }
}
