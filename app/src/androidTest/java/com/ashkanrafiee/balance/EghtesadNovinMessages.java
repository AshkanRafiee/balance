package com.ashkanrafiee.balance;

/**
 * Eghtesad Novin movement messages, written for these tests in the shape the bank sends them:
 * the movement label naming the dash-separated account, the bare signed "ریال" amount, the
 * resulting balance, the year-less date, and the support phone line (which the app deliberately
 * reads nothing from).
 *
 * <p><b>Every figure here is made up.</b> The wording, the line order and the amount/balance/date
 * shapes are the ones a scan has to survive, but the account number, amounts, balance, date and
 * phone number are invented — nothing in this file came off a phone. The "ریال" words use the
 * Arabic yeh the bank spells them with, exactly the encoding split the matchers have to fold.
 *
 * <p>Shared rather than copied, for the same reason as {@link BluMessages}: the rule test and the
 * scan test have to see the same bytes the SMS provider hands back.
 */
final class EghtesadNovinMessages {
    private EghtesadNovinMessages() {}

    /** A sender the Eghtesad Novin messages are known to arrive from, as
     *  {@link BankRules#resolve} sees it. The reporter's real sender is still unconfirmed; the
     *  provider seed uses this spaceless alias because the seed command runs without a quoting
     *  shell. */
    static final String SENDER = "ENBank";

    /** Schematic dash-separated account number (3-3-7-1). Invented — shaped like the bank's,
     *  belonging to nobody — so the tests never carry a real account. */
    static final String ACCOUNT = "314-206-7091854-6";

    /** Schematic support phone number. Invented, so the tests never carry the bank's real one —
     *  and the app must read nothing from this line in any case. */
    static final String PHONE = "02176543";

    /** A withdrawal: the movement, the resulting balance and the date it belongs to. */
    static final String WITHDRAWAL =
        "برداشت: " + ACCOUNT + "\n"
        + "-5,270,000ريال\n"
        + "مانده:1,234,567,890ريال\n"
        + "5/8-10:04\n"
        + "مرکزآواي نوين:02176543";
}
