package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * Optional native store captures, never part of an ordinary regression run.
 *
 * <p>Requires storeScreenshots=true, an Android emulator, and READ_SMS revoked or explicitly
 * checked-empty-inbox capture mode. Only then
 * may the fixture replace the emulator's app data. It never reads message bodies or changes SMS and
 * never snapshots existing app records. Use only a disposable emulator; invoke this class with
 * {@code -e storeScreenshots true}. Checked-empty-inbox mode additionally requires
 * {@code -e storeScreenshotsEmptyInbox true}; it grants SMS access and verifies an empty inbox.
 */
@RunWith(AndroidJUnit4.class)
public final class StoreScreenshotsTest {
    private static final String ACCOUNT = "00000000001";
    private Instrumentation instrumentation;
    private Context context;
    private Application application;
    private Application.ActivityLifecycleCallbacks permissionPromptCallback;
    private LocaleList originalLocales;
    private ActivityScenario<MainActivity> dashboard;
    private ActivityScenario<HistoryActivity> history;
    private ActivityScenario<CommitmentsActivity> commitments;
    private Transaction featured;
    private String featuredNote;

    @Before public void setUp() throws Exception {
        // This must precede every context/store/permission operation, including teardown setup.
        assumeTrue("store capture is opt-in", "true".equals(
            InstrumentationRegistry.getArguments().getString("storeScreenshots")));
        instrumentation = InstrumentationRegistry.getInstrumentation();
        assertTrue("Refusing to replace data outside an Android emulator",
            ("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE))
                && ("1".equals(property("ro.kernel.qemu"))
                    || "1".equals(property("ro.boot.qemu"))));
        Context target = instrumentation.getTargetContext();
        boolean emptyInbox = "true".equals(InstrumentationRegistry.getArguments()
            .getString("storeScreenshotsEmptyInbox"));
        if (emptyInbox) {
            instrumentation.getUiAutomation().grantRuntimePermission(target.getPackageName(),
                Manifest.permission.READ_SMS);
            // Only check whether a row exists; never inspect any message body or sender. A healthy
            // store overview is allowed only after explicitly clearing the disposable emulator inbox.
            assertEmptyInbox(target);
        } else {
            assertEquals("Revoke READ_SMS before instrumentation in denied-access capture mode",
                PackageManager.PERMISSION_DENIED, target.checkSelfPermission(Manifest.permission.READ_SMS));
        }

        context = target;
        originalLocales = LocaleList.getDefault();
        BalanceData.reset(context, true);
        assertTrue("fixture preferences must reset", context.getSharedPreferences(
            BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit());
        // This is now a guarded, synthetic-only emulator setup. Choose the unlocked product
        // state rather than bypassing FLAG_SECURE or inheriting another test's lock preference.
        LockManager.disable(context);
        LockManager.unlockSession();
        BalanceData.setOnboardingSeen(context, true);
        BalanceData.setShowCommitments(context, true);
        BalanceData.setAutoHide(context, false);
        CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_TOMAN);
        ThemeHelper.setTheme(context, ThemeHelper.THEME_LIGHT);
        application = (Application) context.getApplicationContext();
        permissionPromptCallback = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {
                if (!(activity instanceof MainActivity)) return;
                // Suppress only the automatic permission prompt, not any visible UI or data load.
                // Activity-created callbacks run after onCreate and before the first onResume.
                try {
                    Field field = MainActivity.class.getDeclaredField("smsRequested");
                    field.setAccessible(true);
                    field.setBoolean(activity, true);
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError("could not suppress the fixture permission prompt", error);
                }
            }
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        };
        application.registerActivityLifecycleCallbacks(permissionPromptCallback);
    }

    @After public void tearDown() {
        if (commitments != null) commitments.close();
        if (history != null) history.close();
        if (dashboard != null) dashboard.close();
        if (application != null && permissionPromptCallback != null)
            application.unregisterActivityLifecycleCallbacks(permissionPromptCallback);
        if (originalLocales != null) LocaleList.setDefault(originalLocales);
    }

    @Test public void captureEnglish() throws Exception {
        capture("en", "en-US");
    }

    @Test public void capturePersian() throws Exception {
        capture("fa", "fa");
    }

