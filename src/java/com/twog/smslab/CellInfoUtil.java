package com.twog.smslab;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.CellInfo;
import android.telephony.CellSignalStrength;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;

import java.util.List;

/**
 * Honest signal snapshot. dBm comes only from the registered cell's
 * CellSignalStrength (API 28+, needs location permission) and is
 * reported as unknown otherwise — a 0..4 "level" or an ASU value is
 * NEVER relabelled as dBm (the reviewed draft's central instrument
 * error). Level is the standard 0..4 scale; ASU is reported as ASU.
 */
public final class CellInfoUtil {

    public static final class Snapshot {
        public String netType = "—";
        public Integer dbm;    // null = unknown
        public Integer level;  // null = unknown, else 0..4
        public Integer asu;    // null = unknown
    }

    private CellInfoUtil() {}

    public static Snapshot snapshot(Context ctx) {
        Snapshot s = new Snapshot();
        TelephonyManager tm =
                (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) return s;

        boolean phonePerm = ctx.checkSelfPermission(
                Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
        boolean locPerm = ctx.checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (phonePerm) {
            try { s.netType = netLabel(tm.getDataNetworkType()); }
            catch (Throwable ignored) {}
        }

        if (locPerm && Build.VERSION.SDK_INT >= 28) {
            try {
                List<CellInfo> cells = tm.getAllCellInfo();
                if (cells != null) {
                    for (CellInfo ci : cells) {
                        if (!ci.isRegistered()) continue;
                        CellSignalStrength ss = ci.getCellSignalStrength();
                        if (ss != null) {
                            s.dbm = ss.getDbm();
                            s.level = ss.getLevel();
                            int asu = ss.getAsuLevel();
                            if (asu >= 0 && asu != 99) s.asu = asu;
                        }
                        break;
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (s.level == null && phonePerm && Build.VERSION.SDK_INT >= 29) {
            try {
                SignalStrength ss = tm.getSignalStrength();
                if (ss != null) s.level = ss.getLevel();
            } catch (Throwable ignored) {}
        }

        return s;
    }

    static String netLabel(int t) {
        switch (t) {
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_CDMA:
            case TelephonyManager.NETWORK_TYPE_1xRTT:
            case TelephonyManager.NETWORK_TYPE_IDEN:
                return "2G";
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_HSPAP:
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
                return "3G";
            case TelephonyManager.NETWORK_TYPE_LTE:
                return "4G";
            case TelephonyManager.NETWORK_TYPE_NR:
                return "5G";
            default:
                return "—";
        }
    }
}
