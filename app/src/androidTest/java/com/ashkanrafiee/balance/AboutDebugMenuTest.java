package com.ashkanrafiee.balance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.LocaleList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Seven taps on the About version unlock the hidden debug menu. */
@RunWith(AndroidJUnit4.class)
public class AboutDebugMenuTest {
    @Test public void versionSevenTaps_unlocksDebugMenu() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences(
            BalanceData.PREFS_PREF, Context.MODE_PRIVATE);
        boolean hadUnlockedPreference = prefs.contains(BalanceData.KEY_DEBUG_MENU_UNLOCKED);
        boolean wasUnlocked = BalanceData.isDebugMenuUnlocked(context);
        String originalLanguage = LocaleHelper.currentTag(context);
        LocaleList originalLocales = LocaleList.getDefault();
        try {
            for (String language : new String[]{"en", "fa"}) {
                LocaleHelper.setLanguage(context, language);
                prefs.edit().putBoolean(BalanceData.KEY_DEBUG_MENU_UNLOCKED, false).commit();
                try (ActivityScenario<AboutActivity> scenario =
                        ActivityScenario.launch(AboutActivity.class)) {
                    scenario.onActivity(activity -> {
                        String footerText = activity.getString(R.string.about_footer,
                            activity.appVersion());
                        TextView footer = findFooter(activity.findViewById(android.R.id.content),
                            footerText);
                        assertNotNull("about version footer", footer);
                        for (int i = 0; i < 6; i++) footer.performClick();
                        assertFalse(BalanceData.isDebugMenuUnlocked(context));
                        footer.performClick();
                    });
                }
                assertTrue(BalanceData.isDebugMenuUnlocked(context));
            }
        } finally {
            LocaleHelper.setLanguage(context, originalLanguage);
            LocaleList.setDefault(originalLocales);
            SharedPreferences.Editor restore = prefs.edit();
            if (hadUnlockedPreference) {
                restore.putBoolean(BalanceData.KEY_DEBUG_MENU_UNLOCKED, wasUnlocked);
            } else {
                restore.remove(BalanceData.KEY_DEBUG_MENU_UNLOCKED);
            }
            restore.commit();
        }
    }

    private static TextView findFooter(View root, String footerText) {
        if (root instanceof TextView
                && footerText.contentEquals(((TextView) root).getText())) {
            return (TextView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findFooter(group.getChildAt(i), footerText);
                if (found != null) return found;
            }
        }
        return null;
    }
}
