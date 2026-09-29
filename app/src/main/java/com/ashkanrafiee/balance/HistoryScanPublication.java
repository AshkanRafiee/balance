package com.ashkanrafiee.balance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, already-prepared result of a history scan. */
final class HistoryScanPublication {
    private final byte[] transactionsJson;
    private final byte[] reasonsJson;
    private final byte[] channelsJson;
    private final byte[] recentMovementsJson;
    private final byte[] historyLastBalanceJson;
    private final Long historyThrough;
    private final Integer historyRulesVersion;
    private final Integer historySchema;

    private HistoryScanPublication(byte[] transactionsJson, byte[] reasonsJson, byte[] channelsJson,
            byte[] recentMovementsJson, byte[] historyLastBalanceJson, Long historyThrough,
            Integer historyRulesVersion, Integer historySchema) {
        if (transactionsJson == null) throw new IllegalArgumentException("ARGUMENT");
        this.transactionsJson = transactionsJson.clone();
        this.reasonsJson = clone(reasonsJson);
        this.channelsJson = clone(channelsJson);
        this.recentMovementsJson = clone(recentMovementsJson);
        this.historyLastBalanceJson = clone(historyLastBalanceJson);
        this.historyThrough = historyThrough;
        this.historyRulesVersion = historyRulesVersion;
        this.historySchema = historySchema;
    }

    static Builder builder() { return new Builder(); }

    static HistoryScanPublication prepare(byte[] transactionsJson, byte[] reasonsJson,
            byte[] channelsJson, byte[] recentMovementsJson, byte[] historyLastBalanceJson,
            Long historyThrough, Integer historyRulesVersion, Integer historySchema) {
        return new HistoryScanPublication(transactionsJson, reasonsJson, channelsJson,
                recentMovementsJson, historyLastBalanceJson, historyThrough,
                historyRulesVersion, historySchema);
    }

    byte[] transactionsJson() { return transactionsJson.clone(); }
    byte[] reasonsJson() { return clone(reasonsJson); }
    byte[] channelsJson() { return clone(channelsJson); }
    byte[] recentMovementsJson() { return clone(recentMovementsJson); }
    byte[] historyLastBalanceJson() { return clone(historyLastBalanceJson); }
    Long historyThrough() { return historyThrough; }
    Integer historyRulesVersion() { return historyRulesVersion; }
    Integer historySchema() { return historySchema; }

    /** Returns only the history-owned components represented by this publication. */
    Map<String, byte[]> updates() {
        Map<String, byte[]> updates = new LinkedHashMap<>();
        updates.put(FinancialSnapshotAdapter.TRANSACTIONS, transactionsJson.clone());
        if (reasonsJson != null) updates.put(FinancialSnapshotAdapter.TRANSACTION_REASONS,
                reasonsJson.clone());
        if (channelsJson != null) updates.put(FinancialSnapshotAdapter.TRANSACTION_CHANNELS,
                channelsJson.clone());
        if (recentMovementsJson != null) updates.put(FinancialSnapshotAdapter.RECENT_MOVEMENTS,
                recentMovementsJson.clone());
        if (historyLastBalanceJson != null) updates.put(FinancialSnapshotAdapter.HISTORY_LAST_BALANCE,
                historyLastBalanceJson.clone());
        if (historyThrough != null) updates.put(FinancialSnapshotAdapter.HISTORY_THROUGH,
                Long.toString(historyThrough).getBytes(StandardCharsets.UTF_8));
        if (historyRulesVersion != null) updates.put(FinancialSnapshotAdapter.HISTORY_RULES_VERSION,
                Integer.toString(historyRulesVersion).getBytes(StandardCharsets.UTF_8));
        if (historySchema != null) updates.put(FinancialSnapshotAdapter.HISTORY_SCHEMA,
                Integer.toString(historySchema).getBytes(StandardCharsets.UTF_8));
        return Collections.unmodifiableMap(updates);
    }

    void publish(FinancialSnapshotAdapter adapter) throws IOException {
        if (adapter == null) throw new IllegalArgumentException("ARGUMENT");
        adapter.publishHistory(updates());
    }

    static final class Builder {
        private byte[] transactionsJson;
        private byte[] reasonsJson;
        private byte[] channelsJson;
        private byte[] recentMovementsJson;
        private byte[] historyLastBalanceJson;
        private Long historyThrough;
        private Integer historyRulesVersion;
        private Integer historySchema;

        Builder transactionsJson(byte[] value) { transactionsJson = value; return this; }
        Builder reasonsJson(byte[] value) { reasonsJson = value; return this; }
        Builder channelsJson(byte[] value) { channelsJson = value; return this; }
        Builder recentMovementsJson(byte[] value) { recentMovementsJson = value; return this; }
        Builder historyLastBalanceJson(byte[] value) { historyLastBalanceJson = value; return this; }
        Builder historyThrough(Long value) { historyThrough = value; return this; }
        Builder historyRulesVersion(Integer value) { historyRulesVersion = value; return this; }
        Builder historySchema(Integer value) { historySchema = value; return this; }

        HistoryScanPublication build() {
            return new HistoryScanPublication(transactionsJson, reasonsJson, channelsJson,
                    recentMovementsJson, historyLastBalanceJson, historyThrough,
                    historyRulesVersion, historySchema);
        }
    }

    private static byte[] clone(byte[] value) { return value == null ? null : value.clone(); }
}
