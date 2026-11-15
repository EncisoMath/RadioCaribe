package com.radiocaribe.tuner;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * Starts the FM bridge from the app using on-device su privileges.
 * Never changes SELinux policy or /dev/radio0 permissions.
 * Requires user-granted application root (Magisk/Lineage addonsu).
 * Starting the FM tuner is always an explicit user selection.
 */
public final class FmBridgeAutostart {
    public interface Logger { void log(String message); }

    private static final Object LOCK = new Object();
    private static volatile boolean pending;
    private static volatile long lastAttemptMs;
    private static volatile String lastProblem = "";

    private FmBridgeAutostart() {}

    public static String getLastProblem() {
        return lastProblem;
    }

    public static boolean ensureStarting(Context context, Logger logger) {
        synchronized (LOCK) {
            long now = SystemClock.elapsedRealtime();
            if (pending || (lastAttemptMs != 0 && now - lastAttemptMs < 22000)) {
                return pending;
            }
            pending = true;
            lastProblem = "";
            lastAttemptMs = now;
        }

        final Context app = context.getApplicationContext();
        try {
            ApplicationInfo info = app.getApplicationInfo();
            // The FM vendor JNI is bundled in the APK. Android 9 exposes
            // its platform libc++.so under /system/lib on 32-bit devices;
            // that runtime is NOT bundled in RadioCaribe's jniLibs folder.
            // v0.34 wrongly rejected every FM request before it could call su.
            File fm = new File(info.nativeLibraryDir, "libfmjni.so");
            File apk = new File(info.sourceDir);
            if (!fm.isFile()) {
                throw new IllegalStateException("FM nativa no extraída: "
                        + fm.getAbsolutePath() + " (verificar APK/ABI)");
            }
            if (!apk.isFile()) {
                throw new IllegalStateException("Ruta del APK no encontrada");
            }
            File cpp = new File(info.nativeLibraryDir, "libc++.so");
            String cppInstall = cpp.isFile()
                    ? "cp " + quote(cpp.getAbsolutePath())
                        + " /data/local/tmp/radiocaribe-fm/libc++.so || exit 73; "
                    : // Preserve a previously working runtime from the owner's
                      // root FM bridge; otherwise use Android's 32-bit runtime.
                      "if [ -s /data/local/tmp/radiocaribe-fm/libc++.so ]; then :; " +
                      "elif [ -r /system/lib/libc++.so ]; then " +
                      "cp /system/lib/libc++.so /data/local/tmp/radiocaribe-fm/libc++.so || exit 73; " +
                      "elif [ -r /vendor/lib/libc++.so ]; then " +
                      "cp /vendor/lib/libc++.so /data/local/tmp/radiocaribe-fm/libc++.so || exit 73; " +
                      "else echo 'FM_ROOT_ERR: libc++.so ausente en sistema'; exit 76; fi; ";

            // Source paths come exclusively from Android app metadata.
            String script =
                    "umask 077; " +
                    "mkdir -p /data/local/tmp/radiocaribe-fm || exit 71; " +
                    "chmod 700 /data/local/tmp/radiocaribe-fm || exit 72; " +
                    cppInstall +
                    "cp " + quote(fm.getAbsolutePath()) +
                    " /data/local/tmp/radiocaribe-fm/libfmjni.so || exit 74; " +
                    "chmod 600 /data/local/tmp/radiocaribe-fm/libc++.so " +
                    "/data/local/tmp/radiocaribe-fm/libfmjni.so || exit 75; " +
                    "export CLASSPATH=" + quote(apk.getAbsolutePath()) + "; " +
                    "exec /system/bin/app_process32 /system/bin " +
                    "com.radiocaribe.tuner.FmBridgeDaemon";

            // A root manager may show a grant prompt. Never invoke from UI thread.
            Process process = new ProcessBuilder("su", "-c", script)
                    .redirectErrorStream(true).start();
            if (logger != null) logger.log("FM: solicitando acceso root desde RadioCaribe...");
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.contains("FM_BRIDGE_READY")) {
                            if (logger != null) logger.log("FM: controlador root iniciado en el teléfono");
                        } else if (line.contains("FM_BRIDGE_FATAL") ||
                                line.contains("Exception") || line.contains("denied") ||
                                line.contains("not found") || line.contains("error:") ||
                                line.contains("FM_ROOT_ERR")) {
                            if (logger != null) logger.log("FM ROOT: " + trim(line));
                            lastProblem = trim(line);
                        }
                    }
                    int status = process.waitFor();
                    lastProblem = "El controlador root finalizó (código " + status + ")";
                    if (logger != null && status != 0) logger.log("FM ROOT: " + lastProblem);
                } catch (Exception error) {
                    lastProblem = "Root: " + error.getClass().getSimpleName();
                } finally {
                    pending = false;
                }
            }, "RadioCaribe-root-output").start();
            return true;
        } catch (Exception error) {
            pending = false;
            lastProblem = error.getClass().getSimpleName() + ": " +
                    String.valueOf(error.getMessage());
            if (logger != null) logger.log("FM ROOT: " + lastProblem);
            return false;
        }
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String trim(String s) {
        return s.length() > 260 ? s.substring(0, 260) : s;
    }
}
