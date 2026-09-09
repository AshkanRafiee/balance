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
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    static final String KEY_SCANNED_THROUGH = "scanned_through";
    static final String KEY_RULES_VERSION = "rules_version";
    static final String KEY_HISTORY_THROUGH = "history_through";
    static final String KEY_HISTORY_RULES_VERSION = "history_rules_version";

    /** Bumped whenever the movement-message recognition rules change, forcing a full history re-scan. */
    static final int HISTORY_RULES_VERSION = 1;

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
    /** The transaction amount in a money-movement message follows the "مبلغ" (amount) label. */
    private static final Pattern amountLabel = Pattern.compile(
        "(?:\u0645\u0628\u0644\u063A)[^\\d]{0,12}?([0-9][0-9,]*)");
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

    /** Reads all saved transactions, newest last, in the order they were appended. */
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
            Log.w(TAG, "parseTransactions failed", ex);
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
            .remove(KEY_BALANCES).remove(KEY_TRANSACTIONS).apply();
        context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE).edit()
            .remove(KEY_SCANNED_THROUGH)
            .remove(KEY_RULES_VERSION)
            .remove(KEY_HISTORY_THROUGH)
            .remove(KEY_HISTORY_RULES_VERSION)
            .apply();
    }

    static boolean isHidden(Context context) {
        return context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDDEN, false);
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
                Bank existing = current.get(bank);
                if (existing == null || date > existing.date) {
                    matched++;
                    current.put(bank, new Bank(bank, value, date, sender));
                }
                if (full && matchedBanks.size() == senderTarget) break;
            }
        } catch (Exception e) {
            Log.w(TAG, "scan failed", e);
        }
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
                while (cursor.moveToNext()) {
                    long date = cursor.getLong(2);
                    if (date > newest) newest = date;
                    String sender = cursor.getString(0);
                    String bank = BankRules.resolve(sender);
                    if (bank == null) continue;
                    String body = cursor.getString(1);
                    Long txn = extractTransaction(body);
                    if (txn == null) continue;
                    // The message fingerprint is the primary identity: it folds sender + movement
                    // amount + resulting balance (falling back to the normalized body), so it is
                    // independent of time. A bank sending the same SMS twice is one transaction even
                    // if the copies differ in timestamp or reference number, while two genuine
                    // movements of the same value — whose messages report different resulting
                    // balances — stay distinct. The legacy bank|date|amount triple is only consulted
                    // for entries saved before fingerprints existed, so an upgrade re-scan never
                    // duplicates them.
                    String legacyKey = bank + "|" + date + "|" + txn;
                    if (seenLegacy.contains(legacyKey)) continue;
                    String sig = messageSig(sender, body);
                    if (sig != null) {
                        if (seenSigs.contains(sig)) continue;
                        seenSigs.add(sig);
                        stored.add(new Transaction(bank, date, txn, sig));
                    } else if (!seenSigs.contains(legacyKey)) {
                        stored.add(new Transaction(bank, date, txn, null));
                    }
                    added++;
                }
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
     *  not describe a completed money movement. A transaction is only recognized when the message
     *  carries the "مبلغ" (amount) label together with exactly one deposit/withdrawal keyword AND the
     *  resulting balance — the balance is the proof that the movement actually settled, so OTP payment
     *  prompts or authorization messages (which carry an amount but no final state) are never counted.
     *  Returns a negative value for a withdrawal and a positive one for a deposit. */
    static Long extractTransaction(String raw) {
        if (raw == null) return null;
        String s = digits(raw.replace("\u066C", ",").replace("\u060C", ","));
        if (otp.matcher(s).find()) return null;
        String n = normalizeLetters(s);
        Matcher m = amountLabel.matcher(n);
        String amountStr = null;
        while (m.find()) amountStr = m.group(1);
        if (amountStr == null) return null;
        long amount;
        try {
            amount = Long.parseLong(amountStr.replace(",", ""));
        } catch (Exception e) {
            return null;
        }
        if (amount <= 0) return null;
        boolean deposit = false;
        for (String k : DEPOSIT_KEYWORDS) if (n.contains(k)) { deposit = true; break; }
        boolean withdrawal = false;
        for (String k : WITHDRAWAL_KEYWORDS) if (n.contains(k)) { withdrawal = true; break; }
        if (deposit == withdrawal) return null;
        // After the amount and a single direction are identified, the movement is only added to
        // history if the message also states the resulting balance; without it the message is a
        // prompt/OTP or unconfirmed state, so it must not be recorded.
        if (extract(raw) < 0) return null;
        return deposit ? amount : -amount;
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