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
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    static final String PREFS_PREF = "balance_preferences";
    static final String KEY_HIDDEN = "balances_hidden";
    static final String KEY_AUTO_HIDE = "balances_auto_hide";
    static final String KEY_SCANNED_THROUGH = "scanned_through";
    static final String KEY_RULES_VERSION = "rules_version";
    static final String KEY_HISTORY_THROUGH = "history_through";
    static final String KEY_HISTORY_RULES_VERSION = "history_rules_version";
    static final String KEY_HISTORY_LAST_BALANCE = "history_last_balance";
    static final String KEY_EXCLUDED = "excluded_banks";
    static final String KEY_SORT = "sort_mode";

    /** Sort modes for the bank list. Each pair (balance / update date) has a reverse variant so
     *  re-selecting the same sort flips its direction. The list is always sorted; fresh installs
     *  default to highest balance first. */
    static final int SORT_BALANCE_HIGH = 1;
    static final int SORT_BALANCE_LOW = 2;
    static final int SORT_DATE_RECENT = 3;
    static final int SORT_DATE_OLDEST = 4;

    /** Bumped whenever the movement-message recognition rules change, forcing a full history re-scan. */
    static final int HISTORY_RULES_VERSION = 4;

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
                String bank = keys.next();
                JSONObject entry = obj.getJSONObject(bank);
                map.put(bank, new Bank(bank, entry.getLong("amount"),
                    entry.getLong("date"), entry.getString("sender")));
            }
        } catch (Exception e) { }
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
     *  message fingerprint is optional and skipped when absent, so backups stay readable both ways. */
    static String serializeTransactions(List<Transaction> txs) throws Exception {
        JSONArray arr = new JSONArray();
        for (Transaction t : txs) {
            JSONObject e = new JSONObject()
                .put("bank", t.bank)
                .put("date", t.date)
                .put("amount", t.amount);
            if (t.sig != null) e.put("sig", t.sig);
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
                list.add(new Transaction(e.getString("bank"), e.getLong("date"),
                    e.getLong("amount"), sig));
            }
        } catch (Exception ex) {
            Log.w(TAG, "parseTransactions failed");
        }
        return list;
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
     *  behave like a fresh install and rebuild from the messages currently in the inbox. The hide/unmask
     *  preference is untouched (it is a display choice, not app data). */
    static void reset(Context context) {
        context.getSharedPreferences(PREFS_DATA, Context.MODE_PRIVATE).edit()
            .remove(KEY_BALANCES).remove(KEY_TRANSACTIONS).remove(KEY_HISTORY_LAST_BALANCE)
            .remove(KEY_RECENT_MOVEMENTS).apply();
        context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE).edit()
            .remove(KEY_SCANNED_THROUGH)
            .remove(KEY_RULES_VERSION)
            .remove(KEY_HISTORY_THROUGH)
            .remove(KEY_HISTORY_RULES_VERSION)
            .remove(KEY_EXCLUDED)
            .apply();
    }

    static boolean isHidden(Context context) {
        return context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDDEN, false);
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

    static Set<String> getExcluded(Context context) {
        Set<String> set = new HashSet<>();
        try {
            String raw = context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE)
                .getString(KEY_EXCLUDED, null);
            if (raw == null) return set;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) set.add(arr.getString(i));
        } catch (Exception e) { }
        return set;
    }

    static void setExcluded(Context context, Set<String> excluded) {
        JSONArray arr = new JSONArray();
        for (String name : excluded) arr.put(name);
        context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_EXCLUDED, arr.toString()).apply();
    }

    static boolean isExcluded(Context context, String bankName) {
        return getExcluded(context).contains(bankName);
    }

    static void toggleExcluded(Context context, String bankName) {
        Set<String> excluded = getExcluded(context);
        if (excluded.contains(bankName)) excluded.remove(bankName);
        else excluded.add(bankName);
        setExcluded(context, excluded);
    }

    /** Orders the supplied banks for display: included banks first (sorted by the given mode),
     *  followed by excluded banks (also sorted among themselves). The input map's own order is
     *  never modified. */
    static List<Bank> orderForDisplay(Map<String, Bank> banks, Set<String> excluded) {
        return orderForDisplay(banks, excluded, SORT_BALANCE_HIGH);
    }

    static List<Bank> orderForDisplay(Map<String, Bank> banks, Set<String> excluded, int sort) {
        List<Bank> included = new ArrayList<>();
        List<Bank> excludedBanks = new ArrayList<>();
        for (Bank b : banks.values()) {
            if (excluded.contains(b.name)) excludedBanks.add(b);
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

    /** The persisted bank-list sort mode, {@link #SORT_BALANCE_HIGH} when never chosen. */
    static int getSort(Context context) {
        return context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE).getInt(KEY_SORT, SORT_BALANCE_HIGH);
    }

    static void setSort(Context context, int mode) {
        context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE).edit().putInt(KEY_SORT, mode).apply();
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
        boolean full = watermark == 0 || prefs.getInt(KEY_RULES_VERSION, -1) != rulesVersion;
        if (full) watermark = 0;
        int matched = 0;
        long newest = 0;
        Set<String> matchedBanks = new HashSet<>();
        int senderTarget = BankRules.supportedSenderCount();
        String selection = !full ? Telephony.Sms.DATE + " > ?" : null;
        String[] args = selection != null ? new String[]{Long.toString(watermark)} : null;

        // Collect every matching message per bank (sender, body, device date, stated balance). A bank
        // that sends a fee and the transfer it belongs to in the wrong order surfaces here as two
        // rows whose device dates disagree with their true chronology; the recent-movements window
        // below reconciles that before the balance is chosen.
        Map<String, List<Object[]>> rowsByBank = new LinkedHashMap<>();
        try (Cursor cursor = context.getContentResolver().query(
            Telephony.Sms.Inbox.CONTENT_URI,
            new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE},
            selection, args, Telephony.Sms.DATE + " DESC")) {
            if (cursor == null) return 0;
            while (cursor.moveToNext()) {
                long date = cursor.getLong(2);
                if (newest < date) newest = date;
                String sender = cursor.getString(0);
                String bank = BankRules.resolve(sender);
                if (bank == null) continue;
                long value = extract(cursor.getString(1));
                if (value < 0) continue;
                matchedBanks.add(bank);
                rowsByBank.computeIfAbsent(bank, k -> new ArrayList<>())
                    .add(new Object[]{sender, cursor.getString(1), date});
                if (full && matchedBanks.size() == senderTarget) break;
            }
        } catch (Exception e) {
            Log.w(TAG, "scan failed", e);
        }

        Map<String, List<Reconcile.Entry>> windows = loadRecentMovements(context);
        for (Map.Entry<String, List<Object[]>> e : rowsByBank.entrySet()) {
            String bank = e.getKey();
            List<Object[]> rows = e.getValue();
            // Merge this scan's movements into the recent-movements window and reconcile the unique
            // balance chain, so a fee and its transfer that the bank sent in the wrong order are seen
            // in their true order instead of by arrival time.
            Map<String, String> sigSender = new HashMap<>();
            List<Reconcile.Entry> merged = mergedForWindow(windows.get(bank), rows, sigSender);
            List<Reconcile.Entry> chain = reconcile(merged);

            Object[] newestArr = newestRow(rows);
            Reconcile.Entry chosen = null;
            String chosenSender = null;
            boolean chainTrusted = chain != null && !chain.isEmpty()
                && isChainMovement(chain, (String) newestArr[0], (String) newestArr[1]);
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
                Bank existing = current.get(bank);
                boolean changed = chainTrusted
                    ? existing == null || existing.amount != chosen.balance || existing.date != chosen.date
                    : existing == null || chosen.date > existing.date;
                if (changed) {
                    matched++;
                    current.put(bank, new Bank(bank, chosen.balance, chosen.date,
                        chosenSender != null ? chosenSender : (existing != null ? existing.sender : null)));
                }
            }
            windows.put(bank, pruneWindow(merged));
        }
        saveRecentMovements(context, windows);

        write(context, current);
        SharedPreferences.Editor editor = prefs.edit().putInt(KEY_RULES_VERSION, rulesVersion);
        if (newest > watermark) editor.putLong(KEY_SCANNED_THROUGH, newest);
        editor.apply();
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
     *  no-op so concurrent triggers (app open + history open) collapse into a single pass. */
    static synchronized int scanHistory(Context context) {
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
            return 0;
        if (HISTORY_SCANNING) return 0;
        HISTORY_SCANNING = true;
        try {
            List<Transaction> stored = readTransactions(context);
            Set<String> seenSigs = new HashSet<>();
            Set<String> seenLegacy = new HashSet<>();
            for (Transaction t : stored) {
                if (t.sig != null) seenSigs.add(t.sig);
                else seenLegacy.add(t.bank + "|" + t.date + "|" + t.amount);
            }
            SharedPreferences prefs = context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE);
            long hwm = prefs.getLong(KEY_HISTORY_THROUGH, 0);
            boolean full = hwm == 0
                || prefs.getInt(KEY_HISTORY_RULES_VERSION, -1) != HISTORY_RULES_VERSION;
            if (full) hwm = 0;
            int added = 0;
            long newest = 0;
            String selection = !full ? Telephony.Sms.DATE + " > ?" : null;
            String[] args = selection != null ? new String[]{Long.toString(hwm)} : null;
            try (Cursor cursor = context.getContentResolver().query(
                Telephony.Sms.Inbox.CONTENT_URI,
                new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE},
                selection, args, Telephony.Sms.DATE + " DESC")) {
                if (cursor == null) return 0;
                List<Object[]> rows = new ArrayList<>();
                while (cursor.moveToNext()) {
                    long date = cursor.getLong(2);
                    if (date > newest) newest = date;
                    String sender = cursor.getString(0);
                    String bank = BankRules.resolve(sender);
                    if (bank == null) continue;
                    rows.add(new Object[]{bank, sender, cursor.getString(1), date});
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
                    rowsByBank.computeIfAbsent((String) row[0], k -> new ArrayList<>()).add(row);
                }
                Map<String, List<Reconcile.Entry>> windows = loadRecentMovements(context);
                Map<String, Map<String, Integer>> chainPosByBank = new LinkedHashMap<>();
                Map<String, List<Reconcile.Entry>> chainByBank = new LinkedHashMap<>();
                for (Map.Entry<String, List<Object[]>> e : rowsByBank.entrySet()) {
                    String bank = e.getKey();
                    // History rows carry [bank, sender, body, date]; the window merger reads them as
                    // [sender, body, date] (the layout scanSms builds), so rebind before merging.
                    List<Reconcile.Entry> merged = mergedForWindow(windows.get(bank),
                        senderBodyDate(e.getValue()));
                    List<Reconcile.Entry> chain = reconcile(merged);
                    if (chain != null && !chain.isEmpty()) {
                        chainByBank.put(bank, chain);
                        Map<String, Integer> pos = new HashMap<>();
                        for (int i = 0; i < chain.size(); i++) {
                            Reconcile.Entry en = chain.get(i);
                            if (en.sig != null) pos.put(en.sig, i);
                        }
                        chainPosByBank.put(bank, pos);
                    }
                    windows.put(bank, pruneWindow(merged));
                }
                saveRecentMovements(context, windows);

                // Reorder the date-sorted rows so that adjacent same-bank movements known to a unique
                // chain appear in their true order. Everything else keeps its current relative order.
                rows = reorderByChains(rows, chainPosByBank);

                List<Transaction> fresh = new ArrayList<>();
                List<Transaction> placed = new ArrayList<>();
                Set<String> addedSigs = new HashSet<>();
                for (Object[] row : rows) {
                    String bank = (String) row[0];
                    String sender = (String) row[1];
                    String body = (String) row[2];
                    long date = (Long) row[3];
                    Long last = lastBalance.get(bank);
                    Transaction t = parseMovement(bank, sender, body, date,
                        last != null, last != null ? last : 0);
                    if (t != null) {
                        // The message fingerprint is the primary identity: it folds sender + movement
                        // amount + resulting balance (falling back to the normalized body), so it is
                        // independent of time. A bank sending the same SMS twice is one transaction even
                        // if the copies differ in timestamp or reference number, while two genuine
                        // movements of the same value — whose messages report different resulting
                        // balances — stay distinct. The legacy bank|date|amount triple is only consulted
                        // for entries saved before fingerprints existed, so an upgrade re-scan never
                        // duplicates them.
                        String legacyKey = bank + "|" + date + "|" + t.amount;
                        if (seenLegacy.contains(legacyKey)) continue;
                        String sig = t.sig;
                        if (sig != null) {
                            if (seenSigs.contains(sig)) continue;
                            seenSigs.add(sig);
                            addedSigs.add(sig);
                        } else if (seenSigs.contains(legacyKey)) {
                            continue;
                        }
                        fresh.add(t);
                        added++;
                    }
                    // Remember the last stated balance per bank so the next movement can be measured
                    // against it, across scans. OTP messages and balance-less prompts return -1 here
                    // and leave the chain untouched.
                    long bal = extract(body);
                    if (bal >= 0) lastBalance.put(bank, bal);
                }
                // Chain banks: keep the balance chain end correct when the true-newest movement was not
                // among the rows scanned now (a previous scan already committed it), and place fresh
                // chain members relative to their already-stored siblings when part of the pair was
                // recorded earlier (split scans). A full re-scan additionally reorders any stored
                // entries the arrival order previously put back-to-front.
                for (Map.Entry<String, List<Reconcile.Entry>> e : chainByBank.entrySet()) {
                    String bank = e.getKey();
                    List<Reconcile.Entry> chain = e.getValue();
                    List<Transaction> txs = new ArrayList<>();
                    for (Transaction t : fresh) if (bank.equals(t.bank)) txs.add(t);
                    // Chain banks: keep the persisted last balance at the chain end (the true-newest
                    // movement) unless the chain-last movement itself was committed now, or a movement
                    // OUTSIDE the chain was recorded this scan and superseded it (a delta-derived
                    // balance that moved on past the pair).
                    Reconcile.Entry last = chain.get(chain.size() - 1);
                    if (last.sig != null && !addedSigs.contains(last.sig)
                            && !hasFreshOutsideChain(txs, chain)) {
                        lastBalance.put(bank, last.balance);
                    }
                    // Place freshly scanned chain members next to their already-stored siblings when
                    // part of the pair was recorded earlier (split scans). Only chain members are ever
                    // placed, so a non-chain movement keeps the append path below and is never dropped.
                    if (txs.isEmpty()) continue;
                    if (hasStoredChainMember(stored, bank, chain)) {
                        Set<String> chainSigs = new HashSet<>();
                        for (Reconcile.Entry ce : chain) if (ce.sig != null) chainSigs.add(ce.sig);
                        Map<String, Transaction> bySig = new HashMap<>();
                        for (Transaction t : txs)
                            if (t.sig != null && chainSigs.contains(t.sig)) bySig.put(t.sig, t);
                        placeReconciled(stored, bank, chain, bySig);
                        placed.addAll(bySig.values());
                    }
                }
                // Append the remaining fresh transactions newest-first, preserving the append order
                // earlier versions produced. Chain placements above already wrote their entries at the
                // correct position relative to their stored siblings.
                for (int i = fresh.size() - 1; i >= 0; i--) {
                    if (placed.contains(fresh.get(i))) continue;
                    stored.add(fresh.get(i));
                }
                if (full) reorderStoredByChains(stored, chainPosByBank);
                saveLastBalances(context, lastBalance);
            } catch (Exception e) {
                Log.w(TAG, "history scan failed", e);
            }
            writeTransactions(context, stored);
            SharedPreferences.Editor editor = prefs.edit();
            if (full) editor.putInt(KEY_HISTORY_RULES_VERSION, HISTORY_RULES_VERSION);
            if (newest > 0) editor.putLong(KEY_HISTORY_THROUGH, newest);
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
     *  history entirely. Messages without a stated balance fall back to the whole normalized body. */
    static String messageSig(String sender, String body) {
        if (body == null) return null;
        String s = normalizeLetters(digits(body.replace("\u066C", ",").replace("\u060C", ",")))
            .trim().replaceAll("\\s+", " ");
        if (s.isEmpty()) return null;
        String fold;
        long balance = extract(body);
        Long txn = extractTransaction(body);
        if (txn != null && balance >= 0) {
            fold = sender + "|" + txn + "|" + balance;
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

    /** Uniquely identifies a stored transaction for dedup: the message fingerprint when known, or the
     *  legacy bank/date/amount triple for entries written before signatures existed. */
    static String txIdentityKey(Transaction t) {
        return t.sig != null ? "s:" + t.sig : t.bank + "|" + t.date + "|" + t.amount;
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
     *  "مبلغ:500,000-"), after a deposit/withdrawal label ("واریز:"/"برداشت:", Tejarat), as a bare
     *  number standing next to "ریال" that is not the stated resulting balance (Blu), or as a bare
     *  signed amount opening the message (Resalat's "-200,000,000" first line). The direction is
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

        // 3) A bare number adjacent to "ریال", excluding the resulting balance itself.
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

        // 4) A bare signed amount at the start of the message (e.g. Resalat's "-200,000,000" first
        //    line, with the resulting balance at the end). The explicit sign is the direction.
        if (amount <= 0) {
            Matcher ms = signedAmount.matcher(n);
            if (ms.find()) {
                sign = ms.group(1).equals("-") ? -1 : 1;
                amount = toLong(ms.group(2));
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
        Long txn = extractTransaction(body);
        if (txn == null) {
            String n = normalizeLetters(digits(body.replace("\u066C", ",").replace("\u060C", ",")));
            if (!containsAny(n, DEPOSIT_KEYWORDS) && !containsAny(n, WITHDRAWAL_KEYWORDS)) return null;
            long bal = extract(body);
            if (bal < 0 || !hasPrev) return null;
            long delta = bal - prevBalance;
            if (delta == 0) return null;
            txn = delta;
        }
        return new Transaction(bank, date, txn, messageSig(sender, body));
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
     *  selection can keep the originating address. */
    private static List<Reconcile.Entry> mergedForWindow(List<Reconcile.Entry> window,
            List<Object[]> rows, Map<String, String> sigSender) {
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
            String sig = messageSig(sender, body);
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
        return mergedForWindow(window, rows, null);
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

    /** The newest-arrived row of a bank (the one with the maximum device date). */
    private static Object[] newestRow(List<Object[]> rows) {
        Object[] best = null;
        for (Object[] r : rows) {
            if (best == null || (Long) r[2] > (Long) best[2]) best = r;
        }
        return best;
    }

    /** Whether the given message is a money movement whose fingerprint belongs to a reconciled chain.
     *  When it is, the chain's own order is authoritative over the arrival order. */
    private static boolean isChainMovement(List<Reconcile.Entry> chain, String sender, String body) {
        if (body == null || extractTransaction(body) == null) return false;
        String sig = messageSig(sender, body);
        if (sig == null) return false;
        for (Reconcile.Entry en : chain) if (sig.equals(en.sig)) return true;
        return false;
    }

    /** Reorders the date-sorted scan rows so that adjacent same-bank movements known to a unique
     *  chain appear in its true order. Only adjacent same-bank rows are ever swapped, so unrelated
     *  messages (other banks, balance-only snapshots) keep their current relative positions. */
    private static List<Object[]> reorderByChains(List<Object[]> rows,
            Map<String, Map<String, Integer>> posByBank) {
        if (posByBank.isEmpty() || rows.size() < 2) return rows;
        List<Object[]> out = new ArrayList<>(rows);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i + 1 < out.size(); i++) {
                Object[] a = out.get(i);
                Object[] b = out.get(i + 1);
                if (!a[0].equals(b[0])) continue;
                Map<String, Integer> pos = posByBank.get(a[0]);
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
        String sig = messageSig((String) row[1], body);
        return sig == null ? null : pos.get(sig);
    }

    /** Whether any entry of a bank's reconciled chain was already saved to the stored history, which
     *  is the signature of a split scan (part of a reversal pair committed earlier). */
    private static boolean hasStoredChainMember(List<Transaction> stored, String bank,
            List<Reconcile.Entry> chain) {
        for (Reconcile.Entry e : chain) {
            if (e.sig == null) continue;
            for (Transaction t : stored) {
                if (t.sig != null && t.sig.equals(e.sig) && bank.equals(t.bank)) return true;
            }
        }
        return false;
    }

    /** Places the freshly scanned chain members of a bank into the stored history at the position
     *  their already-stored siblings dictate (newest first), instead of appending on top. This keeps
     *  a fee that arrives in a later scan below the transfer it belongs to. */
    private static void placeReconciled(List<Transaction> stored, String bank,
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
            stored.add(chainInsertIndex(stored, bank, chain, k), tx);
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
     *  bank's chain reads newest-first: after its newest already-stored sibling, or before its oldest
     *  already-stored sibling, or at the top when it has none. */
    private static int chainInsertIndex(List<Transaction> stored, String bank,
            List<Reconcile.Entry> chain, int k) {
        int maxNewer = -1;
        int minOlder = Integer.MAX_VALUE;
        for (int i = 0; i < stored.size(); i++) {
            Transaction t = stored.get(i);
            if (t.bank == null || !t.bank.equals(bank) || t.sig == null) continue;
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

    /** On a full re-scan, bubbles adjacent same-bank stored entries that a reversed arrival order
     *  previously saved back-to-front into their true chain order. Entries that are adjacent and both
     *  known to a unique chain are the only ones moved, mirroring {@link #reorderByChains}. */
    private static void reorderStoredByChains(List<Transaction> stored,
            Map<String, Map<String, Integer>> posByBank) {
        if (posByBank.isEmpty() || stored.size() < 2) return;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i + 1 < stored.size(); i++) {
                Transaction a = stored.get(i);
                Transaction b = stored.get(i + 1);
                if (a.bank == null || !a.bank.equals(b.bank)) continue;
                Map<String, Integer> pos = posByBank.get(a.bank);
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

    static String digits(String s) {        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= '\u06F0' && c <= '\u06F9')
                b.append((char) ('0' + c - '\u06F0'));
            else if (c >= '\u0660' && c <= '\u0669')
                b.append((char) ('0' + c - '\u0660'));
            else b.append(c);
        }
        return b.toString();
    }

    /** Formats a rial amount as toman using the device/app language (Persian digits for Persian). */
    static String toman(Context context, long n) {
        Locale locale;
        String tag = LocaleHelper.currentTag(context);
        if ("fa".equals(tag)) {
            locale = new Locale("fa");
        } else if (tag.isEmpty()) {
            Locale device = context.getResources().getConfiguration().getLocales().get(0);
            locale = device != null && "fa".equals(device.getLanguage()) ? device : Locale.US;
        } else {
            locale = Locale.US;
        }
        return NumberFormat.getNumberInstance(locale).format(n / 10);
    }
}