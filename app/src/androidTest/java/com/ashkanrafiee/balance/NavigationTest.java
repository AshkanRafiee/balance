package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Tests the navigation bar's stable destinations, selection state, and touch target size. */
@RunWith(AndroidJUnit4.class)
public class NavigationTest {

    @Test public void itemsAreAccessibleAndSelectTheRequestedDestination() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicInteger selected = new AtomicInteger(-1);
        AtomicInteger initial = new AtomicInteger(-1);
        AtomicReference<BottomNavigation> navigation = new AtomicReference<>();
        AtomicReference<Boolean> itemsAccessible = new AtomicReference<>(true);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            BottomNavigation bar = new BottomNavigation(context, BottomNavigation.SAVINGS,
                selected::set);
            navigation.set(bar);
            initial.set(bar.selectedTab());
            int minHeight = (int) (48 * context.getResources().getDisplayMetrics().density + .5f);
            int minWidth = minHeight;
            for (int i = 0; i < 4; i++) {
                android.view.View item = bar.getChildAt(i);
                if (item == null || !item.isClickable() || !item.isFocusable()
                        || item.getMinimumHeight() < minHeight
                        || item.getMinimumWidth() < minWidth
                        || item.getContentDescription() == null
                        || item.getContentDescription().length() == 0) {
                    itemsAccessible.set(false);
                }
            }
            bar.getChildAt(BottomNavigation.SETTINGS).performClick();
        });

        assertEquals(BottomNavigation.SAVINGS, initial.get());
        assertTrue(itemsAccessible.get());
        assertEquals(BottomNavigation.SETTINGS, selected.get());
        assertEquals(BottomNavigation.SETTINGS, navigation.get().selectedTab());
    }

    @Test public void navigationSurfaceFillsItsParentWidth() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<BottomNavigation> navigation = new AtomicReference<>();
        AtomicReference<android.widget.FrameLayout> parent = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            android.widget.FrameLayout host = new android.widget.FrameLayout(context);
            BottomNavigation bar = new BottomNavigation(context, BottomNavigation.HOME, null);
            host.addView(bar, new android.widget.FrameLayout.LayoutParams(700, -2));
            int width = android.view.View.MeasureSpec.makeMeasureSpec(700, android.view.View.MeasureSpec.EXACTLY);
            int height = android.view.View.MeasureSpec.makeMeasureSpec(300, android.view.View.MeasureSpec.AT_MOST);
            host.measure(width, height);
            host.layout(0, 0, 700, host.getMeasuredHeight());
            navigation.set(bar);
            parent.set(host);
        });
        assertEquals(0, navigation.get().getLeft());
        assertEquals(parent.get().getWidth(), navigation.get().getRight());
        assertEquals(parent.get().getWidth(), navigation.get().getMeasuredWidth());
    }
}
