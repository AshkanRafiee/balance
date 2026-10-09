package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.provider.Telephony;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;

/** Refah's glued account and trailing-sign movement SMS layout. */
@RunWith(AndroidJUnit4.class)
public class RefahBankTest {

    private static final String BANK = "Refah";
    private static final String PROVIDER_SENDER = "REFAH";
    private static final long PROVIDER_ARRIVAL = 1_000_000_000L;
    private Context context;
    private boolean providerTouched;

    @Test public void senderAccountBalanceAndPurchase_areReadFromTemplate() {
        assertEquals(BANK, BankRules.resolve(RefahMessages.SENDER));
        assertEquals(RefahMessages.ACCOUNT,
            BankRules.extractAccount(BANK, RefahMessages.PURCHASE));
        assertEquals(-3_210_000L, (long) BalanceData.extractTransaction(RefahMessages.PURCHASE));
        assertEquals(147_654_321L, BalanceData.extract(RefahMessages.PURCHASE));
    }

    @Test public void cardMovement_usesItsTrailingMinusSign() {
        assertEquals(RefahMessages.ACCOUNT,
            BankRules.extractAccount(BANK, RefahMessages.CARD));
        assertEquals(-12_345_600L, (long) BalanceData.extractTransaction(RefahMessages.CARD));
        assertEquals(987_654_321L, BalanceData.extract(RefahMessages.CARD));

        Transaction transaction = BalanceData.parseMovement(BANK, RefahMessages.SENDER,
            RefahMessages.CARD, 1L, false, 0L);
        assertNotNull(transaction);
        assertEquals(RefahMessages.ACCOUNT, transaction.account);
        assertEquals(-12_345_600L, transaction.amount);
        assertEquals(Long.valueOf(987_654_321L), transaction.balance);
    }

    @Test public void accountRule_rejectsColonAndShortValues() {
        assertNull(BankRules.extractAccount(BANK, "حساب: 123456789\nمانده1,000"));
        assertNull(BankRules.extractAccount(BANK, "حساب12345\nمانده1,000"));
    }

    @Test public void movementRule_requiresTheTrailingSignAndResultingBalance() {
        assertNull(BalanceData.extractTransaction(
            "بانک رفاه\nحساب" + RefahMessages.ACCOUNT + "\nخرید3,210,000\nمانده147,654,321"));
        assertNull(BalanceData.extractTransaction(
            "بانک رفاه\nحساب" + RefahMessages.ACCOUNT + "\nخرید3,210,000-"));
        assertNull(BalanceData.extractTransaction(
            "بانک رفاه\nحساب" + RefahMessages.ACCOUNT + "\nمانده147,654,321-\n07/11-11:33"));
        assertNull(BalanceData.extractTransaction(
            "بانک رفاه\nحساب" + RefahMessages.ACCOUNT + "-\nمانده147,654,321\n07/11-11:33"));
    }

    @Test public void persianDigitsAndCrLf_areAccepted() {
        String message = RefahMessages.PURCHASE
            .replace("123456789", "۱۲۳۴۵۶۷۸۹")
            .replace("3,210,000", "۳,۲۱۰,۰۰۰")
            .replace("147,654,321", "۱۴۷,۶۵۴,۳۲۱")
            .replace("\n", "\r\n");
        assertEquals(RefahMessages.ACCOUNT, BankRules.extractAccount(BANK, message));
        assertEquals(-3_210_000L, (long) BalanceData.extractTransaction(message));
        assertEquals(147_654_321L, BalanceData.extract(message));
    }

    @Test public void providerScan_storesRefahMovementAndAccount() throws Exception {
        providerTouched = true;
        prepareProvider();
        seed(PROVIDER_SENDER, RefahMessages.CARD, PROVIDER_ARRIVAL);

        assertEquals(1, BalanceData.scanHistory(context));
        java.util.List<Transaction> transactions = BalanceData.readTransactions(context);
        assertEquals(1, transactions.size());

        Transaction transaction = transactions.get(0);
        assertEquals(BANK, transaction.bank);
        assertEquals(RefahMessages.ACCOUNT, transaction.account);
        assertEquals(-12_345_600L, transaction.amount);
        assertEquals(Long.valueOf(987_654_321L), transaction.balance);
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
        throw new AssertionError("seeded SMS did not arrive in time: sender=" + sender);
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
        throw new AssertionError("SMS inbox did not clear in time");
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
}
