package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.provider.Telephony;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.util.Calendar;

/**
 * The complete Sepah profit-deposit SMS shape: sender, account, movement, date and reason.
 * Pure parser assertions use a fixed arrival time; one test also sends the same fixture through
 * the real SMS provider and history scan.
 */
@RunWith(AndroidJUnit4.class)
public class SepahBankTest {

    private static final String SENDER = SepahMessages.SENDER;
    private static final String BANK = "Sepah";
    private static final String ACCOUNT = SepahMessages.ACCOUNT;
    private static final String REASON = SepahMessages.PROFIT;
    private static final long MOVEMENT = 87_450L;
    private static final long BALANCE = 9_876_543L;

    /** The sample's stated date is 1405/06/11 at 20:16. The year-less sample (7/15) reads off
     *  the same arrival, which sits a few days after it. */
    private static final long ARRIVAL = at(2026, 10, 10, 12, 0);

    /** Fixed provider row time; it is deliberately not the device clock at test execution. */
    private static final long PROVIDER_ARRIVAL = 1_000_000_000L;

    /** The sender the provider test seeds with. Spaceless on purpose: the seed command below is
     *  executed without a quoting shell, so a sender with a space would arrive truncated (the
     *  spaced sender is covered by the resolution assertions above instead). */
    private static final String PROVIDER_SENDER = "SEPAHBANK";

    private Context context;
    private boolean providerTouched;

    @Test public void senderAccountReasonAndDeposit_areReadFromTheCompleteFixture() {
        assertEquals(BANK, BankRules.resolve(SENDER));
        assertEquals(BANK, BankRules.resolve(PROVIDER_SENDER));
        assertEquals(ACCOUNT, BankRules.extractAccount(BANK, SepahMessages.PROFIT_A));
        assertEquals(MOVEMENT, (long) BalanceData.extractTransaction(SepahMessages.PROFIT_A));
        assertEquals(BALANCE, BalanceData.extract(SepahMessages.PROFIT_A));
        assertEquals(REASON, BankRules.extractReason(BANK, SepahMessages.PROFIT_A));

        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.res.Configuration english =
            new android.content.res.Configuration(target.getResources().getConfiguration());
        english.setLocale(java.util.Locale.ENGLISH);
        assertEquals("Profit deposit", BankRules.reasonCaption(
            target.createConfigurationContext(english), REASON));

        long event = MessageDate.eventTime(SepahMessages.PROFIT_A, ARRIVAL, BankRules.calendar(BANK));
        assertEquals(persian(1405, 6, 11, 20, 16), event);

        Transaction transaction = BalanceData.parseMovement(BANK, SENDER, SepahMessages.PROFIT_A,
            event, false, 0);
        assertNotNull(transaction);
        assertEquals(BANK, transaction.bank);
        assertEquals(ACCOUNT, transaction.account);
        assertEquals(MOVEMENT, transaction.amount);
        assertEquals(Long.valueOf(BALANCE), transaction.balance);
        assertEquals(event, transaction.date);
    }

    @Test public void laterProfit_chainsOffTheEarlierBalance() {
        assertEquals(92_310L, (long) BalanceData.extractTransaction(SepahMessages.PROFIT_B));
        assertEquals(9_968_853L, BalanceData.extract(SepahMessages.PROFIT_B));
        assertEquals(ACCOUNT, BankRules.extractAccount(BANK, SepahMessages.PROFIT_B));
        assertEquals(REASON, BankRules.extractReason(BANK, SepahMessages.PROFIT_B));
        assertEquals(persian(1405, 7, 12, 2, 33),
            MessageDate.eventTime(SepahMessages.PROFIT_B, ARRIVAL, BankRules.calendar(BANK)));
    }

    @Test public void alternateSpellingsAndDigits_readTheSameAccountAndReason() {
        String persianYeh = SepahMessages.PROFIT_A.replace("واريز", "واریز");
        assertEquals(ACCOUNT, BankRules.extractAccount(BANK, persianYeh));
        assertEquals(REASON, BankRules.extractReason(BANK, persianYeh));
        assertEquals(MOVEMENT, (long) BalanceData.extractTransaction(persianYeh));

        String persianDigits = "بانک سپه\n"
            + "واریز سود به: \u06F0\u06F1\u06F0\u06F0\u06F0\u06F0\u06F0\u06F0\u06F0\u06F0\u06F0\u06F0\u06F0\u06F1\n"
            + "مبلغ: \u06F8\u06F7,\u06F4\u06F5\u06F0ريال\n"
            + "زمان: \u06F1\u06F4\u06F0\u06F5/\u06F6/\u06F1\u06F1-\u06F2\u06F0:\u06F1\u06F6\n"
            + "مانده: \u06F9,\u06F8\u06F7\u06F6,\u06F5\u06F4\u06F3ريال";
        assertEquals(ACCOUNT, BankRules.extractAccount(BANK, persianDigits));
        assertEquals(REASON, BankRules.extractReason(BANK, persianDigits));
        assertEquals(MOVEMENT, (long) BalanceData.extractTransaction(persianDigits));
        assertEquals(BALANCE, BalanceData.extract(persianDigits));
        assertEquals(persian(1405, 6, 11, 20, 16),
            MessageDate.eventTime(persianDigits, ARRIVAL, BankRules.calendar(BANK)));

        String crlf = SepahMessages.PROFIT_A.replace("\n", "\r\n");
        assertEquals(ACCOUNT, BankRules.extractAccount(BANK, crlf));
        assertEquals(REASON, BankRules.extractReason(BANK, crlf));
    }

