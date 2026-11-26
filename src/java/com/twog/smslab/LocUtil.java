package com.twog.smslab;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;

import java.util.Locale;

/**
 * Location stamping for attempts.
 *
 * History's lesson, completed in 1.18: through 1.17 this class only
 * ever READ the system's last-known cache — the app never asked the
 * system for a position of its own. When no other app had recently
 * requested location, every provider's cache stood empty, the app's
 * own fallback memory was never fed either, and messages went out
 * time-only again and again. The radical fix: the app now ACTIVELY
 * tracks location while it is in the foreground (MainActivity's
 * listener feeds saveFix below) and, at series start, demands a
 * fresh fix with a bounded wait. This class remains the store and
 * the reader; the asking lives in the activity.
 *
 * The coordinate line appended to a message is pure GSM-7 text
 * ("LOC: 22.98123,57.52341") so it never forces a UCS-2 re-encode.
 */
public final class LocUtil {

    private LocUtil() {}

    /**
     * @return {lat, lon} of the freshest last-known fix, or null.
     * Every real fix seen is remembered by the app itself; when the
     * system cache blinks empty (seen in the field, 1.7), the app's
     * own last fix is reused while it is still fresh (FixCache).
     */
    public static double[] lastKnown(Context ctx) {
        try {
            LocationManager lm =
                    (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            Location best = null;
            String[] providers = {
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER};
            long nowMs = System.currentTimeMillis();
            for (String p : providers) {
                try {
                    Location l = lm.getLastKnownLocation(p);
                    if (l == null) continue;
                    // 1.20: never resurrect a stale system fix as
                    // a current position.
                    if (!FixCache.systemFixAcceptable(l.getTime(), nowMs)) continue;
                    if (best == null || l.getTime() > best.getTime()) best = l;
                } catch (SecurityException | IllegalArgumentException ignored) {}
            }
            android.content.SharedPreferences sp =
                    ctx.getSharedPreferences("smslab_fix", Context.MODE_PRIVATE);
            if (best != null) {
                // Freshness is judged from the moment WE obtained
                // the position (1.10) — not from the fix's own birth
                // time: a fix born long ago while the sender sat
                // still made the memory "expire" before it was ever
                // used, and the field series lost its location from
                // the second attempt on.
                saveFix(ctx, best);
                return new double[]{best.getLatitude(), best.getLongitude()};
            }
            long seen = sp.getLong("seen", 0);
            if (FixCache.usable(seen, System.currentTimeMillis())) {
                return new double[]{
                        sp.getFloat("lat", 0f), sp.getFloat("lon", 0f)};
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Remember a fix the app itself obtained, stamped with NOW. */
    public static void saveFix(Context ctx, Location l) {
        if (ctx == null || l == null) return;
        ctx.getSharedPreferences("smslab_fix", Context.MODE_PRIVATE)
                .edit()
                .putFloat("lat", (float) l.getLatitude())
                .putFloat("lon", (float) l.getLongitude())
                .putLong("seen", System.currentTimeMillis())
                .apply();
    }

    /** @return {lat, lon, seenMs} of the app's remembered fix, or null. */
    public static double[] cachedFix(Context ctx) {
        try {
            android.content.SharedPreferences sp =
                    ctx.getSharedPreferences("smslab_fix", Context.MODE_PRIVATE);
            long seen = sp.getLong("seen", 0);
            if (seen <= 0) return null;
            return new double[]{
                    sp.getFloat("lat", 0f), sp.getFloat("lon", 0f), seen};
        } catch (Throwable t) {
            return null;
        }
    }

    public static String fmt(double lat, double lon) {
        return String.format(Locale.US, "%.5f,%.5f", lat, lon);
    }

}
