package com.radiocaribe.tuner;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import com.android.fmradio.FmNative;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class InternalFmController {

    public interface Logger {
        void log(String line);
    }

    public interface Callback {
        void onResult(boolean ok, String detail);
    }

    private final Context context;
    private final AudioManager audioManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Logger logger;

    private volatile boolean deviceOpen = false;
    private volatile boolean powered = false;
    private volatile Object audioPatch = null;
    // A startup FM request may arrive before the ADB-root service is listening.
    // Each station change invalidates outstanding startup retries.
    private final AtomicInteger commandGeneration = new AtomicInteger();
    private volatile boolean wantsFm = false;

    private final AudioManager.OnAudioFocusChangeListener focusListener = focusChange -> {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            try {
                if (powered) muteRootFromFocus(true);
            } catch (Throwable ignored) { }
        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            try {
                if (powered) muteRootFromFocus(false);
            } catch (Throwable ignored) { }
        }
    };

    public InternalFmController(Context context, Logger logger) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.logger = logger;
        FmBridgeClient.prepare(this.context);
    }

    public boolean isPowered() {
        return powered;
    }

    public void tune(final float frequency, final Callback callback) {
        wantsFm = true;
        final int generation = commandGeneration.incrementAndGet();
        new Thread(() -> {
            boolean ok = false;
            String detail = null;
            try {
                String response = null;
                for (int attempt = 0; attempt < 24; attempt++) {
                    if (generation != commandGeneration.get()) return;
                    try {
                        response = FmBridgeClient.command(context, "TUNE " + frequency);
                        break;
                    } catch (java.net.ConnectException notReady) {
                        if (attempt == 0) log("FM ROOT: esperando a que inicie el servicio...");
                        if (attempt == 23) throw notReady;
                        try { Thread.sleep(750L); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }

                // Switching to online or choosing a new station invalidates old FM work.
                if (generation != commandGeneration.get()) {
                    if (!wantsFm) {
                        try { FmBridgeClient.command(context, "STOP"); }
                        catch (Throwable ignored) {}
                    }
                    return;
                }
                log("FM ROOT: " + response);
                if (response == null || !response.startsWith("OK ")) {
                    throw new IllegalStateException(String.valueOf(response));
                }
                powered = true;
                deviceOpen = true;
                requestFocus();
                ok = true;
                detail = "FM " + frequency + " ROOT OK · "
                        + (response.contains("audio=patched") ? "AUDIO OK" : "AUDIO PENDIENTE");
                if (!response.contains("audio=patched")) log("FM AUDIO ROOT: " + response);
            } catch (Throwable err) {
                if (generation != commandGeneration.get()) return;
                String error = shortError(err);
                if (err instanceof java.net.ConnectException) {
                    detail = "FM ROOT no disponible. Inicia el servicio FM de RadioCaribe.";
                } else {
                    detail = "FM ROOT ERROR: " + error;
                }
                log(detail);
                powered = false;
                deviceOpen = false;
            }

            if (generation != commandGeneration.get()) return;
            final boolean success = ok;
            final String result = detail;
            if (callback != null) {
                mainHandler.post(() -> {
                    if (generation == commandGeneration.get()) {
                        callback.onResult(success, result);
                    }
                });
            }
        }, "RadioCaribe-FM-startup").start();
    }

    public void stopAsync() {
        wantsFm = false;
        commandGeneration.incrementAndGet();
        new Thread(this::stopInternal, "RadioCaribe-FM-bridge-stop").start();
    }

    public void stop() {
        stopAsync();
    }

    private synchronized void stopInternal() {
        if (powered) {
            try { log("FM ROOT: " + FmBridgeClient.command(context, "STOP")); }
            catch (Throwable err) { log("FM ROOT STOP: " + shortError(err)); }
        }
        powered = false;
        deviceOpen = false;
        releaseAudioPatchReflective();
        abandonFocus();
    }

    private void muteRootFromFocus(boolean mute) {
        new Thread(() -> {
            try { FmBridgeClient.command(context, mute ? "MUTE 1" : "MUTE 0"); }
            catch (Throwable ignored) { }
        }, "FM-focus-command").start();
    }

    private void requestFocus() {
        if (audioManager == null) return;
        try {
            int result = audioManager.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
            log("FM AUDIO: focus=" + result);
        } catch (Throwable t) {
            log("FM AUDIO: focus " + shortError(t));
        }
    }

    private void abandonFocus() {
        if (audioManager == null) return;
        try { audioManager.abandonAudioFocus(focusListener); } catch (Throwable ignored) { }
    }

    private boolean createAudioPatchReflective() {
        if (audioManager == null) return false;

        try {
            Class<?> audioDevicePortClass = Class.forName("android.media.AudioDevicePort");
            Class<?> audioPortConfigClass = Class.forName("android.media.AudioPortConfig");
            Class<?> audioPatchClass = Class.forName("android.media.AudioPatch");
            Class<?> audioSystemClass = Class.forName("android.media.AudioSystem");

            int fmIn = staticInt(audioSystemClass, "DEVICE_IN_FM_TUNER");
            int wiredHeadset = staticInt(audioSystemClass, "DEVICE_OUT_WIRED_HEADSET");
            int wiredHeadphone = staticInt(audioSystemClass, "DEVICE_OUT_WIRED_HEADPHONE");

            Method listAudioPorts = AudioManager.class.getDeclaredMethod(
                    "listAudioPorts", ArrayList.class);
            listAudioPorts.setAccessible(true);

            ArrayList<Object> ports = new ArrayList<>();
            Object listStatus = listAudioPorts.invoke(audioManager, ports);
            log("FM AUDIO: ports=" + ports.size() + " status=" + String.valueOf(listStatus));

            Object sourcePort = null;
            Object sinkPort = null;

            Method typeMethod = audioDevicePortClass.getDeclaredMethod("type");
            typeMethod.setAccessible(true);

            for (Object port : ports) {
                if (!audioDevicePortClass.isInstance(port)) continue;
                int type = ((Number) typeMethod.invoke(port)).intValue();
                if (type == fmIn) {
                    sourcePort = port;
                } else if (type == wiredHeadset || type == wiredHeadphone) {
                    sinkPort = port;
                }
            }

            if (sourcePort == null) {
                log("FM AUDIO: no DEVICE_IN_FM_TUNER");
                return false;
            }
            if (sinkPort == null) {
                log("FM AUDIO: no jack activo");
                return false;
            }

            Method activeConfig = audioDevicePortClass.getMethod("activeConfig");
            activeConfig.setAccessible(true);
            Object sourceConfig = activeConfig.invoke(sourcePort);
            Object sinkConfig = activeConfig.invoke(sinkPort);

            if (sourceConfig == null || sinkConfig == null) {
                log("FM AUDIO: activeConfig=null");
                return false;
            }

            Object patchArray = Array.newInstance(audioPatchClass, 1);
            Object sourceArray = Array.newInstance(audioPortConfigClass, 1);
            Object sinkArray = Array.newInstance(audioPortConfigClass, 1);
            Array.set(sourceArray, 0, sourceConfig);
            Array.set(sinkArray, 0, sinkConfig);

            Method createAudioPatch = AudioManager.class.getDeclaredMethod(
                    "createAudioPatch",
                    patchArray.getClass(),
                    sourceArray.getClass(),
                    sinkArray.getClass());
            createAudioPatch.setAccessible(true);

            int status = ((Number) createAudioPatch.invoke(
                    audioManager, patchArray, sourceArray, sinkArray)).intValue();

            audioPatch = Array.get(patchArray, 0);
            log("FM AUDIO: createPatch=" + status + " patch=" + (audioPatch != null));
            return status == 0 && audioPatch != null;
        } catch (Throwable t) {
            log("FM AUDIO ERROR: " + shortError(t));
            return false;
        }
    }

    private void releaseAudioPatchReflective() {
        Object patch = audioPatch;
        audioPatch = null;
        if (patch == null || audioManager == null) return;

        try {
            Class<?> audioPatchClass = Class.forName("android.media.AudioPatch");
            Method release = AudioManager.class.getDeclaredMethod(
                    "releaseAudioPatch", audioPatchClass);
            release.setAccessible(true);
            release.invoke(audioManager, patch);
            log("FM AUDIO: patch liberado");
        } catch (Throwable t) {
            log("FM AUDIO release: " + shortError(t));
        }
    }

    private int staticInt(Class<?> cls, String fieldName) throws Exception {
        Field field = cls.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private String shortError(Throwable t) {
        Throwable x = t;
        while (x.getCause() != null && x.getCause() != x) x = x.getCause();
        String message = x.getMessage();
        return x.getClass().getSimpleName() +
                (message == null || message.isEmpty() ? "" : ": " + message);
    }

    private void log(String line) {
        if (logger != null) mainHandler.post(() -> logger.log(line));
    }
}
