package com.ashkanrafiee.balance;

/**
 * Persian (Jalali / Solar Hijri) calendar conversion utilities.
 *
 * <p>Implements the well-established arithmetic Persian calendar algorithm (the same proven integer
 * algorithm used by the widely deployed {@code jalaali} open-source library). The Persian calendar
 * has a fixed structure: the first six months (Farvardin..Shahrivar) have 31 days, the next five
 * (Mehr..Bahman) have 30 days, and Esfand (the twelfth month) has 29 days normally and 30 days in a
 * leap year. Leap years follow the arithmetic 33-year rule, so Esfand's length (and thus every
 * month/year boundary) is computed exactly rather than assumed.
 *
 * <p>All arithmetic is integer-based (no floating point). Both directions are exact and round-trip
 * correctly across the whole range of usable dates, which is why it is safe to use for grouping
 * transactions by Persian day / month / year.
 */
final class JalaliCalendar {
    final int year;
    final int month;
    final int day;

    private JalaliCalendar(int year, int month, int day) {
        this.year = year;
        this.month = month;
        this.day = day;
    }

    /** Converts a Gregorian {@code gYear}/{@code gMonth}/{@code gDay} (proleptic) to a Persian date. */
    static JalaliCalendar fromGregorian(int gYear, int gMonth, int gDay) {
        int[] j = d2j(g2d(gYear, gMonth, gDay));
        return new JalaliCalendar(j[0], j[1], j[2]);
    }

    /** Converts this Persian date back to a proleptic Gregorian {@code {year, month, day}} array. */
    int[] toGregorian() {
        return d2g(j2d(year, month, day));
    }

    /** Whether {@code year} is a Persian leap year (Esfand has 30 days). */
    static boolean isLeap(int year) {
        return jalCal(year)[0] == 0;
    }

    /** Number of days in Persian month {@code month} (1..12) of {@code year}. */
    static int daysInMonth(int year, int month) {
        if (month <= 6) return 31;
        if (month <= 11) return 30;
        return isLeap(year) ? 30 : 29;
    }

    // ====================================================================
    // Primitive arithmetic helpers (truncation toward zero, C-style).
    // ====================================================================

    private static long div(long a, long b) {
        return a / b;
    }

    private static long mod(long a, long b) {
        return a - (a / b) * b;
    }

    // ====================================================================
    // Gregorian (proleptic) <-> Julian Day Number
    // ====================================================================

    private static long g2d(int gy, int gm, int gd) {
        long d = div((gy + div(gm - 8, 6) + 100100) * 1461, 4)
            + div(153 * mod(gm + 9, 12) + 2, 5) + gd - 34840408;
        d = d - div(div(gy + 100100 + div(gm - 8, 6), 100) * 3, 4) + 752;
        return d;
    }

    private static int[] d2g(long jdn) {
        long j = 4L * jdn + 139361631L;
        j = j + div(div(4L * jdn + 183187720L, 146097) * 3, 4) * 4 - 3908;
        long i = div(mod(j, 1461), 4) * 5 + 308;
        long gd = div(mod(i, 153), 5) + 1;
        int gm = (int) mod(div(i, 153), 12) + 1;
        long gy = div(j, 1461) - 100100 + div(8 - gm, 6);
        return new int[]{(int) gy, gm, (int) gd};
    }

    // ====================================================================
    // Persian calendar arithmetic
    // ====================================================================

    /**
     * Returns {@code {leap, gy, march} } for a Persian year: 1 if leap, else 0; {@code gy} is the
     * corresponding Gregorian year; {@code march} is the day of March on which Nowruz (Farvardin 1)
     * falls.
     */
    private static int[] jalCal(int jy) {
        int[] breaks = {-61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210, 1635,
            2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178};
        int bl = breaks.length;
        int gy = jy + 621;
        int leapJ = -14;
        int jp = breaks[0];
        int jump = 0;
        int leap;
        int march;
        int n;
        for (int i = 1; i < bl; i++) {
            jp = breaks[i - 1];
            jump = breaks[i] - jp;
            if (jy < breaks[i]) break;
            leapJ += (int) (div(jump, 33) * 8 + div(mod(jump, 33), 4));
        }
        n = jy - jp;
        leapJ += (int) (div(n, 33) * 8 + div(mod(n, 33) + 3, 4));
        if (mod(jump, 33) == 4 && jump - n == 4) leapJ += 1;
        long leapG = div(gy, 4) - div((div(gy, 100) + 1) * 3, 4) - 150;
        march = (int) (20 + leapJ - leapG);
        if (jump - n < 6) n = (int) (n - jump + div(jump + 4, 33) * 33);
        leap = (int) mod(mod(n + 1, 33) - 1, 4);
        if (leap == -1) leap = 4;
        return new int[]{leap, gy, march};
    }

    /** Persian date -> Julian Day Number. */
    private static long j2d(int jy, int jm, int jd) {
        int[] r = jalCal(jy);
        return g2d(r[1], 3, r[2]) + (long) (jm - 1) * 31 - div(jm, 7) * (jm - 7) + jd - 1;
    }

    /** Julian Day Number -> Persian date {@code {year, month, day}}. */
    private static int[] d2j(long jdn) {
        int[] g = d2g(jdn);
        int gy = g[0];
        int jy = gy - 621;
        int[] r = jalCal(jy);
        long jdn1f = g2d(gy, 3, r[2]);
        long k = jdn - jdn1f;
        int jm;
        int jd;
        if (k >= 0) {
            if (k <= 185) {
                jm = 1 + (int) div(k, 31);
                jd = (int) mod(k, 31) + 1;
                return new int[]{jy, jm, jd};
            }
            k -= 186;
        } else {
            jy -= 1;
            k += 179;
            if (r[0] == 1) k += 1;
        }
        jm = 7 + (int) div(k, 30);
        jd = (int) mod(k, 30) + 1;
        return new int[]{jy, jm, jd};
    }
}
