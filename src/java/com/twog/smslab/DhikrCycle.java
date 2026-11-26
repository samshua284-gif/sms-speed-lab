package com.twog.smslab;

/**
 * The dhikr rotation in the app's top bar — pure timing, JVM-testable.
 *
 * Owner's specification (1.11): each phrase is shown beautifully for
 * 10 seconds, then fades; the next comes after 5 seconds; after the
 * last phrase the pause is 15 seconds and the loop restarts — an
 * endless, automatic cycle, with an optional stop button.
 * Full cycle: 4×10 + 5 + 5 + 5 + 15 = 70 seconds.
 */
public final class DhikrCycle {

    public static final String[] PHRASES = {
            "سبحان الله",
            "الحمد لله",
            "الله أكبر",
            "لله الحمد",
    };

    public static final long SHOW_MS = 10_000L;
    public static final long GAP_MS = 5_000L;
    public static final long RESTART_GAP_MS = 15_000L;

    private DhikrCycle() {}

    public static int next(int index) {
        return (index + 1) % PHRASES.length;
    }

    /** Pause after the phrase at {@code index} fades out. */
    public static long gapAfter(int index) {
        return index == PHRASES.length - 1 ? RESTART_GAP_MS : GAP_MS;
    }

    public static long cycleMs() {
        long t = 0;
        for (int i = 0; i < PHRASES.length; i++) t += SHOW_MS + gapAfter(i);
        return t;
    }
}
