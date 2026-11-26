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
import android.view.View;
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
    private Button clearBtn;

    private boolean seriesActive;
    private int seriesLeft;
    private boolean seriesScheduled;
    private boolean autoRepeat;
    private int seriesRoundCount;
    private boolean seriesSubmitted;
    private int roundNo;

    private TextView dhikrText;
    private Button dhikrToggle;
    private boolean dhikrOn = true;
    private boolean dhikrForeground;
    private int dhikrIdx;
    private GlowBorderDrawable dhikrGlow;
    private GlowBorderDrawable toggleGlow;
    private android.animation.ValueAnimator glowAnimator;
    private View verseBox;
    private TextView verseText;
    private android.text.SpannableString verseSpannable;
    private Object bracketSpanStart;
    private Object bracketSpanEnd;
    private boolean verseShown;
    private boolean pulseRunning;
    private Runnable versePulse;

    private Button seriesBtn;
    private Button seriesStopBtn;

    // Active location (1.18): the app asks for its own fixes.
    private TextView locStatus;
    private Button locEnableBtn;
    private boolean locDialogTried;
    private android.location.LocationManager locMgr;
    private android.location.LocationListener locListener;
    private boolean awaitingFix;
    private int pendingSeriesCount;
    private Runnable fixTimeout;

    private final Runnable signalTick = new Runnable() {
        @Override public void run() {
            refreshSignal();
            refreshLocStatus();
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
        clearBtn = findViewById(R.id.clearBtn);
        locStatus = findViewById(R.id.locStatus);
        locEnableBtn = findViewById(R.id.locEnableBtn);
        locEnableBtn.setOnClickListener(v -> {
            if (!hasLocPermission()) {
                if (!locDialogTried) {
                    // First press: the quick grant dialog.
                    locDialogTried = true;
                    requestPermissions(new String[]{
                            Manifest.permission.SEND_SMS,
                            Manifest.permission.READ_PHONE_STATE,
                            Manifest.permission.ACCESS_FINE_LOCATION}, REQ_PERM);
                } else {
                    // The dialog is being suppressed (denied for
                    // good): the certain road is the app's own
                    // settings page, permissions row.
                    try {
                        Intent it = new Intent(
                                android.provider.Settings
                                        .ACTION_APPLICATION_DETAILS_SETTINGS);
                        it.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(it);
                    } catch (Throwable ignored) {}
                }
            } else {
                // Permission is fine; the phone's location switch
                // itself is off — open its own page.
                try {
                    startActivity(new Intent(
                            android.provider.Settings
                                    .ACTION_LOCATION_SOURCE_SETTINGS));
                } catch (Throwable ignored) {}
            }
        });

        engine.setListener(a -> {
            refreshStatsAndLog();
            if (AttemptStore.S_SUBMITTED.equals(a.status)) {
                statusInfo.setText("قُبلت الرسالة عند مركز الرسائل — بانتظار تقرير التسليم");
                beep(ToneGenerator.TONE_PROP_BEEP, 140);
                onSeriesAttemptResolved(a.status);
            } else if (AttemptStore.S_DELIVERED.equals(a.status)) {
                statusInfo.setText("سُلّمت الرسالة إلى الهاتف الآخر ✓");
                beep(ToneGenerator.TONE_PROP_ACK, 220);
            } else if (AttemptStore.S_FAILED.equals(a.status)) {
                statusInfo.setText("فشل الإرسال: " + a.error);
                beep(ToneGenerator.TONE_PROP_NACK, 260);
                onSeriesAttemptResolved(a.status);
            } else if (AttemptStore.S_TIMEOUT.equals(a.status)) {
                statusInfo.setText("انقضت مهلة نتيجة الإرسال (النتيجة غير معروفة)");
                beep(ToneGenerator.TONE_PROP_NACK, 260);
                onSeriesAttemptResolved(a.status);
            }
        });
        engine.register();

        // Restore the last number only. The message box opens EMPTY
        // every time by the owner's design (1.6): each message is
        // written fresh and leaves with its time — and location —
        // stamped automatically.
        SharedPreferences ui = getSharedPreferences("smslab_ui", MODE_PRIVATE);
        phoneInput.setText(ui.getString("last_phone", ""));

        CheckBox attachLoc = findViewById(R.id.attachLocCheck);
        attachLoc.setChecked(ui.getBoolean("attach_loc", true));
        engine.setAttachLoc(attachLoc.isChecked());
        attachLoc.setOnCheckedChangeListener((v, checked) -> {
            engine.setAttachLoc(checked);
            getSharedPreferences("smslab_ui", MODE_PRIVATE)
                    .edit().putBoolean("attach_loc", checked).apply();
        });

        messageInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                updateCharInfo(s == null ? "" : s.toString());
            }
        });
        updateCharInfo(messageInput.getText().toString());

        CheckBox repeatCheck = findViewById(R.id.autoRepeatCheck);
        autoRepeat = ui.getBoolean("auto_repeat", false);
        repeatCheck.setChecked(autoRepeat);
        repeatCheck.setOnCheckedChangeListener((v, checked) -> {
            autoRepeat = checked;
            getSharedPreferences("smslab_ui", MODE_PRIVATE)
                    .edit().putBoolean("auto_repeat", checked).apply();
        });

        initDhikrRunnables();
        dhikrText = findViewById(R.id.dhikrText);
        dhikrToggle = findViewById(R.id.dhikrToggle);

        // Flowing light threads (1.13): sunset around the dhikr
        // frame, green-white around its stop button — one clock
        // drives both and rests in the background.
        float density = getResources().getDisplayMetrics().density;
        dhikrGlow = new GlowBorderDrawable(0xFF101E33,
                GlowBorderDrawable.SUNSET, 12f, 2f, 1f, density);
        findViewById(R.id.dhikrFrame).setBackground(dhikrGlow);
        toggleGlow = new GlowBorderDrawable(0xFF141C2C,
                GlowBorderDrawable.GREEN_WHITE, 10f, 1.6f, -0.7f, density);
        dhikrToggle.setBackground(toggleGlow);
        glowAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f);
        glowAnimator.setDuration(7000L);
        glowAnimator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        glowAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        glowAnimator.addUpdateListener(an -> {
            float p = (float) an.getAnimatedValue();
            dhikrGlow.setPhase(p);
            toggleGlow.setPhase(p);
        });
        glowAnimator.start();
        dhikrOn = ui.getBoolean("dhikr_on", true);
        dhikrToggle.setText(dhikrOn ? "إيقاف الذكر" : "تشغيل الذكر");
        dhikrToggle.setOnClickListener(v -> {
            if (dhikrOn) stopDhikr(); else startDhikr();
        });
        verseBox = findViewById(R.id.verseBox);
        verseText = findViewById(R.id.verseText);
        ((TextView) findViewById(R.id.verseRef)).setText(DhikrCycle.VERSE_REF);
        buildVerseText();
        initVersePulse();
        if (!dhikrOn) showVerse(false);

        seriesBtn = findViewById(R.id.seriesBtn);
        seriesStopBtn = findViewById(R.id.seriesStopBtn);
        setSeriesButtons(false);
        seriesBtn.setOnClickListener(v -> {
            if (!ensureSmsPermission()) return;
            int n;
            try { n = Integer.parseInt(seriesCount.getText().toString().trim()); }
            catch (Exception e) { n = 10; }
            if (n < 1) n = 1; if (n > 50) n = 50;
            beginSeries(n);
        });
        seriesStopBtn.setOnClickListener(v -> {
            seriesActive = false;
            awaitingFix = false;
            if (fixTimeout != null) handler.removeCallbacks(fixTimeout);
            setSeriesButtons(false);
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
        // Only the number is remembered (1.6) — the message box
        // opens empty every time.
        getSharedPreferences("smslab_ui", MODE_PRIVATE).edit()
                .putString("last_phone", phoneInput.getText().toString())
                .apply();
    }

    private void beep(int tone, int ms) {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
            tg.startTone(tone, ms);
            handler.postDelayed(tg::release, ms + 120);
        } catch (Throwable ignored) {}
    }

    private void updateCharInfo(String t) {
        charInfo.setText("الأحرف: " + t.length()
                + " • الترميز: " + GsmUtil.encodingName(t)
                + " • الأجزاء: " + Math.max(1, GsmUtil.segments(t)));
    }

    // ---------------------------------------------------------------
    // Dhikr bar: an endless rotation in the top bar — each phrase
    // shines 5 s and the next follows immediately (owner's field
    // revision, 1.12). Optional stop button under the frame; the
    // choice is remembered; the loop rests in the background.
    // ---------------------------------------------------------------

    // Initialized in onCreate (the two runnables refer to each
    // other, which field initializers cannot express).
    private Runnable dhikrShow;
    private Runnable dhikrSwap;

    private void initDhikrRunnables() {
        dhikrShow = () -> {
            if (!dhikrOn || !dhikrForeground || dhikrText == null) return;
            dhikrText.setText(DhikrCycle.PHRASES[dhikrIdx]);
            dhikrText.animate().alpha(1f).setDuration(DhikrCycle.FADE_MS).start();
            handler.postDelayed(dhikrSwap, DhikrCycle.SHOW_MS);
        };
        dhikrSwap = () -> {
            if (dhikrText == null) return;
            dhikrText.animate().alpha(0f).setDuration(DhikrCycle.FADE_MS)
                    .withEndAction(() -> {
                        dhikrIdx = DhikrCycle.next(dhikrIdx);
                        if (dhikrOn && dhikrForeground) handler.post(dhikrShow);
                    }).start();
        };
    }

    private void startDhikr() {
        dhikrOn = true;
        getSharedPreferences("smslab_ui", MODE_PRIVATE)
                .edit().putBoolean("dhikr_on", true).apply();
        dhikrToggle.setText("إيقاف الذكر");
        verseShown = false;
        stopVersePulse();
        if (verseBox != null) verseBox.setVisibility(View.GONE);
        if (dhikrText != null) dhikrText.setVisibility(View.VISIBLE);
        dhikrIdx = 0;
        handler.removeCallbacks(dhikrShow);
        handler.removeCallbacks(dhikrSwap);
        handler.post(dhikrShow);
    }

    private void stopDhikr() {
        dhikrOn = false;
        getSharedPreferences("smslab_ui", MODE_PRIVATE)
                .edit().putBoolean("dhikr_on", false).apply();
        dhikrToggle.setText("تشغيل الذكر");
        handler.removeCallbacks(dhikrShow);
        handler.removeCallbacks(dhikrSwap);
        showVerse(true);
    }

    /**
     * The verse text with its ornate brackets INSIDE the text
     * (owner's correction, 1.17): ﴿ stands immediately before
     * «إِنَّ» at the verse's head and ﴾ immediately after
     * «تَسْلِيمًا» at its tail, exactly as the mushaf prints
     * them. The brackets are enlarged by a size span and coloured
     * by two colour spans the pulse rewrites as they breathe.
     */
    private void buildVerseText() {
        String full = "﴿" + DhikrCycle.VERSE + "﴾";
        verseSpannable = new android.text.SpannableString(full);
        int last = full.length() - 1;
        verseSpannable.setSpan(new android.text.style.RelativeSizeSpan(1.45f),
                0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        verseSpannable.setSpan(new android.text.style.RelativeSizeSpan(1.45f),
                last, full.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        paintBrackets(0xFFFFFFFF);
        verseText.setText(verseSpannable);
    }

    private void paintBrackets(int color) {
        if (bracketSpanStart != null) verseSpannable.removeSpan(bracketSpanStart);
        if (bracketSpanEnd != null) verseSpannable.removeSpan(bracketSpanEnd);
        bracketSpanStart = new android.text.style.ForegroundColorSpan(color);
        bracketSpanEnd = new android.text.style.ForegroundColorSpan(color);
        int last = verseSpannable.length() - 1;
        verseSpannable.setSpan(bracketSpanStart, 0, 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        verseSpannable.setSpan(bracketSpanEnd, last, verseSpannable.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /**
     * The brackets' breathing runs on its OWN clock (1.17): a
     * 2.8 s cycle — white melts to green, rests green a full
     * third, melts back — repainted every 120 ms while the verse
     * is shown. (In 1.16 the pulse rode the frame's 7 s clock
     * through separate side views and the owner saw no change.)
     */
    private void initVersePulse() {
        versePulse = () -> {
            if (!verseShown || verseText == null) {
                pulseRunning = false;
                return;
            }
            float t = GlowBorderDrawable.phase(
                    android.os.SystemClock.elapsedRealtime(), 2800L);
            paintBrackets(GlowBorderDrawable.colorAt(
                    GlowBorderDrawable.WHITE_GREEN, t));
            verseText.setText(verseSpannable);
            handler.postDelayed(versePulse, 120);
        };
    }

    private void startVersePulse() {
        if (pulseRunning || versePulse == null) return;
        pulseRunning = true;
        handler.post(versePulse);
    }

    private void stopVersePulse() {
        pulseRunning = false;
        if (versePulse != null) handler.removeCallbacks(versePulse);
    }

    /**
     * While the dhikr rests, the frame carries the noble verse —
     * snow-white with a yellow glow, standing still until the
     * dhikr is started again (owner, 1.15).
     */
    private void showVerse(boolean animate) {
        verseShown = true;
        if (dhikrText != null) {
            dhikrText.animate().cancel();
            dhikrText.setVisibility(View.GONE);
        }
        if (verseBox != null) {
            verseBox.setVisibility(View.VISIBLE);
            if (animate) {
                verseBox.setAlpha(0f);
                verseBox.animate().alpha(1f).setDuration(450).start();
            } else {
                verseBox.setAlpha(1f);
            }
        }
        startVersePulse();
    }

    // ---------------------------------------------------------------
    // Active location (1.18) — the radical fix for "time only".
    //
    // Through 1.17 the app only READ the system's last-known cache
    // and never asked for a position of its own; on a phone where
    // no other app had just requested location, every message left
    // time-only. Now: (a) while the app is in the foreground it
    // tracks location itself and remembers every fix it obtains;
    // (b) a series pressed with no usable fix first DEMANDS one,
    // waiting at most 12 s before the first attempt leaves;
    // (c) a permanent status line says, before any send, whether
    // the next message will carry a location and how fresh it is.
    // ---------------------------------------------------------------

    private void startLocTracking() {
        try {
            if (!hasLocPermission()) {
                refreshLocStatus();
                return;
            }
            if (locMgr == null) {
                locMgr = (android.location.LocationManager)
                        getSystemService(LOCATION_SERVICE);
            }
            if (locMgr == null) return;
            if (locListener == null) {
                locListener = new android.location.LocationListener() {
                    @Override public void onLocationChanged(
                            android.location.Location l) {
                        onFreshFix(l);
                    }
                    @Override public void onProviderEnabled(String p) {}
                    @Override public void onProviderDisabled(String p) {}
                    @Override public void onStatusChanged(
                            String p, int s, Bundle b) {}
                };
            }
            if (hasFinePermission()) {
                try {
                    locMgr.requestLocationUpdates(
                            android.location.LocationManager.GPS_PROVIDER,
                            5000L, 0f, locListener, Looper.getMainLooper());
                } catch (Throwable ignored) {}
            }
            try {
                locMgr.requestLocationUpdates(
                        android.location.LocationManager.NETWORK_PROVIDER,
                        5000L, 0f, locListener, Looper.getMainLooper());
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        refreshLocStatus();
    }

    private void stopLocTracking() {
        try {
            if (locMgr != null && locListener != null) {
                locMgr.removeUpdates(locListener);
            }
        } catch (Throwable ignored) {}
    }

    private void onFreshFix(android.location.Location l) {
        if (l == null) return;
        LocUtil.saveFix(this, l);
        refreshLocStatus();
        if (awaitingFix) {
            awaitingFix = false;
            if (fixTimeout != null) handler.removeCallbacks(fixTimeout);
            startSeriesNow(pendingSeriesCount);
        }
    }

    private void requestSingleFix() {
        if (locMgr == null) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                java.util.concurrent.Executor ex = getMainExecutor();
                java.util.function.Consumer<android.location.Location> c =
                        this::onFreshFix;
                if (hasFinePermission()) {
                    try {
                        locMgr.getCurrentLocation(
                                android.location.LocationManager.GPS_PROVIDER,
                                null, ex, c);
                    } catch (Throwable ignored) {}
                }
                try {
                    locMgr.getCurrentLocation(
                            android.location.LocationManager.NETWORK_PROVIDER,
                            null, ex, c);
                } catch (Throwable ignored) {}
                try {
                    locMgr.getCurrentLocation(
                            android.location.LocationManager.PASSIVE_PROVIDER,
                            null, ex, c);
                } catch (Throwable ignored) {}
            } else if (locListener != null) {
                if (hasFinePermission()) {
                    try {
                        locMgr.requestSingleUpdate(
                                android.location.LocationManager.GPS_PROVIDER,
                                locListener, Looper.getMainLooper());
                    } catch (Throwable ignored) {}
                }
                try {
                    locMgr.requestSingleUpdate(
                            android.location.LocationManager.NETWORK_PROVIDER,
                            locListener, Looper.getMainLooper());
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private boolean hasLocPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasFinePermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isSystemLocationOn() {
        try {
            if (locMgr == null) {
                locMgr = (android.location.LocationManager)
                        getSystemService(LOCATION_SERVICE);
            }
            if (locMgr == null) return false;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                return locMgr.isLocationEnabled();
            }
            return locMgr.isProviderEnabled(
                            android.location.LocationManager.GPS_PROVIDER)
                    || locMgr.isProviderEnabled(
                            android.location.LocationManager.NETWORK_PROVIDER);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The status line tells the TRUTH, in order (1.20): attach
     * off? permission off? no fix? stale fix (named stale, with
     * its age — never dressed as «جاهز»)? only then: ready.
     *
     * And while location is OFF — by permission or by the phone's
     * own switch — a «تشغيل الموقع» button stands beside the
     * warning (owner, 1.21); the moment location works, both the
     * button and the warning are gone.
     */
    private void refreshLocStatus() {
        if (locStatus == null || engine == null) return;
        if (!engine.isAttachLoc()) {
            locStatus.setText("الموقع: الإرفاق مغلق من الخيار أعلاه");
            if (locEnableBtn != null) locEnableBtn.setVisibility(View.GONE);
            return;
        }
        if (!hasLocPermission()) {
            locStatus.setText("الموقع: إذن الموقع غير مفعّل — فعّله ليُرفق الموقع في الرسالة");
            if (locEnableBtn != null) locEnableBtn.setVisibility(View.VISIBLE);
            return;
        }
        if (!isSystemLocationOn()) {
            locStatus.setText("الموقع: متوقف في الهاتف — شغّله ليُرفق الموقع في الرسالة");
            if (locEnableBtn != null) locEnableBtn.setVisibility(View.VISIBLE);
            return;
        }
        if (locEnableBtn != null) locEnableBtn.setVisibility(View.GONE);
        double[] fix = LocUtil.cachedFix(this);
        if (fix == null) {
            locStatus.setText("الموقع: لا إصلاح بعد — إن بدأت الآن خرجت الرسالة بالوقت فقط");
            return;
        }
        long now = System.currentTimeMillis();
        long ageMs = now - (long) fix[2];
        if (!FixCache.usable((long) fix[2], now)) {
            locStatus.setText("الموقع: آخر إصلاح قبل " + (ageMs / 60000L)
                    + " د — قديم ولن يُرفق؛ جارٍ طلب إصلاح جديد…");
            return;
        }
        long ageSec = Math.max(0, ageMs / 1000L);
        String age = ageSec < 2 ? "الآن" : ("قبل " + ageSec + " ث");
        locStatus.setText("الموقع جاهز: " + LocUtil.fmt(fix[0], fix[1])
                + " — " + age);
    }

    /**
     * The series buttons wear the state (owner, 1.19): whichever
     * button no longer applies stands DIMMED — at rest the stop
     * button is dimmed, while a series runs the start button is,
     * so the eye reads the state before the finger moves.
     */
    private void setSeriesButtons(boolean running) {
        if (seriesBtn == null || seriesStopBtn == null) return;
        seriesBtn.setAlpha(running ? 0.45f : 1f);
        seriesStopBtn.setAlpha(running ? 1f : 0.45f);
    }

    private void beginSeries(int n) {
        // The message is written; put the pen down (owner, 1.20):
        // focus leaves the box, the cursor hides, the keyboard goes.
        messageInput.clearFocus();
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(messageInput.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {}
        // No location permission, no located series (1.20): ask
        // for it here and now instead of silently sending bare.
        if (engine.isAttachLoc() && !hasLocPermission()) {
            statusInfo.setText("يلزم إذن الموقع لإرفاق الإحداثيات — فعّله ثم أعد بدء السلسلة");
            refreshLocStatus();
            requestPermissions(new String[]{
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.ACCESS_FINE_LOCATION}, REQ_PERM);
            return;
        }
        setSeriesButtons(true);
        if (!engine.isAttachLoc() || LocUtil.lastKnown(this) != null) {
            startSeriesNow(n);
            return;
        }
        pendingSeriesCount = n;
        awaitingFix = true;
        statusInfo.setText("جارٍ تحديد الموقع قبل بدء السلسلة… (حتى 12 ثانية)");
        refreshLocStatus();
        requestSingleFix();
        fixTimeout = () -> {
            if (!awaitingFix) return;
            awaitingFix = false;
            statusInfo.setText("تعذّر تحديد الموقع — تبدأ السلسلة بالوقت فقط");
            startSeriesNow(pendingSeriesCount);
        };
        handler.postDelayed(fixTimeout, 12000);
    }

    private void startSeriesNow(int n) {
        seriesActive = true;
        seriesLeft = n;
        seriesRoundCount = n;
        seriesSubmitted = false;
        roundNo = 1;
        statusInfo.setText("بدأت سلسلة من " + n + " محاولات — فاصل 15 ثانية");
        trySeriesSend();
    }

    // ---------------------------------------------------------------
    // Series: strictly sequential; the next attempt is scheduled only
    // after the current one resolves, with a fixed 15 s gap.
    // ---------------------------------------------------------------

    private void trySeriesSend() {
        if (!seriesActive) return;
        if (seriesLeft <= 0) {
            // Auto-repeat (1.24): a round with zero submissions is
            // followed by a fresh round on its own — the owner does
            // not re-press start while the service is simply absent.
            if (SeriesPolicy.shouldRepeatRound(autoRepeat, seriesSubmitted, roundNo)) {
                seriesLeft = seriesRoundCount;
                seriesSubmitted = false;
                roundNo++;
                statusInfo.setText("لا قبول بعد — تبدأ الجولة " + roundNo + " تلقائيًا…");
                handler.postDelayed(this::trySeriesSend, SERIES_GAP_MS);
                return;
            }
            seriesActive = false;
            setSeriesButtons(false);
            if (autoRepeat && !seriesSubmitted) {
                // The ceiling (1.26): announced, never silent.
                statusInfo.setText("توقفت: بلغت الحد الأقصى (" + SeriesPolicy.MAX_ROUNDS + " جولة) بلا قبول");
            } else {
                statusInfo.setText("اكتملت السلسلة ✓");
            }
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
            setSeriesButtons(false);
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

    private void onSeriesAttemptResolved(String status) {
        if (!seriesActive) return;
        if ("SUBMITTED".equals(status)) seriesSubmitted = true;
        // One toggle carries the whole reach promise (owner, 1.25):
        // auto-repeat until first success also STOPS at that success
        // the moment it lands — the separate stop-on-success box
        // was retired as redundant.
        if (SeriesPolicy.shouldStop(autoRepeat, status)) {
            seriesActive = false;
            setSeriesButtons(false);
            statusInfo.setText("توقفت السلسلة: قُبلت أول رسالة — وصلت ✓");
            return;
        }
        if (seriesScheduled) return;
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
        startLocTracking();
    }

    @Override
    protected void onPause() {
        saveInputs();
        dhikrForeground = false;
        handler.removeCallbacks(dhikrShow);
        handler.removeCallbacks(dhikrSwap);
        stopVersePulse();
        stopLocTracking();
        awaitingFix = false;
        if (fixTimeout != null) handler.removeCallbacks(fixTimeout);
        if (glowAnimator != null) glowAnimator.cancel();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        dhikrForeground = true;
        if (glowAnimator != null && !glowAnimator.isRunning()) glowAnimator.start();
        if (verseShown) startVersePulse();
        startLocTracking();
        if (dhikrOn && dhikrText != null) {
            handler.removeCallbacks(dhikrShow);
            handler.removeCallbacks(dhikrSwap);
            handler.post(dhikrShow);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        engine.unregister();
        super.onDestroy();
    }
}
