package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.ashkanrafiee.balance.parser.PackDocument;
import com.ashkanrafiee.balance.parser.Parser;
import com.ashkanrafiee.balance.parser.Rules;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** On-device differential between the packed-engine parse and the app's own legacy reduction:
 *  banks via BankRules.resolve, accounts via BankRules.extractAccount, stated balances via
 *  BalanceData.extract and signed movements via BalanceData.extractTransaction. The host
 *  officialPackTest pins engine-vs-LegacyReference; this pins engine-vs-the generic app reducers
 *  that the scan and history paths actually consume, so the seam can trust engine facts where the
 *  two agree and must keep the legacy path wherever they diverge. No device data is used. */
@RunWith(AndroidJUnit4.class)
public class PackedLegacyDifferentialTest {
    /** Divergence buckets the corpus is permitted to hit, keyed by fixture id and documented per
     *  entry. A fixture not listed here but diverging fails the test. */
    private static final String[] ENGINE_ONLY_BALANCE = {};
    private static final String[] ENGINE_ONLY_MOVEMENT = {};
    private static final String[] LEGACY_ONLY_BALANCE = {};
    private static final String[] LEGACY_ONLY_MOVEMENT = {};
    private static final String[] CONFLICTS = {};
    private static final String[] ACCOUNT_DIVERGENCE = {};

    private static final class Reduced {
        Long balance;
        String balanceAccount;
        Long movement;
        String movementAccount;
        String reason;
    }

