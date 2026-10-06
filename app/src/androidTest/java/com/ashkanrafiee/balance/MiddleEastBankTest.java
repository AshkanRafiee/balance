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
 * The complete Middle East Bank SMS shape: sender, account, signed movement, date and reason.
 * Pure parser assertions use a fixed arrival time; one test also sends the same fixture through the
 * real SMS provider and history scan.
 */
@RunWith(AndroidJUnit4.class)
public class MiddleEastBankTest {

    private static final String SENDER = "20004861";
    private static final String BANK = "Middle East";
    private static final String ACCOUNT = "020/001234567";
    private static final String REASON = "واریز مبلغ افزایش موجودی حساب";
    private static final long MOVEMENT = 1_000_000L;

    /** The sample's stated date is 1405/07/13 at 21:32, written without its year. */
    private static final long ARRIVAL = at(2026, 10, 6, 12, 0);

    /** Fixed provider row time; it is deliberately not the device clock at test execution. */
    private static final long PROVIDER_ARRIVAL = 1_000_000_000L;

    private static final String DEPOSIT = middleEastBody("+");
    private static final String WITHDRAWAL = middleEastBody("-");

    private Context context;
    private boolean providerTouched;

    private static String middleEastBody(String sign) {
        return "بانک خاورمیانه\n"
            + ACCOUNT + "\n"
            + sign + "1,000,000\n"
            + "07/13\n"
            + "21:32\n"
            + "مانده 1,000,000\n"
            + REASON;
    }

    @Test public void senderAccountReasonAndDeposit_areReadFromTheCompleteFixture() {
        assertEquals(BANK, BankRules.resolve(SENDER));
        assertEquals(ACCOUNT, BankRules.extractAccount(BANK, DEPOSIT));
        assertEquals(MOVEMENT, (long) BalanceData.extractTransaction(DEPOSIT));
        assertEquals(MOVEMENT, BalanceData.extract(DEPOSIT));
        assertEquals(REASON, BankRules.extractReason(BANK, DEPOSIT));

        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.res.Configuration english =
            new android.content.res.Configuration(target.getResources().getConfiguration());
        english.setLocale(java.util.Locale.ENGLISH);
        assertEquals("Balance increase", BankRules.reasonCaption(
            target.createConfigurationContext(english), REASON));

        long event = MessageDate.eventTime(DEPOSIT, ARRIVAL, BankRules.calendar(BANK));
        assertEquals(persian(1405, 7, 13, 21, 32), event);

        Transaction transaction = BalanceData.parseMovement(BANK, SENDER, DEPOSIT, event, false, 0);
        assertNotNull(transaction);
        assertEquals(BANK, transaction.bank);
        assertEquals(ACCOUNT, transaction.account);
        assertEquals(MOVEMENT, transaction.amount);
        assertEquals(Long.valueOf(MOVEMENT), transaction.balance);
        assertEquals(event, transaction.date);
    }

    @Test public void signedMovementLine_controlsDepositAndWithdrawal_whenBalanceEqualsMovement() {
        assertEquals(MOVEMENT, (long) BalanceData.extractTransaction(DEPOSIT));
        assertEquals(-MOVEMENT, (long) BalanceData.extractTransaction(WITHDRAWAL));

        Transaction deposit = BalanceData.parseMovement(BANK, SENDER, DEPOSIT, ARRIVAL, false, 0);
        Transaction withdrawal = BalanceData.parseMovement(BANK, SENDER, WITHDRAWAL, ARRIVAL, false, 0);
        assertNotNull(deposit);
        assertNotNull(withdrawal);
        assertEquals(ACCOUNT, deposit.account);
        assertEquals(ACCOUNT, withdrawal.account);
        assertEquals(MOVEMENT, deposit.amount);
        assertEquals(-MOVEMENT, withdrawal.amount);
        assertEquals(Long.valueOf(MOVEMENT), deposit.balance);
        assertEquals(Long.valueOf(MOVEMENT), withdrawal.balance);
    }

    @Test public void accountLine_preservesDigitsAndSlash_andRejectsDatesPartialAndLongLines() {
        String persianAndArabicDigits = "بانک خاورمیانه\r\n"
            + "\u06F0\u06F2\u06F0/\u06F0\u06F0\u06F1\u06F2\u06F3\u06F4\u06F5\u06F6\u06F7\r\n"
            + "+\u0661\u066C\u0660\u0660\u0660\u066C\u0660\u0660\u0660\r\n"
            + "\u06F0\u06F7/\u0661\u0663\r\n"
            + "\u06F2\u0661:\u06F3\u0662\r\n"
            + "مانده \u0661\u066C\u0660\u0660\u0660\u066C\u0660\u0660\u0660\r\n"
            + "\u202A" + REASON + "\u202C";
        assertEquals(ACCOUNT, BankRules.extractAccount(BANK, persianAndArabicDigits));
        assertEquals(MOVEMENT, (long) BalanceData.extractTransaction(persianAndArabicDigits));
        assertEquals(MOVEMENT, BalanceData.extract(persianAndArabicDigits));
        assertEquals(REASON, BankRules.extractReason(BANK, persianAndArabicDigits));
        assertEquals(persian(1405, 7, 13, 21, 32),
            MessageDate.eventTime(persianAndArabicDigits, ARRIVAL, BankRules.calendar(BANK)));

        assertNull(BankRules.extractAccount(BANK,
            "بانک خاورمیانه\n020/00123456\n+1,000,000\nمانده 1,000,000"));
        assertNull(BankRules.extractAccount(BANK,
            "بانک خاورمیانه\n020/0012345678\n+1,000,000\nمانده 1,000,000"));
        assertNull(BankRules.extractAccount(BANK,
            "بانک خاورمیانه\n1405/07/13\n21:32\nمانده 1,000,000"));
        assertNull(BankRules.extractAccount(BANK,
            "بانک خاورمیانه\n020/001234567 suffix\n+1,000,000\nمانده 1,000,000"));
    }

    @Test public void providerScan_storesMiddleEastMovementAndReason_withoutInventingChannel()
            throws Exception {
        providerTouched = true;
        prepareProvider();
        seed(SENDER, DEPOSIT, PROVIDER_ARRIVAL);

        assertEquals(1, BalanceData.scanHistory(context));
        java.util.List<Transaction> transactions = BalanceData.readTransactions(context);
        assertEquals(1, transactions.size());

        Transaction transaction = transactions.get(0);
        assertEquals(BANK, transaction.bank);
        assertEquals(ACCOUNT, transaction.account);
        assertEquals(MOVEMENT, transaction.amount);
        assertEquals(Long.valueOf(MOVEMENT), transaction.balance);
        assertEquals(REASON, BalanceData.readReasons(context).get(BalanceData.noteKey(transaction)));
        assertTrue("Middle East does not state a supported channel",
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
