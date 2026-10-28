package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Shared parser normalization: mixed scripts must become one stable matching form. */
@RunWith(AndroidJUnit4.class)
public class TextNormalizerTest {

    @Test public void normalize_mixedArabicPersianFormsAndDigits() {
        assertEquals("بانک ی ی ک ا ه 1234,567.8:\n",
            TextNormalizer.normalize("بانك ي ى ك أ ة ١۲۳۴٬۵۶۷٫۸：\n"));
    }

    @Test public void normalize_removesMarksTatweelAndBidiControls() {
        assertEquals("مانده", TextNormalizer.normalize("مَـا\u200Fنْدَه"));
    }

    @Test public void normalize_isIdempotentAndNullSafe() {
        String input = "بانك\u200C ي\u066C١";
        String once = TextNormalizer.normalize(input);
        assertEquals(once, TextNormalizer.normalize(once));
        assertNull(TextNormalizer.normalize(null));
    }
}
