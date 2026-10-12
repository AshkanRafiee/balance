package com.ashkanrafiee.balance;

/**
 * Gardeshgari movement messages, written for these tests in the shape the bank sends them: the
 * brand line, the standalone "کارت" line (which the app deliberately does not read yet), the
 * "واریز به:" line naming the dotted account, the "مبلغ:" amount, the year-less two-digit date
 * and the resulting balance.
 *
 * <p><b>Every figure here is made up.</b> The wording, the line order and the amount/balance/date
 * shapes are the ones a scan has to survive, but the account number, amounts and balance are
 * invented — nothing in this file came off a phone. The labels use the Arabic yeh and kaf the
 * bank spells them with ("واريز", "موجودي", "ريال", "بانك"), exactly the encoding split the
 * matchers have to fold.
 *
 * <p>Shared rather than copied, for the same reason as {@link BluMessages}: the rule test and the
 * scan test have to see the same bytes the SMS provider hands back.
 */
final class GardeshgariMessages {
    private GardeshgariMessages() {}

    /** A sender the Gardeshgari messages are known to arrive from, as {@link BankRules#resolve}
     *  sees it. The reporter's real sender is still unconfirmed; the provider seed uses this
     *  spaceless alias because the seed command runs without a quoting shell. */
    static final String SENDER = "TourismBank";

    /** Schematic four-part dotted account number (3/4/7/1). Invented — shaped like the bank's,
     *  belonging to nobody — so the tests never carry a real account. */
    static final String ACCOUNT = "310.9876.5432109.4";

    /** A deposit: the movement, the resulting balance and the date it belongs to. */
    static final String DEPOSIT =
        "*بانك گردشگری*\n"
        + "کارت\n"
        + "واريز به: " + ACCOUNT + "\n"
        + "مبلغ: 2,350,000 ريال\n"
        + "05/05/09_21:36\n"
        + "موجودي: 123,456,789 ريال";
}
