package com.ashkanrafiee.balance;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reminder alarms for commitments.
 *
 *  <p>One alarm per reminding commitment, set for its next unsettled due minus the lead
 *  time the user chose — exact where the system allows exact alarms, inexact otherwise
 *  (which still fires the same day for these day-scale lead times). When an alarm
 *  fires, the receiver notifies and re-arms for the following due, so a monthly series reminds
 *  every month with no ledger of past firings. Battery savers cannot silently eat a series:
 *  everything is re-derived from the stored commitments on boot, on app open and on every
 *  commitment write, so a killed alarm is at most delayed until the next one of those.
 *
 *  <p>Which alarms are armed lives in a tiny private prefs file of scheduled ids, so deletions
 *  and switched-off reminders cancel their alarm instead of haunting the clock. Everything is
 *  re-derived from the stored commitments on boot, on app open and on every commitment write,
 *  so a missed re-arm can only delay a reminder until the next one of those, never lose it. */
final class CommitmentReminders {
    private CommitmentReminders() {}

    static final String CHANNEL_ID = "commitment_reminders";
    private static final String PREFS = "commitment_reminders";
    private static final String KEY_SCHEDULED = "scheduled_ids";
    private static final String KEY_FIRED = "fired_due";
    static final String ACTION_REMIND = "com.ashkanrafiee.balance.COMMITMENT_REMIND";
    static final String EXTRA_ID = "commitment_id";

    /** Creates the notification channel on first use; a no-op everywhere it already exists. */
    static void ensureChannel(Context context) {
        NotificationManager manager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
            context.getString(R.string.commitments_channel), NotificationManager.IMPORTANCE_DEFAULT));
    }

    /** Whether exact alarms may be used: below Android 12 there is nothing to ask, above it
     *  the system grants the permission unless the user revoked it. */
    static boolean canScheduleExact(Context context) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return alarms != null && alarms.canScheduleExactAlarms();
    }

    /** Re-arms every reminder from the stored commitments: schedules what is due ahead, cancels
     *  whatever is no longer wanted (deleted, settled, switched off, or rescheduled). */
    static void scheduleAll(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        boolean iran = RegionHelper.isIran(context);
        CalendarSystem cal = iran ? CalendarSystem.JALALI : CalendarSystem.GREGORIAN;
        long now = System.currentTimeMillis();
        Set<String> wanted = new HashSet<>();
        SharedPreferences prefs =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (Commitment c : BalanceData.readCommitments(context)) {
            Long due = reminderDue(context, c, cal, now);
            Long at = due == null ? null : due - Math.max(0, c.remindBeforeMs);
            if (at == null) continue;
            wanted.add(c.id);
            // A due already inside its lead window reports now: nudge it a minute out so the
            // alarm still fires instead of being set in the past. Exact where the system
            // allows it, so a due-day reminder cannot slide; inexact otherwise, which still
            // fires the same day for these day-scale lead times.
            long trigger = at <= now ? now + 60_000L : at;
            try {
                if (canScheduleExact(context)) {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger,
                        alarm(context, c.id));
                } else {
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger,
                        alarm(context, c.id));
                }
            } catch (SecurityException exactPermissionChanged) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger,
                    alarm(context, c.id));
            }
        }
        Set<String> previous = prefs.getStringSet(KEY_SCHEDULED, new HashSet<String>());
        for (String id : previous) {
            if (!wanted.contains(id)) alarms.cancel(alarm(context, id));
        }
        prefs.edit().putStringSet(KEY_SCHEDULED, wanted).apply();
    }

    private static PendingIntent alarm(Context context, String id) {
        Intent intent = new Intent(context, CommitmentAlarmReceiver.class)
            .setAction(ACTION_REMIND)
            .putExtra(EXTRA_ID, id);
        return PendingIntent.getBroadcast(context, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Fires one due reminder: notifies unless the commitment is gone or settled, then re-arms. */
    static void fire(Context context, String id) {
        Commitment found = null;
        for (Commitment c : BalanceData.readCommitments(context)) {
            if (c.id.equals(id)) found = c;
        }
        if (found != null && found.remind) {
            boolean iran = RegionHelper.isIran(context);
            CalendarSystem cal = iran ? CalendarSystem.JALALI : CalendarSystem.GREGORIAN;
            Long due = reminderDue(context, found, cal, System.currentTimeMillis());
            if (due != null && !found.isSettled(due)
                    && notifyDue(context, found, due, iran)) {
                rememberFired(context, found.id, due);
            }
        }
        scheduleAll(context);
    }

    /** Finds the next unsettled due that has not already produced a notification. Overdue dues
     *  are considered first; once one fires, its {id,due} token prevents a killed/reopened app
     *  from producing the same reminder every minute. */
    private static Long reminderDue(Context context, Commitment c, CalendarSystem cal, long now) {
        if (c == null || !c.remind) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (long at : Commitment.occurrences(c, cal,
                Math.min(c.start, Commitment.startOfDay(now) - 366L * 86400000L),
                Commitment.startOfDay(now) - 1)) {
            if (!c.isSettled(at) && !wasFired(prefs, c.id, at)) return at;
        }
        for (long at : Commitment.occurrences(c, cal, Commitment.startOfDay(now),
                Commitment.startOfDay(now) + 730L * 86400000L)) {
            if (!c.isSettled(at) && !wasFired(prefs, c.id, at)) return at;
        }
        return null;
    }

    private static String firedKey(String id, long due) {
        return id + "|" + due;
    }

    private static boolean wasFired(SharedPreferences prefs, String id, long due) {
        return prefs.getStringSet(KEY_FIRED, new HashSet<String>()).contains(firedKey(id, due));
    }

    private static void rememberFired(Context context, String id, long due) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> fired = new HashSet<>(prefs.getStringSet(KEY_FIRED, new HashSet<String>()));
        fired.add(firedKey(id, due));
        while (fired.size() > Commitment.MAX_COMMITMENTS * 4) {
            String first = fired.iterator().next();
            fired.remove(first);
        }
        prefs.edit().putStringSet(KEY_FIRED, fired).apply();
    }

    private static boolean notifyDue(Context context, Commitment c, long due, boolean iran) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        ensureChannel(context);
        NotificationManager manager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        boolean persian = LocaleHelper.isPersian(context);
        CalendarSystem cal = iran ? CalendarSystem.JALALI : CalendarSystem.GREGORIAN;
        int[] civil = Commitment.civilDay(due, cal);
        String when = (persian ? faDigits(String.valueOf(civil[2])) : String.valueOf(civil[2]))
            + " " + CalDate.monthName(civil[1], iran, persian);
        String mag = CurrencyHelper.amount(context, Math.abs(c.amount));
        String signed = (c.amount < 0 ? "−" : "+") + mag;
        Intent open = new Intent(context, CommitmentsActivity.class);
        PendingIntent tap = PendingIntent.getActivity(context, c.id.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(c.name)
            .setContentText(signed + " · " + when)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build();
        manager.notify("commitment:" + c.id, 1, notification);
        return true;
    }

    private static String faDigits(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '0' && c <= '9') b.append((char) ('۰' + c - '0'));
            else b.append(c);
        }
        return b.toString();
    }

    /** Test-only view of the armed alarm ids. */
    static List<String> scheduledIds(Context context) {
        return new ArrayList<>(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_SCHEDULED, new HashSet<String>()));
    }
}
