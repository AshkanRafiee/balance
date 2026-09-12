package com.ashkanrafiee.balance;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int SMS_REQUEST = 10;
    private static final int REQ_CREATE_BACKUP = 20;
    private static final int REQ_PICK_RESTORE = 21;
    private BalanceView view;
    private String pendingBackupPassword;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 30)
            getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(resColor(R.color.status_bar));
        getWindow().setNavigationBarColor(resColor(R.color.nav_bar));
        view = new BalanceView();
        setContentView(view);
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            view.insetsTop = insets.getSystemWindowInsetTop();
            view.insetsBottom = insets.getSystemWindowInsetBottom();
            view.invalidate();
            return insets;
        });
        requestSms();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (view != null) view.refresh();
    }

    /**
     * Forces a full redraw when the window regains focus. Without this, a frame
     * drawn around an Activity recreation can leave the bottom strip (below the
     * footer) showing the dark window background until something triggers a
     * redraw (e.g. tapping the eye). Repainting here clears that stale frame.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && view != null) view.invalidate();
    }

    private void requestSms() {
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.READ_SMS}, SMS_REQUEST);
        else view.refresh();
    }

    @Override
    public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r, p, g);
        if (r == SMS_REQUEST) view.refresh();
    }

    private void languageDialog() {
        String[] tags = LocaleHelper.SUPPORTED;
        String[] labels = new String[tags.length];
        for (int i = 0; i < tags.length; i++)
            labels[i] = tags[i].isEmpty() ? getString(R.string.language_system_default) : LocaleHelper.displayName(tags[i]);
        String current = LocaleHelper.currentTag(this);
        int checkedIndex = 0;
        for (int i = 0; i < tags.length; i++) if (tags[i].equals(current)) { checkedIndex = i; break; }
        new android.app.AlertDialog.Builder(this).setTitle(getString(R.string.dialog_language_title))
            .setSingleChoiceItems(labels, checkedIndex, (dialogInterface, which) -> {
                LocaleHelper.setLanguage(this, tags[which]);
                dialogInterface.dismiss();
                recreate();
            }).show();
    }

    void hardRefreshDialog() {
        new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_hard_refresh_title))
            .setMessage(getString(R.string.dialog_hard_refresh_message))
            .setNegativeButton(getString(R.string.dialog_hard_refresh_cancel), null)
            .setPositiveButton(getString(R.string.dialog_hard_refresh_confirm), (d, w) -> view.refresh(true))
            .show();
    }

    // ====================================================================
    // Encrypted backup / restore
    // ====================================================================

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + .5f);
    }

    /** [top, bottom] system-bar insets in px across every supported API level. */
    private int[] systemBarInsets(android.view.WindowInsets ins) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            return new int[]{
                ins.getInsets(android.view.WindowInsets.Type.statusBars()).top,
                ins.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom,
            };
        }
        return new int[]{ins.getSystemWindowInsetTop(), ins.getSystemWindowInsetBottom()};
    }

    private void backupDialog() {
        String[] options = {
            getString(R.string.backup_action_create),
            getString(R.string.backup_action_restore)
        };
        new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.backup_title))
            .setItems(options, (d, which) -> {
                if (which == 0) askPassword(true, null);
                else pickRestoreSource();
            })
            .show();
    }

    private void pickBackupTarget() {
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType("application/octet-stream");
        create.putExtra(Intent.EXTRA_TITLE, backupFileName());
        startActivityForResult(create, REQ_CREATE_BACKUP);
    }

    private void pickRestoreSource() {
        Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        open.addCategory(Intent.CATEGORY_OPENABLE);
        open.setType("*/*");
        startActivityForResult(open, REQ_PICK_RESTORE);
    }

    private String backupFileName() {
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .format(new java.util.Date());
        return "balance-backup-" + stamp + ".balance";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CREATE_BACKUP) {
            String password = pendingBackupPassword;
            pendingBackupPassword = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || password == null) return;
            createBackup(data.getData(), password);
        } else if (requestCode == REQ_PICK_RESTORE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null)
                askPassword(false, data.getData());
        }
    }

    private void askPassword(boolean forBackup, Uri restoreUri) {
        askPassword(forBackup, restoreUri, null);
    }

    private void askPassword(boolean forBackup, Uri restoreUri, String warning) {
        EditText password = new EditText(this);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        password.setHint(getString(R.string.backup_password_hint));
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(8), dp(24), 0);
        if (warning != null) {
            TextView error = new TextView(this);
            error.setText(warning);
            error.setTextColor(0xFFB91C1C);
            error.setTextSize(13);
            error.setPadding(0, 0, 0, dp(10));
            layout.addView(error);
        }
        TextView info = new TextView(this);
        info.setTextSize(13);
        info.setText(forBackup ? getString(R.string.backup_password_info) : getString(R.string.backup_restore_info));
        info.setPadding(0, 0, 0, dp(10));
        layout.addView(info);
        layout.addView(password);
        EditText[] confirm = {null};
        if (forBackup) {
            confirm[0] = new EditText(this);
            confirm[0].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            confirm[0].setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            confirm[0].setHint(getString(R.string.backup_password_confirm_hint));
            layout.addView(confirm[0]);
        }
        EditText pw = password;
        EditText cf = confirm[0];
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
            .setTitle(forBackup
                ? getString(R.string.backup_create_title)
                : getString(R.string.backup_restore_title))
            .setView(layout)
            .setNegativeButton(getString(R.string.dialog_hard_refresh_cancel), null)
            .setPositiveButton(forBackup
                ? getString(R.string.backup_action_create)
                : getString(R.string.backup_restore_confirm), null)
            .create();
        dlg.setOnShowListener(d -> dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v -> {
                String value = pw.getText().toString();
                if (value.isEmpty()) {
                    Toast.makeText(MainActivity.this, getString(R.string.backup_validate_empty), Toast.LENGTH_SHORT).show();
                    return;
                }
                if (forBackup && !value.equals(cf.getText().toString())) {
                    Toast.makeText(MainActivity.this, getString(R.string.backup_validate_mismatch), Toast.LENGTH_SHORT).show();
                    return;
                }
                dlg.dismiss();
                if (forBackup) {
                    pendingBackupPassword = value;
                    pickBackupTarget();
                } else {
                    restoreBackup(restoreUri, value);
                }
            }));
        dlg.show();
    }

    private void createBackup(Uri uri, String password) {
        showProgress(getString(R.string.backup_progress_creating));
        new Thread(() -> {
            final int[] error = {0};
            try {
                BackupManager.create(getApplicationContext(), uri, password);
            } catch (BackupManager.BackupException e) {
                error[0] = e.resId;
            } catch (Exception e) {
                android.util.Log.w("BalanceBackup", "create failed", e);
                error[0] = R.string.backup_error_generic;
            }
            int err = error[0];
            runOnUiThread(() -> {
                dismissProgress();
                if (err != 0) {
                    Toast.makeText(MainActivity.this,
                        getString(R.string.backup_create_failed) + "\n" + getString(err),
                        Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(MainActivity.this, getString(R.string.backup_created), Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private void restoreBackup(Uri uri, String password) {
        showProgress(getString(R.string.backup_progress_restoring));
        new Thread(() -> {
            final int[] error = {0};
            final BackupManager.RestoreResult[] result = {null};
            try {
                result[0] = BackupManager.restore(getApplicationContext(), uri, password);
            } catch (BackupManager.BackupException e) {
                error[0] = e.resId;
            } catch (Exception e) {
                android.util.Log.w("BalanceBackup", "restore failed", e);
                error[0] = R.string.backup_error_generic;
            }
            int err = error[0];
            BackupManager.RestoreResult res = result[0];
            runOnUiThread(() -> {
                dismissProgress();
                if (err != 0) {
                    if (err == R.string.backup_error_password) {
                        // Wrong password (or a corrupted backup): let the user retry the
                        // password for the same file instead of forcing a new file pick.
                        askPassword(false, uri, getString(R.string.backup_error_password));
                    } else {
                        Toast.makeText(MainActivity.this,
                            getString(R.string.backup_restore_failed) + "\n" + getString(err),
                            Toast.LENGTH_LONG).show();
                    }
                } else {
                    view.loadSaved();
                    String summary = res.changed()
                        ? getString(R.string.backup_restore_summary, res.added, res.updated)
                        : getString(R.string.backup_restore_summary_none);
                    Toast.makeText(MainActivity.this,
                        getString(R.string.backup_restored) + "\n" + summary,
                        Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private android.app.AlertDialog progressDialog;

    private void showProgress(String message) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        int margin = dp(24);
        wrap.setPadding(margin, dp(18), margin, dp(14));
        TextView tv = new TextView(this);
        tv.setText(message);
        tv.setTextSize(15);
        tv.setTextColor(resColor(R.color.fg));
        wrap.addView(tv);
        // An indeterminate frame animation that stays visibly moving on every
        // supported Android version and theme, which matters while the decrypt
        // work is running in the background.
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setIndeterminateDrawable(getResources().getDrawable(
            android.R.drawable.progress_indeterminate_horizontal, getTheme()));
        bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(resColor(R.color.accent)));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
        barLp.topMargin = dp(14);
        bar.setLayoutParams(barLp);
        wrap.addView(bar);
        progressDialog = new android.app.AlertDialog.Builder(this)
            .setView(wrap).setCancelable(false).create();
        progressDialog.show();
        // The old password dialog was just dismissed with its soft keyboard still
        // animating away. Pin this dialog to the exact middle of the screen:
        // the window manager centers dialogs inside the area that excludes the
        // status and navigation bars, so without a correction the card sits
        // (statusBarHeight - navBarHeight) / 2 px off the true middle of the
        // display. The a.y offset below (positive moves down from the center)
        // restores an equal margin above and below the card on the full screen.
        android.view.Window win = progressDialog.getWindow();
        if (win != null) {
            win.setLayout(dp(300), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            win.setGravity(Gravity.CENTER);
            win.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            android.view.WindowInsets ins = getWindow().getDecorView().getRootWindowInsets();
            if (ins != null) {
                int[] sb = systemBarInsets(ins);
                android.view.WindowManager.LayoutParams a = win.getAttributes();
                a.y = (sb[1] - sb[0]) / 2;
                win.setAttributes(a);
            }
        }
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
    }

    private int resColor(int res) {
        return getResources().getColor(res, getTheme());
    }

    private final class BalanceView extends View {
        final Paint p = new Paint(3);
        final LinkedHashMap<String, Bank> banks = new LinkedHashMap<>();
        final java.util.Set<String> excluded = new java.util.HashSet<>();
        final float d = getResources().getDisplayMetrics().density;
        Drawable refreshIcon;
        boolean hidden, refreshing;
        int insetsTop, insetsBottom;
        int sortMode;
        float scrollY = 0, lastY, downY, refreshAngle;
        boolean dragging;
        boolean hardArmed;
        boolean hardProbeFired;
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable hardRefreshProbe = () -> {
            hardProbeFired = true;
            if (MainActivity.this.isFinishing() || MainActivity.this.isDestroyed()) return;
            MainActivity.this.hardRefreshDialog();
        };
        String status = getString(R.string.status_reading_sms);
        long total;
        float footerAboutStart, footerAboutEnd, footerLangStart, footerLangEnd,
            footerBackupStart, footerBackupEnd, footerHistoryStart, footerHistoryEnd, footerY;
        final int fg = resColor(R.color.fg);
        final int muted = resColor(R.color.muted);
        final int accent = resColor(R.color.accent);
        final int purple = resColor(R.color.purple);
        final int panel = resColor(R.color.panel);
        final int bg = resColor(R.color.bg);
        final int[] bankColors = {
            Color.rgb(14, 165, 233), Color.rgb(139, 92, 246),
            Color.rgb(16, 185, 129), Color.rgb(245, 158, 11),
            Color.rgb(244, 63, 94), Color.rgb(20, 184, 166)
        };
        BalanceView() {
            super(MainActivity.this);
            hidden = BalanceData.isHidden(MainActivity.this);
            sortMode = BalanceData.getSort(MainActivity.this);
            p.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL));
            setBackgroundColor(bg);
        }

        boolean isRtl() {
            return getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        }

        void refresh() { refresh(false); }

        /** Recomputes the total from included banks only. */
        void recalcTotal() {
            total = 0;
            for (java.util.Map.Entry<String, Bank> e : banks.entrySet())
                if (!excluded.contains(e.getKey())) total += e.getValue().amount;
        }

        /** Reloads the saved balances (e.g. after a restore) without re-scanning SMS. */
        void loadSaved() {
            LinkedHashMap<String, Bank> saved = BalanceData.read(MainActivity.this);
            banks.clear();
            banks.putAll(saved);
            excluded.clear();
            excluded.addAll(BalanceData.getExcluded(MainActivity.this));
            recalcTotal();
            status = getString(R.string.status_loaded_from_saved);
            refreshing = false;
            invalidate();
            BalanceWidgetProvider.push(MainActivity.this);
        }

        /** Refreshes from the SMS inbox. With {@code hard} set, saved balances are discarded first and
         *  only the messages currently in the inbox are re-read, so banks whose SMS are no longer
         *  available disappear. Callers must already have shown the consequence dialog. */
        void refresh(boolean hard) {
            if (refreshing) return;
            if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                status = getString(R.string.status_permission_needed);
                invalidate();
                return;
            }
            refreshing = true;
            status = getString(R.string.status_refreshing);
            invalidate();
            // Resolve the status messages on the UI thread; the worker below must not call getString()
            // off the main thread (it can hit a stale configuration after a recreate).
            String statusNoSms = getString(R.string.status_no_sms_found);
            String statusLoaded = getString(R.string.status_loaded_from_saved);
            new Thread(() -> {
                final Context app = MainActivity.this.getApplicationContext();
                try {
                    if (hard) BalanceData.reset(app);
                    LinkedHashMap<String, Bank> saved = BalanceData.read(app);
                    int count = BalanceData.scanSms(app, saved);
                    post(() -> {
                        banks.clear();
                        banks.putAll(saved);
                        excluded.clear();
                        excluded.addAll(BalanceData.getExcluded(app));
                        recalcTotal();
                        status = buildStatus(count, saved.isEmpty(), statusNoSms, statusLoaded);
                        refreshing = false;
                        invalidate();
                        BalanceWidgetProvider.push(app);
                    });
                } catch (Exception e) {
                    post(() -> {
                        LinkedHashMap<String, Bank> saved2 = BalanceData.read(app);
                        banks.clear();
                        banks.putAll(saved2);
                        excluded.clear();
                        excluded.addAll(BalanceData.getExcluded(app));
                        recalcTotal();
                        status = banks.isEmpty() ? getString(R.string.status_sms_unreadable) : statusLoaded;
                        refreshing = false;
                        invalidate();
                        BalanceWidgetProvider.push(app);
                    });
                }
                // History is re-scanned independently of balances, on this same worker thread so it
                // never stutters the UI; an open history screen re-renders via the change listener.
                BalanceData.scanHistory(app);
            }).start();
        }

        private String buildStatus(int count, boolean empty, String noSms, String loaded) {
            if (count == 0)
                return empty ? noSms : loaded;
            return getResources().getQuantityString(R.plurals.status_updated_banks, count, count);
        }

        void value(Canvas c, long n, float x, float baseline, float width,
                   float size, Paint.Align align, boolean strikethrough) {
            if (hidden) {
                text(c, "\u2022\u2022\u2022\u2022\u2022\u2022", x, baseline, size, fg, align);
                return;
            }
            String number = BalanceData.toman(MainActivity.this, n);
            float current = size;
            while (current > 10 && measure(number, current) > width) current -= 1;
            text(c, number, x, baseline, current, accent, align);
            text(c, getString(R.string.unit_toman), x, baseline + 19, 11, muted, align);
            if (strikethrough) {
                float numW = measure(number, current);
                float lineX1 = align == Paint.Align.RIGHT ? x - numW : x;
                float lineX2 = align == Paint.Align.RIGHT ? x : x + numW;
                p.setColor(muted);
                p.setStrokeWidth(2f);
                c.drawLine(lineX1, baseline - 5, lineX2, baseline - 5, p);
            }
        }

        void value(Canvas c, long n, float x, float baseline, float width,
                   float size, Paint.Align align) {
            value(c, n, x, baseline, width, size, align, false);
        }

        void totalValue(Canvas c, long n, float x, float baseline, float width, boolean rtl) {
            Paint.Align anchor = rtl ? Paint.Align.RIGHT : Paint.Align.LEFT;
            if (hidden) {
                text(c, "\u2022\u2022\u2022\u2022\u2022\u2022", x, baseline, 34, fg, anchor);
                return;
            }
            String number = BalanceData.toman(MainActivity.this, n);
            String unit = getString(R.string.unit_toman);
            float unitSize = 13, unitGap = 10, unitWidth = measure(unit, unitSize);
            float current = 34;
            while (current > 16 && measure(number, current) + unitGap + unitWidth > width) current -= 1;
            float numberWidth = measure(number, current);
            text(c, number, x, baseline, current, fg, anchor);
            float unitX = rtl ? x - numberWidth - unitGap : x + numberWidth + unitGap;
            text(c, unit, unitX, baseline - 2, unitSize, muted, anchor);
        }

        float measure(String value, float size) {
            p.setTextSize(size);
            p.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL));
            return p.measureText(value);
        }

        String fit(String value, float size, float max) {
            p.setTextSize(size);
            p.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL));
            if (p.measureText(value) <= max) return value;
            String s = value;
            while (s.length() > 1 && p.measureText(s + "\u2026") > max)
                s = s.substring(0, s.length() - 1);
            return s + "\u2026";
        }

        void text(Canvas c, String s, float x, float y, float size,
                  int color, Paint.Align align) {
            p.setTextSize(size);
            p.setColor(color);
            p.setTextAlign(align);
            p.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL));
            c.drawText(s, x, y, p);
        }

        void round(Canvas c, float l, float t, float r, float b,
                   float rad, int color) {
            p.setColor(color);
            c.drawRoundRect(new RectF(l, t, r, b), rad, rad, p);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            boolean rtl = isRtl();
            int top = insetsTop, bottom = insetsBottom;
            if (top == 0 && bottom == 0 && android.os.Build.VERSION.SDK_INT >= 23
                    && getRootWindowInsets() != null) {
                top = getRootWindowInsets().getSystemWindowInsetTop();
                bottom = getRootWindowInsets().getSystemWindowInsetBottom();
            }
            c.save();
            c.translate(0, top);
            c.scale(d, d);
            int w = (int) (getWidth() / d);
            c.drawColor(bg);

            Paint.Align edgeAlign = rtl ? Paint.Align.RIGHT : Paint.Align.LEFT;
            float edgeX = rtl ? w - 32 : 32;
            text(c, getString(R.string.app_name), edgeX, 58, 25, fg, edgeAlign);
            text(c, fit(getString(R.string.subtitle_offline_bank_balances), 14, w - 64), edgeX, 86, 14, muted, edgeAlign);

            drawRefreshIcon(c, accent);
            if (refreshing) {
                refreshAngle = (refreshAngle + 18) % 360;
                postInvalidateOnAnimation();
            }

            round(c, 24, 120, w - 24, 270, 28, panel);
            float totalLabelX = rtl ? w - 48 : 48;
            text(c, getString(R.string.total_balance_label), totalLabelX, 158, 13, muted, edgeAlign);
            totalValue(c, total, totalLabelX, 220, w - 150, rtl);
            RectF eyeRect = rtl ? new RectF(45, 147, 75, 165) : new RectF(w - 75, 147, w - 45, 165);
            float eyeCenterX = rtl ? 60 : w - 60;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.5f);
            p.setColor(accent);
            c.drawOval(eyeRect, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(accent);
            c.drawCircle(eyeCenterX, 156, 5, p);
            if (hidden) {
                p.setColor(accent);
                p.setStrokeWidth(2.5f);
                if (rtl) c.drawLine(43, 142, 77, 170, p);
                else c.drawLine(w - 77, 142, w - 43, 170, p);
            }

            float banksHeaderX = rtl ? w - 28 : 28;
            text(c, getString(R.string.section_banks), banksHeaderX, 320, 22, fg, edgeAlign);
            float sortX = rtl ? 28 : w - 28;
            Paint.Align sortAlign = rtl ? Paint.Align.LEFT : Paint.Align.RIGHT;
            text(c, sortLabel(), sortX, 320, 14, accent, sortAlign);

            float by = (getHeight() - top - bottom) / d - 32;
            c.save();
            c.clipRect(0, 352, w, by - 42);
            float y = 352 - scrollY;
            if (banks.isEmpty()) {
                round(c, 24, y, w - 24, y + 96, 22, panel);
                float statusX = rtl ? w - 48 : 48;
                text(c, fit(status, 15, w - 96), statusX, y + 56, 15, muted, edgeAlign);
            } else for (Bank b : BalanceData.orderForDisplay(banks, excluded, sortMode)) {
                boolean excluded = this.excluded.contains(b.name);
                int cardColor = excluded ? bg : panel;
                round(c, 24, y, w - 24, y + 82, 20, cardColor);
                String displayName = BankRules.displayName(MainActivity.this, b.name);
                float valueWidth = Math.min(150, Math.max(115, w * .38f));
                float valueLeft = w - 60 - valueWidth;
                float badgeX = rtl ? w - 55 : 55;
                float nameX = rtl ? w - 88 : 88;
                float valueX = rtl ? 60 : w - 60;
                float menuX = rtl ? 36 : w - 36;
                Paint.Align nameAlign = rtl ? Paint.Align.RIGHT : Paint.Align.LEFT;
                Paint.Align valueAlign = rtl ? Paint.Align.LEFT : Paint.Align.RIGHT;
                bankBadge(c, b.name, badgeX, y + 41);
                p.setColor(muted);
                for (int dot = -1; dot <= 1; dot++)
                    c.drawCircle(menuX, y + 41 + dot * 4.5f, 1.8f, p);
                text(c, fit(displayName, 17, Math.max(40, valueLeft - 100)), nameX, y + 36, 17,
                    excluded ? muted : fg, nameAlign);
                if (excluded) {
                    value(c, b.amount, valueX, y + 35, valueWidth, 17, valueAlign, true);
                    String exLabel = getString(R.string.excluded_label);
                    text(c, exLabel, nameX, y + 68, 11, muted, nameAlign);
                } else {
                    value(c, b.amount, valueX, y + 35, valueWidth, 17, valueAlign);
                }
                y += 96;
            }
            c.restore();

            p.setTextSize(13);
            p.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL));
            String aboutText = getString(R.string.footer_about);
            String langText = getString(R.string.footer_language);
            String backupText = getString(R.string.footer_backup);
            String historyText = getString(R.string.footer_history);
            String sep = "  \u00b7  ";
            float aboutW = measure(aboutText, 13), langW = measure(langText, 13),
                backupW = measure(backupText, 13), historyW = measure(historyText, 13),
                sepW = measure(sep, 13);
            float totalW = aboutW + langW + backupW + historyW + sepW * 3;
            float scale = Math.min(1, (w - 64) / totalW);
            float x0 = (w - totalW * scale) / 2;
            if (!rtl) {
                footerHistoryStart = x0; text(c, historyText, x0, by + 4, 13 * scale, purple, Paint.Align.LEFT); x0 += historyW * scale; footerHistoryEnd = x0;
                text(c, sep, x0, by + 4, 13 * scale, muted, Paint.Align.LEFT); x0 += sepW * scale;
                footerBackupStart = x0; text(c, backupText, x0, by + 4, 13 * scale, purple, Paint.Align.LEFT); x0 += backupW * scale; footerBackupEnd = x0;
                text(c, sep, x0, by + 4, 13 * scale, muted, Paint.Align.LEFT); x0 += sepW * scale;
                footerLangStart = x0; text(c, langText, x0, by + 4, 13 * scale, purple, Paint.Align.LEFT); x0 += langW * scale; footerLangEnd = x0;
                text(c, sep, x0, by + 4, 13 * scale, muted, Paint.Align.LEFT); x0 += sepW * scale;
                footerAboutStart = x0; text(c, aboutText, x0, by + 4, 13 * scale, purple, Paint.Align.LEFT); x0 += aboutW * scale; footerAboutEnd = x0;
            } else {
                footerAboutStart = x0; text(c, aboutText, x0, by + 4, 13 * scale, purple, Paint.Align.LEFT); x0 += aboutW * scale; footerAboutEnd = x0;
                text(c, sep, x0, by + 4, 13 * scale, muted, Paint.Align.LEFT); x0 += sepW * scale;
                footerLangStart = x0; text(c, langText, x0, by + 4, 13 * scale, purple, Paint.Align.LEFT); x0 += langW * scale; footerLangEnd = x0;
                text(c, sep, x0, by + 4, 13 * scale, muted, Paint.Align.LEFT); x0 += sepW * scale;
                footerBackupStart = x0; text(c, backupText, x0, by + 4, 13 * scale, purple, Paint.Align.LEFT); x0 += backupW * scale; footerBackupEnd = x0;
                text(c, sep, x0, by + 4, 13 * scale, muted, Paint.Align.LEFT); x0 += sepW * scale;
                footerHistoryStart = x0; text(c, historyText, x0, by + 4, 13 * scale, purple, Paint.Align.LEFT); x0 += historyW * scale; footerHistoryEnd = x0;
            }
            footerY = by;
            c.restore();
        }

        /** Uses the canonical (English) name so a bank's badge stays stable across languages: the bank's
         *  brand icon when one is available, otherwise the colored square with the bank's initials. */
        void bankBadge(Canvas c, String canonicalName, float x, float centerY) {
            int iconRes = BankIcon.iconFor(canonicalName);
            if (iconRes != 0) {
                Drawable icon = getResources().getDrawable(iconRes, getTheme());
                icon.setBounds((int) (x - 16), (int) (centerY - 16), (int) (x + 16), (int) (centerY + 16));
                icon.draw(c);
                return;
            }
            int color = bankColors[Math.floorMod(canonicalName.hashCode(), bankColors.length)];
            round(c, x - 18, centerY - 18, x + 18, centerY + 18, 12, color);
            text(c, bankInitials(canonicalName), x, centerY + 5, 11, Color.WHITE, Paint.Align.CENTER);
        }

        String bankInitials(String name) {
            String[] words = name.split(" ");
            if (words.length > 1)
                return (words[0].substring(0, 1) + words[1].substring(0, 1)).toUpperCase(Locale.US);
            return name.substring(0, Math.min(2, name.length())).toUpperCase(Locale.US);
        }

        /** The compact sort button label shown next to the "Banks" header; the arrow shows direction,
         *  so re-tapping the sort dialog's matching option reads as reversing that direction. */
        String sortLabel() {
            switch (sortMode) {
                case BalanceData.SORT_BALANCE_HIGH:
                    return getString(R.string.sort_label_balance) + " \u2193";
                case BalanceData.SORT_BALANCE_LOW:
                    return getString(R.string.sort_label_balance) + " \u2191";
                case BalanceData.SORT_DATE_RECENT:
                    return getString(R.string.sort_label_date) + " \u2193";
                case BalanceData.SORT_DATE_OLDEST:
                    return getString(R.string.sort_label_date) + " \u2191";
                default:
                    return getString(R.string.action_sort);
            }
        }

        /** Draws the rounded-arrow refresh icon (the same glyph the widget's refresh button uses) top-right
         *  of the app bar, opposite the app title. While a refresh runs, {@code refreshAngle} spins the
         *  whole icon around its center. */
        void drawRefreshIcon(Canvas c, int accent) {
            float w = getWidth() / d;
            float cx = isRtl() ? 56 : w - 56;
            float cy = 76;
            if (refreshIcon == null) {
                refreshIcon = getContext().getDrawable(R.drawable.ic_refresh).mutate();
                refreshIcon.setTint(accent);
            }
            int half = 13;
            refreshIcon.setBounds((int) (cx - half), (int) (cy - half), (int) (cx + half), (int) (cy + half));
            c.save();
            c.rotate(refreshing ? refreshAngle : 0f, cx, cy);
            refreshIcon.draw(c);
            c.restore();
        }

        /** The refresh control sits on the top-right, opposite the app title and subtitle (top-left in RTL). */
        boolean isOnRefresh(float x, float y) {
            boolean rtl = isRtl();
            return rtl ? x <= 110 && y >= 36 && y <= 100
                : x >= getWidth() / d - 110 && y >= 36 && y <= 100;
        }

        /** Picks a sort. Selecting the active category again reverses its direction, which the dialog
         *  rows communicate explicitly ("… — tap again to reverse"). Excluded banks stay at the bottom
         *  of the list in every mode. */
        void showSortDialog() {
            boolean balHigh = sortMode == BalanceData.SORT_BALANCE_HIGH;
            boolean balLow = sortMode == BalanceData.SORT_BALANCE_LOW;
            boolean dateRecent = sortMode == BalanceData.SORT_DATE_RECENT;
            boolean dateOldest = sortMode == BalanceData.SORT_DATE_OLDEST;
            String balanceLabel = balHigh ? getString(R.string.sort_balance_high_reverse)
                : balLow ? getString(R.string.sort_balance_low_reverse)
                : getString(R.string.sort_balance_prompt);
            String dateLabel = dateRecent ? getString(R.string.sort_date_recent_reverse)
                : dateOldest ? getString(R.string.sort_date_oldest_reverse)
                : getString(R.string.sort_date_prompt);
            String[] options = { balanceLabel, dateLabel };
            new android.app.AlertDialog.Builder(MainActivity.this)
                .setTitle(getString(R.string.sort_dialog_title))
                .setItems(options, (dialogInterface, which) -> {
                    if (which == 0) sortMode = balHigh ? BalanceData.SORT_BALANCE_LOW
                        : BalanceData.SORT_BALANCE_HIGH;
                    else sortMode = dateRecent ? BalanceData.SORT_DATE_OLDEST
                        : BalanceData.SORT_DATE_RECENT;
                    BalanceData.setSort(MainActivity.this, sortMode);
                    invalidate();
                })
                .show();
        }

        void copyBalance(String label, long value) {
            if (hidden) {
                Toast.makeText(MainActivity.this, getString(R.string.toast_unmask_to_copy), Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(label, Long.toString(value / 10)));
            Toast.makeText(MainActivity.this, getString(R.string.toast_copied_balance, label), Toast.LENGTH_SHORT).show();
        }

        void showBankMenu(Bank bank) {
            String displayName = BankRules.displayName(MainActivity.this, bank.name);
            boolean isExcluded = excluded.contains(bank.name);
            String[] options = {
                getString(isExcluded ? R.string.action_include : R.string.action_exclude),
                getString(R.string.action_copy_balance)
            };
            new android.app.AlertDialog.Builder(MainActivity.this)
                .setTitle(displayName)
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        BalanceData.toggleExcluded(MainActivity.this, bank.name);
                        excluded.clear();
                        excluded.addAll(BalanceData.getExcluded(MainActivity.this));
                        recalcTotal();
                        invalidate();
                        BalanceWidgetProvider.push(MainActivity.this);
                        Toast.makeText(MainActivity.this,
                            getString(excluded.contains(bank.name)
                                ? R.string.toast_excluded : R.string.toast_included),
                            Toast.LENGTH_SHORT).show();
                    } else {
                        copyBalance(displayName, bank.amount);
                    }
                })
                .show();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            boolean rtl = isRtl();
            int top = insetsTop, bottom = insetsBottom;
            if (top == 0 && bottom == 0 && android.os.Build.VERSION.SDK_INT >= 23
                    && getRootWindowInsets() != null) {
                top = getRootWindowInsets().getSystemWindowInsetTop();
                bottom = getRootWindowInsets().getSystemWindowInsetBottom();
            }
            float x = e.getX() / d, y = (e.getY() - top) / d,
                h = (getHeight() - top - bottom) / d;
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                lastY = y; downY = y; dragging = false;
                hardArmed = isOnRefresh(x, y);
                hardProbeFired = false;
                if (hardArmed) handler.postDelayed(hardRefreshProbe, 650);
                else handler.removeCallbacks(hardRefreshProbe);
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_MOVE) {
                if (Math.abs(y - lastY) > 3) {
                    dragging = true;
                    handler.removeCallbacks(hardRefreshProbe);
                    hardArmed = false;
                    scrollY = Math.max(0, Math.min(
                        Math.max(0, banks.size() * 96 - (h - 440)),
                        scrollY + lastY - y));
                    lastY = y;
                    invalidate();
                }
                return true;
            }
            if (e.getAction() != MotionEvent.ACTION_UP) return true;
            handler.removeCallbacks(hardRefreshProbe);
            if (hardProbeFired) { hardProbeFired = false; hardArmed = false; return true; }
            if (dragging) {
                if (downY < 360 && y - downY > 55) refresh();
                return true;
            }
            if (y > footerY - 20 && y < footerY + 24) {
                if (x >= footerAboutStart - 10 && x <= footerAboutEnd + 10) {
                    startActivity(new Intent(MainActivity.this, AboutActivity.class));
                } else if (x >= footerLangStart - 10 && x <= footerLangEnd + 10) {
                    MainActivity.this.languageDialog();
                } else if (x >= footerBackupStart - 10 && x <= footerBackupEnd + 10) {
                    MainActivity.this.backupDialog();
                } else if (x >= footerHistoryStart - 10 && x <= footerHistoryEnd + 10) {
                    startActivity(new Intent(MainActivity.this, HistoryActivity.class));
                }
            } else if (isOnRefresh(x, y)) {
                refresh();
            } else if (y >= 120 && y <= 270) {
                boolean onEye = rtl ? x <= 105 && y <= 185 : x >= getWidth() / d - 105 && y <= 185;
                if (onEye) {
                    hidden = !hidden;
                    MainActivity.this.getSharedPreferences(BalanceData.PREFS_PREF, MODE_PRIVATE)
                        .edit().putBoolean(BalanceData.KEY_HIDDEN, hidden).apply();
                    invalidate();
                } else copyBalance(getString(R.string.total_label), total);
            } else if (y > 290 && y < 350 && (rtl ? x < 150 : x > getWidth() / d - 150)) {
                showSortDialog();
            } else if (y >= 352 && y < byForTouch(h)) {
                int index = (int) ((y - 352 + scrollY) / 96);
                float rowOffset = (y - 352 + scrollY) % 96;
                if (rowOffset < 82 && index >= 0 && index < banks.size()) {
                    int i = 0;
                    for (Bank bank : BalanceData.orderForDisplay(banks, excluded, sortMode)) {
                        if (i++ == index) {
                            boolean onMenu = rtl
                                ? x >= 16 && x <= 56
                                : x >= getWidth() / d - 56 && x <= getWidth() / d - 16;
                            if (onMenu) showBankMenu(bank);
                            else copyBalance(
                                BankRules.displayName(MainActivity.this, bank.name), bank.amount);
                            break;
                        }
                    }
                }
            }
            performClick();
            return true;
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        float byForTouch(float h) { return h - 74; }
    }
}
