package com.twog.smslab;

/**
 * Series stop policy — pure logic, JVM-testable.
 *
 * A series has two honest modes:
 *  - Measurement (default): always completes the chosen count, so
 *    the statistics (average / fastest / slowest) rest on a full
 *    sample. Success changes nothing.
 *  - Reach (stopOnSuccess): the moment one attempt is SUBMITTED —
 *    accepted by the SMSC, i.e. the message has truly left — the
 *    series stops, so no balance is spent on attempts nobody needs.
 *    The trigger is submission, not the delivery report: delivery
 *    can trail by minutes and is tracked on the attempt regardless.
 */
public final class SeriesPolicy {

    private SeriesPolicy() {}

    public static boolean shouldStop(boolean stopOnSuccess, String resolvedStatus) {
        return stopOnSuccess && "SUBMITTED".equals(resolvedStatus);
    }
}
