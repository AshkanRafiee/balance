package com.ashkanrafiee.balance;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test access to the financial generation store.
 *
 * <p>The financial data lives in the store, not in the legacy preference files, so a test that wants
 * a fresh install has to clear the store (and the process cache that goes with it) rather than only
 * the preferences. The seeding helpers are here for the same reason: a test can no longer set up a
 * stored state by editing a preference file, so it writes the components a previous scan would have
 * left behind.
 */
final class FinancialTestStore {
    private FinancialTestStore() {}

    /** Puts the device back to a fresh-install financial state, as a real first launch would be. */
    static void wipe(Context context) throws IOException {
        synchronized (BalanceData.class) {
            FinancialAuthority.forget();
            File directory = FinancialStoreProvider.directory(context);
            if (directory.exists() && !deleteTree(directory))
                throw new IOException("could not clear " + directory);
        }
    }

    /** One pinned read of the whole financial state, for assertions. */
    static FinancialSnapshotAdapter.Snapshot snapshot(Context context) throws IOException {
        return FinancialAuthority.open(context).snapshots().snapshot();
    }

    /** Everything the store has written to disk, as text, to check nothing readable is in it. */
    static String rawStoreText(Context context) throws IOException {
        StringBuilder raw = new StringBuilder();
        collect(FinancialStoreProvider.directory(context), raw);
        return raw.toString();
    }

    private static void collect(File file, StringBuilder raw) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("could not list " + file);
            for (File child : children) collect(child, raw);
            return;
        }
        byte[] bytes = new byte[(int) file.length()];
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            int read = 0;
            while (read < bytes.length) {
                int step = in.read(bytes, read, bytes.length - read);
                if (step < 0) break;
                read += step;
            }
        }
        raw.append(new String(bytes, StandardCharsets.ISO_8859_1));
    }

    /** Overwrites a numeric component, so a test can pretend a scan had recorded something else. */
    static void putLong(Context context, String component, long value) throws IOException {
        commit(context, draft -> {
            draft.put(component, Long.toString(value).getBytes(StandardCharsets.UTF_8));
            return null;
        });
    }

    static void putInt(Context context, String component, int value) throws IOException {
        putLong(context, component, value);
    }

    /** Removes a component, so a test can check what a scan does when it is absent. */
    static void remove(Context context, String component) throws IOException {
        commit(context, draft -> { draft.remove(component); return null; });
    }

    /** Seeds stored balances, as a previous scan would have left them. */
    static void write(Context context, LinkedHashMap<String, Bank> balances) throws IOException {
        commit(context, draft -> { draft.putBalances(balances); return null; });
    }

    /** Seeds stored transactions, as a previous scan would have left them. */
    static void writeTransactions(Context context, List<Transaction> transactions)
            throws IOException {
        commit(context, draft -> { draft.putTransactions(transactions); return null; });
    }

    /** Folds detected reasons into the stored ones on the production terms: add-only, and never
     *  touching a note. The history scan does this inside its publication; a test seeds it directly. */
    static void mergeReasons(Context context, Map<String, String> detected) throws IOException {
        if (detected == null || detected.isEmpty()) return;
        Map<String, String> merged = new LinkedHashMap<>(BalanceData.readReasons(context));
        for (Map.Entry<String, String> e : detected.entrySet())
            if (!merged.containsKey(e.getKey())) merged.put(e.getKey(), e.getValue());
        commit(context, draft -> { draft.putTransactionReasons(merged); return null; });
    }

    /** As {@link #mergeReasons}, for the channels the banks stated. */
    static void mergeChannels(Context context, Map<String, String> detected) throws IOException {
        if (detected == null || detected.isEmpty()) return;
        Map<String, String> merged = new LinkedHashMap<>(BalanceData.readChannels(context));
        for (Map.Entry<String, String> e : detected.entrySet())
            if (!merged.containsKey(e.getKey())) merged.put(e.getKey(), e.getValue());
        commit(context, draft -> { draft.putTransactionChannels(merged); return null; });
    }

    /** Applies the production re-keying a full-history rebuild performs to all three text stores. */
    static void migrateTransactionText(Context context, Map<Transaction, Transaction> replaced)
            throws IOException {
        if (replaced == null || replaced.isEmpty()) return;
        Map<String, String> notes = new LinkedHashMap<>(BalanceData.readNotes(context));
        Map<String, String> reasons = new LinkedHashMap<>(BalanceData.readReasons(context));
        Map<String, String> channels = new LinkedHashMap<>(BalanceData.readChannels(context));
        boolean notesChanged = BalanceData.migrateTextKeys(notes, replaced);
        boolean reasonsChanged = BalanceData.migrateTextKeys(reasons, replaced);
        boolean channelsChanged = BalanceData.migrateTextKeys(channels, replaced);
        if (!notesChanged && !reasonsChanged && !channelsChanged) return;
        commit(context, draft -> {
            if (notesChanged) draft.putTransactionNotes(notes);
            if (reasonsChanged) draft.putTransactionReasons(reasons);
            if (channelsChanged) draft.putTransactionChannels(channels);
            return null;
        });
    }

    private static void commit(Context context, FinancialSnapshotAdapter.Work<Void> work)
            throws IOException {
        FinancialAuthority.open(context).snapshots().transaction(work);
    }

    private static boolean deleteTree(File file) {
        File[] children = file.listFiles();
        if (children == null) return file.delete();
        boolean deleted = true;
        for (File child : children) deleted &= deleteTree(child);
        return file.delete() && deleted;
    }
}
