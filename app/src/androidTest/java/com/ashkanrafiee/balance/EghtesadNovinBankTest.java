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
 * The Eghtesad Novin withdrawal SMS shape: sender, dash-separated account behind the movement
 * label, bare signed "ریال" amount, resulting balance, year-less date and the support phone line
 * (which states no reason, no channel, no account and no balance).
 *
 * <p>Pure parser assertions use a fixed arrival time; one test also sends the same fixture
 * through the real SMS provider and history scan.
 */
@RunWith(AndroidJUnit4.class)
public class EghtesadNovinBankTest {

    private static final String BANK = "Eghtesad Novin";
    private static final long MOVEMENT = -5_270_000L;
    private static final long BALANCE = 1_234_567_890L;

    /** The sample's stated date is 05/08 at 10:04, written without its year. The arrival sits
     *  about a month after it, inside the trust window. */
    private static final long ARRIVAL = at(2026, 9, 1, 12, 0);

    /** Fixed provider row time; it is deliberately not the device clock at test execution. */
    private static final long PROVIDER_ARRIVAL = 1_000_000_000L;

    private Context context;
    private boolean providerTouched;

    @Test public void senderAccountBalanceAndWithdrawal_areReadFromTheCompleteFixture() {
        assertEquals(BANK, BankRules.resolve(EghtesadNovinMessages.SENDER));
        assertEquals(BANK, BankRules.resolve("Enbank"));
        assertEquals(EghtesadNovinMessages.ACCOUNT,
            BankRules.extractAccount(BANK, EghtesadNovinMessages.WITHDRAWAL));
        assertEquals(MOVEMENT,
            (long) BalanceData.extractTransaction(EghtesadNovinMessages.WITHDRAWAL));
        assertEquals(BALANCE, BalanceData.extract(EghtesadNovinMessages.WITHDRAWAL));

        long event = MessageDate.eventTime(EghtesadNovinMessages.WITHDRAWAL, ARRIVAL,
            BankRules.calendar(BANK));
        assertEquals(persian(1405, 5, 8, 10, 4), event);

        Transaction transaction = BalanceData.parseMovement(BANK, EghtesadNovinMessages.SENDER,
            EghtesadNovinMessages.WITHDRAWAL, event, false, 0);
        assertNotNull(transaction);
        assertEquals(BANK, transaction.bank);
        assertEquals(EghtesadNovinMessages.ACCOUNT, transaction.account);
        assertEquals(MOVEMENT, transaction.amount);
        assertEquals(Long.valueOf(BALANCE), transaction.balance);
        assertEquals(event, transaction.date);
    }

    @Test public void amountLine_doesNotReadTheAccountDigits() {
        // Without the signed amount line the label scan would read the account's own leading
        // digits ("برداشت: 314-…") as the movement; the signed "ریال" line is what carries it.
        assertEquals(EghtesadNovinMessages.ACCOUNT,
            BankRules.extractAccount(BANK, EghtesadNovinMessages.WITHDRAWAL));
        assertEquals(MOVEMENT,
            (long) BalanceData.extractTransaction(EghtesadNovinMessages.WITHDRAWAL));
    }

