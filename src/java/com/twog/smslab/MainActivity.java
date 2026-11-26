package com.twog.smslab;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * طمأنينة — 2G SMS Speed Lab.
 * A measurement instrument, not a speed promise: it sends single SMS
 * attempts (or a disciplined series), records the network's own
 * submission and delivery times with the signal snapshot of the
 * moment, and reports averages / fastest / slowest honestly.
 */
public class MainActivity extends Activity {

    private static final int REQ_PERM = 7;
    private static final int REQ_EXPORT = 42;
    private static final long SERIES_GAP_MS = 15_000L;

    private AttemptStore store;
    private SmsEngine engine;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView signalDbm, signalMeta, charInfo, statusInfo, statsInfo, logInfo;
    private EditText phoneInput, messageInput, seriesCount;
    private Button sendBtn, clearBtn;

    private boolean seriesActive;
    private int seriesLeft;
    private boolean seriesScheduled;

    private final Runnable signalTick = new Runnable() {
        @Override public void run() {
            refreshSignal();
            handler.postDelayed(this, 7000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        store = new AttemptStore(this);
        engine = new SmsEngine(this, store);

        signalDbm = findViewById(R.id.signalDbm);
        signalMeta = findViewById(R.id.signalMeta);
        charInfo = findViewById(R.id.charInfo);
        statusInfo = findViewById(R.id.statusInfo);
        statsInfo = findViewById(R.id.statsInfo);
        logInfo = findViewById(R.id.logInfo);
        phoneInput = findViewById(R.id.phoneInput);
        messageInput = findViewById(R.id.messageInput);
        seriesCount = findViewById(R.id.seriesCount);
        sendBtn = findViewById(R.id.sendBtn);
        clearBtn = findViewById(R.id.clearBtn);

        engine.setListener(a -> {
            refreshStatsAndLog();
            if (AttemptStore.S_SUBMITTED.equals(a.status)) {
                statusInfo.setText("قُبلت الرسالة عند مركز الرسائل — بانتظار تقرير التسليم");
                beep(ToneGenerator.TONE_PROP_BEEP, 140);
                onSeriesAttemptResolved();
            } else if (AttemptStore.S_DELIVERED.equals(a.status)) {
                statusInfo.setText("سُلّمت الرسالة إلى الهاتف الآخر ✓");
                beep(ToneGenerator.TONE_PROP_ACK, 220);
            } else if (AttemptStore.S_FAILED.equals(a.status)) {
                statusInfo.setText("فشل الإرسال: " + a.error);
                beep(ToneGenerator.TONE_PROP_NACK, 260);
                onSeriesAttemptResolved();
            } else if (AttemptStore.S_TIMEOUT.equals(a.status)) {
                statusInfo.setText("انقضت مهلة نتيجة الإرسال (النتيجة غير معروفة)");
                beep(ToneGenerator.TONE_PROP_NACK, 260);
                onSeriesAttemptResolved();
            }
        });
        engine.register();

        // Restore the last number and message — a repeated field test
        // must not start with retyping.
        SharedPreferences ui = getSharedPreferences("smslab_ui", MODE_PRIVATE);
        phoneInput.setText(ui.getString("last_phone", ""));
        messageInput.setText(ui.getString("last_text", ""));

        CheckBox attachLoc = findViewById(R.id.attachLocCheck);
        attachLoc.setChecked(ui.getBoolean("attach_loc", true));
        engine.setAttachLoc(attachLoc.isChecked());
        attachLoc.setOnCheckedChangeListener((v, checked) -> {
            engine.setAttachLoc(checked);
            getSharedPreferences("smslab_ui", MODE_PRIVATE)
                    .edit().putBoolean("attach_loc", checked).apply();
        });

        Button quickTestBtn = findViewById(R.id.quickTestBtn);
        quickTestBtn.setOnClickListener(v -> {
            String t = "TEST #" + store.peekNextId() + " - "
                    + new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
            messageInput.setText(t);
            messageInput.setSelection(t.length());
        });

        messageInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                String t = s == null ? "" : s.toString();
                charInfo.setText("الأحرف: " + t.length()
                        + " • الترميز: " + GsmUtil.encodingName(t)
                        + " • الأجزاء: " + Math.max(1, GsmUtil.segments(t)));
            }
        });

