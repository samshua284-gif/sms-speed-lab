package com.twog.smslab;

import java.util.regex.Pattern;

/**
 * Live stamp for test messages — pure logic, JVM-testable.
 *
 * The quick-test button fills a template like "TEST #7 - 07:23".
 * Without re-stamping, a whole series would carry that one frozen
 * number and time. So at the moment of each actual send, a text that
 * contains a stamp of the form  TEST #<n> - <HH:mm>  gets the stamp
 * replaced with THIS attempt's real id and the current time — the
 * inbox on the receiving phone then matches the lab log attempt for
 * attempt. Any other text (including the plain "TEST 123456"
 * protocol message, which has no stamp) is sent byte-for-byte as
 * typed.
 */
public final class StampUtil {

    private static final Pattern STAMP =
            Pattern.compile("TEST #\\d+ - \\d{2}:\\d{2}");

    private StampUtil() {}

    public static boolean hasStamp(String text) {
        return text != null && STAMP.matcher(text).find();
    }

    public static String stamp(String text, long attemptId, String hhmm) {
        if (text == null) return null;
        return STAMP.matcher(text)
                .replaceFirst("TEST #" + attemptId + " - " + hhmm);
    }
}
