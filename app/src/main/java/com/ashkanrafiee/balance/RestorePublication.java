package com.ashkanrafiee.balance;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable, already-merged result of a financial restore. */
final class RestorePublication {
    private final byte[] balancesJson;
    private final byte[] transactionsJson;
    private final byte[] notesJson;
    private final byte[] reasonsJson;
    private final byte[] channelsJson;
    private final int added;
    private final int updated;
    private final Set<String> removals;

    private RestorePublication(byte[] balancesJson, byte[] transactionsJson, byte[] notesJson,
            byte[] reasonsJson, byte[] channelsJson, int added, int updated, Set<String> removals) {
        if (balancesJson == null || transactionsJson == null || added < 0 || updated < 0)
            throw new IllegalArgumentException("ARGUMENT");
        this.balancesJson = balancesJson.clone();
        this.transactionsJson = transactionsJson.clone();
        this.notesJson = clone(notesJson);
        this.reasonsJson = clone(reasonsJson);
        this.channelsJson = clone(channelsJson);
        this.added = added;
        this.updated = updated;
        this.removals = removals(removals, Arrays.asList(FinancialSnapshotAdapter.BALANCES,
                FinancialSnapshotAdapter.TRANSACTIONS, FinancialSnapshotAdapter.TRANSACTION_NOTES,
                FinancialSnapshotAdapter.TRANSACTION_REASONS,
                FinancialSnapshotAdapter.TRANSACTION_CHANNELS));
    }

    static Builder builder() { return new Builder(); }

    static RestorePublication prepare(byte[] balancesJson, byte[] transactionsJson,
            byte[] notesJson, byte[] reasonsJson, byte[] channelsJson, int added, int updated) {
        return prepare(balancesJson, transactionsJson, notesJson, reasonsJson, channelsJson, added,
                updated, Collections.emptySet());
    }

    static RestorePublication prepare(byte[] balancesJson, byte[] transactionsJson,
            byte[] notesJson, byte[] reasonsJson, byte[] channelsJson, int added, int updated,
            Set<String> removals) {
        return new RestorePublication(balancesJson, transactionsJson, notesJson, reasonsJson,
                channelsJson, added, updated, removals);
    }

    byte[] balancesJson() { return balancesJson.clone(); }
    byte[] transactionsJson() { return transactionsJson.clone(); }
    byte[] notesJson() { return clone(notesJson); }
    byte[] reasonsJson() { return clone(reasonsJson); }
    byte[] channelsJson() { return clone(channelsJson); }
    int added() { return added; }
    int updated() { return updated; }

    /** The components this publication deletes, because the merge derived none. */
    Set<String> removals() { return removals; }

    /** Returns all prepared restore components for one generation commit. */
    Map<String, byte[]> updates() {
        Map<String, byte[]> updates = new LinkedHashMap<>();
        updates.put(FinancialSnapshotAdapter.BALANCES, balancesJson.clone());
        updates.put(FinancialSnapshotAdapter.TRANSACTIONS, transactionsJson.clone());
        if (notesJson != null) updates.put(FinancialSnapshotAdapter.TRANSACTION_NOTES,
                notesJson.clone());
        if (reasonsJson != null) updates.put(FinancialSnapshotAdapter.TRANSACTION_REASONS,
                reasonsJson.clone());
        if (channelsJson != null) updates.put(FinancialSnapshotAdapter.TRANSACTION_CHANNELS,
                channelsJson.clone());
        return Collections.unmodifiableMap(updates);
    }

    void publish(FinancialSnapshotAdapter adapter) throws IOException {
        if (adapter == null) throw new IllegalArgumentException("ARGUMENT");
        adapter.publish(updates(), removals);
    }

    static final class Builder {
        private byte[] balancesJson;
        private byte[] transactionsJson;
        private byte[] notesJson;
        private byte[] reasonsJson;
        private byte[] channelsJson;
        private int added;
        private int updated;
        private Set<String> removals = Collections.emptySet();

        Builder balancesJson(byte[] value) { balancesJson = value; return this; }
        Builder transactionsJson(byte[] value) { transactionsJson = value; return this; }
        Builder notesJson(byte[] value) { notesJson = value; return this; }
        Builder reasonsJson(byte[] value) { reasonsJson = value; return this; }
        Builder channelsJson(byte[] value) { channelsJson = value; return this; }
        Builder added(int value) { added = value; return this; }
        Builder updated(int value) { updated = value; return this; }
        Builder removals(Set<String> value) { removals = value; return this; }

        RestorePublication build() {
            return new RestorePublication(balancesJson, transactionsJson, notesJson, reasonsJson,
                    channelsJson, added, updated, removals);
        }
    }

    /** Rejects a null, overlapping or foreign removal. */
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
