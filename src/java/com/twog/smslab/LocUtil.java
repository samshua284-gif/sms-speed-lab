package com.twog.smslab;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;

import java.util.Locale;

/**
 * Location stamping for attempts. Uses the freshest last-known fix
 * (GPS / network / passive) — the send is NEVER delayed waiting for a
 * fix, because the lab measures the network, not the satellite: if no
 * fix exists, the attempt simply carries no stamp.
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
            for (String p : providers) {
                try {
                    Location l = lm.getLastKnownLocation(p);
                    if (l == null) continue;
                    if (best == null || l.getTime() > best.getTime()) best = l;
                } catch (SecurityException | IllegalArgumentException ignored) {}
            }
            android.content.SharedPreferences sp =
                    ctx.getSharedPreferences("smslab_fix", Context.MODE_PRIVATE);
            if (best != null) {
                sp.edit()
                        .putFloat("lat", (float) best.getLatitude())
                        .putFloat("lon", (float) best.getLongitude())
                        .putLong("time", best.getTime())
                        .apply();
                return new double[]{best.getLatitude(), best.getLongitude()};
            }
            long t = sp.getLong("time", 0);
            if (FixCache.usable(t, System.currentTimeMillis())) {
                return new double[]{
                        sp.getFloat("lat", 0f), sp.getFloat("lon", 0f)};
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static String fmt(double lat, double lon) {
        return String.format(Locale.US, "%.5f,%.5f", lat, lon);
    }

}
