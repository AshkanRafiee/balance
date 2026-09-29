package com.ashkanrafiee.balance.parser.catalog;

import static com.ashkanrafiee.balance.parser.Parser.ENGINE;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** One immutable, compiled, indexed snapshot of a sender catalog for a profile.
 * Banks, calendars and senders are supplied in declaration order by the caller
 * (the same order the legacy contract uses for first-wins resolution); the model
 * and a deterministic digest never depend on map iteration order. */
public final class CompiledCatalog {
    public record BankEntry(String bankId, String displayName, String calendar, List<String> aliases) {
        public BankEntry {
            Objects.requireNonNull(bankId);
            Objects.requireNonNull(displayName);
            Objects.requireNonNull(calendar);
            aliases = List.copyOf(aliases);
            if (bankId.isEmpty() || displayName.isEmpty() || calendar.isEmpty())
                throw new IllegalArgumentException("empty bank metadata");
        }
    }
    /** A normalized sender owned by two distinct banks; resolved only through a pin. */
    public record Collision(String key, String winnerBank, String otherBank) {}
    /** Informational: a suffix match resolves to a different bank than the exact owner. */
    public record SuffixOverlap(String key, String exactBank, String suffixBank) {}

    private static final class Owner {
        final String bankId;
        final int index;
        Owner(String bankId, int index) { this.bankId = bankId; this.index = index; }
    }

    private final SenderProfile profile;
    private final List<BankEntry> banks;
    private final Map<String, Owner> exact;
    private final Map<String, Owner> suffix;
    private final List<Collision> collisions;
    private final List<SuffixOverlap> suffixOverlaps;
    private final String digest;

    private CompiledCatalog(SenderProfile profile, List<BankEntry> banks, Map<String, Owner> exact,
            Map<String, Owner> suffix, List<Collision> collisions, List<SuffixOverlap> suffixOverlaps,
            String digest) {
        this.profile = profile;
        this.banks = List.copyOf(banks);
        this.exact = Map.copyOf(exact);
        this.suffix = Map.copyOf(suffix);
        this.collisions = List.copyOf(collisions);
        this.suffixOverlaps = List.copyOf(suffixOverlaps);
        this.digest = digest;
    }

    public static CompiledCatalog compile(SenderProfile profile, List<BankEntry> entries) {
        return compile(profile, entries, CollisionAllowlist.NONE);
    }

    public static CompiledCatalog compile(SenderProfile profile, List<BankEntry> entries,
            CollisionAllowlist allowlist) {
        Objects.requireNonNull(profile);
        Objects.requireNonNull(allowlist);
        Map<String, Owner> exact = new HashMap<>();
        Map<String, Owner> suffix = new HashMap<>();
        List<Collision> collisions = new ArrayList<>();
        Set<String> bankIds = new HashSet<>();
        Set<String> displayNames = new HashSet<>();
        int index = 0;
        for (BankEntry bank : entries) {
            if (!bankIds.add(bank.bankId()))
                throw new IllegalArgumentException("duplicate bank id '" + bank.bankId() + "'");
            if (!displayNames.add(bank.displayName()))
                throw new IllegalArgumentException("duplicate display name '" + bank.displayName() + "'");
            for (String raw : bank.aliases()) {
                String b = profile.normalize(raw);
                if (b.isEmpty()) continue;
                Owner owner = new Owner(bank.bankId(), index++);
                Owner previous = exact.putIfAbsent(b, owner);
                if (previous != null && !previous.bankId.equals(bank.bankId()))
                    collisions.add(new Collision(b, previous.bankId, bank.bankId()));
                if (profile.suffixCandidate(b)) {
                    for (int len = profile.suffixMinDigits(); len <= b.length(); len++)
                        suffix.putIfAbsent(b.substring(b.length() - len), owner);
                }
            }
        }
        enforceAllowlist(allowlist, exact, collisions);
        List<SuffixOverlap> suffixOverlaps = suffixOverlaps(exact, suffix, profile);
        String digest = digest(profile, entries, allowlist);
        return new CompiledCatalog(profile, entries, exact, suffix,
                sortedCollisions(collisions), sortedOverlaps(suffixOverlaps), digest);
    }

    private static void enforceAllowlist(CollisionAllowlist allowlist, Map<String, Owner> exact,
            List<Collision> collisions) {
        if (allowlist.isEmpty()) {
            if (!collisions.isEmpty()) throw new IllegalArgumentException(
                "collision allowlist required for keys " + keys(collisions));
            return;
        }
        for (Map.Entry<String, String> pin : allowlist.pins().entrySet()) {
            Owner owner = exact.get(pin.getKey());
            if (owner == null)
                throw new IllegalArgumentException("stale collision pin '" + pin.getKey() + "'");
            if (!owner.bankId.equals(pin.getValue()))
                throw new IllegalArgumentException("collision pin '" + pin.getKey()
                    + "' rewrites the declared winner '" + owner.bankId + "'");
            boolean real = false;
            for (Collision collision : collisions)
                if (collision.key().equals(pin.getKey())) { real = true; break; }
            if (!real)
                throw new IllegalArgumentException("phantom collision pin '" + pin.getKey() + "'");
        }
        List<String> unpinned = new ArrayList<>();
        for (Collision collision : collisions)
            if (!allowlist.pins().containsKey(collision.key())) unpinned.add(collision.key());
        if (!unpinned.isEmpty())
            throw new IllegalArgumentException("collision allowlist missing pins for " + unpinned);
    }

