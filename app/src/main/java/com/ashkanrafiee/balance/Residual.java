package com.ashkanrafiee.balance;

import java.util.ArrayList;
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
        this.bank = bank;
        this.account = account;
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.amount = amount;
        this.movements = movements;
    }

    /** The slot this residual belongs to, matching the balance and movement stores. */
    String key() {
        return BalanceData.storageKey(bank, account);
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
     * <p>Two movements sharing a timestamp bound no interval: we cannot tell which came first, and a
     * residual claimed across an unknowable ordering would be a fabrication. Such a pair is dropped
     * rather than guessed at, and the next message stating a balance opens a fresh bracket, so the
     * region stays silent instead of reporting a number we cannot stand behind. Likewise, arithmetic
     * that would overflow {@code long} yields no residual: a wrapped subtraction would report a
     * spectacularly wrong number as fact.
     *
     * <p>The input list is never modified.
     */
    static List<Residual> between(List<Transaction> txs) {
        List<Residual> out = new ArrayList<>();
        if (txs == null || txs.isEmpty()) return out;
        Map<String, List<Transaction>> bySlot = new LinkedHashMap<>();
        for (Transaction t : txs) {
            if (t == null) continue;
            bySlot.computeIfAbsent(BalanceData.storageKey(t.bank, t.account), k -> new ArrayList<>())
                .add(t);
        }
        List<Residual> found = new ArrayList<>();
        for (List<Transaction> slot : bySlot.values()) {
            List<Transaction> sorted = new ArrayList<>(slot);
            sorted.sort((a, b) -> Long.compare(a.date, b.date));
            walk(sorted, found);
        }
        found.sort((a, b) -> {
            int byDate = Long.compare(a.toDate, b.toDate);
            return byDate != 0 ? byDate : a.key().compareTo(b.key());
        });
        out.addAll(found);
        return out;
    }

    /** The bracketing walk for one account slot, appending every residual it proves.
     *
     *  <p>All arithmetic is exact. A window whose running sum has once overflowed {@code long} stays
     *  unclaimed for the rest of the walk: a wrapped number is not a small error, it is a
     *  spectacularly wrong one, and reporting it as fact is the one outcome worse than staying
     *  silent. Real rial totals sit many orders of magnitude below the bound, so the guard costs
     *  nothing in practice. */
    private static void walk(List<Transaction> sorted, List<Residual> out) {
        Transaction open = null;    // the last movement that stated a balance
        long inside = 0;            // sum of movements received after it, up to the current one
        int count = 0;
        boolean exact = true;       // false once that sum has overflowed
        for (Transaction t : sorted) {
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
            if (t.date == open.date) {
                // Two messages stamped the same second bound no interval: which movement happened
                // first is unknowable, and a gap claimed across an unknowable order would be a
                // fabrication. The bracket is dropped rather than guessed at, and the next message
                // that states a balance opens a fresh one — so the region stays silent instead of
                // reporting a number we cannot stand behind.
                open = null;
                inside = 0;
                count = 0;
                exact = true;
                continue;
            }
            // Everything after the open statement, up to and including this one, happened inside
            // the window the two statements bracket. The closing statement's own movement counts
            // too: the balance it reports is the one read after that very movement, so leaving it
            // out would report every ordinary movement as a missing one.
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
                    out.add(new Residual(t.bank, t.account, open.date, t.date, gap, count));
                }
            }
            open = t;
            inside = 0;
            count = 0;
            exact = true;
        }
    }
}
