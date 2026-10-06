package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure history tests for exact tag filtering and tag-aware partial search. */
@RunWith(AndroidJUnit4.class)
public class HistoryTagTest {
    private static final long T = 1_000_000_000L;

    private Transaction tx(String content, long amount) {
        return new Transaction("Tejarat", null, T, amount, "sig-" + content, content);
    }

    private Map<String, List<String>> tags(Transaction... txs) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put(BalanceData.noteKey(txs[0]), Arrays.asList("groceries", "monthly"));
        if (txs.length > 1) out.put(BalanceData.noteKey(txs[1]), Arrays.asList("grocery", "monthly"));
        if (txs.length > 2) out.put(BalanceData.noteKey(txs[2]), Arrays.asList("monthly"));
        return out;
    }

    @Test public void exactFilter_doesNotUsePartialTagMatches() {
        Transaction groceries = tx("a", -10);
        Transaction grocery = tx("b", -20);
        List<Transaction> input = Arrays.asList(groceries, grocery);
        List<Transaction> out = HistoryActivity.applyTagFilter(input, tags(groceries, grocery),
            Arrays.asList("groceries"));

        assertEquals(Arrays.asList(groceries), out);
        assertEquals(2, input.size());
    }

    @Test public void filterRequiresEverySelectedTag() {
        Transaction first = tx("a", -10);
        Transaction second = tx("b", -20);
        Transaction third = tx("c", -30);
        Map<String, List<String>> allTags = tags(first, second, third);

        assertEquals(Arrays.asList(first), HistoryActivity.applyTagFilter(
            Arrays.asList(first, second, third), allTags, Arrays.asList("groceries", "monthly")));
    }

    @Test public void filterIsCaseInsensitiveButStillExact() {
        Transaction first = tx("a", -10);
        Transaction second = tx("b", -20);
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put(BalanceData.noteKey(first), Arrays.asList("Rent"));
        map.put(BalanceData.noteKey(second), Arrays.asList("Rental"));

        assertEquals(Arrays.asList(first), HistoryActivity.applyTagFilter(
            Arrays.asList(first, second), map, Arrays.asList("rent")));
    }

    @Test public void searchIncludesTagsWithoutChangingExistingFields() {
        Transaction t = tx("search", 500);
        String haystack = HistoryActivity.transactionSearchText(t, "Tejarat", null,
            null, null, null, null, "500", "Withdrawal", "date", "time", "month", "compact",
            Arrays.asList("reimbursable", "tax"));
        assertTrue(HistoryActivity.matchesSearch(haystack, "reimburs"));
        assertTrue(HistoryActivity.matchesSearch(haystack, "tax"));
        assertFalse(HistoryActivity.matchesSearch(haystack, "travel"));
    }
}
