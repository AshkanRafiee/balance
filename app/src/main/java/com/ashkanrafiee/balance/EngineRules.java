package com.ashkanrafiee.balance;

import android.content.Context;

import com.ashkanrafiee.balance.parser.PackDocument;
import com.ashkanrafiee.balance.parser.LocalPackStore;
import com.ashkanrafiee.balance.parser.Parser;
import com.ashkanrafiee.balance.parser.Rules;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
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
 *  the index does not hit is routed back to the legacy path by the caller.
 *
 *  <p>A pack the user brought or wrote is composed here too, through {@link LocalPackStore}, and
 *  the two are held to one rule: a bundled pack always keeps a sender it already claims. A local
 *  pack is registered after every bundled one, so an imported fork of a bank we ship reads that
 *  bank's messages only where its own senders are not already covered -- the fork is a rule the
 *  user added, not a way to replace coverage they did not ask to replace. Among local packs the
 *  first in id order wins, so which of two packs owns a shared sender never depends on the order a
 *  directory happened to be read in.
 *
 *  <p>Because an import can land while the app is running, the index is immutable and swapped
 *  whole: a scan either reads the composition as it was when it started or the one that replaced
 *  it, never a half-rebuilt map. A local store that cannot be opened leaves the bundled
 *  composition in place rather than failing the load -- packs the user brought must never make
 *  bank messages unreadable, and their absence is reported by {@link #unreadableLocal()} instead. */
final class EngineRules {

    /** The one catalog bank that is legitimately without a pack (shared sender with Tosee Taavon). */
    private static final String UNPACKED_COLLISION_BANK = "ir.tosee-credit-inst";

    /** Where local packs live: app-private no-backup storage, so rules never leave the device with
     *  a backup and never travel with one either. The store creates it and owns what is inside. */
    private static final String LOCAL_DIRECTORY = "local-packs";

    private static volatile EngineRules instance;

    static final class Bank {
        /** The shipped region this pack came from, or {@code null} for a local pack, which belongs
         *  to no region: a region is a grouping of the catalog we publish, not a claim about a
         *  user's own rules. */
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

        /** Whether this pack came from the device rather than from the shipped catalog. */
        boolean local() {
            return region == null;
        }
    }

    /** One immutable composition of every pack the engine will answer from. Swapped as a whole, so
     *  a reader never observes a partially rebuilt index. */
    private static final class Index {
        final Map<String, Bank> bySender;
        final Map<String, Bank> byId;
        final List<Bank> bundled;
        final List<Bank> local;
        final List<String> unreadable;

        Index(Map<String, Bank> bySender, Map<String, Bank> byId, List<Bank> bundled,
                List<Bank> local, List<String> unreadable) {
            this.bySender = bySender;
            this.byId = byId;
            this.bundled = Collections.unmodifiableList(bundled);
            this.local = Collections.unmodifiableList(local);
            this.unreadable = Collections.unmodifiableList(unreadable);
        }
    }

    static final String INDEX = "index.json";

    /** Every bundled bank in the order its region's catalog lists it, so registration does not
     *  depend on the iteration order of a hash map: two regions claiming one sender must always
     *  resolve to the same bank. */
    private final List<Bank> shipped;
    private final Context context;
    private volatile Index index;
    private volatile boolean active;
    /** What the local directory looked like when the current composition was built: the file names,
     *  sizes and modification times, which is enough to tell whether anything changed without
     *  re-reading every pack on every scan. */
    private volatile String localSignature;
    /** How many times the local composition has changed on this device. A scan remembers the number
     *  it read the inbox under and re-reads the inbox when the number has moved on, for the same
     *  reason it does for the reader's on/off choice: a pack that arrived now has messages already
     *  in the inbox that only it can read. */
    private volatile int localGeneration;

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
        this.context = context.getApplicationContext();
        this.shipped = loadShipped(this.context);
        refreshLocal();
    }

    /** Every bundled bank, in catalog order, which is also the order the sender index registers
     *  them in after the legacy table's own order. */
    private static List<Bank> loadShipped(Context context) throws IOException {
        List<Bank> ordered = new ArrayList<>();
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
                if (find(ordered, id) != null) {
                    throw new IOException("two regions ship a bank with the id " + id);
                }
                Bank bank = new Bank(region, id, pack.bank().name(), pack.bank().country(),
                        pack.bank().provenance(), new Parser(pack.templates()), pack.templates());
                ordered.add(bank);
            }
        }
        return ordered;
    }

    private static Bank find(List<Bank> banks, String id) {
        for (Bank bank : banks) if (bank.id.equals(id)) return bank;
        return null;
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

    /** Opens the local pack store over this app's no-backup directory, for the import screen and
     *  for gates that need to reach the same packs the engine reads. */
    static LocalPackStore localStore(Context context) throws IOException {
        File noBackup = context.getNoBackupFilesDir();
        if (noBackup == null) throw new IOException("no-backup storage unavailable");
        return LocalPackStore.open(packsDirectory(context), PlatformRuleJson::read);
    }

    /** The directory local packs are stored in. Package-private so a test can assert against the
     *  same place the app writes rather than a copy of the path. */
    static Path packsDirectory(Context context) {
        return new File(context.getNoBackupFilesDir(), LOCAL_DIRECTORY).toPath();
    }

    /** The local packs currently stored, or an empty list when the store cannot be read. An
     *  unreadable store costs the user their own rules, not the app's ability to read a bank. */
    private static List<Bank> loadLocal(Context context, List<String> unreadable) {
        LocalPackStore store;
        try {
            store = localStore(context);
        } catch (IOException unavailable) {
            android.util.Log.w(TAG, "local packs unavailable, keeping the bundled engine", unavailable);
            return Collections.emptyList();
        }
        List<Bank> banks = new ArrayList<>();
        try {
            // The store already isolated what it could not read, so one damaged file costs the
            // user that pack rather than the whole store.
            LocalPackStore.Snapshot snapshot = store.snapshot();
            for (LocalPackStore.Pack pack : snapshot.packs()) {
                PackDocument document = pack.document();
                banks.add(new Bank(null, document.id(), document.bank().name(),
                        document.bank().country(), document.bank().provenance(),
                        new Parser(document.templates()), document.templates()));
            }
        } catch (IOException unavailable) {
            android.util.Log.w(TAG, "local packs unreadable, keeping the bundled engine", unavailable);
            return Collections.emptyList();
        }
        return banks;
    }

    /** What the local directory holds, cheaply: names, sizes and modification times. Every field
     *  that would change a pack's content is here, so an unchanged signature means the composition
     *  cannot have changed, and a scan does not re-read packs it already read.
     *
     *  <p>The signature is computed from the file system rather than the store, because opening the
     *  store is exactly the work this is trying to avoid: a sweep and a directory listing the app
     *  must not perform on every message. A file this cannot describe -- an unreadable directory, or
     *  a pack added while it was being listed -- produces a different signature, which costs one
     *  pointless rebuild and never a missed one. */
    private static String signature(Context context) {
        File directory = packsDirectory(context).toFile();
        File[] files = directory.listFiles();
        if (files == null) return directory.exists() ? "unreadable" : "";
        List<String> described = new ArrayList<>(files.length);
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(LocalPackStore.SUFFIX) && !name.endsWith(LocalPackStore.TEMPORARY))
                continue;
            described.add(name + ':' + file.length() + ':' + file.lastModified());
        }
        Collections.sort(described);
        return String.join(",", described);
    }

    /** Rereads the local store and rebuilds the composition, so a pack imported or removed after
     *  the process started is read by the next message. Returns true when the local set changed in
     *  a way that can alter what a sender resolves to.
     *
     *  <p>The bundled composition is loaded once and reused: it cannot change under a running
     *  process, and rereading 43 packs to answer "did my import land" would cost more than it says. */
    synchronized boolean refreshLocal() {
        String current = signature(context);
        // Null until the first composition is built: an empty directory has the empty signature,
        // and "nothing was composed yet" is not the same answer as "nothing has changed".
        if (localSignature != null && current.equals(localSignature)) return false;
        List<String> unreadable = unreadableLocalNames();
        List<Bank> local = loadLocal(context, unreadable);
        Index next = compose(shipped, local, unreadable);
        Index previous = index;
        index = next;
        localSignature = current;
        if (!local.equals(previous == null ? Collections.emptyList() : previous.local)
                || !unreadable.equals(previous == null ? Collections.emptyList()
                        : previous.unreadable))
            localGeneration++;
        return true;
    }

    /** How many times the local composition has changed on this device. A scan compares this with
     *  the number it last read the inbox under, because a pack that arrived afterwards has
     *  messages already in the inbox that only that pack can read. */
    int localGeneration() {
        return localGeneration;
    }

    /** Composes one index: bundled banks register first, in legacy-table order and then catalog
     *  order, and local packs after them, so no pack a user brought can take a sender a shipped pack
     *  already claims. Within each group the first registration wins, and registration never
     *  overwrites, which is what makes the outcome independent of map iteration order. */
    private static Index compose(List<Bank> shipped, List<Bank> local, List<String> unreadable) {
        Map<String, Bank> bySender = new HashMap<>();
        Map<String, Bank> byId = new HashMap<>();
        for (Bank bank : shipped) register(bySender, byId, bank);
        for (Bank bank : local) register(bySender, byId, bank);
        return new Index(bySender, byId, new ArrayList<>(shipped), new ArrayList<>(local), unreadable);
    }

    /** Registers every packed alias under both its raw form (the engine matches senders exactly,
     *  as authored) and its BankRules-normalized form (what the legacy resolve() path matches inbox
     *  senders against). Never overwrites an existing owner: the first-registered bank wins. */
    private static void register(Map<String, Bank> bySender, Map<String, Bank> byId, Bank bank) {
        byId.putIfAbsent(bank.id, bank);
        for (Rules.Template template : bank.templates) {
            for (String sender : template.senders()) {
                put(bySender, sender, bank);
                put(bySender, BankRules.normalize(sender), bank);
            }
        }
    }

    private static void put(Map<String, Bank> bySender, String key, Bank bank) {
        if (!key.isEmpty()) bySender.putIfAbsent(key, bank);
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
        Index current = index;
        Bank bank = current.bySender.get(sender);
        if (bank == null) bank = current.bySender.get(BankRules.normalize(sender));
        return bank;
    }

    int bankCount() {
        return index.byId.size();
    }

    /** Every shipped bank, in the order the index registers its regions. */
    List<Bank> banksInOrder() {
        return index.bundled;
    }

    /** Every bank the engine reads, shipped and local. This is what a screen listing banks to turn
     *  on and off draws, because a pack the user brought is a bank whose messages are read. */
    List<Bank> allBanksInOrder() {
        Index current = index;
        List<Bank> all = new ArrayList<>(current.bundled);
        all.addAll(current.local);
        return Collections.unmodifiableList(all);
    }

    /** The local packs currently composed, which a screen shows as the user's own rules. */
    List<Bank> localBanks() {
        return index.local;
    }

    /** Stored local packs the engine could not read, by file name. Non-empty means the user has
     *  rules on this device the app is not currently using, which is worth saying rather than
     *  silently ignoring. */
    List<String> unreadableLocal() {
        return index.unreadable;
    }

    private List<String> unreadableLocalNames() {
        try {
            return localStore(context).snapshot().unreadable();
        } catch (IOException unavailable) {
            return Collections.emptyList();
        }
    }

    /** The canonical bank name a packed catalog id maps to, or null when no pack carries it. This
     *  is the bridge from the engine's catalog ids to the bank name the app keys storage by. */
    String bankNameOf(String bankId) {
        Bank bank = index.byId.get(bankId);
        return bank == null ? null : bank.name;
    }

    int senderAliasCount() {
        return index.bySender.size();
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