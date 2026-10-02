package com.ashkanrafiee.balance;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Money that provably left (or entered) an account without a message ever reaching us.
 *
 * <p>Every bank SMS states the balance <em>after</em> the movement it reports, so two balance
 * statements of the same account bracket everything that happened between them. If the balance moved
 * by more than the movements we actually received account for, the difference is not a guess — it is
 * arithmetic on two numbers the bank itself reported:
 *
 * <pre>residual = balance(after) − balance(before) − sum(movements we received in between)</pre>
 *
 * <p>"In between" is the half-open span after the earlier statement and up to and including the
 * later one. The closing statement's own movement is inside it, because the balance it reports was
 * read after that very movement; leaving it out would call every ordinary movement missing. *
 * <p>The app never invents the missing movement. We cannot know how many there were, what each one
 * was for, or when exactly it happened, so a residual stays a single net figure bounded by the two
 * statements that prove it. That is the honest form of the answer, and it is the whole point: a
 * history that quietly omits a withdrawal it knows about is worse than one that admits it.
 *
 * <p>Detection is a pure function of the stored movements, which is what makes the result
 * self-correcting. Should a delayed message finally arrive, the next scan parses it into a real
 * movement, the bracketing sums close, and the residual disappears on its own — no stored gap, no
 * invalidation, and no risk of both a residual and the real movement counting the same money.
 *
 * <p>What this cannot see, by construction: anything before the first balance statement we hold for
 * an account, and anything after the last one. A gap is only ever provable from two statements.
 */
final class Residual {

    /** Canonical bank name, the same key {@link BalanceData#storageKey} uses. */
    final String bank;
    /** ISO-style code of the currency the bracketed statements are denominated in, never null; the
     *  rial default for every row stored so far. */
    final String currency;
    /** Account number this residual belongs to, or null when the bank stated none. */
    final String account;
    /** Date of the earlier statement that starts the bracketed window. */
    final long fromDate;
    /** Date of the later statement that closes it, and where the residual is placed. */
    final long toDate;
    /** Signed rials that moved unaccounted for: negative when the balance fell, positive when it
     *  rose. Magnitude only — never a per-movement split, which we do not have. */
    final long amount;
    /** How many movements we did receive inside the window. Context for the explanation only; the
     *  number of <em>missing</em> movements is unknowable and is never stated. */
    final int movements;

    Residual(String bank, String account, long fromDate, long toDate, long amount, int movements) {
        this(bank, account, fromDate, toDate, amount, movements, BalanceData.IRR);
    }

    Residual(String bank, String account, long fromDate, long toDate, long amount, int movements,
            String currency) {
        this.bank = bank;
        this.currency = currency == null ? BalanceData.IRR : currency;
        this.account = account;
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.amount = amount;
        this.movements = movements;
    }

    /** The slot this residual belongs to, matching the balance and movement stores. */
    String key() {
        return BalanceData.storageKey(bank, account, currency);
    }

