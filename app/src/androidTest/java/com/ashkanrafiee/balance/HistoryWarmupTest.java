package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/** The dashboard warm-up cache: hits describe the same view from unchanged stores, and ownership
 *  transfers exactly once. */
@RunWith(AndroidJUnit4.class)
public class HistoryWarmupTest {
    private Context context;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BalanceData.reset(context, true);
        HistoryWarmup.invalidate();
        LocaleHelper.setLanguage(context, "en");
        CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_RIAL);
    }

    @After public void tearDown() {
        HistoryWarmup.invalidate();
        BalanceData.reset(context, true);
    }

    @Test public void take_missesOnColdCacheAndHitsAfterWarm() throws Exception {
        seed();
        assertNull(takeDefault());
        HistoryWarmup.warmNow(context);
        HistoryReader.Result result = takeDefault();
        assertNotNull("a warm default view must be reusable", result);
        try {
            assertEquals(2L, result.movementCount);
        } finally {
            result.close();
        }
        // Ownership transferred: a second take misses and the screen would run a fresh pass.
        assertNull(takeDefault());
    }

    @Test public void take_missesAfterAStoreWrite() throws Exception {
        seed();
        HistoryWarmup.warmNow(context);
        assertNotNull(takeDefault());
        // A write bumps a store revision, so the next take must miss even though the scope matches.
        HistoryWarmup.warmNow(context);
        BalanceData.setNote(context, tx(1L), "edited");
        assertNull("a metadata write must invalidate the warm-up", takeDefault());
        HistoryWarmup.invalidate();
    }

    @Test public void take_missesForANarrowedScope() throws Exception {
        seed();
        HistoryWarmup.warmNow(context);
        HistoryReader.Result result = HistoryWarmup.take("Other", null, HistoryActivity.Filter.ALL,
            "", Collections.emptyList(), false, context);
        assertNull("a bank-narrowed view must not reuse the default warm-up", result);
        HistoryWarmup.invalidate();
    }

    private void seed() {
        List<Transaction> stored = new ArrayList<>();
        stored.add(tx(1L));
        stored.add(tx(2L));
        assertTrue(BalanceData.writeTransactions(context, stored));
    }

    private Transaction tx(long amount) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(2026, Calendar.SEPTEMBER, 15, 12, 0, 0);
        return new Transaction("Mellat", "111", calendar.getTimeInMillis(), amount, null,
            "sig-" + amount, "content-" + amount);
    }

    private HistoryReader.Result takeDefault() {
        return HistoryWarmup.take(null, null, HistoryActivity.Filter.ALL, "",
            Collections.emptyList(), RegionHelper.isIran(context), context);
    }
}
