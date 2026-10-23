package com.radiocaribe.tuner;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.security.SecureRandom;

public final class FmBridgeClient {
    private static final int PORT = 39013;
    private static final String TOKEN_FILE = "fm-bridge-token";

    private FmBridgeClient() {}

    public static synchronized void prepare(Context ctx) {
        try {
            File target = new File(ctx.getFilesDir(), TOKEN_FILE);
            if (target.isFile() && target.length() == 64) return;
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : raw) {
                hex.append(Character.forDigit((b >> 4) & 15, 16));
                hex.append(Character.forDigit(b & 15, 16));
            }
            try (FileOutputStream out = new FileOutputStream(target, false)) {
                out.write(hex.toString().getBytes("US-ASCII"));
                out.getFD().sync();
            }
            target.setReadable(false, false);
            target.setWritable(false, false);
            target.setReadable(true, true);
            target.setWritable(true, true);
        } catch (IOException ex) { throw new IllegalStateException("FM bridge pairing failed", ex); }
    }

    public static String command(Context context, String cmd) throws IOException {
        prepare(context);
        File token = new File(context.getFilesDir(), TOKEN_FILE);
        byte[] secret = new byte[64];
        try (FileInputStream stream = new FileInputStream(token)) {
            int n = 0;
            while (n < secret.length) {
                int r = stream.read(secret, n, secret.length - n);
                if (r < 0) break;
                n += r;
            }
            if (n != 64) throw new IOException("Invalid FM secret");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 1500);
            socket.setSoTimeout(4000);
            PrintWriter output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            output.println(new String(secret, "US-ASCII") + " " + cmd);
            String response = input.readLine();
            if (response == null) throw new IOException("FM root bridge disconnected");
            return response;
        }
    }
}
