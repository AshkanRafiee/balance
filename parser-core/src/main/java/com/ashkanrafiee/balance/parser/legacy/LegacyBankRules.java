package com.ashkanrafiee.balance.parser.legacy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Frozen legacy rules. Only trusted, hardcoded expressions are compiled here; captions belong
 * to the Android adapter. Declaration order and fingerprint arithmetic are compatibility data. */
public final class LegacyBankRules {
    private LegacyBankRules() {}

    private static final String[][] RULES = {
        {"Pasargad", "b.pasargad|098500019000|98500019000|+98500019000", "J"},
        {"Eghtesad Novin", "ENBank|Enbank|+9890004800|90004800", "J"},
        {"Shahr", "+98200035|20005|20003502|+98200085|700820428285|9200035|98200035|200035", "J"},
        {"Ansar", "+98200036|100036|98100038", "J"},
        {"Tejarat", "5000973189|985000973189|tejaratbank|TejaratBank", "J"},
        {"Refah", "Refah|REFAH|REFAH BANK|Refah Bank|RefahBank", "J"},
        {"Saman", "+9820000|Saman Bank|Saman|500095|SamanBank|9999920000|2000084080|99999984080|099999984080|9899999984080|+989999984080|+9899999984080|989999920000|+989999920000", "J"},
        {"Sarmayeh", "+98300058|98300058|7007058|987007058|+987007058", "J"},
        {"Sina", "Sina Bank|+9850003700798704|9850003700798704|50003700798704|09850004756|+9850004756|9850004756|50004756|50004751|+98300028|500048|500019|98500048|sina bank|SinaBank|sinabank", "J"},
        {"Saderat", "BankSaderat|Bank Saderat|Saderat| صادرات", "J"},
        {"Mellat", "Bank Mellat|BankMellat|Mellat", "J"},
        {"Melli", "Bank Melli|BankMelli|Melli Iran", "J"},
        {"Maskan", "Bank Maskan|BankMaskan|Maskan", "J"},
        {"Keshavarzi", "Keshavarzi|Bank Keshavarzi", "J"},
        {"Parsian", "ParsianBank|Parsian|Bank Parsian", "J"},
        {"Post", "Post|PostBank|Post Bank", "J"},
        {"Dey", "Dey|Bank Dey", "J"},
        {"Hekmat", "Hekmat Iranian|Hekmat", "J"},
        {"Tosee Taavon", "Tosee Taavon", "J"},
        {"Noor", "Noor Credit Inst.|Noor|0200080947001|0200002734006", "J"},
        {"Blu", "Blu|blu|+982187641|98300087641|300087641|989999987641|9999987641|+989999987641|+9890000258", "J"},
        {"Kosar", "Kosar|Kosar Credit", "J"},
        {"Mehr", "Mehr Iran|MehrIran", "J"},
        {"Mehr Eghtesad", "Mehr Eghtesad|MehrEghtesad", "J"},
        {"Ghavamin", "Ghavamin|Ghavamin Bank", "J"},
        {"Zamin", "Iran Zamin|IranZamin", "J"},
        {"Gardeshgari", "Gardeshgari|Tourism Bank", "J"},
        {"Middle East", "Middle East Bank|Khavarmianeh", "J"},
        {"Tosee", "Tosee|Tosee Bank", "J"},
        {"Karafarin", "Karafarin|Karafarin Bank", "J"},
        {"Resalat", "Resalat|Bank Resalat", "J"},
        {"Venezuela", "Iran Venezuela|IranVenezuela", "J"},
        {"Melal", "Melal|Melal Credit Inst.", "J"},
        {"Sanat Madan", "Sanat Madan|SanatMadan", "J"},
        {"Sepah", "Sepah|Bank Sepah", "J"},
        {"Tosee Saderat", "Tosee Saderat|ToseeSaderat", "J"},
        {"Bankino", "Bankino|Bankino Bank", "J"},
        {"Wepod", "Wepod|Wepod Bank", "J"}
    };
    private static final String[][] OFFICIAL_EXTRA_RULES = {
        {"Saderat", "+987007851040|+9830009419|9830009419|30009419|983-000-9419|+98200060|+98200040|+9820004008|+98700719|700710|700718|98700719|700719|7007190", "J"},
        {"Sepah", "100072419|SEPAHBANK|SEPAH BANK|SepahBank|Sepah Bank|986715001|+986715001|6715001|986715000|6715000|+986715000|+986715000015|986715000015|+989122200207|200015|6715000015|+986830068400107|98715000015|6715000016", "J"},
        {"Industry & Mine", "+9820004003|+98100099|100099", "J"},
        {"Resalat", "2000474701|+982000474701|982000474701|Resalat|resalat|RESALAT|ResalatBank|Resalat Bank|resalatbank|50001474701|9850001474701|+9850001474701|9850004747|+9850004747|989999904747|9999904747|50004747|500014747|+9820004747|20004746|20004747|+98500014747|9820004747", "J"},
        {"Mehr", "B.QMEHRIRAN", "J"},
        {"Ghavamin", "+981000222|+9820000222|+981105151|2000222|2000228", "J"},
        {"Maskan", "+9810002503|+9850004920|+98500094|100025|98100025|9850004930", "J"},
        {"Mellat", "+9815560001|+981000920000|981000920000|1000920000|9815560001|+9830007505|+9820003304|+9820003305|+9830003304|30003305|500092000", "J"},
        {"Melli", "+987007170|98500043087|300084731|+989032229936|+98700717|+98200044|+9820004000|98700717|700717|9830009417|+9830009417|30009417|983000941001|200080|3000941001|98300094170|+983000941001|+98700759", "J"},
        {"Mehr Eghtesad", "+98200089|+98100089|+982000089|+981000089", "J"},
        {"Parsian", "99902318|99992318|+98200082|+98300054|+98500024|+9850002318|+9850001099|50001099|300071|9830007171|9810005403|9830007171|9899902318", "J"},
        {"Post", "9840400108|+9840400108|40400108|50004940|+9820004940|9820004940|20004940|+98200029|+98100029|50004949|98700717|9850004940|98500009440|+9850004940", "J"},
        {"Karafarin", "200057780|B.Karafarin|98200004321|+9830004321|30004321|+98200004321|50004858|50004857|98200002341|981000004|200004321", "J"},
        {"Keshavarzi", "+98300081301|5000181301|+989999944444|9999944444|989999944444", "J"},
        {"Zamin", "IZBANK", "J"},
        {"Gardeshgari", "TourismBank|+982000300|982000309|982000300", "J"},
        {"Kosar", "+9850002477|10002477|9810002477|6715014005|98715014005", "J"},
        {"Tosee Taavon", "ttbank|TTBANK|+9820006438|+985000257|5000157|+985000157|500158|30005816|+989810007000|9810007000", "J"},
        {"Middle East", "9820004861|+9820004861|20004861|20004840|+9820004860|9820004860|20004860", "J"},
        {"Dey", "2000766|+9820004002|+9820043|+9830002726|Day Bank|Day|+98300097500027|3000766|500018|982000766|DayBank|98200766|+982000766", "J"},
        {"Hekmat", "+9820008955", "J"},
        {"Tosee Credit Inst.", "+9830005816", "J"},
        {"EDBI", "7000730|+9830009430|9830009430|30009430", "J"},
        {"Melal Credit Inst.", "+98200022222", "J"},
        {"Noor Credit Inst.", "9830009480|30009480|+9820004009|7007780|20004293", "J"},
        {"Wepod", "+981000214|98500011|5000114|+985000114|985000114|981000214|1000214|9830009017|30009017", "J"},
        {"Bankino", "20004860", "J"}
    };

