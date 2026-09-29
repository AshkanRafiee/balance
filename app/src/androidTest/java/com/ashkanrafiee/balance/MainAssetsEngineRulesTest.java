package com.ashkanrafiee.balance;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.ashkanrafiee.balance.parser.Parser;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** F1.c: the production loader. Verifies the official packs are bundled into the app's own main
 *  assets, that EngineRules indexes every catalog sender exactly once, and that the whole
 *  112-fixture corpus parses through the packaged index to the same statuses the on-device corpus
 *  test asserts. Also pins the production activation contract: the app's scan path activates the
 *  seam once per process, and activation is idempotent. */
@RunWith(AndroidJUnit4.class)
public class MainAssetsEngineRulesTest {

    @Test public void loadedPackagesIndexAndParseTheWholeCorpus() throws Exception {
        EngineRules rules = EngineRules.activate(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        assertNotNull("production activation must load the bundled packs", rules);
        assertTrue("production activation turns the seam on", rules.active());
        assertEquals("activation is idempotent", rules,
                EngineRules.activate(InstrumentationRegistry.getInstrumentation().getTargetContext()));
        assertEquals("Every non-collision bank is packed", 42, rules.bankCount());
        assertTrue("Sender index is populated", rules.senderAliasCount() > 0);

        Map<String, Object> catalog = asset("catalog.json");
        List<?> banks = (List<?>) catalog.get("banks");
        int fixtures = 0;
        for (Object item : banks) {
            Map<?, ?> bank = (Map<?, ?>) item;
            String id = (String) bank.get("id");
            List<?> cases;
            try (InputStream input = InstrumentationRegistry.getInstrumentation()
                    .getTargetContext().getAssets().open(id + "/fixtures.json")) {
                cases = (List<?>) PlatformRuleJson.read(input).get("cases");
            } catch (java.io.IOException notPacked) {
                continue;
            }
            for (Object f : cases) {
                Map<?, ?> fixture = (Map<?, ?>) f;
                String caseId = id + "/" + fixture.get("id");
                EngineRules.Bank covers = rules.covers((String) fixture.get("sender"));
                assertNotNull(caseId + " sender is indexed", covers);
                assertEquals(caseId + " sender is indexed to its own pack", id, covers.id);

                Parser.Result result = rules.parse(
                        (String) fixture.get("sourceId"), (String) fixture.get("sender"),
                        (String) fixture.get("body"),
                        Instant.parse((String) fixture.get("arrival")),
                        ZoneId.of((String) fixture.get("zone")));
                assertNotNull(caseId + " parse routed to the pack engine", result);
                Map<?, ?> expected = (Map<?, ?>) fixture.get("expected");
                assertEquals(caseId + " status through the loader matches the corpus",
                        expected.get("status"), result.status().name());
                fixtures++;
            }
        }
        assertEquals("The full corpus was exercised", 112, fixtures);
    }

    private static Map<String, Object> asset(String path) throws Exception {
        try (InputStream input = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getAssets().open(path)) {
            return PlatformRuleJson.read(input);
        }
    }
}