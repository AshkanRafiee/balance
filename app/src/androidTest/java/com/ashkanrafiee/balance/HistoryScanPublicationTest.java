package com.ashkanrafiee.balance;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class HistoryScanPublicationTest {
    private static final class Fake implements FinancialRepository.Backend {
        String revision = "r0";
        Map<String, byte[]> values = new LinkedHashMap<>();
        int commits;

        @Override public FinancialRepository.Snapshot load() {
            return new FinancialRepository.Snapshot(revision, values);
        }

        @Override public void commit(FinancialRepository.Snapshot base, Map<String, byte[]> next)
                throws IOException {
            if (!revision.equals(base.revision())) throw new IOException("STALE");
            values = new LinkedHashMap<>(next);
            revision = "r" + (++commits);
        }
    }

    @Test public void publishesOncePreservesBalanceComponentsAndOmitsNulls() throws Exception {
        Fake fake = new Fake();
        FinancialSnapshotAdapter adapter = new FinancialSnapshotAdapter(new FinancialRepository(fake));
        adapter.publishBalance(updates(FinancialSnapshotAdapter.BALANCES, "{}",
                FinancialSnapshotAdapter.SCANNED_THROUGH, "17"));

        HistoryScanPublication publication = HistoryScanPublication.prepare(
                bytes("[]"), null, null, null, null, null, 4, 2);
        int before = fake.commits;
        publication.publish(adapter);

        assertEquals(before + 1, fake.commits);
        assertEquals(5, fake.values.size());
        assertArrayEquals(bytes("{}"), fake.values.get(FinancialSnapshotAdapter.BALANCES));
        assertArrayEquals(bytes("17"), fake.values.get(FinancialSnapshotAdapter.SCANNED_THROUGH));
        assertArrayEquals(bytes("[]"), fake.values.get(FinancialSnapshotAdapter.TRANSACTIONS));
        assertFalse(fake.values.containsKey(FinancialSnapshotAdapter.TRANSACTION_REASONS));
        assertFalse(fake.values.containsKey(FinancialSnapshotAdapter.TRANSACTION_CHANNELS));
        assertFalse(fake.values.containsKey(FinancialSnapshotAdapter.RECENT_MOVEMENTS));
        assertFalse(fake.values.containsKey(FinancialSnapshotAdapter.HISTORY_LAST_BALANCE));
        assertFalse(fake.values.containsKey(FinancialSnapshotAdapter.HISTORY_THROUGH));
        assertArrayEquals(bytes("4"), fake.values.get(FinancialSnapshotAdapter.HISTORY_RULES_VERSION));
        assertArrayEquals(bytes("2"), fake.values.get(FinancialSnapshotAdapter.HISTORY_SCHEMA));
    }

    @Test public void clonesInputsAndReturnedValues() {
        byte[] transactions = bytes("[]");
        byte[] reasons = bytes("{}");
        HistoryScanPublication publication = HistoryScanPublication.prepare(
                transactions, reasons, null, null, null, 9L, 1, 3);
        transactions[0] = '{';
        reasons[0] = '[';

        byte[] returned = publication.transactionsJson();
        returned[0] = '{';
        assertArrayEquals(bytes("[]"), publication.transactionsJson());
        assertArrayEquals(bytes("{}"), publication.reasonsJson());

        Map<String, byte[]> updates = publication.updates();
        updates.get(FinancialSnapshotAdapter.TRANSACTIONS)[0] = '{';
        assertArrayEquals(bytes("[]"), publication.updates().get(FinancialSnapshotAdapter.TRANSACTIONS));
    }

    @Test public void noTransactionsDeletesTheComponentRatherThanStoringAnEmptyList() {
        // The store means "no history" by the component being absent, which is what the canonical
        // writer does for an empty list. A publication that both stored an empty list and deleted
        // the component would ask for both in one generation, and the store refuses that: the two
        // are alternatives, not a pair.
        HistoryScanPublication publication = HistoryScanPublication.prepare(null, null, null, null,
                null, null, 1, 1);

        assertNull(publication.transactionsJson());
        assertFalse(publication.updates().containsKey(FinancialSnapshotAdapter.TRANSACTIONS));
        assertTrue(publication.removals().contains(FinancialSnapshotAdapter.TRANSACTIONS));
        // Both components this publication does write are already in the store, so a second such
        // scan has nothing left to change.
        Map<String, byte[]> alreadyScanned = updates(FinancialSnapshotAdapter.HISTORY_RULES_VERSION,
                "1");
        alreadyScanned.put(FinancialSnapshotAdapter.HISTORY_SCHEMA, bytes("1"));
        assertFalse("a store with no history has nothing left to change",
                publication.changesAnything(alreadyScanned));
        assertTrue("a store that still has a component to delete is changed",
                publication.changesAnything(updates(FinancialSnapshotAdapter.TRANSACTIONS,
                        "{\"transactions\":[]}")));
    }

@Test public void rejectsMissingRemovalsAndNullAdapter() throws Exception {
        HistoryScanPublication publication = HistoryScanPublication.prepare(
                bytes("[]"), null, null, null, null, null, 1, 1);
        try {
            publication.publish(null);
            fail("accepted null adapter");
        } catch (IllegalArgumentException expected) { }

        try {
            HistoryScanPublication.prepare(bytes("[]"), null, null, null, null, 9L, 1, 1, null);
            fail("accepted missing removals");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void aSecondIdenticalScanChangesNothing() {
        // The case the redraw skip exists for: the history screen opens, which starts a scan, and
        // the scan finds the movements it already stored. Every component it would write comes back
        // with the bytes it already has.
        Map<String, byte[]> store = new LinkedHashMap<>();
        store.put(FinancialSnapshotAdapter.TRANSACTIONS, bytes("[]"));
        store.put(FinancialSnapshotAdapter.TRANSACTION_REASONS, bytes("{}"));
        store.put(FinancialSnapshotAdapter.HISTORY_THROUGH, bytes("17"));

        HistoryScanPublication second = HistoryScanPublication.prepare(bytes("[]"), null, null,
                null, null, 17L, null, null);

        assertFalse("an identical scan must not be reported as a change", second.changesAnything(store));
    }

    @Test public void aFirstScanIntoAnEmptyStoreIsAChange() {
        // The other half, and the reason the skip cannot be lazy about it: nothing is stored yet, so
        // the very same publication is a change. A reader that was never told would sit on an empty
        // screen forever.
        HistoryScanPublication first = HistoryScanPublication.prepare(bytes("[]"), null, null,
                null, null, null, 1, 1);

        assertTrue("the first scan records the rules version", first.changesAnything(
                new LinkedHashMap<String, byte[]>()));
    }

    @Test public void oneChangedComponentIsEnough() {
        // Only the components the scan would write count, so a store that differs elsewhere must not
        // be mistaken for a change, and a store that differs in one of them must not be missed. The
        // transactions match in both halves: the publication writes them every time, so leaving them
        // out would itself be a difference.
        HistoryScanPublication publication = HistoryScanPublication.prepare(bytes("[]"), bytes("{}"),
                null, null, null, null, null, null);

        // The two components this publication writes are both already in the store, so the only
        // difference in the first half is the balance the scan never touches.
        Map<String, byte[]> store = new LinkedHashMap<>();
        store.put(FinancialSnapshotAdapter.TRANSACTIONS, bytes("[]"));
        store.put(FinancialSnapshotAdapter.TRANSACTION_REASONS, bytes("{}"));
        store.put(FinancialSnapshotAdapter.BALANCES, bytes("{\"x\":1}"));
        assertFalse("a difference the scan does not write is not its change",
                publication.changesAnything(store));

        Map<String, byte[]> oneReason = new LinkedHashMap<>(store);
        oneReason.put(FinancialSnapshotAdapter.TRANSACTION_REASONS, bytes("{\"k\":\"v\"}"));
        assertTrue("a changed reason the scan would write is a change",
                publication.changesAnything(oneReason));
    }

    @Test public void aRemovalIsAChangeOnlyWhenSomethingIsThereToRemove() {
        Set<String> removals = new LinkedHashSet<>();
        removals.add(FinancialSnapshotAdapter.RECENT_MOVEMENTS);
        HistoryScanPublication publication = HistoryScanPublication.prepare(bytes("[]"), null, null,
                null, null, null, null, null, removals);

        assertFalse("the recent-movement windows are gone, but the store never had any",
                publication.changesAnything(updates(FinancialSnapshotAdapter.TRANSACTIONS, "[]")));
        assertTrue("a component that was there is gone", publication.changesAnything(
                updates(FinancialSnapshotAdapter.TRANSACTIONS, "[]",
                        FinancialSnapshotAdapter.RECENT_MOVEMENTS, "[]")));
    }

    @Test public void rejectsMissingCurrent() {
        HistoryScanPublication publication = HistoryScanPublication.prepare(bytes("[]"), null, null,
                null, null, null, null, null);
        try {
            publication.changesAnything(null);
            fail("accepted null store");
        } catch (IllegalArgumentException expected) { }
    }

    private static Map<String, byte[]> updates(String first, String firstValue) {
        Map<String, byte[]> updates = new LinkedHashMap<>();
        updates.put(first, bytes(firstValue));
        return updates;
    }

    private static Map<String, byte[]> updates(String first, String firstValue,
            String second, String secondValue) {
        Map<String, byte[]> updates = new LinkedHashMap<>();
        updates.put(first, bytes(firstValue));
        updates.put(second, bytes(secondValue));
        return updates;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