    private static final Set<String> SUPPORTED_BANKS = new HashSet<>();
    private static final Map<String, LegacyCalendarSystem> CALENDARS = new HashMap<>();
    private static final class Alias {
        final String bank;
        final int index;
        Alias(String bank, int index) { this.bank = bank; this.index = index; }
    }
    private static final Map<String, Alias> EXACT = new HashMap<>();
    private static final Map<String, Alias> SUFFIX_OF = new HashMap<>();
    static {
        int index = 0;
        for (String[][] table : new String[][][]{RULES, OFFICIAL_EXTRA_RULES}) {
            for (String[] row : table) {
                SUPPORTED_BANKS.add(row[0]);
                LegacyCalendarSystem cal = LegacyCalendarSystem.ofTag(row.length > 2 ? row[2] : null);
                if (cal != null) CALENDARS.put(row[0], cal);
                for (String alias : row[1].split("\\|")) registerAlias(normalize(alias), row[0], index++);
            }
        }
    }
    private static void registerAlias(String b, String bank, int index) {
        if (b.isEmpty() || EXACT.containsKey(b)) return;
        EXACT.put(b, new Alias(bank, index));
        if (!allDigits(b) || b.length() < 5) return;
        for (int len = 5; len <= b.length(); len++)
            SUFFIX_OF.putIfAbsent(b.substring(b.length() - len), new Alias(bank, index));
    }
    private static boolean allDigits(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        return true;
    }
    public static String resolve(String sender) {
        if (sender == null || sender.indexOf('*') >= 0 || sender.indexOf('#') >= 0) return null;
        String a = normalize(sender);
        if (a.isEmpty()) return null;
        if (allDigits(a) && a.length() >= 5) {
            Alias best = SUFFIX_OF.get(a);
            int start = a.length() - 5;
            for (int len = 5; len <= a.length(); len++) {
                Alias e = EXACT.get(a.substring(start));
                if (e != null && (best == null || e.index < best.index)) best = e;
                start--;
            }
            return best == null ? null : best.bank;
        }
        Alias e = EXACT.get(a);
        return e == null ? null : e.bank;
    }
    public static String normalize(String raw) {
        StringBuilder out = new StringBuilder();
        for (char c : LegacyDigits.ascii(raw).toCharArray())
            if (Character.isLetterOrDigit(c)) out.append(Character.toLowerCase(c));
        String s = out.toString();
        if (s.startsWith("0098")) s = s.substring(4);
        if (s.startsWith("98") && s.length() > 8) s = s.substring(2);
        return s;
    }
    public static LegacyCalendarSystem calendar(String bank) {
        LegacyCalendarSystem cal = CALENDARS.get(bank);
        return cal == null ? LegacyCalendarSystem.JALALI : cal;
    }
    public static Set<String> supportedNames() { return new HashSet<>(SUPPORTED_BANKS); }
    public static List<String> aliasList() {
        List<String> all = new ArrayList<>();
        for (String[] row : rulesTestOnly()) Collections.addAll(all, row[1].split("\\|"));
        return all;
    }
    public static Set<String> reachableBanks() {
        Set<String> all = new HashSet<>();
        for (String alias : aliasList()) {
            String bank = resolve(alias);
            if (bank != null) all.add(bank);
        }
        return all;
    }
    /** Defensive copies: callers cannot alter the frozen rules or compiled fingerprint. */
    public static String[][] rulesTestOnly() {
        String[][] all = new String[RULES.length + OFFICIAL_EXTRA_RULES.length][];
        int i = 0;
        for (String[] row : RULES) all[i++] = row.clone();
        for (String[] row : OFFICIAL_EXTRA_RULES) all[i++] = row.clone();
        return all;
    }
    private static String[][] copyRows(String[][] rows) {
        String[][] all = new String[rows.length][];
        for (int i = 0; i < rows.length; i++) all[i] = rows[i].clone();
        return all;
    }

