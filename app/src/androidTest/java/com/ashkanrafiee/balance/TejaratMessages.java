package com.ashkanrafiee.balance;

/**
 * Tejarat movement messages, written for these tests in the shape the bank sends them: the brand
 * line, the account, the amount, the channel the money went through, the resulting balance, and the
 * date and time. Every figure and account number here is invented — nothing in this file came off a
 * phone, and the names and balances in it belong to nobody.
 *
 * <p>Shared rather than copied, for the same reason as {@link BluMessages}: the rule test and the scan
 * test have to see the same bytes the SMS provider hands back, down to the trailing spaces the sender
 * pads its lines with and the Arabic yeh it spells the channel label with.
 */
final class TejaratMessages {
    private TejaratMessages() {}

    /** The header every movement message opens with. */
    private static final String BRAND = "*\u0628\u0627\u0646\u06A9 \u062A\u062C\u0627\u0631\u062A* \n";

    /** The account line. A number no account has. */
    private static final String ACCOUNT = "\u062D\u0633\u0627\u0628: 01350000000 \n";

    /** A withdrawal over Shetab, the instant-payment network. */
    static final String WITHDRAWAL_SHETAB =
        BRAND + ACCOUNT
        + "\u0628\u0631\u062F\u0627\u0634\u062A: 5,000,000 \u0631\u06CC\u0627\u0644 \n"
        + "\u0627\u0632 \u0637\u0631\u064A\u0642: \u0634\u062A\u0627\u0628  \n"
        + "\u0645\u0627\u0646\u062F\u0647: 48,000,000 \u0631\u06CC\u0627\u0644 \n"
        + "1405/06/07\n20:16";

    /** A deposit over the instant payment service, whose channel the bank spells out in full. */
    static final String DEPOSIT_SEP =
        BRAND + ACCOUNT
        + "\u0648\u0627\u0631\u06CC\u0632: 12,000,000 \u0631\u06CC\u0627\u0644 \n"
        + "\u0627\u0632 \u0637\u0631\u064A\u0642: \u0633\u0627\u0645\u0627\u0646\u0647 \u067E\u0644 "
        + "(\u067E\u0631\u062F\u0627\u062E\u062A \u0644\u062D\u0638\u0647 \u0627\u06CC)  \n"
        + "\u0645\u0627\u0646\u062F\u0647: 60,000,000 \u0631\u06CC\u0627\u0644 \n"
        + "1405/06/06\n00:08";

    /** A withdrawal at a shop's own terminal. */
    static final String WITHDRAWAL_POS =
        BRAND + ACCOUNT
        + "\u0628\u0631\u062F\u0627\u0634\u062A: 300,000 \u0631\u06CC\u0627\u0644 \n"
        + "\u0627\u0632 \u0637\u0631\u064A\u0642: \u067E\u0627\u06CC\u0627\u0646\u0647 \u0641\u0631\u0648\u0634  \n"
        + "\u0645\u0627\u0646\u062F\u0647: 47,700,000 \u0631\u06CC\u0627\u0644 \n"
        + "1405/06/08\n11:02";

    /** A withdrawal made in the app of the bank itself. */
    static final String WITHDRAWAL_MOBILE =
        BRAND + ACCOUNT
        + "\u0628\u0631\u062F\u0627\u0634\u062A: 200,000 \u0631\u06CC\u0627\u0644 \n"
        + "\u0627\u0632 \u0637\u0631\u064A\u0642: \u0647\u0645\u0631\u0627\u0647 \u0628\u0627\u0646\u06A9  \n"
        + "\u0645\u0627\u0646\u062F\u0647: 47,500,000 \u0631\u06CC\u0627\u0644 \n"
        + "1405/06/09\n09:41";

    /** A withdrawal over the counter, the one channel that is a place rather than a system. */
    static final String WITHDRAWAL_BRANCH =
        BRAND + ACCOUNT
        + "\u0628\u0631\u062F\u0627\u0634\u062A: 1,500,000 \u0631\u06CC\u0627\u0644 \n"
        + "\u0627\u0632 \u0637\u0631\u064A\u0642: \u0634\u0639\u0628\u0647  \n"
        + "\u0645\u0627\u0646\u062F\u0647: 46,000,000 \u0631\u06CC\u0627\u0644 \n"
        + "1405/06/10\n13:20";

    /** A movement that states no channel at all: the bank sent it without the line, and the movement
     *  still has to be read, dated and summed exactly as before. */
    static final String NO_CHANNEL =
        BRAND + ACCOUNT
        + "\u0628\u0631\u062F\u0627\u0634\u062A: 900,000 \u0631\u06CC\u0627\u0644 \n"
        + "\u0645\u0627\u0646\u062F\u0647: 45,100,000 \u0631\u06CC\u0627\u0644 \n"
        + "1405/06/11\n08:05";

    /** A movement whose channel this build does not caption. The bank naming a channel the app has
     *  never seen must store nothing rather than show an untranslated fragment of its message. */
    static final String UNKNOWN_CHANNEL =
        BRAND + ACCOUNT
        + "\u0628\u0631\u062F\u0627\u0634\u062A: 800,000 \u0631\u06CC\u0627\u0644 \n"
        + "\u0627\u0632 \u0637\u0631\u064A\u0642: \u062F\u0631\u06AF\u0627\u0647 \u0627\u06CC\u0646\u062A\u0631\u0646\u062A\u06CC  \n"
        + "\u0645\u0627\u0646\u062F\u0647: 44,300,000 \u0631\u06CC\u0627\u0644 \n"
        + "1405/06/12\n17:55";

    /** The same channel line written the way a sender that spells Persian yeh would write it. The
     *  label is the bank's, not the app's, so the app has to fold the two letter forms to read it. */
    static final String PERSIAN_YEH_CHANNEL =
        BRAND + ACCOUNT
        + "\u0628\u0631\u062F\u0627\u0634\u062A: 700,000 \u0631\u06CC\u0627\u0644 \n"
        + "\u0627\u0632 \u0637\u0631\u06CC\u0642: \u0634\u062A\u0627\u0628  \n"
        + "\u0645\u0627\u0646\u062F\u0647: 43,600,000 \u0631\u06CC\u0627\u0644 \n"
        + "1405/06/13\n10:30";

    /** A channel-shaped line that is really a balance: the guards have to refuse it on shape alone,
     *  before the allowlist is even consulted. */
    static final String BALANCE_IN_CHANNEL_POSITION =
        BRAND + ACCOUNT
        + "\u0628\u0631\u062F\u0627\u0634\u062A: 600,000 \u0631\u06CC\u0627\u0644 \n"
        + "\u0627\u0632 \u0637\u0631\u064A\u0642: 1,000,000 \u0631\u06CC\u0627\u0644 \n"
        + "\u0645\u0627\u0646\u062F\u0647: 43,000,000 \u0631\u06CC\u0627\u0644 \n"
        + "1405/06/14\n12:00";
}
