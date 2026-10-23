package com.android.fmradio;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * FM JNI bridge for Moto G3 / LineageOS 16.
 * Library loading is explicit: libc++.so MUST be loaded before libfmjni.so.
 * The exact binaries are bundled inside the installable APK.
 */
public final class FmNative {
    private static volatile boolean loaded;
    private static boolean attempted;
    private static String loadMessage = "No inicializada";

    private FmNative() {}

    public static synchronized boolean load(Context context) {
        if (attempted) return loaded;
        attempted = true;
        ApplicationInfo info = context.getApplicationInfo();
        String ordinaryError = "";

        // Standard Android classloader path (lib/armeabi-v7a extracted at install).
        try {
            System.loadLibrary("c++");
            System.loadLibrary("fmjni");
            loaded = true;
            loadMessage = "OK: libc++ y libfmjni instaladas en " + info.nativeLibraryDir;
            return true;
        } catch (Throwable t) {
            ordinaryError = concise(t);
        }

        // Recovery if the native lib dir did not expose the bundled libraries:
        // extract BOTH supplied ARM32 files from our own APK and explicitly load
        // libc++ first, then FM JNI. No /system partition writes are made.
        try {
            File directory = context.getDir("radiocaribe_fm_native", Context.MODE_PRIVATE);
            File cpp = extract(context, directory, "libc++.so");
            File fm = extract(context, directory, "libfmjni.so");
            System.load(cpp.getAbsolutePath());
            System.load(fm.getAbsolutePath());
            loaded = true;
            loadMessage = "OK: libc++ + libfmjni cargadas desde copia local";
            return true;
        } catch (Throwable t) {
            loaded = false;
            loadMessage = "Carga normal: " + ordinaryError
                    + " | Copia local: " + concise(t);
            return false;
        }
    }

    private static File extract(Context context, File directory, String name) throws Exception {
        File result = new File(directory, name);
        String insideApk = "lib/armeabi-v7a/" + name;
        try (ZipFile apk = new ZipFile(context.getApplicationInfo().sourceDir)) {
            ZipEntry entry = apk.getEntry(insideApk);
            if (entry == null) throw new IllegalStateException("Falta dentro del APK: " + insideApk);

            File temp = new File(directory, name + ".part");
            try (InputStream input = apk.getInputStream(entry);
                 FileOutputStream output = new FileOutputStream(temp, false)) {
                byte[] buffer = new byte[16384];
                int size;
                while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
                output.getFD().sync();
            }
            if (result.exists() && !result.delete()) {
                throw new IllegalStateException("No se puede reemplazar " + result.getAbsolutePath());
            }
            if (!temp.renameTo(result)) {
                throw new IllegalStateException("No se puede preparar " + result.getAbsolutePath());
            }
        }
        if (!result.setReadable(true, true) || !result.setExecutable(true, true)) {
            throw new IllegalStateException("Sin permiso de lectura/ejecucion: " + name);
        }
        return result;
    }

    private static String concise(Throwable error) {
        Throwable e = error;
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
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