    private static final String[][] ACCOUNT_RULES = {
        {"Mellat", "label-glued", "6", ""},
        {"Melli", "label-colon", "3", "12"},
        {"Tejarat", "label-colon", "6", "24"},
        {"Saderat", "label-colon-line", "4", "10"},
        {"Parsian", "bare-mablagh", "10", "24"},
        {"Mehr", "bare-bidi", "10", "24"},
        {"Resalat", "dotted", "", ""},
        {"Pasargad", "dotted-line", "", ""}
    };
    private static Pattern compileAccount(String[] row) {
        String d = row[3].isEmpty() ? "[0-9]{" + row[2] + ",}" : "[0-9]{" + row[2] + "," + row[3] + "}";
        switch (row[1]) {
            case "label-glued": return Pattern.compile("\u062D\u0633\u0627\u0628(" + d + ")");
            case "label-colon": return Pattern.compile("\u062D\u0633\u0627\u0628\\s*:\\s*(" + d + ")(?![0-9,.])");
            case "label-colon-line": return Pattern.compile("(?m)^[ \\t]*\u062D\u0633\u0627\u0628\\s*:\\s*(" + d + ")(?![0-9,.])");
            case "bare-mablagh": return Pattern.compile("(?m)^(" + d + ")\\s*\\r?\\n\\s*\u0645\u0628\u0644\u063A:");
            case "bare-bidi": return Pattern.compile("(?m)^[\\u202A-\\u202E]*(" + d + ")(?![0-9,.])[\\u202A-\\u202E ]*\\r?$");
            case "dotted": return Pattern.compile("(?<![0-9])[0-9]{1,2}\\.[0-9]{4,12}\\.[0-9]{1,2}(?![0-9])");
            case "dotted-line": return Pattern.compile("(?m)^[0-9]{1,4}\\.[0-9]{1,6}\\.[0-9]{6,12}\\.[0-9]{1,3}(?![0-9.])\\s*\\r?$");
            default: throw new IllegalArgumentException("unknown account shape '" + row[1] + "' for " + row[0]);
        }
    }
    private static final Map<String, Pattern> ACCOUNT_PATTERNS = new HashMap<>();
    static { for (String[] row : ACCOUNT_RULES) ACCOUNT_PATTERNS.put(row[0], compileAccount(row)); }
    public static String extractAccount(String bank, String body) {
        if (bank == null || body == null) return null;
        Pattern p = ACCOUNT_PATTERNS.get(bank);
        if (p == null) return null;
        Matcher m = p.matcher(LegacyDigits.ascii(body));
        if (!m.find()) return null;
        return m.groupCount() == 0 ? m.group(0) : m.group(1);
    }
    public static String[][] accountRulesTestOnly() { return copyRows(ACCOUNT_RULES); }

