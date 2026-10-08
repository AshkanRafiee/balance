package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/** Generation-safe paged transaction storage and migration from the legacy JSON value. */
@RunWith(AndroidJUnit4.class)
public class TransactionStoreTest {

    private Context context;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BalanceData.reset(context, true);
    }

    @After public void tearDown() {
        BalanceData.reset(context, true);
    }

    @Test public void pagedRoundTrip_preservesEveryTransactionField() {
        List<Transaction> input = new ArrayList<>();
        for (int i = 0; i < 2_050; i++) {
            input.add(new Transaction("Synthetic", i % 2 == 0 ? "account-" + i : null,
                2_000_000L + i, i % 3 == 0 ? -100_000L - i : 100_000L + i,
                i % 2 == 0 ? 9_000_000L + i : null,
                i % 3 == 0 ? "signature-" + i : null,
                i % 4 == 0 ? "content-" + i : null));
        }

        assertEquals(true, BalanceData.writeTransactions(context, input));
        List<Transaction> output = BalanceData.readTransactions(context);
        assertEquals(input.size(), output.size());
        assertTransactionEquals(input.get(0), output.get(0));
        assertTransactionEquals(input.get(1_000), output.get(1_000));
        assertTransactionEquals(input.get(2_049), output.get(2_049));
    }

    @Test public void legacyJson_migratesWithoutChangingTheRows() throws Exception {
        List<Transaction> input = new ArrayList<>();
        input.add(new Transaction("Synthetic", "account-1", 10L, -20L, 30L,
            "signature-1", "content-1"));
        input.add(new Transaction("Synthetic", null, 20L, 40L, null, null, "content-2"));
        String legacy = BalanceData.serializeTransactions(input);
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .putString(BalanceData.KEY_TRANSACTIONS, legacy).commit();

        List<Transaction> output = BalanceData.readTransactions(context);
        assertEquals(2, output.size());
        assertTransactionEquals(input.get(0), output.get(0));
        assertTransactionEquals(input.get(1), output.get(1));
        assertNull(context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE)
            .getString(BalanceData.KEY_TRANSACTIONS, null));
    }

    @Test public void replacingWithAShorterGeneration_removesTheOldTail() {
        List<Transaction> longList = new ArrayList<>();
        for (int i = 0; i < 1_501; i++)
            longList.add(new Transaction("Synthetic", 1_000L + i, 1L));
        assertEquals(true, BalanceData.writeTransactions(context, longList));

        List<Transaction> shortList = new ArrayList<>();
        shortList.add(new Transaction("Synthetic", 9_001L, -1L));
        shortList.add(new Transaction("Synthetic", 9_002L, 2L));
        assertEquals(true, BalanceData.writeTransactions(context, shortList));

        List<Transaction> output = BalanceData.readTransactions(context);
        assertEquals(2, output.size());
        assertEquals(9_001L, output.get(0).date);
        assertEquals(9_002L, output.get(1).date);
    }

    @Test public void pageReadsOnlyTheRequestedWindow() throws Exception {
        List<Transaction> input = new ArrayList<>();
        for (int i = 0; i < 2_050; i++)
            input.add(new Transaction("Synthetic", 10_000L + i, i));
        assertEquals(true, BalanceData.writeTransactions(context, input));

        TransactionStore.Page first = TransactionStore.page(context, -1, 37);
        assertEquals(37, first.rows.size());
        assertEquals(10_036L, first.rows.get(36).date);
        assertEquals(true, first.hasMore);

        TransactionStore.Page second = TransactionStore.page(context, first.nextOrdinal, 37);
        assertEquals(37, second.rows.size());
        assertEquals(37, second.rows.get(0).amount);
    }

    private static void assertTransactionEquals(Transaction expected, Transaction actual) {
        assertEquals(expected.bank, actual.bank);
        assertEquals(expected.account, actual.account);
        assertEquals(expected.date, actual.date);
        assertEquals(expected.amount, actual.amount);
        assertEquals(expected.balance, actual.balance);
        assertEquals(expected.sig, actual.sig);
        assertEquals(expected.content, actual.content);
    }
}
