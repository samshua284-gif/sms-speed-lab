package com.twog.smslab;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent log of measurement attempts (SharedPreferences + JSON).
 * Every attempt keeps the signal snapshot taken at send time, the
 * encoding and part count, and the submission / delivery timings —
 * the raw material for the statistics panel and the CSV export.
 */
public final class AttemptStore {

    public static final String S_SENDING = "SENDING";
    public static final String S_SUBMITTED = "SUBMITTED";
    public static final String S_DELIVERED = "DELIVERED";
    public static final String S_DELIVERY_FAILED = "DELIVERY_FAILED";
    public static final String S_DELIVERY_UNKNOWN = "DELIVERY_UNKNOWN";
    public static final String S_FAILED = "FAILED";
    public static final String S_TIMEOUT = "TIMEOUT";

    public static final class Attempt {
        public long id;
        public long tsWall;        // wall-clock send time (epoch ms)
        public String phone = "";      // number as SENT (normalized)
        public String phoneTyped = ""; // number exactly as typed
        public String text = "";
        public String encoding = "";
        public int parts;
        public String netType = "—";
        public int dbm = 999;      // 999 = unknown
        public int level = -1;     // -1 = unknown, else 0..4
        public int asu = -1;       // -1 = unknown
        public String status = S_SENDING;
        public String error = "";
        public long submitMs = -1;
        public long deliveryMs = -1;
        public String lat = "";    // "" = no fix at send time
        public String lon = "";

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id); o.put("ts", tsWall); o.put("phone", phone);
                o.put("phoneT", phoneTyped);
                o.put("text", text); o.put("enc", encoding); o.put("parts", parts);
                o.put("net", netType); o.put("dbm", dbm); o.put("level", level);
                o.put("asu", asu); o.put("status", status); o.put("error", error);
                o.put("submitMs", submitMs); o.put("deliveryMs", deliveryMs);
                o.put("lat", lat); o.put("lon", lon);
            } catch (Exception ignored) {}
            return o;
        }

        static Attempt fromJson(JSONObject o) {
            Attempt a = new Attempt();
            a.id = o.optLong("id");
            a.tsWall = o.optLong("ts");
            a.phone = o.optString("phone", "");
            a.phoneTyped = o.optString("phoneT", "");
            a.text = o.optString("text", "");
            a.encoding = o.optString("enc", "");
            a.parts = o.optInt("parts");
            a.netType = o.optString("net", "—");
            a.dbm = o.optInt("dbm", 999);
            a.level = o.optInt("level", -1);
            a.asu = o.optInt("asu", -1);
            a.status = o.optString("status", S_SENDING);
            a.error = o.optString("error", "");
            a.submitMs = o.optLong("submitMs", -1);
            a.deliveryMs = o.optLong("deliveryMs", -1);
            a.lat = o.optString("lat", "");
            a.lon = o.optString("lon", "");
            return a;
        }
    }

    private final SharedPreferences prefs;

    public AttemptStore(Context ctx) {
        prefs = ctx.getApplicationContext()
                .getSharedPreferences("smslab_attempts", Context.MODE_PRIVATE);
    }

    public synchronized long nextId() {
        long id = prefs.getLong("seq", 0) + 1;
        prefs.edit().putLong("seq", id).apply();
        return id;
    }

    /** The id the NEXT attempt will receive, without consuming it. */
    public synchronized long peekNextId() {
        return prefs.getLong("seq", 0) + 1;
    }

    public synchronized List<Attempt> all() {
        List<Attempt> out = new ArrayList<>();
        String raw = prefs.getString("attempts", "[]");
        try {
            JSONArray arr = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) out.add(Attempt.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) {}
        return out;
    }

    public synchronized void add(Attempt a) {
        List<Attempt> list = all();
        list.add(a);
        save(list);
    }

    public synchronized void update(Attempt a) {
        List<Attempt> list = all();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id == a.id) { list.set(i, a); break; }
        }
        save(list);
    }

    public synchronized Attempt byId(long id) {
        for (Attempt a : all()) if (a.id == id) return a;
        return null;
    }

    public synchronized void clear() {
        prefs.edit().putString("attempts", "[]").apply();
    }

    private void save(List<Attempt> list) {
        JSONArray arr = new JSONArray();
        for (Attempt a : list) arr.put(a.toJson());
        prefs.edit().putString("attempts", arr.toString()).apply();
    }

    public StatsCalc.Result stats() {
        int total = 0, submitted = 0;
        List<Long> times = new ArrayList<>();
        for (Attempt a : all()) {
            if (S_SENDING.equals(a.status)) continue;
            total++;
            if (S_SUBMITTED.equals(a.status) || S_DELIVERED.equals(a.status)
                    || S_DELIVERY_FAILED.equals(a.status) || S_DELIVERY_UNKNOWN.equals(a.status)) {
                submitted++;
            }
            if (a.submitMs >= 0) times.add(a.submitMs);
        }
        long[] t = new long[times.size()];
        for (int i = 0; i < t.length; i++) t[i] = times.get(i);
        return StatsCalc.compute(total, submitted, t);
    }

    public synchronized String toCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("id,time_iso,phone_sent,phone_typed,encoding,parts,network,dbm,level,asu,lat,lon,status,error,submit_ms,delivery_ms,text\n");
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
        for (Attempt a : all()) {
            sb.append(a.id).append(',');
            sb.append(fmt.format(new java.util.Date(a.tsWall))).append(',');
            sb.append(q(a.phone)).append(',');
            sb.append(q(a.phoneTyped)).append(',');
            sb.append(q(a.encoding)).append(',');
            sb.append(a.parts).append(',');
            sb.append(q(a.netType)).append(',');
            sb.append(a.dbm == 999 ? "" : String.valueOf(a.dbm)).append(',');
            sb.append(a.level < 0 ? "" : String.valueOf(a.level)).append(',');
            sb.append(a.asu < 0 ? "" : String.valueOf(a.asu)).append(',');
            sb.append(a.lat).append(',');
            sb.append(a.lon).append(',');
            sb.append(q(a.status)).append(',');
            sb.append(q(a.error)).append(',');
            sb.append(a.submitMs < 0 ? "" : String.valueOf(a.submitMs)).append(',');
            sb.append(a.deliveryMs < 0 ? "" : String.valueOf(a.deliveryMs)).append(',');
            sb.append(q(a.text)).append('\n');
        }
        return sb.toString();
    }

    private static String q(String s) {
        if (s == null) return "\"\"";
        return '"' + s.replace("\"", "\"\"").replace("\n", " ") + '"';
    }
}