    /**
     * Every provable residual in the supplied movements, oldest first within each account slot.
     *
     * <p>Movements are grouped by the same composite key the balance screen uses, so two accounts of
     * one bank are never reconciled against each other. Within a group the walk runs in date order
     * and carries two things forward: the last movement that stated a balance (the open bracket) and
     * the sum of the movements received since it. Closing a bracket is pure subtraction, so an
     * account whose statements all agree reports nothing at all — this only speaks up when the bank
     * contradicts us.
     *
     * <p>Movements sharing a timestamp are first put in the order their stated balances prove, which
     * is usually enough: two statements stamped the same second are told apart by the one arithmetic
     * relation that cannot be coincidental, {@code balance(i) + amount(j) == balance(j)}. Where that
     * chain is exact the window is reconciled normally. Where it is not — the movements disagree
     * with each other, or both sit at the same balance — the timestamps bound no interval, and no
     * statement inside such a tie may anchor or close a window: it sits at a point in time the app
     * cannot place. The region stays silent instead of reporting a number we cannot stand behind.
     * Likewise, arithmetic that would overflow {@code long} yields no residual: a wrapped
     * subtraction would report a spectacularly wrong number as fact.
     *
     * <p>The input list is never modified.
     */
    static List<Residual> between(List<Transaction> txs) {
        List<Residual> out = new ArrayList<>();
        if (txs == null || txs.isEmpty()) return out;
        Map<String, List<Transaction>> bySlot = new LinkedHashMap<>();
        for (Transaction t : txs) {
            if (t == null) continue;
            // Brackets are arithmetic on stated balances, so an account's slots are kept apart by
            // the currency of those balances rather than by the currency of the movements: a card
            // spent in dollars still settles into one rial or dinar balance chain.
            bySlot.computeIfAbsent(BalanceData.storageKey(t.bank, t.account, t.balanceCurrency),
                    k -> new ArrayList<>()).add(t);
        }
        List<Residual> found = new ArrayList<>();
        for (List<Transaction> slot : bySlot.values()) {
            List<Transaction> sorted = new ArrayList<>(slot);
            sorted.sort((a, b) -> Long.compare(a.date, b.date));
            walk(sorted, disambiguate(sorted), found);
        }
        found.sort((a, b) -> {
            int byDate = Long.compare(a.toDate, b.toDate);
            return byDate != 0 ? byDate : a.key().compareTo(b.key());
        });
        out.addAll(found);
        return out;
    }

    /**
     * Puts every run of movements sharing a timestamp into the order their stated balances prove,
     * and marks the runs that stay unknowable.
     *
     * <p>A date sort alone leaves same-timestamp movements in whatever order they arrived, and
     * arrival order is the reverse of chronology for rows read back newest-first. Reconciling them
     * as they came would subtract one movement's amount from the *other* movement's balance and
     * report the money that provably moved as money that went missing. Where the balances chain
     * exactly — {@code balance(i) + amount(j) == balance(j)}, the relation no coincidence survives —
     * the real order is provable, so the run is reordered and reconciled like any other window.
     *
     * <p>{@code sorted} is rearranged in place; the caller owns that copy.
     *
     * @return flags where {@code flags[i]} is true when {@code sorted.get(i)} belongs to a run of
     *     same-timestamp movements whose order no balance chain settles. Such a row may neither
     *     anchor a window nor close one: it sits at a point in time the app cannot place, so letting
     *     it open a window would measure the next statement against an arbitrary member of the tie.
     */
    private static boolean[] disambiguate(List<Transaction> sorted) {
        int n = sorted.size();
        boolean[] flags = new boolean[n];
        int start = 0;
        while (start < n) {
            int end = start + 1;
            while (end < n && sorted.get(end).date == sorted.get(start).date) end++;
            if (end - start > 1) {
                List<Transaction> proven = provenOrder(sorted.subList(start, end));
                if (proven != null) {
                    for (int k = 0; k < proven.size(); k++) sorted.set(start + k, proven.get(k));
                } else {
                    for (int k = start; k < end; k++) flags[k] = true;
                }
            }
            start = end;
        }
        return flags;
    }

    /**
     * The chronological order of a run of same-timestamp movements when their stated balances admit
     * exactly one, or {@code null} when the run is genuinely ambiguous.
     *
     * <p>Delegates the ordering decision to {@link Reconcile#order} so the history view and the
     * unaccounted-money walk agree on what "provable order" means; that returns {@code null} for
     * equal-opposite pairs, branching, cycles and mixed currencies alike.
     */
    private static List<Transaction> provenOrder(List<Transaction> run) {
        int n = run.size();
        if (n < 2) return null;
        List<Reconcile.Entry> entries = new ArrayList<>(n);
        IdentityHashMap<Reconcile.Entry, Transaction> owners = new IdentityHashMap<>();
        for (Transaction t : run) {
            // Chaining needs a stated balance on both sides, so one bare row makes the run unknowable.
            if (t.balance == null) return null;
            Reconcile.Entry entry = new Reconcile.Entry(t.date, t.amount, t.balance, null,
                    t.currency, t.balanceCurrency);
            entries.add(entry);
            owners.put(entry, t);
        }
        List<Reconcile.Entry> ordered = Reconcile.order(entries);
        if (ordered == null) return null;
        List<Transaction> out = new ArrayList<>(n);
        for (Reconcile.Entry entry : ordered) out.add(owners.get(entry));
        return out;
    }

