package com.radiocaribe.tuner;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String PREFS = "radio_caribe";
    private static final String KEY_INDEX = "last_station";
    private static final String KEY_DARK = "dark_theme";
    private static final String KEY_STATIONS = "station_list_v2";

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

    private final ArrayList<Station> stations = new ArrayList<>();
    private SharedPreferences prefs;
    private MediaPlayer player;
    private AudioManager audioManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int stationIndex = 0;
    private boolean darkMode = false;
    private boolean shouldPlay = true;
    private boolean preparing = false;
    private long connectStartMs = 0L;

    private FrameLayout root;
    private TextView clockView;
    private TextView themeView;
    private TextView diagnosticButton;
    private TextView diagnosticView;
    private WifiView wifiView;
    private StationBadgeView badgeView;
    private TextView stationNameView;
    private TextView sourceView;
    private TextView frequencyView;
    private RoundButton previousButton;
    private RoundButton playButton;
    private RoundButton nextButton;

    private final boolean[] controlDown = new boolean[] {false, false, false};
    private boolean adminGestureLatched = false;

    private final Runnable clockTicker = new Runnable() {
        @Override public void run() {
            updateClock();
            long delay = 60000L - (System.currentTimeMillis() % 60000L);
            handler.postDelayed(this, delay);
        }
    };

    private final Runnable retryRunnable = new Runnable() {
        @Override public void run() {
            if (shouldPlay && !isFinishing()) playCurrentStation(true);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadStations();
        stationIndex = Math.max(0, Math.min(stations.size() - 1, prefs.getInt(KEY_INDEX, 0)));
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

    private void loadStations() {
        stations.clear();
        String saved = prefs.getString(KEY_STATIONS, "");
        if (saved != null && !saved.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(saved);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    stations.add(new Station(
                            o.optString("short", "RC"),
                            o.optString("name", "RADIO"),
                            o.optString("frequency", "ONLINE"),
                            o.optString("source", "ONLINE"),
                            o.optString("url", "")
                    ));
                }
            } catch (Exception ignored) {
                stations.clear();
            }
        }
        if (stations.isEmpty()) addDefaultStations();
    }

    private void addDefaultStations() {
        stations.clear();
        stations.add(new Station("RT", "RADIO TIEMPO BARRANQUILLA", "96.1 FM", "ONLINE", "https://playerservices.streamtheworld.com/api/livestream-redirect/RT_BARRANQUILLAAAC.aac"));
        stations.add(new Station("OL", "OLÍMPICA BARRANQUILLA", "92.1 FM", "ONLINE", "https://playerservices.streamtheworld.com/api/livestream-redirect/OLP_BARRANQUILLAAAC_SC"));
        stations.add(new Station("LR", "LA REINA BARRANQUILLA", "98.6 FM", "ONLINE", "https://playerservices.streamtheworld.com/api/livestream-redirect/RNA_BARRANQUILLA_SC"));
        stations.add(new Station("MX", "MIX RADIO BARRANQUILLA", "103.9 FM", "ONLINE", "https://playerservices.streamtheworld.com/api/livestream-redirect/MIX_BARRANQUILLAAAC.aac"));
        stations.add(new Station("CA", "CANDENTE STEREO", "FUNDACIÓN", "ONLINE", "https://stream.zeno.fm/nizmbxnuo53uv"));
        stations.add(new Station("CY", "CAYENA STEREO", "FUNDACIÓN", "ONLINE", "https://server1.intermediacolombia.com:8112/;"));
        stations.add(new Station("FT", "FORTALEZA", "102.9 FM", "ONLINE", "https://streaming.radiosenlinea.com.ar/8830/stream"));
        stations.add(new Station("OL", "OLÍMPICA SANTA MARTA", "97.1 FM", "ONLINE", "https://playerservices.streamtheworld.com/api/livestream-redirect/OLP_SANTA_MARTAAAC.aac"));
        stations.add(new Station("ES", "EL SOL SANTA MARTA", "106.9 FM", "ONLINE", "https://mdstrm.com/audio/632ce8986d2e8108b23d249f/live.m3u8"));
        stations.add(new Station("FG", "FUEGO STEREO", "101.9 FM", "ONLINE", "http://real2.streaming-co.com:8747/stream/"));
        saveStations();
    }

    private void saveStations() {
        try {
            JSONArray arr = new JSONArray();
            for (Station s : stations) {
                JSONObject o = new JSONObject();
                o.put("short", s.shortName);
                o.put("name", s.name);
                o.put("frequency", s.frequency);
                o.put("source", s.source);
                o.put("url", s.url);
                arr.put(o);
            }
            prefs.edit().putString(KEY_STATIONS, arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void buildUi() {
        root = new FrameLayout(this);
        setContentView(root);

        themeView = makeTopButton("☾", 22);
        FrameLayout.LayoutParams themeLp = new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP | Gravity.LEFT);
        themeLp.leftMargin = dp(14);
        themeLp.topMargin = dp(9);
        root.addView(themeView, themeLp);
        themeView.setOnClickListener(v -> toggleTheme());

        diagnosticButton = makeTopButton("NET", 13);
        FrameLayout.LayoutParams diagBtnLp = new FrameLayout.LayoutParams(dp(54), dp(46), Gravity.TOP | Gravity.LEFT);
        diagBtnLp.leftMargin = dp(68);
        diagBtnLp.topMargin = dp(9);
        root.addView(diagnosticButton, diagBtnLp);
        diagnosticButton.setOnClickListener(v -> toggleDiagnostics());

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setOrientation(LinearLayout.HORIZONTAL);
        clockRow.setGravity(Gravity.CENTER_VERTICAL);
        wifiView = new WifiView(this);
        clockRow.addView(wifiView, new LinearLayout.LayoutParams(dp(38), dp(34)));
        clockView = makeText("00:00", 29, Typeface.BOLD);
        LinearLayout.LayoutParams clockTextLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        clockTextLp.leftMargin = dp(4);
        clockRow.addView(clockView, clockTextLp);
        FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48), Gravity.TOP | Gravity.RIGHT);
        clockLp.rightMargin = dp(17);
        clockLp.topMargin = dp(8);
        root.addView(clockRow, clockLp);

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams centerLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        centerLp.leftMargin = dp(28);
        centerLp.rightMargin = dp(28);
        centerLp.topMargin = dp(45);
        centerLp.bottomMargin = dp(6);
        root.addView(center, centerLp);

        badgeView = new StationBadgeView(this);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(108), dp(108));
        center.addView(badgeView, badgeLp);

        stationNameView = makeText("", 23, Typeface.BOLD);
        stationNameView.setGravity(Gravity.CENTER);
        stationNameView.setMaxLines(1);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        nameLp.topMargin = dp(2);
        center.addView(stationNameView, nameLp);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
        center.addView(meta, metaLp);

        sourceView = makeText("● ONLINE", 14, Typeface.BOLD);
        sourceView.setGravity(Gravity.CENTER);
        sourceView.setPadding(dp(12), 0, dp(12), 0);
        meta.addView(sourceView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)));

        TextView divider = makeText("|", 17, Typeface.NORMAL);
        divider.setAlpha(0.45f);
        divider.setGravity(Gravity.CENTER);
        meta.addView(divider, new LinearLayout.LayoutParams(dp(24), dp(28)));

        frequencyView = makeText("", 15, Typeface.BOLD);
        frequencyView.setGravity(Gravity.CENTER);
        meta.addView(frequencyView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams controlsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(82));
        controlsLp.topMargin = dp(2);
        center.addView(controls, controlsLp);

        previousButton = new RoundButton(this, "<", false);
        playButton = new RoundButton(this, "II", true);
        nextButton = new RoundButton(this, ">", false);

        LinearLayout.LayoutParams sideLp = new LinearLayout.LayoutParams(dp(64), dp(64));
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(dp(76), dp(76));
        playLp.leftMargin = dp(32);
        playLp.rightMargin = dp(32);
        controls.addView(previousButton, sideLp);
        controls.addView(playButton, playLp);
        controls.addView(nextButton, sideLp);

        previousButton.setOnTouchListener(new ControlTouchListener(0, () -> changeStation(-1)));
        playButton.setOnTouchListener(new ControlTouchListener(1, this::togglePlayback));
        nextButton.setOnTouchListener(new ControlTouchListener(2, () -> changeStation(1)));

        diagnosticView = makeText("", 11, Typeface.NORMAL);
        diagnosticView.setTypeface(Typeface.MONOSPACE);
        diagnosticView.setGravity(Gravity.TOP | Gravity.LEFT);
        diagnosticView.setPadding(dp(10), dp(7), dp(10), dp(7));
        diagnosticView.setVisibility(View.GONE);
        diagnosticView.setMaxLines(6);
        FrameLayout.LayoutParams diagLp = new FrameLayout.LayoutParams(dp(360), dp(100), Gravity.TOP | Gravity.LEFT);
        diagLp.leftMargin = dp(14);
        diagLp.topMargin = dp(60);
        root.addView(diagnosticView, diagLp);

        applyTheme();
        renderStation();
    }

    private TextView makeTopButton(String label, float sp) {
        TextView v = makeText(label, sp, Typeface.BOLD);
        v.setGravity(Gravity.CENTER);
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    private void renderStation() {
        if (stations.isEmpty()) addDefaultStations();
        stationIndex = Math.max(0, Math.min(stationIndex, stations.size() - 1));
        Station s = stations.get(stationIndex);
        stationNameView.setText(s.name);
        sourceView.setText("● " + s.source);
        frequencyView.setText(s.frequency);
        badgeView.setStation(s.shortName);
        prefs.edit().putInt(KEY_INDEX, stationIndex).apply();
    }

    private void changeStation(int direction) {
        if (stations.isEmpty()) return;
        stationIndex = (stationIndex + direction + stations.size()) % stations.size();
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
                appendDiag("PLAYER pause");
                updatePlayButton(false);
                return;
            }
        }
        shouldPlay = true;
        if (player != null && !preparing) {
            try {
                player.start();
                appendDiag("PLAYER resume");
                updatePlayButton(true);
                return;
            } catch (IllegalStateException ignored) { }
        }
        playCurrentStation(false);
    }

    private void playCurrentStation(boolean isRetry) {
        handler.removeCallbacks(retryRunnable);
        releasePlayer();

        if (stations.isEmpty()) return;
        Station station = stations.get(stationIndex);

        if (!isNetworkAvailable()) {
            preparing = false;
            updatePlayButton(false);
            sourceView.setText("● SIN RED");
            appendDiag("RED: sin conexión");
            handler.postDelayed(retryRunnable, 5000L);
            return;
        }

        preparing = true;
        connectStartMs = System.currentTimeMillis();
        sourceView.setText("● CONECTANDO");
        appendDiag((isRetry ? "RETRY " : "OPEN  ") + station.name);
        updatePlayButton(true);

        MediaPlayer p = new MediaPlayer();
        player = p;
        p.setAudioStreamType(AudioManager.STREAM_MUSIC);
        p.setOnPreparedListener(mp -> {
            if (mp != player) return;
            preparing = false;
            long elapsed = System.currentTimeMillis() - connectStartMs;
            sourceView.setText("● " + station.source);
            appendDiag("PLAYER listo: " + elapsed + " ms");
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
            sourceView.setText("● REINTENTANDO");
            appendDiag("ERROR player what=" + what + " extra=" + extra);
            updatePlayButton(false);
            if (shouldPlay) handler.postDelayed(retryRunnable, isRetry ? 8000L : 4500L);
            return true;
        });
        p.setOnCompletionListener(mp -> {
            appendDiag("PLAYER fin de stream");
            if (shouldPlay) handler.postDelayed(retryRunnable, 1500L);
        });

        try {
            requestAudioFocus();
            p.setDataSource(station.url);
            p.prepareAsync();
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            preparing = false;
            sourceView.setText("● ERROR");
            appendDiag("ERROR apertura: " + e.getClass().getSimpleName());
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
                if (focusChange < 0 && player != null && !preparing) {
                    try {
                        if (player.isPlaying()) {
                            player.pause();
                            updatePlayButton(false);
                        }
                    } catch (Exception ignored) { }
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

    private void toggleDiagnostics() {
        boolean show = diagnosticView.getVisibility() != View.VISIBLE;
        diagnosticView.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            diagnosticView.setText("");
            appendDiag("DIAG " + stations.get(stationIndex).name);
            runNetworkDiagnostics();
        }
    }

    private void appendDiag(String line) {
        if (diagnosticView == null) return;
        runOnUiThread(() -> {
            String current = diagnosticView.getText().toString();
            String[] lines = current.isEmpty() ? new String[0] : current.split("\n");
            StringBuilder out = new StringBuilder();
            int start = Math.max(0, lines.length - 4);
            for (int i = start; i < lines.length; i++) {
                if (!lines[i].isEmpty()) out.append(lines[i]).append("\n");
            }
            out.append(new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()))
                    .append("  ").append(line);
            diagnosticView.setText(out.toString());
        });
    }

    private void runNetworkDiagnostics() {
        if (stations.isEmpty()) return;
        Station s = stations.get(stationIndex);
        appendDiag("RED: " + (isNetworkAvailable() ? "conectada" : "sin red"));
        new Thread(() -> {
            try {
                URL u = new URL(s.url);
                String host = u.getHost();
                int port = u.getPort();
                if (port < 0) port = "https".equalsIgnoreCase(u.getProtocol()) ? 443 : 80;

                long t0 = System.currentTimeMillis();
                InetAddress address = InetAddress.getByName(host);
                long dns = System.currentTimeMillis() - t0;
                appendDiag("DNS " + host + ": " + dns + " ms");

                t0 = System.currentTimeMillis();
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(address, port), 3500);
                long tcp = System.currentTimeMillis() - t0;
                socket.close();
                appendDiag("TCP " + port + ": " + tcp + " ms");
            } catch (Exception e) {
                appendDiag("NET ERROR: " + e.getClass().getSimpleName());
            }
        }).start();
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
        diagnosticButton.setTextColor(text);
        themeView.setText(darkMode ? "☀" : "☾");

        GradientDrawable themeBg = topButtonBackground(darkMode);
        themeView.setBackground(themeBg);
        diagnosticButton.setBackground(topButtonBackground(darkMode));

        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(18));
        pill.setColor(darkMode ? Color.rgb(4, 45, 92) : Color.rgb(219, 239, 255));
        sourceView.setBackground(pill);
        sourceView.setTextColor(darkMode ? Color.rgb(64, 205, 255) : Color.rgb(0, 104, 222));

        GradientDrawable diagBg = new GradientDrawable();
        diagBg.setCornerRadius(dp(10));
        diagBg.setColor(darkMode ? Color.argb(245, 8, 24, 43) : Color.argb(245, 255, 255, 255));
        diagBg.setStroke(dp(1), darkMode ? Color.rgb(49, 86, 120) : Color.rgb(205, 218, 232));
        diagnosticView.setBackground(diagBg);
        diagnosticView.setTextColor(darkMode ? Color.rgb(198, 232, 255) : Color.rgb(25, 55, 82));

        badgeView.setDark(darkMode);
        previousButton.applyTheme(darkMode);
        playButton.applyTheme(darkMode);
        nextButton.applyTheme(darkMode);
        if (wifiView != null) wifiView.invalidate();

        getWindow().setNavigationBarColor(bg);
        getWindow().setStatusBarColor(bg);
    }

    private GradientDrawable topButtonBackground(boolean dark) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(dark ? Color.rgb(14, 32, 54) : Color.WHITE);
        gd.setStroke(dp(1), dark ? Color.rgb(35, 63, 93) : Color.rgb(226, 234, 244));
        return gd;
    }

    private void updatePlayButton(boolean playing) {
        if (playButton != null) playButton.setText(playing ? "II" : "PLAY");
    }

    private void updateClock() {
        if (clockView != null) {
            clockView.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
        }
    }

    private void showAdminPanel() {
        appendDiag("ADMIN abierto");

        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(12), dp(18), dp(12));
        scroll.addView(box);

        TextView hint = makeText("RADIOCARIBE DEBUG / ADMIN", 18, Typeface.BOLD);
        hint.setPadding(0, 0, 0, dp(8));
        box.addView(hint);

        Station current = stations.get(stationIndex);
        EditText name = adminField("Nombre", current.name);
        EditText freq = adminField("Frecuencia / ciudad", current.frequency);
        EditText url = adminField("URL del stream", current.url);
        box.addView(name);
        box.addView(freq);
        box.addView(url);

        Button save = new Button(this);
        save.setText("GUARDAR EMISORA ACTUAL");
        box.addView(save);

        Button delete = new Button(this);
        delete.setText("ELIMINAR EMISORA ACTUAL");
        box.addView(delete);

        TextView importLabel = makeText("IMPORTAR M3U (pega el contenido aquí)", 14, Typeface.BOLD);
        importLabel.setPadding(0, dp(10), 0, dp(4));
        box.addView(importLabel);

        EditText m3u = new EditText(this);
        m3u.setMinLines(6);
        m3u.setGravity(Gravity.TOP | Gravity.LEFT);
        m3u.setTextSize(12);
        m3u.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        m3u.setHint("#EXTM3U\n#EXTINF:-1,Mi Radio\nhttps://...");
        box.addView(m3u, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));

        Button importButton = new Button(this);
        importButton.setText("IMPORTAR M3U");
        box.addView(importButton);

        Button reset = new Button(this);
        reset.setText("RESTAURAR LISTA BASE");
        box.addView(reset);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(scroll)
                .setNegativeButton("CERRAR", null)
                .create();

        save.setOnClickListener(v -> {
            String newName = name.getText().toString().trim();
            String newFreq = freq.getText().toString().trim();
            String newUrl = url.getText().toString().trim();
            if (newName.isEmpty() || newUrl.isEmpty()) {
                Toast.makeText(this, "Nombre y URL son obligatorios", Toast.LENGTH_SHORT).show();
                return;
            }
            stations.set(stationIndex, new Station(initialsFor(newName), newName,
                    newFreq.isEmpty() ? "ONLINE" : newFreq, "ONLINE", newUrl));
            saveStations();
            renderStation();
            shouldPlay = true;
            playCurrentStation(false);
            Toast.makeText(this, "Emisora actualizada", Toast.LENGTH_SHORT).show();
        });

        delete.setOnClickListener(v -> {
            if (stations.size() <= 1) {
                Toast.makeText(this, "Debe quedar al menos una emisora", Toast.LENGTH_SHORT).show();
                return;
            }
            stations.remove(stationIndex);
            if (stationIndex >= stations.size()) stationIndex = stations.size() - 1;
            saveStations();
            renderStation();
            shouldPlay = true;
            playCurrentStation(false);
            dialog.dismiss();
            Toast.makeText(this, "Emisora eliminada", Toast.LENGTH_SHORT).show();
        });

        importButton.setOnClickListener(v -> {
            int added = importM3u(m3u.getText().toString());
            if (added > 0) {
                saveStations();
                Toast.makeText(this, added + " emisora(s) importada(s)", Toast.LENGTH_SHORT).show();
                m3u.setText("");
            } else {
                Toast.makeText(this, "No encontré entradas válidas", Toast.LENGTH_SHORT).show();
            }
        });

        reset.setOnClickListener(v -> {
            addDefaultStations();
            stationIndex = 0;
            renderStation();
            shouldPlay = true;
            playCurrentStation(false);
            dialog.dismiss();
            Toast.makeText(this, "Lista base restaurada", Toast.LENGTH_SHORT).show();
        });

        dialog.setOnDismissListener(d -> enterImmersiveMode());
        dialog.show();
    }

    private EditText adminField(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setSelectAllOnFocus(false);
        return e;
    }

    private int importM3u(String raw) {
        if (raw == null || raw.trim().isEmpty()) return 0;
        String[] lines = raw.replace("\r", "").split("\n");
        String pendingName = null;
        int added = 0;
        for (String original : lines) {
            String line = original.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF")) {
                int comma = line.lastIndexOf(',');
                pendingName = comma >= 0 ? line.substring(comma + 1).trim() : "RADIO IMPORTADA";
            } else if (!line.startsWith("#") && (line.startsWith("http://") || line.startsWith("https://"))) {
                String name = pendingName == null || pendingName.isEmpty() ? "RADIO IMPORTADA " + (added + 1) : pendingName;
                stations.add(new Station(initialsFor(name), name, "ONLINE", "ONLINE", line));
                pendingName = null;
                added++;
            }
        }
        return added;
    }

    private String initialsFor(String name) {
        String clean = name == null ? "RC" : name.trim().toUpperCase(Locale.getDefault());
        if (clean.isEmpty()) return "RC";
        String[] words = clean.split("\\s+");
        StringBuilder s = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) s.append(word.charAt(0));
            if (s.length() == 2) break;
        }
        if (s.length() == 1 && clean.length() > 1) s.append(clean.charAt(1));
        return s.toString();
    }

    private class ControlTouchListener implements View.OnTouchListener {
        private final int index;
        private final Runnable action;

        ControlTouchListener(int index, Runnable action) {
            this.index = index;
            this.action = action;
        }

        @Override public boolean onTouch(View v, MotionEvent event) {
            int actionCode = event.getActionMasked();
            if (actionCode == MotionEvent.ACTION_DOWN) {
                controlDown[index] = true;
                v.setAlpha(0.65f);
                if (!adminGestureLatched && controlDown[0] && controlDown[1] && controlDown[2]) {
                    adminGestureLatched = true;
                    showAdminPanel();
                }
                return true;
            }

            if (actionCode == MotionEvent.ACTION_UP || actionCode == MotionEvent.ACTION_CANCEL) {
                boolean execute = !adminGestureLatched && actionCode == MotionEvent.ACTION_UP;
                controlDown[index] = false;
                v.setAlpha(1f);

                boolean any = controlDown[0] || controlDown[1] || controlDown[2];
                if (!any && adminGestureLatched) adminGestureLatched = false;

                if (execute) action.run();
                return true;
            }
            return true;
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
            setTextSize(primary ? 20 : 32);
            setTypeface(Typeface.DEFAULT_BOLD);
            setIncludeFontPadding(false);
            setElevation(dp(primary ? 7 : 5));
            setClickable(true);
            setFocusable(true);
        }

        void applyTheme(boolean dark) {
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            if (primary) {
                gd.setColor(BLUE);
                gd.setStroke(dp(2), dark ? Color.rgb(27, 200, 255) : Color.rgb(72, 184, 255));
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

        StationBadgeView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void setStation(String value) {
            initials = value;
            invalidate();
        }

        void setDark(boolean value) {
            dark = value;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float radius = Math.min(w, h) * 0.40f;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(dark ? Color.rgb(5, 22, 42) : Color.WHITE);
            paint.setShadowLayer(dp(10), 0, dp(4), dark ? Color.argb(140, 0, 137, 255) : Color.argb(45, 14, 128, 240));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.clearShadowLayer();

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            paint.setColor(dark ? CYAN : Color.rgb(55, 161, 245));
            canvas.drawCircle(cx, cy, radius, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(Typeface.create("sans", Typeface.BOLD));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(radius * 0.75f);
            paint.setColor(dark ? Color.WHITE : LIGHT_TEXT);
            Paint.FontMetrics fm = paint.getFontMetrics();
            float base = cy - (fm.ascent + fm.descent) / 2f - dp(3);
            canvas.drawText(initials, cx, base, paint);

            paint.setColor(BLUE);
            oval.set(cx - radius * 0.48f, cy + radius * 0.34f, cx + radius * 0.48f, cy + radius * 0.54f);
            canvas.drawOval(oval, paint);
            paint.setColor(CYAN);
            oval.set(cx - radius * 0.36f, cy + radius * 0.44f, cx + radius * 0.36f, cy + radius * 0.60f);
            canvas.drawOval(oval, paint);
        }
    }

    private class WifiView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        WifiView(Context context) {
            super(context);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int c = darkMode ? Color.WHITE : LIGHT_TEXT;
            p.setColor(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(2.5f));
            float cx = getWidth() / 2f;
            float cy = getHeight() * 0.72f;
            for (int i = 0; i < 3; i++) {
                float rad = dp(5 + i * 5);
                r.set(cx - rad, cy - rad, cx + rad, cy + rad);
                canvas.drawArc(r, 220, 100, false, p);
            }
            p.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy + dp(1), dp(2), p);
        }
    }
}
