package com.ashkanrafiee.balance;

import java.util.Locale;

final class BankRules {
    private static final String[][] RULES = {
        {"Pasargad", "b.pasargad|098500019000|98500019000|+98500019000"},
        {"Eghtesad Novin", "ENBank|Enbank|+9890004800|90004800"},
        {"Shahr", "+98200035|20005|20003502|+98200085|700820428285|9200035|98200035|200035"},
        {"Ansar", "+98200036|100036|98100038"},
        {"Tejarat", "5000973189|985000973189|tejaratbank|TejaratBank"},
        {"Refah", "Refah|REFAH|REFAH BANK|Refah Bank|RefahBank"},
        {"Saman", "+9820000|Saman Bank|Saman|500095|SamanBank|9999920000|2000084080|99999984080|099999984080|9899999984080|+989999984080|+9899999984080|989999920000|+989999920000"},
        {"Sarmayeh", "+98300058|98300058|7007058|987007058|+987007058"},
        {"Sina", "Sina Bank|+9850003700798704|9850003700798704|50003700798704|09850004756|+9850004756|9850004756|50004756|50004751|+98300028|500048|500019|98500048|sina bank|SinaBank|sinabank"},
        {"Saderat", "BankSaderat|Bank Saderat|Saderat| صادرات"},
        {"Mellat", "Bank Mellat|BankMellat|Mellat"},
        {"Melli", "Bank Melli|BankMelli|Melli Iran"},
        {"Maskan", "Bank Maskan|BankMaskan|Maskan"},
        {"Keshavarzi", "Keshavarzi|Bank Keshavarzi"},
        {"Parsian", "ParsianBank|Parsian|Bank Parsian"},
        {"Post", "Post|PostBank|Post Bank"},
        {"Dey", "Dey|Bank Dey"},
        {"Hekmat", "Hekmat Iranian|Hekmat"},
        {"Tosee Taavon", "Tosee Taavon"},
        {"Noor", "Noor Credit Inst.|Noor|0200080947001|0200002734006"},
        {"Blu", "Blu|blu|+982187641|98300087641|300087641|989999987641|9999987641|+989999987641"},
        {"Kosar", "Kosar|Kosar Credit"},
        {"Mehr", "Mehr Iran|MehrIran"},
        {"Mehr Eghtesad", "Mehr Eghtesad|MehrEghtesad"},
        {"Ghavamin", "Ghavamin|Ghavamin Bank"},
        {"Zamin", "Iran Zamin|IranZamin"},
        {"Gardeshgari", "Gardeshgari|Tourism Bank"},
        {"Middle East", "Middle East Bank|Khavarmianeh"},
        {"Tosee", "Tosee|Tosee Bank"},
        {"Karafarin", "Karafarin|Karafarin Bank"},
        {"Resalat", "Resalat|Bank Resalat"},
        {"Venezuela", "Iran Venezuela|IranVenezuela"},
        {"Melal", "Melal|Melal Credit Inst."},
        {"Sanat Madan", "Sanat Madan|SanatMadan"},
        {"Sepah", "Sepah|Bank Sepah"},
        {"Tosee Saderat", "Tosee Saderat|ToseeSaderat"},
        {"Bankino", "Bankino|Bankino Bank"},
        {"Wepod", "Wepod|Wepod Bank"}
    };
    private static final String[][] OFFICIAL_EXTRA_RULES = {
        {"Saderat", "+987007851040|+9830009419|9830009419|30009419|983-000-9419|+98200060|+98200040|+9820004008|+98700719|700710|700718|98700719|700719|7007190"},
        {"Sepah", "100072419|SEPAHBANK|SEPAH BANK|SepahBank|Sepah Bank|986715001|+986715001|6715001|986715000|6715000|+986715000|+986715000015|986715000015|+989122200207|200015|6715000015|+986830068400107|98715000015|6715000016"},
        {"Industry & Mine", "+9820004003|+98100099|100099"},
        {"Resalat", "2000474701|+982000474701|982000474701|Resalat|resalat|RESALAT|ResalatBank|Resalat Bank|resalatbank|50001474701|9850001474701|+9850001474701|9850004747|+9850004747|989999904747|9999904747|50004747|500014747|+9820004747|20004746|20004747|+98500014747|9820004747"},
        {"Mehr", "B.QMEHRIRAN"},
        {"Ghavamin", "+981000222|+9820000222|+981105151|2000222|2000228"},
        {"Maskan", "+9810002503|+9850004920|+98500094|100025|98100025|9850004930"},
        {"Mellat", "+9815560001|+981000920000|981000920000|1000920000|9815560001|+9830007505|+9820003304|+9820003305|+9830003304|30003305|500092000"},
        {"Melli", "+987007170|98500043087|300084731|+989032229936|+98700717|+98200044|+9820004000|98700717|700717|9830009417|+9830009417|30009417|983000941001|200080|3000941001|98300094170|+983000941001|+98700759"},
        {"Mehr Eghtesad", "+98200089|+98100089|+982000089|+981000089"},
        {"Parsian", "99902318|99992318|+98200082|+98300054|+98500024|+9850002318|+9850001099|50001099|300071|9830007171|9810005403|9830007171|9899902318"},
        {"Post", "9840400108|+9840400108|40400108|50004940|+9820004940|9820004940|20004940|+98200029|+98100029|50004949|98700717|9850004940|98500009440|+9850004940"},
        {"Karafarin", "200057780|B.Karafarin|98200004321|+9830004321|30004321|+98200004321|50004858|50004857|98200002341|981000004|200004321"},
        {"Keshavarzi", "+98300081301|5000181301|+989999944444|9999944444|989999944444"},
        {"Zamin", "IZBANK"},
        {"Gardeshgari", "TourismBank|+982000300|982000309|982000300"},
        {"Kosar", "+9850002477|10002477|9810002477|6715014005|98715014005"},
        {"Tosee Taavon", "ttbank|TTBANK|+9820006438|+985000257|5000157|+985000157|500158|30005816|+989810007000|9810007000"},
        {"Middle East", "9820004861|+9820004861|20004861|20004840|+9820004860|9820004860|20004860"},
        {"Dey", "2000766|+9820004002|+9820043|+9830002726|Day Bank|Day|+98300097500027|3000766|500018|982000766|DayBank|98200766|+982000766"},
        {"Hekmat", "+9820008955"},
        {"Tosee Credit Inst.", "+9830005816"},
        {"EDBI", "7000730|+9830009430|9830009430|30009430"},
        {"Melal Credit Inst.", "+98200022222"},
        {"Noor Credit Inst.", "9830009480|30009480|+9820004009|7007780|20004293"},
        {"Wepod", "+981000214|98500011|5000114|+985000114|985000114|981000214|1000214|9830009017|30009017"},
        {"Bankino", "20004860"}
    };

