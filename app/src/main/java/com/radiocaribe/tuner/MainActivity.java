package com.radiocaribe.tuner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.AudioDeviceInfo;
import android.media.audiofx.LoudnessEnhancer;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.analytics.AnalyticsListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private static final String PREFS = "radio_caribe";
    private static final String KEY_INDEX = "last_station";
    private static final String KEY_DARK = "dark_theme";
    private static final String KEY_STATIONS = "station_list_v2";
    private static final String KEY_RECENTS = "recent_urls_v1";

    private static final int NAVY = Color.rgb(10, 31, 58);
    private static final int BLUE = Color.rgb(18, 139, 255);
    private static final int LIGHT_BG = Color.rgb(250, 251, 253);
    private static final int LIGHT_TEXT = Color.rgb(8, 18, 34);
    private static final int DARK_BG = Color.rgb(5, 13, 25);
    private static final int DARK_TEXT = Color.WHITE;

    private static class Station {
        final String id;
        final String shortName;
        final String name;
        final String frequency;
        final String source;
        final String url;

        Station(String shortName, String name, String frequency, String source, String url) {
            this(shortName, name, frequency, source, url, "");
        }
        Station(String shortName, String name, String frequency, String source, String url, String id) {
            this.id = (id != null && id.matches("[a-zA-Z0-9-]{8,60}"))
                    ? id : UUID.randomUUID().toString();
            this.shortName = shortName;
            this.name = name;
            this.frequency = frequency;
            this.source = source;
            this.url = url;
        }
    }

    private final ArrayList<Station> stations = new ArrayList<>();
    private SharedPreferences prefs;
    private ExoPlayer player;
    private AudioManager audioManager;
    private LoudnessEnhancer streamingBooster;
    private int persistentStreamSession = -1;
    private final Runnable jackVolumeRetry = () -> {
        maximizeMediaVolume();
        updateBoosterForJack();
    };
    private final BroadcastReceiver jackReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            handler.removeCallbacks(jackVolumeRetry);
            jackVolumeRetry.run();
            handler.postDelayed(jackVolumeRetry, 300L);
            handler.postDelayed(jackVolumeRetry, 1000L);
        }
    };
    // 600 millibels = +6 dB target gain on the online audio session only.
    // Android's LoudnessEnhancer limits/compresses peaks. FM remains untouched.
    private static final int STREAM_GAIN_MB = 600;
    private InternalFmController fmController;
    private WebAdminServer webAdmin;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int stationIndex = 0;
    private boolean darkMode = false;
    private boolean shouldPlay = true;
    private boolean preparing = false;
    private long connectStartMs = 0L;
    private int stationAccent = NAVY;

    private FrameLayout root;
    private TextView clockView;
    private TextView themeView;
    private TextView diagnosticButton;
    private TextView diagnosticView;
    private BatteryView batteryView;
    private WifiView wifiView;

    private FrameLayout mainLogoBox;
    private ImageView mainLogoView;
    private StationBadgeView mainFallbackView;

    private HorizontalScrollView gridScroll;
    private LinearLayout gridRow;

    private TextView stationNameView;
    private TextView sourceView;
    private TextView frequencyView;
    private ColorButton previousButton;
    private ColorButton playButton;
    private ColorButton nextButton;

    private final boolean[] controlDown = new boolean[] {false, false, false};
    private boolean adminGestureLatched = false;
    private boolean suppressCarouselSelection = true;
    private int carouselPreviewIndex = -1;
    // Smooth scrolling to a chosen tile must not trigger a second, accidental
    // selection halfway through the animation (especially at both ends).
    private final Runnable carouselScrollUnlockRunnable = () -> {
        suppressCarouselSelection = false;
        updateCarouselTransforms();
        // A user can start a swipe while automated centering was in progress.
        // Select only if the viewport actually landed on a different tile;
        // do not repeatedly re-scroll the already selected station.
        int nearest = findNearestStationIndex();
        if (nearest >= 0 && nearest != stationIndex) {
            selectStation(nearest, false);
        }
    };

    private final Runnable carouselSnapRunnable = new Runnable() {
        @Override public void run() {
            if (suppressCarouselSelection || gridScroll == null || gridRow == null) return;
            int nearest = findNearestStationIndex();
            if (nearest < 0) return;

            if (nearest != stationIndex) {
                // El cambio real de emisora sucede solo cuando el carrusel se detiene.
                selectStation(nearest, false);
            } else {
                renderStation();
                scrollToSelected(true);
            }
        }
    };

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

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0 && scale > 0 && batteryView != null) {
                batteryView.setPercent(Math.round(level * 100f / scale));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        registerReceiver(jackReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
        maximizeMediaVolume();
        handler.postDelayed(jackVolumeRetry, 400L);
        handler.postDelayed(jackVolumeRetry, 1400L);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        fmController = new InternalFmController(this, this::appendDiag);

        loadStations();
        stationIndex = Math.max(0, Math.min(stations.size() - 1, prefs.getInt(KEY_INDEX, 0)));
        darkMode = prefs.getBoolean(KEY_DARK, false);

        buildUi();
        enterImmersiveMode();
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        clockTicker.run();

        markRecent(stations.get(stationIndex));
        rebuildGrid();
        webAdmin = new WebAdminServer(this, handler, new WebAdminServer.Controller() {
            @Override public JSONObject execute(String operation, JSONObject data) throws Exception {
                return executeWebAdmin(operation, data);
            }
            @Override public File findLogo(String id) {
                return id != null && id.matches("[a-zA-Z0-9-]{8,60}") ? logoFileForId(id) : null;
            }
        });
        webAdmin.start();
        playCurrentStation(false);
    }

    @Override protected void onResume() {
        super.onResume();
        maximizeMediaVolume();
        updateBoosterForJack();
        enterImmersiveMode();
        if (wifiView != null) wifiView.invalidate();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) { }
        try { unregisterReceiver(jackReceiver); } catch (Exception ignored) { }
        if (webAdmin != null) webAdmin.stop();
        releasePlayer();
        releaseStreamingBooster();
        if (fmController != null) fmController.stop();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersiveMode();
    }

    private void loadStations() {
        stations.clear();
        String saved = prefs.getString(KEY_STATIONS, "");
        boolean migratedIds = false;
        if (saved != null && !saved.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(saved);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    if (!o.has("id")) migratedIds = true;
                    stations.add(new Station(
                            o.optString("short", "RC"),
                            o.optString("name", "RADIO"),
                            o.optString("frequency", "ONLINE"),
                            o.optString("source", "ONLINE"),
                            o.optString("url", ""),
                            o.optString("id", "")
                    ));
                }
            } catch (Exception ignored) {
                stations.clear();
            }
        }
        if (stations.isEmpty()) addDefaultStations();
        ensureMacondo();
        if (migratedIds) saveStations();
    }

    private void addDefaultStations() {
        stations.clear();
        stations.add(new Station("MC", "MACONDO STEREO", "103.4 FM", "FM", "fm://103.4"));
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

    private void ensureMacondo() {
        for (Station s : stations) {
            if ("FM".equalsIgnoreCase(s.source) && s.frequency.contains("103.4")) return;
        }
        stations.add(0, new Station("MC", "MACONDO STEREO", "103.4 FM", "FM", "fm://103.4"));
        saveStations();
    }

    private void saveStations() {
        try {
            JSONArray arr = new JSONArray();
            for (Station s : stations) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
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

    private File logoFileForId(String id) {
        File dir = new File(getFilesDir(), "station_logos");
        return new File(dir, id + ".jpg");
    }

    private File logoFileFor(Station s) {
        File candidate = logoFileForId(s.id);
        return candidate.isFile() ? candidate : null;
    }

    private JSONObject webResult() throws Exception { return new JSONObject().put("ok", true); }

    private JSONObject webStations() throws Exception {
        JSONArray all = new JSONArray();
        for (Station s : stations) {
            JSONObject row = new JSONObject();
            row.put("id", s.id).put("name", s.name).put("short", s.shortName)
                    .put("source", s.source).put("frequency", s.frequency).put("url", s.url)
                    .put("logo", logoFileFor(s) != null);
            all.put(row);
        }
        return webResult().put("stations", all);
    }

    private JSONObject executeWebAdmin(String operation, JSONObject json) throws Exception {
        if ("list".equals(operation)) return webStations();
        String id = json.optString("id", "");
        int at = -1;
        for (int i = 0; i < stations.size(); i++)
            if (stations.get(i).id.equals(id)) { at = i; break; }

        if ("save".equals(operation)) {
            String name = json.optString("name", "").trim();
            String freq = json.optString("frequency", "").trim();
            String source = json.optString("source", "ONLINE");
            String url = json.optString("url", "").trim();
            if (name.isEmpty() || name.length() > 80 || url.length() > 1000 ||
                    freq.length() > 45 || (!"FM".equals(source) && !"ONLINE".equals(source)) ||
                    ("FM".equals(source) && !url.matches("fm://[0-9]{2,3}([.][0-9]{1,2})?")) ||
                    ("ONLINE".equals(source) && !(url.startsWith("https://") || url.startsWith("http://")))) {
                throw new IllegalArgumentException("Revisa nombre, tipo y URL");
            }
            String selectedId = stations.isEmpty() ? "" : stations.get(stationIndex).id;
            Station existing = at >= 0 ? stations.get(at) : null;
            Station updated = new Station(initialsFor(name), name,
                    freq.isEmpty() ? source : freq, source, url,
                    existing != null ? existing.id : "");
            if (existing != null) stations.set(at, updated);
            else stations.add(updated);
            saveStations();
            if (existing != null && selectedId.equals(updated.id)) {
                renderStation();
                rebuildGrid();
                shouldPlay = true; playCurrentStation(false);
            } else {
                if (!selectedId.isEmpty()) {
                    for (int i = 0; i < stations.size(); i++)
                        if (selectedId.equals(stations.get(i).id)) { stationIndex = i; break; }
                }
                rebuildGrid();
            }
            return webResult().put("id", updated.id);
        }
        if (at < 0) throw new IllegalArgumentException("La emisora ya no existe");
        if ("delete".equals(operation)) {
            if (stations.size() <= 1) throw new IllegalArgumentException("Debe quedar una emisora");
            boolean playing = at == stationIndex;
            stations.remove(at);
            File logo = logoFileForId(id);
            if (logo.exists()) logo.delete();
            if (at < stationIndex) stationIndex--;
            if (stationIndex >= stations.size()) stationIndex = stations.size() - 1;
            saveStations();
            rebuildGrid();
            renderStation();
            if (playing) { shouldPlay = true; playCurrentStation(false); }
            return webResult();
        }
        if ("move".equals(operation)) {
            int delta = json.optInt("delta", 0), destination = at + delta;
            if ((delta != -1 && delta != 1) || destination < 0 || destination >= stations.size())
                throw new IllegalArgumentException("Movimiento no válido");
            String selectedId = stations.get(stationIndex).id;
            java.util.Collections.swap(stations, at, destination);
            for (int i = 0; i < stations.size(); i++) {
                if (stations.get(i).id.equals(selectedId)) { stationIndex = i; break; }
            }
            saveStations(); rebuildGrid(); renderStation();
            return webResult();
        }
        if ("upload".equals(operation)) {
            String raw = json.optString("image", "");
            if (raw.isEmpty() || raw.length() > 950000)
                throw new IllegalArgumentException("Imagen demasiado grande");
            byte[] input = Base64.decode(raw, Base64.DEFAULT);
            if (input.length > 700000) throw new IllegalArgumentException("Imagen demasiado grande");
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(input, 0, input.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                    bounds.outWidth > 4000 || bounds.outHeight > 4000)
                throw new IllegalArgumentException("Imagen inválida");
            Bitmap bitmap = BitmapFactory.decodeByteArray(input, 0, input.length);
            if (bitmap == null) throw new IllegalArgumentException("Imagen inválida");
            File target = logoFileForId(id);
            File folder = target.getParentFile();
            if (!folder.isDirectory() && !folder.mkdirs())
                throw new IllegalStateException("No se pudo crear carpeta de logos");
            File tmp = new File(folder, id + ".tmp");
            try (FileOutputStream output = new FileOutputStream(tmp)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output))
                    throw new IllegalStateException("No se pudo procesar el logo");
            } finally { bitmap.recycle(); }
            if (target.exists() && !target.delete()) throw new IllegalStateException("No se pudo reemplazar el logo");
            if (!tmp.renameTo(target)) throw new IllegalStateException("No se pudo guardar el logo");
            rebuildGrid(); renderStation();
            return webResult().put("id", id);
        }
        throw new IllegalArgumentException("Comando desconocido");
    }

    private void buildUi() {
        root = new FrameLayout(this);
        setContentView(root);

        buildTopStatusBar();
        buildMainLogo();
        buildRightPanel();
        buildDiagnostics();

        applyTheme();
        renderStation();
    }

    private void buildTopStatusBar() {
        LinearLayout leftStatus = new LinearLayout(this);
        leftStatus.setOrientation(LinearLayout.HORIZONTAL);
        leftStatus.setGravity(Gravity.CENTER_VERTICAL);

        batteryView = new BatteryView(this);
        leftStatus.addView(batteryView, new LinearLayout.LayoutParams(dp(56), dp(34)));

        wifiView = new WifiView(this);
        LinearLayout.LayoutParams wifiLp = new LinearLayout.LayoutParams(dp(38), dp(34));
        wifiLp.leftMargin = dp(4);
        leftStatus.addView(wifiView, wifiLp);

        themeView = makeIconButton("☾", 22);
        LinearLayout.LayoutParams themeLp = new LinearLayout.LayoutParams(dp(38), dp(34));
        themeLp.leftMargin = dp(3);
        leftStatus.addView(themeView, themeLp);
        themeView.setOnClickListener(v -> toggleTheme());

        diagnosticButton = makeIconButton("NET", 12);
        LinearLayout.LayoutParams netLp = new LinearLayout.LayoutParams(dp(48), dp(32));
        netLp.leftMargin = dp(4);
        leftStatus.addView(diagnosticButton, netLp);
        diagnosticButton.setOnClickListener(v -> toggleDiagnostics());

        FrameLayout.LayoutParams leftLp = new FrameLayout.LayoutParams(dp(192), dp(42), Gravity.TOP | Gravity.LEFT);
        leftLp.leftMargin = dp(12);
        leftLp.topMargin = dp(4);
        root.addView(leftStatus, leftLp);

        clockView = makeText("", 22, Typeface.BOLD);
        clockView.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        clockView.setSingleLine(true);
        FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(dp(410), dp(42), Gravity.TOP | Gravity.RIGHT);
        clockLp.rightMargin = dp(15);
        clockLp.topMargin = dp(3);
        root.addView(clockView, clockLp);
    }

    private void buildMainLogo() {
        // v0.6: el logo principal ahora forma parte del carrusel central.
    }

    private void buildRightPanel() {
        FrameLayout panel = new FrameLayout(this);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        panelLp.leftMargin = 0;
        panelLp.rightMargin = 0;
        panelLp.topMargin = dp(48);
        panelLp.bottomMargin = 0;
        root.addView(panel, panelLp);

        gridScroll = new HorizontalScrollView(this);
        gridScroll.setHorizontalScrollBarEnabled(false);
        gridScroll.setVerticalScrollBarEnabled(false);
        gridScroll.setFillViewport(false);
        gridScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        gridScroll.setSmoothScrollingEnabled(true);
        gridScroll.setClipToPadding(false);

        gridRow = new LinearLayout(this);
        gridRow.setOrientation(LinearLayout.HORIZONTAL);
        gridRow.setGravity(Gravity.CENTER_VERTICAL);
        gridRow.setPadding(0, 0, 0, 0);
        gridRow.setClipChildren(false);
        gridScroll.addView(gridRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        gridScroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            updateCarouselTransforms();
            if (suppressCarouselSelection) return;

            int nearest = findNearestStationIndex();
            if (nearest >= 0 && nearest != carouselPreviewIndex) {
                carouselPreviewIndex = nearest;
                previewStation(nearest);
                vibrateCarouselTick();
            }

            handler.removeCallbacks(carouselSnapRunnable);
            handler.postDelayed(carouselSnapRunnable, 145L);
        });

        FrameLayout.LayoutParams gridLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(238), Gravity.TOP);
        gridLp.topMargin = dp(2);
        panel.addView(gridScroll, gridLp);

        stationNameView = makeText("", 22, Typeface.BOLD);
        stationNameView.setSingleLine(true);
        stationNameView.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams nameLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38), Gravity.TOP);
        nameLp.leftMargin = dp(16);
        nameLp.rightMargin = dp(16);
        nameLp.topMargin = dp(238);
        panel.addView(stationNameView, nameLp);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER);

        sourceView = makeText("ONLINE", 13, Typeface.BOLD);
        sourceView.setGravity(Gravity.CENTER);
        sourceView.setPadding(dp(13), 0, dp(13), 0);
        meta.addView(sourceView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));

        frequencyView = makeText("", 15, Typeface.NORMAL);
        frequencyView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams freqLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        freqLp.leftMargin = dp(8);
        meta.addView(frequencyView, freqLp);

        FrameLayout.LayoutParams metaLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34), Gravity.BOTTOM);
        metaLp.bottomMargin = dp(2);
        panel.addView(meta, metaLp);
    }

    private void buildDiagnostics() {
        diagnosticView = makeText("", 11, Typeface.NORMAL);
        diagnosticView.setTypeface(Typeface.MONOSPACE);
        diagnosticView.setGravity(Gravity.TOP | Gravity.LEFT);
        diagnosticView.setPadding(dp(10), dp(7), dp(10), dp(7));
        diagnosticView.setVisibility(View.GONE);
        diagnosticView.setMaxLines(7);
        diagnosticView.setElevation(dp(12));

        FrameLayout.LayoutParams diagLp = new FrameLayout.LayoutParams(dp(350), dp(116), Gravity.TOP | Gravity.RIGHT);
        diagLp.rightMargin = dp(12);
        diagLp.topMargin = dp(48);
        root.addView(diagnosticView, diagLp);
    }

    private TextView makeIconButton(String label, float sp) {
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
        sourceView.setText(s.source);
        frequencyView.setText(s.frequency);

        int logoRes = logoResFor(s);
        stationAccent = logoRes != 0 ? extractAccentColor(logoRes) : NAVY;

        updateSourcePill();
        prefs.edit().putInt(KEY_INDEX, stationIndex).apply();
        if (wifiView != null) wifiView.invalidate();
    }

    private int logoResFor(Station s) {
        String n = s.name.toUpperCase(Locale.ROOT);

        if (n.contains("MACONDO")) return R.drawable.macondo;
        if (n.contains("RADIO TIEMPO")) return R.drawable.radio_tiempo;
        if (n.contains("OLÍMPICA") || n.contains("OLIMPICA")) {
            if (n.contains("SANTA MARTA")) return R.drawable.olimpica_smr;
            return R.drawable.olimpica_baq;
        }
        if (n.contains("LA REINA")) return R.drawable.la_reina;
        if (n.contains("MIX")) return R.drawable.mix;
        if (n.contains("CANDENTE")) return R.drawable.candente;
        if (n.contains("CAYENA")) return R.drawable.cayena;
        if (n.contains("FORTALEZA")) return R.drawable.fortaleza;
        if (n.contains("EL SOL")) return R.drawable.el_sol;
        if (n.contains("FUEGO")) return R.drawable.fuego;

        return 0;
    }

    private int extractAccentColor(int resId) {
        try {
            Bitmap bitmap = BitmapFactory.decodeResource(getResources(), resId);
            if (bitmap == null) return NAVY;

            int bins = 24;
            float[] weights = new float[bins];
            float[] sumR = new float[bins];
            float[] sumG = new float[bins];
            float[] sumB = new float[bins];

            int stepX = Math.max(1, bitmap.getWidth() / 60);
            int stepY = Math.max(1, bitmap.getHeight() / 60);
            float[] hsv = new float[3];

            for (int y = 0; y < bitmap.getHeight(); y += stepY) {
                for (int x = 0; x < bitmap.getWidth(); x += stepX) {
                    int c = bitmap.getPixel(x, y);
                    if (Color.alpha(c) < 180) continue;

                    Color.colorToHSV(c, hsv);
                    if (hsv[1] < 0.28f || hsv[2] < 0.20f || hsv[2] > 0.97f) continue;

                    int bin = Math.min(bins - 1, (int) (hsv[0] / 360f * bins));
                    float w = hsv[1] * (0.35f + hsv[2]);
                    weights[bin] += w;
                    sumR[bin] += Color.red(c) * w;
                    sumG[bin] += Color.green(c) * w;
                    sumB[bin] += Color.blue(c) * w;
                }
            }

            int best = -1;
            float bestWeight = 0;
            for (int i = 0; i < bins; i++) {
                if (weights[i] > bestWeight) {
                    bestWeight = weights[i];
                    best = i;
                }
            }

            if (best < 0 || weights[best] <= 0) return NAVY;

            int color = Color.rgb(
                    Math.round(sumR[best] / weights[best]),
                    Math.round(sumG[best] / weights[best]),
                    Math.round(sumB[best] / weights[best]));

            Color.colorToHSV(color, hsv);
            hsv[1] = Math.max(0.62f, hsv[1]);
            if (darkMode) {
                hsv[2] = Math.max(0.78f, Math.min(0.95f, hsv[2]));
            } else {
                hsv[2] = Math.max(0.52f, Math.min(0.78f, hsv[2]));
            }
            return Color.HSVToColor(hsv);
        } catch (Exception ignored) {
            return NAVY;
        }
    }

    private void rebuildGrid() {
        if (gridRow == null || gridScroll == null) return;

        suppressCarouselSelection = true;
        handler.removeCallbacks(carouselSnapRunnable);
        handler.removeCallbacks(carouselScrollUnlockRunnable);
        gridRow.removeAllViews();

        final int tileSize = 138;

        for (int i = 0; i < stations.size(); i++) {
            final int sourceIndex = i;
            final Station s = stations.get(sourceIndex);

            FrameLayout tile = new FrameLayout(this);
            tile.setTag(sourceIndex);
            tile.setClickable(true);
            tile.setFocusable(true);
            tile.setClipChildren(false);

            int logo = logoResFor(s);
            File customLogo = logoFileFor(s);
            if (customLogo != null || logo != 0) {
                ImageView img = new ImageView(this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                if (customLogo != null) img.setImageBitmap(BitmapFactory.decodeFile(customLogo.getAbsolutePath()));
                else img.setImageResource(logo);
                tile.addView(img, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                StationBadgeView fallback = new StationBadgeView(this);
                fallback.setStation(s.shortName);
                fallback.setAccent(NAVY);
                fallback.setDark(darkMode);
                tile.addView(fallback, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }

            tile.setOnClickListener(v -> {
                // The touched card is authoritative; don't guess its index
                // from a later scroll position (which breaks edge stations).
                handler.removeCallbacks(carouselSnapRunnable);
                selectStation(sourceIndex, true);
            });

            tile.setOnLongClickListener(v -> {
                if (sourceIndex == stationIndex) {
                    showAdminPanel();
                } else {
                    scrollToIndex(sourceIndex, true);
                }
                return true;
            });

            LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(
                    dp(tileSize), dp(tileSize));
            tileLp.leftMargin = dp(5);
            tileLp.rightMargin = dp(5);
            gridRow.addView(tile, tileLp);
        }

        // Recreating the activity used to restore scroll while tile positions
        // were still zero. Wait for a real measured/layout pass instead of
        // chaining post(), which is not a layout completion guarantee.
        final int desiredStationIndex = stationIndex;
        gridScroll.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (gridScroll == null || gridRow == null ||
                        gridScroll.getWidth() <= 0 ||
                        gridRow.getChildCount() != stations.size()) return true;
                int sidePadding = Math.max(0,
                        (gridScroll.getWidth() - dp(tileSize)) / 2 - dp(5));
                if (gridRow.getPaddingLeft() != sidePadding ||
                        gridRow.getPaddingRight() != sidePadding) {
                    gridRow.setPadding(sidePadding, 0, sidePadding, 0);
                    // Await the next layout before relying on child positions.
                    return true;
                }
                if (gridRow.getChildAt(0).getWidth() <= 0 ||
                        gridRow.getChildAt(gridRow.getChildCount() - 1).getWidth() <= 0) {
                    return true;
                }
                ViewTreeObserver observer = gridScroll.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                carouselPreviewIndex = desiredStationIndex;
                scrollToIndex(desiredStationIndex, false);
                updateCarouselTransforms();
                handler.removeCallbacks(carouselScrollUnlockRunnable);
                handler.postDelayed(carouselScrollUnlockRunnable, 200L);
                return true;
            }
        });
    }

    private void updateCarouselTransforms() {
        if (gridScroll == null || gridRow == null || gridScroll.getWidth() <= 0) return;

        final float viewportCenter = gridScroll.getScrollX() + gridScroll.getWidth() / 2f;
        final float influence = Math.max(dp(155), gridScroll.getWidth() * 0.34f);

        for (int i = 0; i < gridRow.getChildCount(); i++) {
            View child = gridRow.getChildAt(i);
            float childCenter = child.getLeft() + child.getWidth() / 2f;
            float distance = Math.abs(childCenter - viewportCenter);

            float t = 1f - Math.min(1f, distance / influence);
            // smoothstep: easeInOut continuo según se acerca al centro.
            float eased = t * t * (3f - 2f * t);
            float scale = 0.82f + 0.78f * eased;

            child.setScaleX(scale);
            child.setScaleY(scale);
            child.setAlpha(0.78f + 0.22f * eased);
            child.setTranslationZ(dp(12) * eased);
        }
    }

    private void previewStation(int index) {
        if (index < 0 || index >= stations.size()) return;
        Station s = stations.get(index);

        stationNameView.setText(s.name);
        sourceView.setText(s.source);
        frequencyView.setText(s.frequency);

        int logoRes = logoResFor(s);
        stationAccent = logoRes != 0 ? extractAccentColor(logoRes) : NAVY;
        updateSourcePill();
    }

    private void scrollToIndex(int index, boolean smooth) {
        if (gridScroll == null || gridRow == null) return;

        View target = null;
        for (int i = 0; i < gridRow.getChildCount(); i++) {
            View child = gridRow.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof Integer && ((Integer) tag) == index) {
                target = child;
                break;
            }
        }
        if (target == null) return;

        int x = target.getLeft() + target.getWidth() / 2 - gridScroll.getWidth() / 2;
        // Allow both end stations to reach the center without overscroll.
        int maxX = Math.max(0, gridRow.getWidth() - gridScroll.getWidth());
        x = Math.max(0, Math.min(x, maxX));
        if (smooth) {
            handler.removeCallbacks(carouselSnapRunnable);
            handler.removeCallbacks(carouselScrollUnlockRunnable);
            suppressCarouselSelection = true;
            gridScroll.smoothScrollTo(x, 0);
            handler.postDelayed(carouselScrollUnlockRunnable, 460L);
        } else {
            gridScroll.scrollTo(x, 0);
        }
    }

    private int findNearestStationIndex() {
        if (gridScroll == null || gridRow == null || gridRow.getChildCount() == 0) return -1;

        int viewportCenter = gridScroll.getScrollX() + gridScroll.getWidth() / 2;
        int nearestIndex = -1;
        int nearestDistance = Integer.MAX_VALUE;

        for (int i = 0; i < gridRow.getChildCount(); i++) {
            View child = gridRow.getChildAt(i);
            Object tag = child.getTag();
            if (!(tag instanceof Integer)) continue;

            int childCenter = child.getLeft() + child.getWidth() / 2;
            int distance = Math.abs(childCenter - viewportCenter);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestIndex = (Integer) tag;
            }
        }
        return nearestIndex;
    }

    private void scrollToSelected(boolean smooth) {
        scrollToIndex(stationIndex, smooth);
    }

    private void selectStation(int index, boolean haptic) {
        if (index < 0 || index >= stations.size()) return;

        boolean changed = index != stationIndex;
        stationIndex = index;
        carouselPreviewIndex = index;

        if (changed && haptic) vibrateStationTick();

        markRecent(stations.get(stationIndex));
        renderStation();
        scrollToSelected(true);
        updateCarouselTransforms();

        if (changed) {
            shouldPlay = true;
            playCurrentStation(false);
        }
    }

    private void vibrateStationTick() {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(
                        24L, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(24L);
            }
        } catch (Exception ignored) { }
    }

    private void vibrateCarouselTick() {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(
                        12L, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(12L);
            }
        } catch (Exception ignored) { }
    }

    private List<Integer> orderedStationIndices() {
        ArrayList<Integer> result = new ArrayList<>();
        Set<Integer> used = new LinkedHashSet<>();
        List<String> recentUrls = loadRecentUrls();

        for (String url : recentUrls) {
            for (int i = 0; i < stations.size(); i++) {
                if (url.equals(stations.get(i).url) && used.add(i)) {
                    result.add(i);
                    break;
                }
            }
        }

        for (int i = 0; i < stations.size(); i++) {
            if (used.add(i)) result.add(i);
        }

        return result;
    }

    private List<String> loadRecentUrls() {
        ArrayList<String> urls = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString(KEY_RECENTS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                String url = a.optString(i, "");
                if (!url.isEmpty() && !urls.contains(url)) urls.add(url);
            }
        } catch (Exception ignored) { }
        return urls;
    }

    private void markRecent(Station station) {
        ArrayList<String> urls = new ArrayList<>(loadRecentUrls());
        urls.remove(station.url);
        urls.add(0, station.url);

        while (urls.size() > 30) urls.remove(urls.size() - 1);

        JSONArray a = new JSONArray();
        for (String url : urls) a.put(url);
        prefs.edit().putString(KEY_RECENTS, a.toString()).apply();
    }

    private void changeStation(int direction) {
        if (stations.isEmpty()) return;
        int next = (stationIndex + direction + stations.size()) % stations.size();
        selectStation(next, true);
    }

    private void togglePlayback() {
        if (stations.isEmpty()) return;
        Station station = stations.get(stationIndex);

        if ("FM".equalsIgnoreCase(station.source)) {
            if (fmController != null && fmController.isPowered()) {
                fmController.stopAsync();
                sourceView.setText("FM");
                updateSourcePill();
                appendDiag("FM interno detenido");
            } else {
                shouldPlay = true;
                playCurrentStation(false);
            }
            return;
        }

        if (player != null) {
            if (player.isPlaying()) {
                player.pause();
                shouldPlay = false;
                appendDiag("PLAYER pausa");
                updatePlayButton(false);
                return;
            } else if (player.getPlaybackState() == Player.STATE_READY) {
                shouldPlay = true;
                player.play();
                appendDiag("PLAYER reanuda");
                updatePlayButton(true);
                return;
            }
        }

        shouldPlay = true;
        playCurrentStation(false);
    }

    private boolean isJackPresent() {
        if (audioManager == null) return false;
        try {
            for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int type = d.getType();
                if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || type == AudioDeviceInfo.TYPE_LINE_ANALOG) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void maximizeMediaVolume() {
        if (audioManager == null || !isJackPresent()) return;
        try {
            int maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (maximum > 0 && audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) < maximum) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maximum, 0);
            }
            android.util.Log.i("RadioCaribeAudio", "jack=YES media="
                    + audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    + "/" + maximum);
        } catch (Exception e) {
            android.util.Log.w("RadioCaribeAudio", "Jack media volume max failed", e);
        }
    }

    private void releaseStreamingBooster() {
        LoudnessEnhancer old = streamingBooster;
        streamingBooster = null;
        if (old != null) {
            try { old.setEnabled(false); } catch (Exception ignored) { }
            try { old.release(); } catch (Exception ignored) { }
        }
    }

    private void updateBoosterForJack() {
        if (streamingBooster == null) return;
        try {
            boolean jack = isJackPresent();
            streamingBooster.setEnabled(jack);
            android.util.Log.i("RadioCaribeAudio", "booster enabled=" + jack
                    + " session=" + persistentStreamSession);
        } catch (Exception e) {
            android.util.Log.w("RadioCaribeAudio", "Could not update jack booster", e);
        }
    }

    private void ensureStreamingBooster(int sessionId) {
        if (sessionId <= 0) return;
        if (streamingBooster != null && persistentStreamSession == sessionId) {
            updateBoosterForJack();
            return;
        }
        releaseStreamingBooster();
        persistentStreamSession = sessionId;
        try {
            LoudnessEnhancer booster = new LoudnessEnhancer(sessionId);
            booster.setTargetGain(STREAM_GAIN_MB);
            streamingBooster = booster;
            updateBoosterForJack();
            android.util.Log.i("RadioCaribeAudio", "session-fixed +" + (STREAM_GAIN_MB / 100f)
                    + "dB session=" + sessionId);
        } catch (Exception e) {
            android.util.Log.w("RadioCaribeAudio", "LoudnessEnhancer unsupported: no boost", e);
        }
    }

    private void playCurrentStation(boolean isRetry) {
        handler.removeCallbacks(retryRunnable);
        releasePlayer();

        if (stations.isEmpty()) return;
        Station station = stations.get(stationIndex);

        if ("FM".equalsIgnoreCase(station.source)) {
            preparing = false;
            shouldPlay = true;
            sourceView.setText("FM");
            updateSourcePill();
            appendDiag("FM interno: preparando " + station.frequency);

            float frequency = 103.4f;
            try {
                String raw = station.url == null ? "" : station.url.replace("fm://", "").trim();
                if (!raw.isEmpty()) frequency = Float.parseFloat(raw);
            } catch (Exception ignored) { }

            final float targetFrequency = frequency;
            if (fmController == null) {
                sourceView.setText("FM ERR");
                updateSourcePill();
                appendDiag("FM ERROR: controlador no inicializado");
                return;
            }

            fmController.tune(targetFrequency, (ok, detail) -> {
                if (stations.isEmpty()) return;
                Station current = stations.get(stationIndex);
                if (!"FM".equalsIgnoreCase(current.source)) return;

                sourceView.setText(ok ? "FM" : "FM ERR");
                updateSourcePill();
                appendDiag(detail);
                if (!ok) {
                    Toast.makeText(this,
                            detail != null && (detail.contains("ROOT") || detail.contains("su:"))
                                    ? "La FM necesita permiso root para RadioCaribe. Autorízalo en el teléfono."
                                    : "FM interno no pudo iniciar. Pulsa NET para ver el punto exacto.",
                            Toast.LENGTH_LONG).show();
                }
            });
            return;
        }

        // Also cancel FM connection retries if the root service was still starting.
        if (fmController != null) {
            fmController.stopAsync();
        }

        if (!isNetworkAvailable()) {
            preparing = false;
            updatePlayButton(false);
            sourceView.setText("SIN RED");
            updateSourcePill();
            appendDiag("RED: sin conexión");
            handler.postDelayed(retryRunnable, 5000L);
            return;
        }

        preparing = true;
        connectStartMs = System.currentTimeMillis();
        sourceView.setText("ABRIENDO");
        updateSourcePill();
        appendDiag((isRetry ? "RETRY " : "OPEN  ") + station.name);
        updatePlayButton(true);

        maximizeMediaVolume();
        ExoPlayer p = new ExoPlayer.Builder(this).build();
        player = p;
        // Reuse the SAME AudioTrack session across station changes, instead of
        // releasing/recreating the DSP and causing a loudness drop every time.
        if (persistentStreamSession <= 0 && audioManager != null) {
            persistentStreamSession = audioManager.generateAudioSessionId();
        }
        if (persistentStreamSession > 0) {
            p.setAudioSessionId(persistentStreamSession);
            ensureStreamingBooster(persistentStreamSession);
        }
        p.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onAudioSessionIdChanged(AnalyticsListener.EventTime eventTime, int audioSessionId) {
                if (player == p && audioSessionId > 0) ensureStreamingBooster(audioSessionId);
            }
        });

        p.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (p != player) return;

                if (state == Player.STATE_BUFFERING) {
                    sourceView.setText("BUFFER");
                    updateSourcePill();
                    appendDiag("PLAYER buffer");
                } else if (state == Player.STATE_READY) {
                    maximizeMediaVolume();
                    preparing = false;
                    long elapsed = System.currentTimeMillis() - connectStartMs;
                    sourceView.setText(station.source);
                    updateSourcePill();
                    appendDiag("PLAYER listo: " + elapsed + " ms");
                    updatePlayButton(shouldPlay);
                } else if (state == Player.STATE_ENDED) {
                    appendDiag("PLAYER fin de stream");
                    if (shouldPlay) handler.postDelayed(retryRunnable, 1500L);
                }
            }

            @Override public void onPlayerError(PlaybackException error) {
                if (p != player) return;
                preparing = false;
                sourceView.setText("ERROR");
                updateSourcePill();
                appendDiag("EXO " + error.getErrorCodeName());
                updatePlayButton(false);
                if (shouldPlay) handler.postDelayed(retryRunnable, isRetry ? 8000L : 4500L);
            }
        });

        try {
            requestAudioFocus();
            p.setMediaItem(MediaItem.fromUri(station.url));
            p.setPlayWhenReady(shouldPlay);
            p.prepare();
        } catch (Exception e) {
            preparing = false;
            sourceView.setText("ERROR");
            updateSourcePill();
            appendDiag("ERROR apertura: " + e.getClass().getSimpleName());
            updatePlayButton(false);
            if (shouldPlay) handler.postDelayed(retryRunnable, 5000L);
        }
    }

    private void releasePlayer() {
        // DSP is deliberately NOT released here: the next online station
        // gets the exact same session and gain immediately.
        ExoPlayer old = player;
        player = null;
        preparing = false;
        if (old != null) {
            try { old.stop(); } catch (Exception ignored) { }
            try { old.release(); } catch (Exception ignored) { }
        }
    }

    private void requestAudioFocus() {
        if (audioManager != null) {
            audioManager.requestAudioFocus(focusChange -> {
                if (focusChange < 0 && player != null) {
                    try {
                        if (player.isPlaying()) {
                            player.pause();
                            updatePlayButton(false);
                        }
                    } catch (Exception ignored) { }
                } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN && shouldPlay && player != null) {
                    try {
                        player.play();
                        updatePlayButton(true);
                    } catch (Exception ignored) { }
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
        android.util.Log.i("RadioCaribeFM", line);
        if (diagnosticView == null) return;
        runOnUiThread(() -> {
            String current = diagnosticView.getText().toString();
            String[] lines = current.isEmpty() ? new String[0] : current.split("\n");
            StringBuilder out = new StringBuilder();
            int start = Math.max(0, lines.length - 5);
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

        if ("FM".equalsIgnoreCase(s.source)) {
            appendDiag("FM: no usa Internet");
            return;
        }

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

        int logo = logoResFor(stations.get(stationIndex));
        stationAccent = logo != 0 ? extractAccentColor(logo) : NAVY;

        applyTheme();
        rebuildGrid();
    }

    private void applyTheme() {
        int bg = darkMode ? DARK_BG : LIGHT_BG;
        int text = darkMode ? DARK_TEXT : LIGHT_TEXT;
        int secondary = darkMode ? Color.rgb(198, 210, 225) : Color.rgb(80, 94, 111);

        root.setBackgroundColor(bg);
        clockView.setTextColor(text);
        stationNameView.setTextColor(text);
        frequencyView.setTextColor(secondary);
        themeView.setTextColor(text);
        diagnosticButton.setTextColor(text);
        themeView.setText(darkMode ? "☀" : "☾");

        batteryView.setDark(darkMode);
        wifiView.invalidate();
        if (mainFallbackView != null) mainFallbackView.setDark(darkMode);

        updateSourcePill();
        applyControlColors();

        GradientDrawable diagBg = new GradientDrawable();
        diagBg.setCornerRadius(dp(8));
        diagBg.setColor(darkMode ? Color.argb(248, 8, 22, 39) : Color.argb(248, 255, 255, 255));
        diagBg.setStroke(dp(1), darkMode ? Color.rgb(48, 65, 85) : Color.rgb(210, 217, 226));
        diagnosticView.setBackground(diagBg);
        diagnosticView.setTextColor(darkMode ? Color.rgb(208, 230, 250) : NAVY);

        getWindow().setNavigationBarColor(bg);
        getWindow().setStatusBarColor(bg);
    }

    private void updateSourcePill() {
        if (sourceView == null) return;

        String state = sourceView.getText().toString();
        boolean fm = "FM".equalsIgnoreCase(state);
        boolean error = state.contains("ERROR") || state.contains("SIN RED");
        boolean pending = state.contains("ABRIENDO") || state.contains("BUFFER");

        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(8));

        if (error) {
            pill.setColor(darkMode ? Color.rgb(92, 24, 31) : Color.rgb(255, 220, 224));
            sourceView.setTextColor(darkMode ? Color.rgb(255, 170, 178) : Color.rgb(150, 16, 29));
        } else if (pending) {
            pill.setColor(darkMode ? Color.rgb(74, 59, 15) : Color.rgb(255, 239, 180));
            sourceView.setTextColor(darkMode ? Color.rgb(255, 220, 110) : Color.rgb(120, 87, 0));
        } else if (fm) {
            pill.setColor(darkMode ? Color.rgb(17, 54, 93) : Color.rgb(218, 236, 255));
            sourceView.setTextColor(darkMode ? Color.rgb(125, 193, 255) : Color.rgb(18, 91, 166));
        } else {
            pill.setColor(darkMode ? Color.rgb(36, 78, 28) : Color.rgb(190, 255, 111));
            sourceView.setTextColor(darkMode ? Color.rgb(180, 255, 145) : Color.rgb(15, 78, 17));
        }

        sourceView.setBackground(pill);
    }

    private void applyControlColors() {
        if (previousButton == null) return;

        int c = stationAccent;
        previousButton.setAccent(c, darkMode);
        playButton.setAccent(c, darkMode);
        nextButton.setAccent(c, darkMode);
    }

    private void updatePlayButton(boolean playing) {
        if (playButton == null || stations.isEmpty()) return;

        Station s = stations.get(stationIndex);
        if ("FM".equalsIgnoreCase(s.source)) {
            playButton.setText("FM");
            playButton.setTextSize(18);
        } else {
            playButton.setText(playing ? "Ⅱ" : "▶");
            playButton.setTextSize(playing ? 27 : 38);
        }
    }

    private void updateClock() {
        if (clockView == null) return;

        Locale es = new Locale("es", "CO");
        String value = new SimpleDateFormat("EEEE, d 'de' MMMM | HH:mm", es).format(new Date());
        if (!value.isEmpty()) value = Character.toUpperCase(value.charAt(0)) + value.substring(1);
        clockView.setText(value);
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
        String webUrl = webAdmin == null ? "" : webAdmin.getAddress();
        TextView webHint = makeText(webUrl.isEmpty()
                ? "PANEL WEB: conecta el Moto G3 al Wi-Fi y vuelve a abrir este menú."
                : "PANEL WEB SIN PIN (MISMA RED WI-FI): " + webUrl, 14, Typeface.BOLD);
        webHint.setPadding(0, dp(5), 0, dp(12));
        box.addView(webHint);

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
        box.addView(m3u, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));

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

            String source = newUrl.startsWith("fm://") ? "FM" : "ONLINE";
            stations.set(stationIndex, new Station(
                    initialsFor(newName),
                    newName,
                    newFreq.isEmpty() ? source : newFreq,
                    source,
                    newUrl,
                    current.id));

            saveStations();
            markRecent(stations.get(stationIndex));
            renderStation();
            rebuildGrid();

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
            markRecent(stations.get(stationIndex));
            renderStation();
            rebuildGrid();

            shouldPlay = true;
            playCurrentStation(false);
            dialog.dismiss();
            Toast.makeText(this, "Emisora eliminada", Toast.LENGTH_SHORT).show();
        });

        importButton.setOnClickListener(v -> {
            int added = importM3u(m3u.getText().toString());
            if (added > 0) {
                saveStations();
                rebuildGrid();
                Toast.makeText(this, added + " emisora(s) importada(s)", Toast.LENGTH_SHORT).show();
                m3u.setText("");
            } else {
                Toast.makeText(this, "No encontré entradas válidas", Toast.LENGTH_SHORT).show();
            }
        });

        reset.setOnClickListener(v -> {
            addDefaultStations();
            stationIndex = 0;
            prefs.edit().remove(KEY_RECENTS).apply();
            markRecent(stations.get(stationIndex));

            renderStation();
            rebuildGrid();

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
            } else if (!line.startsWith("#") &&
                    (line.startsWith("http://") || line.startsWith("https://"))) {

                String name = pendingName == null || pendingName.isEmpty()
                        ? "RADIO IMPORTADA " + (added + 1)
                        : pendingName;

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
                v.setAlpha(0.55f);

                if (!adminGestureLatched &&
                        controlDown[0] && controlDown[1] && controlDown[2]) {
                    adminGestureLatched = true;
                    showAdminPanel();
                }
                return true;
            }

            if (actionCode == MotionEvent.ACTION_UP ||
                    actionCode == MotionEvent.ACTION_CANCEL) {

                boolean execute = !adminGestureLatched &&
                        actionCode == MotionEvent.ACTION_UP;

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

    private class ColorButton extends TextView {
        private final boolean primary;
        private int accent = NAVY;

        ColorButton(Context context, String text, boolean primary) {
            super(context);
            this.primary = primary;
            setText(text);
            setGravity(Gravity.CENTER);
            setTypeface(Typeface.DEFAULT_BOLD);
            setIncludeFontPadding(false);
            setClickable(true);
            setFocusable(true);

            if (primary) {
                setTextSize(38);
                setElevation(dp(5));
            } else {
                setTextSize(35);
            }
        }

        void setAccent(int color, boolean dark) {
            accent = color;

            float[] hsv = new float[3];
            Color.colorToHSV(accent, hsv);
            if (dark && hsv[2] < 0.72f) {
                hsv[2] = 0.86f;
                accent = Color.HSVToColor(hsv);
            }

            if (primary) {
                GradientDrawable gd = new GradientDrawable();
                gd.setShape(GradientDrawable.OVAL);
                gd.setColor(accent);
                setBackground(gd);

                float luminance = (0.299f * Color.red(accent) +
                        0.587f * Color.green(accent) +
                        0.114f * Color.blue(accent)) / 255f;
                setTextColor(luminance > 0.72f ? NAVY : Color.WHITE);
            } else {
                setBackgroundColor(Color.TRANSPARENT);
                setTextColor(accent);
            }
        }
    }

    private class StationBadgeView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String initials = "RC";
        private boolean dark;
        private int accent = NAVY;

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

        void setAccent(int value) {
            accent = value;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float radius = Math.min(w, h) * 0.46f;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(accent);
            canvas.drawCircle(cx, cy, radius, paint);

            paint.setTypeface(Typeface.create("sans", Typeface.BOLD));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(radius * 0.66f);
            paint.setColor(Color.WHITE);

            Paint.FontMetrics fm = paint.getFontMetrics();
            float base = cy - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(initials, cx, base, paint);
        }
    }

    private class BatteryView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF body = new RectF();
        private int percent = 0;
        private boolean dark;

        BatteryView(Context context) {
            super(context);
        }

        void setPercent(int value) {
            percent = Math.max(0, Math.min(100, value));
            invalidate();
        }

        void setDark(boolean value) {
            dark = value;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            int color = dark ? Color.WHITE : LIGHT_TEXT;
            int w = getWidth();
            int h = getHeight();

            float left = dp(2);
            float top = dp(6);
            float right = w - dp(6);
            float bottom = h - dp(6);

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(color);
            body.set(left, top, right, bottom);
            canvas.drawRoundRect(body, dp(4), dp(4), p);

            p.setStyle(Paint.Style.FILL);
            canvas.drawRect(right + dp(1), top + dp(6), right + dp(4), bottom - dp(6), p);

            p.setTypeface(Typeface.create("sans", Typeface.BOLD));
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(dp(10));
            Paint.FontMetrics fm = p.getFontMetrics();
            float base = h / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(percent + "%", (left + right) / 2f, base, p);
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
            if (!isNetworkAvailable()) c = Color.rgb(145, 145, 145);

            p.setColor(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(2.2f));

            float cx = getWidth() / 2f;
            float cy = getHeight() * 0.72f;

            for (int i = 0; i < 3; i++) {
                float rad = dp(4.5f + i * 4.5f);
                r.set(cx - rad, cy - rad, cx + rad, cy + rad);
                canvas.drawArc(r, 220, 100, false, p);
            }

            p.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy + dp(1), dp(1.8f), p);
        }
    }
}
