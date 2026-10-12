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
 * The complete Maskan SMS shape: sender, account, leading-sign movement, balance, compact date
 * and the event label the first line states as the reason.
 *
 * <p>Pure parser assertions use a fixed arrival time; one test also sends the same fixture
 * through the real SMS provider and history scan.
 */
@RunWith(AndroidJUnit4.class)
public class MaskanBankTest {

    private static final String BANK = "Maskan";
    private static final long PROVIDER_ARRIVAL = 1_000_000_000L;

    /** The purchase sample's stated date is 07/06 at 11:48, written without its year. */
    private static final long ARRIVAL = at(2026, 10, 6, 12, 0);

    /** The internet-transfer sample's stated date is 02/20 at 09:26. It needs an arrival within
     *  the 45-day trust window after Ordibehesht 20, so it has an arrival of its own. */
    private static final long ARRIVAL_INTERNET = at(2026, 6, 15, 12, 0);

    private Context context;
    private boolean providerTouched;

    @Test public void senderAccountReasonAndWithdrawal_areReadFromTheCompleteFixture() {
        assertEquals(BANK, BankRules.resolve(MaskanMessages.SENDER));
        assertEquals(BANK, BankRules.resolve(MaskanMessages.BRAND_SENDER));
        assertEquals(MaskanMessages.ACCOUNT_A,
            BankRules.extractAccount(BANK, MaskanMessages.PURCHASE));
        assertEquals(-1_234_567L, (long) BalanceData.extractTransaction(MaskanMessages.PURCHASE));
        assertEquals(12_345_678L, BalanceData.extract(MaskanMessages.PURCHASE));
        assertEquals(MaskanMessages.PURCHASE_REASON,
            BankRules.extractReason(BANK, MaskanMessages.PURCHASE));

        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.res.Configuration english =
            new android.content.res.Configuration(target.getResources().getConfiguration());
        english.setLocale(java.util.Locale.ENGLISH);
        assertEquals("Purchase", BankRules.reasonCaption(
            target.createConfigurationContext(english), MaskanMessages.PURCHASE_REASON));

        long event = MessageDate.eventTime(MaskanMessages.PURCHASE, ARRIVAL,
            BankRules.calendar(BANK));
        assertEquals(persian(1405, 7, 6, 11, 48), event);

        Transaction transaction = BalanceData.parseMovement(BANK, MaskanMessages.SENDER,
            MaskanMessages.PURCHASE, event, false, 0);
        assertNotNull(transaction);
        assertEquals(BANK, transaction.bank);
        assertEquals(MaskanMessages.ACCOUNT_A, transaction.account);
        assertEquals(-1_234_567L, transaction.amount);
        assertEquals(Long.valueOf(12_345_678L), transaction.balance);
        assertEquals(event, transaction.date);
    }

    @Test public void leadingPlus_controlsDepositDirection_despiteWithdrawalKeyword() {
        // "انتقال" is a withdrawal keyword, so only the explicit plus makes this a deposit.
        assertEquals(50_000_000L,
            (long) BalanceData.extractTransaction(MaskanMessages.INTERNET_TRANSFER));
        assertEquals(57_894_000L, BalanceData.extract(MaskanMessages.INTERNET_TRANSFER));
        assertEquals(MaskanMessages.ACCOUNT_B,
            BankRules.extractAccount(BANK, MaskanMessages.INTERNET_TRANSFER));
        assertEquals(MaskanMessages.INTERNET_REASON,
            BankRules.extractReason(BANK, MaskanMessages.INTERNET_TRANSFER));
        assertEquals(persian(1405, 2, 20, 9, 26),
            MessageDate.eventTime(MaskanMessages.INTERNET_TRANSFER, ARRIVAL_INTERNET,
                BankRules.calendar(BANK)));

        Transaction transaction = BalanceData.parseMovement(BANK, MaskanMessages.SENDER,
            MaskanMessages.INTERNET_TRANSFER, ARRIVAL_INTERNET, false, 0);
        assertNotNull(transaction);
        assertEquals(50_000_000L, transaction.amount);
        assertEquals(MaskanMessages.ACCOUNT_B, transaction.account);
    }

