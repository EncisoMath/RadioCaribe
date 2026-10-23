package com.radiocaribe.tuner;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import com.android.fmradio.FmNative;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;

/** Privileged bridge started explicitly by the device owner using ADB root. */
public final class FmBridgeDaemon {
    private static boolean opened = false;
    private static boolean powered = false;
    private static int clientUid;
    public static void main(String[] args) throws Exception {
        clientUid = Integer.parseInt(args[0]);
        if (clientUid < 10000) throw new SecurityException("Invalid app UID");
        System.load("/data/local/tmp/radiocaribe-fm/libc++.so");
        System.load("/data/local/tmp/radiocaribe-fm/libfmjni.so");
        System.out.println("FM_BRIDGE_READY uid=" + clientUid);
        try (LocalServerSocket server = new LocalServerSocket("radiocaribe_fm_root")) {
            while (true) {
                try (LocalSocket client = server.accept()) {
                    if (client.getPeerCredentials().getUid() != clientUid) continue;
                    BufferedReader input = new BufferedReader(new InputStreamReader(client.getInputStream()));
                    PrintWriter output = new PrintWriter(client.getOutputStream(), true);
                    String command = input.readLine();
                    String answer;
                    try { answer = execute(command); }
                    catch (Throwable e) { answer = "ERR " + e.getClass().getSimpleName() + ": " + e.getMessage(); }
                    output.println(answer);
                    System.out.println("FM_BRIDGE " + command + " -> " + answer);
                } catch (Throwable error) { System.err.println(error); }
            }
        }
    }
    private static String execute(String command) {
        if (command == null) return "ERR no command";
        if (command.equals("PING")) return "OK ready";
        if (command.equals("STOP")) {
            if (powered) {
                FmNative.setMute(true);
                boolean off = FmNative.powerDown(0);
                powered = false;
                return "OK powerDown=" + off;
            }
            return "OK already stopped";
        }
        if (command.startsWith("TUNE ")) {
            float mhz = Float.parseFloat(command.substring(5));
            if (mhz < 87.5 || mhz > 108.0) return "ERR invalid frequency";
            if (!opened) {
                opened = FmNative.openDev();
                if (!opened) return "ERR openDev=false";
            }
            boolean good = powered ? FmNative.tune(mhz) : FmNative.powerUp(mhz);
            if (!good) return "ERR powerUp/tune failed";
            powered = true;
            FmNative.setMute(false);
            return "OK tuned=" + mhz;
        }
        return "ERR unknown command";
    }
}
