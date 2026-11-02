package com.ashkanrafiee.balance;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Reminder alarms for commitments.
 *
 *  <p>One alarm per reminding commitment, set for its next unsettled due minus the lead
 *  time the user chose — exact where the system allows exact alarms, inexact otherwise.
 *  Receivers persist a native-job handoff; a cancellable worker validates the current due,
 *  notifies and re-arms for the following due. Delivery remains subject to Android's
 *  background and battery restrictions. Recovery is local:
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
    private static final String KEY_OVERDUE_FIRED = "overdue_fired";
    private static final String KEY_SHOWN = "shown_due";
    private static final int PLAN_PAGE_SIZE = 16;
    /** Preference state only. Never hold this while touching CommitmentStore: its reads take
     * BalanceData.class, so the two locks cannot be acquired in the opposite order. */
    private static final Object STATE_LOCK = new Object();
    /** Prevents a stale definition snapshot from cancelling alarms armed by a newer pass. */
    private static final Object SCHEDULE_LOCK = new Object();
    static final String ACTION_REMIND = "com.ashkanrafiee.balance.COMMITMENT_REMIND";
    static final String EXTRA_ID = "commitment_id";

    /** Creates the notification channel on first use; a no-op everywhere it already exists. */
    static void ensureChannel(Context context) {
        ensureChannel(context, null);
    }

    private static void ensureChannel(Context context, CommitmentReminderJobs.Token token) {
        NotificationManager manager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;
        if (token != null) token.check();
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
        CommitmentReminderJobs.requestPlan(context);
    }

    static final class PlanPage {
        final boolean more;
        final long cursor;
        final Set<String> wanted;
        PlanPage(boolean more, long cursor, Set<String> wanted) {
            this.more = more;
            this.cursor = cursor;
            this.wanted = wanted;
        }
    }

    static PlanPage planPage(Context context, long cursor, Set<String> previous, boolean deliver,
            CommitmentReminderJobs.Token token) throws Exception {
        token.check();
        Set<String> wanted = new HashSet<>(previous);
        if (!notificationsAvailable(context)) return new PlanPage(false, cursor, new HashSet<>());
        CommitmentStore.DefinitionPage page;
        synchronized (BalanceData.class) {
            token.check(); // A Java monitor wait cannot be interrupted by JobService.onStopJob.
            BalanceData.ensureResetComplete(context);
            token.check();
            page = CommitmentStore.pageDefinitions(context, Math.max(0, cursor), PLAN_PAGE_SIZE);
            token.check();
        }
        // Page rows are used only for IDs. Never evaluate their lazy settlements outside a session.
        for (Commitment row : page.rows) {
            token.check();
            Projection projection;
            if (deliver) projection = fireAndPlan(context, row.id, token);
            else {
                projection = project(context, row.id, token);
                applyPlan(context, row.id, projection, token);
            }
            // Retain live notification owners too, even when their last alarm has fired.
            if (projection.definition != null && projection.definition.remind) wanted.add(row.id);
            else wanted.remove(row.id);
        }
        return new PlanPage(page.hasMore, page.nextId, wanted);
    }

    /** Revalidate disappearing owners: another job/write can run after generation validation. */
    static void finishPlan(Context context, Set<String> wanted,
            CommitmentReminderJobs.Token token) throws Exception {
        Set<String> cleanup;
        synchronized (SCHEDULE_LOCK) {
            token.check();
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            cleanup = copySet(prefs, KEY_SCHEDULED);
            for (String shown : copySet(prefs, KEY_SHOWN)) {
                int split = shown.lastIndexOf('|');
                if (split >= 0) cleanup.add(shown.substring(0, split));
            }
        }
        for (String id : cleanup) {
            token.check();
            if (wanted.contains(id)) continue;
            Projection current = project(context, id, token);
            if (!notificationsAvailable(context))
                current = new Projection(current.definition, null, null, false, true,
                    current.revision);
            applyPlan(context, id, current, token);
        }
    }

    /** Cancels every armed alarm and clears the armed-id snapshot. */
    private static void cancelAllLocked(Context context, AlarmManager alarms,
            SharedPreferences prefs) {
        synchronized (STATE_LOCK) {
            Set<String> ids = copySet(prefs, KEY_SCHEDULED);
            for (String shown : copySet(prefs, KEY_SHOWN)) {
                int split = shown.lastIndexOf('|');
                if (split >= 0) ids.add(shown.substring(0, split));
            }
            for (String id : ids)
                cancelScheduled(context, alarms, id, new CommitmentReminderJobs.Token());
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

    private static void arm(AlarmManager alarms, Context context, String id, long trigger,
            CommitmentReminderJobs.Token token) {
        boolean exact = canScheduleExact(context);
        token.check();
        PendingIntent operation = alarm(context, id);
        token.check();
        try {
            if (exact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger,
                    operation);
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger,
                    operation);
            }
        } catch (SecurityException exactPermissionChanged) {
            token.check();
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation);
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
        CommitmentReminderJobs.requestFire(context, id);
    }

    /** A due selected from a current definition, not from the stale alarm's old due date. */
    static void fireNow(Context context, String id, CommitmentReminderJobs.Token token)
            throws Exception {
        fireAndPlan(context, id, token);
    }

    private static Projection fireAndPlan(Context context, String id,
            CommitmentReminderJobs.Token token) throws Exception {
        Projection projection = project(context, id, token);
        if (!notificationsAvailable(context)) {
            applyPlan(context, id, new Projection(projection.definition, null, null, false,
                true, projection.revision), token);
            return projection;
        }
        if (projection.due != null
                && leadTime(projection.definition, projection.due) <= System.currentTimeMillis()) {
            synchronized (SCHEDULE_LOCK) {
                validate(context, projection, token);
                if (notifyDue(context, projection, token)) {
                    // Do not acknowledge a failed notification: the durable fire request retries.
                    token.check();
                    rememberFired(context, id, projection.due);
                }
            }
            projection = project(context, id, token);
        }
        applyPlan(context, id, projection, token);
        return projection;
    }

    /** Only detached scalars leave the active settlement session. */
    private static final class Projection {
        final Commitment definition;
        final Long due, trigger;
        final boolean clearOverdue, cancelNotice;
        final String revision;
        Projection(Commitment definition, Long due, Long trigger, boolean clearOverdue,
                boolean cancelNotice, String revision) {
            this.definition = definition;
            this.due = due;
            this.trigger = trigger;
            this.clearOverdue = clearOverdue;
            this.cancelNotice = cancelNotice;
            this.revision = revision;
        }
    }

    private static long leadTime(Commitment definition, long due) {
        // Dates and lead times are nonnegative, so subtraction cannot overflow a signed long.
        return due - Math.max(0L, definition.remindBeforeMs);
    }

    private static Projection project(Context context, String id,
            CommitmentReminderJobs.Token token) throws Exception {
        token.check();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> fired = copySet(prefs, KEY_FIRED);
        boolean overdueFired = overdueWasFired(prefs, id);
        Long shown = null;
        for (String value : copySet(prefs, KEY_SHOWN)) {
            int split = value.lastIndexOf('|');
            if (split >= 0 && id.equals(value.substring(0, split)))
                shown = Long.valueOf(value.substring(split + 1));
        }
        final Long shownDue = shown;
        CalendarSystem cal = RegionHelper.isIran(context) ? CalendarSystem.JALALI
            : CalendarSystem.GREGORIAN;
        long now = System.currentTimeMillis();
        Projection result;
        synchronized (BalanceData.class) {
            token.check();
            BalanceData.ensureResetComplete(context);
            token.check();
            result = CommitmentStore.withDefinition(context, id, c -> {
                token.check();
                Long due = reminderDue(c, cal, now, fired, overdueFired, token);
                Long trigger = due == null ? null : Math.max(now, leadTime(c, due));
                if (trigger == null && c.remind && hasDueBeyondWindow(c, cal, now))
                    trigger = now + 365L * 86400000L;
                boolean cancelNotice = !c.remind || shownDue != null && c.isSettled(shownDue);
                Commitment detached = new Commitment(c.id, c.name, c.amount, c.frequency,
                    c.start, c.end, c.done, null, c.legacyPaidThrough, null,
                    c.remind, c.remindBeforeMs);
                token.check();
                return new Projection(detached, due, trigger, !c.remind,
                    cancelNotice, null);
            });
            token.check();
            String revision = CommitmentStore.revision(context);
            if (revision == null) throw new IllegalStateException("commitment revision unavailable");
            result = result == null ? new Projection(null, null, null, true, true, revision)
                : new Projection(result.definition, result.due, result.trigger,
                    result.clearOverdue, result.cancelNotice, revision);
        }
        token.check();
        return result;
    }

    private static void validate(Context context, Projection projection,
            CommitmentReminderJobs.Token token) {
        token.check();
        if (!Objects.equals(projection.revision, CommitmentStore.revision(context)))
            throw new CancellationException("commitments changed during reminder planning");
        token.check();
    }

    private static void applyPlan(Context context, String id, Projection projection,
            CommitmentReminderJobs.Token token) throws Exception {
        synchronized (SCHEDULE_LOCK) {
            validate(context, projection, token);
            AlarmManager alarms = context.getSystemService(AlarmManager.class);
            if (alarms == null) throw new IllegalStateException("alarm service unavailable");
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (projection.cancelNotice) {
                validate(context, projection, token);
                cancelNotification(context, id, token);
            }
            token.check();
            if (projection.clearOverdue) clearOverdueFired(context, id);
            validate(context, projection, token);
            boolean armed = projection.trigger != null;
            if (armed) arm(alarms, context, id, projection.trigger, token);
            else cancelAlarm(context, alarms, id, token);
            synchronized (STATE_LOCK) {
                Set<String> scheduled = copySet(prefs, KEY_SCHEDULED);
                if (armed) scheduled.add(id);
                else scheduled.remove(id);
                token.check();
                if (!prefs.edit().putStringSet(KEY_SCHEDULED, scheduled).commit())
                    throw new IllegalStateException("reminder state persistence failed");
            }
        }
    }

    /** Finds the next unsettled due that has not already produced a notification. Overdue dues
     *  are considered first; once one fires, its {id,due} token prevents a killed/reopened app
     *  from producing the same reminder every minute. */
    static Long reminderDue(Commitment c, CalendarSystem cal, long now, Set<String> fired,
            boolean overdueFired, CommitmentReminderJobs.Token token) {
        if (c == null || !c.remind) return null;
        if (!overdueFired) {
            List<Long> overdue = new ArrayList<>();
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
                    token.check();
                    overdue.add(at);
                }, CommitmentsActivity.MAX_OVERDUE_OCCURRENCES);
            // Preserve precisely the old capped ascending occurrence window, but do not query
            // every old settlement when only its latest eligible due can be selected.
            for (int i = overdue.size() - 1; i >= 0; i--) {
                token.check();
                long at = overdue.get(i);
                if (!fired.contains(firedKey(c.id, at)) && !c.isSettled(at)) return at;
            }
        }
        for (long at : Commitment.occurrences(c, cal, Commitment.startOfDay(now),
                 Commitment.startOfDay(now) + 730L * 86400000L)) {
            token.check();
            if (!fired.contains(firedKey(c.id, at)) && !c.isSettled(at)) return at;
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

    private static void cancelScheduled(Context context, AlarmManager alarms, String id,
            CommitmentReminderJobs.Token token) {
        cancelAlarm(context, alarms, id, token);
        cancelNotification(context, id, token);
    }

    private static void cancelAlarm(Context context, AlarmManager alarms, String id,
            CommitmentReminderJobs.Token token) {
        token.check();
        PendingIntent operation = alarm(context, id);
        token.check();
        alarms.cancel(operation);
    }

    private static void cancelNotification(Context context, String id,
            CommitmentReminderJobs.Token token) {
        NotificationManager manager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        token.check();
        if (manager != null) manager.cancel("commitment:" + id, 1);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        synchronized (STATE_LOCK) {
            Set<String> shown = copySet(prefs, KEY_SHOWN);
            if (shown.removeIf(value -> value.startsWith(id + "|")))
                prefs.edit().putStringSet(KEY_SHOWN, shown).apply();
        }
    }

    private static void rememberFired(Context context, String id, long due) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        synchronized (STATE_LOCK) {
            Set<String> fired = copySet(prefs, KEY_FIRED);
            Set<String> overdue = copySet(prefs, KEY_OVERDUE_FIRED);
            Set<String> shown = copySet(prefs, KEY_SHOWN);
            shown.removeIf(value -> value.startsWith(id + "|"));
            shown.add(firedKey(id, due));
            fired.add(firedKey(id, due));
            if (due < Commitment.startOfDay(System.currentTimeMillis())) overdue.add(id);
            else overdue.remove(id);
            if (!prefs.edit().putStringSet(KEY_FIRED, fired)
                    .putStringSet(KEY_OVERDUE_FIRED, overdue)
                    .putStringSet(KEY_SHOWN, shown).commit())
                throw new IllegalStateException("reminder delivery persistence failed");
        }
    }

    private static Set<String> copySet(SharedPreferences prefs, String key) {
        return new HashSet<>(prefs.getStringSet(key, new HashSet<String>()));
    }

    private static boolean notificationsAvailable(Context context) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = (NotificationManager) context.getSystemService(
            Context.NOTIFICATION_SERVICE);
        if (manager == null) throw new IllegalStateException("notification service unavailable");
        if (!manager.areNotificationsEnabled()) return false;
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
        if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE)
            return false;
        return true;
    }

    private static boolean notifyDue(Context context, Projection projection,
            CommitmentReminderJobs.Token token) {
        Commitment c = projection.definition;
        long due = projection.due;
        token.check();
        if (!notificationsAvailable(context)) return false;
        token.check();
        ensureChannel(context, token);
        NotificationManager manager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) throw new IllegalStateException("notification service unavailable");
        Intent open = new Intent(context, CommitmentsActivity.class);
        open.setData(Uri.parse("balance://commitment/" + Uri.encode(c.id)));
        token.check();
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
        validate(context, projection, token);
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

}
