package com.ashkanrafiee.balance;

import android.content.Context;
import java.util.Objects;

/** Read-only evidence lookup. Never guesses a physical source from amount/time similarity. */
final class FigureEvidence {
    private static final java.util.regex.Pattern CODE_MARKER = java.util.regex.Pattern.compile(
        "(?:رمز|پویا|کد|otp|passcode|password|one[- ]?time|"
            + "(?<![A-Za-z])code(?![A-Za-z]))", java.util.regex.Pattern.CASE_INSENSITIVE);
    private FigureEvidence() { }

    static final class SourceMatch {
        private SourceStore.Source source;
        boolean ambiguous;

        void add(SourceStore.Source candidate) {
            if (source == null) source = candidate;
            else if (source.id != candidate.id) ambiguous = true;
        }

        SourceStore.Source unique() { return ambiguous ? null : source; }
    }

    static SourceMatch transaction(Context context, Transaction transaction) throws Exception {
        SourceMatch match = new SourceMatch();
        SourceStore.forEachSourceWithObservations(context, 128, (source, transactions, balances) -> {
            for (SourceStore.TransactionObservation observed : transactions) {
                if (sameTransaction(transaction, observed.transaction)) match.add(source);
            }
        });
        return match;
    }

    static boolean sameTransaction(Transaction a, Transaction b) {
        // A legacy row without either source identity has no physical link we can safely claim.
        return (a.sig != null || a.content != null) && Objects.equals(a.bank, b.bank)
            && Objects.equals(a.account, b.account) && a.date == b.date && a.amount == b.amount
            && Objects.equals(a.balance, b.balance) && Objects.equals(a.sig, b.sig)
            && Objects.equals(a.content, b.content);
    }

    static SourceMatch balance(Context context, Bank bank) throws Exception {
        SourceMatch match = new SourceMatch();
        SourceStore.forEachSourceWithObservations(context, 128, (source, transactions, balances) -> {
            for (SourceStore.BalanceObservation observed : balances) {
                if (Objects.equals(bank.name, observed.bank) && Objects.equals(bank.account, observed.account)
                        && bank.date == observed.date && bank.amount == observed.balance) match.add(source);
            }
        });
        return match;
    }

    static String safeBody(SourceStore.Source source) {
        // Source details are not a diagnostics report: preserving an authorization layout is not
        // worth exposing an unusual/alphanumeric code or a code on a preceding line. Hide the
        // whole message whenever it is code-bearing, then use the shared sanitizer for other text.
        if (source.rawBody != null && CODE_MARKER.matcher(TextNormalizer.normalize(source.rawBody)).find())
            return "[redacted]";
        return ScanDiagnostics.sanitizeForReport(source.rawBody);
    }

    /** Reconstructs only the exact bracket, from the complete store rather than filtered UI rows.
     * A changed/ambiguous bracket returns null, never a plausible-looking substitute calculation. */
    static Bracket residual(Context context, Residual residual) throws Exception {
        BracketReader reader = new BracketReader(residual);
        TransactionStore.forEach(context, 256, reader::add);
        return reader.finish();
    }

    static final class Bracket {
        final long before, after, received;
        Bracket(long before, long after, long received) {
            this.before = before;
            this.after = after;
            this.received = received;
        }
    }

    static final class BracketReader {
        private final Residual residual;
        private long before, after, received;
        private int opens, closes, movements;
        private boolean invalid;

        BracketReader(Residual residual) { this.residual = residual; }

        void add(Transaction t) {
            if (!Objects.equals(t.bank, residual.bank) || !Objects.equals(t.account, residual.account)
                    || t.date < residual.fromDate || t.date > residual.toDate) return;
            if (t.date == residual.fromDate) {
                if (t.balance != null) { before = t.balance; opens++; }
                return;
            }
            if (t.balance != null) {
                if (t.date == residual.toDate) { after = t.balance; closes++; }
                else invalid = true; // Another statement would have closed this bracket earlier.
            }
            try { received = Math.addExact(received, t.amount); }
            catch (ArithmeticException overflow) { invalid = true; }
            movements++;
        }

        Bracket finish() {
            if (invalid || opens != 1 || closes != 1 || movements != residual.movements
                    || residual.fromDate >= residual.toDate) return null;
            try {
                long gap = Math.subtractExact(Math.subtractExact(after, before), received);
                return gap == residual.amount ? new Bracket(before, after, received) : null;
            } catch (ArithmeticException overflow) { return null; }
        }
    }
}
