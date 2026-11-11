package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.provider.Telephony;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.model.MultipleFailureException;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Mofid's confirmed Hami messages, without treating other brokerage products as Jet Card. */
@RunWith(AndroidJUnit4.class)
public class MofidTest {
    private static final String BANK = "Mofid";
    private static final String SENDER = "10008700";
    private static final long DEPOSIT_AMOUNT = 47_381_295L;
    private static final long DEPOSIT_BALANCE = 83_146_702L;
    private static final long WITHDRAWAL_AMOUNT = -72_614L;
    private static final long WITHDRAWAL_BALANCE = 83_074_088L;
    // Near the sample dates, so the date trust window does not depend on today's clock.
    private static final long ARRIVAL = at(2023, 7, 28, 12, 0);

    private static final String DEPOSIT = "کارگزاری مفید\n"
        + "واریز مبلغ 47,381,295 ریال \n"
        + "به حساب حامی\n"
        + "مانده: 83,146,702 ریال\n"
        + "1402/04/26\n"
        + "09:41";
    private static final String WITHDRAWAL = "کارگزاری مفید\n"
        + "برداشت مبلغ 72,614 ریال\n"
        + "از حساب حامی\n"
        + "مانده: 83,074,088 ریال\n"
        + "1402/04/27\n"
        + "15:08";

    private Context context;
    private boolean providerTouched;
    private boolean shellIdentityAdopted;

    @Test public void senderVariants_resolveToCanonicalMofid_withLocalizedProductNames() {
        for (String sender : new String[]{SENDER, "+98 10 0087 00", "009810008700",
                "۱۰۰۰۸۷۰۰", "+۹۸ ۱۰ ۰۰۸۷ ۰۰"}) {
            assertEquals(sender, BANK, BankRules.resolve(sender));
        }
        assertEquals(CalendarSystem.JALALI, BankRules.calendar(BANK));
        assertTrue(BankRules.supportedNames().contains(BANK));

        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration english = new Configuration(target.getResources().getConfiguration());
        english.setLocale(Locale.ENGLISH);
        assertEquals("Mofid — Jet Card / Hami",
            BankRules.displayName(target.createConfigurationContext(english), BANK));
        Configuration persian = new Configuration(target.getResources().getConfiguration());
        persian.setLocale(new Locale("fa"));
        assertEquals("مفید — جت کارت / حامی",
            BankRules.displayName(target.createConfigurationContext(persian), BANK));
    }

    @Test public void exactFixtures_readBalancesSignedMovementsAndJalaliDates_withoutNumericAccount() {
        assertFixture(DEPOSIT, DEPOSIT_AMOUNT, DEPOSIT_BALANCE, persian(1402, 4, 26, 9, 41));
        assertFixture(WITHDRAWAL, WITHDRAWAL_AMOUNT, WITHDRAWAL_BALANCE,
            persian(1402, 4, 27, 15, 8));
    }

    @Test public void persianDigitsArabicLettersAndCrLf_preserveBothMovements() {
        assertFixture(alternate(DEPOSIT), DEPOSIT_AMOUNT, DEPOSIT_BALANCE,
            persian(1402, 4, 26, 9, 41));
        assertFixture(alternate(WITHDRAWAL), WITHDRAWAL_AMOUNT, WITHDRAWAL_BALANCE,
            persian(1402, 4, 27, 15, 8));
    }

    @Test public void hamiAndBrokerageHeader_allowSurroundingWhitespace() {
        String spaced = DEPOSIT.replace("کارگزاری مفید\n", "  کارگزاری   مفید  \r\n")
            .replace("به حساب حامی\n", "  به   حساب   حامی  \r\n");
        assertTrue(BankRules.acceptsBalance(BANK, spaced));
        assertEquals(DEPOSIT_BALANCE, BalanceData.extract(BANK, spaced));
        assertNotNull(BalanceData.parseMovement(BANK, SENDER, spaced, ARRIVAL, false, 0));
    }

