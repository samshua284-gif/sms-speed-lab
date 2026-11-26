package com.twog.smslab;

/**
 * Conservative phone-number normalization — pure logic, JVM-testable.
 *
 * Field lesson (2026-11-26): attempts to 96897436900 were rejected by
 * the SMSC with NETWORK_ERROR, while +96897436900 was accepted and
 * delivered. An ambiguous number form must never leave the lab again.
 *
 * Rules, in order:
 *  1. Strip spaces, dashes and parentheses.
 *  2. A leading "00" becomes "+".
 *  3. A leading "+" is kept as-is.
 *  4. 11 digits starting with 968 (Oman country code) gain a "+".
 *  5. Exactly 8 digits are an Omani national number: gain "+968".
 *  6. Anything else is returned cleaned but unchanged — the lab does
 *     not invent a country for a number it cannot recognize.
 */
public final class PhoneNorm {

    private PhoneNorm() {}

    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.trim()
                .replace(" ", "")
                .replace("-", "")
                .replace("(", "")
                .replace(")", "");
        if (s.startsWith("00")) return "+" + s.substring(2);
        if (s.startsWith("+")) return s;
        if (s.length() == 11 && s.startsWith("968") && allDigits(s)) return "+" + s;
        if (s.length() == 8 && allDigits(s)) return "+968" + s;
        return s;
    }

    private static boolean allDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return s.length() > 0;
    }
}
