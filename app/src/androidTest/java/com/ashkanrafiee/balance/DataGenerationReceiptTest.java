package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.util.Collections;
import java.util.List;

/** Staging must retain independent store-loss receipts, not turn lost data into a fresh store. */
@RunWith(AndroidJUnit4.class)
public class DataGenerationReceiptTest {
    private static final String SOURCE_RECEIPT = "balance_sources.present";
    private static final String TRANSACTION_RECEIPT = "balance_transactions.present";
    private Context context;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        resetRootFixture();
    }

    @After public void tearDown() {
        resetRootFixture();
    }

    private void resetRootFixture() {
        synchronized (BalanceData.class) {
            DataGeneration.clearForTests(context);
            BalanceData.reset(context, true);
        }
    }

    @Test public void missingRootSourceDatabaseRemainsGuardedAfterStagingAndPublication()
            throws Exception {
        synchronized (BalanceData.class) {
            assertNotNull(SourceStore.capture(context, "Refah", "root source", 100L, 1));
            assertReceipt(context, SOURCE_RECEIPT);
            assertTrue(context.deleteDatabase(SourceStore.DB_NAME));
            assertMissingSourceGuard(context);

            try (DataGeneration.Stage stage = DataGeneration.beginStage(context)) {
                assertReceipt(stage.context(), SOURCE_RECEIPT);
                assertMissingSourceGuard(stage.context());
                stage.publish();
            }

            Context selected = DataGeneration.context(context);
            assertNotEquals(context.getDatabasePath(SourceStore.DB_NAME),
                selected.getDatabasePath(SourceStore.DB_NAME));
            assertReceipt(selected, SOURCE_RECEIPT);
            assertMissingSourceGuard(context);
            assertFalse(context.getDatabasePath(SourceStore.DB_NAME).exists());
        }
    }

    @Test public void sourceCreatedAfterPublicationRemainsGuardedWhenLostAndRestaged()
            throws Exception {
        synchronized (BalanceData.class) {
            try (DataGeneration.Stage stage = DataGeneration.beginStage(context)) {
                stage.publish();
            }
            Context original = DataGeneration.context(context);
            assertFalse(original.getDatabasePath(SourceStore.DB_NAME).exists());
            assertFalse(receipt(original, SOURCE_RECEIPT).exists());

            // This database is intentionally absent from the publication inventory. Its receipt
            // alone must detect loss; deleting an inventoried database already rejects the context.
            assertNotNull(SourceStore.capture(context, "Refah", "later source", 200L, 1));
            assertReceipt(original, SOURCE_RECEIPT);
            assertTrue(original.deleteDatabase(SourceStore.DB_NAME));
            assertEquals(original.getDatabasePath(SourceStore.DB_NAME),
                DataGeneration.context(context).getDatabasePath(SourceStore.DB_NAME));
            assertMissingSourceGuard(context);

            try (DataGeneration.Stage stage = DataGeneration.beginStage(context)) {
                assertNotEquals(original.getDatabasePath(SourceStore.DB_NAME),
                    stage.context().getDatabasePath(SourceStore.DB_NAME));
                assertReceipt(stage.context(), SOURCE_RECEIPT);
                assertMissingSourceGuard(stage.context());
                stage.publish();
            }

            Context selected = DataGeneration.context(context);
            assertNotEquals(original.getDatabasePath(SourceStore.DB_NAME),
                selected.getDatabasePath(SourceStore.DB_NAME));
            assertReceipt(selected, SOURCE_RECEIPT);
            assertMissingSourceGuard(context);
            assertFalse(original.getDatabasePath(SourceStore.DB_NAME).exists());
            assertFalse(context.getDatabasePath(SourceStore.DB_NAME).exists());
        }
    }

    @Test public void healthyStoresAndBothReceiptsSurviveStagingAndPublication() throws Exception {
        synchronized (BalanceData.class) {
            Transaction transaction = new Transaction("Refah", "account", 300L, -20L, 80L,
                "receipt-signature", "receipt-content");
            assertTrue(TransactionStore.replace(context, Collections.singletonList(transaction)));
            SourceStore.Source source = SourceStore.capture(context,
                "Refah", "healthy source", 300L, 2);
            assertNotNull(source);
            assertTrue(SourceStore.observeTransaction(context, source, 2, transaction));
            assertTrue(SourceStore.observeBalance(context, source, 2, "Refah", "account", 300L, 80L));

            try (DataGeneration.Stage stage = DataGeneration.beginStage(context)) {
                assertHealthyStores(stage.context(), source, transaction);
                stage.publish();
            }

            assertNotEquals(context.getDatabasePath(SourceStore.DB_NAME),
                DataGeneration.context(context).getDatabasePath(SourceStore.DB_NAME));
            assertHealthyStores(context, source, transaction);
            assertReceipt(context, SOURCE_RECEIPT);
            assertReceipt(context, TRANSACTION_RECEIPT);
        }
    }

    @Test public void genuinelyFreshStoresRemainUsableAfterPublication() throws Exception {
        synchronized (BalanceData.class) {
            assertFreshStores(context);
            try (DataGeneration.Stage stage = DataGeneration.beginStage(context)) {
                assertFreshStores(stage.context());
                assertNull(TransactionStore.read(stage.context()));
                assertFreshStores(stage.context());
                stage.publish();
            }

            Context selected = DataGeneration.context(context);
            assertFreshStores(selected);
            assertNull(TransactionStore.read(context));
            assertEquals(0L, SourceStore.sourceCount(context));
            assertNotNull(SourceStore.capture(context, "Refah", "first source", 400L, 1));
            assertTrue(TransactionStore.replace(context,
                Collections.singletonList(new Transaction("Refah", 400L, 10L))));
            assertEquals(1L, SourceStore.sourceCount(context));
            assertEquals(1L, TransactionStore.count(context));
            assertReceipt(selected, SOURCE_RECEIPT);
            assertReceipt(selected, TRANSACTION_RECEIPT);
            assertFreshStores(context);
        }
    }

    @Test public void missingTransactionDatabaseKeepsReceiptOnlyGuardAfterPublication()
            throws Exception {
        synchronized (BalanceData.class) {
            assertTrue(TransactionStore.replace(context,
                Collections.singletonList(new Transaction("Refah", 500L, 10L))));
            assertReceipt(context, TRANSACTION_RECEIPT);
            assertTrue(DataGeneration.dataPrefs(context).edit()
                .remove(BalanceData.KEY_TRANSACTION_STORE_TOKEN).commit());
            assertTrue(context.deleteDatabase(TransactionStore.DB_NAME));
            assertMissingTransactionGuard(context);

            try (DataGeneration.Stage stage = DataGeneration.beginStage(context)) {
                assertReceipt(stage.context(), TRANSACTION_RECEIPT);
                assertFalse(DataGeneration.dataPrefs(stage.context())
                    .contains(BalanceData.KEY_TRANSACTION_STORE_TOKEN));
                assertMissingTransactionGuard(stage.context());
                stage.publish();
            }

            Context selected = DataGeneration.context(context);
            assertReceipt(selected, TRANSACTION_RECEIPT);
            assertFalse(DataGeneration.dataPrefs(context)
                .contains(BalanceData.KEY_TRANSACTION_STORE_TOKEN));
            assertMissingTransactionGuard(context);
            assertFalse(context.getDatabasePath(TransactionStore.DB_NAME).exists());
        }
    }

    private void assertMissingSourceGuard(Context input) throws Exception {
        Context data = DataGeneration.context(input);
        assertFalse(data.getDatabasePath(SourceStore.DB_NAME).exists());
        try {
            SourceStore.sourceCount(input);
            fail("a lost source database must not be read as empty");
        } catch (IllegalStateException expected) {
            assertEquals("source database is missing", expected.getMessage());
        }
        try {
            SourceStore.capture(input, "Refah", "must not recreate", 999L, 1);
            fail("capture must not recreate a lost source database");
        } catch (IllegalStateException expected) {
            assertEquals("source database is missing", expected.getMessage());
        }
        assertFalse(data.getDatabasePath(SourceStore.DB_NAME).exists());
        assertReceipt(data, SOURCE_RECEIPT);
    }

    private void assertMissingTransactionGuard(Context input) throws Exception {
        Context data = DataGeneration.context(input);
        assertFalse(data.getDatabasePath(TransactionStore.DB_NAME).exists());
        try {
            TransactionStore.read(input);
            fail("a lost transaction database must not be read as fresh");
        } catch (IllegalStateException expected) {
            assertEquals("transaction database is missing", expected.getMessage());
        }
        try {
            TransactionStore.count(input);
            fail("a lost transaction database must not be counted as empty");
        } catch (IllegalStateException expected) {
            assertEquals("transaction database is missing", expected.getMessage());
        }
        assertFalse(data.getDatabasePath(TransactionStore.DB_NAME).exists());
        assertReceipt(data, TRANSACTION_RECEIPT);
    }

    private void assertHealthyStores(Context input, SourceStore.Source source,
            Transaction transaction) throws Exception {
        Context data = DataGeneration.context(input);
        assertTrue(data.getDatabasePath(SourceStore.DB_NAME).isFile());
        assertTrue(data.getDatabasePath(TransactionStore.DB_NAME).isFile());
        // Check receipts before reads, since a successful store read can create a missing receipt.
        assertReceipt(data, SOURCE_RECEIPT);
        assertReceipt(data, TRANSACTION_RECEIPT);
        List<Transaction> transactions = TransactionStore.read(input);
        assertNotNull(transactions);
        assertEquals(1, transactions.size());
        Transaction retainedTransaction = transactions.get(0);
        assertEquals(transaction.bank, retainedTransaction.bank);
        assertEquals(transaction.account, retainedTransaction.account);
        assertEquals(transaction.date, retainedTransaction.date);
        assertEquals(transaction.amount, retainedTransaction.amount);
        assertEquals(transaction.balance, retainedTransaction.balance);
        assertEquals(transaction.sig, retainedTransaction.sig);
        assertEquals(transaction.content, retainedTransaction.content);
        assertEquals(1L, SourceStore.sourceCount(input));
        SourceStore.Source retained = SourceStore.pageSources(input, -1, 10).rows.get(0);
        assertEquals(source.id, retained.id);
        assertEquals(source.physicalIdentity, retained.physicalIdentity);
        assertEquals(source.contentIdentity, retained.contentIdentity);
        assertEquals(source.bank, retained.bank);
        assertEquals(source.sender, retained.sender);
        assertEquals(source.rawBody, retained.rawBody);
        assertEquals(source.arrivalTime, retained.arrivalTime);
        assertEquals(source.ruleVersion, retained.ruleVersion);
        assertEquals(1L, SourceStore.transactionObservationCount(input));
        assertEquals(1L, SourceStore.balanceObservationCount(input));
    }

    private void assertFreshStores(Context data) {
        assertFalse(data.getDatabasePath(SourceStore.DB_NAME).exists());
        assertFalse(data.getDatabasePath(TransactionStore.DB_NAME).exists());
        assertFalse(receipt(data, SOURCE_RECEIPT).exists());
        assertFalse(receipt(data, TRANSACTION_RECEIPT).exists());
    }

    private File receipt(Context data, String name) {
        return new File(data.getNoBackupFilesDir(), name);
    }

    private void assertReceipt(Context data, String name) throws Exception {
        File file = receipt(data, name);
        assertTrue("store receipt must survive: " + name, file.isFile());
        try (FileInputStream input = new FileInputStream(file)) {
            assertEquals(1, input.read());
            assertEquals(-1, input.read());
        }
    }
}
