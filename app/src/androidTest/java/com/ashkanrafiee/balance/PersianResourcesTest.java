package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks the compiled translations, including screens that are not open during UI tests. */
@RunWith(AndroidJUnit4.class)
public class PersianResourcesTest {
    private static final Pattern ARGUMENT = Pattern.compile("%(\\d+)\\$([sd])");
    private static final Pattern PERSIAN_LETTER = Pattern.compile("[\\u0621-\\u063a\\u0641-\\u064a\\u067e\\u0686\\u0698\\u06a9\\u06af\\u06cc]");

    private Resources resources(String language) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag(language));
        return context.createConfigurationContext(config).getResources();
    }

    @Test public void everyStringIsLocalizedAndKeepsItsFormatArguments() throws Exception {
        Resources english = resources("en");
        Resources persian = resources("fa");
        for (Field field : R.string.class.getFields()) {
            int id = field.getInt(null);
            String name = field.getName();
            String source = english.getString(id);
            String translated = persian.getString(id);
            // Brand names and punctuation are intentionally shared between languages.
            String sourceWords = ARGUMENT.matcher(source).replaceAll("");
            if (!name.equals("app_name") && sourceWords.matches("(?s).*[A-Za-z].*")) {
                assertTrue(name + " needs Persian text, not an English fallback",
                    PERSIAN_LETTER.matcher(translated).find());
            }
            assertFormat(name, source, translated);
        }
    }

    @Test public void pluralsAreLocalizedAndFormatForZeroOneAndMany() throws Exception {
        Resources english = resources("en");
        Resources persian = resources("fa");
        for (Field field : R.plurals.class.getFields()) {
            int id = field.getInt(null);
            for (int quantity : new int[] {0, 1, 2, 10}) {
                String translated = persian.getQuantityString(id, quantity);
                assertTrue(field.getName(), PERSIAN_LETTER.matcher(translated).find());
                assertFormat(field.getName(), english.getQuantityString(id, quantity), translated);
            }
        }
    }

    private static List<String> arguments(String text) {
        List<String> arguments = new ArrayList<>();
        Matcher matcher = ARGUMENT.matcher(text);
        while (matcher.find()) arguments.add(matcher.group());
        Collections.sort(arguments);
        return arguments;
    }

    private static void assertFormat(String name, String source, String translated) {
        assertFalse(name + " must not be empty", translated.trim().isEmpty());
        assertEquals(name + " must retain placeholder positions and types",
            arguments(source), arguments(translated));
        Matcher matcher = ARGUMENT.matcher(source);
        List<Object> values = new ArrayList<>();
        while (matcher.find()) {
            int position = Integer.parseInt(matcher.group(1));
            while (values.size() < position) values.add(null);
            values.set(position - 1, matcher.group(2).equals("d") ? 12 : "نمونه");
        }
        String formatted = String.format(Locale.forLanguageTag("fa"), translated, values.toArray());
        assertFalse(name + " has an unresolved placeholder", ARGUMENT.matcher(formatted).find());
    }
}
