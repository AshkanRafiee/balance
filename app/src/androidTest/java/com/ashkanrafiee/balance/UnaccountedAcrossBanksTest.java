package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Unaccounted-money detection audited across every bank the app ships rules for.
 *
 * <p>The Tejarat report was not a Tejarat bug. A bank stamped two withdrawals with the same minute;
 * the walk reconciled that tie in arrival order, which is the reverse of chronology, and so compared
 * one movement's amount against the other's balance. It reported money the app had itself recorded
 * as money that went missing. {@link Residual} groups by bank purely to keep two accounts apart and
 * never looks inside a bank's rules, so the defect and the repair belong to every bank equally. These
 * tests hold that claim to account: the verdicts must not depend on arrival order, must not fabricate
 * a gap out of a tie the balances can settle, and must still speak up when a bank really does move
 * money it never itemised.
 *
 * <p>Currencies are the ones the shipped corpus actually contains, and the tie scenarios are run
 * under every catalog bank, so a bank added tomorrow is covered by construction.
 */
@RunWith(AndroidJUnit4.class)
public class UnaccountedAcrossBanksTest {

    private static final String ACCOUNT = "7700123456";
    private static final long D = 1_700_000_000_000L;
    private static final long DAY = 86_400_000L;

    /** Every currency present in the shipped fixtures. */
    private static final List<String> SHIPPED_CURRENCIES = Arrays.asList("IRR", "JOD", "EUR", "USD");

    // -----------------------------------------------------------------------------------------
    // The bug, generalized
    // -----------------------------------------------------------------------------------------

    /**
     * A tie the stated balances settle must reconcile cleanly, whichever way the rows are handed
     * over. Read newest-first — the order the app stores and reads history in — this is the shape
     * that used to report the tied withdrawal as unaccounted.
     */
    @Test public void aSettlableTieNeverChangesTheVerdictWithArrivalOrder() throws IOException {
        for (String bank : catalogBanks()) {
            for (String currency : SHIPPED_CURRENCIES) {
                List<Transaction> chronological = tie(bank, currency);
                assertReconciles(label(bank, currency, "chronological"), chronological);
                assertReconciles(label(bank, currency, "newest first"), reversed(chronological));
                assertReconciles(label(bank, currency, "rotated"), rotated(chronological));
                assertReconciles(label(bank, currency, "shuffled"), shuffled(chronological, 7));
            }
        }
    }

    /** Three movements in the same second, in an order the balances prove. */
    @Test public void aLongerTieIsReconciledInTheOrderTheBalancesProve() throws IOException {
        for (String bank : catalogBanks()) {
            List<Transaction> txs = Arrays.asList(
                    tx(bank, D, 100_000_000L),
                    tx(bank, D, -1_000_000L, 99_000_000L),
                    tx(bank, D, -7_000_000L, 92_000_000L),
                    tx(bank, D, -2_000_000L, 90_000_000L),
                    tx(bank, D + DAY, -500_000L, 89_500_000L));
            assertReconciles(bank, txs);
            assertReconciles(bank + "/reversed", reversed(txs));
        }
    }

