package com.android.fmradio;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Moto G3 / LineageOS 16 FM JNI bridge.
 * Preloads the system-matched libc++ before loading the FM JNI library.
 */
public final class FmNative {
    private static boolean loaded = false;
    private static boolean attempted = false;
    private static String loadMessage = "pendiente";

    private FmNative() { }

    public static synchronized boolean load(Context context) {
        if (attempted) return loaded;
        attempted = true;
        String preloadedFrom = "";
        try {
            try {
                System.loadLibrary("c++");
                preloadedFrom = "loadLibrary(c++)";
            } catch (Throwable first) {
                File cpp = new File(context.getApplicationInfo().nativeLibraryDir, "libc++.so");
                if (!cpp.isFile()) cpp = extractCppFromApk(context);
                try {
                    System.load(cpp.getAbsolutePath());
                    preloadedFrom = "load(" + cpp.getAbsolutePath() + ")";
                } catch (Throwable second) {
                    loadMessage = "libc++ ERROR: " + shortError(first) +
                            " | ruta alternativa: " + shortError(second);
                    return false;
                }
            }
            System.loadLibrary("fmjni");
            loaded = true;
            loadMessage = "libc++ OK (" + preloadedFrom + "), libfmjni OK";
        } catch (Throwable error) {
            loaded = false;
            loadMessage = "libc++ " + (preloadedFrom.isEmpty() ? "SIN CARGAR" : "OK") +
                    ", libfmjni ERROR: " + shortError(error);
        }
        return loaded;
    }

    private static File extractCppFromApk(Context context) throws Exception {
        File target = new File(context.getDir("radiocaribe_native", Context.MODE_PRIVATE), "libc++.so");
        try (ZipFile apk = new ZipFile(context.getApplicationInfo().sourceDir)) {
            ZipEntry entry = apk.getEntry("lib/armeabi-v7a/libc++.so");
            if (entry == null) throw new IllegalStateException("libc++.so no incluida en APK");
            try (InputStream input = apk.getInputStream(entry);
                 FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                output.getFD().sync();
            }
        }
        target.setReadable(true, true);
        target.setExecutable(true, true);
        return target;
    }

    private static String shortError(Throwable throwable) {
        Throwable x = throwable;
        while (x.getCause() != null && x.getCause() != x) x = x.getCause();
        return x.getClass().getSimpleName() +
                (x.getMessage() == null ? "" : ": " + x.getMessage());
    }

    public static boolean isLoaded() { return loaded; }
    public static String getLoadMessage() { return loadMessage; }

    public static native boolean openDev();
    public static native boolean closeDev();
    public static native boolean powerUp(float frequency);
    public static native boolean powerDown(int type);
    public static native boolean tune(float frequency);
    public static native float seek(float frequency, boolean isUp);
    public static native short[] autoScan();
    public static native boolean stopScan();
    public static native int setRds(boolean enabled);
    public static native short readRds();
    public static native byte[] getPs();
    public static native byte[] getLrText();
    public static native short activeAf();
    public static native int setMute(boolean mute);
    public static native int isRdsSupport();
    public static native int switchAntenna(int antenna);
}
