package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.LocaleList;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Covers the language setting: the wrapper that resolves the saved override, and the process-wide
 *  default locale it keeps in step with it.
 *
 *  <p>The default locale is a process-lifetime static, and it is what a view falls back to when it
 *  resolves its text direction from the locale, and what every
 *  {@code Calendar.getInstance(Locale.getDefault())} prints month names with. So a choice that was
 *  set but never undone went on deciding the direction and the month names of every screen until
 *  the process died — which is what "only right after I force-stopped the app" looks like. The
 *  tests below are what fixing that has to keep holding. */
@RunWith(AndroidJUnit4.class)
public class LocaleHelperTest {

    private Context ctx;
    /** What the device itself asked for. Read from the (never locale-wrapped) app context rather
     *  than from the process default, which is exactly the value under test. */
    private LocaleList systemLocales;

    @Before public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        systemLocales = ctx.getResources().getConfiguration().getLocales();
        ctx.getSharedPreferences("balance_language", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() {
        ctx.getSharedPreferences("balance_language", Context.MODE_PRIVATE).edit().clear().commit();
        LocaleList.setDefault(systemLocales);
    }

    private static String languageOf(Context c) {
        return c.getResources().getConfiguration().getLocales().get(0).getLanguage();
    }

    @Test public void freshInstallFollowsSystem() {
        assertEquals("", LocaleHelper.currentTag(ctx));
        assertEquals(ctx, LocaleHelper.wrap(ctx));
    }

    @Test public void overrideResolvesTheChosenLanguage() {
        LocaleHelper.setLanguage(ctx, "fa");
        assertEquals("fa", LocaleHelper.currentTag(ctx));
        assertEquals("fa", languageOf(LocaleHelper.wrap(ctx)));
        assertTrue(LocaleHelper.isPersian(ctx));

        LocaleHelper.setLanguage(ctx, "en");
        assertEquals("en", languageOf(LocaleHelper.wrap(ctx)));
    }

    @Test public void overrideMovesTheProcessDefault() {
        LocaleHelper.setLanguage(ctx, "fa");
        LocaleHelper.wrap(ctx);
        assertEquals("fa", LocaleList.getDefault().get(0).getLanguage());
    }

    /** The regression: going back to "follow the system" has to hand the device's own locale back
     *  instead of leaving the previous language's process default in place for the rest of the
     *  process. */
    @Test public void backToSystemDefaultRestoresTheProcessDefault() {
        LocaleHelper.setLanguage(ctx, "fa");
        LocaleHelper.wrap(ctx);
        assertEquals("fa", LocaleList.getDefault().get(0).getLanguage());

        LocaleHelper.setLanguage(ctx, "");
        LocaleHelper.wrap(ctx);
        assertEquals(systemLocales, LocaleList.getDefault());
    }

    /** …and the restored value is the device's locale, not a second copy of the override. */
    @Test public void systemDefaultMatchesTheDeviceConfiguration() {
        LocaleHelper.setLanguage(ctx, "");
        LocaleHelper.wrap(ctx);
        assertEquals(systemLocales, LocaleList.getDefault());
    }

    /** A switch in either direction has to take effect at once, with no process restart to make it
     *  stick — including back out to "follow the system" and in again. */
    @Test public void repeatedSwitchesTrackTheChoiceEveryTime() {
        for (int i = 0; i < 3; i++) {
            LocaleHelper.setLanguage(ctx, "fa");
            LocaleHelper.wrap(ctx);
            assertEquals("round " + i + " fa", "fa", LocaleList.getDefault().get(0).getLanguage());

            LocaleHelper.setLanguage(ctx, "en");
            LocaleHelper.wrap(ctx);
            assertEquals("round " + i + " en", "en", LocaleList.getDefault().get(0).getLanguage());

            LocaleHelper.setLanguage(ctx, "");
            LocaleHelper.wrap(ctx);
            assertEquals("round " + i + " system", systemLocales, LocaleList.getDefault());
        }
    }

    @Test public void displayNameIsNativeScript() {
        assertEquals("English", LocaleHelper.displayName("en"));
        assertEquals("فارسی", LocaleHelper.displayName("fa"));
    }
}
