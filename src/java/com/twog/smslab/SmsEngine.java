package com.twog.smslab;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.SmsManager;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Serialized SMS measurement engine — the corrected state machine.
 *
 * Corrections against the reviewed draft:
 *  - ONE attempt in flight at a time. A single shared startTime can
 *    no longer be overwritten by an overlapping send, because an
 *    overlapping send is impossible (send() refuses while busy).
 *  - Every attempt owns UNIQUE PendingIntents (requestCode derived
 *    from the attempt id), so results are attributed to their own
 *    attempt — PendingIntent identity ignores extras, so the code,
 *    not the extra, carries the identity.
 *  - Submission resolves only when ALL parts report (or the first
 *    error arrives); a fixed blind timer never stands in for the
 *    result. A 60 s watchdog marks TIMEOUT — an honest "unknown",
 *    never an automatic resend. This lab measures; it does not retry.
 *  - Delivery is tracked from the store by attempt id, so a late
 *    delivery report still lands on its attempt; after 180 s a
 *    submitted attempt is closed as DELIVERY_UNKNOWN.
 */
public final class SmsEngine {

    public static final String ACTION_SENT = "com.twog.smslab.SMS_SENT";
    public static final String ACTION_DELIVERED = "com.twog.smslab.SMS_DELIVERED";
    private static final long SEND_TIMEOUT_MS = 60_000L;
    private static final long DELIVERY_TIMEOUT_MS = 180_000L;

    public interface Listener {
        void onAttemptUpdated(AttemptStore.Attempt a);
    }

    private static final class Flight {
        long id;
        long t0Elapsed;
        int parts;
        int partsReported;
        boolean resolved;
    }

    private final Context appContext;
    private final AttemptStore store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;
    private boolean registered;
    private Flight flight;

    public SmsEngine(Context ctx, AttemptStore store) {
        this.appContext = ctx.getApplicationContext();
        this.store = store;
    }

    public void setListener(Listener l) { listener = l; }

    public boolean isBusy() { return flight != null && !flight.resolved; }

    // ---------------------------------------------------------------
    // Receivers
    // ---------------------------------------------------------------