    @Test public void feeLabelWithServiceYear_readsMovementAccountBalanceDateAndReason() {
        assertEquals(-95_140L, (long) BalanceData.extractTransaction(MaskanMessages.SMS_FEE));
        assertEquals(2_495_507L, BalanceData.extract(MaskanMessages.SMS_FEE));
        assertEquals(MaskanMessages.ACCOUNT_C,
            BankRules.extractAccount(BANK, MaskanMessages.SMS_FEE));
        assertEquals(MaskanMessages.SMS_FEE_REASON,
            BankRules.extractReason(BANK, MaskanMessages.SMS_FEE));
        assertEquals(persian(1405, 6, 11, 11, 54),
            MessageDate.eventTime(MaskanMessages.SMS_FEE, ARRIVAL, BankRules.calendar(BANK)));
    }

    @Test public void persianDigitsAndCrLf_areAccepted() {
        String message = MaskanMessages.PURCHASE
            .replace("150000000001", "۱۵۰۰۰۰۰۰۰۰۰۱")
            .replace("1,234,567", "۱,۲۳۴,۵۶۷")
            .replace("12,345,678", "۱۲,۳۴۵,۶۷۸")
            .replace("\n", "\r\n");
        assertEquals(MaskanMessages.ACCOUNT_A, BankRules.extractAccount(BANK, message));
        assertEquals(-1_234_567L, (long) BalanceData.extractTransaction(message));
        assertEquals(12_345_678L, BalanceData.extract(message));
        assertEquals(MaskanMessages.PURCHASE_REASON, BankRules.extractReason(BANK, message));
    }

    @Test public void movementRule_requiresTheSignAndResultingBalance() {
        // The same lines without the leading sign state no movement.
        assertNull(BalanceData.extractTransaction(
            "خريد:1,234,567\nحساب:" + MaskanMessages.ACCOUNT_A + "\nمانده:12,345,678\n0706-11:48"));
        // A movement without the resulting balance is a prompt, not a settled movement.
        assertNull(BalanceData.extractTransaction(
            "خريد:\u200E-1,234,567\u200E\nحساب:" + MaskanMessages.ACCOUNT_A + "\n0706-11:48"));
        // An unsigned balance line is never the movement, even with its sign-like date.
        assertNull(BalanceData.extractTransaction(
            "مانده:12,345,678\n0706-11:48"));
    }

    @Test public void accountRule_rejectsCommaGroupedAndShortValues() {
        assertNull(BankRules.extractAccount(BANK, "مانده:12,345,678\n0706-11:48"));
        assertNull(BankRules.extractAccount(BANK,
            "حساب:12345\nمانده:12,345,678\n0706-11:48"));
    }

    @Test public void reason_notStatedWithoutTheSignedAmountLine() {
        assertNull(BankRules.extractReason(BANK, "مانده:12,345,678\n0706-11:48"));
        assertNull(BankRules.extractReason(BANK,
            "حساب:" + MaskanMessages.ACCOUNT_A + "\nمانده:12,345,678\n0706-11:48"));
        assertNull(BankRules.extractReason(BANK, BluMessages.TOPUP));
    }

    @Test public void providerScan_storesMaskanMovementAccountAndReason_withoutInventingChannel()
            throws Exception {
        providerTouched = true;
        prepareProvider();
        seed(MaskanMessages.SENDER, MaskanMessages.PURCHASE, PROVIDER_ARRIVAL);

        assertEquals(1, BalanceData.scanHistory(context));
        java.util.List<Transaction> transactions = BalanceData.readTransactions(context);
        assertEquals(1, transactions.size());

        Transaction transaction = transactions.get(0);
        assertEquals(BANK, transaction.bank);
        assertEquals(MaskanMessages.ACCOUNT_A, transaction.account);
        assertEquals(-1_234_567L, transaction.amount);
        assertEquals(Long.valueOf(12_345_678L), transaction.balance);
        assertEquals(MaskanMessages.PURCHASE_REASON,
            BalanceData.readReasons(context).get(BalanceData.noteKey(transaction)));
        assertTrue("Maskan does not state a supported channel",
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
        exec("cmd appops set " + context.getPackageName()
            + " android:read_restricted_messages allow");
        exec("cmd appops set com.ashkanrafiee.smsinject WRITE_SMS allow");
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
