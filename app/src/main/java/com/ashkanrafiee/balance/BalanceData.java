package com.ashkanrafiee.balance;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.provider.Telephony;
import java.text.NumberFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

final class BalanceData {
    static final String PREFS_DATA = "balance_data";
    static final String KEY_BALANCES = "balances";
    static final String PREFS_PREF = "balance_preferences";
    static final String KEY_HIDDEN = "balances_hidden";

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

    private BalanceData() {}

    static LinkedHashMap<String, Bank> read(Context context) {
        LinkedHashMap<String, Bank> map = new LinkedHashMap<>();
        try {
            String json = context.getSharedPreferences(PREFS_DATA, Context.MODE_PRIVATE)
                .getString(KEY_BALANCES, null);
            if (json != null) {
                JSONObject obj = new JSONObject(json);
                Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String bank = keys.next();
                    JSONObject entry = obj.getJSONObject(bank);
                    map.put(bank, new Bank(bank, entry.getLong("amount"),
                        entry.getLong("date"), entry.getString("sender")));
                }
            }
        } catch (Exception e) { }
        return map;
    }

    static void write(Context context, LinkedHashMap<String, Bank> map) {
        try {
            JSONObject obj = new JSONObject();
            for (java.util.Map.Entry<String, Bank> e : map.entrySet()) {
                Bank b = e.getValue();
                JSONObject entry = new JSONObject();
                entry.put("amount", b.amount);
                entry.put("date", b.date);
                entry.put("sender", b.sender);
                obj.put(e.getKey(), entry);
            }
            context.getSharedPreferences(PREFS_DATA, Context.MODE_PRIVATE).edit()
                .putString(KEY_BALANCES, obj.toString()).apply();
        } catch (Exception e) { }
    }

    static boolean isHidden(Context context) {
        return context.getSharedPreferences(PREFS_PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDDEN, false);
    }

    /** Scans the SMS inbox and merges newer balances into the given map, then persists the result. */
    static void scanSms(Context context, LinkedHashMap<String, Bank> saved) {
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
            return;
        try {
            Cursor cursor = context.getContentResolver().query(
                Telephony.Sms.Inbox.CONTENT_URI,
                new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE},
                null, null, Telephony.Sms.DATE + " DESC");
            if (cursor != null) while (cursor.moveToNext()) {
                String sender = cursor.getString(0), body = cursor.getString(1);
                long date = cursor.getLong(2);
                long value = extract(body);
                if (value < 0) continue;
                String bank = BankRules.resolve(sender);
                if (bank == null || value <= 0) continue;
                Bank existing = saved.get(bank);
                if (existing == null || date > existing.date)
                    saved.put(bank, new Bank(bank, value, date, sender));
            }
            if (cursor != null) cursor.close();
        } catch (Exception e) { }
        write(context, saved);
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

    static String digits(String s) {
        StringBuilder b = new StringBuilder();
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