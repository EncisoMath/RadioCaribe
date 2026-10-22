package com.radiocaribe.tuner;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String PREFS = "radio_caribe";
    private static final String KEY_INDEX = "last_station";
    private static final String KEY_DARK = "dark_theme";

    private static final int BLUE = Color.rgb(18, 139, 255);
    private static final int CYAN = Color.rgb(0, 207, 235);
    private static final int LIGHT_BG = Color.rgb(247, 250, 253);
    private static final int LIGHT_TEXT = Color.rgb(9, 31, 58);
    private static final int DARK_BG = Color.rgb(4, 14, 28);
    private static final int DARK_TEXT = Color.WHITE;

    private static class Station {
        final String shortName;
        final String name;
        final String frequency;
        final String source;
        final String url;

        Station(String shortName, String name, String frequency, String source, String url) {
            this.shortName = shortName;
            this.name = name;
            this.frequency = frequency;
            this.source = source;
            this.url = url;
        }
    }

    private final Station[] stations = new Station[] {
        new Station("RT", "RADIO TIEMPO BARRANQUILLA", "96.1 FM", "ONLINE", "https://playerservices.streamtheworld.com/api/livestream-redirect/RT_BARRANQUILLAAAC.aac"),
        new Station("OL", "OLÍMPICA BARRANQUILLA", "92.1 FM", "ONLINE", "https://playerservices.streamtheworld.com/api/livestream-redirect/OLP_BARRANQUILLAAAC_SC"),
        new Station("LR", "LA REINA BARRANQUILLA", "98.6 FM", "ONLINE", "https://playerservices.streamtheworld.com/api/livestream-redirect/RNA_BARRANQUILLA_SC"),
        new Station("MX", "MIX RADIO BARRANQUILLA", "103.9 FM", "ONLINE", "https://playerservices.streamtheworld.com/api/livestream-redirect/MIX_BARRANQUILLAAAC.aac"),
        new Station("CA", "CANDENTE STEREO", "FUNDACIÓN", "ONLINE", "https://stream.zeno.fm/nizmbxnuo53uv"),
        new Station("CY", "CAYENA STEREO", "FUNDACIÓN", "ONLINE", "https://server1.intermediacolombia.com:8112/;"),
        new Station("FT", "FORTALEZA", "102.9 FM", "ONLINE", "https://streaming.radiosenlinea.com.ar/8830/stream"),
        new Station("OL", "OLÍMPICA SANTA MARTA", "97.1 FM", "ONLINE", "https://playerservices.streamtheworld.com/api/livestream-redirect/OLP_SANTA_MARTAAAC.aac"),
        new Station("ES", "EL SOL SANTA MARTA", "106.9 FM", "ONLINE", "https://mdstrm.com/audio/632ce8986d2e8108b23d249f/live.m3u8"),
        new Station("FG", "FUEGO STEREO", "101.9 FM", "ONLINE", "http://real2.streaming-co.com:8747/stream/")
    };

    private SharedPreferences prefs;
    private MediaPlayer player;
    private AudioManager audioManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int stationIndex = 0;
    private boolean darkMode = false;
    private boolean shouldPlay = true;
    private boolean preparing = false;

    private FrameLayout root;
    private TextView clockView;
    private TextView themeView;
    private WifiView wifiView;
    private StationBadgeView badgeView;
    private TextView stationNameView;
    private TextView sourceView;
    private TextView frequencyView;
    private RoundButton previousButton;
    private RoundButton playButton;
    private RoundButton nextButton;

    private final Runnable clockTicker = new Runnable() {
        @Override public void run() {
            updateClock();
            long delay = 60000L - (System.currentTimeMillis() % 60000L);
            handler.postDelayed(this, delay);
        }
    };

    private final Runnable retryRunnable = new Runnable() {
        @Override public void run() {
            if (shouldPlay && !isFinishing()) playCurrentStation(false);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        stationIndex = Math.max(0, Math.min(stations.length - 1, prefs.getInt(KEY_INDEX, 0)));
        darkMode = prefs.getBoolean(KEY_DARK, false);

        buildUi();
        enterImmersiveMode();
        clockTicker.run();
        playCurrentStation(false);
    }

    @Override protected void onResume() {
        super.onResume();
        enterImmersiveMode();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersiveMode();
    }

    private void buildUi() {
        root = new FrameLayout(this);
        setContentView(root);

        themeView = makeText("☾", 27, Typeface.BOLD);
        themeView.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams themeLp = new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.TOP | Gravity.LEFT);
        themeLp.leftMargin = dp(26);
        themeLp.topMargin = dp(18);
        root.addView(themeView, themeLp);
        themeView.setOnClickListener(v -> toggleTheme());

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setOrientation(LinearLayout.HORIZONTAL);
        clockRow.setGravity(Gravity.CENTER_VERTICAL);
        wifiView = new WifiView(this);
        clockRow.addView(wifiView, new LinearLayout.LayoutParams(dp(56), dp(44)));
        clockView = makeText("00:00", 35, Typeface.BOLD);
        LinearLayout.LayoutParams clockTextLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(54));
        clockTextLp.leftMargin = dp(10);
        clockRow.addView(clockView, clockTextLp);
        FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(64), Gravity.TOP | Gravity.RIGHT);
        clockLp.rightMargin = dp(30);
        clockLp.topMargin = dp(13);
        root.addView(clockRow, clockLp);

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams centerLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        centerLp.leftMargin = dp(72);
        centerLp.rightMargin = dp(72);
        centerLp.topMargin = dp(22);
        centerLp.bottomMargin = dp(24);
        root.addView(center, centerLp);

        badgeView = new StationBadgeView(this);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(205), dp(205));
        badgeLp.topMargin = dp(6);
        center.addView(badgeView, badgeLp);

        stationNameView = makeText("", 36, Typeface.BOLD);
        stationNameView.setGravity(Gravity.CENTER);
        stationNameView.setMaxLines(1);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62));
        nameLp.topMargin = dp(2);
        center.addView(stationNameView, nameLp);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        metaLp.topMargin = dp(2);
        center.addView(meta, metaLp);

        sourceView = makeText("●  ONLINE", 20, Typeface.BOLD);
        sourceView.setGravity(Gravity.CENTER);
        sourceView.setPadding(dp(20), 0, dp(20), 0);
        meta.addView(sourceView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));

        TextView divider = makeText("│", 24, Typeface.NORMAL);
        divider.setAlpha(0.45f);
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(dp(36), dp(42));
        divider.setGravity(Gravity.CENTER);
        meta.addView(divider, dividerLp);

        frequencyView = makeText("", 22, Typeface.BOLD);
        frequencyView.setGravity(Gravity.CENTER);
        meta.addView(frequencyView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams controlsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(142));
        controlsLp.topMargin = dp(5);
        center.addView(controls, controlsLp);

        previousButton = new RoundButton(this, "◀◀", false);
        playButton = new RoundButton(this, "❚❚", true);
        nextButton = new RoundButton(this, "▶▶", false);

        LinearLayout.LayoutParams sideLp = new LinearLayout.LayoutParams(dp(120), dp(120));
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(dp(140), dp(140));
        playLp.leftMargin = dp(62);
        playLp.rightMargin = dp(62);
        controls.addView(previousButton, sideLp);
        controls.addView(playButton, playLp);
        controls.addView(nextButton, sideLp);

        previousButton.setOnClickListener(v -> changeStation(-1));
        nextButton.setOnClickListener(v -> changeStation(1));
        playButton.setOnClickListener(v -> togglePlayback());

        applyTheme();
        renderStation();
    }

    private void renderStation() {
        Station s = stations[stationIndex];
        stationNameView.setText(s.name);
        sourceView.setText("●  " + s.source);
        frequencyView.setText(s.frequency);
        badgeView.setStation(s.shortName);
        prefs.edit().putInt(KEY_INDEX, stationIndex).apply();
    }

    private void changeStation(int direction) {
        stationIndex = (stationIndex + direction + stations.length) % stations.length;
        renderStation();
        shouldPlay = true;
        playCurrentStation(false);
    }

    private void togglePlayback() {
        if (player != null && !preparing) {
            boolean playingNow = false;
            try { playingNow = player.isPlaying(); } catch (IllegalStateException ignored) { }
            if (playingNow) {
            player.pause();
            shouldPlay = false;
            updatePlayButton(false);
            return;
            }
        }
        shouldPlay = true;
        if (player != null && !preparing) {
            try {
                player.start();
                updatePlayButton(true);
                return;
            } catch (IllegalStateException ignored) { }
        }
        playCurrentStation(false);
    }

    private void playCurrentStation(boolean isRetry) {
        handler.removeCallbacks(retryRunnable);
        releasePlayer();

        if (!isNetworkAvailable()) {
            preparing = false;
            updatePlayButton(false);
            sourceView.setText("●  SIN RED");
            handler.postDelayed(retryRunnable, 5000L);
            return;
        }

        Station station = stations[stationIndex];
        preparing = true;
        sourceView.setText("●  CONECTANDO");
        updatePlayButton(true);

        MediaPlayer p = new MediaPlayer();
        player = p;
        p.setAudioStreamType(AudioManager.STREAM_MUSIC);
        p.setOnPreparedListener(mp -> {
            if (mp != player) return;
            preparing = false;
            sourceView.setText("●  " + station.source);
            if (shouldPlay) {
                mp.start();
                updatePlayButton(true);
            } else {
                updatePlayButton(false);
            }
        });
        p.setOnErrorListener((mp, what, extra) -> {
            if (mp != player) return true;
            preparing = false;
            sourceView.setText("●  REINTENTANDO");
            updatePlayButton(false);
            if (shouldPlay) handler.postDelayed(retryRunnable, isRetry ? 8000L : 4500L);
            return true;
        });
        p.setOnCompletionListener(mp -> {
            if (shouldPlay) handler.postDelayed(retryRunnable, 1500L);
        });

        try {
            requestAudioFocus();
            p.setDataSource(station.url);
            p.prepareAsync();
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            preparing = false;
            sourceView.setText("●  ERROR");
            updatePlayButton(false);
            if (shouldPlay) handler.postDelayed(retryRunnable, 5000L);
        }
    }

    private void releasePlayer() {
        MediaPlayer old = player;
        player = null;
        preparing = false;
        if (old != null) {
            try { old.reset(); } catch (Exception ignored) { }
            try { old.release(); } catch (Exception ignored) { }
        }
    }

    private void requestAudioFocus() {
        if (audioManager != null) {
            audioManager.requestAudioFocus(focusChange -> {
                if (focusChange < 0 && player != null && !preparing && player.isPlaying()) {
                    player.pause();
                    updatePlayButton(false);
                } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN && shouldPlay && player != null && !preparing) {
                    try { player.start(); updatePlayButton(true); } catch (Exception ignored) { }
                }
            }, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private void toggleTheme() {
        darkMode = !darkMode;
        prefs.edit().putBoolean(KEY_DARK, darkMode).apply();
        applyTheme();
    }

    private void applyTheme() {
        int bg = darkMode ? DARK_BG : LIGHT_BG;
        int text = darkMode ? DARK_TEXT : LIGHT_TEXT;
        int secondary = darkMode ? Color.rgb(205, 220, 239) : Color.rgb(91, 111, 139);

        root.setBackgroundColor(bg);
        clockView.setTextColor(text);
        stationNameView.setTextColor(text);
        frequencyView.setTextColor(secondary);
        themeView.setTextColor(text);
        themeView.setText(darkMode ? "☀" : "☾");

        GradientDrawable themeBg = new GradientDrawable();
        themeBg.setShape(GradientDrawable.OVAL);
        themeBg.setColor(darkMode ? Color.rgb(14, 32, 54) : Color.WHITE);
        themeBg.setStroke(dp(1), darkMode ? Color.rgb(35, 63, 93) : Color.rgb(226, 234, 244));
        themeView.setBackground(themeBg);

        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(24));
        pill.setColor(darkMode ? Color.rgb(4, 45, 92) : Color.rgb(219, 239, 255));
        sourceView.setBackground(pill);
        sourceView.setTextColor(darkMode ? Color.rgb(64, 205, 255) : Color.rgb(0, 104, 222));

        badgeView.setDark(darkMode);
        previousButton.applyTheme(darkMode);
        playButton.applyTheme(darkMode);
        nextButton.applyTheme(darkMode);
        if (wifiView != null) wifiView.invalidate();

        getWindow().setNavigationBarColor(bg);
        getWindow().setStatusBarColor(bg);
        if (!darkMode) {
            getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private void updatePlayButton(boolean playing) {
        if (playButton != null) playButton.setText(playing ? "❚❚" : "▶");
    }

    private void updateClock() {
        if (clockView != null) {
            clockView.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
        }
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private TextView makeText(String text, float sp, int style) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setTypeface(Typeface.create("sans", style));
        view.setIncludeFontPadding(false);
        return view;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private class RoundButton extends TextView {
        private final boolean primary;
        RoundButton(Context context, String text, boolean primary) {
            super(context);
            this.primary = primary;
            setText(text);
            setGravity(Gravity.CENTER);
            setTextSize(primary ? 35 : 28);
            setTypeface(Typeface.DEFAULT_BOLD);
            setIncludeFontPadding(false);
            setElevation(dp(primary ? 10 : 7));
            setClickable(true);
            setFocusable(true);
        }

        void applyTheme(boolean dark) {
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            if (primary) {
                gd.setColor(BLUE);
                gd.setStroke(dp(3), dark ? Color.rgb(27, 200, 255) : Color.rgb(72, 184, 255));
                setTextColor(Color.WHITE);
            } else {
                gd.setColor(dark ? Color.rgb(10, 27, 47) : Color.WHITE);
                gd.setStroke(dp(2), dark ? Color.rgb(44, 71, 101) : Color.rgb(226, 234, 243));
                setTextColor(dark ? Color.WHITE : LIGHT_TEXT);
            }
            setBackground(gd);
        }
    }

    private class StationBadgeView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private String initials = "RC";
        private boolean dark;

        StationBadgeView(Context context) { super(context); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }
        void setStation(String value) { initials = value; invalidate(); }
        void setDark(boolean value) { dark = value; invalidate(); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float radius = Math.min(w, h) * 0.43f;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(dark ? Color.rgb(5, 22, 42) : Color.WHITE);
            paint.setShadowLayer(dp(16), 0, dp(7), dark ? Color.argb(150, 0, 137, 255) : Color.argb(55, 14, 128, 240));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.clearShadowLayer();

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(5));
            paint.setColor(dark ? CYAN : Color.rgb(55, 161, 245));
            canvas.drawCircle(cx, cy, radius, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(Typeface.create("sans", Typeface.BOLD));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(radius * 0.78f);
            paint.setColor(dark ? Color.WHITE : LIGHT_TEXT);
            Paint.FontMetrics fm = paint.getFontMetrics();
            float base = cy - (fm.ascent + fm.descent) / 2f - dp(5);
            canvas.drawText(initials, cx, base, paint);

            paint.setColor(BLUE);
            oval.set(cx - radius * 0.48f, cy + radius * 0.36f, cx + radius * 0.48f, cy + radius * 0.60f);
            canvas.drawOval(oval, paint);
            paint.setColor(CYAN);
            oval.set(cx - radius * 0.38f, cy + radius * 0.48f, cx + radius * 0.38f, cy + radius * 0.67f);
            canvas.drawOval(oval, paint);
        }
    }

    private class WifiView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        WifiView(Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int c = darkMode ? Color.WHITE : LIGHT_TEXT;
            p.setColor(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(4));
            float cx = getWidth() / 2f;
            float cy = getHeight() * 0.72f;
            for (int i = 0; i < 3; i++) {
                float rad = dp(7 + i * 8);
                r.set(cx - rad, cy - rad, cx + rad, cy + rad);
                canvas.drawArc(r, 220, 100, false, p);
            }
            p.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy + dp(1), dp(3), p);
        }
    }
}
