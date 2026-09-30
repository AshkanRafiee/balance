package com.ashkanrafiee.balance;

import com.ashkanrafiee.balance.parser.Parser;

import java.time.Instant;
import java.time.ZoneId;

/** One bank message reduced to exactly what storage needs: the bank, the account, the stated
 *  balance, the settled movement, and the event time. The scan and history paths used to call
 *  {@code BankRules.resolve}, {@code extractAccount}, {@code extract}, {@code extractTransaction}
 *  and {@code MessageDate.eventTime} at four separate call sites; they now all reduce through this
 *  one facade, so the engine can take over a message as a whole rather than piecemeal.
 *
 *  <p>Engine-first, legacy-always: when the seam is active, the sender is covered by a bundled
 *  pack, and the engine reports {@code PARSED}, the engine's facts are authoritative for that
 *  message. Every other case — the seam off, an uncovered sender, or any non-parsed status — takes
 *  the untouched legacy path, message by message. A message can therefore never be lost or
 *  reinterpreted by a partial engine result, and a packed message that the engine reads exactly
 *  as the legacy reducers do is served by the engine.
 *
 *  <p>The engine never *removes* a movement the legacy path would have found: the balance-delta
 *  inference in {@code BalanceData.parseMovement} stays available to both paths. */
final class MessageFacts {

    /** The bank whose message this is, in the canonical name the app keys storage by; null when
     *  the sender belongs to no bank (and the message is not ours to store). */
    final String bank;
    /** The stated account number, or null when the message states none. */
    final String account;
    /** The stated balance in {@link #balanceCurrency} minor units, or {@link #NO_BALANCE} when the
     *  message states no balance. */
    final long balance;
    /** The settled signed movement in {@link #movementCurrency} minor units (negative for a
     *  withdrawal), or null when the message does not describe one. */
    final Long movement;
    /** When the money moved, in epoch millis. */
    final long time;
    /** When the pack that read this message says the money moved, in epoch millis, or null when
     *  the engine did not cover the message or stated no time of its own. Kept apart from
     *  {@link #time} because the two are different readings of the same question: for an Iranian
     *  bank the legacy date reader is the reading and the pack's is not trusted yet, and for a bank
     *  only a pack knows it is the other way round. */
    final Long packedTime;
    /** ISO-style code of the currency the stated balance is denominated in, never null; the rial
     *  default for the legacy path. */
    final String balanceCurrency;
    /** ISO-style code of the currency the movement is denominated in, never null; the rial default
     *  for the legacy path.
     *
     *  <p>Two codes, not one, because a bank can spend a card in one currency and settle that card
     *  in another: "123 USD was withdrawn, your available balance is 123.456". Those are two sums
     *  of money in two currencies, and the difference between them is a conversion rate the app has
     *  no way to know and must never invent, so neither may be expressed in the other's units. For
     *  every Iranian message the two codes are the same, which is why the storage key, the history
     *  grouping and every Iranian screen have always been able to speak of one currency. */
    final String movementCurrency;

    static final long NO_BALANCE = -1;

    private MessageFacts(String bank, String account, long balance, Long movement, long time) {
        this(bank, account, balance, movement, time, BalanceData.IRR, BalanceData.IRR, null);
    }

    private MessageFacts(String bank, String account, long balance, Long movement, long time,
            String balanceCurrency, String movementCurrency) {
        this(bank, account, balance, movement, time, balanceCurrency, movementCurrency, null);
    }

    private MessageFacts(String bank, String account, long balance, Long movement, long time,
            String balanceCurrency, String movementCurrency, Long packedTime) {
        this.bank = bank;
        this.account = account;
        this.balance = balance;
        this.movement = movement;
        this.time = time;
        this.balanceCurrency = balanceCurrency == null ? BalanceData.IRR : balanceCurrency;
        this.movementCurrency = movementCurrency == null ? BalanceData.IRR : movementCurrency;
        this.packedTime = packedTime;
    }

    /** The arrival-independent reduction: stated balance, settled movement, and the account the
     *  message states, read with the caller's {@code bankHint} exactly as the legacy path did (the
     *  account rules are per-bank, and a caller may legitimately know the bank the sender resolves
     *  to nothing). Used by the window merger and {@code parseMovement}, which already hold the
     *  resolved event time. */
    static MessageFacts amounts(String sender, String body, String bankHint) {
        EngineRules engine = activeEngine();
        if (engine != null) {
            // The engine is told the epoch here because this reduction is the arrival-independent
            // one: it answers what the message says, and nothing it says about a date can be right
            // without knowing when the message arrived. Every caller that needs the date asks
            // {@link #of} instead, which hands the engine the real arrival.
            MessageFacts packed = engineFacts(engine, sender, body, Instant.EPOCH);
            if (packed != null) return packed;
        }
        return new MessageFacts(null,
            bankHint == null || body == null ? null : BankRules.extractAccount(bankHint, body),
            BalanceData.extract(body), BalanceData.extractTransaction(body), 0);
    }