    @Test public void alternateSpellingsAndDigits_readTheSameMovement() {
        // The same message spelled the Persian way states the same movement, balance and account.
        String persian = EghtesadNovinMessages.WITHDRAWAL
            .replace("ريال", "ریال")
            .replace("آواي", "آوای")
            .replace("نوين", "نوین");
        assertEquals(EghtesadNovinMessages.ACCOUNT, BankRules.extractAccount(BANK, persian));
        assertEquals(MOVEMENT, (long) BalanceData.extractTransaction(persian));
        assertEquals(BALANCE, BalanceData.extract(persian));
        assertEquals(persian(1405, 5, 8, 10, 4),
            MessageDate.eventTime(persian, ARRIVAL, BankRules.calendar(BANK)));

        String persianDigits = EghtesadNovinMessages.WITHDRAWAL
            .replace("5,270,000", "۵,۲۷۰,۰۰۰")
            .replace("1,234,567,890", "۱۲۳۴۵۶۷۸۹۰")
            .replace("5/8-10:04", "۵/۸-۱۰:۰۴");
        assertEquals(MOVEMENT, (long) BalanceData.extractTransaction(persianDigits));
        assertEquals(BALANCE, BalanceData.extract(persianDigits));

        String crlf = EghtesadNovinMessages.WITHDRAWAL.replace("\n", "\r\n");
        assertEquals(EghtesadNovinMessages.ACCOUNT, BankRules.extractAccount(BANK, crlf));
        assertEquals(MOVEMENT, (long) BalanceData.extractTransaction(crlf));
        assertEquals(BALANCE, BalanceData.extract(crlf));

        // The bank may wrap the amount line in direction marks, like Maskan does.
        String marked = EghtesadNovinMessages.WITHDRAWAL
            .replace("-5,270,000ريال", "‎-5,270,000ريال‎");
        assertEquals(MOVEMENT, (long) BalanceData.extractTransaction(marked));
    }

    @Test public void movementRule_requiresTheBalanceLine() {
        // A movement without the resulting balance is a prompt, not a settled movement.
        assertNull(BalanceData.extractTransaction(
            "برداشت: " + EghtesadNovinMessages.ACCOUNT + "\n-5,270,000ريال\n5/8-10:04\n"
            + "مرکزآواي نوين:" + EghtesadNovinMessages.PHONE));
        assertNull(BalanceData.extractTransaction(
            "مانده:1,234,567,890ريال\n5/8-10:04"));
    }

    @Test public void accountLine_rejectsWrongStructuresAndForeignLines() {
        assertNull(BankRules.extractAccount(BANK,
            EghtesadNovinMessages.WITHDRAWAL.replace(EghtesadNovinMessages.ACCOUNT,
                "31-206-7091854-6")));
        assertNull(BankRules.extractAccount(BANK,
            EghtesadNovinMessages.WITHDRAWAL.replace(EghtesadNovinMessages.ACCOUNT,
                "314-206-7091854-66")));
        // The support phone line is not an account, a balance or a movement.
        assertNull(BankRules.extractAccount(BANK,
            "مرکزآواي نوين:" + EghtesadNovinMessages.PHONE));
        assertNull(BankRules.extractAccount(BANK, "مانده:1,234,567,890ريال"));
        assertNull(BankRules.extractAccount(BANK, "5/8-10:04\nمانده:1,234,567,890ريال"));
    }

    @Test public void phoneLine_statesNothingAtAll() {
        String phoneLine = "مرکزآواي نوين:" + EghtesadNovinMessages.PHONE;
        assertEquals(-1L, BalanceData.extract(phoneLine));
        assertNull(BalanceData.extractTransaction(
            phoneLine + "\nمانده:1,234,567,890ريال"));
        assertNull(BankRules.extractReason(BANK, EghtesadNovinMessages.WITHDRAWAL));
        assertNull(BankRules.extractChannel(BANK, EghtesadNovinMessages.WITHDRAWAL));
    }

    @Test public void providerScan_storesEghtesadNovinMovementAndAccount_withoutReasonOrChannel()
            throws Exception {
        providerTouched = true;
        prepareProvider();
        seed(EghtesadNovinMessages.SENDER, EghtesadNovinMessages.WITHDRAWAL, PROVIDER_ARRIVAL);

        assertEquals(1, BalanceData.scanHistory(context));
        java.util.List<Transaction> transactions = BalanceData.readTransactions(context);
        assertEquals(1, transactions.size());

        Transaction transaction = transactions.get(0);
        assertEquals(BANK, transaction.bank);
        assertEquals(EghtesadNovinMessages.ACCOUNT, transaction.account);
        assertEquals(MOVEMENT, transaction.amount);
        assertEquals(Long.valueOf(BALANCE), transaction.balance);
        assertTrue("Eghtesad Novin states no supported reason",
            BalanceData.readReasons(context).isEmpty());
        assertTrue("Eghtesad Novin states no supported channel",
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
