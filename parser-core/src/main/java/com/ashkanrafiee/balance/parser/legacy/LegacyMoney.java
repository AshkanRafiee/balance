package com.ashkanrafiee.balance.parser.legacy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Frozen legacy rial parser and identity normalization. These audited, fixed expressions are
 * compatibility operations, not a facility for executing untrusted/custom regular expressions.
 * Missing/invalid balances are -1; absent movements are null. Sign loss, decimal truncation,
 * unchecked inference subtraction and destructive fallback replacement are intentional parity.
 */
public final class LegacyMoney {
    private LegacyMoney() {}
    private static final Pattern balance = Pattern.compile(
        "(?:\u0645\u0648\u062c\u0648\u062f\u06cc \u062d\u0633\u0627\u0628" +
        "|\u0645\u0627\u0646\u062f\u0647 \u062d\u0633\u0627\u0628" +
        "|\u0645\u0648\u062c\u0648\u062f\u06cc" +
        "|\u0645\u0627\u0646\u062f\u0647" +
        "|available balance|balance|bal)" +
        "[^\\d]{0,12}?([0-9][0-9,]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern otp = Pattern.compile(
        "(?<![\u0621-\u0640A-Za-z])" +
        "(?:\u0631\u0645\u0632|\u067e\u0648\u06cc\u0627" +
        "|\u06a9\u062f \\s*\u062a\u0627\u06cc\u06cc\u062f" +
        "|\u06a9\u062f \\s*\u062a\u0623\u06cc\u06cc\u062f" +
        "|otp|code)", Pattern.CASE_INSENSITIVE);
    private static final Pattern amountLabel = Pattern.compile(
        "(?:\u0645\u0628\u0644\u063A)[^\\d]{0,12}?([+-]?\\s*[0-9][0-9,]*\\s*[+-]?)");
    private static final Pattern depositLabel = Pattern.compile(
        "(?:\u0648\u0627\u0631\u06CC\u0632)[^\\d]{0,12}?([0-9][0-9,]*)");
    private static final Pattern withdrawalLabel = Pattern.compile(
        "(?:\u0628\u0631\u062F\u0627\u0634\u062A)[^\\d]{0,12}?([0-9][0-9,]*)");
    private static final Pattern rialAmount = Pattern.compile("([0-9][0-9,]*)\\s*\u0631\u06CC\u0627\u0644");
    private static final Pattern signedAmount = Pattern.compile(
        "^\\s*([+-])\\s*([0-9][0-9,]*)", Pattern.MULTILINE);
    private static final Pattern bareSignedAmount = Pattern.compile(
        "(?m)^[ \\t\\u202A-\\u202E]*([0-9][0-9,]*)[ \\t\\u202A-\\u202E]*([+-])[ \\t\\u202A-\\u202E]*$");
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

    public static String[] depositKeywords() { return DEPOSIT_KEYWORDS.clone(); }
    public static String[] withdrawalKeywords() { return WITHDRAWAL_KEYWORDS.clone(); }

    public static long extract(String raw) {
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

    public static Long extractTransaction(String raw) {
        if (raw == null) return null;
        String s = digits(raw.replace("\u066C", ",").replace("\u060C", ","));
        if (otp.matcher(s).find()) return null;
        String n = normalizeLetters(s);
        long amount = -1;
        int sign = 0;
        int labelDir = 0;
        String g = lastGroup(amountLabel, n);
        if (g != null) {
            String t = g.trim();
            if (t.startsWith("-") || t.endsWith("-")) sign = -1;
            else if (t.startsWith("+") || t.endsWith("+")) sign = 1;
            t = t.replace("+", "").replace("-", "").trim();
            amount = toLong(t);
        }
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
        if (amount <= 0) {
            Matcher ml = labeledSignedAmount.matcher(n);
            if (ml.find()) {
                amount = toLong(ml.group(2));
                sign = ml.group(3).equals("-") ? -1 : 1;
            }
        }
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
        if (amount <= 0) {
            Matcher ms = signedAmount.matcher(n);
            if (ms.find()) {
                sign = ms.group(1).equals("-") ? -1 : 1;
                amount = toLong(ms.group(2));
            }
        }
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
        if (extract(raw) < 0) return null;
        return direction > 0 ? amount : -amount;
    }

    /**
     * Legacy financial fallback ONLY. Caller first calls extractTransaction, and calls this only
     * if it returned null. Previous balance must belong to the same bank/account slot. Keyword
     * conflicts do not prevent inference; subtraction deliberately retains Java long wraparound.
     */
    public static Long inferMovement(String body, boolean hasPrev, long prevBalance) {
        if (body == null) return null;
        long stated = extract(body);
        String n = normalizeLetters(digits(body.replace("\u066C", ",").replace("\u060C", ",")));
        if (!containsAny(n, DEPOSIT_KEYWORDS) && !containsAny(n, WITHDRAWAL_KEYWORDS)) return null;
        if (stated < 0 || !hasPrev) return null;
        long delta = stated - prevBalance;
        if (delta == 0) return null;
        return delta;
    }

    public static String messageSig(String sender, String body) { return messageSig(sender, body, null); }
    public static String messageSig(String sender, String body, String account) {
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
        try { return digest(fold); }
        catch (Exception e) { return fold; }
    }
    public static String contentHash(String sender, String body) {
        if (body == null) return null;
        String s = normalizeLetters(digits(body.replace("\u066C", ",").replace("\u060C", ",")))
            .trim().replaceAll("\\s+", " ");
        if (s.isEmpty()) return null;
        try { return digest(sender + "|" + s); }
        catch (Exception e) { return null; }
    }
    private static String digest(String fold) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(fold.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 16; i++) {
            int b = h[i] & 0xFF;
            sb.append(Character.forDigit(b >>> 4, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
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
        try { return Long.parseLong(s.replace(",", "")); }
        catch (Exception e) { return -1; }
    }
    public static String normalizeLetters(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c == '\u064A' || c == '\u06CC') b.append('\u06CC');
            else if (c == '\u0643') b.append('\u06A9');
            else b.append(c);
        }
        return b.toString();
    }
    public static String digits(String s) { return LegacyDigits.ascii(s); }
}
