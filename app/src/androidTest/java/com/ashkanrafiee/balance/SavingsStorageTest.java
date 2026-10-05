package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Encrypted storage, stable-ID editing and SMS-reset isolation for holdings. */
@RunWith(AndroidJUnit4.class)
public class SavingsStorageTest {
    private Context context;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BalanceData.writeSavingsAssets(context, Collections.emptyList());
    }

    @After public void tearDown() {
        BalanceData.writeSavingsAssets(context, Collections.emptyList());
    }

    private SavingsAsset asset(String id, long value) {
        return new SavingsAsset(id, "Holding", SavingsAsset.Kind.CURRENCY, 0, "", "USD",
            1_000, value);
    }

    @Test public void editAndDeleteUseStableID() {
        BalanceData.saveSavingsAsset(context, asset("holding", 10_000L));
        BalanceData.saveSavingsAsset(context, asset("holding", 12_000L));
        List<SavingsAsset> saved = BalanceData.readSavingsAssets(context);
        assertEquals(1, saved.size());
        assertEquals(12_000L, saved.get(0).unitValueRial);
        assertTrue(BalanceData.deleteSavingsAsset(context, "holding"));
        assertTrue(BalanceData.readSavingsAssets(context).isEmpty());
        assertFalse(BalanceData.deleteSavingsAsset(context, "holding"));
    }

    @Test public void malformedBlobIsNotTreatedAsEmpty() {
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .putString(BalanceData.KEY_SAVINGS_ASSETS, "{\"schema\":1,\"assets\":[null]}").commit();
        try {
            BalanceData.readSavingsAssets(context);
            fail("corrupt savings data must not be silently replaced");
        } catch (IllegalStateException expected) {
            // The raw value remains for recovery rather than being overwritten by a later save.
        }
    }

    @Test public void storageIsEncryptedAndPreservesExactQuantity() {
        SavingsAsset original = new SavingsAsset("precise", "Holding", SavingsAsset.Kind.CURRENCY,
            0, "", "USD", 1_234, 987_654L);
        BalanceData.writeSavingsAssets(context, Collections.singletonList(original));
        String raw = context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE)
            .getString(BalanceData.KEY_SAVINGS_ASSETS, null);
        assertTrue(raw != null && !raw.trim().startsWith("{"));
        SavingsAsset restored = BalanceData.readSavingsAssets(context).get(0);
        assertEquals(1_234L, restored.quantityScaled);
        assertEquals(987_654L, restored.unitValueRial);
    }

    @Test public void aggregateOverflowDoesNotReplacePreviousEncryptedSnapshot() {
        SavingsAsset old = new SavingsAsset("old", "Holding", SavingsAsset.Kind.CURRENCY,
            0, "", "USD", 1_000, 10_000L);
        BalanceData.writeSavingsAssets(context, Collections.singletonList(old));
        String before = context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE)
            .getString(BalanceData.KEY_SAVINGS_ASSETS, null);
        SavingsAsset first = new SavingsAsset("first", "Holding", SavingsAsset.Kind.CURRENCY,
            0, "", "USD", 1_000, Long.MAX_VALUE);
        SavingsAsset second = new SavingsAsset("second", "Holding", SavingsAsset.Kind.CURRENCY,
            0, "", "EUR", 1_000, Long.MAX_VALUE);
        try {
            BalanceData.writeSavingsAssets(context, Arrays.asList(first, second));
            fail("aggregate overflow accepted");
        } catch (IllegalStateException expected) {
            // The failed candidate must not mutate the last good snapshot.
        }
        assertEquals(before, context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE)
            .getString(BalanceData.KEY_SAVINGS_ASSETS, null));
        assertEquals("old", BalanceData.readSavingsAssets(context).get(0).id);
    }

    @Test public void maximumItemCountIsRejectedWithoutTruncation() throws Exception {
        SavingsAsset asset = new SavingsAsset("one", "Holding", SavingsAsset.Kind.CURRENCY,
            0, "", "USD", 1_000, 1L);
        try {
            BalanceData.serializeSavingsAssets(Collections.nCopies(SavingsAsset.MAX_ITEMS + 1, asset));
            fail("oversized savings snapshot accepted");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test public void smsResetPreservesManualAssets() {
        BalanceData.saveSavingsAsset(context, asset("keep", 10_000L));
        BalanceData.reset(context, true);
        assertEquals(1, BalanceData.readSavingsAssets(context).size());
    }
}