    private void capture(String language, String folder) throws Exception {
        seed(language);
        // Give the overview room to show every account; commitments have their own full frame.
        BalanceData.setShowCommitments(context, false);
        File directory = new File(context.getCacheDir(), "store-screenshots/" + folder);
        assertTrue("private screenshot directory must exist", directory.isDirectory() || directory.mkdirs());

        dashboard = ActivityScenario.launch(MainActivity.class);
        await(() -> {
            boolean[] ready = {false};
            dashboard.onActivity(activity -> {
                View root = activity.getWindow().getDecorView();
                ready[0] = activity.hasWindowFocus()
                    && findText(root, CurrencyHelper.amount(activity, 128_450_000L)) != null
                    && findText(root, BankRules.displayName(activity, "Blu")) != null;
            });
            return ready[0];
        }, "dashboard balances and commitments");
        save(directory, "01-dashboard.png");
        dashboard.close();
        dashboard = null;
        BalanceData.setShowCommitments(context, true);

        history = ActivityScenario.launch(HistoryActivity.class);
        await(() -> {
            boolean[] ready = {false};
            history.onActivity(activity -> ready[0] = activity.hasWindowFocus()
                && HistoryActivity.idleForTest(activity)
                && findText(activity.getWindow().getDecorView(), featuredNote) != null);
            return ready[0];
        }, "history with synthetic transaction notes");
        history.onActivity(activity -> {
            View note = findText(activity.getWindow().getDecorView(), featuredNote);
            View parent = note;
            while (parent != null && !(parent instanceof android.widget.ScrollView))
                parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
            assertTrue("history content must be scrollable", parent instanceof android.widget.ScrollView);
            android.widget.ScrollView scroll = (android.widget.ScrollView) parent;
            scroll.scrollTo(0, Math.max(0, scroll.getChildAt(0).getHeight() - scroll.getHeight()));
        });
        await(() -> {
            boolean[] visible = {false};
            history.onActivity(activity -> {
                View note = findText(activity.getWindow().getDecorView(), featuredNote);
                android.graphics.Rect bounds = new android.graphics.Rect();
                visible[0] = note != null && note.getGlobalVisibleRect(bounds) && bounds.height() > 0;
            });
            return visible[0];
        }, "visible synthetic transaction details");
        save(directory, "02-history.png");

        // Use the same accessible transaction row a user taps, rather than rendering a mock detail.
        history.onActivity(activity -> {
            View note = findText(activity.getWindow().getDecorView(), featuredNote);
            assertNotNull("featured transaction must be rendered", note);
            View row = note;
            while (!row.isClickable() && row.getParent() instanceof View)
                row = (View) row.getParent();
            assertTrue("featured transaction must open its native explanation", row.performClick());
        });
        await(() -> {
            boolean[] ready = {false};
            history.onActivity(activity -> {
                for (android.app.AlertDialog dialog : HistoryActivity.shownDialogsForTest(activity)) {
                    if (!dialog.isShowing() || dialog.getWindow() == null) continue;
                    View root = dialog.getWindow().getDecorView();
                    if (findText(root, activity.getString(R.string.figure_title)) == null) continue;
                    ready[0] = root.isLaidOut() && root.hasWindowFocus()
                        && containsText(root, activity.getString(R.string.figure_source_body))
                        && containsText(root, "Demo data") && containsText(root, featuredNote);
                }
            });
            return ready[0];
        }, "native explanation with synthetic source evidence and annotations");
        save(directory, "04-explanation.png");
        history.close();
        history = null;

        commitments = ActivityScenario.launch(CommitmentsActivity.class);
        String rent = localized(language, "Demo · apartment rent", "نمونه · اجاره خانه");
        await(() -> {
            boolean[] ready = {false};
            commitments.onActivity(activity -> ready[0] = activity.hasWindowFocus()
                && findText(activity.getWindow().getDecorView(), rent) != null);
            return ready[0];
        }, "synthetic commitments");
        save(directory, "03-commitments.png");
        commitments.close();
        commitments = null;
    }

    private void seed(String language) throws Exception {
        LocaleHelper.setLanguage(context, language);
        RegionHelper.setRegion(context, "fa".equals(language)
            ? RegionHelper.REGION_IRAN : RegionHelper.REGION_INTERNATIONAL);
        long now = System.currentTimeMillis();
        LinkedHashMap<String, Bank> banks = new LinkedHashMap<>();
        banks.put(BalanceData.storageKey("Tejarat", ACCOUNT),
            new Bank("Tejarat", 128_450_000L, now - 60_000L, "TejaratBank", ACCOUNT));
        banks.put(BalanceData.storageKey("Mellat", "00000000002"),
            new Bank("Mellat", 74_200_000L, now, "BankMellat", "00000000002"));
        banks.put(BalanceData.storageKey("Blu", "00000000003"),
            new Bank("Blu", 32_000_000L, now, "Blu", "00000000003"));
        assertTrue("synthetic balances must save", BalanceData.write(context, banks));

        Transaction salary = transaction(now - 180_000L, 150_000_000L, 150_000_000L, "سامانه پل");
        Transaction rent = transaction(now - 120_000L, -19_200_000L, 130_800_000L, "همراه بانک");
        featured = transaction(now - 60_000L, -2_350_000L, 128_450_000L, "پایانه فروش");
        assertTrue("synthetic movements must save",
            BalanceData.writeTransactions(context, Arrays.asList(salary, rent, featured)));
        featuredNote = localized(language, "Demo · weekly groceries", "نمونه · خرید هفتگی");
        BalanceData.setNote(context, featured, featuredNote);
        BalanceData.setNote(context, salary, localized(language, "Demo · monthly salary", "نمونه · حقوق ماهانه"));
        BalanceData.setNote(context, rent, localized(language, "Demo · rent payment", "نمونه · پرداخت اجاره"));
        String demo = localized(language, "Demo", "نمونه");
        BalanceData.setTags(context, featured, Arrays.asList(demo,
            localized(language, "Groceries", "خرید روزانه")));
        BalanceData.setTags(context, salary, Arrays.asList(demo,
            localized(language, "Income", "درآمد")));
        BalanceData.setTags(context, rent, Arrays.asList(demo,
            localized(language, "Home", "خانه")));
        BalanceData.mergeChannels(context, Collections.singletonMap(BalanceData.noteKey(featured), "پایانه فروش"));
        BalanceData.mergeChannels(context, Collections.singletonMap(BalanceData.noteKey(rent), "همراه بانک"));

        long due = Commitment.startOfDay(now);
        BalanceData.writeCommitments(context, Arrays.asList(
            Commitment.create(localized(language, "Demo · apartment rent", "نمونه · اجاره خانه"),
                -19_200_000L, Commitment.MONTHLY, due, null, false, 0),
            Commitment.create(localized(language, "Demo · language course", "نمونه · کلاس زبان"),
                -3_500_000L, Commitment.MONTHLY, due, null, false, 0),
            Commitment.create(localized(language, "Demo · project payment", "نمونه · دریافتی پروژه"),
                12_000_000L, Commitment.ONCE, due, null, false, 0)));
    }