    private final BroadcastReceiver sentReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            long id = intent.getLongExtra("attemptId", -1);
            onSentResult(id, getResultCode());
        }
    };

    private final BroadcastReceiver deliveredReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            long id = intent.getLongExtra("attemptId", -1);
            onDeliveredResult(id, getResultCode() == Activity.RESULT_OK);
        }
    };

    public void register() {
        if (registered) return;
        IntentFilter sf = new IntentFilter(ACTION_SENT);
        IntentFilter df = new IntentFilter(ACTION_DELIVERED);
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(sentReceiver, sf, Context.RECEIVER_NOT_EXPORTED);
            appContext.registerReceiver(deliveredReceiver, df, Context.RECEIVER_NOT_EXPORTED);
        } else {
            appContext.registerReceiver(sentReceiver, sf);
            appContext.registerReceiver(deliveredReceiver, df);
        }
        registered = true;
    }

    public void unregister() {
        if (!registered) return;
        try { appContext.unregisterReceiver(sentReceiver); } catch (Exception ignored) {}
        try { appContext.unregisterReceiver(deliveredReceiver); } catch (Exception ignored) {}
        registered = false;
    }

    // ---------------------------------------------------------------
    // Sending
    // ---------------------------------------------------------------

    /** @return attempt id, or -1 if another attempt is in flight. */
    public long send(String phone, String text) {
        if (isBusy()) return -1;

        SmsManager mgr = getSmsManager();
        ArrayList<String> parts = mgr.divideMessage(text);
        int partCount = Math.max(1, parts.size());

        CellInfoUtil.Snapshot cell = CellInfoUtil.snapshot(appContext);

        AttemptStore.Attempt a = new AttemptStore.Attempt();
        a.id = store.nextId();
        a.tsWall = System.currentTimeMillis();
        a.phone = phone;
        a.text = text;
        a.encoding = GsmUtil.encodingName(text);
        a.parts = partCount;
        a.netType = cell.netType;
        a.dbm = (cell.dbm == null) ? 999 : cell.dbm;
        a.level = (cell.level == null) ? -1 : cell.level;
        a.asu = (cell.asu == null) ? -1 : cell.asu;
        a.status = AttemptStore.S_SENDING;
        store.add(a);

        int base = (int) ((a.id * 2) % 90000);
        PendingIntent sentPI = PendingIntent.getBroadcast(
                appContext, base,
                new Intent(ACTION_SENT).setPackage(appContext.getPackageName())
                        .putExtra("attemptId", a.id),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent delPI = PendingIntent.getBroadcast(
                appContext, base + 1,
                new Intent(ACTION_DELIVERED).setPackage(appContext.getPackageName())
                        .putExtra("attemptId", a.id),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Flight f = new Flight();
        f.id = a.id;
        f.t0Elapsed = SystemClock.elapsedRealtime();
        f.parts = partCount;
        flight = f;

        final long fid = a.id;
        handler.postDelayed(() -> onSendTimeout(fid), SEND_TIMEOUT_MS);

        try {
            if (partCount == 1) {
                mgr.sendTextMessage(phone, null, text, sentPI, delPI);
            } else {
                ArrayList<PendingIntent> sList =
                        new ArrayList<>(Collections.nCopies(partCount, sentPI));
                ArrayList<PendingIntent> dList =
                        new ArrayList<>(Collections.nCopies(partCount, delPI));
                mgr.sendMultipartTextMessage(phone, null, parts, sList, dList);
            }
        } catch (Exception e) {
            resolveFailed(a, "EXC: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        notifyUpdated(a);
        return a.id;
    }

    // ---------------------------------------------------------------
    // Results
    // ---------------------------------------------------------------

    private void onSentResult(long id, int resultCode) {
        Flight f = flight;
        if (f == null || f.id != id || f.resolved) return;
        if (resultCode != Activity.RESULT_OK) {
            AttemptStore.Attempt a = store.byId(id);
            if (a != null) resolveFailed(a, errorName(resultCode));
            return;
        }
        f.partsReported++;
        if (f.partsReported < f.parts) return; // wait for the rest
        f.resolved = true;
        flight = null;

        AttemptStore.Attempt a = store.byId(id);
        if (a == null) return;
        a.status = AttemptStore.S_SUBMITTED;
        a.submitMs = SystemClock.elapsedRealtime() - f.t0Elapsed;
        store.update(a);
        notifyUpdated(a);

        final long fid = id;
        handler.postDelayed(() -> onDeliveryTimeout(fid), DELIVERY_TIMEOUT_MS);
    }

    private void onDeliveredResult(long id, boolean ok) {
        AttemptStore.Attempt a = store.byId(id);
        if (a == null) return;
        if (!AttemptStore.S_SUBMITTED.equals(a.status)) return;
        a.status = ok ? AttemptStore.S_DELIVERED : AttemptStore.S_DELIVERY_FAILED;
        a.deliveryMs = System.currentTimeMillis() - a.tsWall;
        store.update(a);
        notifyUpdated(a);
    }

    private void onSendTimeout(long id) {
        Flight f = flight;
        if (f == null || f.id != id || f.resolved) return;
        f.resolved = true;
        flight = null;
        AttemptStore.Attempt a = store.byId(id);
        if (a != null && AttemptStore.S_SENDING.equals(a.status)) {
            a.status = AttemptStore.S_TIMEOUT;
            a.error = "No sent-result within 60s (result unknown)";
            store.update(a);
            notifyUpdated(a);
        }
    }

    private void onDeliveryTimeout(long id) {
        AttemptStore.Attempt a = store.byId(id);
        if (a != null && AttemptStore.S_SUBMITTED.equals(a.status)) {
            a.status = AttemptStore.S_DELIVERY_UNKNOWN;
            store.update(a);
            notifyUpdated(a);
        }
    }

    private void resolveFailed(AttemptStore.Attempt a, String error) {
        Flight f = flight;
        if (f != null && f.id == a.id) { f.resolved = true; flight = null; }
        a.status = AttemptStore.S_FAILED;
        a.error = error;
        store.update(a);
        notifyUpdated(a);
    }

    private void notifyUpdated(AttemptStore.Attempt a) {
        if (listener != null) listener.onAttemptUpdated(a);
    }

    private SmsManager getSmsManager() {
        if (Build.VERSION.SDK_INT >= 31) {
            SmsManager m = appContext.getSystemService(SmsManager.class);
            if (m != null) return m;
        }
        //noinspection deprecation
        return SmsManager.getDefault();
    }

    static String errorName(int code) {
        switch (code) {
            case SmsManager.RESULT_ERROR_GENERIC_FAILURE: return "GENERIC_FAILURE";
            case SmsManager.RESULT_ERROR_NO_SERVICE: return "NO_SERVICE";
            case SmsManager.RESULT_ERROR_RADIO_OFF: return "RADIO_OFF";
            case SmsManager.RESULT_ERROR_NULL_PDU: return "NULL_PDU";
            default: return "Error " + code;
        }
    }
}
