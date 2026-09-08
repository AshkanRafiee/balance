package com.ashkanrafiee.balance;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps the canonical (English, storage-key) bank name to its brand icon drawable
 * where one exists. Banks without an icon return 0 so callers can fall back to the
 * colored initials badge, keeping a consistent look even when an icon is missing.
 */
final class BankIcon {
    private static final Map<String, Integer> ICON_RES = new HashMap<>();
    static {
        ICON_RES.put("Ansar", R.drawable.bank_ansar);
        ICON_RES.put("Bankino", R.drawable.bank_bankino);
        ICON_RES.put("Blu", R.drawable.bank_blu);
        ICON_RES.put("Dey", R.drawable.bank_dey);
        ICON_RES.put("Eghtesad Novin", R.drawable.bank_eghtesad_novin);
        ICON_RES.put("Gardeshgari", R.drawable.bank_gardeshgari);
        ICON_RES.put("Ghavamin", R.drawable.bank_ghavamin);
        ICON_RES.put("Hekmat", R.drawable.bank_hekmat);
        ICON_RES.put("Industry & Mine", R.drawable.bank_industry_mine);
        ICON_RES.put("Zamin", R.drawable.bank_zamin);
        ICON_RES.put("Karafarin", R.drawable.bank_karafarin);
        ICON_RES.put("Keshavarzi", R.drawable.bank_keshavarzi);
        ICON_RES.put("Kosar", R.drawable.bank_kosar);
        ICON_RES.put("Maskan", R.drawable.bank_maskan);
        ICON_RES.put("Mehr Eghtesad", R.drawable.bank_mehr_eghtesad);
        ICON_RES.put("Mehr", R.drawable.bank_mehr);
        ICON_RES.put("Melal", R.drawable.bank_melal);
        ICON_RES.put("Mellat", R.drawable.bank_mellat);
        ICON_RES.put("Melli", R.drawable.bank_melli);
        ICON_RES.put("Middle East", R.drawable.bank_middle_east);
        ICON_RES.put("Noor", R.drawable.bank_noor);
        ICON_RES.put("Parsian", R.drawable.bank_parsian);
        ICON_RES.put("Pasargad", R.drawable.bank_pasargad);
        ICON_RES.put("Post", R.drawable.bank_post);
        ICON_RES.put("Refah", R.drawable.bank_refah);
        ICON_RES.put("Resalat", R.drawable.bank_resalat);
        ICON_RES.put("Saderat", R.drawable.bank_saderat);
        ICON_RES.put("Saman", R.drawable.bank_saman);
        ICON_RES.put("Sanat Madan", R.drawable.bank_sanat_madan);
        ICON_RES.put("Sarmayeh", R.drawable.bank_sarmayeh);
        ICON_RES.put("Sepah", R.drawable.bank_sepah);
        ICON_RES.put("Shahr", R.drawable.bank_shahr);
        ICON_RES.put("Sina", R.drawable.bank_sina);
        ICON_RES.put("Tejarat", R.drawable.bank_tejarat);
        ICON_RES.put("Tosee", R.drawable.bank_tosee);
        ICON_RES.put("Tosee Saderat", R.drawable.bank_tosee_saderat);
        ICON_RES.put("Tosee Taavon", R.drawable.bank_tosee_taavon);
        ICON_RES.put("Venezuela", R.drawable.bank_venezuela);
    }

    private BankIcon() { }

    /** Drawable resource id for {@code canonicalName}'s brand icon, or 0 when none is available. */
    static int iconFor(String canonicalName) {
        Integer resId = ICON_RES.get(canonicalName);
        return resId != null ? resId : 0;
    }
}
