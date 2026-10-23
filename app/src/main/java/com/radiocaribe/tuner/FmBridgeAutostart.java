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
            File cpp = new File(info.nativeLibraryDir, "libc++.so");
            File fm = new File(info.nativeLibraryDir, "libfmjni.so");
            File apk = new File(info.sourceDir);
            if (!cpp.isFile() || !fm.isFile() || !apk.isFile()) {
                throw new IllegalStateException("Faltan las bibliotecas FM dentro del APK");
            }

            // Every filesystem path is derived from Android application metadata,
            // never from user input. Single-quote escape for shell safety.
            String script =
                    "umask 077; " +
                    "mkdir -p /data/local/tmp/radiocaribe-fm || exit 71; " +
                    "chmod 700 /data/local/tmp/radiocaribe-fm || exit 72; " +
                    "cp " + quote(cpp.getAbsolutePath()) +
                    " /data/local/tmp/radiocaribe-fm/libc++.so || exit 73; " +
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
                                line.contains("not found") || line.contains("error:")) {
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
