package com.ashkanrafiee.balance;

import android.content.Context;
import android.graphics.Typeface;

/** The UI typefaces: the bundled Vazirmatn family in Persian, the system fonts otherwise.
 *
 *  <p>Persian digits and letterforms in the system sans render small and thin next to the Latin
 *  UI, so Persian screens read through Vazirmatn (regular body, medium titles, bold amounts)
 *  while English keeps exactly the typefaces it always had: the helpers below return null there,
 *  so each call site falls back to the same code path as before this class existed. The theme
 *  carries the family for every view that never sets a typeface of its own (see
 *  {@code values-fa/styles.xml}); the call sites below only cover the ones that do. The widget
 *  keeps the system font: remote views cannot carry a bundled one. */
final class Fonts {
    private Fonts() {}

    private static Typeface family;
    private static Typeface medium;

    /** The regular UI family: Vazirmatn in Persian, null (whatever the view had) otherwise. */
    static Typeface text(Context context) {
        if (!LocaleHelper.isPersian(context)) return null;
        if (family == null) family = context.getResources().getFont(R.font.vazirmatn);
        return family;
    }

    /** The medium UI family: Vazirmatn Medium in Persian, the system medium otherwise. */
    static Typeface medium(Context context) {
        if (!LocaleHelper.isPersian(context))
            return Typeface.create("sans-serif-medium", Typeface.NORMAL);
        if (medium == null) medium = context.getResources().getFont(R.font.vazirmatn_medium);
        return medium;
    }

    /** The canvas paint family: Vazirmatn in Persian, the same "sans" paint as before otherwise. */
    static Typeface paint(Context context) {
        if (!LocaleHelper.isPersian(context))
            return Typeface.create("sans", Typeface.NORMAL);
        return text(context);
    }

    /** The amount-figure family: Vazirmatn Bold in Persian, the same regular "sans" as before
     *  otherwise, so figures gain weight only where the digits needed it. */
    static Typeface amount(Context context) {
        if (!LocaleHelper.isPersian(context))
            return Typeface.create("sans", Typeface.NORMAL);
        return Typeface.create(text(context), Typeface.BOLD);
    }
}