    /** The full reduction including the event time, where {@code arrival} (the inbox timestamp
     *  clamped to now) seeds any date the message does not state. The event time is always the
     *  legacy reading: the official packs reproduce the shared profile's money and account anchors
     *  (pinned equal corpus-wide), but they do not yet read every legacy date layout — a real bank
     *  statement whose date the packs cannot anchor falls back to its arrival while the legacy path
     *  reads the stated date — and a movement filed on the wrong day is exactly the failure the
     *  date-owned increments must earn, not the seam's first step. */
    static MessageFacts of(String sender, String body, long arrival) {
        EngineRules engine = activeEngine();
        if (engine != null) {
            MessageFacts packed = engineFacts(engine, sender, body, Instant.ofEpochMilli(arrival));
            if (packed != null)
                return new MessageFacts(packed.bank, packed.account, packed.balance, packed.movement,
                    eventTime(body, arrival, packed), packed.balanceCurrency, packed.movementCurrency,
                    packed.packedTime);
        }
        String bank = BankRules.resolve(sender);
        if (bank == null) return new MessageFacts(null, null, NO_BALANCE, null, 0);
        return new MessageFacts(bank, BankRules.extractAccount(bank, body),
            BalanceData.extract(body), BalanceData.extractTransaction(body),
            MessageDate.eventTime(body, arrival, BankRules.calendar(bank)));
    }

    /** When the money moved.
     *
     *  <p>For an Iranian bank this is the legacy reading, which the engine deliberately does not
     *  replace: the official packs reproduce the legacy money and account anchors but do not yet
     *  read every legacy date layout, and a movement filed on the wrong day is exactly what the
     *  date-owned increments have to earn before the seam takes dates over.
     *
     *  <p>For a bank the legacy table has never heard of, there is no legacy reading to be had --
     *  {@code BankRules.calendar} answers every unknown bank with the Persian calendar, so asking
     *  it about an Italian card statement would read a date out of a wording that has none. The
     *  pack that read the message already resolved the date in the sender's own calendar and zone,
     *  so that is the reading; and where the message states no date, the arrival is the honest
     *  answer rather than a guess at midnight. */
    private static long eventTime(String body, long arrival, MessageFacts packed) {
        if (BankRules.supportedNames().contains(packed.bank))
            return MessageDate.eventTime(body, arrival, BankRules.calendar(packed.bank));
        if (packed.packedTime != null) return packed.packedTime;
        return arrival;
    }

    private static EngineRules activeEngine() {
        EngineRules engine = EngineRules.get();
        return engine != null && engine.active() ? engine : null;
    }

    /** The engine reduction of one message, or null when the engine does not authoritatively cover
     *  it (inactive seam, uncovered sender, or any non-parsed status) and the caller must take the
     *  legacy path instead.
     *
     *  <p>{@code arrival} is the message's real delivery time wherever the caller has it, and that
     *  matters more than it looks: a statement that dates itself with a day and a month and no year
     *  can only be placed on the calendar by reference to when it arrived, and asked instead about
     *  the epoch the engine would resolve it to 1970, find it decades past its own plausibility
     *  window, and report that it knows no date -- silently costing the message the one thing it
     *  did state. */
    private static MessageFacts engineFacts(EngineRules engine, String sender, String body,
            Instant arrival) {
        if (body == null) return null;
        Parser.Result result;
        try {
            result = engine.parse(NO_SOURCE, sender, body, arrival, ZoneId.systemDefault());
        } catch (RuntimeException engineFailure) {
            return null;
        }
        if (result == null || result.status() != Parser.Status.PARSED) return null;
        long balance = NO_BALANCE;
        Long movement = null;
        String account = null;
        String bank = null;
        String balanceCurrency = BalanceData.IRR;
        String movementCurrency = BalanceData.IRR;
        Long packedTime = null;
        for (Parser.Fact fact : result.facts()) {
            if (bank == null) bank = fact.bankId();
            if (fact.account() != null) account = fact.account();
            // The pack's own reading of when the money moved, where it stated one rather than
            // falling back to the message's arrival: a fallback would be the arrival again, said
            // twice, and the distinction is what tells a stated date from an assumed one.
            if (fact.time() != null && fact.time().instant() != null && !fact.time().fallback()
                    && (packedTime == null || packedTime < fact.time().instant().toEpochMilli()))
                packedTime = fact.time().instant().toEpochMilli();
            switch (fact.kind()) {
                case BOOKED_BALANCE:
                case AVAILABLE_BALANCE:
                    // Both kinds state a balance, and the app keeps one balance per account: which
                    // of the two a bank means is its wording, not a difference in what the number
                    // is. No Iranian pack declares the available kind, so nothing about the
                    // messages parsed today takes a different path here.
                    balance = fact.money().minorUnits();
                    balanceCurrency = fact.money().currency().name();
                    break;
                case POSTED_MOVEMENT:
                    movement = fact.money().minorUnits();
                    movementCurrency = fact.money().currency().name();
                    break;
                default:
                    break;
            }
        }
        if (bank == null) return null;
        // A pack may own a stored row for any bank it names: the name comes out of the pack, and
        // the dashboard, the history and the widget all draw a bank by the name they are given
        // rather than by looking it up in the Iranian table. Requiring membership of that table
        // would mean a pack could only ever cover banks that shipped before it, which is the one
        // thing a community pack exists to change. What is still refused is a bank the loaded
        // catalog does not name at all, since a row under a nameless key could never be shown.
        String name = engine.bankNameOf(bank);
        if (name == null || name.isEmpty()) return null;
        // A bank that reports only what it did states no balance, so its row is denominated in
        // the currency it spends in: there is no other currency in the row for the balance to be
        // in, and a rial there would file a euro movement under a rial account.
        if (balance == NO_BALANCE) balanceCurrency = movementCurrency;
        return new MessageFacts(name, account, balance, movement, 0, balanceCurrency, movementCurrency,
            packedTime);
    }

    /** The engine only ever sees this synthetic source id: the seam identifies a message by its
     *  sender and body, never by an inbox row id the two paths would not agree on. */
    private static final String NO_SOURCE = "seam";
}
