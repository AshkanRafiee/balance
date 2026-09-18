package com.ashkanrafiee.balance;

import java.util.ArrayList;
import java.util.List;

/**
 * Reconciles a batch of bank movement entries into the correct chronological order using exact
 * balance chaining: two consecutive entries satisfy {@code balance[i] + amount[j] == balance[j]}.
 *
 * <p>When a unique total order exists, the entries are returned in chronological (oldest-first)
 * order. Any ambiguity (branching, cycles, disconnected components, equal-opposite pairs) results
 * in a {@code null} return so the caller falls back to the original arrival order.
 */
final class Reconcile {

    static final class Entry {
        final long date;
        final long amount;
        final long balance;
        final String sig;

        Entry(long date, long amount, long balance, String sig) {
            this.date = date;
            this.amount = amount;
            this.balance = balance;
            this.sig = sig;
        }
    }

    private Reconcile() {}

    /** Returns the entries in correct chronological order, or {@code null} when the chain is
     *  ambiguous. The returned list contains the same entry objects as the input. */
    static List<Entry> order(List<Entry> in) {
        int n = in.size();
        if (n < 2) return null;

        // Edge i -> j iff balance[i] + amount[j] == balance[j]
        int[] outDegree = new int[n];
        int[] inDegree = new int[n];
        int[][] succ = new int[n][];
        int[] succCount = new int[n];
        for (int i = 0; i < n; i++) {
            succ[i] = new int[n];
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                Entry ei = in.get(i);
                Entry ej = in.get(j);
                long sum;
                try {
                    sum = Math.addExact(ei.balance, ej.amount);
                } catch (ArithmeticException overflow) {
                    continue;
                }
                if (sum == ej.balance) {
                    succ[i][succCount[i]++] = j;
                    outDegree[i]++;
                    inDegree[j]++;
                }
            }
        }

        // Unique total order requires: every node has out-degree <= 1, in-degree <= 1,
        // exactly one node with in-degree 0 (the head), exactly one with out-degree 0 (the tail),
        // no cycles, and the chain covers all nodes.
        int inZeroCount = 0;
        int outZeroCount = 0;
        for (int i = 0; i < n; i++) {
            if (outDegree[i] > 1 || inDegree[i] > 1) return null;
            if (inDegree[i] == 0) inZeroCount++;
            if (outDegree[i] == 0) outZeroCount++;
        }
        if (inZeroCount != 1 || outZeroCount != 1) return null;

        // Walk from the head (in-degree 0).
        int head = -1;
        for (int i = 0; i < n; i++) {
            if (inDegree[i] == 0) { head = i; break; }
        }
        List<Entry> result = new ArrayList<>(n);
        // Every node has in-degree <= 1 and the head has in-degree 0 (both checked above), so the
        // walk from the head can never revisit a node: a cycle would need an in-degree-2 node or a
        // missing head, and each is already rejected. The walk is therefore at most n steps.
        int cur = head;
        while (cur != -1) {
            result.add(in.get(cur));
            int next = -1;
            for (int k = 0; k < succCount[cur]; k++) {
                next = succ[cur][k];
                break;
            }
            cur = next;
        }
        return result.size() == n ? result : null;
    }
}
