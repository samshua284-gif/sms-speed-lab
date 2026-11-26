package com.twog.smslab;

/**
 * Pure statistics over attempt outcomes — no Android dependencies,
 * unit-testable on a plain JVM with the exact shipped code.
 */
public final class StatsCalc {

    public static final class Result {
        public int total;        // resolved attempts (not still sending)
        public int submitted;    // reached the SMSC (sent result OK)
        public double successPct;
        public int timedCount;   // attempts with a submission time
        public long avgMs;
        public long fastestMs;
        public long slowestMs;
    }

    private StatsCalc() {}

    /**
     * @param total     resolved attempts
     * @param submitted attempts whose submission succeeded
     * @param times     submission times (ms) of the submitted attempts
     */
    public static Result compute(int total, int submitted, long[] times) {
        Result r = new Result();
        r.total = total;
        r.submitted = submitted;
        r.successPct = (total == 0) ? 0.0 : (100.0 * submitted / total);
        r.timedCount = (times == null) ? 0 : times.length;
        if (times != null && times.length > 0) {
            long sum = 0, min = Long.MAX_VALUE, max = Long.MIN_VALUE;
            for (long t : times) {
                sum += t;
                if (t < min) min = t;
                if (t > max) max = t;
            }
            r.avgMs = Math.round((double) sum / times.length);
            r.fastestMs = min;
            r.slowestMs = max;
        }
        return r;
    }
}
