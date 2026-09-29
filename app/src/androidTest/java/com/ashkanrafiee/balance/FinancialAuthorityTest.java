package com.ashkanrafiee.balance;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class FinancialAuthorityTest {
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
            revision = "r" + (++commits);
        }
    }

    @Test public void authoritySharesOneRepositoryAcrossAdapters() throws Exception {
        Fake fake = new Fake();
        FinancialAuthority authority = FinancialAuthority.fromRepository(
                new FinancialRepository(fake));

        authority.snapshots().publishBalance(updates("{}", "7"));
        assertArrayEquals(bytes("{}"), authority.repository().snapshot()
                .get(FinancialSnapshotAdapter.BALANCES));
        assertArrayEquals(bytes("7"), authority.snapshots().snapshot()
                .get(FinancialSnapshotAdapter.SCANNED_THROUGH));
        assertSame(authority.repository(), authority.repository());
        assertNotNull(authority.operations());
        assertEquals(1, fake.commits);
    }

    @Test public void invalidPublicationDoesNotCreateASecondPartialAuthority() throws Exception {
        Fake fake = new Fake();
        FinancialAuthority authority = FinancialAuthority.fromRepository(
                new FinancialRepository(fake));
        try {
            authority.snapshots().publishBalance(updates("not-json", "7"));
            fail("accepted malformed balance");
        } catch (IOException expected) { }
        assertEquals(0, fake.commits);
        assertTrue(authority.repository().snapshot().components().isEmpty());
    }

    @Test public void directAuthorityOperationsUseTheSamePinnedRepository() throws Exception {
        Fake fake = new Fake();
        FinancialAuthority authority = FinancialAuthority.fromRepository(
                new FinancialRepository(fake));

        authority.transaction(draft -> {
            draft.put(FinancialSnapshotAdapter.BALANCES, bytes("{}"));
            draft.put(FinancialSnapshotAdapter.RULES_VERSION, bytes("3"));
            assertArrayEquals(bytes("{}"), authority.snapshot()
                    .get(FinancialSnapshotAdapter.BALANCES));
            return null;
        });

        assertEquals(1, fake.commits);
        assertArrayEquals(bytes("3"), authority.snapshots().snapshot()
                .get(FinancialSnapshotAdapter.RULES_VERSION));
    }

    private static Map<String, byte[]> updates(String balances, String scannedThrough) {
        Map<String, byte[]> updates = new LinkedHashMap<>();
        updates.put(FinancialSnapshotAdapter.BALANCES, bytes(balances));
        updates.put(FinancialSnapshotAdapter.SCANNED_THROUGH, bytes(scannedThrough));
        updates.put(FinancialSnapshotAdapter.RULES_VERSION, bytes("1"));
        return updates;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
