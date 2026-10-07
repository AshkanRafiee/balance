package com.ashkanrafiee.balance;

/** Synthetic Refah fixtures. Account numbers, amounts, balances, and dates are invented. */
final class RefahMessages {
    static final String SENDER = "Refah Bank";
    static final String ACCOUNT = "123456789";

    static final String PURCHASE =
        "بانک رفاه\n"
        + "حساب" + ACCOUNT + "\n"
        + "خرید3,210,000-\n"
        + "مانده147,654,321\n"
        + "07/11-11:33";

    static final String CARD =
        "بانک رفاه\n"
        + "حساب" + ACCOUNT + "\n"
        + "کارت12,345,600-\n"
        + "مانده987,654,321\n"
        + "07/12-17:37";

    private RefahMessages() {}
}
