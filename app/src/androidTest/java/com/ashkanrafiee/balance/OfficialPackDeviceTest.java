package com.ashkanrafiee.balance;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.ashkanrafiee.balance.parser.PackDocument;
import com.ashkanrafiee.balance.parser.Parser;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** On-device engine parity for the bundled official Iranian packs (no device data used).
 * Decodes every catalog bank pack through the Android JSON codec and runs all its fixtures
 * through PackDocument + Parser, asserting the same self-contained statuses and outputs as
 * the host officialPackTest. Host already pins the legacy differential; this protects the
 * production parse path against D8/asset/JSON mismatches. */
@RunWith(AndroidJUnit4.class)
public class OfficialPackDeviceTest {
    private static final String MISSING = "ir.tosee-credit-inst";

    @Test public void allCatalogPacksDecodeAndAllFixturesParseOnDevice() throws Exception {
        Map<String, Object> catalog = asset("catalog.json");
        List<?> banks = (List<?>) catalog.get("banks");
        assertNotNull(banks);

        Set<String> packed = new HashSet<>();
        Set<String> missing = new HashSet<>();
        int fixtures = 0;
        for (Object item : banks) {
            Map<?, ?> bank = (Map<?, ?>) item;
            String id = (String) bank.get("id");
            PackDocument pack;
            try {
                pack = PackDocument.decode(asset(id + "/pack.json"));
            } catch (IOException noPack) {
                missing.add(id);
                continue;
            }
            packed.add(id);
            int cases = assertPackCases(id, pack);
            fixtures += cases;
            assertTrue("Pack has fixtures: " + id, cases > 0);
        }

        assertEquals("Every catalog bank is packed except the documented collision", 43, banks.size());
        assertEquals("Missing pack must be exactly the collision bank", 42, packed.size());
        assertEquals(Set.of(MISSING), missing);
        assertEquals("Official fixture count matches the host differential corpus", 112, fixtures);
    }

    private static int assertPackCases(String id, PackDocument pack) throws Exception {
        Map<String, Object> fixtures = asset(id + "/fixtures.json");
        assertEquals("prototype-fixtures-1", fixtures.get("schema"));
        assertEquals(pack.id(), fixtures.get("pack"));
        List<?> cases = (List<?>) fixtures.get("cases");
        assertNotNull(cases);
        List<Object> remaining = new ArrayList<>(cases);
        for (Object item : cases) {
            Map<?, ?> fixture = (Map<?, ?>) item;
            String caseId = (String) fixture.get("id");
            assertTrue("Unique fixture id: " + caseId, remaining.remove(item));
            assertPackCase(id, caseId, pack, fixture);
        }
        assertTrue("All fixtures consumed: " + id, remaining.isEmpty());
        return cases.size();
    }

    private static void assertPackCase(String packId, String id, PackDocument pack, Map<?, ?> fixture)
            throws Exception {
        Parser parser = new Parser(pack.templates());
        Parser.Result result = parser.parse(new Parser.Message(
                (String) fixture.get("sourceId"), (String) fixture.get("sender"),
                (String) fixture.get("body"), Instant.parse((String) fixture.get("arrival")),
                ZoneId.of((String) fixture.get("zone"))));
        Map<?, ?> expected = (Map<?, ?>) fixture.get("expected");
        assertEquals(id + " " + packId, expected.get("status"), result.status().name());
        assertEquals(id, ((List<?>) expected.get("outputs")).size(), result.facts().size());

        Map<String, Parser.Fact> byId = new HashMap<>();
        for (Parser.Fact fact : result.facts()) {
            assertNull(id, byId.put(fact.provenance().outputId(), fact));
            assertEquals(id, pack.id(), fact.provenance().packId());
            assertEquals(id, fixture.get("sourceId"), fact.provenance().sourceId());
        }
        for (Object output : (List<?>) expected.get("outputs")) {
            Map<?, ?> want = (Map<?, ?>) output;
            Parser.Fact fact = byId.remove((String) want.get("id"));
            assertNotNull(id + " both outputs produced", fact);
            assertEquals(id, want.get("account"), fact.account());
            assertEquals(id, want.get("currency"), fact.money().currency().name());
            assertEquals(id, Long.parseLong((String) want.get("minorUnits")), fact.money().minorUnits());
            assertEquals(id, ((BigDecimal) want.get("scale")).intValueExact(), fact.money().scale());
            assertTime(id, want, fact);
            assertSemanticText(id + "/reason", want.get("reason"), fact.reason());
            assertSemanticText(id + "/channel", want.get("channel"), fact.channel());
        }
        assertTrue(id, byId.isEmpty());
        if (result.status() != Parser.Status.PARSED) {
            assertTrue(id, result.facts().isEmpty());
            assertTrue(id, result.matchedProvenance().isEmpty());
        } else {
            assertEquals(id, result.facts().size(), result.matchedProvenance().size());
        }
    }

    private static void assertTime(String id, Map<?, ?> want, Parser.Fact fact) {
        Object expectedTime = want.get("time");
        if (expectedTime == null) return;
        Map<?, ?> time = (Map<?, ?>) expectedTime;
        assertNotNull(id, fact.time());
        assertEquals(id, Instant.parse((String) time.get("instant")), fact.time().instant());
        assertEquals(id, time.get("precision"), fact.time().precision().name());
        assertEquals(id, ZoneId.of((String) time.get("zone")), fact.time().zone());
        assertEquals(id, time.get("fallback"), fact.time().fallback());
    }

    private static void assertSemanticText(String label, Object expected, Parser.SemanticText actual) {
        if (expected == null) {
            assertNull(label, actual);
        } else {
            Map<?, ?> text = (Map<?, ?>) expected;
            assertNotNull(label, actual);
            assertEquals(label, text.get("id"), actual.id());
            assertEquals(label, text.get("text"), actual.text());
        }
    }

    private static Map<String, Object> asset(String path) throws IOException {
        try (InputStream input = InstrumentationRegistry.getInstrumentation()
                .getContext().getAssets().open(path)) {
            return PlatformRuleJson.read(input);
        }
    }
}