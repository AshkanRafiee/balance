package com.ashkanrafiee.balance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final Set<String> removals;

    private HistoryScanPublication(byte[] transactionsJson, byte[] reasonsJson, byte[] channelsJson,
            byte[] recentMovementsJson, byte[] historyLastBalanceJson, Long historyThrough,
            Integer historyRulesVersion, Integer historySchema, Set<String> removals) {
        // No transactions is expressed as a deletion, not as a stored empty list, because that is
        // what the store already means by it: the canonical writer drops the component for an
        // empty list, and a reader reads an absent component as no history. Writing the empty list
        // as well would leave two ways to say the same thing and, worse, ask the store to write and
        // delete one component in the same generation, which it rightly refuses.
        this.transactionsJson = clone(transactionsJson);
        this.reasonsJson = clone(reasonsJson);
        this.channelsJson = clone(channelsJson);
        this.recentMovementsJson = clone(recentMovementsJson);
        this.historyLastBalanceJson = clone(historyLastBalanceJson);
        this.historyThrough = historyThrough;
        this.historyRulesVersion = historyRulesVersion;
        this.historySchema = historySchema;
        Set<String> removed = new LinkedHashSet<>(removals(removals, Arrays.asList(
                FinancialSnapshotAdapter.TRANSACTIONS,
                FinancialSnapshotAdapter.TRANSACTION_REASONS,
                FinancialSnapshotAdapter.TRANSACTION_CHANNELS,
                FinancialSnapshotAdapter.RECENT_MOVEMENTS,
                FinancialSnapshotAdapter.HISTORY_LAST_BALANCE,
                FinancialSnapshotAdapter.HISTORY_THROUGH,
                FinancialSnapshotAdapter.HISTORY_RULES_VERSION,
                FinancialSnapshotAdapter.HISTORY_SCHEMA)));
        if (transactionsJson == null) removed.add(FinancialSnapshotAdapter.TRANSACTIONS);
        this.removals = Collections.unmodifiableSet(removed);
    }

    static Builder builder() { return new Builder(); }

    static HistoryScanPublication prepare(byte[] transactionsJson, byte[] reasonsJson,
            byte[] channelsJson, byte[] recentMovementsJson, byte[] historyLastBalanceJson,
            Long historyThrough, Integer historyRulesVersion, Integer historySchema) {
        return prepare(transactionsJson, reasonsJson, channelsJson, recentMovementsJson,
                historyLastBalanceJson, historyThrough, historyRulesVersion, historySchema,
                Collections.emptySet());
    }

    static HistoryScanPublication prepare(byte[] transactionsJson, byte[] reasonsJson,
            byte[] channelsJson, byte[] recentMovementsJson, byte[] historyLastBalanceJson,
            Long historyThrough, Integer historyRulesVersion, Integer historySchema,
            Set<String> removals) {
        return new HistoryScanPublication(transactionsJson, reasonsJson, channelsJson,
                recentMovementsJson, historyLastBalanceJson, historyThrough,
                historyRulesVersion, historySchema, removals);
    }

    byte[] transactionsJson() { return clone(transactionsJson); }
    byte[] reasonsJson() { return clone(reasonsJson); }
    byte[] channelsJson() { return clone(channelsJson); }
    byte[] recentMovementsJson() { return clone(recentMovementsJson); }
    byte[] historyLastBalanceJson() { return clone(historyLastBalanceJson); }
    Long historyThrough() { return historyThrough; }
    Integer historyRulesVersion() { return historyRulesVersion; }
    Integer historySchema() { return historySchema; }

    /** The history-owned components this publication deletes, because the scan derived none. */
    Set<String> removals() { return removals; }

    /** Whether publishing this would leave {@code current} different from what it holds now.
     *
     *  <p>A scan is started every time the history screen opens, and the overwhelmingly common one
     *  finds no movement it had not already stored. Publishing is cheap; telling every reader to
     *  redraw is not, and it costs each of them a read, a re-parse and a rebuild of a screen whose
     *  data came back identical. So the question is worth asking before publishing rather than after,
     *  and it can be answered exactly: the publication knows every component it would write, and a
     *  component is a change only when its bytes differ from the ones already stored.
     *
     *  <p>Only components this publication would write or delete are considered, so an unrelated
     *  difference in {@code current} does not count as a change to make. Deleting a component that
     *  is not there is not a change either.
     */
    boolean changesAnything(Map<String, byte[]> current) {
        if (current == null) throw new IllegalArgumentException("ARGUMENT");
        for (String name : removals) {
            if (current.containsKey(name)) return true;
        }
        for (Map.Entry<String, byte[]> update : updates().entrySet()) {
            if (!Arrays.equals(current.get(update.getKey()), update.getValue())) return true;
        }
        return false;
    }

    /** Returns only the history-owned components represented by this publication. */
    Map<String, byte[]> updates() {
        Map<String, byte[]> updates = new LinkedHashMap<>();
        if (transactionsJson != null) updates.put(FinancialSnapshotAdapter.TRANSACTIONS,
                transactionsJson.clone());
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
        adapter.publishHistory(updates(), removals);
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
        private Set<String> removals = Collections.emptySet();

        Builder transactionsJson(byte[] value) { transactionsJson = value; return this; }
        Builder reasonsJson(byte[] value) { reasonsJson = value; return this; }
        Builder channelsJson(byte[] value) { channelsJson = value; return this; }
        Builder recentMovementsJson(byte[] value) { recentMovementsJson = value; return this; }
        Builder historyLastBalanceJson(byte[] value) { historyLastBalanceJson = value; return this; }
        Builder historyThrough(Long value) { historyThrough = value; return this; }
        Builder historyRulesVersion(Integer value) { historyRulesVersion = value; return this; }
        Builder historySchema(Integer value) { historySchema = value; return this; }
        Builder removals(Set<String> value) { removals = value; return this; }

        HistoryScanPublication build() {
            return new HistoryScanPublication(transactionsJson, reasonsJson, channelsJson,
                    recentMovementsJson, historyLastBalanceJson, historyThrough,
                    historyRulesVersion, historySchema, removals);
        }
    }

    /** Rejects a null, overlapping or non-history-owned removal. */
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

    private static byte[] clone(byte[] value) { return value == null ? null : value.clone(); }
}
