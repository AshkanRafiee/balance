package com.ashkanrafiee.balance;

import android.content.Context;
import com.ashkanrafiee.balance.parser.legacy.LegacyBankRules;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BankRules {
    /** Maps a canonical (English, storage-key) bank name to its localized display string resource. */
    private static final Map<String, Integer> DISPLAY_NAME_RES = new HashMap<>();
    static {
        DISPLAY_NAME_RES.put("Pasargad", R.string.bank_pasargad);
        DISPLAY_NAME_RES.put("Eghtesad Novin", R.string.bank_eghtesad_novin);
        DISPLAY_NAME_RES.put("Shahr", R.string.bank_shahr);
        DISPLAY_NAME_RES.put("Ansar", R.string.bank_ansar);
        DISPLAY_NAME_RES.put("Tejarat", R.string.bank_tejarat);
        DISPLAY_NAME_RES.put("Refah", R.string.bank_refah);
        DISPLAY_NAME_RES.put("Saman", R.string.bank_saman);
        DISPLAY_NAME_RES.put("Sarmayeh", R.string.bank_sarmayeh);
        DISPLAY_NAME_RES.put("Sina", R.string.bank_sina);
        DISPLAY_NAME_RES.put("Saderat", R.string.bank_saderat);
        DISPLAY_NAME_RES.put("Mellat", R.string.bank_mellat);
        DISPLAY_NAME_RES.put("Melli", R.string.bank_melli);
        DISPLAY_NAME_RES.put("Maskan", R.string.bank_maskan);
        DISPLAY_NAME_RES.put("Keshavarzi", R.string.bank_keshavarzi);
        DISPLAY_NAME_RES.put("Parsian", R.string.bank_parsian);
        DISPLAY_NAME_RES.put("Post", R.string.bank_post);
        DISPLAY_NAME_RES.put("Dey", R.string.bank_dey);
        DISPLAY_NAME_RES.put("Hekmat", R.string.bank_hekmat);
        DISPLAY_NAME_RES.put("Tosee Taavon", R.string.bank_tosee_taavon);
        DISPLAY_NAME_RES.put("Noor", R.string.bank_noor);
        DISPLAY_NAME_RES.put("Blu", R.string.bank_blu);
        DISPLAY_NAME_RES.put("Kosar", R.string.bank_kosar);
        DISPLAY_NAME_RES.put("Mehr", R.string.bank_mehr);
        DISPLAY_NAME_RES.put("Mehr Eghtesad", R.string.bank_mehr_eghtesad);
        DISPLAY_NAME_RES.put("Ghavamin", R.string.bank_ghavamin);
        DISPLAY_NAME_RES.put("Zamin", R.string.bank_zamin);
        DISPLAY_NAME_RES.put("Gardeshgari", R.string.bank_gardeshgari);
        DISPLAY_NAME_RES.put("Middle East", R.string.bank_middle_east);
        DISPLAY_NAME_RES.put("Tosee", R.string.bank_tosee);
        DISPLAY_NAME_RES.put("Karafarin", R.string.bank_karafarin);
        DISPLAY_NAME_RES.put("Resalat", R.string.bank_resalat);
        DISPLAY_NAME_RES.put("Venezuela", R.string.bank_venezuela);
        DISPLAY_NAME_RES.put("Melal", R.string.bank_melal);
        DISPLAY_NAME_RES.put("Sanat Madan", R.string.bank_sanat_madan);
        DISPLAY_NAME_RES.put("Sepah", R.string.bank_sepah);
        DISPLAY_NAME_RES.put("Tosee Saderat", R.string.bank_tosee_saderat);
        DISPLAY_NAME_RES.put("Bankino", R.string.bank_bankino);
        DISPLAY_NAME_RES.put("Wepod", R.string.bank_wepod);
        DISPLAY_NAME_RES.put("Industry & Mine", R.string.bank_industry_mine);
        DISPLAY_NAME_RES.put("Tosee Credit Inst.", R.string.bank_tosee_credit_inst);
        DISPLAY_NAME_RES.put("EDBI", R.string.bank_edbi);
        DISPLAY_NAME_RES.put("Melal Credit Inst.", R.string.bank_melal_credit_inst);
        DISPLAY_NAME_RES.put("Noor Credit Inst.", R.string.bank_noor_credit_inst);
    }

    /** Localized name for display; the canonical name remains the storage/lookup key. */
    static String displayName(Context context, String canonical) {
        Integer resId = DISPLAY_NAME_RES.get(canonical);
        return resId != null ? context.getString(resId) : canonical;
    }

    /** The shared legacy rule fingerprint, including account, reason and channel rules. */
    static final int VERSION = LegacyBankRules.VERSION;

    static CalendarSystem calendar(String bank) {
        return CalendarSystem.valueOf(LegacyBankRules.calendar(bank).name());
    }

    static Set<String> supportedNames() {
        return LegacyBankRules.supportedNames();
    }

    static List<String> aliasList() {
        return LegacyBankRules.aliasList();
    }

    static String[][] rulesTestOnly() {
        return LegacyBankRules.rulesTestOnly();
    }

    static String[][] channelRulesTestOnly() {
        return LegacyBankRules.channelRulesTestOnly();
    }

    static Set<String> channelCaptionKeys() {
        return new HashSet<>(CHANNEL_CAPTION_RES.keySet());
    }

    static String[][] accountRulesTestOnly() {
        return LegacyBankRules.accountRulesTestOnly();
    }

    static String[][] reasonRulesTestOnly() {
        return LegacyBankRules.reasonRulesTestOnly();
    }

    static Set<String> reasonCaptionKeys() {
        return new HashSet<>(REASON_CAPTION_RES.keySet());
    }

    static Set<String> reachableBanks() {
        return LegacyBankRules.reachableBanks();
    }

    static String resolve(String sender) {
        return LegacyBankRules.resolve(sender);
    }

    static String normalize(String raw) {
        return LegacyBankRules.normalize(raw);
    }

    static String extractAccount(String bank, String body) {
        return LegacyBankRules.extractAccount(bank, body);
    }

    /** Localized captions for the normalized event titles understood by the shared rules. */
    private static final Map<String, Integer> REASON_CAPTION_RES = new HashMap<>();
    static {
        REASON_CAPTION_RES.put("شارژ شدی", R.string.reason_topup);
        REASON_CAPTION_RES.put("پرداخت قبض", R.string.reason_bill_payment);
        REASON_CAPTION_RES.put("برگشت پول", R.string.reason_refund);
        REASON_CAPTION_RES.put("دریافت پل", R.string.reason_transfer_in);
        REASON_CAPTION_RES.put("انتقال پل", R.string.reason_transfer_out);
    }

    static String extractReason(String bank, String body) {
        return LegacyBankRules.extractReason(bank, body);
    }

    /** The caption in the app's current language, or null for an unknown stored reason. */
    static String reasonCaption(Context context, String reason) {
        if (reason == null) return null;
        Integer resId = REASON_CAPTION_RES.get(normalizeReason(reason));
        return resId == null ? null : context.getString(resId);
    }

    static String normalizeReason(String raw) {
        return LegacyBankRules.normalizeReason(raw);
    }

    /** Localized captions for the normalized channels understood by the shared rules. */
    private static final Map<String, Integer> CHANNEL_CAPTION_RES = new HashMap<>();
    static {
        CHANNEL_CAPTION_RES.put("شتاب", R.string.channel_shetab);
        CHANNEL_CAPTION_RES.put("سامانه پل (پرداخت لحظه ای)", R.string.channel_sep);
        CHANNEL_CAPTION_RES.put("پایانه فروش", R.string.channel_pos);
        CHANNEL_CAPTION_RES.put("همراه بانک", R.string.channel_mobile);
        CHANNEL_CAPTION_RES.put("شعبه", R.string.channel_branch);
    }

    static String extractChannel(String bank, String body) {
        return LegacyBankRules.extractChannel(bank, body);
    }

    /** The caption in the app's current language, or null for an unknown stored channel. */
    static String channelCaption(Context context, String channel) {
        if (channel == null) return null;
        Integer resId = CHANNEL_CAPTION_RES.get(normalizeChannel(channel));
        return resId == null ? null : context.getString(resId);
    }

    static String normalizeChannel(String raw) {
        return LegacyBankRules.normalizeChannel(raw);
    }
}
