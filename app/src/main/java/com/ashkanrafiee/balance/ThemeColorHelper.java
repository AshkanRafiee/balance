package com.ashkanrafiee.balance;

import android.app.Activity;
import android.content.Context;

/**
 * Stores and resolves Balance's selectable accent palette.
 *
 * <p>The app uses semantic color resources throughout its hand-built UI. Resource values cannot be
 * changed at runtime, so this helper is the single translation layer for the roles that belong to
 * the user's theme color. Semantic colors such as warning, deposit and withdrawal red stay
 * independent of the choice.
 */
final class ThemeColorHelper {
    static final String COLOR_OCEAN = "ocean";
    static final String COLOR_VIOLET = "violet";
    static final String COLOR_ROSE = "rose";
    static final String COLOR_FOREST = "forest";
    static final String COLOR_SUNSET = "sunset";
    static final String COLOR_INDIGO = "indigo";
    static final String COLOR_CORAL = "coral";
    static final String COLOR_BERRY = "berry";

    /** Order shown in the Display menu. Ocean is the original Balance palette. */
    static final String[] CHOICES = {
        COLOR_OCEAN, COLOR_VIOLET, COLOR_ROSE, COLOR_FOREST,
        COLOR_SUNSET, COLOR_INDIGO, COLOR_CORAL, COLOR_BERRY
    };

    private static final String PREFS = "balance_theme";
    private static final String KEY_COLOR = "theme_color";

    private ThemeColorHelper() { }

    static String themeColor(Context context) {
        String stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COLOR, COLOR_OCEAN);
        return isChoice(stored) ? stored : COLOR_OCEAN;
    }

    static void setThemeColor(Context context, String value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_COLOR, isChoice(value) ? value : COLOR_OCEAN).apply();
    }

    /** Applies the palette's colorAccent so native controls and dialogs match the custom UI. */
    static void apply(Context context) {
        if (!(context instanceof Activity)) return;
        int style = style(themeColor(context));
        if (style != 0) context.getTheme().applyStyle(style, true);
    }

    /** Resolves a resource, replacing only the semantic roles owned by the selected palette. */
    static int color(Context context, int resource) {
        int replacement = replacement(themeColor(context), resource);
        return context.getResources().getColor(replacement == 0 ? resource : replacement,
            context.getTheme());
    }

    /** The primary swatch color shown in the Display picker. */
    static int swatch(Context context, String choice) {
        return context.getResources().getColor(primary(choice), context.getTheme());
    }

    private static boolean isChoice(String value) {
        if (value == null) return false;
        for (String choice : CHOICES) if (choice.equals(value)) return true;
        return false;
    }

    private static int style(String choice) {
        if (COLOR_VIOLET.equals(choice)) return R.style.BalancePaletteViolet;
        if (COLOR_ROSE.equals(choice)) return R.style.BalancePaletteRose;
        if (COLOR_FOREST.equals(choice)) return R.style.BalancePaletteForest;
        if (COLOR_SUNSET.equals(choice)) return R.style.BalancePaletteSunset;
        if (COLOR_INDIGO.equals(choice)) return R.style.BalancePaletteIndigo;
        if (COLOR_CORAL.equals(choice)) return R.style.BalancePaletteCoral;
        if (COLOR_BERRY.equals(choice)) return R.style.BalancePaletteBerry;
        return R.style.BalancePaletteOcean;
    }

    private static int replacement(String choice, int resource) {
        if (resource == R.color.accent
                || resource == R.color.expressive_primary
                || resource == R.color.expressive_primary_container
                || resource == R.color.expressive_on_primary
                || resource == R.color.expressive_on_primary_container
                || resource == R.color.hero
                || resource == R.color.link
                || resource == R.color.history_hero_top
                || resource == R.color.history_rail
                || resource == R.color.history_badge_fg
                || resource == R.color.history_badge_bg) {
            if (resource == R.color.expressive_on_primary) return onPrimary(choice);
            if (resource == R.color.expressive_on_primary_container) return onContainer(choice);
            if (resource == R.color.expressive_primary_container
                    || resource == R.color.hero
                    || resource == R.color.history_hero_top
                    || resource == R.color.history_badge_bg) return container(choice);
            return primary(choice);
        }
        return 0;
    }

    private static int primary(String choice) {
        if (COLOR_VIOLET.equals(choice)) return R.color.palette_violet_primary;
        if (COLOR_ROSE.equals(choice)) return R.color.palette_rose_primary;
        if (COLOR_FOREST.equals(choice)) return R.color.palette_forest_primary;
        if (COLOR_SUNSET.equals(choice)) return R.color.palette_sunset_primary;
        if (COLOR_INDIGO.equals(choice)) return R.color.palette_indigo_primary;
        if (COLOR_CORAL.equals(choice)) return R.color.palette_coral_primary;
        if (COLOR_BERRY.equals(choice)) return R.color.palette_berry_primary;
        return R.color.palette_ocean_primary;
    }

    private static int onPrimary(String choice) {
        if (COLOR_VIOLET.equals(choice)) return R.color.palette_violet_on_primary;
        if (COLOR_ROSE.equals(choice)) return R.color.palette_rose_on_primary;
        if (COLOR_FOREST.equals(choice)) return R.color.palette_forest_on_primary;
        if (COLOR_SUNSET.equals(choice)) return R.color.palette_sunset_on_primary;
        if (COLOR_INDIGO.equals(choice)) return R.color.palette_indigo_on_primary;
        if (COLOR_CORAL.equals(choice)) return R.color.palette_coral_on_primary;
        if (COLOR_BERRY.equals(choice)) return R.color.palette_berry_on_primary;
        return R.color.palette_ocean_on_primary;
    }

    private static int container(String choice) {
        if (COLOR_VIOLET.equals(choice)) return R.color.palette_violet_container;
        if (COLOR_ROSE.equals(choice)) return R.color.palette_rose_container;
        if (COLOR_FOREST.equals(choice)) return R.color.palette_forest_container;
        if (COLOR_SUNSET.equals(choice)) return R.color.palette_sunset_container;
        if (COLOR_INDIGO.equals(choice)) return R.color.palette_indigo_container;
        if (COLOR_CORAL.equals(choice)) return R.color.palette_coral_container;
        if (COLOR_BERRY.equals(choice)) return R.color.palette_berry_container;
        return R.color.palette_ocean_container;
    }

    private static int onContainer(String choice) {
        if (COLOR_VIOLET.equals(choice)) return R.color.palette_violet_on_container;
        if (COLOR_ROSE.equals(choice)) return R.color.palette_rose_on_container;
        if (COLOR_FOREST.equals(choice)) return R.color.palette_forest_on_container;
        if (COLOR_SUNSET.equals(choice)) return R.color.palette_sunset_on_container;
        if (COLOR_INDIGO.equals(choice)) return R.color.palette_indigo_on_container;
        if (COLOR_CORAL.equals(choice)) return R.color.palette_coral_on_container;
        if (COLOR_BERRY.equals(choice)) return R.color.palette_berry_on_container;
        return R.color.palette_ocean_on_container;
    }
}
