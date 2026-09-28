package com.ashkanrafiee.balance;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

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

    @Test public void rejectsMissingTransactionsAndNullAdapter() throws Exception {
        try {
            HistoryScanPublication.prepare(null, null, null, null, null, null, 1, 1);
            fail("accepted missing transactions");
        } catch (IllegalArgumentException expected) { }

        HistoryScanPublication publication = HistoryScanPublication.prepare(
                bytes("[]"), null, null, null, null, null, 1, 1);
        try {
            publication.publish(null);
            fail("accepted null adapter");
        } catch (IllegalArgumentException expected) { }
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
