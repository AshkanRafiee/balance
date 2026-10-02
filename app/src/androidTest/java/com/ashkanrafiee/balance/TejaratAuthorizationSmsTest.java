package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Unaccounted-money detection on a real Tejarat day.
 *
 * <p>Every message below is verbatim from one account, with the account number masked except where
 * the case needs it. Two separate things were proven wrong here and both are locked down:
 *
 * <ul>
 *   <li>The bank stamped two withdrawals at the same minute, {@code 11:51}. Reordering them as they
 *       arrived subtracted one movement's amount from the other's balance and reported
 *       {@code -50,010,000} rials — a movement the app had itself recorded — as money that went
 *       missing. The balances chain exactly, so the real order is provable.
 *   <li>The authorization codes that precede each transfer state the amount too, and they must never
 *       count as movements. They carry a one-off code, no balance, and no {@code مانده}.
 * </ul>
 *
 * <p>The 450-rial case is the other direction: the bank really did move 450 rials that no message
 * itemised, and the app is right to say so.
 */
@RunWith(AndroidJUnit4.class)
public class TejaratAuthorizationSmsTest {

    private static final String SENDER = "TejaratBank";
    private static final String ACCOUNT = "0135399698887";

    private Context ctx;

    @Before public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(android.Manifest.permission.READ_SMS);
        exec("pm grant " + ctx.getPackageName() + " android.permission.READ_SMS");
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
        FinancialTestStore.wipe(ctx);
        clearInbox();
    }

    @After public void tearDown() throws Exception { clearInbox(); }

    // ---- verbatim Tejarat messages ----------------------------------------------------------

    private static String salaryDeposit() {
        return "*بانک تجارت* \n"
                + "حساب: " + ACCOUNT + " \n"
                + "واریز حقوق : 3,195,890,915 ریال \n"
                + "از طريق: شعبه  \n"
                + "کدشعبه: 2080\n"
                + "مانده: 3,197,100,203 ریال \n"
                + "1405/06/31\n"
                + "11:23";
    }

    private static String transferCode50m() {
        return "تراکنش حسابی\n"
                + "مبدا  87*****0135\n"
                + "مقصد  950560611828007334905201\n"
                + "مبلغ 50,000,000\n"
                + "رمز 60163589\n"
                + "تاریخ 1405/06/31";
    }

    private static String transferCode25m() {
        return "تراکنش حسابی\n"
                + "مبدا  87*****0135\n"
                + "مقصد  450190000000207676959009\n"
                + "مبلغ 25,000,000\n"
                + "رمز 88371937\n"
                + "تاریخ 1405/06/31";
    }

    private static String withdrawalAt11_47() {
        return settlement("11:47", "18,190,000", "سامانه پل (پرداخت لحظه ای)", "2,979,102,303");
    }

    /** First of the two withdrawals the bank stamped {@code 11:51}. */
    private static String withdrawal50m() {
        return settlement("11:51", "50,010,000", "سامانه پل (پرداخت لحظه ای)", "2,929,092,303");
    }

    /** Second of the two withdrawals the bank stamped {@code 11:51}, 40 seconds later. */
    private static String withdrawal25m() {
        return settlement("11:51", "25,008,000", "سامانه پل (پرداخت لحظه ای)", "2,904,084,303");
    }

    private static String withdrawalAt14_42() {
        return settlement("14:42", "27,020,110", "شتاب  ", "2,877,064,193");
    }

    private static String withdrawal2b() {
        return settlement("14:51", "2,159,000,000", "همراه بانک  ", "664,878,193", "1405/07/01");
    }

    /** Costs 450 rials more than the previous balance implies: a fee no message itemised. */
    private static String withdrawalCharged450Extra() {
        return settlement("15:41", "21,000,000", "پایانه فروش  ", "643,877,743", "1405/07/01");
    }

    private static String settlement(String time, String amount, String via, String balance) {
        return settlement(time, amount, via, balance, "1405/06/31");
    }

    private static String settlement(String time, String amount, String via, String balance,
            String date) {
        return "*بانک تجارت* \n"
                + "حساب: " + ACCOUNT + " \n"
                + "برداشت: " + amount + " ریال \n"
                + "از طريق: " + via + " \n"
                + "مانده: " + balance + " ریال \n"
                + date + "\n"
                + time;
    }

    // ---- the cases --------------------------------------------------------------------------

    /** The reported bug: two real withdrawals in the same minute, each shown as a movement, with the
     *  money of one of them also claimed as unaccounted. */
    @Test public void twoWithdrawalsStampedTheSameMinuteAreNotUnaccounted() throws Exception {
        // The window opens at 11:47 so the case stands on its own: from there every statement
        // chains to the previous one exactly, with the two 11:51 withdrawals the only test.
        seed(withdrawalAt11_47(), 1790065065704L);
        seed(transferCode50m(), 1790065269659L);
        seed(withdrawal50m(), 1790065276759L);
        seed(transferCode25m(), 1790065308029L);
        seed(withdrawal25m(), 1790065316443L);
        seed(withdrawalAt14_42(), 1790075543606L);
        BalanceData.scanHistory(ctx);

        List<Transaction> stored = BalanceData.readTransactions(ctx);
        assertEquals("every seeded statement is stored, authorization codes are not",
                4, stored.size());
        assertTrue("both same-minute withdrawals are real movements",
                hasAmount(stored, -50010000L) && hasAmount(stored, -25008000L));

        List<Residual> residuals = Residual.between(stored);
        assertEquals("a fully chained window is not unaccounted money: " + describeResiduals(residuals),
                0, residuals.size());
    }

    /** An authorization code states the amount before the money moves, so it must not be a
     *  movement: counting it would double the day and produce a false residual the moment the real
     *  settlement lands. */
    @Test public void anAuthorizationCodeIsNotAMovement() throws Exception {
        seed(salaryDeposit(), 1790063604070L);
        seed(transferCode50m(), 1790065269659L);
        seed(withdrawal50m(), 1790065276759L);
        BalanceData.scanHistory(ctx);

        List<Transaction> stored = BalanceData.readTransactions(ctx);
        assertEquals(describeMovements(stored), 2, stored.size());
        assertTrue("the code's amount is not a movement on its own", !hasAmount(stored, -50000000L));
        assertTrue("the settlement is", hasAmount(stored, -50010000L));
    }

    /** The guard against over-correcting: a difference the bank never explained is still reported.
     *  Rounding this away would leave money unexplained and the user none the wiser. */
    @Test public void anUnitemisedFeeIsStillReported() throws Exception {
        seed(withdrawal2b(), 1790162481724L);
        seed(withdrawalCharged450Extra(), 1790165495783L);
        BalanceData.scanHistory(ctx);

        List<Residual> residuals = Residual.between(BalanceData.readTransactions(ctx));
        assertEquals(describeResiduals(residuals), 1, residuals.size());
        assertEquals("the fee is reported as exactly what it is", -450L, residuals.get(0).amount);
        assertEquals(1, residuals.get(0).movements);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static boolean hasAmount(List<Transaction> stored, long amount) {
        for (Transaction t : stored) {
            if (t.amount == amount) return true;
        }
        return false;
    }

    private static String describeMovements(List<Transaction> stored) {
        StringBuilder sb = new StringBuilder("stored:");
        for (Transaction t : stored) {
            sb.append("\n  ").append(t.date).append(" ").append(t.amount)
                    .append(" bal=").append(t.balance);
        }
        return sb.toString();
    }

    private static String describeResiduals(List<Residual> residuals) {
        StringBuilder sb = new StringBuilder("residuals:");
        for (Residual r : residuals) {
            sb.append("\n  ").append(r.fromDate).append("..").append(r.toDate)
                    .append(" ").append(r.amount).append(" movements=").append(r.movements);
        }
        return sb.toString();
    }

    private void exec(String cmd) throws Exception {
        android.os.ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(cmd);
        try (InputStream is = new android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            byte[] buf = new byte[2048];
            while (is.read(buf) >= 0) { }
        }
        Thread.sleep(200);
    }

    private void clearInbox() throws Exception {
        // Clearing is async across processes, so wait until the inbox is actually empty: the next
        // test must never see a leftover row, regardless of device load.
        exec("am broadcast -n com.ashkanrafiee.smsinject/.SeedReceiver -a com.ashkanrafiee.smsinject.CLEAR");
        long deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline) {
            try (android.database.Cursor c = ctx.getContentResolver().query(
                    android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{android.provider.Telephony.Sms._ID},
                    null, null, null)) {
                if (c == null || c.getCount() == 0) return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("inbox never cleared");
    }

    private void seed(String body, long base) throws Exception {
        String b64 = android.util.Base64.encodeToString(body.getBytes(StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP);
        exec("am broadcast -n com.ashkanrafiee.smsinject/.SeedReceiver"
                + " -a com.ashkanrafiee.smsinject.SEED -e sender " + SENDER
                + " -e body64 " + b64 + " -e base " + base);
        awaitMessage(body);
    }

    private void awaitMessage(String body) throws Exception {
        long deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline) {
            try (android.database.Cursor c = ctx.getContentResolver().query(
                    android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{android.provider.Telephony.Sms.ADDRESS, android.provider.Telephony.Sms.BODY},
                    null, null, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        if (SENDER.equals(c.getString(0)) && body.equals(c.getString(1))) return;
                    }
                }
            }
            Thread.sleep(250);
        }
        throw new AssertionError("seeded message never arrived: "
                + body.substring(0, Math.min(40, body.length())));
    }
}