package com.twog.smslab;

/**
 * App-side memory of the last good location fix — pure rule,
 * JVM-testable.
 *
 * Field lesson (1.7): in one series, two attempts carried the
 * location and the third suddenly carried none — the system's
 * last-known cache blinked empty for a moment, and the app, which
 * only ever reads that cache, had nothing to fall back on. So the
 * app now remembers the last fix IT saw, and may reuse it while it
 * is still fresh: at most 2 minutes old (tightened in 1.9 — the
 * observed system blink lasted seconds inside one series, and a
 * shorter window caps how far a moved sender's stale position can
 * be from the truth). Older than that, a stale position is worse
 * than none — the message goes out time-only.
 */
public final class FixCache {

    public static final long MAX_AGE_MS = 2 * 60 * 1000L;

    private FixCache() {}

    public static boolean usable(long fixTimeMs, long nowMs) {
        if (fixTimeMs <= 0) return false;
        long age = nowMs - fixTimeMs;
        return age >= 0 && age <= MAX_AGE_MS;
    }
}
