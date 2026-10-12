package com.ashkanrafiee.balance;

/**
 * Shahr movement messages, written for these tests in the shape the bank sends them: the brand
 * line, the card-purchase event line, the "برداشت از:" debited account, the "مبلغ:" amount, the
 * resulting balance, and the full-year date with seconds.
 *
 * <p><b>Every figure here is made up.</b> The wording, the line order and the amount/balance/date
 * shapes are the ones a scan has to survive, but the account number, amounts, balance and date
 * are invented — nothing in this file came off a phone. The event words use the Arabic yeh the
 * bank spells them with, exactly the encoding split the matchers have to fold.
 *
 * <p>Shared rather than copied, for the same reason as {@link BluMessages}: the rule test and the
 * scan test have to see the same bytes the SMS provider hands back.
 */
final class ShahrMessages {
    private ShahrMessages() {}

    /** A sender the Shahr messages are known to arrive from, as {@link BankRules#resolve} sees
     *  it. The reporter's real sender is still unconfirmed; the provider seed uses this short
     *  code because the seed command runs without a quoting shell. */
    static final String SENDER = "20005";

    /** Schematic 13-digit account number. Invented — shaped like the bank's, belonging to
     *  nobody — so the tests never carry a real account. */
    static final String ACCOUNT = "5029001234567";

    /** The card-purchase event, in the bank's own words (normalized to "خرید با کارت"). */
    static final String CARD_PURCHASE_REASON = "خرید با کارت";

    /** A card-purchase withdrawal: the movement, the resulting balance and the date it belongs
     *  to. */
    static final String PURCHASE =
        "بانک شهر\n"
        + "خريد با کارت\n"
        + "برداشت از:" + ACCOUNT + "\n"
        + "مبلغ:254,800ريال\n"
        + "موجودي:12,345,678 ريال\n"
        + "1403/11/8 02:14:33";
}
