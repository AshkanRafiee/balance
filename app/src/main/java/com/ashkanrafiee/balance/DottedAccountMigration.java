package com.ashkanrafiee.balance;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Repairs only the trailing whitespace formerly captured by the four-part account matcher.
 * Raw sources and their historical observations are deliberately not rewritten. */
final class DottedAccountMigration {
    static final String KEY_COMPLETE = "dotted_account_whitespace_v1";
    private static final Pattern ACCOUNT = Pattern.compile(
        "([0-9]{1,4}\\.[0-9]{1,6}\\.[0-9]{6,12}\\.[0-9]{1,3})[ \\t\\n\\x0B\\f\\r]*");

    private DottedAccountMigration() {}

    static String account(String bank, String value) {
        if (value == null || (!"Pasargad".equals(bank) && !"Wepod".equals(bank))) return value;
        Matcher match = ACCOUNT.matcher(value);
        return match.matches() ? match.group(1) : value;
    }

    static Transaction transaction(Transaction old) {
        String canonical = account(old.bank, old.account);
        return Objects.equals(canonical, old.account) ? old
            : new Transaction(old.bank, canonical, old.date, old.amount, old.balance,
                old.sig, old.content);
    }

    /** Also used at the exclusion boundary: plain bank slots never acquire an account. */
    static String storageKey(String key) {
        int separator = key.indexOf('|');
        if (separator < 0) return key;
        return BalanceData.storageKey(key.substring(0, separator),
            account(key.substring(0, separator), key.substring(separator + 1)));
    }

    /** Sigless metadata may survive even after its transaction was removed by reset(false). */
    static String metadataKey(String key) {
        String[] fields = key.split("\\|", -1);
        if (fields.length != 4 || !fields[1].matches("-?[0-9]+")
                || !fields[2].matches("-?[0-9]+")) return key;
        String canonical = account(fields[0], fields[3]);
        if (Objects.equals(canonical, fields[3])) return key;
        return fields[0] + "|" + fields[1] + "|" + fields[2] + "|" + canonical;
    }

    /** Worker scan lifecycle hook. The marker makes subsequent calls constant-time. */
    static boolean ensure(Context context) throws Exception {
        return ensure(context, null);
    }

    /** Per-call failure seam; no global mutable hook survives a data reset. */
    static boolean ensure(Context context, DataGeneration.PublishHook hook) throws Exception {
        synchronized (BalanceData.class) {
            if (DataGeneration.dataPrefs(context).getBoolean(KEY_COMPLETE, false)) return false;
            try (DataGeneration.Stage stage = DataGeneration.beginStage(context)) {
                stage.setPublishHookForTests(hook);
                boolean changed = normalizeStage(stage.context());
                stage.publish();
                return changed;
            }
        }
    }

    /** Called inside restore's existing stage even if the local one-time marker is already set.
     * Old backups can reintroduce padded accounts and orphan metadata independently of scans. */
    static boolean normalizeStage(Context stage) throws Exception {
        synchronized (BalanceData.class) {
            boolean initial = !DataGeneration.dataPrefs(stage).getBoolean(KEY_COMPLETE, false);
            boolean changed = normalizeBalances(stage);
            changed |= TransactionStore.normalizeDottedAccounts(stage);
            // Old originals are archives after this first copy, not a standing instruction to
            // repopulate a note/tag the user later cleared. Restore handles selected incoming rows.
            if (initial) {
                changed |= MetadataStore.copyNormalizedDottedKeys(stage);
                changed |= normalizeResiduals(stage);
            }
            changed |= normalizeCache(stage, BalanceData.KEY_RECENT_MOVEMENTS, true);
            changed |= normalizeCache(stage, BalanceData.KEY_HISTORY_LAST_BALANCE, false);
            SharedPreferences.Editor editor = DataGeneration.dataPrefs(stage).edit()
                .putBoolean(KEY_COMPLETE, true);
            if (!editor.commit()) throw new Exception("account migration marker could not be saved");
            return changed;
        }
    }

    /** Identity repair only: unrelated seeds/windows and every movement's date are preserved. */
    private static boolean normalizeCache(Context context, String preference, boolean windows)
            throws Exception {
        SharedPreferences prefs = DataGeneration.dataPrefs(context);
        String stored = prefs.getString(preference, null);
        if (stored == null) return false;
        JSONObject original = new JSONObject(stored.startsWith("{") ? stored
            : BalanceData.decryptStorePayload(stored));
        ArrayList<String> keys = new ArrayList<>();
        java.util.Iterator<String> iterator = original.keys();
        while (iterator.hasNext()) keys.add(iterator.next());
        Collections.sort(keys, (a, b) -> {
            boolean ac = a.equals(storageKey(a));
            boolean bc = b.equals(storageKey(b));
            return ac == bc ? a.compareTo(b) : ac ? -1 : 1;
        });
        JSONObject normalized = new JSONObject();
        boolean changed = false;
        for (String key : keys) {
            String destination = storageKey(key);
            changed |= !key.equals(destination);
            Object incoming = original.get(key);
            if (!normalized.has(destination)) normalized.put(destination, incoming);
            else if (windows) {
                JSONArray target = normalized.getJSONArray(destination);
                JSONArray rows = original.getJSONArray(key);
                // This is not transaction deduplication: equal amounts/dates stay distinct.
                for (int i = 0; i < rows.length(); i++) target.put(rows.get(i));
            } else {
                JSONObject target = normalized.optJSONObject(destination);
                JSONObject candidate = original.optJSONObject(key);
                long targetDate = target == null ? Long.MIN_VALUE : target.optLong("d", Long.MIN_VALUE);
                long candidateDate = candidate == null ? Long.MIN_VALUE : candidate.optLong("d", Long.MIN_VALUE);
                if (candidateDate > targetDate) normalized.put(destination, incoming);
            }
        }
        if (changed && !prefs.edit().putString(preference,
                BalanceData.encryptStorePayload(normalized.toString())).commit())
            throw new Exception("account cache repair could not be saved");
        return changed;
    }

