package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The currency-aware ledger identity and row format. A non-IRR row names its currency in both the
 * storage key and the JSON; every IRR row keeps the exact pre-currency form, so all balances,
 * transactions and backups written before currencies existed read back unchanged. Uses no inbox or
 * preferences.
 */
@RunWith(AndroidJUnit4.class)
public class CurrencyLedgerTest {

    @Test public void irrKey_isTheLegacyOneAndTwoPartForm() {
        assertEquals("BankMelli", BalanceData.storageKey("BankMelli", null, BalanceData.IRR));
        assertEquals("BankMelli", BalanceData.storageKey("BankMelli", null));
        assertEquals("BankMelli|910251846",
            BalanceData.storageKey("BankMelli", "910251846", BalanceData.IRR));
        assertEquals("BankMelli|910251846", BalanceData.storageKey("BankMelli", "910251846"));
        // The legacy forms read back as IRR.
        assertEquals("IRR", BalanceData.currencyOfKey("BankMelli"));
        assertEquals("IRR", BalanceData.currencyOfKey("BankMelli|910251846"));
    }

    @Test public void foreignKey_namesTheCurrencyAndParsesBack() {
        String withAccount = BalanceData.storageKey("BankMelli", "910251846", "USD");
        assertEquals("BankMelli|910251846|USD", withAccount);
        assertEquals("BankMelli", BalanceData.bankOfKey(withAccount));
        assertEquals("USD", BalanceData.currencyOfKey(withAccount));

        // An account-less foreign ledger keeps the empty account segment, so the three parts stay
        // unambiguous against the two-part legacy form.
        String noAccount = BalanceData.storageKey("BankMelli", null, "USD");
        assertEquals("BankMelli||USD", noAccount);
        assertEquals("BankMelli", BalanceData.bankOfKey(noAccount));
        assertEquals("USD", BalanceData.currencyOfKey(noAccount));

        LinkedHashMap<String, Bank> parsed = BalanceData.deserialize(
            "{\"BankMelli||USD\":{\"amount\":5,\"date\":1,\"sender\":\"s\"}}");
        Bank b = parsed.get("BankMelli||USD");
        assertEquals("BankMelli", b.name);
        assertNull(b.account);
        assertEquals("USD", b.currency);
    }

    @Test public void balanceSerialization_omitsCurForIrrAndWritesItOtherwise() throws Exception {
        LinkedHashMap<String, Bank> irr = new LinkedHashMap<>();
        irr.put("BankMelli|910251846",
            new Bank("BankMelli", 10, 1, "s", "910251846"));
        String irrJson = BalanceData.serialize(irr);
        assertFalse(irrJson.contains("cur"));

        LinkedHashMap<String, Bank> mixed = new LinkedHashMap<>();
        mixed.put("BankMelli|910251846|USD",
            new Bank("BankMelli", 10, 1, "s", "910251846", "USD"));
        String usdJson = BalanceData.serialize(mixed);
        assertTrue(usdJson.contains("\"cur\":\"USD\""));
        assertEquals("USD", BalanceData.deserialize(usdJson).get("BankMelli|910251846|USD").currency);

        // A legacy JSON row with no currency field is the rial default.
        LinkedHashMap<String, Bank> legacy = BalanceData.deserialize(
            "{\"BankMelli|910251846\":{\"amount\":10,\"date\":1,\"sender\":\"s\","
                + "\"account\":\"910251846\"}}");
        Bank b = legacy.get("BankMelli|910251846");
        assertEquals("IRR", b.currency);
        assertEquals("910251846", b.account);
    }

    @Test public void transactionSerialization_omitsCurForIrrAndWritesItOtherwise() throws Exception {
        String irrJson = BalanceData.serializeTransactions(
            List.of(new Transaction("BankMelli", "910251846", 1, 100, 50L, "sig", "content")));
        assertFalse(irrJson.contains("cur"));
        assertEquals("IRR", BalanceData.deserializeTransactions(irrJson).get(0).currency);

        String usdJson = BalanceData.serializeTransactions(
            List.of(new Transaction("BankMelli", "910251846", 1, 100, 50L, "sig", "content", "USD")));
        assertTrue(usdJson.contains("\"cur\":\"USD\""));
        Transaction t = BalanceData.deserializeTransactions(usdJson).get(0);
        assertEquals("USD", t.currency);
        assertEquals(100L, t.amount);
    }

