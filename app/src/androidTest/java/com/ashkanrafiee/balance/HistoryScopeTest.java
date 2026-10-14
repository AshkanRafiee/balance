package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Multi-bank / multi-account scope: the pickers' collectors, the scope predicate, and the
 *  reader request carrying sets instead of a single bank and account. */
@RunWith(AndroidJUnit4.class)
public class HistoryScopeTest {

    private Context context;
    private String originalLanguage;
    private String originalCurrency;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BalanceData.reset(context, true);
        originalLanguage = LocaleHelper.currentTag(context);
        originalCurrency = CurrencyHelper.currency(context);
        LocaleHelper.setLanguage(context, "en");
        CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_RIAL);
    }

    @After public void tearDown() {
        LocaleHelper.setLanguage(context, originalLanguage);
        CurrencyHelper.setCurrency(context, originalCurrency);
        BalanceData.reset(context, true);
    }

    private static long epoch(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day, 12, 0, 0);
        return calendar.getTimeInMillis();
    }

    private static Transaction tx(String bank, String account, long amount) {
        return new Transaction(bank, account, epoch(2026, 9, 10), amount, null);
    }

    // ---- collectors ----------------------------------------------------------

    @Test public void distinctBanks_firstSeenOrder_skipsNulls() {
        List<Transaction> txs = Arrays.asList(
            tx("Saman", "1", 1L),
            tx("Mellat", "1", 2L),
            tx("Saman", "2", 3L),
            new Transaction("Mellat", epoch(2026, 9, 10), 4L),
            new Transaction(null, epoch(2026, 9, 10), 5L));
        assertEquals(Arrays.asList("Saman", "Mellat"), HistoryActivity.distinctBanks(txs));
        assertTrue(HistoryActivity.distinctBanks(null).isEmpty());
        assertTrue(HistoryActivity.distinctBanks(new ArrayList<>()).isEmpty());
    }

    @Test public void distinctBankAccounts_pairsInOrder_skipsAccountLess() {
        List<Transaction> txs = Arrays.asList(
            tx("Mellat", "111", 1L),
            tx("Saman", "333", 2L),
            tx("Mellat", "222", 3L),
            tx("Mellat", "111", 4L),
            new Transaction("Mellat", epoch(2026, 9, 10), 5L));
        assertEquals(
            Arrays.asList(new HistoryActivity.BankAccount("Mellat", "111"),
                new HistoryActivity.BankAccount("Saman", "333"),
                new HistoryActivity.BankAccount("Mellat", "222")),
            HistoryActivity.distinctBankAccounts(txs));
    }

    // ---- scope predicate --------------------------------------------------------

    @Test public void bankForSingleAccount_prefersSelectedBanks() {
        java.util.LinkedHashMap<String, java.util.Set<String>> membership =
            new java.util.LinkedHashMap<>();
        membership.put("Mellat", new HashSet<>(Arrays.asList("111", "222")));
        membership.put("Saman", new HashSet<>(Arrays.asList("111", "333")));
        // Shared number resolves to the preferred bank first.
        assertEquals("Saman", HistoryActivity.bankForSingleAccount(
            new HashSet<>(Collections.singletonList("111")),
            Arrays.asList("Saman", "Mellat"), membership));
        assertEquals("Mellat", HistoryActivity.bankForSingleAccount(
            new HashSet<>(Collections.singletonList("222")),
            Collections.singletonList("Mellat"), membership));
        // Not among the preferred banks: unknown.
        assertTrue(HistoryActivity.bankForSingleAccount(
            new HashSet<>(Collections.singletonList("333")),
            Collections.singletonList("Mellat"), membership) == null);
        // No preference: first bank holding it.
        assertEquals("Mellat", HistoryActivity.bankForSingleAccount(
            new HashSet<>(Collections.singletonList("111")),
            Collections.emptySet(), membership));
        // Anything but exactly one known account: unknown.
        assertTrue(HistoryActivity.bankForSingleAccount(
            new HashSet<>(Arrays.asList("111", "222")),
            Collections.emptySet(), membership) == null);
        assertTrue(HistoryActivity.bankForSingleAccount(
            Collections.emptySet(), Collections.emptySet(), membership) == null);
        assertTrue(HistoryActivity.bankForSingleAccount(null, null, null) == null);
    }

    @Test public void accountsOfBanks_collectsSelectedBanksAccounts() {
        List<Transaction> txs = Arrays.asList(
            tx("Mellat", "111", 1L),
            tx("Saman", "333", 2L),
            tx("Mellat", "222", 3L),
            tx("Mellat", "111", 4L),
            new Transaction("Mellat", epoch(2026, 9, 10), 5L));
        assertEquals(new HashSet<>(Arrays.asList("111", "222")),
            HistoryActivity.accountsOfBanks(txs, new HashSet<>(Arrays.asList("Mellat", "X"))));
        assertEquals(new HashSet<>(Arrays.asList("111", "222", "333")),
            HistoryActivity.accountsOfBanks(txs, Collections.emptySet()));
        assertEquals(new HashSet<>(Arrays.asList("111", "222", "333")),
            HistoryActivity.accountsOfBanks(txs, null));
        assertTrue(HistoryActivity.accountsOfBanks(null, null).isEmpty());
    }

    @Test public void inBankAccountScope_emptyMeansAll() {
        Transaction t = tx("Mellat", "111", 1L);
        assertTrue(HistoryActivity.inBankAccountScope(t,
            Collections.emptySet(), Collections.emptySet()));
        assertTrue(HistoryActivity.inBankAccountScope(t, null, null));
        assertFalse(HistoryActivity.inBankAccountScope(null,
            Collections.emptySet(), Collections.emptySet()));
    }

    @Test public void inBankAccountScope_banksAndAccountsCombineByAnd() {
        Transaction mellat111 = tx("Mellat", "111", 1L);
        Transaction mellat222 = tx("Mellat", "222", 2L);
        Transaction saman333 = tx("Saman", "333", 3L);

        // Banks only.
        assertTrue(HistoryActivity.inBankAccountScope(mellat111,
            new HashSet<>(Arrays.asList("Mellat", "Saman")), Collections.emptySet()));
        assertFalse(HistoryActivity.inBankAccountScope(saman333,
            new HashSet<>(Collections.singletonList("Mellat")), Collections.emptySet()));

        // Accounts only match across banks.
        assertTrue(HistoryActivity.inBankAccountScope(mellat111,
            Collections.emptySet(), new HashSet<>(Collections.singletonList("111"))));
        assertTrue(HistoryActivity.inBankAccountScope(
            tx("Saman", "111", 9L),
            Collections.emptySet(), new HashSet<>(Collections.singletonList("111"))));

        // Both narrow together.
        assertTrue(HistoryActivity.inBankAccountScope(mellat111,
            new HashSet<>(Collections.singletonList("Mellat")),
            new HashSet<>(Collections.singletonList("111"))));
        assertFalse(HistoryActivity.inBankAccountScope(mellat222,
            new HashSet<>(Collections.singletonList("Mellat")),
            new HashSet<>(Collections.singletonList("111"))));
        assertFalse(HistoryActivity.inBankAccountScope(saman333,
            new HashSet<>(Arrays.asList("Mellat", "Saman")),
            new HashSet<>(Collections.singletonList("111"))));
    }

    // ---- reader request with sets -----------------------------------------------

    @Test public void reference_multiBankScope_unionsTheBanks() throws Exception {
        long day = epoch(2026, 9, 10);
        List<Transaction> stored = Arrays.asList(
            new Transaction("Mellat", "111", day, -30L, 290L, "a", null),
            new Transaction("Saman", "333", day, 900L, 999L, "b", null),
            new Transaction("Mellat", "222", day, 70L, 360L, "c", null));
        assertTrue(BalanceData.writeTransactions(context, stored));

        HistoryReader.Request request = new HistoryReader.Request(false, 16,
            new HashSet<>(Arrays.asList("Mellat", "Saman")), Collections.emptySet(),
            HistoryActivity.Filter.ALL, "", null, null, 0, 0);
        HistoryReader.Result result = HistoryReader.reference(context, request);
        try {
            assertEquals(3, result.visibleTransactions().size());
        } finally {
            result.close();
        }
    }

    @Test public void reference_crossBankAccountScope_matchesEveryBank() throws Exception {
        long day = epoch(2026, 9, 10);
        List<Transaction> stored = Arrays.asList(
            new Transaction("Mellat", "111", day, -30L, 290L, "a", null),
            new Transaction("Saman", "111", day, 900L, 999L, "b", null),
            new Transaction("Mellat", "222", day, 70L, 360L, "c", null));
        assertTrue(BalanceData.writeTransactions(context, stored));

        HistoryReader.Request request = new HistoryReader.Request(false, 16,
            Collections.emptySet(), new HashSet<>(Collections.singletonList("111")),
            HistoryActivity.Filter.ALL, "", null, null, 0, 0);
        HistoryReader.Result result = HistoryReader.reference(context, request);
        try {
            assertEquals(2, result.visibleTransactions().size());
            for (Transaction t : result.visibleTransactions()) assertEquals("111", t.account);
        } finally {
            result.close();
        }
    }

    @Test public void reference_bankPlusAccountScope_narrowsByAnd() throws Exception {
        long day = epoch(2026, 9, 10);
        List<Transaction> stored = Arrays.asList(
            new Transaction("Mellat", "111", day, -30L, 290L, "a", null),
            new Transaction("Mellat", "222", day, 70L, 360L, "b", null),
            new Transaction("Saman", "111", day, 900L, 999L, "c", null));
        assertTrue(BalanceData.writeTransactions(context, stored));

        HistoryReader.Request request = new HistoryReader.Request(false, 16,
            new HashSet<>(Collections.singletonList("Mellat")),
            new HashSet<>(Collections.singletonList("111")),
            HistoryActivity.Filter.ALL, "", null, null, 0, 0);
        HistoryReader.Result result = HistoryReader.reference(context, request);
        try {
            assertEquals(1, result.visibleTransactions().size());
            assertEquals("Mellat", result.visibleTransactions().get(0).bank);
            assertEquals("111", result.visibleTransactions().get(0).account);
        } finally {
            result.close();
        }
    }

    @Test public void summary_setScope_matchesReference() throws Exception {
        long day = epoch(2026, 9, 10);
        List<Transaction> stored = Arrays.asList(
            new Transaction("Mellat", "111", day, -30L, 290L, "a", null),
            new Transaction("Mellat", "222", day, 70L, 360L, "b", null),
            new Transaction("Saman", "333", day, 900L, 999L, "c", null));
        assertTrue(BalanceData.writeTransactions(context, stored));

        HistoryReader.Request request = new HistoryReader.Request(false, 16,
            new HashSet<>(Collections.singletonList("Saman")), Collections.emptySet(),
            HistoryActivity.Filter.ALL, "", null, null, 0, 0);
        HistoryReader.Result result = HistoryReader.summary(context, request);
        try {
            assertEquals(900L, result.total);
            assertEquals(1, result.movementCount);
        } finally {
            result.close();
        }
    }

    @Test public void accountLessBank_isABankChoiceWithNoPairs() {
        // Blu states no account number: it must still be offered as a bank choice, while
        // contributing no account pairs.
        List<Transaction> txs = Arrays.asList(
            new Transaction("Blu", epoch(2026, 9, 10), 1L),
            tx("Mellat", "111", 2L));
        assertEquals(Arrays.asList("Blu", "Mellat"), HistoryActivity.distinctBanks(txs));
        assertEquals(Collections.singletonList(new HistoryActivity.BankAccount("Mellat", "111")),
            HistoryActivity.distinctBankAccounts(txs));
    }

    @Test public void narrowingKey_coversEveryScopeAxis() {
        HistoryActivity.Filter all = HistoryActivity.Filter.ALL;
        String base = HistoryActivity.narrowingKey("", null, null, null, all);
        // Set order never changes the identity.
        assertEquals(
            HistoryActivity.narrowingKey("", null,
                new HashSet<>(Arrays.asList("Mellat", "Saman")), null, all),
            HistoryActivity.narrowingKey("", null,
                new HashSet<>(Arrays.asList("Saman", "Mellat")), null, all));
        // Every axis moves the key.
        assertFalse(base.equals(HistoryActivity.narrowingKey("saman", null, null, null, all)));
        assertFalse(base.equals(HistoryActivity.narrowingKey("", new HashSet<>(Arrays.asList("x")),
            null, null, all)));
        assertFalse(base.equals(HistoryActivity.narrowingKey("", null,
            new HashSet<>(Collections.singletonList("Mellat")), null, all)));
        assertFalse(base.equals(HistoryActivity.narrowingKey("", null, null,
            new HashSet<>(Collections.singletonList("111")), all)));
        assertFalse(base.equals(HistoryActivity.narrowingKey("", null, null, null,
            new HistoryActivity.Filter(HistoryActivity.DIR_DEPOSIT, HistoryActivity.RANGE_ALL,
                null, null))));
        assertFalse(base.equals(HistoryActivity.narrowingKey("", null, null, null,
            new HistoryActivity.Filter(HistoryActivity.DIR_ALL, HistoryActivity.RANGE_CUSTOM,
                CalDate.of(2026, 9, 1), CalDate.of(2026, 9, 10)))));
    }
}
