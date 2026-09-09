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

    /** Serializes transactions to the JSON shape used for the local store and the backup payload. */
    static String serializeTransactions(List<Transaction> txs) throws Exception {
        JSONArray arr = new JSONArray();
        for (Transaction t : txs) {
            arr.put(new JSONObject()
                .put("bank", t.bank)
                .put("date", t.date)
                .put("amount", t.amount));
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
                list.add(new Transaction(e.getString("bank"), e.getLong("date"),
                    e.getLong("amount")));
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

    /** Discards every saved balance and forgets the scan watermark, so the next scan behaves like a
     *  fresh install and rebuilds only from the messages currently in the inbox. The hide/unmask
     *  preference is untouched (it is a display choice, not balance data). */
    static void reset(Context context) {
        context.getSharedPreferences(PREFS_DATA, Context.MODE_PRIVATE).edit()
            .remove(KEY_BALANCES).remove(KEY_TRANSACTIONS).apply();
        context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE).edit()
            .remove(KEY_SCANNED_THROUGH)
            .remove(KEY_RULES_VERSION)
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
        List<Transaction> storedTxs = readTransactions(context);
        Set<String> seenTxs = new HashSet<>();
        for (Transaction t : storedTxs) seenTxs.add(t.bank + "|" + t.date + "|" + t.amount);
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
                Long txn = extractTransaction(cursor.getString(1));
                if (txn != null) {
                    String key = bank + "|" + date + "|" + txn;
                    if (seenTxs.add(key)) storedTxs.add(new Transaction(bank, date, txn));
                }
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
        writeTransactions(context, storedTxs);
        SharedPreferences.Editor editor = prefs.edit().putInt(KEY_RULES_VERSION, rulesVersion);
        if (newest > watermark) editor.putLong(KEY_SCANNED_THROUGH, newest);
        editor.apply();
        saved.clear();
        saved.putAll(current);
        return matched;
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
     *  not describe a money movement. A transaction is only recognized when the message carries the
     *  "مبلغ" (amount) label together with a deposit/withdrawal keyword, so a pure balance report
     *  (which only restates the remaining amount) or an ambiguous movement is never misclassified.
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