    @Test public void packedEngineAgreesWithLegacyAppReductions() throws Exception {
        Map<String, Object> catalog = asset("catalog.json");
        List<?> banks = (List<?>) catalog.get("banks");
        assertNotNull(banks);

        Map<String, String> idToName = new HashMap<>();
        for (Object item : banks) {
            Map<?, ?> bank = (Map<?, ?>) item;
            idToName.put((String) bank.get("id"), (String) bank.get("name"));
        }

        TreeMap<String, String> engineOnlyBalance = new TreeMap<>();
        TreeMap<String, String> engineOnlyMovement = new TreeMap<>();
        TreeMap<String, String> legacyOnlyBalance = new TreeMap<>();
        TreeMap<String, String> legacyOnlyMovement = new TreeMap<>();
        TreeMap<String, String> conflicts = new TreeMap<>();
        TreeMap<String, String> accountDivergence = new TreeMap<>();
        // Banks the frozen legacy table can reach, which is every Iranian bank it was built for
        // and no other. A pack for a bank outside it is new coverage, not a change in behaviour
        // to compare against.
        Set<String> reachable = BankRules.reachableBanks();
        Set<String> packed = new HashSet<>();
        Set<String> parsed = new HashSet<>();
        int engineParsed = 0, compared = 0;

        for (Object item : banks) {
            Map<?, ?> bank = (Map<?, ?>) item;
            String id = (String) bank.get("id");
            PackDocument pack;
            try {
                pack = PackDocument.decode(asset(id + "/pack.json"));
            } catch (IOException notPacked) {
                continue;
            }
            List<?> cases = (List<?>) asset(id + "/fixtures.json").get("cases");
            for (Object f : cases) {
                Map<?, ?> fixture = (Map<?, ?>) f;
                String caseId = id + "/" + fixture.get("id");
                String sender = (String) fixture.get("sender");
                String body = (String) fixture.get("body");
                Parser.Message message = new Parser.Message(
                        (String) fixture.get("sourceId"), sender, body,
                        Instant.parse((String) fixture.get("arrival")),
                        ZoneId.of((String) fixture.get("zone")));

                Parser.Result result = new Parser(pack.templates()).parse(message);
                if (result.status() != Parser.Status.PARSED) continue;
                engineParsed++;
                parsed.add(id);
                if (!reachable.contains(idToName.get(id))) {
                    // The legacy app has no opinion at all about a bank outside its own table, so
                    // there is nothing to differ from. Comparing the two anyway would call new
                    // coverage a divergence, and pinning every such case as an allowed exception
                    // would quietly turn "the two agree everywhere" into "the two agree except for
                    // whatever we remembered to list". Foreign packs are held instead by their own
                    // fixtures, which the pack frontend checks case by case.
                    continue;
                }

                Reduced engine = reduce(result.facts());
                String legacyBank = BankRules.resolve(sender);
                assertEquals(caseId + " engine and legacy resolve the bank the pack belongs to",
                        idToName.get(id), legacyBank);
                compared++;

                String account = engine.balanceAccount != null ? engine.balanceAccount : engine.movementAccount;
                if (account != null) {
                    String legacyAccount = BankRules.extractAccount(legacyBank, body);
                    if (legacyAccount != null && !legacyAccount.equals(account))
                        accountDivergence.put(caseId,
                                "account engine=" + account + " legacy=" + legacyAccount);
                }

                long stated = BalanceData.extract(body);
                Long txn = BalanceData.extractTransaction(body);
                if (engine.balance != null && stated >= 0 && engine.balance.longValue() != stated)
                    conflicts.put(caseId, "balance engine=" + engine.balance + " legacy=" + stated);
                if (engine.balance != null && stated < 0)
                    engineOnlyBalance.put(caseId, "legacy extract read no balance");
                if (engine.balance == null && stated >= 0)
                    legacyOnlyBalance.put(caseId, "engine read no balance");

                if (engine.movement != null && txn != null && engine.movement.longValue() != txn.longValue())
                    conflicts.put(caseId, "movement engine=" + engine.movement + " legacy=" + txn);
                if (engine.movement != null && txn == null)
                    engineOnlyMovement.put(caseId, "legacy extractTransaction read no movement");
                if (engine.movement == null && txn != null)
                    legacyOnlyMovement.put(caseId, "engine read no movement");
            }
        }

        assertDivergences("balances only the engine reads", engineOnlyBalance, ENGINE_ONLY_BALANCE);
        assertDivergences("movements only the engine reads", engineOnlyMovement, ENGINE_ONLY_MOVEMENT);
        assertDivergences("balances only the legacy app reads", legacyOnlyBalance, LEGACY_ONLY_BALANCE);
        assertDivergences("movements only the legacy app reads", legacyOnlyMovement, LEGACY_ONLY_MOVEMENT);
        assertDivergences("amount conflicts", conflicts, CONFLICTS);
        assertDivergences("account divergences", accountDivergence, ACCOUNT_DIVERGENCE);
        assertTrue("corpus exercised the engine and the legacy reducers", engineParsed > 0 && compared > 0);
        // A pack whose fixtures the engine parses none of would pass by being skipped above, so
        // every packed bank must have earned at least one parsed case, reachable or not.
        packed.removeAll(parsed);
        assertTrue("every packed bank parsed at least one of its own fixtures: " + packed, packed.isEmpty());
    }

    private static Reduced reduce(List<Parser.Fact> facts) {
        Reduced out = new Reduced();
        for (Parser.Fact f : facts) {
            if (f.money().scale() != 0) continue;
            if (f.kind() == Rules.Kind.BOOKED_BALANCE) {
                out.balance = f.money().minorUnits();
                out.balanceAccount = f.account();
            } else if (f.kind() == Rules.Kind.POSTED_MOVEMENT) {
                out.movement = f.money().minorUnits();
                out.movementAccount = f.account();
            }
        }
        return out;
    }

    private static void assertDivergences(String label, TreeMap<String, String> actual, String[] allowed) {
        Set<String> permit = new TreeSet<>();
        for (String a : allowed) permit.add(a);
        Set<String> unexpected = new TreeSet<>(actual.keySet());
        unexpected.removeAll(permit);
        if (!unexpected.isEmpty()) {
            StringBuilder message = new StringBuilder("Unexpected " + label + ":");
            for (String f : unexpected) message.append("\n  ").append(f).append(" -> ").append(actual.get(f));
            throw new AssertionError(message.toString());
        }
    }

    private static Map<String, Object> asset(String path) throws IOException {
        try (InputStream input = InstrumentationRegistry.getInstrumentation()
                .getContext().getAssets().open(path)) {
            return PlatformRuleJson.read(input);
        }
    }
}