    private static final String[][] REASON_RULES = {{"Blu", "title-line"}};
    private static final int MAX_REASON_LENGTH = 60;
    private static final Set<String> REASON_KEYS = new HashSet<>();
    static {
        Collections.addAll(REASON_KEYS, "شارژ شدی", "پرداخت قبض", "برگشت پول", "دریافت پل", "انتقال پل");
    }
    private static Pattern compileReason(String[] row) {
        switch (row[1]) {
            case "title-line": return Pattern.compile("\\A[^\\r\\n]*\\r?\\n[ \\t]*([^\\d\\s\\r\\n][^\\d\\r\\n]{0,"
                + (MAX_REASON_LENGTH - 2) + "}[^\\d\\s\\r\\n])[ \\t]*\\r?\\n");
            default: throw new IllegalArgumentException("unknown reason shape '" + row[1] + "' for " + row[0]);
        }
    }
    private static final Map<String, Pattern> REASON_PATTERNS = new HashMap<>();
    static { for (String[] row : REASON_RULES) REASON_PATTERNS.put(row[0], compileReason(row)); }
    public static String extractReason(String bank, String body) {
        if (bank == null || body == null) return null;
        Pattern p = REASON_PATTERNS.get(bank);
        if (p == null) return null;
        Matcher m = p.matcher(LegacyDigits.ascii(body));
        if (!m.find()) return null;
        String title = normalizeReason(m.group(1));
        return title.isEmpty() || !REASON_KEYS.contains(title) ? null : title;
    }
    public static String[][] reasonRulesTestOnly() { return copyRows(REASON_RULES); }
    /** Legacy allowlist keys, without Android resources or translated captions. */
    public static Set<String> reasonCaptionKeys() { return new HashSet<>(REASON_KEYS); }
    private static final String REASON_INVISIBLE =
        "\u200C\u200D\u200E\u200F\u202A\u202B\u202C\u202D\u202E" + "\u2066\u2067\u2068\u2069";
    private static final Pattern REASON_SPACES = Pattern.compile("\\s+");
    private static String normalizeStated(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (REASON_INVISIBLE.indexOf(c) < 0) out.append(c);
        }
        return REASON_SPACES.matcher(out.toString().trim()).replaceAll(" ");
    }
    public static String normalizeReason(String raw) { return normalizeStated(raw); }

    private static final String[][] CHANNEL_RULES = {{"Tejarat", "labeled-line", "از طريق:"}};
    private static final int MAX_CHANNEL_LENGTH = 40;
    private static final Set<String> CHANNEL_KEYS = new HashSet<>();
    static {
        Collections.addAll(CHANNEL_KEYS, "شتاب", "سامانه پل (پرداخت لحظه ای)", "پایانه فروش", "همراه بانک", "شعبه");
    }
    private static Pattern compileChannel(String[] row) {
        switch (row[1]) {
            case "labeled-line": return Pattern.compile("(?m)^[ \\t]*" + Pattern.quote(LegacyMoney.normalizeLetters(row[2]))
                + "[ \\t]*([^\\d\\s\\r\\n][^\\d\\r\\n]{0," + (MAX_CHANNEL_LENGTH - 2)
                + "}[^\\d\\s\\r\\n])[ \\t]*\\r?\\n");
            default: throw new IllegalArgumentException("unknown channel shape '" + row[1] + "' for " + row[0]);
        }
    }
    private static final Map<String, Pattern> CHANNEL_PATTERNS = new HashMap<>();
    static { for (String[] row : CHANNEL_RULES) CHANNEL_PATTERNS.put(row[0], compileChannel(row)); }
    public static String extractChannel(String bank, String body) {
        if (bank == null || body == null) return null;
        Pattern p = CHANNEL_PATTERNS.get(bank);
        if (p == null) return null;
        Matcher m = p.matcher(LegacyMoney.normalizeLetters(LegacyDigits.ascii(body)));
        if (!m.find()) return null;
        String channel = normalizeChannel(m.group(1));
        return channel.isEmpty() || !CHANNEL_KEYS.contains(channel) ? null : channel;
    }
    public static String normalizeChannel(String raw) { return normalizeStated(LegacyMoney.normalizeLetters(raw)); }
    public static String[][] channelRulesTestOnly() { return copyRows(CHANNEL_RULES); }
    public static Set<String> channelCaptionKeys() { return new HashSet<>(CHANNEL_KEYS); }

    private static int rulesVersion() {
        int v = 0;
        for (String[] row : RULES) for (String alias : row[1].split("\\|")) v = v * 31 + alias.hashCode();
        for (String[] row : OFFICIAL_EXTRA_RULES) for (String alias : row[1].split("\\|")) v = v * 31 + alias.hashCode();
        List<String> accounts = new ArrayList<>();
        for (String[] row : ACCOUNT_RULES) accounts.add(row[0]);
        Collections.sort(accounts);
        for (String bank : accounts) v = v * 31 + bank.hashCode() * 31 + ACCOUNT_PATTERNS.get(bank).pattern().hashCode();
        List<String> reasons = new ArrayList<>();
        for (String[] row : REASON_RULES) reasons.add(row[0]);
        Collections.sort(reasons);
        for (String bank : reasons) v = v * 31 + bank.hashCode() * 31 + REASON_PATTERNS.get(bank).pattern().hashCode();
        for (String title : new TreeSet<>(REASON_KEYS)) v = v * 31 + title.hashCode() * 31;
        v = v * 31 + REASON_INVISIBLE.hashCode() * 31 + REASON_SPACES.pattern().hashCode();
        List<String> channels = new ArrayList<>();
        for (String[] row : CHANNEL_RULES) channels.add(row[0]);
        Collections.sort(channels);
        for (String bank : channels) v = v * 31 + bank.hashCode() * 31 + CHANNEL_PATTERNS.get(bank).pattern().hashCode();
        for (String channel : new TreeSet<>(CHANNEL_KEYS)) v = v * 31 + channel.hashCode() * 31;
        return v;
    }
    /** Deliberately excludes bank names, calendar tags and captions, as the legacy app does. */
    public static final int VERSION = rulesVersion();
}
