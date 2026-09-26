package com.ashkanrafiee.balance;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.Telephony;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

final class BalanceData {
    static final String PREFS_DATA = "balance_data";
    static final String KEY_BALANCES = "balances";
    static final String KEY_TRANSACTIONS = "transactions";
    static final String KEY_TX_NOTES = "transaction_notes";
    /** The reasons the bank itself stated, keyed exactly like the notes. Kept in a store of its own so
     *  a detected reason can never overwrite what the user wrote — and never touches it at all. */
    static final String KEY_TX_REASONS = "transaction_reasons";
    /** Upper bound on one transaction note, so a huge paste cannot bloat the encrypted store. */
    static final int MAX_NOTE_LENGTH = 500;
    static final String PREFS_PREF = "balance_preferences";
    static final String KEY_HIDDEN = "balances_hidden";
    static final String KEY_WIDGET_HIDDEN = "widget_balances_hidden";
    static final String KEY_AUTO_HIDE = "balances_auto_hide";
    static final String KEY_SCANNED_THROUGH = "scanned_through";
    static final String KEY_RULES_VERSION = "rules_version";
    static final String KEY_HISTORY_THROUGH = "history_through";
    static final String KEY_HISTORY_RULES_VERSION = "history_rules_version";
    /** Which schema generation of the stored history this build writes. Bumped when existing
     *  entries have to be rebuilt from the inbox to acquire a datum older entries lack — today the
     *  balance each movement reported ({@link Transaction#balance}), without which {@link Residual}
     *  cannot prove a missing message. Tracked beside {@link #KEY_HISTORY_RULES_VERSION} rather than
     *  folded into it, so a schema change never masquerades as a change of parsing rules. */
    static final String KEY_HISTORY_SCHEMA = "history_schema";
    static final int HISTORY_SCHEMA = 1;
    static final String KEY_HISTORY_LAST_BALANCE = "history_last_balance";
    static final String KEY_EXCLUDED = "excluded_banks";
    static final String KEY_SORT = "sort_mode";
    static final String KEY_STALE_DAYS = "stale_days";
    static final int DEFAULT_STALE_DAYS = 7;
    static final String KEY_ONBOARDING_SEEN = "onboarding_seen";

    /** Sort modes for the bank list. Each pair (balance / update date) has a reverse variant so
     *  re-selecting the same sort flips its direction. The list is always sorted; fresh installs
     *  default to highest balance first. */
    static final int SORT_BALANCE_HIGH = 1;
    static final int SORT_BALANCE_LOW = 2;
    static final int SORT_DATE_RECENT = 3;
    static final int SORT_DATE_OLDEST = 4;

    /** Bumped whenever the movement-message recognition rules change, forcing a full history re-scan. */
    static final int HISTORY_RULES_VERSION = BankRules.VERSION;

    /** Persisted recent-movement window for balance-chain reconciliation across split scans. */
    static final String KEY_RECENT_MOVEMENTS = "recent_movements";
    private static final long RECENT_WINDOW_MS = 5 * 60 * 1000;
    private static final int MAX_RECENT_ENTRIES = 16;

    /** True while a history re-scan is running, so a second trigger (app open + history open) is a
     *  no-op instead of a duplicate pass. */
    static volatile boolean HISTORY_SCANNING;

    private static final List<Runnable> historyListeners = new ArrayList<>();

    private static final String TAG = "BalanceData";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "balance_enc_key";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final Pattern balance = Pattern.compile(
        "(?:\u0645\u0648\u062c\u0648\u062f\u06cc \u062d\u0633\u0627\u0628" +
        "|\u0645\u0627\u0646\u062f\u0647 \u062d\u0633\u0627\u0628" +
        "|\u0645\u0648\u062c\u0648\u062f\u06cc" +
        "|\u0645\u0627\u0646\u062f\u0647" +
        "|available balance|balance|bal)" +
        "[^\\d]{0,12}?([0-9][0-9,]*)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern otp = Pattern.compile(
        "(?<![\u0621-\u0640A-Za-z])" +
        "(?:\u0631\u0645\u0632|\u067e\u0648\u06cc\u0627" +
        "|\u06a9\u062f \\s*\u062a\u0627\u06cc\u06cc\u062f" +
        "|\u06a9\u062f \\s*\u062a\u0623\u06cc\u06cc\u062f" +
        "|otp|code)",
        Pattern.CASE_INSENSITIVE);
    /** The transaction amount in a money-movement message. Most banks write it after the "مبلغ"
     *  (amount) label, possibly with an explicit sign (some banks, e.g. Parsian, write "مبلغ:500,000-"
     *  where the trailing minus marks a withdrawal). */
    private static final Pattern amountLabel = Pattern.compile(
        "(?:\u0645\u0628\u0644\u063A)[^\\d]{0,12}?([+-]?\\s*[0-9][0-9,]*\\s*[+-]?)");
    /** The amount written directly after a deposit/withdrawal label, as Tejarat does with
     *  "برداشت: 70,014,000 ریال". Matching one of these also resolves the direction: a label that
     *  feeds the amount is authoritative, so a payment-method word like "پرداخت" in the same message
     *  does not make a deposit look ambiguous. */
    private static final Pattern depositLabel = Pattern.compile(
        "(?:\u0648\u0627\u0631\u06CC\u0632)[^\\d]{0,12}?([0-9][0-9,]*)");
    private static final Pattern withdrawalLabel = Pattern.compile(
        "(?:\u0628\u0631\u062F\u0627\u0634\u062A)[^\\d]{0,12}?([0-9][0-9,]*)");
    /** A bare number standing next to "ریال" (e.g. Blu's "400,000 ریال از حساب شما پرید"). The stated
     *  resulting balance is removed first, so this captures the moved amount rather than the balance. */
    private static final Pattern rialAmount = Pattern.compile("([0-9][0-9,]*)\\s*\u0631\u06CC\u0627\u0644");
    /** A bare, signed amount standing at the start of the message, as Resalat writes it
     *  ("-200,000,000" on its own line, resulting balance on the last). The explicit sign tells the
     *  direction, so no label or keyword is needed. */
    private static final Pattern signedAmount = Pattern.compile(
        "^\\s*([+-])\\s*([0-9][0-9,]*)", Pattern.MULTILINE);
    /** A bare, signed amount on its own line with the sign after the number, as Mehr Iran writes it
     *  ("400,000-" on its own line, resulting balance on the last). Allowing RTL bidi marks around
     *  the amount and holding the whole line to the shape "digits, optional sign" keeps unsigned
     *  balances, account lines and date lines from matching; the explicit sign gives the direction. */
    private static final Pattern bareSignedAmount = Pattern.compile(
        "(?m)^[ \\t\\u202A-\\u202E]*([0-9][0-9,]*)[ \\t\\u202A-\\u202E]*([+-])[ \\t\\u202A-\\u202E]*$");
    /** A line of "<label>:<amount><sign>" where the sign trails the number, as Melli writes it
     *  ("انتقالي:1,000,000-", "خريداينترنتي:7,600,000-", "حواله پل:7,700,000+"). The line ending in
     *  an explicit sign distinguishes the moved amount from balances and account numbers (which are
     *  unsigned), and the sign itself carries the direction — so "حواله پل:7,700,000+" is a deposit
     *  even though "حواله" is a withdrawal keyword. */
    private static final Pattern labeledSignedAmount = Pattern.compile(
        "(?m)^([^\\r\\n:0-9][^\\r\\n:]{0,39}):[ \\t]*([0-9][0-9,]*)[ \\t]*([+-])[ \\t]*$");
    private static final String[] DEPOSIT_KEYWORDS = {
        "\u0648\u0627\u0631\u06cc\u0632", "\u062f\u0631\u06cc\u0627\u0641\u062a",
        "\u0628\u0633\u062a\u0627\u0646\u06a9\u0627\u0631", "\u0627\u0641\u0632\u0627\u06cc\u0634",
        "\u0639\u0648\u062f\u062a", "\u0628\u0631\u06af\u0634\u062a",
        "\u0628\u0647 \u062d\u0633\u0627\u0628", "\u0646\u0634\u0633\u062a"};
    private static final String[] WITHDRAWAL_KEYWORDS = {
        "\u0628\u0631\u062f\u0627\u0634\u062a", "\u062e\u0631\u06cc\u062f",
        "\u062e\u0631\u06cc\u062f\u0627\u0631\u06cc", "\u067e\u0631\u062f\u0627\u062e\u062a",
        "\u0628\u062f\u0647\u06a9\u0627\u0631", "\u06a9\u0627\u0647\u0634",
        "\u0627\u0646\u062a\u0642\u0627\u0644", "\u062d\u0648\u0627\u0644\u0647",
        "\u06a9\u0627\u0631\u0645\u0632\u062f", "\u0642\u0628\u0636"};

    private BalanceData() {}

