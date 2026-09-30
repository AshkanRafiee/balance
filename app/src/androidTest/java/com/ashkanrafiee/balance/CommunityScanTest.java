package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.util.LinkedHashMap;

/**
 * The end of the road for a community pack: the two messages a user reported through the app's
 * unrecognized-sender form, delivered into the real SMS inbox of a real device, scanned by the
 * real scan, and read out of the real store.
 *
 * <p>Everything below this test says a message can be understood; this one says the app
 * understands it, through the same path a user's own message takes. The messages state what was
 * spent and never what is left, so what comes out is a bank with a movement and no balance --
 * which is the whole point: a card that only ever reports spends is a bank the app can now show
 * instead of one it calls unrecognized.
 *
 * <p>The bodies are the reported messages verbatim, as recorded in the pack's own fixtures; the
 * merchant names were already anonymized by the reporting user.
 */
@RunWith(AndroidJUnit4.class)
public class CommunityScanTest {
    private static final String SPEND =
        "Hai richiesto una spesa di EUR 66,80 alle ore 22:29 del giorno 27/09 con CartaBCC *557 presso RAMEN BAR XXX LE.";
    private static final String PAYMENT =
        "Confermiamo il tuo pagamento di 7,99 EUR del 25/09 alle 06:44 con CartaBCC *557 presso ILIAD ITALIA.";

    private Context ctx;

    @Before public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BalanceData.reset(ctx, true);
        clearInbox();
    }

    @After public void tearDown() throws Exception {
        clearInbox();
        BalanceData.reset(ctx, true);
    }

    @Test public void aReportedSpendLandsAsAMovementWithNoBalance() throws Exception {
        long arrival = System.currentTimeMillis();
        seed("CartaBCC", SPEND, arrival - 60_000);

        LinkedHashMap<String, Bank> banks = new LinkedHashMap<>();
        assertEquals(1, BalanceData.scanSms(ctx, banks));

        // The scan keys its result the way it stores it -- bank, account, currency -- and the
        // currency in that key is the claim: a card that states no balance has no currency but its
        // own, so a rial here would file a euro movement under a rial account.
        assertEquals("one bank, keyed in the currency it spends in",
                java.util.Set.of("CartaBCC||EUR"), banks.keySet());
        Bank card = banks.values().iterator().next();
        assertTrue("the message states a movement", card.movementOnly());
        assertFalse("no message of this bank states a balance", card.balanceReported);
        assertEquals("66,80 EUR is 6680 cents, not 66 and not 668",
            Long.valueOf(-6680L), card.movement);
        assertEquals("the currency is the one the message names", "EUR", card.movementCurrency);
        assertEquals("the card number masked in the message is not an account", null, card.account);
        // The stated date is a day with no year, so the pack resolves it against the moment the
        // message arrived: whichever calendar year puts it closest to the arrival without putting
        // it far in the past. The assertion therefore checks the day and the resolution it claims
        // rather than a pinned year -- the day the message states, at midnight in the sender's own
        // zone, and near enough to the arrival that no other day could have been chosen -- so the
        // test says the same thing on any date it is run.
        java.time.ZoneId rome = java.time.ZoneId.of("Europe/Rome");
        java.time.ZonedDateTime dated = java.time.Instant.ofEpochMilli(card.date)
            .atZone(rome);
        assertEquals("the movement is dated the day the message states", 9, dated.getMonthValue());
        assertEquals("the day the message states", 27, dated.getDayOfMonth());
        assertEquals("resolved to the start of that day in the sender's own zone",
            java.time.LocalTime.MIDNIGHT, dated.toLocalTime());
        assertTrue("and to a day the arrival itself accounts for",
            Math.abs(java.time.Duration.between(java.time.Instant.ofEpochMilli(arrival),
                java.time.Instant.ofEpochMilli(card.date)).toDays()) <= 31);
    }

    @Test public void aReportedPaymentLandsTheSameWayWhenTheAmountFollowsTheCurrency() throws Exception {
        // The second reported message states the currency after the amount rather than before it,
        // so it exercises a different wording of the same fact.
        long arrival = System.currentTimeMillis();
        seed("CartaBCC", PAYMENT, arrival - 60_000);

        LinkedHashMap<String, Bank> banks = new LinkedHashMap<>();
        assertEquals("the reported sender is recognized", 1, BalanceData.scanSms(ctx, banks));

        assertEquals("one bank, keyed in the currency it spends in",
                java.util.Set.of("CartaBCC||EUR"), banks.keySet());
        Bank card = banks.values().iterator().next();
        assertTrue(card.movementOnly());
        assertEquals("7,99 EUR is 799 cents", Long.valueOf(-799L), card.movement);
        assertEquals("EUR", card.movementCurrency);
    }

    @Test public void theTwoWordingsShareOneBank() throws Exception {
        long arrival = System.currentTimeMillis();
        seed("CartaBCC", PAYMENT, arrival - 120_000);
        seed("CartaBCC", SPEND, arrival - 60_000);

        LinkedHashMap<String, Bank> banks = new LinkedHashMap<>();
        assertEquals(1, BalanceData.scanSms(ctx, banks));

        assertEquals("one bank however it words itself",
                java.util.Set.of("CartaBCC||EUR"), banks.keySet());
        Bank card = banks.values().iterator().next();
        // The store keeps the newest movement as what the card is worth right now, and the card is
        // never a balance: two movements and no balance in, one movement and still no balance out.
        assertEquals("the newest movement is the one shown", Long.valueOf(-6680L), card.movement);
        assertTrue("still no balance", card.movementOnly());
    }

    @Test public void aSenderThePackDoesNotCoverIsStillIgnored() throws Exception {
        // The pack is guarded on the reported wording, so a message from the same sender that says
        // something else is not this bank's statement and must not become one of its movements.
        long arrival = System.currentTimeMillis();
        seed("CartaBCC", "CartaBCC: la tua carta e attiva.", arrival - 60_000);

        LinkedHashMap<String, Bank> banks = new LinkedHashMap<>();
        assertEquals(0, BalanceData.scanSms(ctx, banks));
        assertTrue("nothing was invented: " + banks.keySet(), banks.isEmpty());
    }

    private void clearInbox() throws Exception {
        exec("am broadcast -n com.ashkanrafiee.smsinject/.SeedReceiver -a com.ashkanrafiee.smsinject.CLEAR");
        long deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline) {
            try (android.database.Cursor c = ctx.getContentResolver().query(
                    android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{android.provider.Telephony.Sms._ID}, null, null, null)) {
                if (c == null || !c.moveToFirst()) return;
            }
            Thread.sleep(150);
        }
        fail("SMS inbox did not clear in time");
    }

    private void seed(String sender, String body, long base) throws Exception {
        String b64 = android.util.Base64.encodeToString(
            body.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        exec("am broadcast -n com.ashkanrafiee.smsinject/.SeedReceiver -a com.ashkanrafiee.smsinject.SEED"
            + " -e sender " + sender + " -e body64 " + b64 + " -e base " + base);
        long deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline) {
            try (android.database.Cursor c = ctx.getContentResolver().query(
                    android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{android.provider.Telephony.Sms.ADDRESS, android.provider.Telephony.Sms.BODY},
                    null, null, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        if (sender.equals(c.getString(0)) && body.equals(c.getString(1))) return;
                    }
                }
            }
            Thread.sleep(150);
        }
        fail("the seeded message never reached the inbox");
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
}