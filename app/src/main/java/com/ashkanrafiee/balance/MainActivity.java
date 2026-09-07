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

    private void backupDialog() {
        String[] options = {
            getString(R.string.backup_action_create),
            getString(R.string.backup_action_restore)
        };
        new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.backup_title))
            .setItems(options, (d, which) -> {
                if (which == 0) askPassword(true);
                else askPassword(false);
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
            String password = pendingBackupPassword;
            pendingBackupPassword = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || password == null) return;
            restoreBackup(data.getData(), password);
        }
    }

    private void askPassword(boolean forBackup) {
        EditText password = new EditText(this);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setHint(getString(R.string.backup_password_hint));
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(8), dp(24), 0);
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
                pendingBackupPassword = value;
                if (forBackup) pickBackupTarget();
                else pickRestoreSource();
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
                    Toast.makeText(MainActivity.this,
                        getString(R.string.backup_restore_failed) + "\n" + getString(err),
                        Toast.LENGTH_LONG).show();
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
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(24);
        wrap.setPadding(pad, dp(18), pad, dp(10));
        wrap.addView(new ProgressBar(this));
        TextView tv = new TextView(this);
        tv.setText(message);
        tv.setTextSize(14);
        tv.setPadding(dp(16), 0, 0, 0);
        wrap.addView(tv);
        progressDialog = new android.app.AlertDialog.Builder(this)
            .setView(wrap).setCancelable(false).create();
        progressDialog.show();
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
        final float d = getResources().getDisplayMetrics().density;
        boolean hidden, refreshing;
        int insetsTop, insetsBottom;
        float scrollY = 0, lastY, downY, refreshAngle;
        boolean dragging;
        boolean hardArmed;
        boolean hardProbeFired;
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable hardRefreshProbe = () -> {
            hardProbeFired = true;
            MainActivity.this.hardRefreshDialog();
        };
        String status = getString(R.string.status_reading_sms);
        long total;
        float footerAboutStart, footerAboutEnd, footerLangStart, footerLangEnd,
            footerBackupStart, footerBackupEnd, footerY;
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
            p.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL));
            setBackgroundColor(bg);
        }

        boolean isRtl() {
            return getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        }

        void refresh() { refresh(false); }

        /** Reloads the saved balances (e.g. after a restore) without re-scanning SMS. */
        void loadSaved() {
            LinkedHashMap<String, Bank> saved = BalanceData.read(MainActivity.this);
            banks.clear();
            banks.putAll(saved);
            total = 0;
            for (Bank b : banks.values()) total += b.amount;
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
            new Thread(() -> {
                final Context app = MainActivity.this.getApplicationContext();
                try {
                    if (hard) BalanceData.reset(app);
                    LinkedHashMap<String, Bank> saved = BalanceData.read(app);
                    int count = BalanceData.scanSms(app, saved);
                    String message = count == 0
                        ? (saved.isEmpty()
                            ? getString(R.string.status_no_sms_found)
                            : getString(R.string.status_loaded_from_saved))
                        : getResources().getQuantityString(R.plurals.status_updated_banks, count, count);
                    post(() -> {
                        banks.clear();
                        banks.putAll(saved);
                        total = 0;
                        for (Bank b : banks.values()) total += b.amount;
                        status = message;
                        refreshing = false;
                        invalidate();
                        BalanceWidgetProvider.push(app);
                    });
                } catch (Exception e) {
                    post(() -> {
                        LinkedHashMap<String, Bank> saved = BalanceData.read(app);
                        banks.clear();
                        banks.putAll(saved);
                        total = 0;
                        for (Bank b : banks.values()) total += b.amount;
                        status = banks.isEmpty() ? getString(R.string.status_sms_unreadable) : getString(R.string.status_loaded_from_saved);
                        refreshing = false;
                        invalidate();
                        BalanceWidgetProvider.push(app);
                    });
                }
            }).start();
        }

        void value(Canvas c, long n, float x, float baseline, float width,
                   float size, Paint.Align align) {
            if (hidden) {
                text(c, "\u2022\u2022\u2022\u2022\u2022\u2022", x, baseline, size, fg, align);
                return;
            }
            String number = BalanceData.toman(MainActivity.this, n);
            float current = size;
            while (current > 10 && measure(number, current) > width) current -= 1;
            text(c, number, x, baseline, current, accent, align);
            text(c, getString(R.string.unit_toman), x, baseline + 19, 11, muted, align);
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
            RectF refreshArc = rtl ? new RectF(30, 297, 58, 325) : new RectF(w - 58, 297, w - 30, 325);
            if (refreshing) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3);
                p.setColor(accent);
                c.drawArc(refreshArc, refreshAngle, 270, false, p);
                p.setStyle(Paint.Style.FILL);
                refreshAngle = (refreshAngle + 14) % 360;
                postInvalidateOnAnimation();
            } else {
                float refreshX = rtl ? 28 : w - 28;
                Paint.Align refreshAlign = rtl ? Paint.Align.LEFT : Paint.Align.RIGHT;
                text(c, getString(R.string.action_refresh), refreshX, 320, 14, accent, refreshAlign);
            }

            float by = (getHeight() - top - bottom) / d - 32;
            c.save();
            c.clipRect(0, 352, w, by - 42);
            float y = 352 - scrollY;
            if (banks.isEmpty()) {
                round(c, 24, y, w - 24, y + 96, 22, panel);
                float statusX = rtl ? w - 48 : 48;
                text(c, fit(status, 15, w - 96), statusX, y + 56, 15, muted, edgeAlign);
            } else for (Bank b : banks.values()) {
                round(c, 24, y, w - 24, y + 82, 20, panel);
                String displayName = BankRules.displayName(MainActivity.this, b.name);
                float valueWidth = Math.min(170, Math.max(125, w * .40f));
                float valueLeft = w - 48 - valueWidth;
                float badgeX = rtl ? w - 55 : 55;
                float nameX = rtl ? w - 88 : 88;
                float valueX = rtl ? 48 : w - 48;
                Paint.Align nameAlign = rtl ? Paint.Align.RIGHT : Paint.Align.LEFT;
                Paint.Align valueAlign = rtl ? Paint.Align.LEFT : Paint.Align.RIGHT;
                bankBadge(c, b.name, badgeX, y + 41);
                text(c, fit(displayName, 17, Math.max(40, valueLeft - 100)), nameX, y + 36, 17, fg, nameAlign);
                value(c, b.amount, valueX, y + 35, valueWidth, 17, valueAlign);
                y += 96;
            }
            c.restore();

            p.setTextSize(13);
            p.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL));
            String aboutText = getString(R.string.footer_about);
            String langText = getString(R.string.footer_language);
            String backupText = getString(R.string.footer_backup);
            String sep = "  \u00b7  ";
            float aboutW = measure(aboutText, 13), langW = measure(langText, 13),
                backupW = measure(backupText, 13), sepW = measure(sep, 13);
            float totalW = aboutW + sepW + langW + sepW + backupW;
            float x0 = (w - totalW) / 2;
            if (!rtl) {
                footerAboutStart = x0; text(c, aboutText, x0, by + 4, 13, purple, Paint.Align.LEFT); x0 += aboutW; footerAboutEnd = x0;
                text(c, sep, x0, by + 4, 13, muted, Paint.Align.LEFT); x0 += sepW;
                footerLangStart = x0; text(c, langText, x0, by + 4, 13, purple, Paint.Align.LEFT); x0 += langW; footerLangEnd = x0;
                text(c, sep, x0, by + 4, 13, muted, Paint.Align.LEFT); x0 += sepW;
                footerBackupStart = x0; text(c, backupText, x0, by + 4, 13, purple, Paint.Align.LEFT); x0 += backupW; footerBackupEnd = x0;
            } else {
                footerBackupStart = x0; text(c, backupText, x0, by + 4, 13, purple, Paint.Align.LEFT); x0 += backupW; footerBackupEnd = x0;
                text(c, sep, x0, by + 4, 13, muted, Paint.Align.LEFT); x0 += sepW;
                footerLangStart = x0; text(c, langText, x0, by + 4, 13, purple, Paint.Align.LEFT); x0 += langW; footerLangEnd = x0;
                text(c, sep, x0, by + 4, 13, muted, Paint.Align.LEFT); x0 += sepW;
                footerAboutStart = x0; text(c, aboutText, x0, by + 4, 13, purple, Paint.Align.LEFT); x0 += aboutW; footerAboutEnd = x0;
            }
            footerY = by;
            c.restore();
        }

        /** Uses the canonical (English) name so a bank's badge color and initials stay stable across languages. */
        void bankBadge(Canvas c, String canonicalName, float x, float centerY) {
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

        void copyBalance(String label, long value) {
            if (hidden) {
                Toast.makeText(MainActivity.this, getString(R.string.toast_unmask_to_copy), Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(label, Long.toString(value / 10)));
            Toast.makeText(MainActivity.this, getString(R.string.toast_copied_balance, label), Toast.LENGTH_SHORT).show();
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
                hardArmed = y > 290 && y < 350 && (rtl ? x < 150 : x > getWidth() / d - 150);
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
                }
            } else if (y >= 120 && y <= 270) {
                boolean onEye = rtl ? x <= 105 && y <= 185 : x >= getWidth() / d - 105 && y <= 185;
                if (onEye) {
                    hidden = !hidden;
                    MainActivity.this.getSharedPreferences(BalanceData.PREFS_PREF, MODE_PRIVATE)
                        .edit().putBoolean(BalanceData.KEY_HIDDEN, hidden).apply();
                    invalidate();
                } else copyBalance(getString(R.string.total_label), total);
            } else if (y > 290 && y < 350 && (rtl ? x < 150 : x > getWidth() / d - 150)) {
                refresh();
            } else if (y >= 352 && y < byForTouch(h)) {
                int index = (int) ((y - 352 + scrollY) / 96);
                float rowOffset = (y - 352 + scrollY) % 96;
                if (rowOffset < 82 && index >= 0 && index < banks.size()) {
                    int i = 0;
                    for (Bank bank : banks.values()) {
                        if (i++ == index) {
                            copyBalance(BankRules.displayName(MainActivity.this, bank.name), bank.amount);
                            break;
                        }
                    }
                }
            }
            return true;
        }

        float byForTouch(float h) { return h - 74; }
    }
}