    private static SecretKey createKey() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT
            | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
            .build());
        return kg.generateKey();
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return (SecretKey) ks.getKey(KEY_ALIAS, null);
        return createKey();
    }

    private static String encrypt(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORM);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    private static String decrypt(String blob) throws Exception {
        byte[] in = Base64.decode(blob, Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance(TRANSFORM);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_BITS, in, 0, 12);
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec);
        return new String(cipher.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
    }

    static LinkedHashMap<String, Bank> read(Context context) {
        LinkedHashMap<String, Bank> map = new LinkedHashMap<>();
        try {
            String stored = context.getSharedPreferences(PREFS_DATA, Context.MODE_PRIVATE)
                .getString(KEY_BALANCES, null);
            if (stored == null) return map;
            boolean legacy = stored.indexOf('{') == 0;
            String json = legacy ? stored : decrypt(stored);
            parse(map, json);
            if (legacy && !map.isEmpty()) write(context, map);
        } catch (Exception e) {
            Log.w(TAG, "read failed", e);
            return map;
        }
        return map;
    }

    private static LinkedHashMap<String, Bank> parse(LinkedHashMap<String, Bank> map, String json) {
        try {
            JSONObject obj = new JSONObject(json);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject entry = obj.getJSONObject(key);
                String account = entry.has("account") && !entry.isNull("account")
                    ? entry.getString("account") : null;
                if (account == null) account = accountOfKey(key);
                map.put(key, new Bank(bankOfKey(key), entry.getLong("amount"),
                    entry.getLong("date"), entry.getString("sender"), account));
            }
        } catch (Exception e) {
            Log.w(TAG, "stored balances unreadable; starting empty", e);
        }
        return map;
    }

    /** Serializes the supplied balances as the same JSON shape used for the local store. */
    static String serialize(LinkedHashMap<String, Bank> map) throws Exception {
        JSONObject obj = new JSONObject();
        for (java.util.Map.Entry<String, Bank> e : map.entrySet()) {
            Bank b = e.getValue();
            JSONObject entry = new JSONObject();
            entry.put("amount", b.amount);
            entry.put("date", b.date);
            entry.put("sender", b.sender);
            if (b.account != null) entry.put("account", b.account);
            obj.put(e.getKey(), entry);
        }
        return obj.toString();
    }

    /** Parses a balance JSON (as produced by {@link #serialize}) into a fresh map. */
    static LinkedHashMap<String, Bank> deserialize(String json) {
        LinkedHashMap<String, Bank> map = new LinkedHashMap<>();
        parse(map, json);
        return map;
    }

    /** The composite storage key for a bank balance/transaction slot: the plain bank name when the
     *  message carries no account number, or {@code bank|account} when it does. Bank names never
     *  contain '|', and neither do account numbers. */
    static String storageKey(String bank, String account) {
        return account == null ? bank : bank + "|" + account;
    }

    /** The canonical bank name embedded in a composite storage key. */
    static String bankOfKey(String key) {
        int i = key.indexOf('|');
        return i < 0 ? key : key.substring(0, i);
    }

    /** The account number embedded in a composite storage key, or null for a plain bank key. */
    private static String accountOfKey(String key) {
        int i = key.indexOf('|');
        return i < 0 ? null : key.substring(i + 1);
    }

    /** Reads all saved transactions, newest first, the order in which they were appended. */
    static List<Transaction> readTransactions(Context context) {
        try {
            String stored = context.getSharedPreferences(PREFS_DATA, Context.MODE_PRIVATE)
                .getString(KEY_TRANSACTIONS, null);
            if (stored == null) return new ArrayList<>();
            String json = stored.indexOf('{') == 0 ? stored : decrypt(stored);
            List<Transaction> list = parseTransactions(json);
            if (stored.indexOf('{') == 0 && !list.isEmpty()) writeTransactions(context, list);
            return list;
        } catch (Exception e) {
            Log.w(TAG, "readTransactions failed", e);
            return new ArrayList<>();
        }
    }

    /** Persists the supplied transactions encrypted under {@link #KEY_TRANSACTIONS}. */
    static void writeTransactions(Context context, List<Transaction> txs) {
        try {
            if (txs.isEmpty()) {
                context.getSharedPreferences(PREFS_DATA, Context.MODE_PRIVATE).edit()
                    .remove(KEY_TRANSACTIONS).apply();
                return;
            }
            context.getSharedPreferences(PREFS_DATA, Context.MODE_PRIVATE).edit()
                .putString(KEY_TRANSACTIONS, encrypt(serializeTransactions(txs))).apply();
        } catch (Exception e) {
            Log.w(TAG, "writeTransactions failed", e);
        }
    }

    /** Serializes transactions to the JSON shape used for the local store and the backup payload. The
     *  message fingerprint, account number, reported balance and content digest are optional and
     *  skipped when absent, so backups stay readable both ways. */
    static String serializeTransactions(List<Transaction> txs) throws Exception {
        JSONArray arr = new JSONArray();
        for (Transaction t : txs) {
            JSONObject e = new JSONObject()
                .put("bank", t.bank)
                .put("date", t.date)
                .put("amount", t.amount);
            if (t.account != null) e.put("account", t.account);
            if (t.balance != null) e.put("bal", t.balance.longValue());
            if (t.sig != null) e.put("sig", t.sig);
            if (t.content != null) e.put("content", t.content);
            arr.put(e);
        }
        return new JSONObject().put(KEY_TRANSACTIONS, arr).toString();
    }

    /** Parses a transaction JSON (as produced by {@link #serializeTransactions}) into a fresh list. */
    static List<Transaction> deserializeTransactions(String json) {
        return parseTransactions(json);
    }

    private static List<Transaction> parseTransactions(String json) {
        List<Transaction> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONObject(json).optJSONArray(KEY_TRANSACTIONS);
            if (arr == null) return list;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.getJSONObject(i);
                String sig = e.has("sig") && !e.isNull("sig") ? e.getString("sig") : null;
                String account = e.has("account") && !e.isNull("account") ? e.getString("account") : null;
                String content = e.has("content") && !e.isNull("content") ? e.getString("content") : null;
                Long balance = e.has("bal") && !e.isNull("bal") ? e.getLong("bal") : null;
                list.add(new Transaction(e.getString("bank"), account, e.getLong("date"),
                    e.getLong("amount"), balance, sig, content));
            }
        } catch (Exception ex) {
            Log.w(TAG, "parseTransactions failed");
        }
        return list;
    }

    // ====================================================================
    // Transaction notes
    // ====================================================================

    /** The identity a note is stored under: the parse-independent content digest when the entry has one
     *  (so the note follows the same physical SMS however parsing rules evolve), else the dedup
     *  identity of legacy entries written before content digests existed. Never derived from the
     *  amount or the resulting balance, so changing a note cannot alter any dedup fingerprint. */
    static String noteKey(Transaction t) {
        return t.content != null ? "c:" + t.content : txIdentityKey(t);
    }

    /** Reads every saved note ({@code noteKey → text}), newest-first irrelevant since it is a plain
     *  lookup map. A missing or corrupt store reads as empty, never null. */
    static Map<String, String> readNotes(Context context) {
        return readTextStore(context, KEY_TX_NOTES);
    }

    /** Persists the supplied notes encrypted under {@link #KEY_TX_NOTES}. An empty map removes the
     *  key so a notes-free device stores nothing at all. */
    static void writeNotes(Context context, Map<String, String> notes) {
        writeTextStore(context, KEY_TX_NOTES, notes);
    }

    /** Every reason the bank stated ({@code noteKey → title}), the same plain lookup map the notes are
     *  read as. Never null, and entirely separate from them. */
    static Map<String, String> readReasons(Context context) {
        return readTextStore(context, KEY_TX_REASONS);
    }

    /** Persists the supplied reasons encrypted under {@link #KEY_TX_REASONS}. */
    static void writeReasons(Context context, Map<String, String> reasons) {
        writeTextStore(context, KEY_TX_REASONS, reasons);
    }

    /** Reads one encrypted {@code key → text} store, or an empty map when it holds nothing or cannot
     *  be read. A value left in plaintext by an older build is still accepted. */
    private static Map<String, String> readTextStore(Context context, String key) {
        try {
            String stored = context.getSharedPreferences(PREFS_DATA, Context.MODE_PRIVATE)
                .getString(key, null);
            if (stored == null) return new LinkedHashMap<>();
            String json = stored.indexOf('{') == 0 ? stored : decrypt(stored);
            return new LinkedHashMap<>(deserializeTextMap(json));
        } catch (Exception e) {
            Log.w(TAG, "text store read failed", e);
            return new LinkedHashMap<>();
        }
    }

    /** Writes one {@code key → text} store encrypted under {@code key}. An empty map removes the key,
     *  so a device with nothing to say about this store keeps nothing at all. */
    private static void writeTextStore(Context context, String key, Map<String, String> map) {
        try {
            android.content.SharedPreferences.Editor e =
                context.getSharedPreferences(PREFS_DATA, Context.MODE_PRIVATE).edit();
            if (map == null || map.isEmpty()) {
                e.remove(key).apply();
                return;
            }
            e.putString(key, encrypt(serializeTextMap(map))).apply();
        } catch (Exception ex) {
            Log.w(TAG, "text store write failed", ex);
        }
    }

    /** Folds the reasons one scan detected into the stored ones. Detection only ever adds: a reason
     *  already stored for a movement is kept as it is, nothing is removed, and the user's own notes are
     *  not touched at all — they live in a store of their own, so a detected reason can never
     *  overwrite a note, and clearing a note can never lose what the bank said. Nothing is written when
     *  every detected reason was already stored, so an unchanged inbox costs no write. */
    static void mergeReasons(Context context, Map<String, String> detected) {
        if (detected == null || detected.isEmpty()) return;
        Map<String, String> reasons = readReasons(context);
        boolean changed = false;
        for (Map.Entry<String, String> e : detected.entrySet()) {
            if (reasons.containsKey(e.getKey())) continue;
            reasons.put(e.getKey(), e.getValue());
            changed = true;
        }
        if (changed) writeReasons(context, reasons);
    }

    /** Serializes a per-transaction text map (the notes, or the reasons) to the JSON shape used for
     *  the local stores and the backup payload. Empty or null values are dropped, so a cleared entry
     *  vanishes from the map. */
    static String serializeTextMap(Map<String, String> text) throws Exception {
        JSONObject o = new JSONObject();
        if (text != null) {
            for (Map.Entry<String, String> e : text.entrySet()) {
                String v = e.getValue();
                if (v != null && !v.isEmpty()) o.put(e.getKey(), v);
            }
        }
        return o.toString();
    }

    /** Parses a per-transaction text map (as produced by {@link #serializeTextMap}) into a fresh map. */
    static Map<String, String> deserializeTextMap(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            JSONObject o = new JSONObject(json);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String key = it.next();
                String v = o.optString(key, null);
                if (v != null && !v.isEmpty()) out.put(key, v);
            }
        } catch (Exception ex) {
            Log.w(TAG, "deserializeTextMap failed");
        }
        return out;
    }

    /** The transaction's note, or null when none is saved. */
    static String getNote(Context context, Transaction t) {
        return readNotes(context).get(noteKey(t));
    }

    /** Saves (or with a blank input, clears) the note for a transaction. The text is trimmed and
     *  capped at {@link #MAX_NOTE_LENGTH}, so hostile or accidental multi-megabyte pastes are cut
     *  down to a bounded size before they are written encrypted. */
    static void setNote(Context context, Transaction t, String text) {
        Map<String, String> notes = readNotes(context);
        String key = noteKey(t);
        if (text == null) {
            notes.remove(key);
            writeNotes(context, notes);
            return;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            notes.remove(key);
            writeNotes(context, notes);
            return;
        }
        notes.put(key, capNoteLength(trimmed));
        writeNotes(context, notes);
    }

    /** Trims a text to {@link #MAX_NOTE_LENGTH} characters without splitting a surrogate pair. */
    private static String capNoteLength(String s) {
        if (s.length() <= MAX_NOTE_LENGTH) return s;
        int end = MAX_NOTE_LENGTH;
        while (end > 0 && Character.isLowSurrogate(s.charAt(end))) end--;
        return s.substring(0, end);
    }

    /** Moves the text attached to a movement that a full-history rebuild would orphan: when a stored
     *  entry {@code from} is replaced by a freshly parsed {@code to}, both the note the user wrote and
     *  the reason the bank stated follow the movement to the latter's key. Keyed by the legacy identity
     *  triple before content digests existed, so the handover happens exactly when a newer rules
     *  version re-parses the same SMS into a content-bearing entry — the one case where
     *  {@link #noteKey} changes between the same physical message. A destination that already carries
     *  text keeps its own. */
    static void migrateTransactionText(Context context, Map<Transaction, Transaction> replaced) {
        Map<String, String> notes = readNotes(context);
        if (migrateTextKeys(notes, replaced)) writeNotes(context, notes);
        Map<String, String> reasons = readReasons(context);
        if (migrateTextKeys(reasons, replaced)) writeReasons(context, reasons);
    }

    /** Moves the text of every replaced entry to its replacement's key, in place. Returns whether
     *  anything moved, so the caller writes the store only when it changed. */
    private static boolean migrateTextKeys(Map<String, String> text,
            Map<Transaction, Transaction> replaced) {
        boolean changed = false;
        for (Map.Entry<Transaction, Transaction> e : replaced.entrySet()) {
            String from = noteKey(e.getKey());
            String to = noteKey(e.getValue());
            if (from.equals(to)) continue;
            String value = text.remove(from);
            if (value == null || text.containsKey(to)) continue;
            text.put(to, value);
            changed = true;
        }
        return changed;
    }

    static void write(Context context, LinkedHashMap<String, Bank> map) {
        try {
            String existing = context.getSharedPreferences(PREFS_DATA, Context.MODE_PRIVATE)
                .getString(KEY_BALANCES, null);
            if (map.isEmpty() && existing != null) {
                Log.w(TAG, "refusing to persist empty balances over existing data");
                return;
            }
            String json = serialize(map);
            context.getSharedPreferences(PREFS_DATA, Context.MODE_PRIVATE).edit()
                .putString(KEY_BALANCES, encrypt(json)).apply();
        } catch (Exception e) {
            Log.w(TAG, "write failed", e);
        }
    }

    /** Discards every saved balance and transaction and forgets both scan watermarks, so the next scans
     *  behave like a fresh install and rebuild from the messages currently in the inbox. Display
     *  preferences are deliberately untouched — the hide/unmask toggle, the language and the sort mode
     *  are choices, not data (a data reset must not dump the user back to defaults); the excluded
     *  entries are forgotten too, because a fresh install has no exclusions. Transaction notes are a
     *  hard-won recollection, so they are kept unless the user explicitly opts into deleting them. The
     *  detected reasons go either way: they are the bank's own words, re-read from the messages the
     *  rebuild below reprocesses, and the transactions they describe are deleted here with everything
     *  else — keeping them would only leave entries nothing points at. */
    static void reset(Context context, boolean alsoNotes) {
        android.content.SharedPreferences.Editor data =
            context.getSharedPreferences(PREFS_DATA, Context.MODE_PRIVATE).edit()
                .remove(KEY_BALANCES).remove(KEY_TRANSACTIONS).remove(KEY_HISTORY_LAST_BALANCE)
                .remove(KEY_RECENT_MOVEMENTS).remove(KEY_TX_REASONS);
        if (alsoNotes) data.remove(KEY_TX_NOTES);
        data.apply();
        context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE).edit()
            .remove(KEY_SCANNED_THROUGH)
            .remove(KEY_RULES_VERSION)
            .remove(KEY_HISTORY_THROUGH)
            .remove(KEY_HISTORY_RULES_VERSION)
            .remove(KEY_HISTORY_SCHEMA)
            .remove(KEY_EXCLUDED)
            .apply();
    }

    static boolean isHidden(Context context) {
        return context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDDEN, false);
    }

    /** The widget keeps its own mask state so hiding the widget never hides (or reveals) the app,
     *  and vice versa. The initial value follows the app mask so a long-time user's expectation on
     *  a freshly added widget matches their existing preference. */
    static boolean isWidgetHidden(Context context) {
        return context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIDGET_HIDDEN, isHidden(context));
    }

    static void setWidgetHidden(Context context, boolean hidden) {
        context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_WIDGET_HIDDEN, hidden).apply();
    }

    /** Whether balances are re-masked automatically whenever the app goes to the background. */
    static boolean isAutoHide(Context context) {
        return context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_HIDE, false);
    }

    static void setAutoHide(Context context, boolean on) {
        context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_HIDE, on).apply();
    }

    /** The set of excluded entries, each stored under its {@link #storageKey} — so exclusion is per
     *  account: {@code bank} for an account-less bank, {@code bank|account} for a specific one. */
    static Set<String> getExcluded(Context context) {
        Set<String> set = new HashSet<>();
        try {
            String raw = context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE)
                .getString(KEY_EXCLUDED, null);
            if (raw == null) return set;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) set.add(arr.getString(i));
        } catch (Exception e) {
            Log.w(TAG, "excluded entries unreadable; treating as none", e);
        }
        return set;
    }

    static void setExcluded(Context context, Set<String> excluded) {
        JSONArray arr = new JSONArray();
        for (String key : excluded) arr.put(key);
        context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_EXCLUDED, arr.toString()).apply();
    }

    static boolean isExcluded(Context context, String key) {
        return getExcluded(context).contains(key);
    }

    static void toggleExcluded(Context context, String key) {
        Set<String> excluded = getExcluded(context);
        if (excluded.contains(key)) excluded.remove(key);
        else excluded.add(key);
        setExcluded(context, excluded);
    }

    /** Orders the supplied banks for display: included banks first (sorted by the given mode),
     *  followed by excluded accounts (also sorted among themselves). The input map's own order is
     *  never modified. */
    static List<Bank> orderForDisplay(Map<String, Bank> banks, Set<String> excluded) {
        return orderForDisplay(banks, excluded, SORT_BALANCE_HIGH);
    }

    static List<Bank> orderForDisplay(Map<String, Bank> banks, Set<String> excluded, int sort) {
        List<Bank> included = new ArrayList<>();
        List<Bank> excludedBanks = new ArrayList<>();
        for (Bank b : banks.values()) {
            if (excluded.contains(storageKey(b.name, b.account))) excludedBanks.add(b);
            else included.add(b);
        }
        sortBanks(included, sort);
        sortBanks(excludedBanks, sort);
        included.addAll(excludedBanks);
        return included;
    }

    private static void sortBanks(List<Bank> banks, int sort) {
        switch (sort) {
            case SORT_BALANCE_HIGH:
                banks.sort((a, b) -> Long.compare(b.amount, a.amount));
                break;
            case SORT_BALANCE_LOW:
                banks.sort((a, b) -> Long.compare(a.amount, b.amount));
                break;
            case SORT_DATE_RECENT:
                banks.sort((a, b) -> Long.compare(b.date, a.date));
                break;
            case SORT_DATE_OLDEST:
                banks.sort((a, b) -> Long.compare(a.date, b.date));
                break;
            default:
                break;
        }
    }

    /** Orders the stored balances for the bank-list UI as one flat card per bank entry: every
     *  account of a multi-account bank ranks by its own sort key, exactly like a separate bank.
     *  Included entries come first (sorted by the given mode), then excluded entries in the
     *  same order. The input map's own order is never modified. */
    static List<List<Bank>> groupedForDisplay(Map<String, Bank> banks, Set<String> excluded, int sort) {
        List<Bank> included = new ArrayList<>();
        List<Bank> excludedBanks = new ArrayList<>();
        for (Bank b : banks.values()) {
            if (excluded.contains(storageKey(b.name, b.account))) excludedBanks.add(b);
            else included.add(b);
        }
        sortBanks(included, sort);
        sortBanks(excludedBanks, sort);
        included.addAll(excludedBanks);
        List<List<Bank>> out = new ArrayList<>();
        for (Bank b : included)
            out.add(java.util.Collections.singletonList(b));
        return out;
    }

    /** The persisted bank-list sort mode, {@link #SORT_BALANCE_HIGH} when never chosen. */
    static int getSort(Context context) {
        return context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE).getInt(KEY_SORT, SORT_BALANCE_HIGH);
    }

    static void setSort(Context context, int mode) {
        context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE).edit().putInt(KEY_SORT, mode).apply();
    }

    /** How many days without a balance SMS mark a bank's balance stale. Zero or negative means never
     *  (the freshness warning is off). Shared with the widget, so both call this one source. */
    static int getStaleDays(Context context) {
        int n = context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE)
            .getInt(KEY_STALE_DAYS, DEFAULT_STALE_DAYS);
        return n > 0 ? n : 0;
    }

    /** Sets the staleness threshold in days; 0 (or any non-positive value) disables the warning. */
    static void setStaleDays(Context context, int days) {
        context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE)
            .edit().putInt(KEY_STALE_DAYS, Math.max(0, days)).apply();
    }

    /** How many whole days a balance has gone without a refresh, or 0 when its SMS date is unknown
     *  (or the freshness warning is off). A date of 0 (a restored or hand-entered balance) is never
     *  flagged: there is nothing to measure freshness against. */
    static int staleDays(Context context, long date) {
        if (date <= 0 || getStaleDays(context) <= 0) return 0;
        int days = (int) ((System.currentTimeMillis() - date) / 86400000L);
        return days > 0 ? days : 0;
    }

    /** Whether a balance is stale: its last SMS is older than the configured freshness window. */
    static boolean isStale(Context context, long date) {
        int threshold = getStaleDays(context);
        if (threshold <= 0 || date <= 0) return false;
        return (System.currentTimeMillis() - date) > threshold * 86400000L;
    }

    /** Whether the first-run introduction has already been shown. Lives in the preferences file (not
     *  the data file) so it survives the in-app "Reset & rescan" — the store is wiped, the intro is not. */
    static boolean isOnboardingSeen(Context context) {
        return context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDING_SEEN, false);
    }

    /** Marks the first-run introduction as seen, so it is not shown again. */
    static void setOnboardingSeen(Context context, boolean seen) {
        context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ONBOARDING_SEEN, seen).apply();
    }

    /** Scans the inbox for balance messages and merges them into the saved store, then persists the
     *  result. Returns how many bank balance messages were matched.
     *
     *  This method is synchronized and re-reads the authoritative store INSIDE the lock: the caller's
     *  map can be a stale snapshot taken the moment before an overlapping scan won the lock, so merging
     *  into a fresh copy guarantees one scan can never overwrite a newer balance written by another (the
     *  fresh copy is merged, persisted, and then copied back into the caller's map so its view stays
     *  authoritative too). Balances are persisted BEFORE the watermark is advanced, so a process death
     *  in between can only cause a harmless re-read of already-scanned rows, never a permanently skipped
     *  message.
     *
     *  Incremental reads only query messages newer than the last-scanned watermark, so refreshes stay
     *  fast no matter how large the inbox grows, and each bank only ever receives newer data. The full
     *  first scan (fresh install, or after the supported-bank list changes) reads the whole inbox so
     *  every bank keeps its newest balance message, but stops as soon as every reachable supported
     *  sender has matched once. Senders are resolved before parsing, skipping the regex pass for the
     *  non-bank tail. */
    static synchronized int scanSms(Context context, LinkedHashMap<String, Bank> saved) {
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
            return 0;
        LinkedHashMap<String, Bank> current = read(context);
        SharedPreferences prefs = context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE);
        long watermark = prefs.getLong(KEY_SCANNED_THROUGH, 0);
        int rulesVersion = BankRules.VERSION;
        // Inbox dates are trusted as-served and each row is clamped to the device clock, so a forged
        // or clock-skewed message dated in the future can never push the watermark past real time.
        // A watermark that now lies AHEAD of the clock means the device clock moved backward since the
        // last scan (travel, NTP correction): messages arriving after the rollback are dated before the
        // watermark, so a plain incremental read would skip every one of them. Treat that as a full
        // rescan, which re-reads the whole inbox and re-pins the watermark under the current clock.
        long now = System.currentTimeMillis();
        boolean full = watermark == 0 || watermark > now
            || prefs.getInt(KEY_RULES_VERSION, -1) != rulesVersion;
        if (full) watermark = 0;
        int matched = 0;
        long newest = 0;
        String selection = !full ? Telephony.Sms.DATE + " > ?" : null;
        String[] args = selection != null ? new String[]{Long.toString(watermark)} : null;

        // Collect every matching message (sender, body, the time it can be dated to, stated
        // balance). A bank that sends a fee and the transfer it belongs to in the wrong order
        // surfaces here as two rows whose times disagree with their true chronology; the
        // recent-movements window below reconciles that before the balance is chosen. A full scan reads the whole inbox (like the
        // history scan), because with per-account composite keys the newest message of one account
        // never proves another account's balance is current.
        Map<String, List<Object[]>> rowsByKey = new LinkedHashMap<>();
        try (Cursor cursor = context.getContentResolver().query(
            Telephony.Sms.Inbox.CONTENT_URI,
            new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE},
            selection, args, Telephony.Sms.DATE + " DESC")) {
            if (cursor == null) return 0;
            while (cursor.moveToNext()) {
                long arrival = Math.min(cursor.getLong(2), now);
                if (newest < arrival) newest = arrival;
                String sender = cursor.getString(0);
                String bank = BankRules.resolve(sender);
                if (bank == null) continue;
                long value = extract(cursor.getString(1));
                if (value < 0) continue;
                String key = storageKey(bank, BankRules.extractAccount(bank, cursor.getString(1)));
                rowsByKey.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new Object[]{sender, cursor.getString(1),
                        MessageDate.eventTime(cursor.getString(1), arrival, BankRules.calendar(bank))});
            }
        } catch (Exception e) {
            Log.w(TAG, "scan failed", e);
        }

        // A full re-scan that re-keys a bank's messages per account supersedes the legacy plain
        // bank slot (which pre-dates account extraction): drop it so the old merged balance is not
        // kept next to — and summed with — the new per-account slots. Banks with no account-bearing
        // message in the inbox keep their plain slot untouched, and composite keys are never removed
        // here because they are not names of banks.
        if (full) {
            Set<String> splitBanks = new HashSet<>();
            for (String k : rowsByKey.keySet()) {
                int bar = k.indexOf('|');
                if (bar > 0) splitBanks.add(k.substring(0, bar));
            }
            if (!splitBanks.isEmpty()) current.keySet().removeIf(splitBanks::contains);
        }

        Map<String, List<Reconcile.Entry>> windows = loadRecentMovements(context);
        for (Map.Entry<String, List<Object[]>> e : rowsByKey.entrySet()) {
            String key = e.getKey();
            String bank = bankOfKey(key);
            List<Object[]> rows = e.getValue();
            // Merge this scan's movements into the recent-movements window and reconcile the unique
            // balance chain, so a fee and its transfer that the bank sent in the wrong order are seen
            // in their true order instead of by arrival time.
            Map<String, String> sigSender = new HashMap<>();
            List<Reconcile.Entry> merged = mergedForWindow(windows.get(key), rows, sigSender, bank);
            List<Reconcile.Entry> chain = reconcile(merged);

            Object[] newestArr = newestRow(rows);
            Reconcile.Entry chosen = null;
            String chosenSender = null;
            boolean chainTrusted = chain != null && !chain.isEmpty()
                && isChainMovement(chain, bank, (String) newestArr[0], (String) newestArr[1]);
            if (chainTrusted) {
                Reconcile.Entry last = chain.get(chain.size() - 1);
                chosen = last;
                chosenSender = sigSender.get(last.sig);
            } else {
                long bal = extract((String) newestArr[1]);
                if (bal >= 0) {
                    chosen = new Reconcile.Entry((Long) newestArr[2], 0, bal, null);
                    chosenSender = (String) newestArr[0];
                }
            }
            if (chosen != null) {
                Bank existing = current.get(key);
                // Only move the stored balance forward in time. The chain-resolved branch must not
                // regress a newer stored entry when the last message is gone or a late,
                // out-of-order movement resolves as the chain tail.
                boolean changed = existing == null || chosen.date > existing.date;
                if (changed) {
                    matched++;
                    current.put(key, new Bank(bank, chosen.balance, chosen.date,
                        chosenSender != null ? chosenSender : (existing != null ? existing.sender : null),
                        accountOfKey(key)));
                }
            }
            windows.put(key, pruneWindow(merged));
        }
        saveRecentMovements(context, windows);

        write(context, current);
        // A full scan that finds no bank message at all could not have re-derived anything, so it
        // must not confirm the rules version or advance the watermark: the stored balances may be
        // stale (the messages they came from are gone, or the SMS store is not available yet) and
        // the rebuild must be retried on the next open instead of being marked as done.
        boolean emptyFullScan = full && rowsByKey.isEmpty() && !current.isEmpty();
        if (!emptyFullScan) {
            SharedPreferences.Editor editor = prefs.edit().putInt(KEY_RULES_VERSION, rulesVersion);
            if (newest > watermark) editor.putLong(KEY_SCANNED_THROUGH, newest);
            editor.apply();
        }
        saved.clear();
        saved.putAll(current);
        return matched;
    }

    /** Scans the inbox for money-movement messages and appends them to the saved transaction history,
     *  deduped so an exact duplicate message is never counted twice. Returns how many NEW transactions
     *  were recorded.
     *
     *  <p>This has its own watermark and rules version, entirely separate from {@link #scanSms}, so a
     *  balance refresh never waits on (or is bounded by) the history scan. The first scan after a fresh
     *  install (or after {@link #reset}) reads the WHOLE inbox instead of stopping at the newest message
     *  per bank, so older movements that the balance scan skips are still captured into history.
     *
     *  <p>Persistence is committed before the watermark advances, so a process death mid-scan only causes
     *  a harmless re-read of already-deduped messages. A scan that is already running is reported as a
     *  no-op so concurrent triggers (app open + history open) collapse into a single pass.
     *
     *  <p>A rules-version change triggers a full re-scan that rebuilds the stored history from the
     *  messages currently in the inbox (correcting entries the old rules parsed wrongly) while keeping
     *  every stored entry whose message was deleted. If that scan fails part-way, neither the rules
     *  version nor the watermark is advanced, so the rebuild runs again on the next scan. */
    static synchronized int scanHistory(Context context) {
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
            return 0;
        if (HISTORY_SCANNING) return 0;
        HISTORY_SCANNING = true;
        boolean completed = false;
        try {
            List<Transaction> stored = readTransactions(context);
            SharedPreferences prefs = context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE);
            long hwm = prefs.getLong(KEY_HISTORY_THROUGH, 0);
            // As in scanSms, a history watermark ahead of the current clock means the device clock
            // moved backward since the last scan; fall back to a full rescan so messages dated after
            // the rollback are not skipped forever by the incremental "newer than watermark" read.
            long now = System.currentTimeMillis();
            boolean full = hwm == 0 || hwm > now
                || prefs.getInt(KEY_HISTORY_RULES_VERSION, -1) != HISTORY_RULES_VERSION
                || prefs.getInt(KEY_HISTORY_SCHEMA, -1) != HISTORY_SCHEMA;
            if (full) hwm = 0;
            // On an incremental scan the stored history doubles as the dedup set: a message already
            // recorded (by fingerprint, or by the legacy bank|date|amount triple) is left alone. On a
            // full scan the inbox is authoritative instead — every present movement is re-parsed under
            // the current rules and the history is rebuilt from those parses (see below) — so dedup
            // against what the old rules stored would only skip the very reprocessing this full scan
            // exists to do.
            Set<String> seenSigs = new HashSet<>();
            Set<String> seenLegacy = new HashSet<>();
            if (!full) {
                for (Transaction t : stored) {
                    if (t.sig != null) seenSigs.add(t.sig);
                    else seenLegacy.add(legacyEntryKey(t));
                }
            }
            int added = 0;
            long newest = 0;
            // The reasons this scan reads out of the bank messages, folded into the store once the
            // movements they belong to are written. Declared out here so it survives the cursor block.
            Map<String, String> detectedReasons = new LinkedHashMap<>();
            String selection = !full ? Telephony.Sms.DATE + " > ?" : null;
            String[] args = selection != null ? new String[]{Long.toString(hwm)} : null;
            try (Cursor cursor = context.getContentResolver().query(
                Telephony.Sms.Inbox.CONTENT_URI,
                new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE},
                selection, args, Telephony.Sms.DATE + " DESC")) {
                if (cursor == null) return 0;
                List<Object[]> rows = new ArrayList<>();
                while (cursor.moveToNext()) {
                    // Two clocks, deliberately. `arrival` is when the phone received the message and
                    // is the only one that can say whether this row is new to us, so it alone feeds
                    // `newest` and therefore the incremental watermark. The date the movement carries
                    // is when the money moved, and that is what orders rows, reconciles balance chains
                    // and dates the stored transaction — otherwise a message that arrived three weeks
                    // late is filed three weeks late, and the period it belongs to is left looking
                    // short of money the app is already holding.
                    long arrival = Math.min(cursor.getLong(2), now);
                    if (arrival > newest) newest = arrival;
                    String sender = cursor.getString(0);
                    String bank = BankRules.resolve(sender);
                    if (bank == null) continue;
                    String body = cursor.getString(1);
                    rows.add(new Object[]{bank, sender, body,
                        MessageDate.eventTime(body, arrival, BankRules.calendar(bank))});
                }
                // Oldest first, so the balance-delta fallback chain below follows time. On a full scan
                // the chain starts from the oldest kept message; on an incremental scan it is seeded
                // from the last balance persisted by the previous scan.
                rows.sort((a, b) -> Long.compare((Long) a[3], (Long) b[3]));
                Map<String, Long> lastBalance = full ? new HashMap<>() : loadLastBalances(context);

                // Group this scan's rows by bank, merge each bank's movements into its recent-window,
                // and reconcile the balance chains, so a fee and its transfer that arrived in the wrong
                // order are processed (and stored) in their true order instead of by arrival time.
                Map<String, List<Object[]>> rowsByBank = new LinkedHashMap<>();
                for (Object[] row : rows) {
                    rowsByBank.computeIfAbsent(rowCompositeKey(row), k -> new ArrayList<>()).add(row);
                }
                Map<String, List<Reconcile.Entry>> windows = loadRecentMovements(context);
                Map<String, Map<String, Integer>> chainPosByBank = new LinkedHashMap<>();
                Map<String, List<Reconcile.Entry>> chainByBank = new LinkedHashMap<>();
                for (Map.Entry<String, List<Object[]>> e : rowsByBank.entrySet()) {
                    String key = e.getKey();
                    // History rows carry [bank, sender, body, date]; the window merger reads them as
                    // [sender, body, date] (the layout scanSms builds), so rebind before merging.
                    List<Reconcile.Entry> merged = mergedForWindow(windows.get(key),
                        senderBodyDate(e.getValue()), null, bankOfKey(e.getKey()));
                    List<Reconcile.Entry> chain = reconcile(merged);
                    if (chain != null && !chain.isEmpty()) {
                        chainByBank.put(key, chain);
                        Map<String, Integer> pos = new HashMap<>();
                        for (int i = 0; i < chain.size(); i++) {
                            Reconcile.Entry en = chain.get(i);
                            if (en.sig != null) pos.put(en.sig, i);
                        }
                        chainPosByBank.put(key, pos);
                    }
                    windows.put(key, pruneWindow(merged));
                }
                saveRecentMovements(context, windows);

                // Reorder the date-sorted rows so that adjacent same-bank movements known to a unique
                // chain appear in their true order. Everything else keeps its current relative order.
                rows = reorderByChains(rows, chainPosByBank);

                List<Transaction> fresh = new ArrayList<>();
                List<Transaction> placed = new ArrayList<>();
                Set<String> addedSigs = new HashSet<>();
                Map<Transaction, String> freeByFresh = new HashMap<>();
                for (Object[] row : rows) {
                    String bank = (String) row[0];
                    String sender = (String) row[1];
                    String body = (String) row[2];
                    long date = (Long) row[3];
                    String key = rowCompositeKey(row);
                    Long last = lastBalance.get(key);
                    Transaction t = parseMovement(bank, sender, body, date,
                        last != null, last != null ? last : 0);
                    if (t != null) {
                        // The reason the bank stated, read from the very message that proves the
                        // movement — so only a settled movement is ever given one, and a promotion or
                        // an OTP prompt (which parse to no movement at all) can never attach a reason
                        // to anything. It is collected under the note key and stored beside the notes
                        // after the transactions, so a user note is never what gets written here.
                        String reason = BankRules.extractReason(bank, body);
                        if (reason != null) detectedReasons.put(noteKey(t), reason);
                        // The message fingerprint is the primary identity: it folds sender + movement
                        // amount + resulting balance (falling back to the normalized body), so it is
                        // independent of time. A bank sending the same SMS twice is one transaction even
                        // if the copies differ in timestamp or reference number, while two genuine
                        // movements of the same value — whose messages report different resulting
                        // balances — stay distinct. The legacy bank|date|amount triple is only consulted
                        // for entries saved before fingerprints existed. On an incremental scan this
                        // dedup runs against the stored history; on a full scan the stored history is
                        // not consulted (the inbox is the source of truth being re-processed), so this
                        // only collapses the scan's own duplicates via the addedSigs set.
                        String legacyKey = legacyEntryKey(t);
                        if (seenLegacy.contains(legacyKey)) continue;
                        String sig = t.sig;
                        if (sig != null) {
                            if (seenSigs.contains(sig)) continue;
                            seenSigs.add(sig);
                            addedSigs.add(sig);
                        } else if (seenSigs.contains(legacyKey)) {
                            continue;
                        }
                        // Remember the account-free fingerprint of every fresh account-bearing parse.
                        // When rules start recognizing an account, a full re-scan re-parses a
                        // still-present message with the account while its account-less twin (stored
                        // under the older rules) keeps an account-free fingerprint; the twin is
                        // reconciled against this in the rebuild below.
                        if (t.account != null && t.sig != null) {
                            String accountFree = messageSig(sender, body, null);
                            if (accountFree != null) freeByFresh.put(t, accountFree);
                        }
                        fresh.add(t);
                        added++;
                    }
                    // Remember the last stated balance per bank so the next movement can be measured
                    // against it, across scans. OTP messages and balance-less prompts return -1 here
                    // and leave the chain untouched.
                    long bal = extract(body);
                    if (bal >= 0) lastBalance.put(key, bal);
                }
                // Chain banks: keep the balance chain end correct when the true-newest movement was not
                // among the rows scanned now (a previous scan already committed it), and place fresh
                // chain members relative to their already-stored siblings when part of the pair was
                // recorded earlier (split scans). A full re-scan additionally reorders any stored
                // entries the arrival order previously put back-to-front.
                for (Map.Entry<String, List<Reconcile.Entry>> e : chainByBank.entrySet()) {
                    String key = e.getKey();
                    List<Reconcile.Entry> chain = e.getValue();
                    List<Transaction> txs = new ArrayList<>();
                    for (Transaction t : fresh) if (key.equals(transactionCompositeKey(t))) txs.add(t);
                    // Chain banks: keep the persisted last balance at the chain end (the true-newest
                    // movement) unless the chain-last movement itself was committed now, or a movement
                    // OUTSIDE the chain was recorded this scan and superseded it (a delta-derived
                    // balance that moved on past the pair).
                    Reconcile.Entry last = chain.get(chain.size() - 1);
                    if (last.sig != null && !addedSigs.contains(last.sig)
                            && !hasFreshOutsideChain(txs, chain)) {
                        lastBalance.put(key, last.balance);
                    }
                    // Place freshly scanned chain members next to their already-stored siblings when
                    // part of the pair was recorded earlier (split scans). Only chain members are ever
                    // placed, so a non-chain movement keeps the append path below and is never dropped.
                    // A full scan never needs this: every chain member in the inbox is re-parsed in
                    // this pass, so the chain order already comes from the row reorder above plus the
                    // stored reorder below, and sibling placement can't reference stale fingerprints.
                    if (txs.isEmpty() || full) continue;
                    if (hasStoredChainMember(stored, key, chain)) {
                        Set<String> chainSigs = new HashSet<>();
                        for (Reconcile.Entry ce : chain) if (ce.sig != null) chainSigs.add(ce.sig);
                        Map<String, Transaction> bySig = new HashMap<>();
                        for (Transaction t : txs)
                            if (t.sig != null && chainSigs.contains(t.sig)) bySig.put(t.sig, t);
                        placeReconciled(stored, key, chain, bySig);
                        placed.addAll(bySig.values());
                    }
                }
                // Full re-scan: the inbox is authoritative under the current rules, so rebuild the stored history
                // from this scan's parses and keep every stored entry that no fresh parse can claim.
                // A stored entry is claimed in two passes. First by identity: its fingerprint matches a
                // fresh parse, so a deleted copy of a still-present message (or a same-moment sibling
                // stored in the wrong order) collapses back into one transaction no matter the stored
                // position. Then by a residual (bank, date) budget — how many fresh parses that moment
                // has left after the identity claims — which replaces stale parses and legacy sigless
                // entries left by the older rules. Everything else stays: deleted messages' orphans,
                // and present messages the current rules no longer recognize as a movement. Fresh
                // parses that claim nothing are new movements. This is what fixes history recorded
                // under an older rules version.
                // Incremental scan: append the remaining fresh transactions newest-first, preserving
                // the append order earlier versions produced. Chain placements above already wrote
                // their entries at the correct position relative to their stored siblings.
                if (full) {
                    Set<String> freshSigs = new HashSet<>();
                    Map<String, Integer> freshAtKey = new HashMap<>();
                    Map<String, Transaction> freshBySig = new HashMap<>();
                    Map<Transaction, Transaction> replaced = new HashMap<>();
                    for (Transaction t : fresh) {
                        if (t.sig != null) {
                            freshSigs.add(t.sig);
                            freshBySig.putIfAbsent(t.sig, t);
                        }
                        String key = transactionCompositeKey(t) + "|" + t.date;
                        freshAtKey.put(key, freshAtKey.getOrDefault(key, 0) + 1);
                    }
                    List<Transaction> rebuilt = new ArrayList<>(fresh.size() + stored.size());
                    for (int i = fresh.size() - 1; i >= 0; i--) rebuilt.add(fresh.get(i));
                    // Identity claims are order-independent so a sibling orphan can never be claimed
                    // for another entry's fingerprint.
                    Set<Transaction> identityClaimed = new HashSet<>();
                    Map<String, Integer> identityClaimsPerKey = new HashMap<>();
                    for (Transaction t : stored) {
                        if (t.sig != null && freshSigs.contains(t.sig)) {
                            identityClaimed.add(t);
                            Transaction f = freshBySig.get(t.sig);
                            if (f != null) replaced.put(t, f);
                            String key = transactionCompositeKey(t) + "|" + t.date;
                            identityClaimsPerKey.put(key,
                                identityClaimsPerKey.getOrDefault(key, 0) + 1);
                        }
                    }
                    Map<String, Integer> budget = new HashMap<>();
                    for (Map.Entry<String, Integer> e : freshAtKey.entrySet()) {
                        int left = e.getValue()
                            - identityClaimsPerKey.getOrDefault(e.getKey(), 0);
                        if (left > 0) budget.put(e.getKey(), left);
                    }
                    // Rules that now recognize an account re-parse a still-present message with that
                    // account, while its stored twin from the older account-less era keeps an
                    // account-free fingerprint: neither the fingerprint identity nor the
                    // (bank|account, date) budget can bridge the two, so the same event would be
                    // recorded twice. Claim the account-less twin when a fresh parse is the same event,
                    // tried strongest-first: identical message content (the parse-independent digest),
                    // then the account-free fingerprint, then the amount, which is all the account-less
                    // era could distinguish (this also covers stored legacy sig-less entries).
                    Set<Transaction> accountTwins = new HashSet<>();
                    Map<Transaction, Transaction> twinOf = new HashMap<>();
                    if (!freeByFresh.isEmpty()) {
                        Map<String, Transaction> byContent = new HashMap<>();
                        Map<String, Transaction> byDigest = new HashMap<>();
                        Map<String, Transaction> byAmount = new HashMap<>();
                        for (Transaction f : fresh) {
                            if (f.account == null) continue;
                            String free = freeByFresh.get(f);
                            if (free != null)
                                byContent.putIfAbsent(f.bank + "|" + f.date + "|" + free, f);
                            if (f.content != null)
                                byDigest.putIfAbsent(f.bank + "|" + f.date + "|" + f.content, f);
                            byAmount.putIfAbsent(f.bank + "|" + f.date + "|" + f.amount, f);
                        }
                        for (Transaction s : stored) {
                            if (s.account != null) continue;
                            Transaction f = null;
                            if (s.content != null)
                                f = byDigest.remove(s.bank + "|" + s.date + "|" + s.content);
                            if (f == null && s.sig != null)
                                f = byContent.remove(s.bank + "|" + s.date + "|" + s.sig);
                            if (f == null)
                                f = byAmount.remove(s.bank + "|" + s.date + "|" + s.amount);
                            if (f != null) {
                                accountTwins.add(s);
                                twinOf.put(s, f);
                            }
                        }
                    }
                    // Fresh parses already claimed by identity or a twin are excluded from the
                    // budget pool, so the same replacement is never handed to two entries; a budget
                    // claim takes the same-moment sibling whose amount matches first — the strongest
                    // thing a legacy sig-less entry could distinguish.
                    Set<Transaction> pairedFresh = new HashSet<>(replaced.values());
                    pairedFresh.addAll(twinOf.values());
                    Map<String, java.util.ArrayDeque<Transaction>> budgetPool = new HashMap<>();
                    for (Transaction f : fresh) {
                        if (pairedFresh.contains(f)) continue;
                        budgetPool.computeIfAbsent(transactionCompositeKey(f) + "|" + f.date,
                            k -> new java.util.ArrayDeque<>()).addLast(f);
                    }
                    for (Transaction t : stored) {
                        if (identityClaimed.contains(t) || accountTwins.contains(t)) continue;
                        String key = transactionCompositeKey(t) + "|" + t.date;
                        Integer left = budget.get(key);
                        if (left != null && left > 0) {
                            budget.put(key, left - 1);
                            java.util.ArrayDeque<Transaction> pool = budgetPool.get(key);
                            if (pool != null && !pool.isEmpty()) {
                                Transaction claim = null;
                                for (Transaction cand : pool)
                                    if (cand.amount == t.amount) { claim = cand; break; }
                                if (claim == null) claim = pool.peekFirst();
                                pool.remove(claim);
                                replaced.put(t, claim);
                            }
                            continue;
                        }
                        rebuilt.add(t);
                    }
                    reorderStoredByChains(rebuilt, chainPosByBank);
                    stored = rebuilt;
                    if (!replaced.isEmpty() || !twinOf.isEmpty()) {
                        replaced.putAll(twinOf);
                        migrateTransactionText(context, replaced);
                    }
                } else {
                    for (int i = fresh.size() - 1; i >= 0; i--) {
                        if (placed.contains(fresh.get(i))) continue;
                        stored.add(fresh.get(i));
                    }
                }
                saveLastBalances(context, lastBalance);
                completed = true;
            } catch (Exception e) {
                Log.w(TAG, "history scan failed", e);
            }
            writeTransactions(context, stored);
            // The reasons land after the transactions they belong to, so the store never holds a
            // reason for a movement that was not written.
            mergeReasons(context, detectedReasons);
            SharedPreferences.Editor editor = prefs.edit();
            // Only commit the rules version and watermark when the scan finished cleanly: marking a
            // full rebuild as done (or advancing past rows that failed) would skip the correction
            // forever until the next manual version bump.
            if (completed && full) editor.putInt(KEY_HISTORY_RULES_VERSION, HISTORY_RULES_VERSION);
            if (completed && full) editor.putInt(KEY_HISTORY_SCHEMA, HISTORY_SCHEMA);
            if (completed && newest > 0) editor.putLong(KEY_HISTORY_THROUGH, newest);
            editor.apply();
            return added;
        } finally {
            HISTORY_SCANNING = false;
            notifyHistoryChanged();
        }
    }

    /** Notifies registered listeners that a history re-scan finished, so an open history screen can
     *  re-render with the fresh data and drop its updating indicator. */
    static void addHistoryListener(Runnable r) {
        synchronized (historyListeners) { historyListeners.add(r); }
    }

    static void removeHistoryListener(Runnable r) {
        synchronized (historyListeners) { historyListeners.remove(r); }
    }

    private static void notifyHistoryChanged() {
        List<Runnable> copy;
        synchronized (historyListeners) { copy = new ArrayList<>(historyListeners); }
        for (Runnable r : copy) {
            try { r.run(); } catch (Throwable t) { Log.w(TAG, "history listener failed", t); }
        }
    }

    /** A deterministic fingerprint of a money-movement message. Two copies of the same movement (a
     *  mistaken double delivery, possibly with different timestamps or reference numbers) must hash
     *  alike, while two genuinely distinct movements of the same value — which move the account
     *  balance between them — must not collide.
     *
     *  <p>A bank message reports both the moved amount and the resulting balance, so the fingerprint
     *  folds {@code sender + signed amount + resulting balance}. That is content-derived but ignores
     *  the volatile metadata (timestamps, transaction/reference numbers) that makes duplicate copies
     *  no longer textually identical. Only completed movements carry a resulting balance; the OTP
     *  rejection and the balance-required rule in {@link #extractTransaction} keep prompts out of
     *  history entirely. Messages without a stated balance fall back to the whole normalized body.
     *
     *  <p>Consequence: two messages whose content is identical (same sender, same amount text, same
     *  resulting balance) are ONE transaction no matter how far apart their timestamps are. That
     *  collapses the same-event double deliveries banks commonly send, at the cost of merging two
     *  genuinely different movements whenever they report an identical balance. If a transaction ever
     *  looks missing or doubled in support reports, this fingerprint and the (bank, date) replacement
     *  key used by the full re-scan in {@link #scanHistory} are the two places that decide it. */
    static String messageSig(String sender, String body) {
        return messageSig(sender, body, null);
    }

    /** Same as {@link #messageSig(String, String)}, folding the account number into the primary
     *  fingerprint so that two same-amount movements ending at the same resulting balance but on two
     *  different accounts of one bank stay distinct ({@code sender|account|amount|balance}). */
    static String messageSig(String sender, String body, String account) {
        if (body == null) return null;
        String s = normalizeLetters(digits(body.replace("\u066C", ",").replace("\u060C", ",")))
            .trim().replaceAll("\\s+", " ");
        if (s.isEmpty()) return null;
        String fold;
        long balance = extract(body);
        Long txn = extractTransaction(body);
        if (txn != null && balance >= 0) {
            fold = sender + (account == null ? "" : "|" + account) + "|" + txn + "|" + balance;
        } else {
            fold = sender + "|" + s;
        }
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                .digest(fold.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                int b = h[i] & 0xFF;
                sb.append(Character.forDigit(b >>> 4, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return fold;
        }
    }

    /** A parse-independent digest of the message text itself (sender + normalized body), unlike
     *  {@link #messageSig} which folds the parsed amount and resulting balance. The same physical
     *  message therefore hashes identically no matter how the parsing rules evolve, which is what lets
     *  a rules update reconcile its re-parsed result with the entry stored under the old rules. */
    static String contentHash(String sender, String body) {
        if (body == null) return null;
        String s = normalizeLetters(digits(body.replace("\u066C", ",").replace("\u060C", ",")))
            .trim().replaceAll("\\s+", " ");
        if (s.isEmpty()) return null;
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                .digest((sender + "|" + s).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                int b = h[i] & 0xFF;
                sb.append(Character.forDigit(b >>> 4, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Uniquely identifies a stored transaction for dedup: the message fingerprint when known, or the
     *  legacy bank/date/amount triple for entries written before signatures existed. The account
     *  number is appended to the triple when the entry carried one, so two same-amount, same-moment
     *  movements across two accounts of one bank never collide. */
    static String txIdentityKey(Transaction t) {
        if (t.sig != null) return "s:" + t.sig;
        String base = t.bank + "|" + t.date + "|" + t.amount;
        return t.account == null ? base : base + "|" + t.account;
    }

    /** The composite storage key for a transaction's own bank slot. */
    private static String transactionCompositeKey(Transaction t) {
        return storageKey(t.bank, t.account);
    }

    /** The composite storage key a [bank, sender, body, date] history row belongs to. */
    private static String rowCompositeKey(Object[] row) {
        String bank = (String) row[0];
        return storageKey(bank, BankRules.extractAccount(bank, (String) row[2]));
    }

    /** The legacy no-fingerprint dedup key of a transaction (bank/date/amount, plus account when
     *  stated), mirroring {@link #txIdentityKey}. */
    private static String legacyEntryKey(Transaction t) {
        String base = t.bank + "|" + t.date + "|" + t.amount;
        return t.account == null ? base : base + "|" + t.account;
    }

    static long extract(String raw) {
        if (raw == null) return -1;
        String s = digits(raw.replace("\u066C", ",").replace("\u060C", ","));
        if (otp.matcher(s).find()) return -1;
        Matcher m = balance.matcher(s);
        String n = null;
        while (m.find()) n = m.group(1);
        if (n == null) return -1;
        try { return Long.parseLong(n.replace(",", "")); }
        catch (Exception e) { return -1; }
    }

    /** Parses a signed transaction amount (in rials) from a bank message, or null if the message does
     *  not describe a completed money movement. The amount is recognized, in order: after the "مبلغ"
     *  (amount) label — where an explicit "+"/"-" sign is authoritative (e.g. Parsian's
     *  "مبلغ:500,000-"), after a deposit/withdrawal label ("واریز:"/"برداشت:", Tejarat), as a
     *  "<label>:<amount><sign>" line with a trailing sign (Melli's "انتقالي:1,000,000-" /
     *  "حواله پل:7,700,000+" layout), as a bare
     *  number standing next to "ریال" that is not the stated resulting balance (Blu), as a bare
     *  signed amount opening the message (Resalat's "-200,000,000" first line), or as a bare
     *  signed amount alone on its own line with the sign trailing the number (Mehr Iran's
     *  "400,000-" layout). The direction is
     *  taken from the explicit sign, the direction label, or exactly one of the deposit/withdrawal
     *  keywords. Finally the message must also carry the resulting balance — the proof that the
     *  movement settled — so OTP payment prompts or authorization messages are never counted. Returns a
     *  negative value for a withdrawal and a positive one for a deposit. */
    static Long extractTransaction(String raw) {
        if (raw == null) return null;
        String s = digits(raw.replace("\u066C", ",").replace("\u060C", ","));
        if (otp.matcher(s).find()) return null;
        String n = normalizeLetters(s);

        long amount = -1;
        int sign = 0;
        int labelDir = 0;

        // 1) Amount following the "مبلغ" label, with an optional explicit sign.
        String g = lastGroup(amountLabel, n);
        if (g != null) {
            String t = g.trim();
            if (t.startsWith("-") || t.endsWith("-")) sign = -1;
            else if (t.startsWith("+") || t.endsWith("+")) sign = 1;
            t = t.replace("+", "").replace("-", "").trim();
            amount = toLong(t);
        }

        // 2) Amount written right after a "واریز:"/"برداشت:" label.
        if (amount <= 0) {
            String d = lastGroup(depositLabel, n);
            String w = lastGroup(withdrawalLabel, n);
            if (d == null && w != null) {
                amount = toLong(w);
                labelDir = -1;
            } else if (w == null && d != null) {
                amount = toLong(d);
                labelDir = 1;
            }
        }

        // 3) A "<label>:<amount><sign>" line where the sign trails the number (Melli), e.g.
        //    "انتقالي:1,000,000-" or "حواله پل:7,700,000+". The explicit sign decides the direction,
        //    so a label that happens to contain a keyword of the opposite kind ("حواله" is a
        //    withdrawal keyword but is a deposit here) cannot flip it.
        if (amount <= 0) {
            Matcher ml = labeledSignedAmount.matcher(n);
            if (ml.find()) {
                amount = toLong(ml.group(2));
                sign = ml.group(3).equals("-") ? -1 : 1;
            }
        }

        // 4) A bare number adjacent to "ریال", excluding the resulting balance itself.
        if (amount <= 0) {
            Matcher mb = balance.matcher(n);
            while (mb.find()) {
                String v = mb.group(1);
                n = n.replace(v, "").replace(v.replace(",", ""), "");
            }
            Matcher mc = rialAmount.matcher(n);
            long best = -1;
            while (mc.find()) best = Math.max(best, toLong(mc.group(1)));
            if (best > 0) amount = best;
        }

        // 5) A bare signed amount at the start of the message (e.g. Resalat's "-200,000,000" first
        //    line, with the resulting balance at the end). The explicit sign is the direction.
        if (amount <= 0) {
            Matcher ms = signedAmount.matcher(n);
            if (ms.find()) {
                sign = ms.group(1).equals("-") ? -1 : 1;
                amount = toLong(ms.group(2));
            }
        }

        // 6) A bare signed amount on its own line with a trailing sign, the mirror of Resalat's
        //    leading-sign form (Mehr Iran writes "400,000-" alone, then the resulting balance). The
        //    whole-line shape keeps the unsigned account, date and balance lines out.
        if (amount <= 0) {
            Matcher mbs = bareSignedAmount.matcher(n);
            if (mbs.find()) {
                sign = mbs.group(2).equals("-") ? -1 : 1;
                amount = toLong(mbs.group(1));
            }
        }

        if (amount <= 0) return null;

        int direction;
        if (sign != 0) direction = sign;
        else if (labelDir != 0) direction = labelDir;
        else {
            boolean deposit = containsAny(n, DEPOSIT_KEYWORDS);
            boolean withdrawal = containsAny(n, WITHDRAWAL_KEYWORDS);
            if (deposit == withdrawal) return null;
            direction = deposit ? 1 : -1;
        }
        // After the amount and a single direction are identified, the movement is only added to
        // history if the message also states the resulting balance; without it the message is a
        // prompt/OTP or unconfirmed state, so it must not be recorded.
        if (extract(raw) < 0) return null;
        return direction > 0 ? amount : -amount;
    }

    /** Parses one bank message into a transaction, using {@link #extractTransaction} when the message
     *  can be matched by the amount/direction/sign rules. When those rules cannot extract a movement
     *  but the message still states a resulting balance and carries a movement keyword, the amount is
     *  derived by comparing that balance with the previous one seen for the same bank
     *  (balance-delta = balance − previous balance). The delta fallback keeps history working for
     *  bank message layouts the rules do not know yet, while the movement-keyword and finality guards
     *  keep plain "موجودی …" informational messages and OTP prompts out of history. Returns null when
     *  the message is not a settled money movement. */
    static Transaction parseMovement(String bank, String sender, String body, long date,
                                     boolean hasPrev, long prevBalance) {
        if (body == null) return null;
        long stated = extract(body);
        Long txn = extractTransaction(body);
        if (txn == null) {
            String n = normalizeLetters(digits(body.replace("\u066C", ",").replace("\u060C", ",")));
            if (!containsAny(n, DEPOSIT_KEYWORDS) && !containsAny(n, WITHDRAWAL_KEYWORDS)) return null;
            if (stated < 0 || !hasPrev) return null;
            long delta = stated - prevBalance;
            if (delta == 0) return null;
            txn = delta;
        }
        String account = BankRules.extractAccount(bank, body);
        // The balance the message reported travels with the movement, so the history can prove a
        // missing message later without ever touching the inbox again (see Residual).
        return new Transaction(bank, account, date, txn,
            stated < 0 ? null : stated, messageSig(sender, body, account), contentHash(sender, body));
    }

    /** The last number captured by the given pattern in the string, or null if it matched nothing. */
    private static String lastGroup(Pattern p, String s) {
        Matcher m = p.matcher(s);
        String g = null;
        while (m.find()) g = m.group(1);
        return g;
    }

    private static boolean containsAny(String s, String[] keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    private static long toLong(String s) {
        try {
            return Long.parseLong(s.replace(",", ""));
        } catch (Exception e) {
            return -1;
        }
    }

    // ============================================================
    // Recent-movements window and balance-chain reconciliation
    // ============================================================

    /** Adds the movement rows of this scan (messages with a transaction amount and a stated balance)
     *  to the bank's recent-movements window, deduped by message fingerprint, and returns the merged
     *  list. {@code sigSender} is populated with the sender of each newly added movement so balance
     *  selection can keep the originating address. The window fingerprint folds the message's account
     *  number (mirroring the stored transactions'), so a fee and its transfer on one account are placed
     *  correctly against their stored siblings rather than colliding with an equal pair on another
     *  account of the same bank. */
    private static List<Reconcile.Entry> mergedForWindow(List<Reconcile.Entry> window,
            List<Object[]> rows, Map<String, String> sigSender, String bank) {
        List<Reconcile.Entry> merged = new ArrayList<>();
        Set<String> sigs = new HashSet<>();
        if (window != null) {
            merged.addAll(window);
            for (Reconcile.Entry en : window) if (en.sig != null) sigs.add(en.sig);
        }
        for (Object[] row : rows) {
            String sender = (String) row[0];
            String body = (String) row[1];
            Long txn = extractTransaction(body);
            long bal = extract(body);
            if (txn == null || bal < 0) continue;
            String sig = messageSig(sender, body,
                bank == null ? null : BankRules.extractAccount(bank, body));
            if (sig == null || !sigs.add(sig)) continue;
            if (sigSender != null) sigSender.put(sig, sender);
            merged.add(new Reconcile.Entry((Long) row[2], txn, bal, sig));
        }
        return merged;
    }

    /** Rebinds history-scan rows [bank, sender, body, date] into the [sender, body, date] layout the
     *  window merger consumes. */
    private static List<Object[]> senderBodyDate(List<Object[]> rows) {
        List<Object[]> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) out.add(new Object[]{r[1], r[2], r[3]});
        return out;
    }

    private static List<Reconcile.Entry> mergedForWindow(List<Reconcile.Entry> window,
            List<Object[]> rows) {
        return mergedForWindow(window, rows, null, null);
    }

    /** Returns the concatenation of the unique, per-cluster balance chains for a bank's movements,
     *  in chronological order, or {@code null} when no cluster of two or more movements is uniquely
     *  orderable (in which case arrival order remains authoritative). */
    private static List<Reconcile.Entry> reconcile(List<Reconcile.Entry> merged) {
        List<List<Reconcile.Entry>> clusters = clusterEntries(merged);
        List<Reconcile.Entry> chain = null;
        for (List<Reconcile.Entry> cluster : clusters) {
            List<Reconcile.Entry> c = Reconcile.order(cluster);
            if (c == null) continue;
            if (chain == null) chain = new ArrayList<>();
            chain.addAll(c);
        }
        return chain;
    }

    /** Splits a bank's movements into clusters separated by gaps larger than {@link #RECENT_WINDOW_MS},
     *  so reversals are only ever resolved against their true neighbors and never against movements
     *  from unrelated moments. */
    private static List<List<Reconcile.Entry>> clusterEntries(List<Reconcile.Entry> list) {
        if (list.isEmpty()) return java.util.Collections.emptyList();
        list.sort((a, b) -> Long.compare(a.date, b.date));
        List<List<Reconcile.Entry>> clusters = new ArrayList<>();
        List<Reconcile.Entry> cur = new ArrayList<>();
        long prev = Long.MIN_VALUE;
        for (Reconcile.Entry en : list) {
            if (!cur.isEmpty() && en.date - prev > RECENT_WINDOW_MS) {
                clusters.add(cur);
                cur = new ArrayList<>();
            }
            cur.add(en);
            prev = en.date;
        }
        if (!cur.isEmpty()) clusters.add(cur);
        return clusters;
    }

    /** Keeps the window to the movements within {@link #RECENT_WINDOW_MS} of the newest one, capped
     *  at {@link #MAX_RECENT_ENTRIES}, so it stays a small cache meant only for split-scan chaining. */
    private static List<Reconcile.Entry> pruneWindow(List<Reconcile.Entry> list) {
        if (list.isEmpty()) return new ArrayList<>();
        long newest = 0;
        for (Reconcile.Entry en : list) if (en.date > newest) newest = en.date;
        long cutoff = newest - RECENT_WINDOW_MS;
        List<Reconcile.Entry> kept = new ArrayList<>();
        for (Reconcile.Entry en : list) if (en.date >= cutoff) kept.add(en);
        kept.sort((a, b) -> Long.compare(a.date, b.date));
        while (kept.size() > MAX_RECENT_ENTRIES) kept.remove(0);
        return kept;
    }

    /** The most recent row of a bank: the one with the latest time we can believe for it, which is
     *  the time the bank stated where it stated a usable one and the delivery time where it did not.
     *  Read by delivery instead, a message that arrived last would always win, and a delayed
     *  statement about a past day would overwrite the balance that came after it. */
    private static Object[] newestRow(List<Object[]> rows) {
        Object[] best = null;
        for (Object[] r : rows) {
            if (best == null || (Long) r[2] > (Long) best[2]) best = r;
        }
        return best;
    }

    /** Whether the given message is a money movement whose fingerprint belongs to a reconciled chain.
     *  When it is, the chain's own order is authoritative over the arrival order. The signature is
     *  qualified with the message's account number, mirroring what {@link #mergedForWindow} persisted
     *  (and what {@link #scanSms} grouped the row under), so a bank that states account numbers in its
     *  messages is chain-matched correctly instead of falling back to arrival order. */
    private static boolean isChainMovement(List<Reconcile.Entry> chain, String bank,
            String sender, String body) {
        if (body == null || extractTransaction(body) == null) return false;
        String sig = messageSig(sender, body, BankRules.extractAccount(bank, body));
        if (sig == null) return false;
        for (Reconcile.Entry en : chain) if (sig.equals(en.sig)) return true;
        return false;
    }

    /** Reorders the date-sorted scan rows so that adjacent same-bank-account movements known to a
     *  unique chain appear in its true order. Only adjacent same-slot rows are ever swapped, so
     *  unrelated messages (other banks/accounts, balance-only snapshots) keep their current relative
     *  positions. */
    private static List<Object[]> reorderByChains(List<Object[]> rows,
            Map<String, Map<String, Integer>> posByKey) {
        if (posByKey.isEmpty() || rows.size() < 2) return rows;
        List<Object[]> out = new ArrayList<>(rows);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i + 1 < out.size(); i++) {
                Object[] a = out.get(i);
                Object[] b = out.get(i + 1);
                if (!rowCompositeKey(a).equals(rowCompositeKey(b))) continue;
                Map<String, Integer> pos = posByKey.get(rowCompositeKey(a));
                if (pos == null) continue;
                Integer pa = chainPosOfRow(pos, a);
                Integer pb = chainPosOfRow(pos, b);
                if (pa == null || pb == null || pa <= pb) continue;
                out.set(i, b);
                out.set(i + 1, a);
                changed = true;
            }
        }
        return out;
    }

    /** The chain position of a scan row (bank, sender, body, date), or null when the row is not a
     *  movement recognized by the chain. */
    private static Integer chainPosOfRow(Map<String, Integer> pos, Object[] row) {
        String body = (String) row[2];
        if (body == null || extractTransaction(body) == null) return null;
        String sig = messageSig((String) row[1], body, BankRules.extractAccount((String) row[0], body));
        return sig == null ? null : pos.get(sig);
    }

    /** Whether any entry of a bank-account slot's reconciled chain was already saved to the stored
     *  history, which is the signature of a split scan (part of a reversal pair committed earlier). */
    private static boolean hasStoredChainMember(List<Transaction> stored, String key,
            List<Reconcile.Entry> chain) {
        for (Reconcile.Entry e : chain) {
            if (e.sig == null) continue;
            for (Transaction t : stored) {
                if (t.sig != null && t.sig.equals(e.sig) && transactionCompositeKey(t).equals(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Places the freshly scanned chain members of a bank-account slot into the stored history at the
     *  position their already-stored siblings dictate (newest first), instead of appending on top.
     *  This keeps a fee that arrives in a later scan below the transfer it belongs to. */
    private static void placeReconciled(List<Transaction> stored, String key,
            List<Reconcile.Entry> chain, Map<String, Transaction> freshBySig) {
        for (int k = chain.size() - 1; k >= 0; k--) {
            Reconcile.Entry e = chain.get(k);
            Transaction tx = freshBySig.get(e.sig);
            if (tx == null) continue;
            boolean dup = false;
            for (Transaction t : stored) {
                if (t.sig != null && t.sig.equals(e.sig)) { dup = true; break; }
            }
            if (dup) continue;
            stored.add(chainInsertIndex(stored, key, chain, k), tx);
        }
    }

    /** Whether any freshly scanned transaction of the bank is NOT part of the reconciled chain, i.e.
     *  whether a movement after (or outside) the chain was recorded in this scan. */
    private static boolean hasFreshOutsideChain(List<Transaction> txs, List<Reconcile.Entry> chain) {
        for (Transaction t : txs) {
            boolean inChain = false;
            if (t.sig != null) {
                for (Reconcile.Entry c : chain) {
                    if (c.sig != null && c.sig.equals(t.sig)) { inChain = true; break; }
                }
            }
            if (!inChain) return true;
        }
        return false;
    }

    /** The stored-history index at which chain entry {@code k} (0 = oldest) must be inserted so the
     *  bank-account slot's chain reads newest-first: after its newest already-stored sibling, or
     *  before its oldest already-stored sibling, or at the top when it has none. */
    private static int chainInsertIndex(List<Transaction> stored, String key,
            List<Reconcile.Entry> chain, int k) {
        int maxNewer = -1;
        int minOlder = Integer.MAX_VALUE;
        for (int i = 0; i < stored.size(); i++) {
            Transaction t = stored.get(i);
            if (t.sig == null || !transactionCompositeKey(t).equals(key)) continue;
            for (int p = 0; p < chain.size(); p++) {
                Reconcile.Entry ce = chain.get(p);
                if (ce.sig != null && ce.sig.equals(t.sig)) {
                    if (p > k) maxNewer = Math.max(maxNewer, i);
                    else if (p < k) minOlder = Math.min(minOlder, i);
                    break;
                }
            }
        }
        if (maxNewer != -1) return maxNewer + 1;
        if (minOlder != Integer.MAX_VALUE) return minOlder;
        return 0;
    }

    /** On a full re-scan, bubbles adjacent same-slot stored entries (bank + account) that a reversed
     *  arrival order previously saved back-to-front into their true chain order. Entries that are
     *  adjacent and both known to a unique chain are the only ones moved, mirroring
     *  {@link #reorderByChains}. */
    private static void reorderStoredByChains(List<Transaction> stored,
            Map<String, Map<String, Integer>> posByKey) {
        if (posByKey.isEmpty() || stored.size() < 2) return;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i + 1 < stored.size(); i++) {
                Transaction a = stored.get(i);
                Transaction b = stored.get(i + 1);
                if (a.bank == null || !transactionCompositeKey(a).equals(transactionCompositeKey(b))) {
                    continue;
                }
                Map<String, Integer> pos = posByKey.get(transactionCompositeKey(a));
                if (pos == null) continue;
                Integer pa = a.sig != null ? pos.get(a.sig) : null;
                Integer pb = b.sig != null ? pos.get(b.sig) : null;
                if (pa == null || pb == null || pa >= pb) continue;
                stored.set(i, b);
                stored.set(i + 1, a);
                changed = true;
            }
        }
    }

    /** Loads the persisted recent-movements window, keyed by bank. */
    private static Map<String, List<Reconcile.Entry>> loadRecentMovements(Context context) {
        Map<String, List<Reconcile.Entry>> map = new HashMap<>();
        try {
            String raw = context.getSharedPreferences(PREFS_DATA, Context.MODE_PRIVATE)
                .getString(KEY_RECENT_MOVEMENTS, null);
            if (raw == null) return map;
            String json = raw.indexOf('{') == 0 ? raw : decrypt(raw);
            JSONObject obj = new JSONObject(json);
            Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String bank = it.next();
                JSONArray arr = obj.optJSONArray(bank);
                List<Reconcile.Entry> list = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject e = arr.getJSONObject(i);
                        list.add(new Reconcile.Entry(e.getLong("d"), e.getLong("a"), e.getLong("b"),
                            e.isNull("s") ? null : e.optString("s", null)));
                    }
                }
                map.put(bank, list);
            }
        } catch (Exception e) {
            Log.w(TAG, "loadRecentMovements failed", e);
        }
        return map;
    }

    /** Persists the recent-movements window, encrypted like the rest of the data store. */
    private static void saveRecentMovements(Context context, Map<String, List<Reconcile.Entry>> map) {
        try {
            if (map.isEmpty()) {
                context.getSharedPreferences(PREFS_DATA, Context.MODE_PRIVATE).edit()
                    .remove(KEY_RECENT_MOVEMENTS).apply();
                return;
            }
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, List<Reconcile.Entry>> e : map.entrySet()) {
                JSONArray arr = new JSONArray();
                for (Reconcile.Entry en : e.getValue()) {
                    JSONObject je = new JSONObject().put("d", en.date).put("a", en.amount).put("b", en.balance);
                    je.put("s", en.sig != null ? en.sig : JSONObject.NULL);
                    arr.put(je);
                }
                obj.put(e.getKey(), arr);
            }
            context.getSharedPreferences(PREFS_DATA, Context.MODE_PRIVATE).edit()
                .putString(KEY_RECENT_MOVEMENTS, encrypt(obj.toString())).apply();
        } catch (Exception e) {
            Log.w(TAG, "saveRecentMovements failed", e);
        }
    }

    /** The last stated balance per bank, persisted so an incremental scan can delta the first new
     *  message against it instead of the very first message in the scan window. */
    private static Map<String, Long> loadLastBalances(Context context) {
        Map<String, Long> map = new HashMap<>();
        try {
            String raw = context.getSharedPreferences(PREFS_DATA, Context.MODE_PRIVATE)
                .getString(KEY_HISTORY_LAST_BALANCE, null);
            if (raw == null) return map;
            String json = raw.indexOf('{') == 0 ? raw : decrypt(raw);
            JSONObject obj = new JSONObject(json);
            Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String b = it.next();
                map.put(b, obj.getLong(b));
            }
        } catch (Exception e) {
            Log.w(TAG, "loadLastBalances failed", e);
        }
        return map;
    }

    private static void saveLastBalances(Context context, Map<String, Long> map) {
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, Long> e : map.entrySet()) obj.put(e.getKey(), e.getValue().longValue());
            if (map.isEmpty()) {
                context.getSharedPreferences(PREFS_DATA, Context.MODE_PRIVATE).edit()
                    .remove(KEY_HISTORY_LAST_BALANCE).apply();
                return;
            }
            context.getSharedPreferences(PREFS_DATA, Context.MODE_PRIVATE).edit()
                .putString(KEY_HISTORY_LAST_BALANCE, encrypt(obj.toString())).apply();
        } catch (Exception ex) {
            Log.w(TAG, "saveLastBalances failed", ex);
        }
    }

    private static String normalizeLetters(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c == '\u064A' || c == '\u06CC') b.append('\u06CC');
            else if (c == '\u0643') b.append('\u06A9');
            else b.append(c);
        }
        return b.toString();
    }

    static String digits(String s) {
        return Digits.ascii(s);
    }

    /** Formats a rial amount as toman using the app language (Persian digits for Persian). */
    static String toman(Context context, long n) {
        return CurrencyHelper.display(context, n / 10);
    }
}