    @Test public void scopeGuard_rejectsOtherProductsMissingLinesAndHamiSuffixes() {
        for (String body : new String[]{
                DEPOSIT.replace("به حساب حامی", "به حساب معاملات"),
                WITHDRAWAL.replace("از حساب حامی", "از حساب معاملات"),
                DEPOSIT.replace("کارگزاری مفید\n", ""),
                DEPOSIT.replace("به حساب حامی\n", ""),
                DEPOSIT.replace("به حساب حامی", "به حساب حامیان"),
                DEPOSIT.replace("به حساب حامی", "اطلاعیه به حساب حامی"),
                DEPOSIT.replace("کارگزاری مفید", "پیام کارگزاری مفید")}) {
            assertFalse(body, BankRules.acceptsBalance(BANK, body));
            assertEquals(body, -1L, BalanceData.extract(BANK, body));
            assertNull(body, BalanceData.parseMovement(BANK, SENDER, body, ARRIVAL, false, 0));
            assertNull(body, BalanceData.parseMovement(BANK, SENDER, body, ARRIVAL,
                true, DEPOSIT_BALANCE));
        }
        // Scope is specific to Mofid; ordinary bank balance messages keep their existing behavior.
        assertEquals(1_000L, BalanceData.extract("Saman", "balance 1,000"));
    }

    @Test public void otpAndMissingBalance_areNotSettledMovements() {
        for (String body : new String[]{DEPOSIT + "\nرمز پویا: 123456",
                DEPOSIT.replace("مانده: 83,146,702 ریال\n", ""),
                WITHDRAWAL.replace("مانده: 83,074,088 ریال\n", "")}) {
            assertEquals(-1L, BalanceData.extract(BANK, body));
            assertNull(BalanceData.extractTransaction(body));
            assertNull(BalanceData.parseMovement(BANK, SENDER, body, ARRIVAL, false, 0));
        }
    }

    @Test public void unsupportedProduct_diagnosticsKeepItKnownButUnparsed() {
        String otherProduct = DEPOSIT.replace("به حساب حامی", "به حساب معاملات");
        ScanDiagnostics.Summary summary = ScanDiagnostics.analyze(Arrays.asList(
            new Object[]{SENDER, DEPOSIT, ARRIVAL},
            new Object[]{SENDER, otherProduct, ARRIVAL + 1_000L}));
        assertEquals(2, summary.messages);
        assertEquals(1, summary.parsedMessages);
        assertEquals(1, summary.unparsedSendersMessages);
        assertEquals(0, summary.unknownSendersMessages);
        assertEquals(1, summary.unparsedSenders.size());
        ScanDiagnostics.SenderHit hit = summary.unparsedSenders.get(0);
        assertEquals(BANK, hit.bank);
        assertEquals(SENDER, hit.sender);
        assertEquals(otherProduct, hit.stored.get(0).body);
    }

    @Test public void providerScans_keepNewestHamiBalanceAndTwoMovements_withoutDuplicates()
            throws Exception {
        prepareProvider();
        seed(SENDER, DEPOSIT, ARRIVAL);
        seed(SENDER, WITHDRAWAL, ARRIVAL + 1_000L);
        // A later unsupported product must not win either the balance snapshot or reconciliation.
        seed(SENDER, DEPOSIT.replace("به حساب حامی", "به حساب معاملات")
            .replace("1402/04/26", "1402/04/28"), ARRIVAL + 2_000L);

        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        assertEquals(1, BalanceData.scanSms(context, saved));
        assertNewestBalance(saved);
        assertNewestBalance(BalanceData.read(context));
        assertEquals(2, BalanceData.scanHistory(context));
        assertHistory();

        assertEquals(0, BalanceData.scanSms(context, saved));
        assertEquals(0, BalanceData.scanHistory(context));
        assertNewestBalance(saved);
        assertHistory();
    }

