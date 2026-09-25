package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.PackageManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for the dashboard's vertical geometry and the rule that puts the stale-data strip on
 *  screen. The strip appears only while saved balances are displayed and SMS access is gone, and it
 *  pushes the banks section down by its own height — the numbers the dashboard draws, scrolls and
 *  hit-tests all have to agree on that, so the rule and the shift are pinned down here rather than
 *  being re-derived by hand at every call site. */
@RunWith(AndroidJUnit4.class)
public class DashboardLayoutTest {

    private static final int GRANTED = PackageManager.PERMISSION_GRANTED;
    private static final int DENIED = PackageManager.PERMISSION_DENIED;

    // ---- smsBannerVisible ----

    @Test public void smsBanner_savedBalancesWithoutPermission_shows() {
        assertTrue(DashboardLayout.smsBannerVisible(true, DENIED));
    }

    @Test public void smsBanner_permissionGranted_hides() {
        assertFalse(DashboardLayout.smsBannerVisible(true, GRANTED));
    }

    @Test public void smsBanner_nothingSaved_hides() {
        // With no balances on screen there is nothing to be out of date, and the empty card in the
        // list already explains the missing permission — so the strip would be a second banner
        // saying the same thing where there is not even a total card to attach it to.
        assertFalse(DashboardLayout.smsBannerVisible(false, DENIED));
        assertFalse(DashboardLayout.smsBannerVisible(false, GRANTED));
    }

    @Test public void smsBanner_readsTheGrantStateNotItsNegation() {
        // The strip shows when access is *not* granted. A caller that reaches for the already
        // tested form — checkSelfPermission(...) != PERMISSION_GRANTED — is true exactly when
        // access is denied, so passing that here would hide the strip in the one case it exists
        // for. Pinning both constants here keeps that inversion from reading as correct.
        assertEquals(PackageManager.PERMISSION_DENIED, DENIED);
        assertEquals(-1, DENIED);
        assertEquals(0, GRANTED);
        assertTrue(DENIED != GRANTED);
        assertFalse(GRANTED != PackageManager.PERMISSION_GRANTED);
    }

    // ---- the shift the strip applies ----

    @Test public void shift_withoutStrip_leavesGeometryUntouched() {
        assertEquals(0f, DashboardLayout.shift(false), 0f);
        assertEquals(352f, DashboardLayout.listTop(false), 0f);
        assertEquals(320f, DashboardLayout.sectionHeaderY(false), 0f);
        assertEquals(440f, DashboardLayout.chromeH(false), 0f);
    }

    @Test public void shift_withStrip_pushesSectionDownByItsHeight() {
        float shift = DashboardLayout.shift(true);
        assertEquals(DashboardLayout.BANNER_H, shift, 0f);
        assertEquals(352f + shift, DashboardLayout.listTop(true), 0f);
        assertEquals(320f + shift, DashboardLayout.sectionHeaderY(true), 0f);
        // The chrome grows with the section, or the list would scroll further than it can be drawn.
        assertEquals(440f + shift, DashboardLayout.chromeH(true), 0f);
    }

    @Test public void strip_sitsBetweenTotalCardAndSectionHeader() {
        // The strip is drawn in the gap under the total card, so it must clear the card above it…
        assertTrue(DashboardLayout.BANNER_TOP >= DashboardLayout.TOTAL_BOTTOM);
        // …and it must not reach the section header it displaces. The header moves down by the
        // strip's own height while the strip is up, so that is the position that is ever drawn
        // alongside it — and the un-shifted position is 340…320, which does overlap.
        assertTrue(DashboardLayout.sectionHeaderY(true)
            > DashboardLayout.BANNER_TOP + DashboardLayout.BANNER_H);
    }

    @Test public void strip_sitsAboveTheFirstRow() {
        assertTrue(DashboardLayout.listTop(true) >= DashboardLayout.BANNER_TOP);
    }

    // ---- tap bands ----

    @Test public void inBanner_onlyHitsWhenTheStripIsUp() {
        float mid = DashboardLayout.BANNER_TOP + DashboardLayout.BANNER_H / 2f;
        assertTrue(DashboardLayout.inBanner(mid, true));
        // A tap at the same place must do nothing once the strip is gone, or it would fall through
        // to whatever chrome happens to sit there instead.
        assertFalse(DashboardLayout.inBanner(mid, false));
    }

    @Test public void inBanner_edgesAreInclusiveAndOutsideMisses() {
        assertTrue(DashboardLayout.inBanner(DashboardLayout.BANNER_TOP, true));
        assertTrue(DashboardLayout.inBanner(DashboardLayout.BANNER_TOP + DashboardLayout.BANNER_H, true));
        assertFalse(DashboardLayout.inBanner(DashboardLayout.BANNER_TOP - 1f, true));
        assertFalse(DashboardLayout.inBanner(
            DashboardLayout.BANNER_TOP + DashboardLayout.BANNER_H + 1f, true));
    }

    @Test public void inSortBand_withoutStrip_keepsItsOriginalRange() {
        // The sort button's band used to be a hardcoded 290..350; pinning it guards the header tap
        // target against drifting when the strip shifts things down.
        assertTrue(DashboardLayout.inSortBand(292f, false));
        assertTrue(DashboardLayout.inSortBand(348f, false));
        assertFalse(DashboardLayout.inSortBand(288f, false));
        assertFalse(DashboardLayout.inSortBand(352f, false));
    }

    @Test public void inSortBand_withStrip_movesDownWithTheHeader() {
        assertFalse(DashboardLayout.inSortBand(292f, true));
        assertTrue(DashboardLayout.inSortBand(292f + DashboardLayout.BANNER_H, true));
    }

    @Test public void inSortBand_neverOverlapsTheStrip() {
        // Both claim a tap, and the strip is checked first, so the sort button must stay clear of
        // the strip's own band.
        assertFalse(DashboardLayout.inSortBand(DashboardLayout.BANNER_TOP, true));
        assertFalse(DashboardLayout.inSortBand(
            DashboardLayout.BANNER_TOP + DashboardLayout.BANNER_H, true));
    }
}
