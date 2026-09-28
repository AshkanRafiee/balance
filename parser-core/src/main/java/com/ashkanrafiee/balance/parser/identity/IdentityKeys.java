package com.ashkanrafiee.balance.parser.identity;

import java.util.Objects;

/** Identity tuples only: no inferred account aliases, financial facts or note migration. */
public final class IdentityKeys {
    private IdentityKeys() {}

    /** Resolved opaque account entity within a stable bank, never an account number or suffix. */
    public static final class Account {
        private final String bankId;
        private final String entityId;

        public Account(String bankId, String entityId) {
            this.bankId = Names.require(bankId);
            this.entityId = Names.require(entityId);
        }

        public String bankId() { return bankId; }
        public String entityId() { return entityId; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Account)) return false;
            Account that = (Account) other;
            return bankId.equals(that.bankId) && entityId.equals(that.entityId);
        }
        @Override public int hashCode() { return Objects.hash(bankId, entityId); }
        @Override public String toString() { return "Account[redacted]"; }
    }

    /** Currency must already be canonical uppercase; no trimming, case folding or FX conflation. */
    public static final class Ledger {
        private final Account account;
        private final String currency;
        private final String product;

        public Ledger(Account account, String currency, String product) {
            this.account = Objects.requireNonNull(account, "account");
            Objects.requireNonNull(currency, "currency");
            if (currency.length() != 3) throw new IllegalArgumentException("Invalid currency code");
            for (int i = 0; i < currency.length(); i++) {
                if (currency.charAt(i) < 'A' || currency.charAt(i) > 'Z') {
                    throw new IllegalArgumentException("Currency must be canonical");
                }
            }
            this.currency = currency;
            this.product = Names.require(product);
        }

        public Account account() { return account; }
        public String currency() { return currency; }
        public String product() { return product; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Ledger)) return false;
            Ledger that = (Ledger) other;
            return account.equals(that.account) && currency.equals(that.currency) && product.equals(that.product);
        }
        @Override public int hashCode() { return Objects.hash(account, currency, product); }
        @Override public String toString() { return "Ledger[redacted]"; }
    }

    /** Static semantic slots only; repeated-block lineage needs a future explicit mapping API. */
    public static final class Output {
        private final String occurrenceId;
        private final String slot;

        public Output(String occurrenceId, String slot) {
            this.occurrenceId = Names.require(occurrenceId);
            this.slot = Names.require(slot);
        }

        public String occurrenceId() { return occurrenceId; }
        public String slot() { return slot; }
        /** Colon is forbidden in components, making the versioned encoding unambiguous. */
        public String id() { return "output-v1:" + occurrenceId + ":" + slot; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Output)) return false;
            Output that = (Output) other;
            return occurrenceId.equals(that.occurrenceId) && slot.equals(that.slot);
        }
        @Override public int hashCode() { return Objects.hash(occurrenceId, slot); }
        @Override public String toString() { return "Output[redacted]"; }
    }
}