    private static String keys(List<Collision> collisions) {
        List<String> keys = new ArrayList<>();
        for (Collision collision : collisions) keys.add(collision.key());
        return String.valueOf(sorted(keys));
    }

    private static List<SuffixOverlap> suffixOverlaps(Map<String, Owner> exact, Map<String, Owner> suffix,
            SenderProfile profile) {
        List<SuffixOverlap> overlaps = new ArrayList<>();
        for (Map.Entry<String, Owner> entry : exact.entrySet()) {
            Owner suffixOwner = suffix.get(entry.getKey());
            if (suffixOwner != null && !suffixOwner.bankId.equals(entry.getValue().bankId))
                overlaps.add(new SuffixOverlap(entry.getKey(), entry.getValue().bankId, suffixOwner.bankId));
        }
        return overlaps;
    }

    private static String digest(SenderProfile profile, List<BankEntry> entries, CollisionAllowlist allowlist) {
        List<String> lines = new ArrayList<>();
        lines.add("engine=" + ENGINE);
        lines.add("profile=" + profile.id());
        for (BankEntry bank : entries) {
            lines.add("bank=" + bank.bankId());
            lines.add("calendar=" + bank.calendar());
            for (String raw : bank.aliases()) {
                String b = profile.normalize(raw);
                if (!b.isEmpty()) lines.add("alias=" + b);
            }
        }
        for (String key : new TreeSet<>(allowlist.pins().keySet()))
            lines.add("allowlist=" + key + "=" + allowlist.winner(key));
        return EffectiveDigest.sha256(EffectiveDigest.canonical(lines));
    }

    /** Legacy-compatible resolution: exact match, then symmetric numeric suffix
     * matching for all-digit senders at or above the profile minimum, with the
     * earliest declared alias winning ties. Senders containing '*' or '#' never
     * resolve, exactly as the legacy contract rejects them up front. */
    public String resolve(String sender) {
        if (sender == null || sender.indexOf('*') >= 0 || sender.indexOf('#') >= 0) return null;
        String a = profile.normalize(sender);
        if (a.isEmpty()) return null;
        if (profile.suffixCandidate(a)) {
            Owner best = suffix.get(a);
            int start = a.length() - profile.suffixMinDigits();
            for (int len = profile.suffixMinDigits(); len <= a.length(); len++) {
                Owner e = exact.get(a.substring(start));
                if (e != null && (best == null || e.index < best.index)) best = e;
                start--;
            }
            return best == null ? null : best.bankId;
        }
        Owner e = exact.get(a);
        return e == null ? null : e.bankId;
    }

    public SenderProfile profile() { return profile; }
    public String digest() { return digest; }
    public List<Collision> collisions() { return collisions; }
    public List<SuffixOverlap> suffixOverlaps() { return suffixOverlaps; }

    /** Sorted stable bank IDs. */
    public Set<String> banks() {
        Set<String> ids = new TreeSet<>();
        for (BankEntry bank : banks) ids.add(bank.bankId());
        return ids;
    }

    /** Banks resolvable from at least one registered alias. */
    public Set<String> reachableBanks() {
        Set<String> ids = new TreeSet<>();
        for (BankEntry bank : banks)
            for (String raw : bank.aliases()) {
                String id = resolve(raw);
                if (id != null) ids.add(id);
            }
        return ids;
    }

    public String displayName(String bankId) {
        for (BankEntry bank : banks) if (bank.bankId().equals(bankId)) return bank.displayName();
        return null;
    }

    public String calendar(String bankId) {
        for (BankEntry bank : banks) if (bank.bankId().equals(bankId)) return bank.calendar();
        return null;
    }

    private static <T extends Comparable<? super T>> List<T> sorted(List<T> values) {
        List<T> copy = new ArrayList<>(values);
        Collections.sort(copy);
        return copy;
    }

    private static List<Collision> sortedCollisions(List<Collision> values) {
        List<Collision> copy = new ArrayList<>(values);
        Collections.sort(copy, Comparator.comparing(Collision::key));
        return copy;
    }

    private static List<SuffixOverlap> sortedOverlaps(List<SuffixOverlap> values) {
        List<SuffixOverlap> copy = new ArrayList<>(values);
        Collections.sort(copy, Comparator.comparing(SuffixOverlap::key));
        return copy;
    }

    /** Raw declaration-order entries, mainly for effective-digest parity checks. */
    public List<BankEntry> declarationEntries() { return banks; }
}