package com.ashkanrafiee.balance;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;

/** Keeps the existing portrait behavior on phones while allowing large screens to rotate. */
final class LargeScreenOrientation {
    static final int LARGE_SCREEN_SMALLEST_WIDTH_DP = 600;

    private LargeScreenOrientation() { }

    /** Applies the policy before an activity builds its view hierarchy. */
    static void apply(Activity activity) {
        int orientation = orientationFor(activity.getResources().getConfiguration());
        if (activity.getRequestedOrientation() != orientation) {
            activity.setRequestedOrientation(orientation);
        }
    }

    static int orientationFor(Configuration configuration) {
        return configuration.smallestScreenWidthDp >= LARGE_SCREEN_SMALLEST_WIDTH_DP
            ? ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
    }
}
