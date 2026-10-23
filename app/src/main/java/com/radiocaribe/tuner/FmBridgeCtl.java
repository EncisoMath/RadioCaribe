package com.radiocaribe.tuner;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Owner-side ADB root control tool, separate from the Android application UI. */
public final class FmBridgeCtl {
    private static final String TOKEN = "/data/user/0/com.radiocaribe.tuner/files/fm-bridge-token";
    private FmBridgeCtl() {}

    public static void main(String[] args) throws Exception {
        String cmd = args.length == 0 ? "PING" : args[0];
        if (!("PING".equals(cmd) || "SHUTDOWN".equals(cmd) || "STOP".equals(cmd))) {
            throw new IllegalArgumentException("Unsupported control operation");
        }
        byte[] secret = new byte[64];
        try (FileInputStream in = new FileInputStream(TOKEN)) {
            int n = 0;
            while (n < 64) {
                int r = in.read(secret, n, 64 - n);
                if (r < 0) throw new IllegalArgumentException("Token incomplete");
                n += r;
            }
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 39013), 1500);
            socket.setSoTimeout(9000);
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            out.println(new String(secret, "US-ASCII") + " " + cmd);
            String answer = in.readLine();
            System.out.println("FM_BRIDGE_CONTROL " + cmd + " => " + answer);
        }
    }
}
