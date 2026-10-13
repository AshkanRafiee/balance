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
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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

    private static final String TAG = "CommitmentReminders";
    static final String CHANNEL_ID = "commitment_reminders";
    private static final String PREFS = "commitment_reminders";
    private static final String KEY_SCHEDULED = "scheduled_ids";
    private static final String KEY_FIRED = "fired_due";
    private static final String KEY_OVERDUE_FIRED = "overdue_fired";
    /** Preference state only. Never hold this while touching CommitmentStore: its reads take
     * BalanceData.class, so the two locks cannot be acquired in the opposite order. */
    private static final Object STATE_LOCK = new Object();
    /** Prevents a stale definition snapshot from cancelling alarms armed by a newer pass. */
    private static final Object SCHEDULE_LOCK = new Object();
    /** Receiver work is serialized and queued only briefly; a stuck/duplicated broadcast cannot
     * create an unbounded executor backlog. */
    private static final ThreadPoolExecutor RECEIVER_WORKER = new ThreadPoolExecutor(
        1, 1, 15L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
        new ThreadPoolExecutor.AbortPolicy());
    static {
        RECEIVER_WORKER.allowCoreThreadTimeOut(true);
    }
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
        try {
            return alarms != null && alarms.canScheduleExactAlarms();
        } catch (SecurityException denied) {
            return false;
        }
    }

    /** Re-arms every reminder from the stored commitments: schedules what is due ahead, cancels
     *  whatever is no longer wanted (deleted, settled, switched off, or rescheduled). */
    static void scheduleAll(Context context) {
        try {
            BalanceData.ensureResetComplete(context);
            synchronized (SCHEDULE_LOCK) {
                scheduleAllLocked(context);
            }
        } catch (Exception error) {
            // Alarm delivery is best effort. A launcher/ROM service failure must not crash a
            // screen or a reset; the next resume/boot retries the authoritative definitions.
            Log.w(TAG, "commitment reminder scheduling failed", error);
        }
    }

    private static void scheduleAllLocked(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!notificationsAvailable(context)) {
            cancelAllLocked(context, alarms, prefs);
            return;
        }
        boolean iran = RegionHelper.isIran(context);
        CalendarSystem cal = iran ? CalendarSystem.JALALI : CalendarSystem.GREGORIAN;
        long now = System.currentTimeMillis();
        Set<String> wanted = new HashSet<>();
        try {
            CommitmentStore.forEachDefinition(context, c -> {
                if (!c.remind) {
                    clearOverdueFired(context, c.id);
                    return;
                }
                Long due = reminderDue(context, c, cal, now);
                if (due == null) clearOverdueFired(context, c.id);
                Long at = due == null ? null : due - Math.max(0, c.remindBeforeMs);
                if (at == null && c.remind && hasDueBeyondWindow(c, cal, now))
                    at = now + 365L * 86400000L;
                if (at == null) return;
                // A due already inside its lead window reports now: nudge it a minute out so the
                // alarm still fires instead of being set in the past. Exact where the system
                // allows it, so a due-day reminder cannot slide; inexact otherwise, which still
                // fires the same day for these day-scale lead times.
                long trigger = at <= now ? now + 60_000L : at;
                if (arm(alarms, context, c.id, trigger)) wanted.add(c.id);
            });
        } catch (Exception error) {
            // Do not cancel the existing alarm set when a paged read fails halfway through. The
            // next app open/boot will retry against the authoritative store.
            Log.w(TAG, "commitment reminder read failed", error);
            return;
        }
        // AlarmManager mutations and the scheduled-id snapshot are one serialized state update,
        // but the database scan above intentionally happened without STATE_LOCK.
        synchronized (STATE_LOCK) {
            Set<String> previous = copySet(prefs, KEY_SCHEDULED);
            for (String id : previous) {
                if (!wanted.contains(id)) cancelScheduled(context, alarms, id);
            }
            prefs.edit().putStringSet(KEY_SCHEDULED, wanted).apply();
        }
    }

    /** Cancels every armed alarm and clears the armed-id snapshot. */
    private static void cancelAllLocked(Context context, AlarmManager alarms,
            SharedPreferences prefs) {
        synchronized (STATE_LOCK) {
            for (String id : copySet(prefs, KEY_SCHEDULED))
                cancelScheduled(context, alarms, id);
            prefs.edit().putStringSet(KEY_SCHEDULED, new HashSet<String>()).apply();
        }
    }

    /** Test-only: runs exactly what the notifications-unavailable branch of
     *  {@link #scheduleAll} does. Simulating unavailable notifications directly is not an
     *  option: revoking the permission kills the app process by design, taking the
     *  in-process test runner with it, and a disabled channel keeps its importance across
     *  delete/recreate, so it cannot be restored afterwards. */
    static void cancelAllForTest(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        synchronized (SCHEDULE_LOCK) {
            cancelAllLocked(context, alarms, prefs);
        }
    }

    private static boolean arm(AlarmManager alarms, Context context, String id, long trigger) {
        try {
            if (canScheduleExact(context)) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger,
                    alarm(context, id));
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger,
                    alarm(context, id));
            }
            return true;
        } catch (SecurityException exactPermissionChanged) {
            try {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, alarm(context, id));
                return true;
            } catch (RuntimeException error) {
                Log.w(TAG, "commitment reminder arm failed", error);
                return false;
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "commitment reminder arm failed", error);
            return false;
        }
    }

    private static PendingIntent alarm(Context context, String id) {
        Intent intent = new Intent(context, CommitmentAlarmReceiver.class)
            .setAction(ACTION_REMIND)
            .setData(Uri.parse("balance://commitment/" + Uri.encode(id)))
            .putExtra(EXTRA_ID, id);
        return PendingIntent.getBroadcast(context, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Fires one due reminder: notifies unless the commitment is gone or settled, then re-arms. */
    static void fire(Context context, String id) {
        try {
            BalanceData.ensureResetComplete(context);
            Commitment found = CommitmentStore.get(context, id);
            if (found != null && found.remind) {
                boolean iran = RegionHelper.isIran(context);
                CalendarSystem cal = iran ? CalendarSystem.JALALI : CalendarSystem.GREGORIAN;
                Long due = reminderDue(context, found, cal, System.currentTimeMillis());
                if (due != null && !found.isSettled(due)) {
                    try {
                        if (notifyDue(context, found, due, iran))
                            rememberFired(context, found.id, due);
                    } catch (Exception error) {
                        Log.w(TAG, "commitment reminder notification failed", error);
                    }
                }
            } else {
                cancelNotification(context, id);
            }
            // Keep this inside the failure boundary: a lazy settlement lookup, notification call,
            // preference mutation or alarm reschedule must never escape a BroadcastReceiver.
            scheduleAll(context);
        } catch (Exception error) {
            Log.w(TAG, "commitment reminder fire failed", error);
        }
    }

    /** Finds the next unsettled due that has not already produced a notification. Overdue dues
     *  are considered first; once one fires, its {id,due} token prevents a killed/reopened app
     *  from producing the same reminder every minute. */
    private static Long reminderDue(Context context, Commitment c, CalendarSystem cal, long now) {
        if (c == null || !c.remind) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!overdueWasFired(prefs, c.id)) {
            final Long[] latest = {null};
            long overdueEnd = Commitment.startOfDay(now) - 1;
            long spanDays = c.frequency == Commitment.WEEKLY
                ? CommitmentsActivity.MAX_OVERDUE_OCCURRENCES * 7L
                : c.frequency == Commitment.MONTHLY
                    ? CommitmentsActivity.MAX_OVERDUE_OCCURRENCES * 31L
                    : c.frequency == Commitment.YEARLY
                        ? CommitmentsActivity.MAX_OVERDUE_OCCURRENCES * 366L
                        : CommitmentsActivity.MAX_OVERDUE_OCCURRENCES;
            long span = spanDays * 86400000L;
            long from = Math.max(Commitment.startOfDay(c.start),
                Commitment.startOfDay(overdueEnd > span ? overdueEnd - span : 0));
            Commitment.visitOccurrences(c, cal, from, overdueEnd, at -> {
                    if (!c.isSettled(at) && !wasFired(prefs, c.id, at)) latest[0] = at;
                }, CommitmentsActivity.MAX_OVERDUE_OCCURRENCES);
            if (latest[0] != null) return latest[0];
        }
        for (long at : Commitment.occurrences(c, cal, Commitment.startOfDay(now),
                 Commitment.startOfDay(now) + 730L * 86400000L)) {
            if (!c.isSettled(at) && !wasFired(prefs, c.id, at)) return at;
        }
        return null;
    }

    /** Keeps long-range reminders alive until their real due date enters the planning window. */
    private static boolean hasDueBeyondWindow(Commitment c, CalendarSystem cal, long now) {
        long horizon = Commitment.startOfDay(now) + 730L * 86400000L;
        if (c.frequency == Commitment.ONCE)
            return !c.done && c.start >= horizon;
        if (c.end != null && c.end < horizon) return false;
        return !Commitment.occurrences(c, cal, horizon, horizon + 730L * 86400000L).isEmpty();
    }

    private static String firedKey(String id, long due) {
        return id + "|" + due;
    }

    private static boolean wasFired(SharedPreferences prefs, String id, long due) {
        return prefs.getStringSet(KEY_FIRED, new HashSet<String>()).contains(firedKey(id, due));
    }

    private static boolean overdueWasFired(SharedPreferences prefs, String id) {
        return prefs.getStringSet(KEY_OVERDUE_FIRED, new HashSet<String>()).contains(id);
    }

    private static void clearOverdueFired(Context context, String id) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        synchronized (STATE_LOCK) {
            Set<String> fired = copySet(prefs, KEY_OVERDUE_FIRED);
            if (fired.remove(id)) prefs.edit().putStringSet(KEY_OVERDUE_FIRED, fired).apply();
        }
    }

    /** Clears reminder suppression when a commitment's settlement state changes. */
    static void resetFired(Context context, String id) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        synchronized (STATE_LOCK) {
            Set<String> fired = copySet(prefs, KEY_FIRED);
            fired.removeIf(value -> value.startsWith(id + "|"));
            Set<String> overdue = copySet(prefs, KEY_OVERDUE_FIRED);
            overdue.remove(id);
            prefs.edit().putStringSet(KEY_FIRED, fired)
                .putStringSet(KEY_OVERDUE_FIRED, overdue).apply();
        }
    }

    private static void cancelScheduled(Context context, AlarmManager alarms, String id) {
        alarms.cancel(alarm(context, id));
        cancelNotification(context, id);
    }

    private static void cancelNotification(Context context, String id) {
        NotificationManager manager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel("commitment:" + id, 1);
    }

    private static void rememberFired(Context context, String id, long due) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        synchronized (STATE_LOCK) {
            Set<String> fired = copySet(prefs, KEY_FIRED);
            Set<String> overdue = copySet(prefs, KEY_OVERDUE_FIRED);
            fired.add(firedKey(id, due));
            while (fired.size() > Commitment.MAX_COMMITMENTS * 4) {
                String first = fired.iterator().next();
                fired.remove(first);
            }
            if (due < Commitment.startOfDay(System.currentTimeMillis())) overdue.add(id);
            else overdue.remove(id);
            prefs.edit().putStringSet(KEY_FIRED, fired)
                .putStringSet(KEY_OVERDUE_FIRED, overdue).apply();
        }
    }

    private static Set<String> copySet(SharedPreferences prefs, String key) {
        return new HashSet<>(prefs.getStringSet(key, new HashSet<String>()));
    }

    private static boolean notificationsAvailable(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
            NotificationManager manager = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
            if (manager == null || !manager.areNotificationsEnabled()) return false;
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE)
                return false;
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static boolean notifyDue(Context context, Commitment c, long due, boolean iran) {
        if (!notificationsAvailable(context)) return false;
        ensureChannel(context);
        NotificationManager manager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        Intent open = new Intent(context, CommitmentsActivity.class);
        open.setData(Uri.parse("balance://commitment/" + Uri.encode(c.id)));
        PendingIntent tap = PendingIntent.getActivity(context, c.id.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // Name every reminder: same-time dues used to post identical lines, distinguishable
        // only by tapping through. The detail line carries data only (no words), so it needs
        // no translation and reads correctly in both languages.
        String detail = notificationDetail(context, c, due);
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_balance_monochrome)
            .setContentTitle(notificationTitle(c))
            .setContentText(detail)
            .setContentIntent(tap)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_balance_monochrome)
                .setContentTitle(context.getString(R.string.app_name))
                .build())
            .setAutoCancel(true)
            .build();
        manager.notify("commitment:" + c.id, 1, notification);
        return true;
    }

    /** The notification title names the commitment, so same-time dues stay distinguishable. */
    static String notificationTitle(Commitment c) {
        if (c == null) throw new NullPointerException("commitment");
        return c.name;
    }

    /** Amount and due day as data only: localizable without a translatable sentence. */
    static String notificationDetail(Context context, Commitment c, long due) {
        if (c == null) throw new NullPointerException("commitment");
        return CurrencyHelper.display(context, c.amount) + " · " + dueText(context, due);
    }

    /** The due day written the way the commitments screen writes dates, in either calendar. */
    private static String dueText(Context context, long due) {
        boolean iran = RegionHelper.isIran(context);
        boolean persian = LocaleHelper.isPersian(context);
        CalendarSystem cal = iran ? CalendarSystem.JALALI : CalendarSystem.GREGORIAN;
        int[] civil = Commitment.civilDay(due, cal);
        String day = persian ? faDigits(String.valueOf(civil[2])) : String.valueOf(civil[2]);
        String year = persian ? faDigits(String.valueOf(civil[0])) : String.valueOf(civil[0]);
        return day + " " + CalDate.monthName(civil[1], iran, persian) + " " + year;
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
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        synchronized (STATE_LOCK) { return new ArrayList<>(copySet(prefs, KEY_SCHEDULED)); }
    }

    /** Runs receiver database work off the main thread while retaining the broadcast deadline. */
    static void runReceiverWork(BroadcastReceiver.PendingResult result, Runnable work) {
        try {
            RECEIVER_WORKER.execute(() -> {
                try {
                    work.run();
                } catch (Exception error) {
                    Log.w(TAG, "commitment receiver work failed", error);
                } finally {
                    result.finish();
                }
            });
        } catch (RuntimeException rejected) {
            result.finish();
            Log.w(TAG, "commitment receiver work rejected", rejected);
        }
    }
}
