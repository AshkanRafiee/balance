package com.ashkanrafiee.balance;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.content.Context;
import android.graphics.Typeface;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The Persian UI typefaces: Vazirmatn in Persian, the untouched system path in English. */
@RunWith(AndroidJUnit4.class)
public class FontsTest {

    private Context ctx;

    @Before public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ctx.getSharedPreferences("balance_language", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() {
        ctx.getSharedPreferences("balance_language", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void englishKeepsTheSystemPath() {
        LocaleHelper.setLanguage(ctx, "en");
        assertNull(Fonts.text(ctx));
        assertNotNull(Fonts.medium(ctx));
        assertNotNull(Fonts.paint(ctx));
        assertNotNull(Fonts.amount(ctx));
    }

    @Test public void persianReadsVazirmatn() {
        LocaleHelper.setLanguage(ctx, "fa");
        assertNotNull(Fonts.text(ctx));
        assertNotNull(Fonts.medium(ctx));
        assertNotNull(Fonts.paint(ctx));
        assertNotNull(Fonts.amount(ctx));
        // The amount figures read in the bold cut, not the regular one.
        assertNotSame(Fonts.text(ctx), Fonts.amount(ctx));
        // Repeated reads share the loaded faces instead of parsing the font again.
        assertSame(Fonts.text(ctx), Fonts.text(ctx));
    }

    @Test public void boldCutDerivesFromTheFamily() {
        LocaleHelper.setLanguage(ctx, "fa");
        assertNotNull(Typeface.create(Fonts.text(ctx), Typeface.BOLD));
    }
}
