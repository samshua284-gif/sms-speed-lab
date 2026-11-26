package com.twog.smslab;

import java.util.Locale;

/**
 * Outgoing payload composition — pure logic, JVM-testable.
 *
 * By the project owner's design (1.6), EVERY message leaves with
 * its send time stamped automatically, and — when the attach
 * option is on and a fix exists — its location under it:
 *
 *     <the message exactly as written>
 *     LOC: 22.52460,57.53556
 *     TIME: 07:47
 *
 * Both lines are plain GSM-7-safe text and are part of the payload
 * BEFORE encoding and segment count are computed, so the attempt
 * record describes exactly what left the phone.
 */
public final class Payload {

    private Payload() {}

    public static String compose(String text, String hhmm,
                                 double[] loc, boolean attachLoc) {
        // Owner's order (1.7): location first, time after it.
        StringBuilder sb = new StringBuilder(text);
        if (loc != null && attachLoc) {
            sb.append("\nLOC: ")
              .append(String.format(Locale.US, "%.5f", loc[0]))
              .append(',')
              .append(String.format(Locale.US, "%.5f", loc[1]));
        }
        sb.append("\nTIME: ").append(hhmm);
        return sb.toString();
    }
}
