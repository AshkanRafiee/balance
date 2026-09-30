package com.ashkanrafiee.balance;

import android.content.Context;

import com.ashkanrafiee.balance.parser.PackDocument;
import com.ashkanrafiee.balance.parser.Parser;
import com.ashkanrafiee.balance.parser.Rules;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Loads the bank packs bundled as main assets, region by region, and indexes them by
 *  sender, so the production path can dispatch a message to the pack engine exactly
 *  where the engine covers it. {@link #activate(Context)} is called from every scan entry
 *  point, so the engine owns a covered message; anything it does not cover, or any message
 *  it does not parse, still goes through {@link BankRules} and the legacy reducers, and
 *  {@link #active()} is the single kill switch for the whole seam.
 *
 *  <p>The shipped asset root holds one directory per region, each with its own catalog and
 *  one directory per bank, because two regions cannot both put a catalog at the asset root.
 *  The index at that root lists the regions in the order they are registered: a sender that
 *  two regions claim belongs to the region listed first, and a sender the legacy Iranian
 *  table claims belongs to that table's bank however late its region is listed. Each pack
 *  states its own country and provenance, so a region is a grouping for the settings screen
 *  and nothing more: nothing here treats an official pack as more trusted than a community
 *  one, and both are read the same way.
 *
 *  <p>The sender index uses the same normalization as {@code BankRules.resolve}, so an inbox
 *  sender that differs from the alias only in whitespace, case, or an IR mobile prefix still finds
 *  its pack. Beyond the index, {@link #parse} never retries with legacy suffix matching: a message
 *  the index does not hit is routed back to the legacy path by the caller. */
final class EngineRules {

    /** The one catalog bank that is legitimately without a pack (shared sender with Tosee Taavon). */
    private static final String UNPACKED_COLLISION_BANK = "ir.tosee-credit-inst";

    private static volatile EngineRules instance;

    static final class Bank {
        /** The shipped region this pack came from, which is also how the settings screen groups
         *  banks: a region is one country covered by one kind of provenance. */
        final String region;
        final String id;
        final String name;
        final String country;
        /** Who asked for this bank's coverage. Purely descriptive: it decides what the settings
         *  screen labels a pack and which of them it offers a switch for, never whether the pack
         *  is loaded or how well it parses. */
        final PackDocument.Bank.Provenance provenance;
        final Parser parser;
        final List<Rules.Template> templates;

        Bank(String region, String id, String name, String country,
             PackDocument.Bank.Provenance provenance, Parser parser, List<Rules.Template> templates) {
            this.region = region;
            this.id = id;
            this.name = name;
            this.country = country;
            this.provenance = provenance;
            this.parser = parser;
            this.templates = templates;
        }
    }

    private static final String INDEX = "index.json";

    private final Map<String, Bank> bankOfSender = new HashMap<>();
    private final Map<String, Bank> banks = new HashMap<>();
    /** Every loaded bank in the order its region's catalog lists it, so registration does not
     *  depend on the iteration order of a hash map: two regions claiming one sender must always
     *  resolve to the same bank. */
    private final List<Bank> ordered = new ArrayList<>();
    private volatile boolean active;

    static EngineRules get() {
        return instance;
    }

    /** Loads the catalog and every packed bank once; subsequent calls return the same instance. */
    static EngineRules load(Context context) throws IOException {
        EngineRules current = instance;
        if (current != null) return current;
        synchronized (EngineRules.class) {
            if (instance != null) return instance;
            instance = new EngineRules(context);
            return instance;
        }
    }

    /** Production activation: loads the bundled packs once and turns the seam on, so every message
     *  the engine covers is parsed by the engine. Called from every entry point that can trigger a
     *  scan (main activity, history refresh, widget), so the behavior does not depend on how the
     *  process was started. A load failure is logged and leaves the seam off and the legacy path in
     *  place — an unreadable pack must never make a bank message unreadable. */
    static EngineRules activate(Context context) {
        EngineRules current = instance;
        if (current != null) return current;
        try {
            current = load(context.getApplicationContext());
        } catch (IOException unavailable) {
            android.util.Log.w(TAG, "engine packs unavailable, keeping the legacy parser", unavailable);
            return null;
        }
        current.active = true;
        return current;
    }

    private static final String TAG = "EngineRules";

    private EngineRules(Context context) throws IOException {
        Map<String, Bank> byName = new HashMap<>();
        for (String region : regions(context)) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries =
                    (List<Map<String, Object>>) asset(context, region + "/catalog.json").get("banks");
            for (Map<String, Object> entry : entries) {
                String id = (String) entry.get("id");
                PackDocument pack;
                try (InputStream input = context.getAssets().open(region + "/" + id + "/pack.json")) {
                    pack = PackDocument.decode(PlatformRuleJson.read(input));
                } catch (IOException unpacked) {
                    if (UNPACKED_COLLISION_BANK.equals(id)) continue;
                    throw unpacked;
                }
                // Two regions shipping one bank id would leave the sender index and the id lookup
                // disagreeing about which pack is which, so it fails the load instead: a catalog
                // mistake must not become half of one bank's messages read one way and half another.
                if (banks.containsKey(id)) throw new IOException("two regions ship a bank with the id " + id);
                Bank bank = new Bank(region, id, pack.bank().name(), pack.bank().country(),
                        pack.bank().provenance(), new Parser(pack.templates()), pack.templates());
                banks.put(id, bank);
                ordered.add(bank);
                byName.put(bank.name, bank);
            }
        }
        // A sender can legitimately belong to two banks (e.g. 98700717 is a Bank Melli and a Post
        // Bank alias), and the legacy resolve() picks the first bank in table order. Register the
        // packs in exactly that order, first wins, so the engine dispatches a shared sender to the
        // same bank resolve() would; the two then agree on the message by construction. Any pack
        // unreachable from the legacy table is registered afterwards, in catalog order.
        for (String[] row : BankRules.rulesTestOnly()) {
            Bank bank = byName.get(row[0]);
            if (bank != null) register(bank);
        }
        for (Bank bank : ordered) register(bank);
    }

    /** The shipped regions, in the order the index lists them. */
    private static List<String> regions(Context context) throws IOException {
        @SuppressWarnings("unchecked")
        List<String> regions = (List<String>) asset(context, INDEX).get("regions");
        if (regions == null || regions.isEmpty()) throw new IOException(INDEX + " lists no region");
        return regions;
    }

    private static Map<String, Object> asset(Context context, String path) throws IOException {
        try (InputStream input = context.getAssets().open(path)) {
            return PlatformRuleJson.read(input);
        }
    }

    /** Registers every packed alias under both its raw form (the engine matches senders exactly,
     *  as authored) and its BankRules-normalized form (what the legacy resolve() path matches inbox
     *  senders against). Never overwrites an existing owner: the first-registered bank wins. */
    private void register(Bank bank) {
        for (Rules.Template template : bank.templates) {
            for (String sender : template.senders()) {
                put(sender, bank);
                put(BankRules.normalize(sender), bank);
            }
        }
    }

    private void put(String key, Bank bank) {
        if (!key.isEmpty() && !bankOfSender.containsKey(key)) bankOfSender.put(key, bank);
    }

    /** Whether the production seam may route packed messages to this engine yet. */
    boolean active() {
        return active;
    }

    void setActive(boolean on) {
        active = on;
    }

    Bank covers(String sender) {
        if (sender == null) return null;
        Bank bank = bankOfSender.get(sender);
        if (bank == null) bank = bankOfSender.get(BankRules.normalize(sender));
        return bank;
    }

    int bankCount() {
        return banks.size();
    }

    /** Every shipped bank, in the order the index registers its regions. */
    List<Bank> banksInOrder() {
        return java.util.Collections.unmodifiableList(ordered);
    }

    /** The canonical bank name a packed catalog id maps to, or null when no pack carries it. This
     *  is the bridge from the engine's catalog ids to the bank name the app keys storage by. */
    String bankNameOf(String bankId) {
        Bank bank = banks.get(bankId);
        return bank == null ? null : bank.name;
    }

    int senderAliasCount() {
        return bankOfSender.size();
    }

    /** Parses a message with the pack engine, or returns null when no pack covers the sender. The
     *  sender passed into the engine is the raw inbox form, because the engine matches template
     *  aliases exactly as authored; when only the normalized form reaches the index, the engine
     *  reports a non-PARSED status and the caller routes the message back to the legacy path. */
    Parser.Result parse(String sourceId, String sender, String body, Instant arrival, ZoneId zone) {
        Bank bank = covers(sender);
        if (bank == null) return null;
        Parser.Message message = new Parser.Message(sourceId, sender, body, arrival, zone);
        return bank.parser.parse(message);
    }
}