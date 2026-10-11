package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Pins the phone/tablet boundary used by every declared activity. */
@RunWith(AndroidJUnit4.class)
public class LargeScreenOrientationTest {
    @Test public void phonesRemainPortrait() {
        Configuration configuration = new Configuration();
        configuration.smallestScreenWidthDp = 599;

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            LargeScreenOrientation.orientationFor(configuration));
    }

    @Test public void largeScreensRemainRotatable() {
        Configuration configuration = new Configuration();
        configuration.smallestScreenWidthDp = 600;

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            LargeScreenOrientation.orientationFor(configuration));
    }
}
