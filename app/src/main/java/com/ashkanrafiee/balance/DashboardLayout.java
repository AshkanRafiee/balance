package com.ashkanrafiee.balance;

import android.content.pm.PackageManager;

/** The dashboard's fixed vertical geometry, in dp.
 *
 *  <p>These numbers used to be spelled out at every draw, scroll and touch site, which meant the
 *  SMS strip below the total card would have had to be threaded through each of them by hand. The
 *  strip is the dashboard's explanation of why the balances on screen may be out of date: it shows
 *  only when saved data is displayed but SMS access is gone. While it is up, the banks section
 *  header and the list below it move down by exactly its height, so every site asks this class
 *  where things are instead of carrying its own copy.
 *
 *  <p>Pure arithmetic on purpose — the dashboard draws onto a {@code Canvas} by hand, and keeping
 *  the geometry free of any {@code View} lets the visibility rule and the shift be tested without
 *  launching an activity. */
final class DashboardLayout {
    static final float TOTAL_TOP = 120f, TOTAL_BOTTOM = 270f;
    /** The strip sits in the gap under the total card, clear of both it and the section header. */
    static final float BANNER_TOP = 284f, BANNER_H = 56f;
    static final float SECTION_HEADER_Y = 320f;
    static final float LIST_TOP = 352f;
    /** Everything above the list that does not scroll: title, total card, section header. */
    static final float CHROME_H = 440f;
    /** The sort button's tap band, as a half-height around the section-header baseline. */
    static final float SORT_BAND = 30f;

    private DashboardLayout() { }

    /** Whether the strip belongs on screen, given the current {@code READ_SMS} grant state.
     *
     *  <p>It takes the grant state rather than a pre-tested boolean on purpose. The strip shows when
     *  access is *not* granted, so a {@code boolean smsGranted} argument is trivially passed
     *  inverted — {@code != PERMISSION_GRANTED} evaluates to true precisely when access is denied —
     *  and the strip then silently never appears. Deriving the sense here makes the call site
     *  {@code checkSelfPermission(READ_SMS)} and nothing else to get wrong.
     *
     *  <p>Only when there is something on screen to be stale: with no saved balances the empty card
     *  in the list already says SMS access is needed and takes the same tap, and with access
     *  granted the strip would be pure noise. */
    static boolean smsBannerVisible(boolean hasBalances, int smsPermissionState) {
        return hasBalances && smsPermissionState != PackageManager.PERMISSION_GRANTED;
    }

    /** How far the banks section is pushed down by the strip, if it is showing. */
    static float shift(boolean banner) { return banner ? BANNER_H : 0f; }

    static float sectionHeaderY(boolean banner) { return SECTION_HEADER_Y + shift(banner); }

    static float listTop(boolean banner) { return LIST_TOP + shift(banner); }

    /** The non-scrolling height the list is measured against when deciding how far it can scroll. */
    static float chromeH(boolean banner) { return CHROME_H + shift(banner); }

    /** Whether a touch at {@code y} landed on the sort button's side of the section header. */
    static boolean inSortBand(float y, boolean banner) {
        float base = sectionHeaderY(banner);
        return y > base - SORT_BAND && y < base + SORT_BAND;
    }

    /** Whether a touch at {@code y} landed on the strip. False whenever the strip is not up, so a
     *  tap can never reach a strip that was never drawn. */
    static boolean inBanner(float y, boolean banner) {
        return banner && y >= BANNER_TOP && y <= BANNER_TOP + BANNER_H;
    }
}
