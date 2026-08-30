package com.ashkanrafiee.balance;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class AboutActivity extends Activity {
    final int bg = Color.rgb(8, 13, 22);
    final int card = Color.rgb(17, 27, 43);
    final int muted = Color.rgb(148, 163, 184);
    final int cyan = Color.rgb(103, 232, 249);
    final int purple = Color.rgb(167, 139, 250);

    int dp(float n) {
        return (int) (n * getResources().getDisplayMetrics().density + .5f);
    }

    TextView text(String s, float size, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    LinearLayout.LayoutParams margin(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        boolean rtl = getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(14), dp(20), dp(14));
        root.setBackgroundColor(bg);
        root.setOnApplyWindowInsetsListener((v, i) -> {
            int top = 0, bottom = 0;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets x = i.getInsets(android.view.WindowInsets.Type.systemBars());
                top = x.top;
                bottom = x.bottom;
            } else {
                top = i.getSystemWindowInsetTop();
                bottom = i.getSystemWindowInsetBottom();
            }
            v.setPadding(dp(20), top + dp(14), dp(20), bottom + dp(14));
            return i;
        });
        setContentView(root);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text(rtl ? "›" : "‹", 34, Color.WHITE);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(48)));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2);
        titleParams.setMarginStart(dp(10));
        bar.addView(text(getString(R.string.about_title), 21, Color.WHITE), titleParams);
        root.addView(bar, margin(0, 0, 0, 16));

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -1));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(dp(20), dp(24), dp(20), dp(24));
        hero.setBackground(rounded(Color.rgb(31, 42, 74), 22));
        TextView mark = text("B", 25, bg);
        mark.setGravity(Gravity.CENTER);
        mark.setTypeface(null, Typeface.BOLD);
        mark.setBackground(rounded(cyan, 16));
        hero.addView(mark, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView title = text(getString(R.string.app_name), 24, Color.WHITE);
        title.setPadding(0, dp(14), 0, dp(2));
        hero.addView(title);
        hero.addView(text(getString(R.string.tagline_offline_bank_balance), 13, Color.rgb(201, 211, 230)));
        body.addView(hero, margin(0, 0, 0, 22));

        body.addView(section(getString(R.string.about_section_heading), getString(R.string.about_section_body)), margin(0, 0, 0, 10));
        body.addView(info(getString(R.string.about_created_by_label), "Ashkan Rafiee", "https://AshkanRafiee.com/"), margin(0, 0, 0, 8));
        body.addView(info(getString(R.string.about_license_label), "GNU General Public License v3.0",
            "https://github.com/AshkanRafiee/balance/blob/main/LICENSE"), margin(0, 0, 0, 8));
        body.addView(info(getString(R.string.about_source_label), "github.com/ashkanrafiee/balance",
            "https://github.com/AshkanRafiee/balance"), margin(0, 0, 0, 8));
        body.addView(info(getString(R.string.about_privacy_label), getString(R.string.about_privacy_value)), margin(0, 0, 0, 20));

        TextView footer = text(getString(R.string.about_footer, appVersion()), 11, Color.rgb(103, 115, 136));
        footer.setGravity(Gravity.CENTER);
        body.addView(footer);
    }

    TextView section(String h, String b) {
        TextView v = text(h + "\n" + b, 12, muted);
        v.setLineSpacing(2, 1.05f);
        return v;
    }

    String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    LinearLayout info(String h, String value) {
        return info(h, value, null);
    }

    LinearLayout info(String h, String value, String url) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(13), dp(16), dp(13));
        box.setBackground(rounded(card, 15));
        box.addView(text(h, 12, muted));
        TextView v = text(value, 14, url != null ? Color.rgb(190, 184, 255) : Color.WHITE);
        v.setPadding(0, dp(5), 0, 0);
        v.setMaxLines(2);
        v.setEllipsize(TextUtils.TruncateAt.END);
        if (url != null) {
            v.setOnClickListener(view -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))));
        }
        box.addView(v);
        return box;
    }
}
