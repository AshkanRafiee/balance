package com.ashkanrafiee.balance;

/**
 * Sepah profit-deposit messages, written for these tests in the shape the bank sends them: the
 * brand line, the profit-credit line naming the account, the "مبلغ:" amount, the "زمان:" date and
 * time, and the "مانده:" resulting balance.
 *
 * <p><b>Every figure here is made up.</b> The wording, the line order and the amount/balance/date
 * shapes are the ones a scan has to survive, but the account number, amounts, balances and dates
 * are invented — nothing in this file came off a phone. The event words use the Arabic yeh the
 * bank spells them with, exactly the encoding split the matchers have to fold.
 *
 * <p>Shared rather than copied, for the same reason as {@link BluMessages}: the rule test and the
 * scan test have to see the same bytes the SMS provider hands back.
 */
final class SepahMessages {
    private SepahMessages() {}

    /** The sender the profit messages arrive from, as {@link BankRules#resolve} sees it. */
    static final String SENDER = "SEPAH BANK";

    /** A schematic account number. Invented — a number shaped like the bank's, belonging to
     *  nobody — so the tests never carry a real account. */
    static final String ACCOUNT = "01000000000001";

    /** The event the profit line states, in the bank's own words. */
    static final String PROFIT =
        "واریز سود";

    /** The brand line every profit message opens with. */
    private static final String BRAND = "بانک سپه\n";

    /** The profit-credit line, with the event spelled the way the bank spells it. */
    private static final String PROFIT_LINE =
        "واريز سود به: " + ACCOUNT + "\n";

    /** A profit deposit: the movement, the resulting balance and the date it belongs to. */
    static final String PROFIT_A =
        BRAND + PROFIT_LINE
        + "مبلغ: 87,450ريال\n"
        + "زمان: 1405/6/11-20:16\n"
        + "مانده: 9,876,543ريال";

    /** A schematic account number for the second layout. Invented, like {@link #ACCOUNT}. */
    static final String ACCOUNT_B = "02000000000002";

    /** A later profit deposit on the same account, chaining straight off the first one's balance
     *  (9,876,543 + 92,310 = 9,968,853). */
    static final String PROFIT_B =
        BRAND + PROFIT_LINE
        + "مبلغ: 92,310ريال\n"
        + "زمان: 1405/7/12-2:33\n"
        + "مانده: 9,968,853ريال";

    /** A plain deposit through an incoming order: the "واریز:" amount, the credited account behind
     *  "حساب:", the resulting balance, a year-less date and the order line with its tracking code.
     *  It states no profit event, so it carries no reason — and the "کد" inside "کدپیگیری" and the
     *  "پرداخت" inside "دستورپرداخت" must never read as an OTP prompt or a withdrawal. */
    static final String DEPOSIT =
        BRAND
        + "واريز:250,000ريال\n"
        + "حساب:" + ACCOUNT_B + "\n"
        + "مانده:3,125,000\n"
        + "7/15-10:48\n"
        + "دستورپرداخت وارده پل کدپيگيري 1405071510484217";
}
