package com.ashkanrafiee.balance;

import static org.junit.Assert.assertTrue;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class FigureCalendarTest {
    @Test public void figureDatesFollowTheSelectedCalendarAndLanguage() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        int region = RegionHelper.region(context);
        String language = LocaleHelper.currentTag(context);
        long at = new java.util.GregorianCalendar(2026, java.util.Calendar.MARCH, 21, 12, 0)
            .getTimeInMillis();
        try {
            LocaleHelper.setLanguage(context, "en");
            RegionHelper.setRegion(context, RegionHelper.REGION_IRAN);
            assertTrue(FigureExplanation.time(context, at).startsWith("1 Farvardin 1405"));
            RegionHelper.setRegion(context, RegionHelper.REGION_INTERNATIONAL);
            assertTrue(FigureExplanation.time(context, at).startsWith("21 March 2026"));
            LocaleHelper.setLanguage(context, "fa");
            RegionHelper.setRegion(context, RegionHelper.REGION_IRAN);
            assertTrue(FigureExplanation.time(context, at).startsWith("۱ فروردین ۱۴۰۵"));
        } finally {
            RegionHelper.setRegion(context, region);
            LocaleHelper.setLanguage(context, language);
        }
    }
}
