package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Persistence and bounded-index tests for manually entered plans. */
@RunWith(AndroidJUnit4.class)
public class ScheduledPaymentStorageTest {
    private Context context;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() {
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
    }

    private ScheduledPayment plan(String id) {
        return new ScheduledPayment(id, "Plan " + id, ScheduledPayment.Type.SUBSCRIPTION,
            1000, CalendarSystem.GREGORIAN, 2024, 1, 31,
            ScheduledPayment.Frequency.MONTHLY, ScheduledPayment.EndMode.NEVER,
            99, 99, 99, 99);
    }

    @Test public void corruptPlannerBlobIsReportedWithoutReplacingTheBlob() {
        String corrupt = "{\"schema\":1,\"plans\":[{\"id\":null}]}";
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .putString(BalanceData.KEY_SCHEDULED_PAYMENTS, corrupt).commit();
        try {
            BalanceData.readScheduledPayments(context);
            fail("corrupt planner data must not read as an empty list");
        } catch (IllegalStateException expected) {
            // The important property is that the corrupt value remains available for recovery.
        }
        assertEquals(corrupt, context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE)
            .getString(BalanceData.KEY_SCHEDULED_PAYMENTS, null));
    }

    @Test public void openWeeklyPlansContinuePastTheOldFiveThousandSequenceLimit() {
        ScheduledPayment weekly = new ScheduledPayment("long-weekly", "Weekly",
            ScheduledPayment.Type.SUBSCRIPTION, 1000, CalendarSystem.GREGORIAN, 2024, 1, 1,
            ScheduledPayment.Frequency.WEEKLY, ScheduledPayment.EndMode.NEVER,
            0, 0, 0, 0);
        List<PaymentOccurrence> rows = ScheduledPayments.occurrences(
            Collections.singletonList(weekly), Long.MIN_VALUE, Long.MAX_VALUE);
        assertTrue(rows.size() > 5000);
        assertEquals(5000, rows.get(5000).sequence);
        assertEquals(ScheduledPayments.occurrenceDate(weekly, 5000), rows.get(5000).date);
    }

    @Test public void planCapRejectsInsteadOfTruncating() throws Exception {
        List<ScheduledPayment> plans = new ArrayList<>();
        for (int i = 0; i <= ScheduledPayments.MAX_PLANS; i++) plans.add(plan("p" + i));
        try {
            BalanceData.serializeScheduledPayments(plans);
            fail("the plan cap must reject the complete snapshot");
        } catch (IllegalArgumentException expected) {
            // No prefix of the supplied list may be serialized.
        }
    }

    @Test public void malformedInputCannotReceiveARandomDeserializedId() throws Exception {
        JSONObject item = plan("stable").toJson();
        item.remove("id");
        try {
            BalanceData.deserializeScheduledPayments(
                new JSONObject().put("schema", 1).put("plans", new org.json.JSONArray().put(item)).toString());
            fail("missing IDs must reject the section");
        } catch (IllegalArgumentException expected) {
            // A deserializer may not create a new identity for malformed persisted data.
        }
    }

    @Test public void metadataAndEndEditsKeepRecordedStates() {
        ScheduledPayment original = new ScheduledPayment("edit", "Old title", ScheduledPayment.Type.LOAN,
            1000, CalendarSystem.GREGORIAN, 2025, 1, 1,
            ScheduledPayment.Frequency.WEEKLY, ScheduledPayment.EndMode.COUNT,
            0, 0, 0, 5);
        original.setState(0, ScheduledPayment.STATE_PAID);
        BalanceData.writeScheduledPayments(context, Collections.singletonList(original));

        ScheduledPayment edited = new ScheduledPayment("edit", "New title", ScheduledPayment.Type.LOAN,
            2000, CalendarSystem.GREGORIAN, 2025, 1, 1,
            ScheduledPayment.Frequency.WEEKLY, ScheduledPayment.EndMode.DATE,
            2025, 2, 1, 0);
        BalanceData.saveScheduledPayment(context, edited);
        ScheduledPayment restored = BalanceData.readScheduledPayments(context).get(0);
        assertEquals("New title", restored.title);
        assertEquals(ScheduledPayment.STATE_PAID, restored.state(0));
    }

    @Test public void stopAfterTodayKeepsTodayAndPaidHistoryButHidesFuture() {
        ScheduledPayment plan = new ScheduledPayment("stop", "Stop", ScheduledPayment.Type.SUBSCRIPTION,
            1000, CalendarSystem.GREGORIAN, 2025, 1, 1,
            ScheduledPayment.Frequency.WEEKLY, ScheduledPayment.EndMode.COUNT,
            0, 0, 0, 10);
        plan.setState(0, ScheduledPayment.STATE_PAID);
        BalanceData.writeScheduledPayments(context, Collections.singletonList(plan));
        assertTrue(BalanceData.stopScheduledPayment(context, "stop",
            new ScheduledDate(CalendarSystem.GREGORIAN, 2025, 1, 8)));

        ScheduledPayment restored = BalanceData.readScheduledPayments(context).get(0);
        List<PaymentOccurrence> rows = ScheduledPayments.occurrences(
            Collections.singletonList(restored), Long.MIN_VALUE, Long.MAX_VALUE);
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).paid());
        assertEquals(1, rows.get(1).sequence);
        assertTrue(!restored.hasFutureOccurrences(new ScheduledDate(
            CalendarSystem.GREGORIAN, 2025, 1, 8)));
    }
}
