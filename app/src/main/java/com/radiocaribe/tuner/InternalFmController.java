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

    private final AudioManager.OnAudioFocusChangeListener focusListener = focusChange -> {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            try {
                if (powered) FmNative.setMute(true);
            } catch (Throwable ignored) { }
        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            try {
                if (powered) FmNative.setMute(false);
            } catch (Throwable ignored) { }
        }
    };

    public InternalFmController(Context context, Logger logger) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.logger = logger;
    }

    public boolean isPowered() {
        return powered;
    }

    public void tune(final float frequency, final Callback callback) {
        new Thread(() -> {
            boolean ok = false;
            String detail;
            try {
                log("FM JNI: " + FmNative.getLoadMessage());
                if (!FmNative.isLoaded()) {
                    throw new IllegalStateException("libfmjni no disponible");
                }

                if (!deviceOpen) {
                    log("FM: abriendo dispositivo");
                    deviceOpen = FmNative.openDev();
                    if (!deviceOpen) throw new IllegalStateException("openDev=false");
                    log("FM: dispositivo abierto");
                }

                try {
                    int antenna = FmNative.switchAntenna(0);
                    log("FM: antena larga ret=" + antenna);
                } catch (Throwable t) {
                    log("FM: antena " + shortError(t));
                }

                requestFocus();

                if (!powered) {
                    log("FM: powerUp " + frequency);
                    powered = FmNative.powerUp(frequency);
                    if (!powered) throw new IllegalStateException("powerUp=false");
                } else {
                    log("FM: tune " + frequency);
                    if (!FmNative.tune(frequency)) {
                        throw new IllegalStateException("tune=false");
                    }
                }

                try { FmNative.setMute(true); } catch (Throwable ignored) { }

                boolean routeOk = createAudioPatchReflective();
                if (routeOk) {
                    log("FM AUDIO: patch FM→jack OK");
                } else {
                    log("FM AUDIO: tuner OK, patch no disponible");
                }

                try { FmNative.setRds(true); } catch (Throwable ignored) { }
                try { FmNative.setMute(false); } catch (Throwable ignored) { }

                ok = true;
                detail = routeOk ? "FM 103.4 OK" : "FM 103.4 SINTONIZADO · AUDIO PENDIENTE";
            } catch (Throwable t) {
                detail = "FM ERROR: " + shortError(t);
                log(detail);
                stopInternal();
            }

            final boolean result = ok;
            final String resultDetail = detail;
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(result, resultDetail));
            }
        }, "RadioCaribe-FM").start();
    }

    public void stopAsync() {
        new Thread(this::stopInternal, "RadioCaribe-FM-stop").start();
    }

    public void stop() {
        stopInternal();
    }

    private synchronized void stopInternal() {
        try {
            if (powered) {
                try { FmNative.setMute(true); } catch (Throwable ignored) { }
                try { FmNative.setRds(false); } catch (Throwable ignored) { }
            }
            releaseAudioPatchReflective();
            if (powered) {
                try { FmNative.powerDown(0); } catch (Throwable ignored) { }
            }
            powered = false;
            if (deviceOpen) {
                try { FmNative.closeDev(); } catch (Throwable ignored) { }
            }
            deviceOpen = false;
            abandonFocus();
        } catch (Throwable ignored) { }
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
