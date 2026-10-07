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
 *  <p>One inexact alarm per reminding commitment, set for its next unsettled due minus the lead
 *  time the user chose. Inexact on purpose: a day-scale finance reminder gains nothing from
 *  waking the phone at an exact minute, and the exact-alarm permission (a settings-page grant
 *  on Android 12+) would gate the whole feature behind a second system screen. When an alarm
 *  fires, the receiver notifies and re-arms for the following due, so a monthly series reminds
 *  every month with no ledger of past firings.
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

    /** Re-arms every reminder from the stored commitments: schedules what is due ahead, cancels
     *  whatever is no longer wanted (deleted, settled, switched off, or rescheduled). */
    static void scheduleAll(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        boolean iran = RegionHelper.isIran(context);
        CalendarSystem cal = iran ? CalendarSystem.JALALI : CalendarSystem.GREGORIAN;
        long now = System.currentTimeMillis();
        Set<String> wanted = new HashSet<>();
        for (Commitment c : BalanceData.readCommitments(context)) {
            Long at = Commitment.reminderAt(c, cal, now);
            if (at == null) continue;
            wanted.add(c.id);
            // A due already inside its lead window reports now: nudge it a minute out so the
            // alarm still fires instead of being set in the past.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                at <= now ? now + 60_000L : at, alarm(context, c.id));
        }
        SharedPreferences prefs =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
            Long due = Commitment.nextDue(found, cal, System.currentTimeMillis());
            if (due != null && !found.isSettled(due)) notifyDue(context, found, due, iran);
        }
        scheduleAll(context);
    }

    private static void notifyDue(Context context, Commitment c, long due, boolean iran) {
        ensureChannel(context);
        NotificationManager manager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
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

/** The alarm endpoint: hands the due id to {@link CommitmentReminders}. */
final class CommitmentAlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !CommitmentReminders.ACTION_REMIND.equals(intent.getAction())) {
            return;
        }
        String id = intent.getStringExtra(CommitmentReminders.EXTRA_ID);
        if (id == null) return;
        CommitmentReminders.fire(context, id);
    }
}

/** Re-arms every reminder after a reboot, when all alarms are gone with the clock. */
final class CommitmentBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        CommitmentReminders.scheduleAll(context);
    }
}
