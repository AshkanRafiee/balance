package com.ashkanrafiee.balance;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
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
    private Intent originalLaunchingIntent;
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
        try {
            if (scenario != null) {
                // ActivityScenario tracks lifecycle events by the launch intent's filter identity.
                // Real warm navigation correctly changes getIntent(); restore only for harness
                // cleanup so it recognizes the subsequent DESTROYED event.
                if (originalLaunchingIntent != null)
                    scenario.onActivity(activity -> activity.setIntent(new Intent(originalLaunchingIntent)));
                scenario.close();
            }
        } finally {
            LockManager.disable(context);
            LockManager.resetSessionForTest();
            CommitmentReminders.cancelAllForTest(context);
            CommitmentStore.clear(context);
        }
    }

    private void rememberLaunchingIntent() {
        scenario.onActivity(activity -> originalLaunchingIntent = new Intent(activity.getIntent()));
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
        rememberLaunchingIntent();
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
        rememberLaunchingIntent();
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
        awaitHighlightedRowVisible();
    }

    @Test public void warmIntentSelectsExactDueAndDeletionShowsAnUnavailableState() throws Exception {
        scenario = ActivityScenario.launch(CommitmentsActivity.class);
        rememberLaunchingIntent();
        scenario.onActivity(activity -> {
            assertNull(activity.findViewById(android.R.id.content).findViewWithTag(tag()));
            activity.onNewIntent(intent());
            assertFalse("warm navigation changes the launch filter identity used by the test harness",
                originalLaunchingIntent.filterEquals(activity.getIntent()));
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
        rememberLaunchingIntent();
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

    @Test public void warmReminderTapRevealsExactDueWhenAlreadyScrolledToLateMonths() throws Exception {
        long today = Commitment.startOfDay(System.currentTimeMillis());
        CommitmentStore.upsert(context, Commitment.create("Many upcoming dues", -1,
            Commitment.DAILY, today, null, false, 0));
        scenario = ActivityScenario.launch(CommitmentsActivity.class);
        rememberLaunchingIntent();
        awaitScrolledToLateMonths();
        scenario.onActivity(activity -> activity.onNewIntent(intent()));
        awaitHighlightedRowVisible();
    }

    private void awaitScrolledToLateMonths() {
        boolean[] scrolled = {false};
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (!scrolled[0] && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(activity -> {
                try {
                    View root = (View) field(activity, "root");
                    ScrollView scroll = (ScrollView) root.getParent();
                    if (root.isLayoutRequested() || scroll.isLayoutRequested()
                            || scroll.getHeight() <= 0 || root.getHeight() <= 2 * scroll.getHeight()) return;
                    // fullScroll() may animate, leaving scrollY at zero until the next frame.
                    // This fixture needs an already-scrolled warm screen, not an animation race.
                    scroll.scrollTo(0, root.getHeight());
                    scrolled[0] = scroll.getScrollY() > scroll.getHeight();
                } catch (Exception e) { throw new AssertionError(e); }
            });
            if (!scrolled[0]) SystemClock.sleep(20);
        }
        assertTrue("laid-out fixture must start well below the reminder card", scrolled[0]);
    }

    private void awaitHighlightedRowVisible() {
        boolean[] visible = {false};
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (!visible[0] && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(activity -> {
                View row = activity.findViewById(android.R.id.content).findViewWithTag(tag());
                android.graphics.Rect bounds = new android.graphics.Rect();
                visible[0] = row != null && row.getHeight() > 0 && row.getGlobalVisibleRect(bounds)
                    && bounds.height() == row.getHeight();
            });
            if (!visible[0]) SystemClock.sleep(20);
        }
        assertTrue("the exact highlighted due must be in the visible viewport", visible[0]);
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