    @Test public void olderInboxFixtures_areDiscoveredAutomaticallyAfterRulesVersionChange()
            throws Exception {
        prepareProvider();
        // Establish both scan cursors beyond the fixtures, as an install without Mofid support did.
        seed("unknown-mofid-test", "not a bank message", ARRIVAL + 10_000L);
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        assertEquals(0, BalanceData.scanSms(context, saved));
        assertEquals(0, BalanceData.scanHistory(context));
        seed(SENDER, DEPOSIT, ARRIVAL);
        seed(SENDER, WITHDRAWAL, ARRIVAL + 1_000L);
        assertTrue(prefs().edit()
            .putInt(BalanceData.KEY_RULES_VERSION, BankRules.VERSION - 1)
            .putInt(BalanceData.KEY_HISTORY_RULES_VERSION, BalanceData.HISTORY_RULES_VERSION - 1)
            .commit());

        assertEquals(1, BalanceData.scanSms(context, saved));
        assertEquals(2, BalanceData.scanHistory(context));
        assertNewestBalance(saved);
        assertHistory();
        assertEquals(BankRules.VERSION, prefs().getInt(BalanceData.KEY_RULES_VERSION, -1));
        assertEquals(BalanceData.HISTORY_RULES_VERSION,
            prefs().getInt(BalanceData.KEY_HISTORY_RULES_VERSION, -1));
        assertEquals(0, BalanceData.scanHistory(context));
        assertHistory();
    }

    @Test public void interleavedOtherProduct_doesNotDisruptReconciliationOrNextDelta()
            throws Exception {
        prepareProvider();
        // Arrival order is B, other product, A; the unique settled Hami chain is A -> B.
        seed(SENDER, "کارگزاری مفید\nبرداشت مبلغ 50 ریال\nاز حساب حامی\nمانده: 1,050 ریال",
            ARRIVAL);
        seed(SENDER, "کارگزاری مفید\nواریز مبلغ 200 ریال\nبه حساب معاملات\nمانده: 5,000 ریال",
            ARRIVAL + 1_000L);
        seed(SENDER, "کارگزاری مفید\nواریز مبلغ 100 ریال\nبه حساب حامی\nمانده: 1,100 ریال",
            ARRIVAL + 2_000L);
        assertEquals(2, BalanceData.scanHistory(context));

        // No stated movement amount: the fallback must start at the true chain tail, 1,050.
        seed(SENDER, "کارگزاری مفید\nبرداشت انجام شد\nاز حساب حامی\nمانده: 1,000 ریال",
            ARRIVAL + 3_000L);
        assertEquals(1, BalanceData.scanHistory(context));
        List<Transaction> transactions = BalanceData.readTransactions(context);
        assertEquals(3, transactions.size());
        Transaction delta = null;
        for (Transaction transaction : transactions) {
            if (Long.valueOf(1_000L).equals(transaction.balance)) delta = transaction;
        }
        assertNotNull(delta);
        assertEquals(-50L, delta.amount);
        assertEquals(Long.valueOf(1_000L), delta.balance);
        assertEquals(0, BalanceData.scanHistory(context));
        assertEquals(3, BalanceData.readTransactions(context).size());
    }

    private void assertFixture(String body, long amount, long balance, long expectedDate) {
        assertTrue(BankRules.acceptsBalance(BANK, body));
        assertEquals(balance, BalanceData.extract(BANK, body));
        assertEquals(Long.valueOf(amount), BalanceData.extractTransaction(body));
        assertNull(BankRules.extractAccount(BANK, body));
        assertNull(BankRules.extractReason(BANK, body));
        assertNull(BankRules.extractChannel(BANK, body));
        long event = MessageDate.eventTime(body, ARRIVAL, BankRules.calendar(BANK));
        assertEquals(expectedDate, event);
        Transaction transaction = BalanceData.parseMovement(BANK, SENDER, body, event, false, 0);
        assertNotNull(transaction);
        assertEquals(BANK, transaction.bank);
        assertEquals(amount, transaction.amount);
        assertEquals(Long.valueOf(balance), transaction.balance);
        assertEquals(expectedDate, transaction.date);
        assertNull(transaction.account);
    }

