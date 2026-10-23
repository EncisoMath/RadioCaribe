package com.radiocaribe.tuner;

import com.android.fmradio.FmNative;
import java.io.*;
import java.net.*;

public final class FmBridgeDaemon {
    private static final int PORT = 39013;
    private static final String DIR = "/data/local/tmp/radiocaribe-fm/";
    private static final String SECRET = "/data/user/0/com.radiocaribe.tuner/files/fm-bridge-token";
    private static boolean opened, powered;
    private static float tuned;
    private FmBridgeDaemon() {}

    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 0) throw new SecurityException("Root required");
        String secret = readSecret();
        if (secret.length() != 64) throw new SecurityException("Pairing token invalid");
        System.load(DIR + "libc++.so");
        System.load(DIR + "libfmjni.so");
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 4);
        System.out.println("FM_BRIDGE_READY port=" + PORT);
        System.out.flush();
        while (true) {
            try (Socket client = server.accept()) {
                client.setSoTimeout(4000);
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
            } catch (Throwable t) { System.err.println("FM_BRIDGE_CLIENT " + t); }
        }
    }

    private static String readSecret() throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(SECRET), "US-ASCII"))) {
            return r.readLine().trim();
        }
    }

    private static boolean equalSecret(String expected, String received) {
        if (expected.length() != received.length()) return false;
        int difference = 0;
        for (int i = 0; i < expected.length(); i++) difference |= expected.charAt(i) ^ received.charAt(i);
        return difference == 0;
    }

    private static String execute(String command) {
        if ("PING".equals(command)) return "OK root=" + android.os.Process.myUid() + " tuned=" + tuned;
        if ("STOP".equals(command)) {
            if (powered) {
                FmNative.setMute(true);
                FmAudioRouting.release();
                boolean off = FmNative.powerDown(0);
                powered = false;
                tuned = 0;
                // Avoid closeDev: this vendor JNI aborted during previous shutdown.
                return "OK stopped=" + off;
            }
            return "OK stopped";
        }
        if (command.startsWith("MUTE ")) {
            if (!powered) return "OK not powered";
            return "OK muted=" + FmNative.setMute("1".equals(command.substring(5).trim()));
        }
        if (command.startsWith("TUNE ")) {
            float freq = Float.parseFloat(command.substring(5).trim());
            if (freq < 87.5f || freq > 108f) return "ERR frequency";
            if (!opened) {
                opened = FmNative.openDev();
                if (!opened) return "ERR root openDev=false";
            }
            boolean ok = powered ? FmNative.tune(freq) : FmNative.powerUp(freq);
            if (!ok) return "ERR native tune=false";
            powered = true;
            tuned = freq;
            String audio = FmAudioRouting.create();
            FmNative.setMute(false);
            return "OK freq=" + freq + " audio=" + audio;
        }
        return "ERR unsupported";
    }
}
