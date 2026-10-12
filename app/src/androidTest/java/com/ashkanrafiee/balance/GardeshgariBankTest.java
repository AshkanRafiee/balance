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
 * The Gardeshgari deposit SMS shape: sender, dotted account behind "واریز به:", "مبلغ:" movement,
 * two-digit-year date and the resulting balance. The standalone "کارت" line is deliberately not
 * read yet (its meaning is unconfirmed), so the movement carries no reason and no channel.
 *
 * <p>Pure parser assertions use a fixed arrival time; one test also sends the same fixture
 * through the real SMS provider and history scan.
 */
@RunWith(AndroidJUnit4.class)
public class GardeshgariBankTest {

    private static final String BANK = "Gardeshgari";
    private static final long MOVEMENT = 2_350_000L;
    private static final long BALANCE = 123_456_789L;

    /** The sample's stated date is 1405/05/09 at 21:36, written with a two-digit year. The
     *  arrival sits about a month after it, inside the trust window. */
    private static final long ARRIVAL = at(2026, 9, 1, 12, 0);

    /** Fixed provider row time; it is deliberately not the device clock at test execution. */
    private static final long PROVIDER_ARRIVAL = 1_000_000_000L;

    private Context context;
    private boolean providerTouched;

    @Test public void senderAccountBalanceAndDeposit_areReadFromTheCompleteFixture() {
        assertEquals(BANK, BankRules.resolve(GardeshgariMessages.SENDER));
        assertEquals(BANK, BankRules.resolve("Gardeshgari"));
        assertEquals(GardeshgariMessages.ACCOUNT,
            BankRules.extractAccount(BANK, GardeshgariMessages.DEPOSIT));
        assertEquals(MOVEMENT, (long) BalanceData.extractTransaction(GardeshgariMessages.DEPOSIT));
        assertEquals(BALANCE, BalanceData.extract(GardeshgariMessages.DEPOSIT));

        long event = MessageDate.eventTime(GardeshgariMessages.DEPOSIT, ARRIVAL,
            BankRules.calendar(BANK));
        assertEquals(persian(1405, 5, 9, 21, 36), event);

        Transaction transaction = BalanceData.parseMovement(BANK, GardeshgariMessages.SENDER,
            GardeshgariMessages.DEPOSIT, event, false, 0);
        assertNotNull(transaction);
        assertEquals(BANK, transaction.bank);
        assertEquals(GardeshgariMessages.ACCOUNT, transaction.account);
        assertEquals(MOVEMENT, transaction.amount);
        assertEquals(Long.valueOf(BALANCE), transaction.balance);
        assertEquals(event, transaction.date);
    }

    @Test public void alternateSpellingsAndDigits_readTheSameMovement() {
        // The same message spelled the Persian way states the same movement, balance and account.
        String persian = GardeshgariMessages.DEPOSIT
            .replace("واريز", "واریز")
            .replace("ريال", "ریال")
            .replace("موجودي", "موجودی")
            .replace("بانك", "بانک");
        assertEquals(GardeshgariMessages.ACCOUNT, BankRules.extractAccount(BANK, persian));
        assertEquals(MOVEMENT, (long) BalanceData.extractTransaction(persian));
        assertEquals(BALANCE, BalanceData.extract(persian));
        assertEquals(persian(1405, 5, 9, 21, 36),
            MessageDate.eventTime(persian, ARRIVAL, BankRules.calendar(BANK)));

        String persianDigits = GardeshgariMessages.DEPOSIT
            .replace("2,350,000", "۲,۳۵۰,۰۰۰")
            .replace("123,456,789", "۱۲۳,۴۵۶,۷۸۹")
            .replace("05/05/09_21:36", "۰۵/۰۵/۰۹_۲۱:۳۶");
        assertEquals(MOVEMENT, (long) BalanceData.extractTransaction(persianDigits));
        assertEquals(BALANCE, BalanceData.extract(persianDigits));

        String crlf = GardeshgariMessages.DEPOSIT.replace("\n", "\r\n");
        assertEquals(GardeshgariMessages.ACCOUNT, BankRules.extractAccount(BANK, crlf));
        assertEquals(MOVEMENT, (long) BalanceData.extractTransaction(crlf));
        assertEquals(BALANCE, BalanceData.extract(crlf));
    }

    @Test public void movementRule_requiresTheBalanceLine() {
        // A movement without the resulting balance is a prompt, not a settled movement.
        assertNull(BalanceData.extractTransaction(
            "*بانك گردشگری*\nکارت\nواريز به: " + GardeshgariMessages.ACCOUNT + "\n"
            + "مبلغ: 2,350,000 ريال\n05/05/09_21:36"));
        assertNull(BalanceData.extractTransaction("موجودي: 123,456,789 ريال\n05/05/09_21:36"));
    }

    @Test public void accountLine_rejectsShortLongBareAndDatelikeLines() {
        assertNull(BankRules.extractAccount(BANK,
            GardeshgariMessages.DEPOSIT.replace(GardeshgariMessages.ACCOUNT, "22.447.6035.7")));
        assertNull(BankRules.extractAccount(BANK,
            GardeshgariMessages.DEPOSIT.replace(GardeshgariMessages.ACCOUNT,
                "22011.447111.60358291111.71111")));
        assertNull(BankRules.extractAccount(BANK, "موجودي: 123,456,789 ريال"));
        assertNull(BankRules.extractAccount(BANK, "05/05/09_21:36\nموجودي: 123,456,789 ريال"));
    }

    @Test public void cardLine_statesNoReasonAndNoChannel() {
        // The "کارت" line's meaning is unconfirmed, so it is read as neither — rather than an
        // untranslated fragment appearing as though the app had understood it.
        assertNull(BankRules.extractReason(BANK, GardeshgariMessages.DEPOSIT));
        assertNull(BankRules.extractChannel(BANK, GardeshgariMessages.DEPOSIT));
    }

    @Test public void providerScan_storesGardeshgariMovementAndAccount_withoutReasonOrChannel()
            throws Exception {
        providerTouched = true;
        prepareProvider();
        seed(GardeshgariMessages.SENDER, GardeshgariMessages.DEPOSIT, PROVIDER_ARRIVAL);

        assertEquals(1, BalanceData.scanHistory(context));
        java.util.List<Transaction> transactions = BalanceData.readTransactions(context);
        assertEquals(1, transactions.size());

        Transaction transaction = transactions.get(0);
        assertEquals(BANK, transaction.bank);
        assertEquals(GardeshgariMessages.ACCOUNT, transaction.account);
        assertEquals(MOVEMENT, transaction.amount);
        assertEquals(Long.valueOf(BALANCE), transaction.balance);
        assertTrue("Gardeshgari states no supported reason yet",
            BalanceData.readReasons(context).isEmpty());
        assertTrue("Gardeshgari states no supported channel",
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