        sendBtn.setOnClickListener(v -> {
            if (!ensureSmsPermission()) return;
            doSend();
        });

        Button seriesBtn = findViewById(R.id.seriesBtn);
        Button seriesStopBtn = findViewById(R.id.seriesStopBtn);
        seriesBtn.setOnClickListener(v -> {
            if (!ensureSmsPermission()) return;
            int n;
            try { n = Integer.parseInt(seriesCount.getText().toString().trim()); }
            catch (Exception e) { n = 10; }
            if (n < 1) n = 1; if (n > 50) n = 50;
            seriesActive = true;
            seriesLeft = n;
            statusInfo.setText("بدأت سلسلة من " + n + " محاولات — فاصل 15 ثانية");
            trySeriesSend();
        });
        seriesStopBtn.setOnClickListener(v -> {
            seriesActive = false;
            statusInfo.setText("أُوقفت السلسلة");
        });

        Button exportBtn = findViewById(R.id.exportBtn);
        exportBtn.setOnClickListener(v -> {
            Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("text/csv");
            it.putExtra(Intent.EXTRA_TITLE, "tamanina-results.csv");
            startActivityForResult(it, REQ_EXPORT);
        });

        clearBtn.setOnClickListener(v -> {
            if ("متأكد؟".contentEquals(clearBtn.getText())) {
                store.clear();
                clearBtn.setText("مسح السجل");
                refreshStatsAndLog();
                statusInfo.setText("مُسح السجل");
            } else {
                clearBtn.setText("متأكد؟");
                handler.postDelayed(() -> clearBtn.setText("مسح السجل"), 3000);
            }
        });

        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.ACCESS_FINE_LOCATION}, REQ_PERM);
        }

        refreshSignal();
        refreshStatsAndLog();
        handler.postDelayed(signalTick, 7000);
    }

    // ---------------------------------------------------------------

    private boolean ensureSmsPermission() {
        if (checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) return true;
        requestPermissions(new String[]{
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION}, REQ_PERM);
        Toast.makeText(this, "امنح إذن الرسائل أولًا ثم أعد المحاولة", Toast.LENGTH_LONG).show();
        return false;
    }

    private void saveInputs() {
        getSharedPreferences("smslab_ui", MODE_PRIVATE).edit()
                .putString("last_phone", phoneInput.getText().toString())
                .putString("last_text", messageInput.getText().toString())
                .apply();
    }

    private void beep(int tone, int ms) {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
            tg.startTone(tone, ms);
            handler.postDelayed(tg::release, ms + 120);
        } catch (Throwable ignored) {}
    }

    private void doSend() {
        String phone = phoneInput.getText().toString().trim();
        String text = messageInput.getText().toString();
        if (phone.isEmpty()) { phoneInput.setError("أدخل رقم الهاتف"); return; }
        if (text.trim().isEmpty()) { messageInput.setError("أدخل نص الرسالة"); return; }
        saveInputs();
        long id = engine.send(phone, text);
        if (id < 0) {
            Toast.makeText(this, "محاولة جارية — انتظر نتيجتها أولًا", Toast.LENGTH_SHORT).show();
            return;
        }
        statusInfo.setText("جارٍ إرسال المحاولة #" + id + " ...");
        refreshStatsAndLog();
    }

    // ---------------------------------------------------------------
    // Series: strictly sequential; the next attempt is scheduled only
    // after the current one resolves, with a fixed 15 s gap.
    // ---------------------------------------------------------------

    private void trySeriesSend() {
        if (!seriesActive) return;
        if (seriesLeft <= 0) {
            seriesActive = false;
            statusInfo.setText("اكتملت السلسلة ✓");
            return;
        }
        if (engine.isBusy()) {
            handler.postDelayed(this::trySeriesSend, 2000);
            return;
        }
        String phone = phoneInput.getText().toString().trim();
        String text = messageInput.getText().toString();
        if (phone.isEmpty() || text.trim().isEmpty()) {
            seriesActive = false;
            statusInfo.setText("أُوقفت السلسلة: أدخل الرقم والنص أولًا");
            return;
        }
        saveInputs();
        long id = engine.send(phone, text);
        if (id >= 0) {
            seriesLeft--;
            statusInfo.setText("سلسلة: أُرسلت المحاولة #" + id + " — المتبقي بعد هذه: " + seriesLeft);
            refreshStatsAndLog();
        } else {
            handler.postDelayed(this::trySeriesSend, 2000);
        }
    }

    private void onSeriesAttemptResolved() {
        if (!seriesActive || seriesScheduled) return;
        seriesScheduled = true;
        handler.postDelayed(() -> {
            seriesScheduled = false;
            trySeriesSend();
        }, SERIES_GAP_MS);
    }

    // ---------------------------------------------------------------

    private void refreshSignal() {
        CellInfoUtil.Snapshot s = CellInfoUtil.snapshot(this);
        if (s.dbm != null) {
            signalDbm.setText(s.dbm + " dBm");
        } else if (s.level != null) {
            signalDbm.setText("المستوى " + s.level + " / 4");
        } else {
            signalDbm.setText("—");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("الشبكة: ").append(s.netType);
        if (s.asu != null) sb.append(" • ASU ").append(s.asu);
        if (s.level != null && s.dbm != null) sb.append(" • المستوى ").append(s.level).append(" / 4");
        if (s.dbm == null) sb.append(" • لقراءة dBm فعّل إذن الموقع");
        signalMeta.setText(sb.toString());
    }

    private void refreshStatsAndLog() {
        StatsCalc.Result r = store.stats();
        if (r.total == 0) {
            statsInfo.setText("لا محاولات مسجلة بعد");
        } else {
            String pct = String.format(Locale.US, "%.0f", r.successPct);
            StringBuilder sb = new StringBuilder();
            sb.append("المحاولات: ").append(r.total)
              .append(" • قُبلت عند مركز الرسائل: ").append(r.submitted)
              .append(" (").append(pct).append("٪)");
            if (r.timedCount > 0) {
                sb.append("\nزمن القبول — المتوسط: ").append(r.avgMs).append("ms")
                  .append(" • الأسرع: ").append(r.fastestMs).append("ms")
                  .append(" • الأبطأ: ").append(r.slowestMs).append("ms");
            }
            statsInfo.setText(sb.toString());
        }

        List<AttemptStore.Attempt> all = store.all();
        SimpleDateFormat tf = new SimpleDateFormat("HH:mm:ss", Locale.US);
        StringBuilder log = new StringBuilder();
        int shown = 0;
        for (int i = all.size() - 1; i >= 0 && shown < 40; i--, shown++) {
            AttemptStore.Attempt a = all.get(i);
            log.append('#').append(a.id).append(' ')
               .append(tf.format(new Date(a.tsWall))).append(" • ")
               .append(a.netType).append(' ')
               .append(a.dbm == 999 ? "—" : (a.dbm + "dBm")).append(" • ")
               .append("أجزاء ").append(a.parts).append(" • ")
               .append(statusAr(a));
            if (a.submitMs >= 0) log.append(" • قبول ").append(a.submitMs).append("ms");
            if (a.deliveryMs >= 0) log.append(" • تسليم ").append(a.deliveryMs).append("ms");
            if (!a.lat.isEmpty()) log.append(" • ").append(a.lat).append(',').append(a.lon);
            if (!a.error.isEmpty()) log.append(" • ").append(a.error);
            log.append('\n');
        }
        logInfo.setText(log.length() == 0 ? "—" : log.toString());
    }

    static String statusAr(AttemptStore.Attempt a) {
        switch (a.status) {
            case AttemptStore.S_SENDING: return "جارٍ الإرسال";
            case AttemptStore.S_SUBMITTED: return "قُبلت";
            case AttemptStore.S_DELIVERED: return "سُلّمت";
            case AttemptStore.S_DELIVERY_FAILED: return "فشل التسليم";
            case AttemptStore.S_DELIVERY_UNKNOWN: return "تسليم غير مؤكد";
            case AttemptStore.S_TIMEOUT: return "انقضت المهلة";
            case AttemptStore.S_FAILED: return "فشلت";
            default: return a.status;
        }
    }

    // ---------------------------------------------------------------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EXPORT && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os != null) {
                    os.write(store.toCsv().getBytes(StandardCharsets.UTF_8));
                    Toast.makeText(this, "صُدّرت النتائج إلى CSV", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "تعذّر التصدير: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshSignal();
    }

    @Override
    protected void onPause() {
        saveInputs();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        engine.unregister();
        super.onDestroy();
    }
}