    static String resolve(String sender) {
        if (sender == null || sender.indexOf('*') >= 0 || sender.indexOf('#') >= 0) return null;
        String a = normalize(sender);
        if (a.isEmpty()) return null;
        for (String[] rule : RULES) for (String alias : rule[1].split("\\|")) {
            String b = normalize(alias);
            if (a.equals(b)) return rule[0];
            if (a.matches("\\d+") && b.matches("\\d+") && a.length() >= 5 && b.length() >= 5 && (a.endsWith(b) || b.endsWith(a))) return rule[0];
        }
        for (String[] rule : OFFICIAL_EXTRA_RULES) for (String alias : rule[1].split("\\|")) {
            String b = normalize(alias);
            if (a.equals(b)) return rule[0];
            if (a.matches("\\d+") && b.matches("\\d+") && a.length() >= 5 && b.length() >= 5 && (a.endsWith(b) || b.endsWith(a))) return rule[0];
        }
        return null;
    }
    static String normalize(String raw) {
        StringBuilder out = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (c >= '۰' && c <= '۹') c = (char) ('0' + c - '۰');
            else if (c >= '٠' && c <= '٩') c = (char) ('0' + c - '٠');
            if (Character.isLetterOrDigit(c)) out.append(Character.toLowerCase(c));
        }
        String s = out.toString();
        if (s.startsWith("0098")) s = s.substring(4);
        if (s.startsWith("98") && s.length() > 8) s = s.substring(2);
        return s;
    }
}
