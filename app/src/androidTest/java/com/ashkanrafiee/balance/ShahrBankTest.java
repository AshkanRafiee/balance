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
 * The Shahr card-purchase SMS shape: sender, event line, debited account behind "برداشت از:",
 * "مبلغ:" movement, resulting balance and full-year date with seconds.
 *
 * <p>Pure parser assertions use a fixed arrival time; one test also sends the same fixture
 * through the real SMS provider and history scan.
 */
@RunWith(AndroidJUnit4.class)
public class ShahrBankTest {

    private static final String BANK = "Shahr";
    private static final long MOVEMENT = -254_800L;
    private static final long BALANCE = 12_345_678L;

    /** The sample's stated date is 1403/11/08 at 02:14:33, with the year written out. The
     *  arrival sits a few weeks after it, inside the trust window. */
    private static final long ARRIVAL = at(2025, 2, 20, 12, 0);

    /** Fixed provider row time; it is deliberately not the device clock at test execution. */
    private static final long PROVIDER_ARRIVAL = 1_000_000_000L;

    private Context context;
    private boolean providerTouched;

    @Test public void senderAccountReasonAndWithdrawal_areReadFromTheCompleteFixture() {
        assertEquals(BANK, BankRules.resolve(ShahrMessages.SENDER));
        assertEquals(ShahrMessages.ACCOUNT,
            BankRules.extractAccount(BANK, ShahrMessages.PURCHASE));
        assertEquals(MOVEMENT, (long) BalanceData.extractTransaction(ShahrMessages.PURCHASE));
        assertEquals(BALANCE, BalanceData.extract(ShahrMessages.PURCHASE));
        assertEquals(ShahrMessages.CARD_PURCHASE_REASON,
            BankRules.extractReason(BANK, ShahrMessages.PURCHASE));

        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.res.Configuration english =
            new android.content.res.Configuration(target.getResources().getConfiguration());
        english.setLocale(java.util.Locale.ENGLISH);
        assertEquals("Card purchase", BankRules.reasonCaption(
            target.createConfigurationContext(english), ShahrMessages.CARD_PURCHASE_REASON));

        long event = MessageDate.eventTime(ShahrMessages.PURCHASE, ARRIVAL,
            BankRules.calendar(BANK));
        assertEquals(persian(1403, 11, 8, 2, 14), event);

        Transaction transaction = BalanceData.parseMovement(BANK, ShahrMessages.SENDER,
            ShahrMessages.PURCHASE, event, false, 0);
        assertNotNull(transaction);
        assertEquals(BANK, transaction.bank);
        assertEquals(ShahrMessages.ACCOUNT, transaction.account);
        assertEquals(MOVEMENT, transaction.amount);
        assertEquals(Long.valueOf(BALANCE), transaction.balance);
        assertEquals(event, transaction.date);
    }

    @Test public void alternateSpellingsAndDigits_readTheSameMovement() {
        // The same message spelled the Persian way states the same movement, balance, account
        // and reason.
        String persian = ShahrMessages.PURCHASE
            .replace("خريد", "خرید")
            .replace("ريال", "ریال")
            .replace("موجودي", "موجودی");
        assertEquals(ShahrMessages.ACCOUNT, BankRules.extractAccount(BANK, persian));
        assertEquals(MOVEMENT, (long) BalanceData.extractTransaction(persian));
        assertEquals(BALANCE, BalanceData.extract(persian));
        assertEquals(ShahrMessages.CARD_PURCHASE_REASON,
            BankRules.extractReason(BANK, persian));
        assertEquals(persian(1403, 11, 8, 2, 14),
            MessageDate.eventTime(persian, ARRIVAL, BankRules.calendar(BANK)));

        String persianDigits = ShahrMessages.PURCHASE
            .replace("254,800", "۲۵۴,۸۰۰")
            .replace("12,345,678", "۱۲,۳۴۵,۶۷۸");
        assertEquals(MOVEMENT, (long) BalanceData.extractTransaction(persianDigits));
        assertEquals(BALANCE, BalanceData.extract(persianDigits));

        String crlf = ShahrMessages.PURCHASE.replace("\n", "\r\n");
        assertEquals(ShahrMessages.ACCOUNT, BankRules.extractAccount(BANK, crlf));
        assertEquals(MOVEMENT, (long) BalanceData.extractTransaction(crlf));
        assertEquals(BALANCE, BalanceData.extract(crlf));
        assertEquals(ShahrMessages.CARD_PURCHASE_REASON,
            BankRules.extractReason(BANK, crlf));
    }

    @Test public void movementRule_requiresTheBalanceLine() {
        // A movement without the resulting balance is a prompt, not a settled movement.
        assertNull(BalanceData.extractTransaction(
            "بانک شهر\nخريد با کارت\nبرداشت از:" + ShahrMessages.ACCOUNT + "\n"
            + "مبلغ:254,800ريال\n1403/11/8 02:14:33"));
        assertNull(BalanceData.extractTransaction(
            "موجودي:12,345,678 ريال\n1403/11/8 02:14:33"));
    }

    @Test public void accountLine_rejectsShortLongAndForeignLines() {
        assertNull(BankRules.extractAccount(BANK,
            ShahrMessages.PURCHASE.replace(ShahrMessages.ACCOUNT, "12345")));
        assertNull(BankRules.extractAccount(BANK,
            ShahrMessages.PURCHASE.replace(ShahrMessages.ACCOUNT,
                "123456789012345678901234567890")));
        // A destination mention mid-line is not the debited account.
        assertNull(BankRules.extractAccount(BANK,
            "انتقال به حساب: 1234567890123\nمبلغ: 87,450ريال\nموجودي: 9,876,543 ريال"));
        assertNull(BankRules.extractAccount(BANK, "موجودي:12,345,678 ريال"));
    }

    @Test public void reason_notStatedWithoutTheCardLine() {
        assertNull(BankRules.extractReason(BANK, "موجودي:12,345,678 ريال"));
        // Mentioned inside a longer sentence, the event is not the line the bank states it on.
        assertNull(BankRules.extractReason(BANK,
            "متن خريد با کارت اضافه\nموجودي:12,345,678 ريال"));
        assertNull(BankRules.extractReason(BANK, BluMessages.TOPUP));
    }

    @Test public void providerScan_storesShahrMovementAccountAndReason_withoutInventingChannel()
            throws Exception {
        providerTouched = true;
        prepareProvider();
        seed(ShahrMessages.SENDER, ShahrMessages.PURCHASE, PROVIDER_ARRIVAL);

        assertEquals(1, BalanceData.scanHistory(context));
        java.util.List<Transaction> transactions = BalanceData.readTransactions(context);
        assertEquals(1, transactions.size());

        Transaction transaction = transactions.get(0);
        assertEquals(BANK, transaction.bank);
        assertEquals(ShahrMessages.ACCOUNT, transaction.account);
        assertEquals(MOVEMENT, transaction.amount);
        assertEquals(Long.valueOf(BALANCE), transaction.balance);
        assertEquals(ShahrMessages.CARD_PURCHASE_REASON,
            BalanceData.readReasons(context).get(BalanceData.noteKey(transaction)));
        assertTrue("Shahr does not state a supported channel",
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
