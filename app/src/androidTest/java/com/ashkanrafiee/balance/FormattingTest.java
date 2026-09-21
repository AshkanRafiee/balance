package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Pure-logic tests for the amount formatter ({@link BalanceData#toman}): the rial->toman halving,
 *  the thousands grouping and the Persian-digit style driven by the app language. No SMS involved. */
@RunWith(AndroidJUnit4.class)
public class FormattingTest {

    private Context ctx;
    private String originalTag;

    @Before public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalTag = LocaleHelper.currentTag(ctx);
    }

    @After public void tearDown() {
        LocaleHelper.setLanguage(ctx, originalTag);
    }

    @Test public void toman_en_halvesRials_withLatinGroupedDigits() {
        LocaleHelper.setLanguage(ctx, "en");
        assertEquals("125,000", BalanceData.toman(ctx, 1_250_000L));
        assertEquals("123,456", BalanceData.toman(ctx, 1_234_560L));
        assertEquals("-50", BalanceData.toman(ctx, -500L));
        assertEquals("0", BalanceData.toman(ctx, 5L));
    }

    @Test public void toman_fa_usesPersianDigitsAndGrouping() {
        LocaleHelper.setLanguage(ctx, "fa");
        assertEquals("۱۲۵٬۰۰۰", BalanceData.toman(ctx, 1_250_000L));
    }

    @Test public void toman_fa_largeAmount_groupedAndPersian() {
        LocaleHelper.setLanguage(ctx, "fa");
        assertEquals("۱۰۰٬۰۰۰٬۰۰۰٬۰۰۰٬۰۰۰", BalanceData.toman(ctx, 1_000_000_000_000_000L));
    }

    @Test public void toman_en_zero_and_small() {
        LocaleHelper.setLanguage(ctx, "en");
        assertEquals("0", BalanceData.toman(ctx, 0L));
        assertEquals("99", BalanceData.toman(ctx, 990L));
    }
}