package com.ashkanrafiee.balance;

import com.ashkanrafiee.balance.parser.legacy.LegacyCalendarContext;
import com.ashkanrafiee.balance.parser.legacy.LegacyCalendarSystem;
import com.ashkanrafiee.balance.parser.legacy.LegacyMessageDate;
import java.util.Calendar;

/** Reads the bank's stated event time, falling back to arrival for implausible or absent dates. */
final class MessageDate {
    private MessageDate() {}

    static long eventTime(String body, long arrival, CalendarSystem cal) {
        // Capture the current device calendar, locale and time zone for each parse.
        return LegacyMessageDate.eventTime(body, arrival,
            cal == null ? null : LegacyCalendarSystem.valueOf(cal.name()),
            new LegacyCalendarContext(Calendar.getInstance()));
    }
}
