package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.Telephony;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;

/** Deterministic provider failures and safe repair; never writes to the device SMS inbox. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 29)
public class ScanOutcomeRepairTest {
    private Context context;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BalanceData.reset(context, true);
    }

    @After public void tearDown() {
        BalanceData.reset(context, true);
    }

    @Test public void noAccessIsNotASuccessfulEmptyScan() {
        Context denied = new ContextWrapper(context) {
            @Override public int checkSelfPermission(String permission) {
                return PackageManager.PERMISSION_DENIED;
            }
        };
        assertEquals(BalanceData.ScanOutcome.Status.NO_ACCESS,
            BalanceData.scanSmsOutcome(denied, new LinkedHashMap<>(), null).status);
        assertEquals(BalanceData.ScanOutcome.Status.NO_ACCESS,
            BalanceData.scanHistoryOutcome(denied, null).status);
        assertEquals(BalanceData.ScanOutcome.Status.NO_ACCESS,
            BalanceData.reReadBankMessages(denied, new LinkedHashMap<>(), null).status);
        assertEquals(0, BalanceData.scanSms(denied, new LinkedHashMap<>()));
        assertEquals(0, BalanceData.scanHistory(denied));
    }

    @Test public void nullAndThrowingProvidersAreNotSuccessfulChecks() {
        for (int mode : new int[]{1, 2}) {
            Context provider = withProvider(mode);
            assertEquals(BalanceData.ScanOutcome.Status.PROVIDER_ERROR,
                BalanceData.scanSmsOutcome(provider, new LinkedHashMap<>(), null).status);
            assertEquals(BalanceData.ScanOutcome.Status.PROVIDER_ERROR,
                BalanceData.scanHistoryOutcome(provider, null).status);
            assertEquals(0, context.getSharedPreferences(BalanceData.PREFS_PREF, 0)
                .getLong(BalanceData.KEY_SCANNED_THROUGH, 0));
        }
    }

    @Test public void emptyReadableProviderIsASuccessfulCheckNotAnUpdate() {
        BalanceData.ScanOutcome balance = BalanceData.scanSmsOutcome(
            withProvider(0), new LinkedHashMap<>(), null);
        assertTrue(balance.succeeded());
        assertEquals(0, balance.changed);
        BalanceData.ScanOutcome history = BalanceData.scanHistoryOutcome(withProvider(0), null);
        assertTrue(history.succeeded());
        assertEquals(0, history.changed);
    }

    @Test public void failedCheckpointCommitIsNotSuccessful() {
        Context failing = new ContextWrapper(withProvider(0)) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                SharedPreferences real = super.getSharedPreferences(name, mode);
                if (!BalanceData.PREFS_PREF.equals(name)) return real;
                return (SharedPreferences) Proxy.newProxyInstance(
                    SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                    (proxy, method, args) -> {
                        if (!method.getName().equals("edit")) return method.invoke(real, args);
                        SharedPreferences.Editor editor = real.edit();
                        return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (ep, em, ea) -> {
                                if (em.getName().equals("commit")) return false;
                                Object value = em.invoke(editor, ea);
                                return value == editor ? ep : value;
                            });
                    });
            }
        };
        assertEquals(BalanceData.ScanOutcome.Status.SAVE_ERROR,
            BalanceData.scanSmsOutcome(failing, new LinkedHashMap<>(), null).status);
        assertEquals(BalanceData.ScanOutcome.Status.SAVE_ERROR,
            BalanceData.scanHistoryOutcome(failing, null).status);
        assertEquals(BalanceData.ScanOutcome.Status.SAVE_ERROR,
            BalanceData.reReadBankMessages(failing, new LinkedHashMap<>(), null).status);
    }

    @Test public void reReadKeepsPersonalDataAndDeletedMessageHistoryAndSources() throws Exception {
        Transaction transaction = new Transaction("Refah", 1_000_000L, -100L);
        assertTrue(BalanceData.writeTransactions(context, Collections.singletonList(transaction)));
        BalanceData.setNote(context, transaction, "keep this note");
        BalanceData.setTags(context, transaction, Collections.singletonList("personal"));
        BalanceData.setExcluded(context, Collections.singleton("Refah"));
        Commitment commitment = Commitment.create("Keep commitment", -200L, Commitment.ONCE,
            Commitment.startOfDay(System.currentTimeMillis()), null, false, 0);
        BalanceData.writeCommitments(context, Collections.singletonList(commitment));
        SourceStore.capture(context, "Refah", "retained source", 1_000_000L, 1);
        long sources = SourceStore.countSources(context);
        LinkedHashMap<String, Bank> balances = new LinkedHashMap<>();
        balances.put("Refah", new Bank("Refah", 900L, 1_000_000L, "Refah", null));
        assertTrue(BalanceData.write(context, balances));

        assertTrue(BalanceData.reReadBankMessages(withProvider(0), balances, null).succeeded());

        assertEquals(1, BalanceData.readTransactions(context).size());
        assertEquals("keep this note", BalanceData.getNote(context, transaction));
        assertEquals(Collections.singletonList("personal"), BalanceData.getTags(context, transaction));
        assertEquals(Collections.singleton("Refah"), BalanceData.getExcluded(context));
        assertEquals(1, BalanceData.readCommitments(context).size());
        assertEquals(commitment.id, BalanceData.readCommitments(context).get(0).id);
        assertEquals(sources, SourceStore.countSources(context));
        assertEquals(900L, BalanceData.read(context).get("Refah").amount);

        assertFalse(BalanceData.reReadBankMessages(withProvider(1), balances, null).succeeded());
        assertEquals("keep this note", BalanceData.getNote(context, transaction));
        assertEquals(1, BalanceData.readCommitments(context).size());
        assertEquals(sources, SourceStore.countSources(context));
    }

    @Test public void reReadReprocessesMessagesWithoutDuplicatingOrLosingAnnotations() throws Exception {
        Context provider = withProvider(3);
        LinkedHashMap<String, Bank> balances = new LinkedHashMap<>();
        assertTrue(BalanceData.reReadBankMessages(provider, balances, null).succeeded());
        assertEquals(1, BalanceData.readTransactions(context).size());
        Transaction saved = BalanceData.readTransactions(context).get(0);
        BalanceData.setNote(context, saved, "keep on reparse");
        BalanceData.setTags(context, saved, Collections.singletonList("kept"));
        BalanceData.setExcluded(context, Collections.singleton("Saman"));
        long sources = SourceStore.countSources(context);

        assertTrue(BalanceData.reReadBankMessages(provider, balances, null).succeeded());

        assertEquals(1, BalanceData.readTransactions(context).size());
        Transaction reparsed = BalanceData.readTransactions(context).get(0);
        assertEquals("keep on reparse", BalanceData.getNote(context, reparsed));
        assertEquals(Collections.singletonList("kept"), BalanceData.getTags(context, reparsed));
        assertEquals(Collections.singleton("Saman"), BalanceData.getExcluded(context));
        assertEquals(sources, SourceStore.countSources(context));
    }

    @Test public void successfulBalancePassDoesNotHideFailedHistoryPass() {
        BalanceData.ScanOutcome outcome = BalanceData.reReadBankMessages(
            withProvider(4), new LinkedHashMap<>(), null);
        assertEquals(BalanceData.ScanOutcome.Status.PROVIDER_ERROR, outcome.status);
        assertFalse(outcome.succeeded());
    }

    private Context withProvider(int mode) {
        ContentProvider provider = new ContentProvider() {
            int queries;
            @Override public boolean onCreate() { return true; }
            @Override public Cursor query(Uri uri, String[] projection, String selection,
                    String[] args, String order) {
                if (mode == 1 || (mode == 4 && ++queries == 2)) return null;
                if (mode == 2) throw new IllegalStateException("provider unavailable");
                MatrixCursor cursor = new MatrixCursor(new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                    Telephony.Sms.DATE, Telephony.Sms._ID});
                if (mode == 3) cursor.addRow(new Object[]{"500095",
                    "مبلغ 200,000 ریال به حساب شما واریز شد، موجودی: 3,000,000 ریال",
                    1_000_000_000L, 1L});
                return cursor;
            }
            @Override public String getType(Uri uri) { return null; }
            @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
            @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
            @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
                throw new UnsupportedOperationException();
            }
        };
        ProviderInfo info = new ProviderInfo();
        info.authority = "sms";
        provider.attachInfo(context, info);
        ContentResolver resolver = ContentResolver.wrap(provider);
        return new ContextWrapper(context) {
            @Override public ContentResolver getContentResolver() { return resolver; }
            @Override public int checkSelfPermission(String permission) {
                return PackageManager.PERMISSION_GRANTED;
            }
        };
    }
}
