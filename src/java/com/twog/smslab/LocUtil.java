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

    /** @return {lat, lon} of the freshest last-known fix, or null. */
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
            if (best == null) return null;
            return new double[]{best.getLatitude(), best.getLongitude()};
        } catch (Throwable t) {
            return null;
        }
    }

    public static String fmt(double lat, double lon) {
        return String.format(Locale.US, "%.5f,%.5f", lat, lon);
    }

}
