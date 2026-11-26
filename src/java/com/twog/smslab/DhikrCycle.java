package com.twog.smslab;

/**
 * The dhikr rotation in the app's top bar — pure timing, JVM-testable.
 *
 * Owner's specification (1.12, revised in the field): each phrase
 * shines for 5 seconds and the next follows IMMEDIATELY — no empty
 * gaps anywhere, an endless back-to-back rotation, with an
 * optional stop button under the frame.
 * Full cycle: 4 × 5 = 20 seconds.
 */
public final class DhikrCycle {

    public static final String[] PHRASES = {
            "سبحان الله",
            "الحمد لله",
            "الله أكبر",
            "لله الحمد",
    };

    public static final long SHOW_MS = 5_000L;
    public static final long FADE_MS = 350L;

    /**
     * What the frame shows while the dhikr is stopped (owner,
     * 1.15): the noble verse, complete to its end, standing
     * still in the frame until the dhikr is started again.
     * Al-Ahzab 56, with its diacritics and stop mark.
     */
    public static final String VERSE =
            "إِنَّ اللَّهَ وَمَلَائِكَتَهُ يُصَلُّونَ عَلَى النَّبِيِّ ۚ "
            + "يَا أَيُّهَا الَّذِينَ آمَنُوا صَلُّوا عَلَيْهِ وَسَلِّمُوا تَسْلِيمًا";
    public static final String VERSE_REF = "سورة الأحزاب — ٥٦";

    private DhikrCycle() {}

    public static int next(int index) {
        return (index + 1) % PHRASES.length;
    }

    public static long cycleMs() {
        return SHOW_MS * PHRASES.length;
    }
}
