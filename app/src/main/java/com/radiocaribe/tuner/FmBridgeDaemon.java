package com.radiocaribe.tuner;

import android.os.Handler;
import android.os.Looper;
import com.android.fmradio.FmNative;
import java.io.*;
import java.net.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Localhost-only token-authenticated FM hardware bridge for rooted Moto G3.
 * Starts with app_process32 under a real root (u:r:su:s0) ADB shell.
 * Android audio framework calls MUST run on a Looper thread.
 *
 * Legacy Moto JNI uses pthread_cancel on powerDown and aborts under Android 9;
 * soft STOP mutes + releases the audio patch without native powerDown.
 */
public final class FmBridgeDaemon {
    private static final int PORT = 39013;
    private static final String DIR = "/data/local/tmp/radiocaribe-fm/";
    private static final String SECRET = "/data/user/0/com.radiocaribe.tuner/files/fm-bridge-token";
    private static boolean opened, powered, parked;
    private static float tuned;
    private FmBridgeDaemon() {}

    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 0) throw new SecurityException("ADB root required");
        Looper.prepareMainLooper();
        final String secret = readSecret();
        if (secret.length() != 64) throw new SecurityException("Invalid app token");
        System.load(DIR + "libc++.so");
        System.load(DIR + "libfmjni.so");

        Thread serverThread = new Thread(() -> {
            try { listen(secret); }
            catch (Throwable t) {
                System.err.println("FM_BRIDGE_FATAL " + t);
                t.printStackTrace();
            }
        }, "RadioCaribe-FM-IPC");
        serverThread.start();
        Looper.loop();  // AudioPortEventHandler and AudioManager callbacks must be dispatched.
    }

    private static void listen(String secret) throws Exception {
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 4);
            System.out.println("FM_BRIDGE_READY port=" + PORT + " mode=root-looper-v015");
            System.out.flush();
            while (true) {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(8000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
                    PrintWriter output = new PrintWriter(new OutputStreamWriter(client.getOutputStream(), "UTF-8"), true);
                    String message = input.readLine();
                    int split = message == null ? -1 : message.indexOf(' ');
                    if (split < 0 || !equalSecret(secret, message.substring(0, split))) {
                        output.println("ERR authorization");
                        continue;
                    }
                    String command = message.substring(split + 1);
                    String answer;
                    try { answer = execute(command); }
                    catch (Throwable t) { answer = "ERR " + t.getClass().getSimpleName() + " " + t.getMessage(); }
                    output.println(answer);
                    System.out.println("FM_BRIDGE " + command + " => " + answer);
                    System.out.flush();
                } catch (Throwable t) {
                    System.err.println("FM_BRIDGE_CLIENT " + t);
                }
            }
        }
    }

    private static String readSecret() throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(SECRET), "US-ASCII"))) {
            return r.readLine().trim();
        }
    }

    private static boolean equalSecret(String expected, String received) {
        if (expected.length() != received.length()) return false;
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) diff |= expected.charAt(i) ^ received.charAt(i);
        return diff == 0;
    }

    private static String audioOnMainThread(boolean release) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (release) { FmAudioRouting.release(); return "released"; }
            return FmAudioRouting.create();
        }
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("audio-not-called");
        Handler main = new Handler(Looper.getMainLooper());
        if (!main.post(() -> {
            try {
                if (release) {
                    FmAudioRouting.release();
                    result.set("released");
                } else {
                    result.set(FmAudioRouting.create());
                }
            } catch (Throwable t) {
                result.set("error-" + t.getClass().getSimpleName() + "-" + t.getMessage());
            } finally {
                finished.countDown();
            }
        })) return "audio-handler-post-failed";
        try {
            if (!finished.await(6500, TimeUnit.MILLISECONDS)) return "audio-handler-timeout";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "audio-handler-interrupted";
        }
        return result.get();
    }

    private static String execute(String command) {
        if ("PING".equals(command)) {
            return "OK root=" + android.os.Process.myUid() + " tuned=" + tuned + " parked=" + parked;
        }
        if ("STOP".equals(command)) {
            if (powered) {
                try { FmNative.setMute(true); } catch (Throwable ignored) {}
                String route = audioOnMainThread(true);
                parked = true;
                tuned = 0;
                // Do not invoke powerDown() or closeDev(). On this Android 9 ROM
                // the vendor JNI does an invalid pthread_cancel and aborts.
                // Root daemon retains the FM device, muted, until the next tune.
                return "OK muted=1 route=" + route + " hardware=parked";
            }
            return "OK already-parked";
        }
        if ("SHUTDOWN".equals(command)) {
            try { if (powered) FmNative.setMute(true); } catch (Throwable ignored) {}
            audioOnMainThread(true);
            // Kill without running the buggy vendor JNI shutdown code.
            Thread terminate = new Thread(() -> {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                android.os.Process.killProcess(android.os.Process.myPid());
            }, "RadioCaribe-FM-SHUTDOWN");
            terminate.start();
            return "OK terminating";
        }
        if (command.startsWith("MUTE ")) {
            if (!powered) return "OK not-powered";
            boolean mute = "1".equals(command.substring(5).trim());
            if (parked && !mute) return "ERR tuner parked";
            return "OK muted=" + FmNative.setMute(mute);
        }
        if (command.startsWith("TUNE ")) {
            float freq = Float.parseFloat(command.substring(5).trim());
            if (freq < 87.5f || freq > 108f) return "ERR frequency-out-of-range";
            if (!opened) {
                opened = FmNative.openDev();
                if (!opened) return "ERR root-openDev=false";
            }
            boolean ok = powered ? FmNative.tune(freq) : FmNative.powerUp(freq);
            if (!ok) return "ERR native-tune=false";
            powered = true;
            tuned = freq;
            parked = false;
            String audio = audioOnMainThread(false);
            try { FmNative.setMute(false); } catch (Throwable ignored) {}
            return "OK freq=" + freq + " audio=" + audio;
        }
        return "ERR command-unsupported";
    }
}
