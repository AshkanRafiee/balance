package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Raster checks exercise the vector at its real display sizes without changing app state. */
@RunWith(AndroidJUnit4.class)
public class BankIconTest {
    @Test public void mofidUsesItsOwnIcon_unknownBanksKeepInitialsFallback() {
        assertEquals(R.drawable.bank_mofid, BankIcon.iconFor("Mofid"));
        assertEquals(R.drawable.bank_melli, BankIcon.iconFor("Melli"));
        assertEquals(0, BankIcon.iconFor("Unknown bank"));
        assertEquals(0, BankIcon.iconFor(""));
        assertEquals(0, BankIcon.iconFor(null));
    }

    @Test public void mofidStrokesRemainVisibleAndUnclippedInLightMode() {
        verifyRasterSizes(false);
    }

    @Test public void mofidStrokesRemainVisibleAndUnclippedInDarkMode() {
        verifyRasterSizes(true);
    }

    private static void verifyRasterSizes(boolean night) {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
            | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        Context context = base.createConfigurationContext(configuration);
        int ink = context.getColor(R.color.bank_logo_ink);
        int panel = context.getColor(R.color.panel);
        assertTrue("Ink contrasts with the configured panel", contrast(ink, panel) >= 7.0);
        assertTrue("Ink changes polarity with the surface",
            night ? luminance(ink) > luminance(panel) : luminance(ink) < luminance(panel));

        // Exact raster pixels, independent of the emulator's display density.
        int[] sizes = {26, 34, 36, 48}; // Widget, history, dashboard, intrinsic icon.
        for (int size : sizes) {
            String label = (night ? "night" : "light") + " " + size + "px: ";
            Drawable drawable = context.getDrawable(BankIcon.iconFor("Mofid"));
            assertNotNull(label + "drawable inflates", drawable);
            int intrinsic = Math.round(48 * context.getResources().getDisplayMetrics().density);
            assertEquals(label + "48dp width", intrinsic, drawable.getIntrinsicWidth());
            assertEquals(label + "48dp height", intrinsic, drawable.getIntrinsicHeight());
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            try {
                drawable.setBounds(0, 0, size, size);
                drawable.draw(new Canvas(bitmap));
                verifyRaster(bitmap, ink, panel, label);
            } finally {
                bitmap.recycle();
            }
        }
    }

    private static void verifyRaster(Bitmap bitmap, int ink, int panel, String label) {
        int size = bitmap.getWidth();
        int left = size, top = size, right = -1, bottom = -1;
        int strongest = Color.TRANSPARENT;
        long alphaSum = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int pixel = bitmap.getPixel(x, y);
                int alpha = Color.alpha(pixel);
                alphaSum += alpha;
                if (alpha > Color.alpha(strongest)) strongest = pixel;
                if (x == 0 || y == 0 || x == size - 1 || y == size - 1) {
                    // Vector downsampling can leave a nearly transparent filtering fringe.
                    assertTrue(label + "no visible stroke clipped at outer edge", alpha < 16);
                }
                // Ignore nearly transparent antialiasing fringes when measuring the artwork.
                if (alpha >= 16) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        assertTrue(label + "visible stroke bounds", right >= left && bottom >= top);
        assertTrue(label + "enough visible ink", alphaSum / (255.0 * size * size) >= 0.01);
        assertTrue(label + "strokes survive downscaling", Color.alpha(strongest) >= 96);
        assertEquals(label + "horizontally centered", size / 2.0, (left + right + 1) / 2.0, 1.5);
        assertEquals(label + "vertically centered", size / 2.0, (top + bottom + 1) / 2.0, 1.5);
        double aspect = (right - left + 1.0) / (bottom - top + 1.0);
        assertTrue(label + "wide bull is not stretched: " + aspect, aspect >= 1.8 && aspect <= 2.8);
        assertTrue(label + "artwork uses the available width", right - left + 1 >= size * 0.65);

        // Unpremultiplication can round RGB channels on thin antialiased strokes.
        assertEquals(label + "red ink", Color.red(ink), Color.red(strongest), 8);
        assertEquals(label + "green ink", Color.green(ink), Color.green(strongest), 8);
        assertEquals(label + "blue ink", Color.blue(ink), Color.blue(strongest), 8);
        assertTrue(label + "rendered stroke contrasts with panel",
            contrast(overSurface(strongest, panel), panel) >= 2.0);
    }

    private static int overSurface(int foreground, int background) {
        double alpha = Color.alpha(foreground) / 255.0;
        return Color.rgb(
            (int) Math.round(Color.red(foreground) * alpha + Color.red(background) * (1 - alpha)),
            (int) Math.round(Color.green(foreground) * alpha + Color.green(background) * (1 - alpha)),
            (int) Math.round(Color.blue(foreground) * alpha + Color.blue(background) * (1 - alpha)));
    }

    private static double contrast(int first, int second) {
        double a = luminance(first), b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(int color) {
        return 0.2126 * linear(Color.red(color)) + 0.7152 * linear(Color.green(color))
            + 0.0722 * linear(Color.blue(color));
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
