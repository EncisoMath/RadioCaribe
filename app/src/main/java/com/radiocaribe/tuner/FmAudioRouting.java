package com.radiocaribe.tuner;

import android.content.Context;
import android.media.AudioManager;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

public final class FmAudioRouting {
    private static AudioManager manager;
    private static Object patch;
    private FmAudioRouting() {}

    public static synchronized String create() {
        if (patch != null) return "already-patched";
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method systemMain = at.getDeclaredMethod("systemMain");
            systemMain.setAccessible(true);
            Object thread = systemMain.invoke(null);
            Method getContext = at.getDeclaredMethod("getSystemContext");
            getContext.setAccessible(true);
            Context context = (Context) getContext.invoke(thread);
            manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (manager == null) return "no-audio-manager";
            Class<?> dev = Class.forName("android.media.AudioDevicePort");
            Class<?> config = Class.forName("android.media.AudioPortConfig");
            Class<?> patchType = Class.forName("android.media.AudioPatch");
            Class<?> system = Class.forName("android.media.AudioSystem");
            int fm = constant(system, "DEVICE_IN_FM_TUNER");
            int hs = constant(system, "DEVICE_OUT_WIRED_HEADSET");
            int hp = constant(system, "DEVICE_OUT_WIRED_HEADPHONE");
            ArrayList<Object> ports = new ArrayList<>();
            Method list = AudioManager.class.getDeclaredMethod("listAudioPorts", ArrayList.class);
            list.setAccessible(true);
            list.invoke(manager, ports);
            Method type = dev.getMethod("type");
            type.setAccessible(true);
            Object source = null, sink = null;
            for (Object port : ports) {
                if (!dev.isInstance(port)) continue;
                int id = ((Number) type.invoke(port)).intValue();
                if (id == fm) source = port;
                else if (id == hs || id == hp) sink = port;
            }
            if (source == null) return "no-FM-input-port";
            if (sink == null) return "no-wired-headset-port";
            Method active = dev.getMethod("activeConfig");
            active.setAccessible(true);
            Object from = active.invoke(source), to = active.invoke(sink);
            if (from == null || to == null) return "inactive-port-config";
            Object patches = Array.newInstance(patchType, 1);
            Object sources = Array.newInstance(config, 1);
            Object sinks = Array.newInstance(config, 1);
            Array.set(sources, 0, from);
            Array.set(sinks, 0, to);
            Method create = AudioManager.class.getDeclaredMethod("createAudioPatch",
                    patches.getClass(), sources.getClass(), sinks.getClass());
            create.setAccessible(true);
            int code = ((Number) create.invoke(manager, patches, sources, sinks)).intValue();
            patch = Array.get(patches, 0);
            return code == 0 && patch != null ? "patched" : "createPatch=" + code;
        } catch (Throwable err) {
            Throwable reason = err;
            while (reason.getCause() != null) reason = reason.getCause();
            return "error-" + reason.getClass().getSimpleName() + "-" + reason.getMessage();
        }
    }

    public static synchronized void release() {
        if (patch == null || manager == null) return;
        try {
            Class<?> type = Class.forName("android.media.AudioPatch");
            Method release = AudioManager.class.getDeclaredMethod("releaseAudioPatch", type);
            release.setAccessible(true);
            release.invoke(manager, patch);
        } catch (Throwable ignored) {}
        patch = null;
    }
    private static int constant(Class<?> c, String name) throws Exception {
        Field field = c.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }
}