    @Test public void accountLine_rejectsShortLongAndForeignLines() {
        assertNull(BankRules.extractAccount(BANK,
            SepahMessages.PROFIT_A.replace(ACCOUNT, "12345")));
        assertNull(BankRules.extractAccount(BANK,
            SepahMessages.PROFIT_A.replace(ACCOUNT, "123456789012345678901234567890")));
        // A destination mention without the profit event is not the credited account.
        assertNull(BankRules.extractAccount(BANK,
            "انتقال به حساب: 12345678\nمبلغ: 87,450ريال\nمانده: 9,876,543ريال"));
        assertNull(BankRules.extractAccount(BANK, "مانده: 9,876,543ريال"));
    }

    @Test public void reason_notStatedWithoutTheProfitLine() {
        assertNull(BankRules.extractReason(BANK, "مانده: 9,876,543ريال"));
        // Mentioned inside a longer sentence, the event is not the line the bank credits on.
        assertNull(BankRules.extractReason(BANK,
            "متن واریز سود به: 12345678\nمانده: 9,876,543ريال"));
        assertNull(BankRules.extractReason(BANK, BluMessages.TOPUP));
    }

    @Test public void plainDeposit_isReadWithItsOwnAccountAndNoReason() {
        assertEquals(SepahMessages.ACCOUNT_B,
            BankRules.extractAccount(BANK, SepahMessages.DEPOSIT));
        assertEquals(250_000L, (long) BalanceData.extractTransaction(SepahMessages.DEPOSIT));
        assertEquals(3_125_000L, BalanceData.extract(SepahMessages.DEPOSIT));
        assertNull(BankRules.extractReason(BANK, SepahMessages.DEPOSIT));
        assertEquals(persian(1405, 7, 15, 10, 48),
            MessageDate.eventTime(SepahMessages.DEPOSIT, ARRIVAL, BankRules.calendar(BANK)));

        Transaction transaction = BalanceData.parseMovement(BANK, SENDER, SepahMessages.DEPOSIT,
            persian(1405, 7, 15, 10, 48), false, 0);
        assertNotNull(transaction);
        assertEquals(BANK, transaction.bank);
        assertEquals(SepahMessages.ACCOUNT_B, transaction.account);
        assertEquals(250_000L, transaction.amount);
        assertEquals(Long.valueOf(3_125_000L), transaction.balance);
    }

    @Test public void providerScan_storesSepahMovementAndReason_withoutInventingChannel()
            throws Exception {
        providerTouched = true;
        prepareProvider();
        seed(PROVIDER_SENDER, SepahMessages.PROFIT_A, PROVIDER_ARRIVAL);

        assertEquals(1, BalanceData.scanHistory(context));
        java.util.List<Transaction> transactions = BalanceData.readTransactions(context);
        assertEquals(1, transactions.size());

        Transaction transaction = transactions.get(0);
        assertEquals(BANK, transaction.bank);
        assertEquals(ACCOUNT, transaction.account);
        assertEquals(MOVEMENT, transaction.amount);
        assertEquals(Long.valueOf(BALANCE), transaction.balance);
        assertEquals(REASON, BalanceData.readReasons(context).get(BalanceData.noteKey(transaction)));
        assertTrue("Sepah does not state a supported channel",
            BalanceData.readChannels(context).isEmpty());
    }

    @After public void tearDown() throws Exception {
        if (!providerTouched) return;
        clearInbox();
        context.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
    }

    private void prepareProvider() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BalanceData.reset(context, true);
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .adoptShellPermissionIdentity(android.Manifest.permission.READ_SMS);
        exec("pm grant " + context.getPackageName() + " android.permission.READ_SMS");
        context.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
        clearInbox();
    }

    private void seed(String sender, String body, long base) throws Exception {
        String body64 = android.util.Base64.encodeToString(
            body.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        exec("am broadcast -n com.ashkanrafiee.smsinject/.SeedReceiver"
            + " -a com.ashkanrafiee.smsinject.SEED"
            + " -e sender " + sender + " -e body64 " + body64 + " -e base " + base);
        awaitSms(sender, body);
    }

    private void awaitSms(String sender, String body) throws Exception {
        long deadline = System.currentTimeMillis() + 45_000L;
        while (System.currentTimeMillis() < deadline) {
            try (android.database.Cursor cursor = context.getContentResolver().query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY}, null, null, null)) {
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        if (sender.equals(cursor.getString(0)) && body.equals(cursor.getString(1))) return;
                    }
                }
            }
            Thread.sleep(150L);
        }
        fail("seeded SMS did not arrive in time: sender=" + sender);
    }

    private void clearInbox() throws Exception {
        exec("am broadcast -n com.ashkanrafiee.smsinject/.SeedReceiver"
            + " -a com.ashkanrafiee.smsinject.CLEAR");
        long deadline = System.currentTimeMillis() + 45_000L;
        while (System.currentTimeMillis() < deadline) {
            try (android.database.Cursor cursor = context.getContentResolver().query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{Telephony.Sms._ID}, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) return;
            }
            Thread.sleep(150L);
        }
        fail("SMS inbox did not clear in time");
    }

    private void exec(String command) throws Exception {
        android.os.ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation().executeShellCommand(command);
        try (InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            byte[] buffer = new byte[2048];
            while (input.read(buffer) >= 0) { }
        }
        Thread.sleep(200L);
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }

    private static long persian(int year, int month, int day, int hour, int minute) {
        int[] gregorian = JalaliCalendar.of(year, month, day).toGregorian();
        return at(gregorian[0], gregorian[1], gregorian[2], hour, minute);
    }
}
