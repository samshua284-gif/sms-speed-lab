package com.twog.smslab;

/**
 * Series stop policy — pure logic, JVM-testable.
 *
 * A series has two honest modes:
 *  - Measurement (default): always completes the chosen count, so
 *    the statistics (average / fastest / slowest) rest on a full
 *    sample. Success changes nothing.
 *  - Reach (the autoRepeat toggle, named "auto-repeat until first
 *    success"): the moment one attempt is SUBMITTED — accepted by
 *    the SMSC, i.e. the message has truly left — the series stops,
 *    so no balance is spent on attempts nobody needs; and a round
 *    with no submission repeats on its own (shouldRepeatRound).
 *    The trigger is submission, not the delivery report: delivery
 *    can trail by minutes and is tracked on the attempt regardless.
 *    (Until 1.24 reach had a second, separate stop-on-success box;
 *    the owner judged it redundant — the toggle's name already
 *    promises the stop — and 1.25 folded the two into one.)
 */
public final class SeriesPolicy {

    private SeriesPolicy() {}

    public static boolean shouldStop(boolean reachOn, String resolvedStatus) {
        return reachOn && "SUBMITTED".equals(resolvedStatus);
    }

    /**
     * The auto-repeat ceiling (owner-approved, 1.26): at most
     * MAX_ROUNDS rounds run back to back. In the worst case
     * (every attempt a full 60 s timeout) that is roughly 75
     * minutes of trying at a round size of 3, about four hours
     * at 10 — a long dead stretch of desert, after which a phone
     * sitting in a truly dead spot would only be spending its
     * battery. Hitting the ceiling is announced, never silent.
     */
    public static final int MAX_ROUNDS = 20;

    /**
     * Auto-repeat rounds (owner's requirement, 1.24): the desert
     * case is not a phone toggled off on purpose — it is 2G enabled
     * with the service simply absent, attempts ending in timeouts,
     * and the owner should NOT have to keep pressing "start series"
     * again. With autoRepeat on, a round that ends with ZERO
     * submitted attempts is followed by a fresh round on its own,
     * until the ceiling; the machine stops when a round contains
     * a submission, when the ceiling is reached, or when the
     * owner presses stop. A round WITH a submission never
     * repeats: the message got out, and re-sending would spend
     * balance and risk a duplicate at the other phone.
     *
     * @param roundNo the round that just ended (1-based)
     */
    public static boolean shouldRepeatRound(boolean autoRepeat,
                                            boolean anySubmittedThisRound,
                                            int roundNo) {
        return autoRepeat && !anySubmittedThisRound && roundNo < MAX_ROUNDS;
    }
}