    @Test public void rowTypes_defaultToIrr() {
        assertEquals("IRR", new Bank("b", 1, 1, "s").currency);
        assertEquals("IRR", new Bank("b", 1, 1, "s", "acct").currency);
        assertEquals("IRR", new Bank("b", 1, 1, "s", "acct", null).currency);
        assertEquals("IRR", new Transaction("b", 1, 1).currency);
        assertEquals("IRR", new Transaction("b", "acct", 1, 1, null, null, null).currency);
        assertEquals("IRR", new Transaction("b", "acct", 1, 1, null, null, null, null).currency);
        assertEquals("USD", new Transaction("b", "acct", 1, 1, null, null, null, "USD").currency);
    }

    @Test public void movementIdentity_foldsCurrencyOnlyWhenNotIrr() {
        assertEquals("b|1|100|acct",
            BalanceData.txIdentityKey(new Transaction("b", "acct", 1, 100, null)));
        assertEquals("b|1|100|acct|USD",
            BalanceData.txIdentityKey(new Transaction("b", "acct", 1, 100, null, null, null, "USD")));
    }

    @Test public void aConversionKeepsTheIdentityOfItsSpentCurrency() {
        // The same message re-read under different rules must still be the same message, so the
        // dedup key follows the money that was actually spent, not the ledger it settled into.
        Transaction t = new Transaction("b", "acct", 1, 100, 900L, null, "c", "USD", "JOD");
        assertEquals("b|1|100|acct|USD", BalanceData.txIdentityKey(t));
    }

    @Test public void aConversionIsFiledUnderTheLedgerItSettledIn() {
        // A dollar purchase spends the dinar card's balance, so it belongs to the same ledger as
        // that card's balance row -- the two are the same account and the same sum of money. The
        // balance currency is the ledger; the spent currency is only what the movement was.
        Transaction t = new Transaction("b", "acct", 1, 100, 900L, null, "c", "USD", "JOD");
        assertEquals(BalanceData.storageKey(t.bank, t.account, t.balanceCurrency),
            BalanceData.storageKey(t.bank, t.account, "JOD"));
        assertTrue(t.converted());
    }

    @Test public void windowRoundTrip_keepsBothCurrenciesAndFoldsIrr() throws Exception {
        // The recent-movements window is the only reconciled state that survives a restart, so it
        // has to carry the currencies too: a window that forgot them would chain a dollar amount
        // onto a dinar balance on the next scan.
        Map<String, List<Reconcile.Entry>> windows = new LinkedHashMap<>();
        List<Reconcile.Entry> entries = new ArrayList<>();
        entries.add(new Reconcile.Entry(1, -100, 900, "sig-1"));
        entries.add(new Reconcile.Entry(2, -50, 850, "sig-2", "USD", "JOD"));
        windows.put("b|acct|JOD", entries);

        String json = BalanceData.serializeRecentMovements(windows);
        // The rial entry is written exactly as it always was, so an Iranian device sees the same
        // bytes it always has; only a window that actually holds another currency grows a field.
        assertTrue(json.contains("{\"d\":1,\"a\":-100,\"b\":900,\"s\":\"sig-1\"}"));
        assertTrue(json.contains("\"c\":\"USD\""));
        assertTrue(json.contains("\"bc\":\"JOD\""));

        List<Reconcile.Entry> back = BalanceData.deserializeRecentMovements(json).get("b|acct|JOD");
        assertEquals(2, back.size());
        assertEquals(BalanceData.IRR, back.get(0).movementCurrency);
        assertEquals(BalanceData.IRR, back.get(0).balanceCurrency);
        assertEquals("USD", back.get(1).movementCurrency);
        assertEquals("JOD", back.get(1).balanceCurrency);
    }

    @Test public void windowRoundTrip_readsAWindowWrittenBeforeCurrencies() {
        // Every window that exists on a user's device was written by a build with no currency
        // fields, so its entries must come back as the rial rather than as null.
        List<Reconcile.Entry> back = BalanceData.deserializeRecentMovements(
            "{\"b\":[{\"d\":1,\"a\":-100,\"b\":900,\"s\":\"sig-1\"}]}").get("b");
        assertEquals(1, back.size());
        assertEquals(BalanceData.IRR, back.get(0).movementCurrency);
        assertEquals(BalanceData.IRR, back.get(0).balanceCurrency);
    }
}
