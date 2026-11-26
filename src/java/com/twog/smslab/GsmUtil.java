package com.twog.smslab;

/**
 * GSM 03.38 encoding helpers — pure logic, no Android dependencies,
 * so the exact shipped code is unit-testable on a plain JVM.
 *
 * A message is GSM-7 only if every character is in the basic set
 * (1 unit each) or the extension set (2 units each). Anything else
 * (Arabic included) forces UCS-2 for the whole message.
 * Segments: GSM-7 holds 160 units single / 153 per concatenated part;
 * UCS-2 holds 70 chars single / 67 per concatenated part.
 */
public final class GsmUtil {

    private static final String BASIC =
            "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ "
            + "!\"#¤%&'()*+,-./0123456789:;<=>?¡"
            + "ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿"
            + "abcdefghijklmnopqrstuvwxyzäöñüà";

    private static final String EXT = "^{}\\[~]|€\f";

    private GsmUtil() {}

    public static boolean isGsm7(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (BASIC.indexOf(c) < 0 && EXT.indexOf(c) < 0) return false;
        }
        return true;
    }

    /** Units the text occupies in GSM-7 (extension chars count 2). */
    public static int gsm7Units(String text) {
        int units = 0;
        for (int i = 0; i < text.length(); i++) {
            units += (EXT.indexOf(text.charAt(i)) >= 0) ? 2 : 1;
        }
        return units;
    }

    /** Estimated segment count for the message. */
    public static int segments(String text) {
        if (text == null || text.length() == 0) return 0;
        if (isGsm7(text)) {
            int u = gsm7Units(text);
            if (u <= 160) return 1;
            return (u + 152) / 153;
        }
        int n = text.length();
        if (n <= 70) return 1;
        return (n + 66) / 67;
    }

    public static String encodingName(String text) {
        return isGsm7(text) ? "GSM-7" : "UCS-2";
    }
}
