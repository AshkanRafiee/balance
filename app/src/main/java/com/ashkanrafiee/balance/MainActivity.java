package com.ashkanrafiee.balance;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.provider.Telephony;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;
import java.text.NumberFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int SMS_REQUEST = 10;
    private BalanceView view;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 30)
            getWindow().setDecorFitsSystemWindows(true);
        getWindow().setStatusBarColor(Color.rgb(11, 18, 32));
        getWindow().setNavigationBarColor(Color.rgb(8, 13, 22));
        view = new BalanceView();
        setContentView(view);
        requestSms();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (view != null) view.refresh();
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

    private final class BalanceView extends View {
        final Paint p = new Paint(3);
        final LinkedHashMap<String, Bank> banks = new LinkedHashMap<>();
        final float d = getResources().getDisplayMetrics().density;
        boolean hidden, refreshing;
        float scrollY = 0, lastY, downY, refreshAngle;
        boolean dragging;
        String status = getString(R.string.status_reading_sms);
        long total;
        final int[] bankColors = {
            Color.rgb(14, 165, 233), Color.rgb(139, 92, 246),
            Color.rgb(16, 185, 129), Color.rgb(245, 158, 11),
            Color.rgb(244, 63, 94), Color.rgb(20, 184, 166)
        };
        final Pattern balance = Pattern.compile(
            "(?:\u0645\u0648\u062c\u0648\u062f\u06cc \u062d\u0633\u0627\u0628" +
            "|\u0645\u0627\u0646\u062f\u0647 \u062d\u0633\u0627\u0628" +
            "|\u0645\u0648\u062c\u0648\u062f\u06cc" +
            "|\u0645\u0627\u0646\u062f\u0647" +
            "|available balance|balance|bal)" +
            "[^\\d]{0,12}?([0-9][0-9,]*)",
            Pattern.CASE_INSENSITIVE);
        final Pattern otp = Pattern.compile(
            "(?<![\u0621-\u0640A-Za-z])" +
            "(?:\u0631\u0645\u0632|\u067e\u0648\u06cc\u0627" +
            "|\u06a9\u062f \\s*\u062a\u0627\u06cc\u06cc\u062f" +
            "|\u06a9\u062f \\s*\u062a\u0623\u06cc\u06cc\u062f" +
            "|otp|code)",
            Pattern.CASE_INSENSITIVE);

        BalanceView() {
            super(MainActivity.this);
            hidden = MainActivity.this.getSharedPreferences("balance_preferences", MODE_PRIVATE)
                    .getBoolean("balances_hidden", false);
            p.setTypeface(android.graphics.Typeface.create("sans", 0));
            setBackgroundColor(Color.rgb(8, 13, 22));
        }

        void refresh() {
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
                try {
                    LinkedHashMap<String, Bank> saved = loadBalances();
                    int count = 0;
                    Cursor c = getContentResolver().query(
                        Telephony.Sms.Inbox.CONTENT_URI,
                        new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE},
                        null, null, Telephony.Sms.DATE + " DESC");
                    if (c != null) while (c.moveToNext()) {
                        String sender = c.getString(0), body = c.getString(1);
                        long date = c.getLong(2);
                        long value = extract(body);
                        if (value < 0) continue;
                        String bank = BankRules.resolve(sender);
                        if (bank == null || value <= 0) continue;
                        Bank existing = saved.get(bank);
                        if (existing == null || date > existing.date)
                            saved.put(bank, new Bank(bank, value, date, sender));
                        count++;
                    }
                    if (c != null) c.close();
                    saveBalances(saved);
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
                    });
                } catch (Exception e) {
                    post(() -> {
                        LinkedHashMap<String, Bank> saved = loadBalances();
                        banks.clear();
                        banks.putAll(saved);
                        total = 0;
                        for (Bank b : banks.values()) total += b.amount;
                        status = banks.isEmpty() ? getString(R.string.status_sms_unreadable) : getString(R.string.status_loaded_from_saved);
                        refreshing = false;
                        invalidate();
                    });
                }
            }).start();
        }

        LinkedHashMap<String, Bank> loadBalances() {
            LinkedHashMap<String, Bank> map = new LinkedHashMap<>();
            try {
                String json = getSharedPreferences("balance_data", MODE_PRIVATE)
                    .getString("balances", null);
                if (json != null) {
                    JSONObject obj = new JSONObject(json);
                    Iterator<String> keys = obj.keys();
                    while (keys.hasNext()) {
                        String bank = keys.next();
                        JSONObject entry = obj.getJSONObject(bank);
                        map.put(bank, new Bank(bank, entry.getLong("amount"),
                            entry.getLong("date"), entry.getString("sender")));
                    }
                }
            } catch (Exception e) { }
            return map;
        }

        void saveBalances(LinkedHashMap<String, Bank> map) {
            try {
                JSONObject obj = new JSONObject();
                for (java.util.Map.Entry<String, Bank> e : map.entrySet()) {
                    Bank b = e.getValue();
                    JSONObject entry = new JSONObject();
                    entry.put("amount", b.amount);
                    entry.put("date", b.date);
                    entry.put("sender", b.sender);
                    obj.put(e.getKey(), entry);
                }
                getSharedPreferences("balance_data", MODE_PRIVATE).edit()
                    .putString("balances", obj.toString()).apply();
            } catch (Exception e) { }
        }

        long extract(String raw) {
            if (raw == null) return -1;
            String s = digits(raw.replace("\u066C", ",").replace("\u060C", ","));
            if (otp.matcher(s).find()) return -1;
            Matcher m = balance.matcher(s);
            String n = null;
            while (m.find()) n = m.group(1);
            if (n == null) return -1;
            try { return Long.parseLong(n.replace(",", "")); }
            catch (Exception e) { return -1; }
        }

        String digits(String s) {
            StringBuilder b = new StringBuilder();
            for (char c : s.toCharArray()) {
                if (c >= '\u06F0' && c <= '\u06F9')
                    b.append((char) ('0' + c - '\u06F0'));
                else if (c >= '\u0660' && c <= '\u0669')
                    b.append((char) ('0' + c - '\u0660'));
                else b.append(c);
            }
            return b.toString();
        }

        String amount(long n) {
            return NumberFormat.getNumberInstance(Locale.US).format(n / 10);
        }

        void value(Canvas c, long n, float x, float baseline, float width,
                   float size, Paint.Align align) {
            if (hidden) {
                text(c, "\u2022\u2022\u2022\u2022\u2022\u2022", x, baseline, size, Color.WHITE, align);
                return;
            }
            String number = amount(n);
            float current = size;
            while (current > 10 && measure(number, current) > width) current -= 1;
            text(c, number, x, baseline, current, Color.rgb(103, 232, 249), align);
            text(c, getString(R.string.unit_toman), x, baseline + 19, 11, Color.rgb(148, 163, 184), align);
        }

        void totalValue(Canvas c, long n, float x, float baseline, float width) {
            if (hidden) {
                text(c, "\u2022\u2022\u2022\u2022\u2022\u2022", x, baseline, 34, Color.WHITE, Paint.Align.LEFT);
                return;
            }
            String number = amount(n);
            String unit = getString(R.string.unit_toman);
            float unitSize = 13, unitGap = 10, unitWidth = measure(unit, unitSize);
            float current = 34;
            while (current > 16 && measure(number, current) + unitGap + unitWidth > width) current -= 1;
            float numberWidth = measure(number, current);
            text(c, number, x, baseline, current, Color.rgb(241, 245, 249), Paint.Align.LEFT);
            text(c, unit, x + numberWidth + unitGap, baseline - 2, unitSize,
                 Color.rgb(148, 163, 184), Paint.Align.LEFT);
        }

        float measure(String value, float size) {
            p.setTextSize(size);
            p.setTypeface(android.graphics.Typeface.create("sans", 0));
            return p.measureText(value);
        }

        String fit(String value, float size, float max) {
            p.setTextSize(size);
            p.setTypeface(android.graphics.Typeface.create("sans", 0));
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
            p.setTypeface(android.graphics.Typeface.create("sans", 0));
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
            int top = 0, bottom = 0;
            if (android.os.Build.VERSION.SDK_INT >= 23 && getRootWindowInsets() != null) {
                top = getRootWindowInsets().getSystemWindowInsetTop();
                bottom = getRootWindowInsets().getSystemWindowInsetBottom();
            }
            c.save();
            c.translate(0, top);
            c.scale(d, d);
            int w = (int) (getWidth() / d);
            int fg = Color.rgb(241, 245, 249), muted = Color.rgb(148, 163, 184),
                cyan = Color.rgb(103, 232, 249), purple = Color.rgb(167, 139, 250),
                panel = Color.rgb(17, 27, 43);
            c.drawColor(Color.rgb(8, 13, 22));

            text(c, getString(R.string.app_name), 32, 58, 25, fg, Paint.Align.LEFT);
            text(c, fit(getString(R.string.subtitle_offline_bank_balances), 14, w - 64), 32, 86, 14, muted, Paint.Align.LEFT);

            round(c, 24, 120, w - 24, 270, 28, panel);
            text(c, getString(R.string.total_balance_label), 48, 158, 13, muted, Paint.Align.LEFT);
            totalValue(c, total, 48, 220, w - 150);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.5f);
            p.setColor(cyan);
            c.drawOval(new RectF(w - 75, 147, w - 45, 165), p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(cyan);
            c.drawCircle(w - 60, 156, 5, p);
            if (hidden) {
                p.setColor(cyan);
                p.setStrokeWidth(2.5f);
                c.drawLine(w - 77, 142, w - 43, 170, p);
            }

            text(c, getString(R.string.section_banks), 28, 320, 22, fg, Paint.Align.LEFT);
            if (refreshing) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3);
                p.setColor(cyan);
                c.drawArc(new RectF(w - 58, 297, w - 30, 325), refreshAngle, 270, false, p);
                p.setStyle(Paint.Style.FILL);
                refreshAngle = (refreshAngle + 14) % 360;
                postInvalidateOnAnimation();
            } else {
                text(c, getString(R.string.action_refresh), w - 28, 320, 14, cyan, Paint.Align.RIGHT);
            }

            float by = (getHeight() - top - bottom) / d - 32;
            c.save();
            c.clipRect(0, 352, w, by - 42);
            float y = 352 - scrollY;
            if (banks.isEmpty()) {
                round(c, 24, y, w - 24, y + 96, 22, panel);
                text(c, fit(status, 15, w - 96), 48, y + 56, 15, muted, Paint.Align.LEFT);
            } else for (Bank b : banks.values()) {
                round(c, 24, y, w - 24, y + 82, 20, panel);
                bankBadge(c, b.name, 55, y + 41);
                float valueWidth = Math.min(170, Math.max(125, w * .40f));
                float valueLeft = w - 48 - valueWidth;
                text(c, fit(b.name, 17, Math.max(40, valueLeft - 100)), 88, y + 36, 17, fg, Paint.Align.LEFT);
                value(c, b.amount, w - 48, y + 35, valueWidth, 17, Paint.Align.RIGHT);
                y += 96;
            }
            c.restore();

            p.setTextSize(13);
            p.setTypeface(android.graphics.Typeface.create("sans", 0));
            String footerPrefix = fit(getString(R.string.footer_prefix) + "  \u00b7  ", 13, w - 90),
                about = getString(R.string.footer_about);
            float footerWidth = p.measureText(footerPrefix) + p.measureText(about),
                footerX = (w - footerWidth) / 2;
            text(c, footerPrefix, footerX, by + 4, 13, muted, Paint.Align.LEFT);
            text(c, about, footerX + p.measureText(footerPrefix), by + 4, 13, purple, Paint.Align.LEFT);
            c.restore();
        }

        void bankBadge(Canvas c, String name, float x, float centerY) {
            int color = bankColors[Math.floorMod(name.hashCode(), bankColors.length)];
            round(c, x - 18, centerY - 18, x + 18, centerY + 18, 12, color);
            text(c, bankInitials(name), x, centerY + 5, 11, Color.WHITE, Paint.Align.CENTER);
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
            int top = 0, bottom = 0;
            if (android.os.Build.VERSION.SDK_INT >= 23 && getRootWindowInsets() != null) {
                top = getRootWindowInsets().getSystemWindowInsetTop();
                bottom = getRootWindowInsets().getSystemWindowInsetBottom();
            }
            float x = e.getX() / d, y = (e.getY() - top) / d,
                h = (getHeight() - top - bottom) / d;
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                lastY = y; downY = y; dragging = false; return true;
            }
            if (e.getAction() == MotionEvent.ACTION_MOVE) {
                if (Math.abs(y - lastY) > 3) {
                    dragging = true;
                    scrollY = Math.max(0, Math.min(
                        Math.max(0, banks.size() * 96 - (h - 440)),
                        scrollY + lastY - y));
                    lastY = y;
                    invalidate();
                }
                return true;
            }
            if (e.getAction() != MotionEvent.ACTION_UP) return true;
            if (dragging) {
                if (downY < 360 && y - downY > 55) refresh();
                return true;
            }
            if (y > h - 55 && x > getWidth() / d / 2 + 55) {
                startActivity(new Intent(MainActivity.this, AboutActivity.class));
            } else if (y >= 120 && y <= 270) {
                if (x >= getWidth() / d - 105 && y <= 185) {
                    hidden = !hidden;
                    MainActivity.this.getSharedPreferences("balance_preferences", MODE_PRIVATE)
                        .edit().putBoolean("balances_hidden", hidden).apply();
                    invalidate();
                } else copyBalance(getString(R.string.total_label), total);
            } else if (y > 290 && y < 350 && x > getWidth() / d - 150) {
                refresh();
            } else if (y >= 352 && y < byForTouch(h)) {
                int index = (int) ((y - 352 + scrollY) / 96);
                float rowOffset = (y - 352 + scrollY) % 96;
                if (rowOffset < 82 && index >= 0 && index < banks.size()) {
                    int i = 0;
                    for (Bank bank : banks.values()) {
                        if (i++ == index) {
                            copyBalance(bank.name, bank.amount);
                            break;
                        }
                    }
                }
            }
            return true;
        }

        float byForTouch(float h) { return h - 74; }
    }

    static final class Bank {
        String name, sender;
        long amount, date;
        Bank(String n, long a, long d, String s) {
            name = n; amount = a; date = d; sender = s;
        }
    }
}
