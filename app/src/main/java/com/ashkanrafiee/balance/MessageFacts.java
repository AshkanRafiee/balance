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
    /** The stated balance in rials, or {@link #NO_BALANCE} when the message states no balance. */
    final long balance;
    /** The settled signed movement in rials (negative for a withdrawal), or null when the message
     *  does not describe one. */
    final Long movement;
    /** When the money moved, in epoch millis. */
    final long time;

    static final long NO_BALANCE = -1;

    private MessageFacts(String bank, String account, long balance, Long movement, long time) {
        this.bank = bank;
        this.account = account;
        this.balance = balance;
        this.movement = movement;
        this.time = time;
    }

    /** The arrival-independent reduction: stated balance, settled movement, and the account the
     *  message states, read with the caller's {@code bankHint} exactly as the legacy path did (the
     *  account rules are per-bank, and a caller may legitimately know the bank the sender resolves
     *  to nothing). Used by the window merger and {@code parseMovement}, which already hold the
     *  resolved event time. */
    static MessageFacts amounts(String sender, String body, String bankHint) {
        EngineRules engine = activeEngine();
        if (engine != null) {
            MessageFacts packed = engineFacts(engine, sender, body);
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
            MessageFacts packed = engineFacts(engine, sender, body);
            if (packed != null)
                return new MessageFacts(packed.bank, packed.account, packed.balance, packed.movement,
                    MessageDate.eventTime(body, arrival, BankRules.calendar(packed.bank)));
        }
        String bank = BankRules.resolve(sender);
        if (bank == null) return new MessageFacts(null, null, NO_BALANCE, null, 0);
        return new MessageFacts(bank, BankRules.extractAccount(bank, body),
            BalanceData.extract(body), BalanceData.extractTransaction(body),
            MessageDate.eventTime(body, arrival, BankRules.calendar(bank)));
    }

    private static EngineRules activeEngine() {
        EngineRules engine = EngineRules.get();
        return engine != null && engine.active() ? engine : null;
    }

    /** The engine reduction of one message, or null when the engine does not authoritatively cover
     *  it (inactive seam, uncovered sender, or any non-parsed status) and the caller must take the
     *  legacy path instead. */
    private static MessageFacts engineFacts(EngineRules engine, String sender, String body) {
        if (body == null) return null;
        Parser.Result result;
        try {
            result = engine.parse(NO_SOURCE, sender, body, Instant.EPOCH, ZoneId.systemDefault());
        } catch (RuntimeException engineFailure) {
            return null;
        }
        if (result == null || result.status() != Parser.Status.PARSED) return null;
        long balance = NO_BALANCE;
        Long movement = null;
        String account = null;
        String bank = null;
        for (Parser.Fact fact : result.facts()) {
            if (bank == null) bank = fact.bankId();
            if (fact.account() != null) account = fact.account();
            switch (fact.kind()) {
                case BOOKED_BALANCE:
                    if (fact.money().scale() == 0) balance = fact.money().minorUnits();
                    break;
                case POSTED_MOVEMENT:
                    if (fact.money().scale() == 0) movement = fact.money().minorUnits();
                    break;
                default:
                    break;
            }
        }
        if (bank == null) return null;
        return new MessageFacts(engine.bankNameOf(bank), account, balance, movement, 0);
    }

    /** The engine only ever sees this synthetic source id: the seam identifies a message by its
     *  sender and body, never by an inbox row id the two paths would not agree on. */
    private static final String NO_SOURCE = "seam";
}
