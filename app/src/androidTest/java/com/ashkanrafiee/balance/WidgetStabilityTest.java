package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.LinkedHashMap;

/** Corruption boundaries for the home-screen widget's encrypted balance store. */
@RunWith(AndroidJUnit4.class)
public class WidgetStabilityTest {
    private Context context;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        LockManager.disable(context);
        LockManager.resetSessionForTest();
        DataGeneration.clearForTests(context);
        BalanceData.reset(context, true);
        resetMaskDebounce();
    }

    @After public void tearDown() {
        LockManager.disable(context);
        LockManager.resetSessionForTest();
        BalanceData.reset(context, true);
        DataGeneration.clearForTests(context);
    }

    @Test public void refreshFrames_areBoundedAndRotated() {
        Bitmap[] frames = BalanceWidgetProvider.refreshFrames(context);
        assertEquals(8, frames.length);
        assertTrue(frames[0].getWidth() <= 128);
        assertTrue(frames[0].getHeight() <= 128);
        assertFalse("successive refresh frames should rotate the icon",
            frames[0].sameAs(frames[1]));
    }

    @Test public void widgetMask_masksHeaderTotalAndRows() throws Exception {
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        saved.put("Tejarat", new Bank("Tejarat", 1_000_000L, 1000L, "5000"));
        assertTrue(BalanceData.write(context, saved));

        BalanceData.setWidgetHidden(context, false);
        View unmasked = BalanceWidgetProvider.buildViews(context).apply(context, null);
        assertEquals(CurrencyHelper.amount(context, 1_000_000L),
            ((TextView) unmasked.findViewById(R.id.widget_total)).getText().toString());

        BalanceData.setWidgetHidden(context, true);
        try {
            View masked = BalanceWidgetProvider.buildViews(context).apply(context, null);
            assertEquals("\u2022\u2022\u2022\u2022\u2022\u2022",
                ((TextView) masked.findViewById(R.id.widget_total)).getText().toString());

            BalanceWidgetService.Factory factory = new BalanceWidgetService.Factory(context);
            factory.onDataSetChanged();
            View row = factory.getViewAt(0).apply(context, null);
            assertEquals("\u2022\u2022\u2022\u2022\u2022\u2022",
                ((TextView) row.findViewById(R.id.bank_amount)).getText().toString());
        } finally {
            BalanceData.setWidgetHidden(context, false);
        }
    }

    @Test public void maskToggle_flipsStateAndStopsRefreshAnimation() throws Exception {
        setRefreshAnimation(true);
        int genBefore = maskToggleGen();
        BalanceData.setWidgetHidden(context, false);

        new BalanceWidgetProvider().onReceive(context,
            new android.content.Intent(BalanceWidgetProvider.ACTION_MASK));

        assertEquals(true, BalanceData.isWidgetHidden(context));
        assertEquals(false, refreshAnimation());
        assertEquals(genBefore + 1, maskToggleGen());
    }

    @Test public void maskToggle_rapidDoubleTap_countsAsOneToggle() throws Exception {
        BalanceData.setWidgetHidden(context, false);
        BalanceWidgetProvider provider = new BalanceWidgetProvider();
        android.content.Intent mask =
            new android.content.Intent(BalanceWidgetProvider.ACTION_MASK);
        provider.onReceive(context, mask);
        provider.onReceive(context, mask);

        assertEquals("a bounced tap must toggle exactly once",
            true, BalanceData.isWidgetHidden(context));
    }

    @Test public void headerOnlyViews_maskTotalWithoutAdapterBinding() throws Exception {
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        saved.put("Tejarat", new Bank("Tejarat", 1_000_000L, 1000L, "5000"));
        assertTrue(BalanceData.write(context, saved));
        BalanceData.setWidgetHidden(context, true);
        try {
            View masked = BalanceWidgetProvider.buildViews(context, false)
                .apply(context, null);
            assertEquals("\u2022\u2022\u2022\u2022\u2022\u2022",
                ((TextView) masked.findViewById(R.id.widget_total)).getText().toString());
        } finally {
            BalanceData.setWidgetHidden(context, false);
        }
    }

    @Test public void corruptEncryptedStore_summaryShowsUnknownAndReadableError() throws Exception {
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        saved.put("Tejarat", new Bank("Tejarat", 12_345L, 1000L, "5000"));
        assertTrue(BalanceData.write(context, saved));
        DataGeneration.dataPrefs(context).edit()
            .putString(BalanceData.KEY_BALANCES, "not-an-encrypted-balance").commit();

        View root = BalanceWidgetProvider.buildViews(context).apply(context, null);
        assertEquals("—", ((TextView) root.findViewById(R.id.widget_total)).getText().toString());
        assertNotEquals("0", ((TextView) root.findViewById(R.id.widget_total)).getText().toString());
        TextView hint = root.findViewById(R.id.widget_hint);
        assertEquals(View.VISIBLE, hint.getVisibility());
        assertEquals(context.getString(R.string.status_sms_unreadable), hint.getText().toString());
    }

    @Test public void corruptEncryptedStore_datasetShowsErrorRowInsteadOfEmptyState() throws Exception {
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        saved.put("Tejarat", new Bank("Tejarat", 12_345L, 1000L, "5000"));
        assertTrue(BalanceData.write(context, saved));
        DataGeneration.dataPrefs(context).edit()
            .putString(BalanceData.KEY_BALANCES, "not-an-encrypted-balance").commit();

        BalanceWidgetService.Factory factory = new BalanceWidgetService.Factory(context);
        factory.onDataSetChanged();
        assertEquals(1, factory.getCount());
        View row = factory.getViewAt(0).apply(context, null);
        assertEquals(context.getString(R.string.status_sms_unreadable),
            ((TextView) row.findViewById(R.id.widget_empty)).getText().toString());
    }

    @Test public void lockedWidgetDoesNotReadCorruptStoreOrExposeItsError() throws Exception {
        LockManager.enable(context, "1234", true, false);
        DataGeneration.dataPrefs(context).edit()
            .putString(BalanceData.KEY_BALANCES, "not-an-encrypted-balance").commit();

        View root = BalanceWidgetProvider.buildViews(context).apply(context, null);
        assertTrue(containsText(root, context.getString(R.string.widget_locked_message)));

        BalanceWidgetService.Factory factory = new BalanceWidgetService.Factory(context);
        factory.onDataSetChanged();
        assertEquals(1, factory.getCount());
        View row = factory.getViewAt(0).apply(context, null);
        assertTrue(containsText(row, context.getString(R.string.widget_locked_message)));
    }

    private static void setRefreshAnimation(boolean value) throws Exception {
        java.lang.reflect.Field field =
            BalanceWidgetProvider.class.getDeclaredField("refreshAnimation");
        field.setAccessible(true);
        Object lock = lockObject();
        synchronized (lock) {
            field.setBoolean(null, value);
        }
    }

    private static boolean refreshAnimation() throws Exception {
        java.lang.reflect.Field field =
            BalanceWidgetProvider.class.getDeclaredField("refreshAnimation");
        field.setAccessible(true);
        synchronized (lockObject()) {
            return field.getBoolean(null);
        }
    }

    private static int maskToggleGen() throws Exception {
        java.lang.reflect.Field field =
            BalanceWidgetProvider.class.getDeclaredField("maskToggleGen");
        field.setAccessible(true);
        synchronized (lockObject()) {
            return field.getInt(null);
        }
    }

    private static void resetMaskDebounce() throws Exception {
        java.lang.reflect.Field field =
            BalanceWidgetProvider.class.getDeclaredField("lastMaskToggleMs");
        field.setAccessible(true);
        synchronized (lockObject()) {
            field.setLong(null, 0L);
        }
    }

    private static Object lockObject() throws Exception {
        java.lang.reflect.Field field =
            BalanceWidgetProvider.class.getDeclaredField("WIDGET_UPDATE_LOCK");
        field.setAccessible(true);
        return field.get(null);
    }

    private static boolean containsText(View view, String expected) {
        if (view instanceof TextView && expected.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                if (containsText(group.getChildAt(i), expected)) return true;
        }
        return false;
    }
}
