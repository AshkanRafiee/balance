package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Behavioral parity between the optimized BankRules.resolve() and the original two-pass algorithm.
 *  The oracle below performs EXACTLY the original compare sequence (RULES then OFFICIAL_EXTRA_RULES,
 *  alias-split order, first match wins) but precomputes each alias's normalized form and digit flag
 *  once, so the device test does not re-normalize ~450 aliases and recompile the "\\d+" regex on every
 *  one of the tens of thousands of fuzz samples. */
@RunWith(AndroidJUnit4.class)
public class BankRulesParityTest {

    private static final class Entry {
        final String bank;
        final String b;
        final boolean digits;
        Entry(String bank, String b) {
            this.bank = bank;
            this.b = b;
            this.digits = allDigits(b);
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    static {
        for (String[] rule : BankRules.rulesTestOnly())
            for (String alias : rule[1].split("\\|")) {
                String b = BankRules.normalize(alias);
                if (!b.isEmpty()) ENTRIES.add(new Entry(rule[0], b));
            }
    }

    private static boolean allDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** Exact replica of the original resolve() semantics: first alias to match exactly, or with
     *  both-digit len>=5 suffix overlap, wins. */
    private static String oracleResolve(String sender) {
        if (sender == null || sender.indexOf('*') >= 0 || sender.indexOf('#') >= 0) return null;
        String a = BankRules.normalize(sender);
        if (a.isEmpty()) return null;
        boolean aDigits = allDigits(a);
        for (Entry e : ENTRIES) {
            if (a.equals(e.b)) return e.bank;
            if (aDigits && e.digits && a.length() >= 5 && e.b.length() >= 5 && (a.endsWith(e.b) || e.b.endsWith(a))) return e.bank;
        }
        return null;
    }

    private static void assertParity(String sender) {
        assertEquals("resolve() diverged from original algorithm for sender=" + display(sender),
                oracleResolve(sender), BankRules.resolve(sender));
    }

    private static String display(String s) {
        return s == null ? "<null>" : "\"" + s + "\"";
    }

    private static List<String> digitAliases() {
        List<String> out = new ArrayList<>();
        for (String alias : BankRules.aliasList())
            out.add(BankRules.normalize(alias));
        return out;
    }

    // ---- directed cases carried over from the existing suite ---------------------------
    @Test public void parity_existingResolutionCases() {
        for (String s : new String[]{
                "5000973189", "500095", "+98500019000", "50003700798704",
                "9850003700798704", "989999920000", "9830005816", "+9830005816",
                "5000973189 ", "  Refah Bank  ", "mELLAt", "tejaratbank",
                "\u0635\u0627\u062f\u0631\u0627\u062a", "Refah Bank", "Tosee Taavon",
                null, "", "   ", "*5000973189", "5000*0973189", "5000973189#", "#",
                "1234567890", "09123456789", "5000", "1234", "98",
                "YouTube", "Google", "+98notanumber", "envoy", "anonymous"})
            assertParity(s);
    }

    // ---- every alias and exact normalized alias -----------------------------------------
    @Test public void parity_everyRawAlias() {
        for (String alias : BankRules.aliasList()) assertParity(alias);
    }

    @Test public void parity_everyNormalizedAlias() {
        for (String alias : digitAliases()) assertParity(alias);
    }

    // ---- renderings & mutations of every alias ------------------------------------------
    @Test public void parity_aliasRenderings() {
        for (String alias : BankRules.aliasList()) {
            String norm = BankRules.normalize(alias);
            assertParity("+" + alias);
            assertParity("+" + norm);
            assertParity(alias.toUpperCase(java.util.Locale.ROOT));
            assertParity(alias.toLowerCase(java.util.Locale.ROOT));
            assertParity(" " + alias + " ");
            assertParity("_" + alias + "_");
            if (BankRules.normalize(alias).matches("\\d+")) {
                assertParity("0098" + norm);
                assertParity(norm + "12345");
            }
        }
    }

    @Test public void parity_persianArabicDigitTexts() {
        String[] fa = {"\u06F0", "\u06F1", "\u06F2", "\u06F3", "\u06F4", "\u06F5", "\u06F6", "\u06F7", "\u06F8", "\u06F9"};
        String[] ar = {"\u0660", "\u0661", "\u0662", "\u0663", "\u0664", "\u0665", "\u0666", "\u0667", "\u0668", "\u0669"};
        for (String alias : digitAliases()) {
            if (!allDigits(alias)) continue;
            StringBuilder faB = new StringBuilder();
            StringBuilder arB = new StringBuilder();
            for (char c : alias.toCharArray()) {
                int d = c - '0';
                faB.append(fa[d]);
                arB.append(ar[d]);
            }
            assertParity(faB.toString());
            assertParity(arB.toString());
        }
    }

    // ---- suffix overlap stress (sender ends with alias / alias ends with sender) --------
    @Test public void parity_suffixOverlaps() {
        for (String b : digitAliases()) {
            if (b.length() < 5) continue;
            // a ends with b: prepend arbitrary digits, country codes, or letters.
            for (String prefix : new String[]{"", "1", "77", "0912", "98999", "0098", "+98"})
                assertParity(prefix + b);
            // b ends with a for every inner suffix length >= 5: sender is the suffix alone,
            // or the suffix with a different header, and matches b via b.endsWith(a).
            for (int len = 5; len <= b.length(); len++) {
                String a = b.substring(b.length() - len);
                assertParity(a);
                assertParity("0" + a);
                assertParity("98" + a);
            }
            // sender one digit longer/shorter than the alias.
            assertParity(b + "0");
            assertParity(b.substring(0, b.length() - 1));
        }
    }

    // ---- targeted first-wins attribution checks -----------------------------------------
    @Test public void parity_collisionStillAttributesToFirstAlias() {
        // +9830005816 belongs to Tosee Taavon (first in order) and also to a Credit Inst.; a suffix
        // fragment shared with a later alias must never steal the attribution from an earlier alias.
        assertEquals("Tosee Taavon", BankRules.resolve("+9830005816"));
        assertParity("+9830005816");
        assertParity("9830005816");
    }

    // ---- random fuzz over a wide alphabet ------------------------------------------------
    @Test public void parity_randomFuzz() {
        Random rnd = new Random(20260906L);
        char[] alphabet = "0123456789abcdefghijklmnopqrstuvwxyz ABCDEFGHIJKLMNOPQRSTUVWXYZ+*#-_/.,".toCharArray();
        for (int i = 0; i < 6000; i++) {
            StringBuilder sb = new StringBuilder();
            int len = rnd.nextInt(25);
            for (int j = 0; j < len; j++) sb.append(alphabet[rnd.nextInt(alphabet.length)]);
            assertParity(sb.toString());
        }
    }

    @Test public void parity_randomDigits() {
        Random rnd = new Random(13579L);
        for (int i = 0; i < 6000; i++) {
            int len = rnd.nextInt(24) + 1;
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < len; j++) sb.append((char) ('0' + rnd.nextInt(10)));
            assertParity(sb.toString());
            assertParity("+" + sb);
            assertParity("0098" + sb);
        }
    }