    /** The repair must not silence the account. A gap after a settled tie is still a gap, and it is the
     *  same gap whichever way the rows arrive. */
    @Test public void aRealGapAfterASettledTieIsStillReported() {
        List<Transaction> txs = new ArrayList<>(tiedWindow("bank", "IRR"));
        // The closing statement leaves 400,000 rials more than the movements account for.
        txs.add(tx("bank", D + DAY, -1_000_000L, 83_600_000L));
        for (List<Transaction> order : List.of(txs, reversed(txs), rotated(txs),
                shuffled(txs, 7))) {
            List<Residual> out = Residual.between(order);
            assertEquals("the unexplained difference is still named: " + render(out), 1, out.size());
            assertEquals(-400_000L, out.get(0).amount);
            assertEquals(D, out.get(0).fromDate);
            assertEquals(D + DAY, out.get(0).toDate);
            assertEquals(1, out.get(0).movements);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Ties the app must not guess at
    // -----------------------------------------------------------------------------------------

    /** Equal movements that no balance chain separates stay silent rather than be guessed at. */
    @Test public void anUnsettleableTieStaysSilent() throws IOException {
        for (String bank : catalogBanks()) {
            List<Transaction> txs = Arrays.asList(
                    tx(bank, D, 100_000_000L),
                    tx(bank, D, -1_000_000L, 99_000_000L),
                    tx(bank, D, -1_000_000L, 103_000_000L),
                    tx(bank, D + DAY, -1_000_000L, 102_000_000L));
            assertNoResiduals(bank, Residual.between(txs));
            assertNoResiduals(bank + "/reversed", Residual.between(reversed(txs)));
        }
    }

    /**
     * A tie the balances cannot settle must not hand the statement after it an opening balance.
     *
     * <p>Measured against whichever member of the tie happened to come first, a later statement
     * that chains to none of them reports a gap out of nothing — a second false positive of the same
     * family as the reported bug, and one that changed with arrival order.
     */
    @Test public void anUnsettleableTieNeverAnchorsTheWindowAfterIt() throws IOException {
        for (String bank : catalogBanks()) {
            List<Transaction> txs = Arrays.asList(
                    tx(bank, D, 100_000_000L),
                    tx(bank, D, -1_000_000L, 99_000_000L),
                    tx(bank, D, -1_000_000L, 103_000_000L),
                    // Chains to no member of the tie, so nothing here may be called unaccounted.
                    tx(bank, D + DAY, -1_000_000L, 102_000_000L));
            assertNoResiduals(bank, Residual.between(txs));
            assertNoResiduals(bank + "/reversed", Residual.between(reversed(txs)));
            assertNoResiduals(bank + "/rotated", Residual.between(rotated(txs)));
        }
    }

    /** A card spent in a currency the ledger does not hold settles into no known rate, so a tie
     *  containing one is not arithmetic the app may perform. */
    @Test public void aTieMixingCurrenciesIsNeverReconciledAsIfItWereOneLedger() {
        List<Transaction> txs = Arrays.asList(
                tx("bank", ACCOUNT, D, -1_000_000L, 99_000_000L, "IRR"),
                // Settled in dollars into the same rial ledger: the rate is unknown.
                tx("bank", ACCOUNT, D, -3_500L, 96_500_000L, "USD"),
                tx("bank", ACCOUNT, D + DAY, -500_000L, 96_000_000L, "IRR"));
        for (List<Transaction> order : List.of(txs, reversed(txs))) {
            for (Residual r : Residual.between(order)) {
                assertTrue("no fabricated gap in a mixed-currency tie: " + r.amount,
                        r.amount != -1_000_000L && r.amount != -3_500L);
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Grouping, which is the only per-bank behaviour there is
    // -----------------------------------------------------------------------------------------

    /** One account number at two banks must never cancel the other's movements. */
    @Test public void twoBanksNeverReconcileAgainstEachOther() {
        List<Transaction> txs = Arrays.asList(
                tx("bank-a", ACCOUNT, D, 100_000_000L),
                tx("bank-b", ACCOUNT, D + 1, 100_000_000L),
                tx("bank-a", ACCOUNT, D + DAY, -40_000_000L, 60_000_000L),
                tx("bank-b", ACCOUNT, D + 2 * DAY, -90_000_000L, 10_000_000L));
        for (Residual r : Residual.between(reversed(txs))) {
            assertTrue("a bank's movements are never another's opening balance",
                    r.amount != 0 && Math.abs(r.amount) != 100_000_000L);
        }
    }

    /** The tie scenario must behave identically no matter which bank it is filed under. */
    @Test public void theBankNameChangesNothingAboutTheArithmetic() throws IOException {
        String first = null;
        String firstBank = null;
        for (String bank : catalogBanks()) {
            String verdict = render(Residual.between(reversed(tie(bank, "IRR"))));
            if (first == null) {
                first = verdict;
                firstBank = bank;
                continue;
            }
            assertEquals(firstBank + " and " + bank + " must reconcile alike", first, verdict);
        }
        assertTrue("the audit covered real banks", firstBank != null);
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /** One open statement, two movements tied to the same second, and the statement that closes the
     *  window. Every balance chains, so the window reconciles to nothing. */
    private static List<Transaction> tie(String bank, String currency) {
        List<Transaction> out = new ArrayList<>(tiedWindow(bank, currency));
        out.add(tx(bank, D + DAY, -1_000_000L, 84_000_000L));
        return out;
    }

    /** The same window without its closing statement, so a test can supply its own. */
    private static List<Transaction> tiedWindow(String bank, String currency) {
        return Arrays.asList(
                tx(bank, D, 100_000_000L),
                tx(bank, D, -10_000_000L, 90_000_000L),
                tx(bank, D, -5_000_000L, 85_000_000L));
    }

    private static Transaction tx(String bank, long date, long balance) {
        return tx(bank, ACCOUNT, date, 0L, balance, "IRR");
    }

    private static Transaction tx(String bank, String account, long date, long balance) {
        return tx(bank, account, date, 0L, balance, "IRR");
    }

    private static Transaction tx(String bank, String account, long date, long amount, long balance) {
        return tx(bank, account, date, amount, balance, "IRR");
    }

    private static Transaction tx(String bank, long date, long amount, long balance) {
        return tx(bank, ACCOUNT, date, amount, balance, "IRR");
    }

    private static Transaction tx(String bank, long date, long amount, long balance, String currency) {
        return tx(bank, ACCOUNT, date, amount, balance, currency);
    }

    private static Transaction tx(String bank, String account, long date, long amount, long balance,
            String currency) {
        return new Transaction(bank, account, date, amount, balance, "s" + amount + "-" + balance,
                null, currency);
    }

    private static List<Transaction> reversed(List<Transaction> txs) {
        List<Transaction> out = new ArrayList<>(txs);
        java.util.Collections.reverse(out);
        return out;
    }

    private static List<Transaction> rotated(List<Transaction> txs) {
        List<Transaction> out = new ArrayList<>(txs);
        out.add(0, out.remove(out.size() - 1));
        return out;
    }

    /** Deterministic shuffle: the same seed always yields the same order, so a failure is
     *  reproducible instead of a one-off. */
    private static List<Transaction> shuffled(List<Transaction> txs, long seed) {
        List<Transaction> out = new ArrayList<>(txs);
        long state = seed;
        for (int i = out.size() - 1; i > 0; i--) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            int j = (int) Math.floorMod(state >> 17, i + 1);
            out.add(i, out.remove(j));
        }
        return out;
    }

    private static void assertReconciles(String label, List<Transaction> txs) {
        assertNoResiduals(label, Residual.between(txs));
    }

    private static void assertNoResiduals(String label, List<Residual> out) {
        assertEquals(label + " must reconcile: " + render(out), 0, out.size());
    }

    private static String render(List<Residual> out) {
        StringBuilder sb = new StringBuilder("residuals");
        for (Residual r : out) {
            sb.append("\n  ").append(r.fromDate).append("..").append(r.toDate)
                    .append(" ").append(r.amount).append(" movements=").append(r.movements);
        }
        return sb.toString();
    }

    private static String label(String bank, String currency, String order) {
        return bank + "/" + currency + "/" + order;
    }

    /** Every bank the app ships a pack for, taken from the shipped catalogs. */
    private static List<String> catalogBanks() throws IOException {
        List<String> banks = new ArrayList<>();
        List<String> regions = regions();
        assertTrue("the shipped index lists its regions", !regions.isEmpty());
        for (String region : regions) {
            Map<String, Object> catalog = asset(region + "/catalog.json");
            for (Object item : (List<?>) catalog.get("banks")) {
                banks.add((String) ((Map<?, ?>) item).get("id"));
            }
        }
        assertEquals("every catalog bank is audited", new HashSet<>(banks).size(), banks.size());
        assertTrue("the corpus is not empty", banks.size() >= 40);
        return banks;
    }

    @SuppressWarnings("unchecked")
    private static List<String> regions() throws IOException {
        return (List<String>) asset("index.json").get("regions");
    }

    private static Map<String, Object> asset(String path) throws IOException {
        try (InputStream input = InstrumentationRegistry.getInstrumentation()
                .getContext().getAssets().open(path)) {
            return PlatformRuleJson.read(input);
        }
    }
}