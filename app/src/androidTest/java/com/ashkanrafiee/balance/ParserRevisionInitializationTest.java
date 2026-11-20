package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Also run alone in a fresh instrumentation process, without the isolation listener. */
@RunWith(AndroidJUnit4.class)
public class ParserRevisionInitializationTest {
    @Test public void balanceDataInitializedFirst_keepsTheHistoryRevisionCurrent() {
        BalanceData.isOnboardingSeen(InstrumentationRegistry.getInstrumentation().getTargetContext());
        assertEquals(1250L, BalanceData.extract("Available balance: 1,250"));
        int historyRevision = BalanceData.historyRulesVersion();
        assertTrue("the history revision must be initialized", historyRevision > 0);
        assertEquals(BankRules.VERSION, historyRevision);
    }

    @Test public void bankRulesInitializedFirst_keepsTheHistoryRevisionCurrent() {
        int revision = BankRules.VERSION;
        assertTrue("the parser revision must be initialized", revision > 0);
        assertEquals("history must not capture an unfinished parser revision",
            revision, BalanceData.historyRulesVersion());
        assertEquals(1250L, BalanceData.extract("Available balance: 1,250"));
    }
}
