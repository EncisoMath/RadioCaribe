package com.android.fmradio;

import java.io.File;

/**
 * Minimal JNI bridge compatible with the LineageOS FMRadio libfmjni ABI.
 * The native library registers methods against this exact class name.
 */
public final class FmNative {
    private static boolean loaded = false;
    private static String loadMessage = "not attempted";

    static {
        Throwable first = null;
        try {
            System.loadLibrary("fmjni");
            loaded = true;
            loadMessage = "System.loadLibrary(fmjni)";
        } catch (Throwable t) {
            first = t;
        }

        if (!loaded) {
            String[] paths = new String[] {
                    "/system/lib/libfmjni.so",
                    "/vendor/lib/libfmjni.so",
                    "/system/vendor/lib/libfmjni.so"
            };
            for (String path : paths) {
                try {
                    if (!new File(path).exists()) continue;
                    System.load(path);
                    loaded = true;
                    loadMessage = path;
                    break;
                } catch (Throwable ignored) { }
            }
        }

        if (!loaded && first != null) {
            loadMessage = first.getClass().getSimpleName() + ": " +
                    String.valueOf(first.getMessage());
        }
    }

    private FmNative() { }

    public static boolean isLoaded() {
        return loaded;
    }

    public static String getLoadMessage() {
        return loadMessage;
    }

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