    private void assertNewestBalance(LinkedHashMap<String, Bank> saved) {
        assertEquals(1, saved.size());
        Bank bank = saved.get(BANK);
        assertNotNull(bank);
        assertEquals(BANK, bank.name);
        assertEquals(WITHDRAWAL_BALANCE, bank.amount);
        assertEquals(persian(1402, 4, 27, 15, 8), bank.date);
        assertNull(bank.account);
    }

    private void assertHistory() {
        List<Transaction> transactions = BalanceData.readTransactions(context);
        assertEquals(2, transactions.size());
        Transaction withdrawal = transactions.get(0);
        Transaction deposit = transactions.get(1);
        assertEquals(WITHDRAWAL_AMOUNT, withdrawal.amount);
        assertEquals(Long.valueOf(WITHDRAWAL_BALANCE), withdrawal.balance);
        assertEquals(persian(1402, 4, 27, 15, 8), withdrawal.date);
        assertEquals(DEPOSIT_AMOUNT, deposit.amount);
        assertEquals(Long.valueOf(DEPOSIT_BALANCE), deposit.balance);
        assertEquals(persian(1402, 4, 26, 9, 41), deposit.date);
        for (Transaction transaction : transactions) {
            assertEquals(BANK, transaction.bank);
            assertNull(transaction.account);
        }
        assertTrue(BalanceData.readReasons(context).isEmpty());
        assertTrue(BalanceData.readChannels(context).isEmpty());
    }

    private static String alternate(String body) {
        StringBuilder result = new StringBuilder();
        for (char c : body.toCharArray()) {
            result.append(c >= '0' && c <= '9' ? (char) ('۰' + c - '0') : c);
        }
        return result.toString().replace('ک', 'ك').replace('ی', 'ي')
            .replace(',', '٬').replace("\n", "\r\n");
    }

    @After public void tearDown() throws Exception {
        if (!providerTouched) return;
        List<Throwable> failures = new ArrayList<>();
        try {
            clearInbox();
        } catch (Throwable failure) {
            failures.add(failure);
        }
        try {
            BalanceData.reset(context, true);
        } catch (Throwable failure) {
            failures.add(failure);
        }
        try {
            assertTrue("display preferences did not clear", prefs().edit().clear().commit());
        } catch (Throwable failure) {
            failures.add(failure);
        } finally {
            if (shellIdentityAdopted) {
                try {
                    InstrumentationRegistry.getInstrumentation().getUiAutomation()
                        .dropShellPermissionIdentity();
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }
        }
        MultipleFailureException.assertEmpty(failures);
    }

    private void prepareProvider() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNotNull(context);
        providerTouched = true;
        BalanceData.reset(context, true);
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .adoptShellPermissionIdentity(android.Manifest.permission.READ_SMS);
        shellIdentityAdopted = true;
        exec("pm grant " + context.getPackageName() + " android.permission.READ_SMS");
        exec("cmd appops set " + context.getPackageName()
            + " android:read_restricted_messages allow");
        exec("cmd appops set com.ashkanrafiee.smsinject WRITE_SMS allow");
        assertTrue("display preferences did not clear", prefs().edit().clear().commit());
        clearInbox();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE);
    }

    private void seed(String sender, String body, long base) throws Exception {
        String body64 = android.util.Base64.encodeToString(
            body.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        exec("am broadcast -n com.ashkanrafiee.smsinject/.SeedReceiver"
            + " -a com.ashkanrafiee.smsinject.SEED"
            + " -e sender " + sender + " -e body64 " + body64 + " -e base " + base);
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
                assertNotNull("could not query the SMS inbox after CLEAR", cursor);
                if (!cursor.moveToFirst()) return;
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
