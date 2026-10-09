package com.ashkanrafiee.balance;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
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
        try (ActivityScenario<AboutActivity> scenario =
                ActivityScenario.launch(AboutActivity.class)) {
            scenario.onActivity(activity -> {
                TextView footer = findFooter(activity.findViewById(android.R.id.content));
                assertNotNull("about version footer", footer);
                for (int i = 0; i < 7; i++) footer.performClick();
            });
        }
        assertTrue(BalanceData.isDebugMenuUnlocked(context));
    }

    private static TextView findFooter(View root) {
        if (root instanceof TextView
                && ((TextView) root).getText().toString().startsWith("Balance · Version ")) {
            return (TextView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findFooter(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