    private Transaction transaction(long date, long amount, long balance, String channel)
            throws Exception {
        String direction = amount > 0 ? "واریز" : "برداشت";
        String body = "*بانک تجارت*\nحساب: " + ACCOUNT + "\n" + direction + ": "
            + String.format(Locale.US, "%,d", Math.abs(amount)) + " ریال\nاز طريق: " + channel
            + "\nمانده: " + String.format(Locale.US, "%,d", balance)
            + " ریال\nنمونه ساختگی — Demo data";
        String sender = "TejaratBank";
        Transaction transaction = new Transaction("Tejarat", ACCOUNT, date, amount, balance,
            BalanceData.messageSig(sender, body, ACCOUNT), BalanceData.contentHash(sender, body));
        // These are invented evidence records in the app store, not messages in the SMS inbox.
        SourceStore.Source source = SourceStore.capture(context, sender, body, date, BankRules.VERSION);
        assertNotNull("synthetic evidence source must save", source);
        assertTrue(SourceStore.observeTransaction(context, source, BankRules.VERSION, transaction));
        assertTrue(SourceStore.observeBalance(context, source, BankRules.VERSION,
            "Tejarat", ACCOUNT, date, balance));
        return transaction;
    }

    private void save(File directory, String name) throws Exception {
        instrumentation.waitForIdleSync();
        // Allow native window/IME/scrollbar animations to finish; pixels remain unmodified.
        SystemClock.sleep(700L);
        if ("true".equals(InstrumentationRegistry.getArguments().getString("storeScreenshotsEmptyInbox"))) {
            assertEquals(PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.READ_SMS));
            assertEmptyInbox(context);
        } else {
            assertEquals("SMS access must stay denied throughout capture", PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission(Manifest.permission.READ_SMS));
        }
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("UiAutomation must return real display pixels", screenshot);
        File file = new File(directory, name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            assertTrue("native PNG must encode", screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            screenshot.recycle();
        }
        assertTrue("native PNG must not be empty", file.length() > 0);
        Bundle status = new Bundle();
        status.putString("stream", "\nSaved cache/store-screenshots/" + directory.getName() + "/" + name + "\n");
        instrumentation.sendStatus(0, status);
    }

    private static void assertEmptyInbox(Context target) {
        try (android.database.Cursor rows = target.getContentResolver().query(
                android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                new String[]{android.provider.Telephony.Sms._ID}, null, null, null)) {
            assertNotNull("the emulator inbox must be accessible", rows);
            assertTrue("clear the disposable emulator inbox before healthy captures", !rows.moveToFirst());
        }
    }

    private String property(String name) throws Exception {
        try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation()
                .executeShellCommand("getprop " + name);
             ParcelFileDescriptor.AutoCloseInputStream input =
                new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[128];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
        }
    }

    private static String localized(String language, String english, String persian) {
        return "fa".equals(language) ? persian : english;
    }

    private static TextView findText(View root, String wanted) {
        if (root instanceof TextView && wanted.contentEquals(((TextView) root).getText()))
            return (TextView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), wanted);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean containsText(View root, String wanted) {
        if (root instanceof TextView && ((TextView) root).getText().toString().contains(wanted))
            return true;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++)
                if (containsText(group.getChildAt(i), wanted)) return true;
        }
        return false;
    }

    private static void await(Check check, String description) {
        long deadline = SystemClock.uptimeMillis() + 30_000L;
        do {
            if (check.ready()) return;
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("timed out waiting for " + description);
    }

    private interface Check { boolean ready(); }
}
