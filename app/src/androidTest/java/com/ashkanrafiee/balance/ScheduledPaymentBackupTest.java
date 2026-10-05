package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Backup merge tests for definitions, explicit unpaid states, and stop metadata. */
@RunWith(AndroidJUnit4.class)
public class ScheduledPaymentBackupTest {
    private static final String PASSWORD = "planner backup test password";
    private Context context;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() {
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
    }

    private File backupFile(String name) {
        File file = new File(context.getCacheDir(), name);
        file.delete();
        return file;
    }

    private ScheduledPayment weekly(String id) {
        return new ScheduledPayment(id, "Weekly", ScheduledPayment.Type.SUBSCRIPTION, 1000,
            CalendarSystem.GREGORIAN, 2025, 1, 1, ScheduledPayment.Frequency.WEEKLY,
            ScheduledPayment.EndMode.COUNT, 0, 0, 0, 8);
    }

    @Test public void encryptedRoundTripAfterResetKeepsStatesAndStopCutoff() throws Exception {
        ScheduledPayment plan = weekly("roundtrip");
        plan.setState(0, ScheduledPayment.STATE_PAID);
        plan.setStoppedAfter(new ScheduledDate(CalendarSystem.GREGORIAN, 2025, 1, 8));
        BalanceData.writeScheduledPayments(context, Collections.singletonList(plan));
        File file = backupFile("scheduled-roundtrip.balance");
        BackupManager.create(context, Uri.fromFile(file), PASSWORD);

        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
        BackupManager.restore(context, Uri.fromFile(file), PASSWORD);
        ScheduledPayment restored = BalanceData.readScheduledPayments(context).get(0);
        assertEquals(ScheduledPayment.STATE_PAID, restored.state(0));
        assertEquals(new ScheduledDate(CalendarSystem.GREGORIAN, 2025, 1, 8), restored.stoppedAfter);
        assertEquals(2, ScheduledPayments.occurrences(BalanceData.readScheduledPayments(context),
            Long.MIN_VALUE, Long.MAX_VALUE).size());
    }

    @Test public void localExplicitUnpaidWinsCompatibleBackupState() throws Exception {
        ScheduledPayment backupPlan = weekly("same");
        backupPlan.setState(0, ScheduledPayment.STATE_PAID);
        BalanceData.writeScheduledPayments(context, Collections.singletonList(backupPlan));
        File file = backupFile("scheduled-unpaid.balance");
        BackupManager.create(context, Uri.fromFile(file), PASSWORD);

        ScheduledPayment local = weekly("same");
        local.setState(0, ScheduledPayment.STATE_UNPAID);
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
        BalanceData.writeScheduledPayments(context, Collections.singletonList(local));
        BackupManager.restore(context, Uri.fromFile(file), PASSWORD);

        assertEquals(ScheduledPayment.STATE_UNPAID,
            BalanceData.readScheduledPayments(context).get(0).state(0));
    }

    @Test public void incompatibleScheduleDoesNotImportItsStates() throws Exception {
        ScheduledPayment backupPlan = weekly("changed");
        backupPlan.setState(0, ScheduledPayment.STATE_PAID);
        BalanceData.writeScheduledPayments(context, Collections.singletonList(backupPlan));
        File file = backupFile("scheduled-incompatible.balance");
        BackupManager.create(context, Uri.fromFile(file), PASSWORD);

        ScheduledPayment local = new ScheduledPayment("changed", "Local", ScheduledPayment.Type.SUBSCRIPTION,
            1000, CalendarSystem.GREGORIAN, 2025, 1, 1, ScheduledPayment.Frequency.MONTHLY,
            ScheduledPayment.EndMode.COUNT, 0, 0, 0, 8);
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
        BalanceData.writeScheduledPayments(context, Collections.singletonList(local));
        BackupManager.restore(context, Uri.fromFile(file), PASSWORD);

        List<ScheduledPayment> restored = BalanceData.readScheduledPayments(context);
        assertEquals(ScheduledPayment.Frequency.MONTHLY, restored.get(0).frequency);
        assertFalse(restored.get(0).state(0) == ScheduledPayment.STATE_PAID);
        assertTrue(restored.get(0).states.isEmpty());
    }
}
