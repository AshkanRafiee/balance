package com.ashkanrafiee.balance;

import android.content.Context;

/**
 * The gate on features that work well enough to ship but are not settled enough to promise.
 *
 * <p>A gated feature is absent from the interface altogether until someone deliberately turns it
 * on, so an ordinary reader never meets a half-finished screen and never has to wonder whether the
 * thing in front of them is the supported one. Turning one on is deliberately awkward: hold the
 * About screen's version line for {@link #UNLOCK_MILLIS}. That is long enough that it cannot happen
 * by accident while scrolling or while the phone is pocketed, and short enough that someone who has
 * been told about it can do it without being told twice.</p>
 *
 * <p>The choice is a preference rather than a build flag, so the people testing a feature can turn
 * it back off, and so enabling it survives an upgrade instead of quietly resetting itself. Nothing
 * here changes what a feature does — it decides only whether the feature is offered.</p>
 */
final class Experimental {
    private static final String PREFS = "balance_experimental";
    private static final String KEY_OWN_PACKS = "own_packs";

    /** How long the version line must be held before the gate opens. */
    static final long UNLOCK_MILLIS = 10_000L;

    private Experimental() {}

    /** Whether the reader has asked for the experimental own-packs screen. */
    static boolean ownPacksEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_OWN_PACKS, false);
    }

    static void setOwnPacksEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_OWN_PACKS, enabled).apply();
    }
}