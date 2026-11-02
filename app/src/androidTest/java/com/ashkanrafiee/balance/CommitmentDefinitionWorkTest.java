package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Per-definition projections reuse one session without changing external lazy-list semantics. */
@RunWith(AndroidJUnit4.class)
public class CommitmentDefinitionWorkTest {
    private static final long T = 1_600_000_000_000L;
    private static final long DAY = 86_400_000L;
    private static final String ID = "old-daily";
    private Context context;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Keep the counting wrapper on the root layout without scheduling reminder work.
        DataGeneration.clearForTests(context);
        CommitmentStore.clear(context);
    }

    @After public void tearDown() throws Exception {
        try {
            CommitmentStore.clear(context);
        } finally {
            DataGeneration.clearForTests(context);
        }
    }

    @Test public void manySettlementChecksUseOneAccessAndRespectReopenedDues() throws Exception {
        List<Long> paid = new ArrayList<>();
        List<Long> unpaid = new ArrayList<>();
        for (int i = 0; i < 1200; i++) (i % 2 == 0 ? paid : unpaid).add(date(i));
        Commitment source = new Commitment(ID, "Old daily", -100L, Commitment.DAILY, T,
            null, false, paid, date(1199), unpaid, true, 0);
        assertTrue(CommitmentStore.upsert(context, source));
        CountingContext counted = new CountingContext(context);

        Integer settled = CommitmentStore.withDefinition(counted, ID, definition -> {
            assertTrue(Thread.holdsLock(BalanceData.class));
            assertTrue("paid history must stay lazy", definition.paid instanceof Commitment.SettlementList);
            assertTrue("unpaid history must stay lazy", definition.unpaid instanceof Commitment.SettlementList);
            int total = 0;
            for (int i = 0; i < 1200; i++) {
                boolean actual = definition.isSettled(date(i));
                assertEquals("a reopened due overrides the covering legacy watermark",
                    i % 2 == 0, actual);
                if (actual) total++;
            }
            assertTrue(definition.paid.contains(date(1198)));
            assertFalse(definition.paid.contains(date(1199)));
            assertTrue(definition.unpaid.contains(date(1199)));
            assertFalse("a date beyond the watermark has no settlement", definition.isSettled(date(1200)));
            return total;
        });

        assertEquals(Integer.valueOf(600), settled);
        // access() resolves these preferences once. Count this boundary rather than elapsed
        // time or platform-specific SQLite opens; no per-due session may pass this assertion.
        assertEquals("all 1200 due checks must share one store access", 1, counted.dataPreferenceReads);
        assertFalse("side effects after the projection must not retain the store monitor",
            Thread.holdsLock(BalanceData.class));
    }

    @Test public void missingDefinitionReturnsNullWithoutInvokingWork() throws Exception {
        CountingContext counted = new CountingContext(context);
        boolean[] called = {false};

        String result = CommitmentStore.withDefinition(counted, "missing", definition -> {
            called[0] = true;
            return definition.id;
        });

        assertNull(result);
        assertFalse(called[0]);
        assertEquals(1, counted.dataPreferenceReads);
        assertFalse(Thread.holdsLock(BalanceData.class));
    }

    @Test public void callbackFailureClosesSessionAndAllowsTheNextOperation() throws Exception {
        assertTrue(CommitmentStore.upsert(context, new Commitment(ID, "Old daily", -100L,
            Commitment.DAILY, T, null, false, Collections.emptyList(), true, 0)));
        CountingContext counted = new CountingContext(context);
        IOException expected = new IOException("projection failure");

        try {
            CommitmentStore.withDefinition(counted, ID, definition -> {
                assertFalse(definition.isSettled(T));
                throw expected;
            });
            fail("the callback failure must propagate");
        } catch (IOException actual) {
            assertSame(expected, actual);
        }
        assertFalse("a failed projection must release its monitor", Thread.holdsLock(BalanceData.class));

        assertTrue("a normal writer must work after callback cleanup", CommitmentStore.settle(context, ID, T));
        String result = CommitmentStore.withDefinition(counted, ID, definition -> {
            assertTrue("the next projection must see the new committed state", definition.isSettled(T));
            return definition.id;
        });
        assertEquals(ID, result);
        assertEquals("both projections need only their own single access", 2, counted.dataPreferenceReads);
        assertFalse(Thread.holdsLock(BalanceData.class));
    }

    private static long date(int day) { return T + day * DAY; }

    private static final class CountingContext extends ContextWrapper {
        int dataPreferenceReads;

        CountingContext(Context base) { super(base); }

        @Override public Context getApplicationContext() { return this; }

        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            if (BalanceData.PREFS_DATA.equals(name)) dataPreferenceReads++;
            return super.getSharedPreferences(name, mode);
        }
    }
}