    /** The bracketing walk for one account slot, appending every residual it proves.
     *
     *  <p>All arithmetic is exact. A window whose running sum has once overflowed {@code long} stays
     *  unclaimed for the rest of the walk: a wrapped number is not a small error, it is a
     *  spectacularly wrong one, and reporting it as fact is the one outcome worse than staying
     *  silent. Real rial totals sit many orders of magnitude below the bound, so the guard costs
     *  nothing in practice.
     *
     *  @param unsettled per-position flags from {@link #disambiguate}: true where the movement sits in
     *  a tie no balance chain settles, so it is in no position to anchor or close a window. */
    private static void walk(List<Transaction> sorted, boolean[] unsettled, List<Residual> out) {
        Transaction open = null;    // the last movement that stated a balance
        long inside = 0;            // sum of movements received after it, up to the current one
        int count = 0;
        boolean exact = true;       // false once that sum has overflowed
        for (int i = 0; i < sorted.size(); i++) {
            Transaction t = sorted.get(i);
            if (unsettled[i]) {
                // Two or more messages stamped the same second, in an order no balance chain
                // settles: which movement happened first is unknowable, so this row sits at no
                // known point in time. It neither closes the window before it nor opens one, and
                // the next statement that states a balance opens a fresh window instead. Anchoring
                // on it would measure that statement against an arbitrary member of the tie and
                // report a gap the bank never described.
                open = null;
                inside = 0;
                count = 0;
                exact = true;
                continue;
            }
            if (open == null) {
                // Only a message that stated a balance can open a bracket. (The parser only records
                // a movement together with its balance, so in practice every row gets here; a row
                // that reached us without one simply rides inside someone else's window further
                // down, where its amount is still counted.)
                if (t.balance == null) continue;
                open = t;
                inside = 0;
                count = 0;
                exact = true;
                continue;
            }
            // Everything after the open statement, up to and including this one, happened inside
            // the window the two statements bracket. The closing statement's own movement counts
            // too: the balance it reports is the one read after that very movement, so leaving it
            // out would report every ordinary movement as a missing one.
            // A card can be spent in one currency and settled in another, and the difference
            // between the two figures is a rate the app has no way to know. Such a movement is
            // therefore neither countable inside this window nor able to close it: the bracket is
            // dropped and the message opens a fresh one from its own balance. Subtracting a foreign
            // amount from a rial balance would report a gap we invented, and keeping the bracket
            // would report the whole converted spend as money that went missing when we hold the
            // message that says it did not.
            if (!open.balanceCurrency.equals(t.currency)) {
                open = t.balance == null ? null : t;
                inside = 0;
                count = 0;
                exact = true;
                continue;
            }
            if (exact) {
                try {
                    inside = Math.addExact(inside, t.amount);
                } catch (ArithmeticException overflow) {
                    exact = false;
                }
            }
            count++;
            if (t.balance == null) continue;    // rides inside, never closes the bracket
            if (exact) {
                long gap;
                try {
                    gap = Math.subtractExact(Math.subtractExact(t.balance, open.balance), inside);
                } catch (ArithmeticException overflow) {
                    gap = 0;
                }
                if (gap != 0) {
                    out.add(new Residual(t.bank, t.account, open.date, t.date, gap, count,
                            t.balanceCurrency));
                }
            }
            open = t;
            inside = 0;
            count = 0;
            exact = true;
        }
    }
}
