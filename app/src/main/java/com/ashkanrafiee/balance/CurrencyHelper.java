package com.ashkanrafiee.balance;

import android.content.Context;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Persists and reads the currency whose value and unit label the app shows for amounts. Amounts
 * are stored in rial; the currency decides both the number and the unit drawn beside it:
 * Toman (the default) converts rial to toman by dividing by ten, exactly as before, while every
 * other currency — Rial as stored or a custom name the user types — shows the raw rial figure with
 * the chosen unit text. No exchange rate is applied, so everything works fully offline.
 *
 * <p>Kept in its own preference file (like the language and the region) so a single key controls
 * the whole app, including the home-screen widget.
 */
public final class CurrencyHelper {
    public static final String CURRENCY_TOMAN = "toman";
    public static final String CURRENCY_RIAL = "rial";

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

    /** The unit label shown next to amounts: the localized toman or rial word, or the verbatim text
     *  the user typed for their custom currency. */
    public static String label(Context context) {
        String v = currency(context);
        if (v.startsWith(CUSTOM_PREFIX)) return v.substring(CUSTOM_PREFIX.length());
        if (CURRENCY_RIAL.equals(v)) return context.getString(R.string.unit_rial);
        return context.getString(R.string.unit_toman);
    }

    /** Formats a stored rial amount for the chosen currency, following the app language's digit
     *  rules: Toman divides by ten, every other currency shows the raw figure. */
    public static String amount(Context context, long n) {
        if (CURRENCY_TOMAN.equals(currency(context))) return BalanceData.toman(context, n);
        return display(context, n);
    }

    /** A number written the way this app writes numbers, in the chosen language. */
    static String display(Context context, long n) {
        Locale locale = LocaleHelper.isPersian(context) ? FA : Locale.US;
        Numbers c = NUMBERS.get();
        if (!locale.equals(c.locale)) {
            c.format = NumberFormat.getNumberInstance(locale);
            c.locale = locale;
        }
        return c.format.format(n);
    }

    /** One formatter, belonging to the thread that asked for it and rebuilt when the language
     *  changes. {@link NumberFormat#getNumberInstance} builds a new DecimalFormat on every call, and
     *  the history screen asks for one per movement row, so a long account spent most of its time
     *  building formatters. DecimalFormat is not thread safe, so a single shared one would be a data
     *  race as soon as any of this ran off the main thread; a per-thread one avoids both. */
    private static final class Numbers {
        Locale locale = Locale.US;
        NumberFormat format = NumberFormat.getNumberInstance(Locale.US);
    }

    private static final ThreadLocal<Numbers> NUMBERS = new ThreadLocal<Numbers>() {
        @Override protected Numbers initialValue() { return new Numbers(); }
    };

    private static final Locale FA = new Locale("fa");

    public static void setCurrency(Context context, String value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CURRENCY, value).apply();
    }

    /** The position of a stored currency value in the fixed choice list — Toman, Rial, or the
     *  Custom slot (2) for a user-typed entry. Kept here so the picker and the storage always
     *  agree on which entry a value maps to. */
    public static int fixedIndex(String value) {
        if (CURRENCY_RIAL.equals(value)) return 1;
        if (value != null && value.startsWith(CUSTOM_PREFIX)) return 2;
        return 0;
    }

    /** The stored currency value for a fixed-list position; position 2 (Custom) has no fixed value
     *  and must be built from the user's typed text with {@link #CUSTOM_PREFIX}. */
    public static String fixedCurrency(int position) {
        if (position == 1) return CURRENCY_RIAL;
        return CURRENCY_TOMAN;
    }
}