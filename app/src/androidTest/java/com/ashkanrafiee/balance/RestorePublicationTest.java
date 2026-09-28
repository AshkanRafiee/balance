package com.ashkanrafiee.balance;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class RestorePublicationTest {
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

    @Test public void publishesAllRestoreComponentsInOneCommit() throws Exception {
        Fake fake = new Fake();
        fake.values.put("future", bytes("keep"));
        FinancialSnapshotAdapter adapter = new FinancialSnapshotAdapter(new FinancialRepository(fake));
        RestorePublication publication = RestorePublication.prepare(bytes("{}"), bytes("[]"),
                bytes("{}"), bytes("{}"), bytes("{}"), 2, 1);

        publication.publish(adapter);

        assertEquals(1, fake.commits);
        assertEquals(6, fake.values.size());
        assertArrayEquals(bytes("keep"), fake.values.get("future"));
        assertArrayEquals(bytes("{}"), fake.values.get(FinancialSnapshotAdapter.BALANCES));
        assertArrayEquals(bytes("[]"), fake.values.get(FinancialSnapshotAdapter.TRANSACTIONS));
        assertArrayEquals(bytes("{}"), fake.values.get(FinancialSnapshotAdapter.TRANSACTION_NOTES));
        assertArrayEquals(bytes("{}"), fake.values.get(FinancialSnapshotAdapter.TRANSACTION_REASONS));
        assertArrayEquals(bytes("{}"), fake.values.get(FinancialSnapshotAdapter.TRANSACTION_CHANNELS));
        assertEquals(2, publication.added());
        assertEquals(1, publication.updated());
    }

    @Test public void omittedOptionalComponentsRemainUnchanged() throws Exception {
        Fake fake = new Fake();
        fake.values.put(FinancialSnapshotAdapter.TRANSACTION_NOTES, bytes("{}"));
        FinancialSnapshotAdapter adapter = new FinancialSnapshotAdapter(new FinancialRepository(fake));
        RestorePublication.prepare(bytes("{}"), bytes("[]"), null, null, null, 0, 0).publish(adapter);

        assertArrayEquals(bytes("{}"), fake.values.get(FinancialSnapshotAdapter.TRANSACTION_NOTES));
        assertFalse(fake.values.containsKey(FinancialSnapshotAdapter.TRANSACTION_REASONS));
        assertFalse(fake.values.containsKey(FinancialSnapshotAdapter.TRANSACTION_CHANNELS));
    }

    @Test public void clonesInputsAndUpdates() {
        byte[] balances = bytes("{}");
        byte[] transactions = bytes("[]");
        RestorePublication publication = RestorePublication.prepare(balances, transactions,
                bytes("{}"), null, null, 0, 0);
        balances[0] = '[';
        transactions[0] = '{';
        assertArrayEquals(bytes("{}"), publication.balancesJson());
        assertArrayEquals(bytes("[]"), publication.transactionsJson());
        byte[] update = publication.updates().get(FinancialSnapshotAdapter.BALANCES);
        update[0] = '[';
        assertArrayEquals(bytes("{}"), publication.updates().get(FinancialSnapshotAdapter.BALANCES));
    }

    @Test public void rejectsInvalidRequiredArguments() {
        try {
            RestorePublication.prepare(null, bytes("[]"), null, null, null, 0, 0);
            fail("accepted missing balances");
        } catch (IllegalArgumentException expected) { }
        try {
            RestorePublication.prepare(bytes("{}"), null, null, null, null, 0, 0);
            fail("accepted missing transactions");
        } catch (IllegalArgumentException expected) { }
        try {
            RestorePublication.prepare(bytes("{}"), bytes("[]"), null, null, null, -1, 0);
            fail("accepted negative count");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void rejectsNullAdapter() throws Exception {
        RestorePublication publication = RestorePublication.prepare(bytes("{}"), bytes("[]"),
                null, null, null, 0, 0);
        try {
            publication.publish(null);
            fail("accepted null adapter");
        } catch (IllegalArgumentException expected) { }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
