package com.ashkanrafiee.balance;

import android.app.Activity;
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
    final int bg=Color.rgb(8,13,22),card=Color.rgb(17,27,43),muted=Color.rgb(148,163,184),cyan=Color.rgb(103,232,249),purple=Color.rgb(167,139,250);
    int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    TextView text(String s,float size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);return v;}
    GradientDrawable rounded(int color,float radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    LinearLayout.LayoutParams margin(int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(14),dp(20),dp(14));root.setBackgroundColor(bg);root.setOnApplyWindowInsetsListener((v,i)->{int top=0,bottom=0;if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets x=i.getInsets(android.view.WindowInsets.Type.systemBars());top=x.top;bottom=x.bottom;}else{top=i.getSystemWindowInsetTop();bottom=i.getSystemWindowInsetBottom();}v.setPadding(dp(20),top+dp(14),dp(20),bottom+dp(14));return i;});setContentView(root);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);TextView back=text("‹",34,Color.WHITE);back.setGravity(Gravity.CENTER);back.setOnClickListener(v->finish());bar.addView(back,new LinearLayout.LayoutParams(dp(42),dp(48)));bar.addView(text("About",21,Color.WHITE),margin(10,0,0,0));root.addView(bar,margin(0,0,0,16));
        ScrollView scroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);scroll.addView(body,new ScrollView.LayoutParams(-1,-1));root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout hero=new LinearLayout(this);hero.setOrientation(LinearLayout.VERTICAL);hero.setGravity(Gravity.CENTER_HORIZONTAL);hero.setPadding(dp(20),dp(24),dp(20),dp(24));hero.setBackground(rounded(Color.rgb(31,42,74),22));TextView mark=text("B",25,bg);mark.setGravity(Gravity.CENTER);mark.setTypeface(null,Typeface.BOLD);mark.setBackground(rounded(cyan,16));hero.addView(mark,new LinearLayout.LayoutParams(dp(56),dp(56)));TextView title=text("Balance",24,Color.WHITE);title.setPadding(0,dp(14),0,dp(2));hero.addView(title);hero.addView(text("Offline bank balance",13,Color.rgb(201,211,230)));body.addView(hero,margin(0,0,0,22));
        body.addView(section("ABOUT THIS APP","Balance is a private, offline dashboard for the latest balances reported by supported banks. It reads supported bank SMS messages locally, keeps one current balance per bank, and never sends SMS or financial data anywhere."),margin(0,0,0,10));
         body.addView(info("Created by","Ashkan Rafiee","https://AshkanRafiee.com/"),margin(0,0,0,8));body.addView(info("License","GNU General Public License v3.0","https://github.com/AshkanRafiee/balance/blob/main/LICENSE"),margin(0,0,0,8));body.addView(info("Source code","github.com/ashkanrafiee/balance","https://github.com/AshkanRafiee/balance"),margin(0,0,0,8));body.addView(info("Privacy","Offline by design · no cloud · no account"),margin(0,0,0,20));TextView footer=text("Balance · Version "+appVersion(),11,Color.rgb(103,115,136));footer.setGravity(Gravity.CENTER);body.addView(footer);
    }
    TextView section(String h,String b){TextView v=text(h+"\n"+b,12,muted);v.setLineSpacing(2,1.05f);return v;}
    String appVersion(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception e){return "unknown";}}
    LinearLayout info(String h,String value){return info(h,value,null);}
    LinearLayout info(String h,String value,String url){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(16),dp(13),dp(16),dp(13));box.setBackground(rounded(card,15));box.addView(text(h,12,muted));TextView v=text(value,14,url!=null?Color.rgb(190,184,255):Color.WHITE);v.setPadding(0,dp(5),0,0);v.setMaxLines(2);v.setEllipsize(TextUtils.TruncateAt.END);if(url!=null){v.setOnClickListener(view->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))));}box.addView(v);return box;}
}
