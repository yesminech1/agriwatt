package tn.agriwatt.backend.service;

import java.util.regex.Pattern;

/** Numéros au format international (E.164), ex. +216XXXXXXXX. Un numéro tunisien à 8 chiffres reçoit le préfixe +216. */
public final class PhoneNumbers {

    private PhoneNumbers() {}

    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");
    private static final Pattern TN_LOCAL = Pattern.compile("^[2-9]\\d{7}$");

    /** Renvoie le numéro normalisé, null si vide ; IllegalArgumentException si invalide. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String n = raw.strip().replaceAll("[\\s.\\-()]", "");
        if (n.startsWith("00")) n = "+" + n.substring(2);
        if (TN_LOCAL.matcher(n).matches()) n = "+216" + n;
        if (!E164.matcher(n).matches() || (n.startsWith("+216") && n.length() != 12)) {
            throw new IllegalArgumentException("Numéro de téléphone invalide : format international attendu, par exemple +216XXXXXXXX.");
        }
        return n;
    }

    /** Pour l'affichage et les journaux : +216 •••• 34 56. */
    public static String mask(String phone) {
        if (phone == null || phone.length() < 6) return "—";
        return phone.substring(0, 4) + " •••• " + phone.substring(phone.length() - 4);
    }
}
