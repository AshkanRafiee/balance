package com.ashkanrafiee.balance;

/**
 * Real Blu SMS as the phone actually delivers it, kept as shared fixtures: the brand on the first
 * line, the event title on the second, then the sentence carrying the amount, the balance, the time
 * and the date. Line 3 and line 4 arrive indented by a space in some messages and flush in others.
 *
 * <p>Kept verbatim so a scan can be driven with exactly the bytes the SMS provider hands back, and so
 * a reader can check a rule against the message it was written for.
 */
final class BluMessages {

    private BluMessages() {}

    /** The number Blu sends from, as {@link BankRules#resolve} sees it. */
    static final String SENDER = "+989999987641";

    /** A phone top-up: the event the app can caption on top of the movement. */
    static final String TOPUP =
        "بلو\n"
        + "شارژ شدی\n"
        + " اشکان عزیز، 220,000 ریال بابت خرید شارژ از حساب شما پرید.\n"
        + "موجودی: 32,307,247 ریال\n"
        + "۹:۴۱\n"
        + "۱۴۰۵.۰۷.۰۲";

    /** A phone-bill payment. */
    static final String BILL =
        "بلو\n"
        + "پرداخت قبض\n"
        + "اشکان عزیز، 540,000 ریال بابت پرداخت قبض تلفن همراه از حساب شما پرید.\n"
        + "موجودی: 32,742,247 ریال\n"
        + "۹:۴۰\n"
        + "۱۴۰۵.۰۷.۰۲";

    /** A purchase refund. */
    static final String REFUND =
        "بلو\n"
        + "برگشت پول\n"
        + "اشکان عزیز ،228,000 ریال معادل 3.0 درصد از خرید کلیک برگر  به حساب شما برگشت.\n"
        + "موجودی: 36,282,247 ریال\n"
        + "۱۴۰۵.۰۷.۰۱\n"
        + "۱۶:۰۹";

    /** An incoming instant transfer. */
    static final String TRANSFER_IN =
        "بلو\n"
        + "دریافت پل\n"
        + " اشکان عزیز، 50,000,000 ریال به حساب شما نشست.\n"
        + " موجودی: 57,599,247 ریال\n"
        + "۱۱:۵۹\n"
        + "۱۴۰۵.۰۷.۰۱";

    /** An outgoing instant transfer. */
    static final String TRANSFER_OUT =
        "بلو\n"
        + "انتقال پل\n"
        + " اشکان عزیز، 1,000,000 ریال از حساب شما پرید.\n"
        + " موجودی: 7,599,247 ریال\n"
        + "۱۷:۴۳\n"
        + "۱۴۰۵.۰۶.۳۱";

    /** A plain withdrawal: the title only restates the direction, so it states no reason. */
    static final String WITHDRAWAL =
        "بلو\n"
        + "برداشت پول\n"
        + " اشکان عزیز، 400,000 ریال از حساب شما برداشت.\n"
        + "موجودی: 57,086,241 ریال\n"
        + "۲۳:۲۸\n"
        + "۱۴۰۵.۰۶.۱۵";

    /** A loan promotion: a titled message that states no movement at all. */
    static final String PROMO =
        "بلو\n"
        + "برای وام گرفتن وقت تنگه\n"
        + "اشکان عزیز، فقط تا پایان ۳۰ شهریور برای دریافت  «وام به‌جا بدون ضامن» "
        + "فعال‌شده فرصت دارید.\n"
        + "برای دریافت وام به بخش وام در اپلیکیشن بلو مراجعه کنید.";

    /** A dynamic-OTP message: titled, but it states a code and not a movement. */
    static final String OTP =
        "بلو\n"
        + "بفرمایید رمز پویا\n"
        + "خرید\n"
        + "دیجی پی\n"
        + "مبلغ: 5,477,100 ریال\n"
        + "رمز: 574295 \n13:46";
}
