package com.ashkanrafiee.balance;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CommitmentReminderNavigationTest {
    private Context context;
    private ActivityScenario<CommitmentsActivity> scenario;
    private Commitment commitment;
    private long due;

    @Before public void before() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        LockManager.disable(context);
        LockManager.resetSessionForTest();
        CommitmentStore.clear(context);
        due = Commitment.millisOf(2000, 1, 15, CalendarSystem.GREGORIAN);
        commitment = Commitment.create("Old paid due", -123L, Commitment.ONCE, due,
            null, false, 0);
        CommitmentStore.upsert(context, commitment);
        CommitmentStore.settle(context, commitment.id, due);
    }

    @After public void after() throws Exception {
        if (scenario != null) scenario.close();
        LockManager.disable(context);
        LockManager.resetSessionForTest();
        CommitmentReminders.cancelAllForTest(context);
        CommitmentStore.clear(context);
    }

    private Intent intent() {
        return CommitmentReminders.dueIntent(context, commitment.id, due)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private String tag() { return "reminder-due:" + commitment.id + ":" + due; }

    @Test public void pendingIntentIdentityIncludesTheExactDueNotJustTheDefinition() {
        Intent first = intent();
        Intent next = CommitmentReminders.dueIntent(context, commitment.id, due + 86400000L);
        assertFalse("updating a later reminder must not retarget an older tap", first.filterEquals(next));
        assertEquals(due, CommitmentsActivity.ReminderTarget.fromIntent(first).due);
        assertNull(CommitmentsActivity.ReminderTarget.fromIntent(new Intent(context,
            CommitmentsActivity.class).putExtra(CommitmentReminders.EXTRA_ID, commitment.id)));
        first.putExtra(CommitmentReminders.EXTRA_DUE, due + 86400000L);
        assertNull("mismatched identity/extras must not open a different due",
            CommitmentsActivity.ReminderTarget.fromIntent(first));
    }

    @Test public void coldOpenHighlightsOldPaidDueOutsideTheNormalVisibleRowsAndSurvivesRecreation() {
        scenario = ActivityScenario.launch(intent());
        scenario.onActivity(activity -> {
            View row = activity.findViewById(android.R.id.content).findViewWithTag(tag());
            assertNotNull(row);
            assertTrue(row.isSelected());
        });
        scenario.recreate();
        scenario.onActivity(activity -> assertNotNull(activity.findViewById(android.R.id.content)
            .findViewWithTag(tag())));
    }

    @Test public void coldLockedOpenWaitsForVerifiedUnlockBeforeHighlightingTheDue() {
        LockManager.enable(context, "navigation-password", false, false);
        LockManager.lockSession();
        scenario = ActivityScenario.launch(intent());
        scenario.onActivity(activity -> {
            assertTrue(LockManager.isSessionLocked());
            assertNull(activity.findViewById(android.R.id.content).findViewWithTag(tag()));
            try {
                LockOverlay overlay = (LockOverlay) field(activity, "lockOverlay");
                ((EditText) field(overlay, "passwordInput")).setText("navigation-password");
                Method submit = LockOverlay.class.getDeclaredMethod("submit");
                submit.setAccessible(true);
                submit.invoke(overlay);
            } catch (Exception e) { throw new AssertionError(e); }
        });
        long deadline = SystemClock.uptimeMillis() + 15000;
        while (LockManager.isSessionLocked() && SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(20);
        assertFalse("verification must finish", LockManager.isSessionLocked());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(activity -> assertNotNull(activity.findViewById(android.R.id.content)
            .findViewWithTag(tag())));
    }

    @Test public void warmIntentSelectsExactDueAndDeletionShowsAnUnavailableState() throws Exception {
        scenario = ActivityScenario.launch(CommitmentsActivity.class);
        scenario.onActivity(activity -> {
            assertNull(activity.findViewById(android.R.id.content).findViewWithTag(tag()));
            activity.onNewIntent(intent());
            assertNotNull(activity.findViewById(android.R.id.content).findViewWithTag(tag()));
        });
        CommitmentStore.delete(context, commitment.id);
        scenario.onActivity(activity -> {
            activity.onNewIntent(intent());
            View content = activity.findViewById(android.R.id.content);
            assertNull(content.findViewWithTag(tag()));
            assertNotNull(content.findViewWithTag("reminder-due-unavailable"));
        });
    }

    @Test public void warmLockedIntentKeepsItsExactTargetPendingBehindTheLock() {
        scenario = ActivityScenario.launch(CommitmentsActivity.class);
        LockManager.enable(context, "navigation-password", false, false);
        LockManager.lockSession();
        scenario.onActivity(activity -> {
            activity.onNewIntent(intent());
            assertNull(activity.findViewById(android.R.id.content).findViewWithTag(tag()));
            try {
                LockOverlay overlay = (LockOverlay) field(activity, "lockOverlay");
                assertEquals(View.VISIBLE, overlay.getVisibility());
                CommitmentsActivity.ReminderTarget target =
                    (CommitmentsActivity.ReminderTarget) field(activity, "reminderTarget");
                assertEquals(due, target.due);
                assertEquals(commitment.id, target.id);
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }

    @Test public void changedScheduleNeverFallsBackToAnotherInstallment() {
        long different = due + 86400000L;
        Commitment changed = Commitment.create("Changed", -1, Commitment.ONCE, different,
            null, false, 0).freezeSchedule(CalendarSystem.GREGORIAN, java.time.ZoneId.systemDefault());
        CommitmentsActivity.ReminderTarget target = new CommitmentsActivity.ReminderTarget(changed.id, due);
        assertNull(target.resolve(Collections.singletonList(changed)));
        assertNull(target.resolve(Collections.emptyList()));
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
}
