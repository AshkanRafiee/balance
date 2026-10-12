package com.ashkanrafiee.balance;

/**
 * Maskan movement messages, written for these tests in the shape the bank sends them: the event
 * label with a leading-sign amount on the first line, the "حساب:" account, the "مانده:" resulting
 * balance, and the year-less compact date.
 *
 * <p><b>Every figure here is made up.</b> The wording, the line order and the amount/balance/date
 * shapes are the ones a scan has to survive, but the account numbers, amounts, balances and dates
 * are invented — nothing in this file came off a phone. The event words use the Arabic yeh the
 * bank spells them with, and the amounts are wrapped in the direction marks the bank sends,
 * exactly the encoding split the matchers have to fold.
 *
 * <p>Shared rather than copied, for the same reason as {@link BluMessages}: the rule test and the
 * scan test have to see the same bytes the SMS provider hands back.
 */
final class MaskanMessages {
    private MaskanMessages() {}

    /** A sender the Maskan messages arrive from, as {@link BankRules#resolve} sees it. */
    static final String SENDER = "100025";

    /** The spaced brand alias, covered by the resolution assertions instead of the provider seed
     *  (the seed command below runs without a quoting shell, so a spaced sender would arrive
     *  truncated). */
    static final String BRAND_SENDER = "Bank Maskan";

    /** Schematic 12-digit account numbers. Invented — numbers shaped like the bank's, belonging
     *  to nobody — so the tests never carry a real account. */
    static final String ACCOUNT_A = "150000000001";
    static final String ACCOUNT_B = "930000000002";
    static final String ACCOUNT_C = "150000000003";

    /** The purchase event, in the bank's own words (normalized to "خرید"). */
    static final String PURCHASE_REASON = "خرید";

    /** The internet-transfer event, in the bank's own words (normalized to "انتقال اینترنت"). */
    static final String INTERNET_REASON = "انتقال اینترنت";

    /** The SMS-service fee event, in the bank's own words (normalized, year included). */
    static final String SMS_FEE_REASON = "برداشت بابت خدمات پیامکی سال 1404";

    /** A purchase withdrawal: the movement, the resulting balance and the date it belongs to. */
    static final String PURCHASE =
        "خريد:\u200E-1,234,567\u200E\n"
        + "حساب:" + ACCOUNT_A + "\n"
        + "مانده:12,345,678\n"
        + "0706-11:48";

    /** An internet-transfer deposit. The "انتقال" keyword is a withdrawal word, so the leading
     *  plus is what makes this a deposit. */
    static final String INTERNET_TRANSFER =
        "انتقال اينترنت:\u200E+50,000,000\u200E\n"
        + "حساب:" + ACCOUNT_B + "\n"
        + "مانده:57,894,000\n"
        + "0220-09:26";

    /** An SMS-service fee withdrawal naming its service year. The digits inside the label are
     *  part of the event, so the reason matcher must allow them. */
    static final String SMS_FEE =
        "برداشت بابت خدمات پيامکي سال 1404:\u200E-95,140\u200E\n"
        + "حساب:" + ACCOUNT_C + "\n"
        + "مانده:2,495,507\n"
        + "0611-11:54";
}
