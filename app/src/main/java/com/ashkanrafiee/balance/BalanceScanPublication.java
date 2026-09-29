package com.ashkanrafiee.balance;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, already-prepared result of a balance scan. */
final class BalanceScanPublication {
    private final byte[] balancesJson;
    private final byte[] recentMovementsJson;
    private final Long scannedThrough;
    private final Integer rulesVersion;
    private final int matched;

    private BalanceScanPublication(byte[] balancesJson, byte[] recentMovementsJson,
            Long scannedThrough, Integer rulesVersion, int matched) {
        if (balancesJson == null || matched < 0) throw new IllegalArgumentException("ARGUMENT");
        this.balancesJson = balancesJson.clone();
        this.recentMovementsJson = recentMovementsJson == null ? null : recentMovementsJson.clone();
        this.scannedThrough = scannedThrough;
        this.rulesVersion = rulesVersion;
        this.matched = matched;
    }

    static Builder builder() { return new Builder(); }

    static BalanceScanPublication prepare(byte[] balancesJson, byte[] recentMovementsJson,
            Long scannedThrough, Integer rulesVersion, int matched) {
        return new BalanceScanPublication(balancesJson, recentMovementsJson, scannedThrough,
                rulesVersion, matched);
    }

    byte[] balancesJson() { return balancesJson.clone(); }

    byte[] recentMovementsJson() {
        return recentMovementsJson == null ? null : recentMovementsJson.clone();
    }

    Long scannedThrough() { return scannedThrough; }
    Integer rulesVersion() { return rulesVersion; }
    int matched() { return matched; }

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
        adapter.publishBalance(updates());
    }

    static final class Builder {
        private byte[] balancesJson;
        private byte[] recentMovementsJson;
        private Long scannedThrough;
        private Integer rulesVersion;
        private int matched;

        Builder balancesJson(byte[] value) { balancesJson = value; return this; }
        Builder recentMovementsJson(byte[] value) { recentMovementsJson = value; return this; }
        Builder scannedThrough(Long value) { scannedThrough = value; return this; }
        Builder rulesVersion(Integer value) { rulesVersion = value; return this; }
        Builder matched(int value) { matched = value; return this; }

        BalanceScanPublication build() {
            return new BalanceScanPublication(balancesJson, recentMovementsJson, scannedThrough,
                    rulesVersion, matched);
        }
    }
}
