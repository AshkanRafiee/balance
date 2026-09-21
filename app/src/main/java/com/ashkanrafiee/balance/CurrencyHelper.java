package com.ashkanrafiee.balance;

import android.content.Context;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Persists and reads the currency whose value and unit label the app shows for amounts. Amounts
 * are stored in rial; the currency decides both the number and the unit drawn beside it:
 * Toman (the default) converts rial to toman by dividing by ten, exactly as before, while every
 * other currency — USD, EUR, or a custom name the user types — shows the raw rial figure with the
 * chosen unit text. No exchange rate is applied, so everything works fully offline.
 *
 * <p>Kept in its own preference file (like the language and the region) so a single key controls
 * the whole app, including the home-screen widget.
 */
public final class CurrencyHelper {
    public static final String CURRENCY_TOMAN = "toman";
    public static final String CURRENCY_USD = "usd";
    public static final String CURRENCY_EUR = "eur";

    /** Marker prepended to user-typed currency names so they can never collide with the fixed ones. */
    public static final String CUSTOM_PREFIX = "custom:";

    private static final String PREFS = "balance_currency";
    private static final String KEY_CURRENCY = "currency";

    private CurrencyHelper() {}

    /** The stored currency value (one of the {@code CURRENCY_*} constants or a custom entry). */
    public static String currency(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CURRENCY, CURRENCY_TOMAN);
    }

    /** Whether the current currency is a user-typed custom one. */
    public static boolean isCustom(Context context) {
        return currency(context).startsWith(CUSTOM_PREFIX);
    }

    /** The unit label shown next to amounts: the localized toman word, USD, EUR, or the verbatim
     *  text the user typed for their custom currency. */
    public static String label(Context context) {
        String v = currency(context);
        if (v.startsWith(CUSTOM_PREFIX)) return v.substring(CUSTOM_PREFIX.length());
        if (CURRENCY_USD.equals(v)) return "USD";
        if (CURRENCY_EUR.equals(v)) return "EUR";
        return context.getString(R.string.unit_toman);
    }

    /** Formats a stored rial amount for the chosen currency, following the app language's digit
     *  rules: Toman divides by ten, every other currency shows the raw figure. */
    public static String amount(Context context, long n) {
        if (CURRENCY_TOMAN.equals(currency(context))) return BalanceData.toman(context, n);
        Locale locale = LocaleHelper.isPersian(context) ? new Locale("fa") : Locale.US;
        return NumberFormat.getNumberInstance(locale).format(n);
    }

    public static void setCurrency(Context context, String value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CURRENCY, value).apply();
    }
}