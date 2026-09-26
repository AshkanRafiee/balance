package com.ashkanrafiee.balance;

/**
 * Blu SMS in the shape the phone actually delivers it, kept as shared fixtures: the brand on the
 * first line, the event title on the second, then the sentence carrying the amount, the balance, the
 * time and the date. Line 3 and line 4 arrive indented by a space in some messages and flush in
 * others.
 *
 * <p><b>The figures here are made up.</b> The wording, the line order, the punctuation, the Persian
 * digits, the spacing and the amount/balance shapes are the ones a scan has to survive, but every
 * name, account figure, balance and code is invented — a fixture copied out of a real inbox would
 * put the owner's finances and name in a public repository, and a test needs none of that to be
 * convincing. Anyone extending these fixtures: keep them synthetic.
 */
final class BluMessages {

    private BluMessages() {}

    /** The number Blu sends from, as {@link BankRules#resolve} sees it. */
    static final String SENDER = "+989999987641";

    /** A phone top-up: the event the app can caption on top of the movement. */
    static final String TOPUP =
        "بلو\n"
        + "شارژ شدی\n"
        + " علی عزیز، 111,000 ریال بابت خرید شارژ از حساب شما پرید.\n"
        + "موجودی: 12,345,678 ریال\n"
        + "۹:۴۱\n"
        + "۱۴۰۵.۰۷.۰۲";

    /** A phone-bill payment. */
    static final String BILL =
        "بلو\n"
        + "پرداخت قبض\n"
        + "علی عزیز، 222,000 ریال بابت پرداخت قبض تلفن همراه از حساب شما پرید.\n"
        + "موجودی: 12,345,679 ریال\n"
        + "۹:۴۰\n"
        + "۱۴۰۵.۰۷.۰۲";

    /** A purchase refund. */
    static final String REFUND =
        "بلو\n"
        + "برگشت پول\n"
        + "علی عزیز ،33,000 ریال معادل 3.0 درصد از خرید فروشگاه نمونه  به حساب شما برگشت.\n"
        + "موجودی: 12,345,680 ریال\n"
        + "۱۴۰۵.۰۷.۰۱\n"
        + "۱۶:۰۹";

    /** An incoming instant transfer. */
    static final String TRANSFER_IN =
        "بلو\n"
        + "دریافت پل\n"
        + " علی عزیز، 4,444,000 ریال به حساب شما نشست.\n"
        + " موجودی: 12,345,681 ریال\n"
        + "۱۱:۵۹\n"
        + "۱۴۰۵.۰۷.۰۱";

    /** An outgoing instant transfer. */
    static final String TRANSFER_OUT =
        "بلو\n"
        + "انتقال پل\n"
        + " علی عزیز، 5,555,000 ریال از حساب شما پرید.\n"
        + " موجودی: 12,345,682 ریال\n"
        + "۱۷:۴۳\n"
        + "۱۴۰۵.۰۶.۳۱";

    /** A plain withdrawal: the title only restates the direction, so it states no reason. */
    static final String WITHDRAWAL =
        "بلو\n"
        + "برداشت پول\n"
        + " علی عزیز، 6,666,000 ریال از حساب شما برداشت.\n"
        + "موجودی: 12,345,683 ریال\n"
        + "۲۳:۲۸\n"
        + "۱۴۰۵.۰۶.۱۵";

    /** A loan promotion: a titled message that states no movement at all. */
    static final String PROMO =
        "بلو\n"
        + "برای وام گرفتن وقت تنگه\n"
        + "علی عزیز، فقط تا پایان ۳۰ شهریور برای دریافت  «وام به‌جا بدون ضامن» "
        + "فعال‌شده فرصت دارید.\n"
        + "برای دریافت وام به بخش وام در اپلیکیشن بلو مراجعه کنید.";

    /** A dynamic-OTP message: titled, but it states a code and not a movement. */
    static final String OTP =
        "بلو\n"
        + "بفرمایید رمز پویا\n"
        + "خرید\n"
        + "فروشگاه نمونه\n"
        + "مبلغ: 7,777,000 ریال\n"
        + "رمز: 123456 \n13:46";
}