    @Test public void parity_suffixMutatedSenders() {
        // Sender = alias minus its head few digits, so resolve() must find it purely as a suffix of
        // the full alias; any wrong suffix registration would surface as a mismatch here.
        Random rnd = new Random(24680L);
        for (String b : digitAliases()) {
            if (b.length() < 6) continue;
            for (int cut = 1; cut <= 3; cut++) {
                String sender = b.substring(cut);
                assertParity(sender);
                StringBuilder pref = new StringBuilder();
                for (int j = 0; j < rnd.nextInt(4); j++) pref.append((char) ('0' + rnd.nextInt(10)));
                assertParity(pref + sender);
            }
        }
    }

    // ---- reachability / counts unchanged -------------------------------------------------
    @Test public void parity_reachableBanksUnchanged() {
        Set<String> reachable = BankRules.reachableBanks();
        // 42 bank senders must still be reachable so the incremental scan's early-exit bound holds.
        assertEquals(42, reachable.size());
        assertEquals(reachable, oracleReachable());
        assertEquals(42, BankRules.supportedSenderCount());
    }

    private static Set<String> oracleReachable() {
        Set<String> reachable = new HashSet<>();
        for (String[] rule : BankRules.rulesTestOnly())
            for (String alias : rule[1].split("\\|")) {
                String resolved = oracleResolve(alias);
                if (resolved != null) reachable.add(resolved);
            }
        return reachable;
    }
}