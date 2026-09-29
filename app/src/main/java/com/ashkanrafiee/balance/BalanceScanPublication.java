package com.ashkanrafiee.balance;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable, already-prepared result of a balance scan. */
final class BalanceScanPublication {
    private final byte[] balancesJson;
    private final byte[] recentMovementsJson;
    private final Long scannedThrough;
    private final Integer rulesVersion;
    private final int matched;
    private final Set<String> removals;

    private BalanceScanPublication(byte[] balancesJson, byte[] recentMovementsJson,
            Long scannedThrough, Integer rulesVersion, int matched, Set<String> removals) {
        if (balancesJson == null || matched < 0) throw new IllegalArgumentException("ARGUMENT");
        this.balancesJson = balancesJson.clone();
        this.recentMovementsJson = recentMovementsJson == null ? null : recentMovementsJson.clone();
        this.scannedThrough = scannedThrough;
        this.rulesVersion = rulesVersion;
        this.matched = matched;
        this.removals = removals(removals, Arrays.asList(
                FinancialSnapshotAdapter.BALANCES, FinancialSnapshotAdapter.RECENT_MOVEMENTS,
                FinancialSnapshotAdapter.SCANNED_THROUGH, FinancialSnapshotAdapter.RULES_VERSION));
    }

    static Builder builder() { return new Builder(); }

    static BalanceScanPublication prepare(byte[] balancesJson, byte[] recentMovementsJson,
            Long scannedThrough, Integer rulesVersion, int matched) {
        return prepare(balancesJson, recentMovementsJson, scannedThrough, rulesVersion, matched,
                Collections.emptySet());
    }

    static BalanceScanPublication prepare(byte[] balancesJson, byte[] recentMovementsJson,
            Long scannedThrough, Integer rulesVersion, int matched, Set<String> removals) {
        return new BalanceScanPublication(balancesJson, recentMovementsJson, scannedThrough,
                rulesVersion, matched, removals);
    }

    byte[] balancesJson() { return balancesJson.clone(); }

    byte[] recentMovementsJson() {
        return recentMovementsJson == null ? null : recentMovementsJson.clone();
    }

    Long scannedThrough() { return scannedThrough; }
    Integer rulesVersion() { return rulesVersion; }
    int matched() { return matched; }

    /** The balance-owned components this publication deletes, because the scan derived none. */
    Set<String> removals() { return removals; }

    /** Returns only the balance-owned components represented by this publication. */
    Map<String, byte[]> updates() {
        Map<String, byte[]> updates = new LinkedHashMap<>();
        updates.put(FinancialSnapshotAdapter.BALANCES, balancesJson.clone());
        if (recentMovementsJson != null)
            updates.put(FinancialSnapshotAdapter.RECENT_MOVEMENTS, recentMovementsJson.clone());
        if (scannedThrough != null)
            updates.put(FinancialSnapshotAdapter.SCANNED_THROUGH,
                    Long.toString(scannedThrough).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (rulesVersion != null) updates.put(FinancialSnapshotAdapter.RULES_VERSION,
                Integer.toString(rulesVersion).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Collections.unmodifiableMap(updates);
    }

    void publish(FinancialSnapshotAdapter adapter) throws IOException {
        if (adapter == null) throw new IllegalArgumentException("ARGUMENT");
        adapter.publishBalance(updates(), removals);
    }

    static final class Builder {
        private byte[] balancesJson;
        private byte[] recentMovementsJson;
        private Long scannedThrough;
        private Integer rulesVersion;
        private int matched;
        private Set<String> removals = Collections.emptySet();

        Builder balancesJson(byte[] value) { balancesJson = value; return this; }
        Builder recentMovementsJson(byte[] value) { recentMovementsJson = value; return this; }
        Builder scannedThrough(Long value) { scannedThrough = value; return this; }
        Builder rulesVersion(Integer value) { rulesVersion = value; return this; }
        Builder matched(int value) { matched = value; return this; }
        Builder removals(Set<String> value) { removals = value; return this; }

        BalanceScanPublication build() {
            return new BalanceScanPublication(balancesJson, recentMovementsJson, scannedThrough,
                    rulesVersion, matched, removals);
        }
    }

    /** Rejects a null, overlapping or non-balance-owned removal: a name that is both written and
     *  deleted in one generation is a caller bug, and deleting a component a scan may not own would
     *  let it discard another writer's state. */
    private static Set<String> removals(Set<String> values, List<String> owned) {
        if (values == null) throw new IllegalArgumentException("ARGUMENT");
        Set<String> result = new LinkedHashSet<>();
        for (String name : values) {
            if (name == null || !owned.contains(name) || result.contains(name))
                throw new IllegalArgumentException("ARGUMENT");
            result.add(name);
        }
        return Collections.unmodifiableSet(result);
    }
}
