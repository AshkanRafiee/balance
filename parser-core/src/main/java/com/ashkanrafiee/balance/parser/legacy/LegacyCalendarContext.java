package com.ashkanrafiee.balance.parser.legacy;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Captured legacy calendar factory context. The Android adapter supplies the device timezone
 * and locale at the operation boundary, rather than the core consulting global defaults.
 * Locale is significant: Calendar.getInstance can select a non-Gregorian calendar subclass.
 * A Calendar prototype additionally preserves that subclass, leniency and cutover behavior.
 */
public final class LegacyCalendarContext {
    private final Calendar prototype;

    public LegacyCalendarContext(TimeZone zone, Locale locale) {
        this(Calendar.getInstance((TimeZone) zone.clone(), locale));
    }

    public LegacyCalendarContext(Calendar prototype) {
        this.prototype = (Calendar) prototype.clone();
    }

    Calendar newCalendar() {
        return (Calendar) prototype.clone();
    }
}