    /** Only invoked for selected incoming note provenance, never for unrelated restore sections. */
    static boolean importResidual(Context context, Residual original, Boolean incomingArchive)
            throws Exception {
        String canonicalAccount = account(original.bank, original.account);
        if (Objects.equals(original.account, canonicalAccount)) return false;
        Residual canonical = new Residual(original.bank, canonicalAccount, original.fromDate,
            original.toDate, original.amount, original.movements);
        boolean changed = BalanceData.mergeResidualNoteProvenance(context, canonical);
        return MetadataStore.copyImportedDottedNote(context, BalanceData.noteKey(original),
            BalanceData.noteKey(canonical), incomingArchive == null) | changed;
    }

    private static boolean normalizeBalances(Context context) throws Exception {
        LinkedHashMap<String, Bank> previous = BalanceData.read(context);
        LinkedHashMap<String, Bank> normalized = new LinkedHashMap<>();
        ArrayList<String> keys = new ArrayList<>(previous.keySet());
        // Canonical spelling wins equal timestamps, then lexical original spelling. Never sum.
        Collections.sort(keys, (a, b) -> {
            boolean aCanonical = a.equals(storageKey(a));
            boolean bCanonical = b.equals(storageKey(b));
            return aCanonical == bCanonical ? a.compareTo(b) : aCanonical ? -1 : 1;
        });
        boolean changed = false;
        for (String key : keys) {
            Bank old = previous.get(key);
            String canonicalKey = storageKey(key);
            String canonicalAccount = account(old.name, old.account);
            changed |= !key.equals(canonicalKey) || !Objects.equals(old.account, canonicalAccount);
            Bank candidate = new Bank(old.name, old.amount, old.date, old.sender, canonicalAccount);
            Bank existing = normalized.get(canonicalKey);
            if (existing == null || candidate.date > existing.date) normalized.put(canonicalKey, candidate);
        }
        if (changed && !BalanceData.write(context, normalized))
            throw new Exception("account balances could not be migrated");
        return changed;
    }

    private static boolean normalizeResiduals(Context context) throws Exception {
        SharedPreferences prefs = DataGeneration.dataPrefs(context);
        String stored = prefs.getString(BalanceData.KEY_RESIDUAL_NOTE_PROVENANCE, null);
        if (stored == null) return false;
        // Parse strictly: a failed read must abort the stage, not replace provenance with a subset.
        JSONObject encoded = new JSONObject(BalanceData.decryptStorePayload(stored));
        Map<String, String> aliases = new LinkedHashMap<>();
        ArrayList<String> keys = new ArrayList<>();
        java.util.Iterator<String> iterator = encoded.keys();
        while (iterator.hasNext()) keys.add(iterator.next());
        Collections.sort(keys);
        boolean changed = false;
        for (String key : keys) {
            JSONObject object = new JSONObject(encoded.getString(key));
            String bank = object.getString("bank");
            String oldAccount = object.has("account") && !object.isNull("account")
                ? object.getString("account") : null;
            String canonicalAccount = account(bank, oldAccount);
            if (Objects.equals(oldAccount, canonicalAccount)) continue;
            Residual old = new Residual(bank, oldAccount, object.getLong("from"),
                object.getLong("to"), object.getLong("amount"), object.getInt("movements"));
            if (!key.equals(BalanceData.noteKey(old)))
                throw new Exception("residual provenance identity mismatch");
            Residual canonical = new Residual(bank, canonicalAccount, old.fromDate, old.toDate,
                old.amount, old.movements);
            String destination = BalanceData.noteKey(canonical);
            aliases.put(key, destination);
            if (!encoded.has(destination)) {
                object.put("account", canonicalAccount);
                encoded.put(destination, object.toString());
                changed = true;
            }
        }
        changed |= MetadataStore.copyKeysPreservingSources(context, aliases);
        // Keep original provenance too: conflicting notes remain recoverable under their old keys.
        if (changed && !prefs.edit().putString(BalanceData.KEY_RESIDUAL_NOTE_PROVENANCE,
                BalanceData.encryptStorePayload(encoded.toString())).commit())
            throw new Exception("residual provenance migration could not be saved");
        return changed;
    }
}
