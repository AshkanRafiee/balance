package com.ashkanrafiee.balance;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class BalanceScanPublicationTest {
    private static final class Fake implements FinancialRepository.Backend {
        String revision = "r0";
        Map<String, byte[]> values = new LinkedHashMap<>();
        int commits;

        @Override public FinancialRepository.Snapshot load() {
            return new FinancialRepository.Snapshot(revision, values);
        }

        @Override public void commit(FinancialRepository.Snapshot base, Map<String, byte[]> next)
                throws IOException {
            values = new LinkedHashMap<>(next);
            revision = "r" + ++commits;
        }
    }

    @Test public void preparedValuesAndUpdatesAreDefensive() {
        byte[] balances = bytes("{}");
        byte[] recent = bytes("[]");
        BalanceScanPublication publication = BalanceScanPublication.prepare(
                balances, recent, Long.MAX_VALUE, Integer.MIN_VALUE, 3);
        balances[0] = '[';
        recent[0] = '{';
        byte[] returned = publication.balancesJson();
        returned[0] = '[';
        byte[] update = publication.updates().get(FinancialSnapshotAdapter.BALANCES);
        update[0] = '[';

        assertArrayEquals(bytes("{}"), publication.balancesJson());
        assertArrayEquals(bytes("[]"), publication.recentMovementsJson());
        assertEquals(Long.MAX_VALUE, publication.scannedThrough().longValue());
        assertEquals(Integer.MIN_VALUE, publication.rulesVersion());
        assertEquals(3, publication.matched());
        assertArrayEquals(bytes("{}"), publication.updates().get(FinancialSnapshotAdapter.BALANCES));
    }

    @Test public void updatesUseCanonicalMetadataAndOmitOptionalComponents() {
        BalanceScanPublication publication = BalanceScanPublication.prepare(
                bytes("{}"), null, null, -7, 0);
        Map<String, byte[]> updates = publication.updates();

        assertEquals(2, updates.size());
        assertArrayEquals(bytes("{}"), updates.get(FinancialSnapshotAdapter.BALANCES));
        assertArrayEquals(bytes("-7"), updates.get(FinancialSnapshotAdapter.RULES_VERSION));
        assertFalse(updates.containsKey(FinancialSnapshotAdapter.RECENT_MOVEMENTS));
        assertFalse(updates.containsKey(FinancialSnapshotAdapter.SCANNED_THROUGH));
    }

    @Test public void publishCommitsOnceAndPreservesOmittedRecentMovements() throws Exception {
        Fake fake = new Fake();
        FinancialSnapshotAdapter adapter = new FinancialSnapshotAdapter(new FinancialRepository(fake));
        adapter.transaction(draft -> {
            draft.put(FinancialSnapshotAdapter.RECENT_MOVEMENTS, bytes("[\"old\"]"));
            return null;
        });
        int before = fake.commits;

        BalanceScanPublication.prepare(bytes("{}"), null, 42L, 8, 1).publish(adapter);

        assertEquals(before + 1, fake.commits);
        assertArrayEquals(bytes("[\"old\"]"), adapter.snapshot().get(
                FinancialSnapshotAdapter.RECENT_MOVEMENTS));
        assertArrayEquals(bytes("42"), adapter.snapshot().get(FinancialSnapshotAdapter.SCANNED_THROUGH));
    }

    @Test public void rejectsInvalidArguments() {
        try {
            BalanceScanPublication.prepare(null, null, null, 1, 0);
            fail("accepted null balances");
        } catch (IllegalArgumentException expected) { }
        try {
            BalanceScanPublication.prepare(bytes("{}"), null, null, 1, -1);
            fail("accepted negative match count");
        } catch (IllegalArgumentException expected) { }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
