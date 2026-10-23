package com.radiocaribe.tuner;

import android.content.Context;
import android.util.Log;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.*;

/** Embedded, Wi-Fi-bound administration server. No remote Internet listener. */
final class WebAdminServer {
    interface Controller {
        JSONObject execute(String operation, JSONObject data) throws Exception;
        File findLogo(String id);
    }

    private final Context context;
    private final android.os.Handler mainHandler;
    private final Controller controller;
    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private volatile String address = "";
    private Thread thread;
    private final ExecutorService clients = Executors.newFixedThreadPool(2);

    WebAdminServer(Context context, android.os.Handler mainHandler, Controller controller) {
        this.context = context.getApplicationContext();
        this.mainHandler = mainHandler;
        this.controller = controller;
    }
    String getAddress() { return address; }
    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::runServer, "RadioCaribe-WebAdmin");
        thread.setDaemon(true);
        thread.start();
    }
    void stop() {
        running = false;
        address = "";
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        clients.shutdownNow();
    }
    private static InetAddress wifiAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.getName().toLowerCase(Locale.ROOT).startsWith("wlan") ||
                        !network.isUp() || network.isLoopback()) continue;
                Enumeration<InetAddress> ips = network.getInetAddresses();
                while (ips.hasMoreElements()) {
                    InetAddress ip = ips.nextElement();
                    if (ip instanceof Inet4Address && !ip.isLoopbackAddress() &&
                            !ip.isLinkLocalAddress()) return ip;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
    private void runServer() {
        while (running) {
            try {
                InetAddress bindIp = wifiAddress();
                if (bindIp == null) { address = ""; Thread.sleep(2000); continue; }
                ServerSocket srv = new ServerSocket();
                serverSocket = srv;
                srv.setReuseAddress(true);
                srv.bind(new InetSocketAddress(bindIp, 8080), 8);
                srv.setSoTimeout(1500);
                address = "http://" + bindIp.getHostAddress() + ":8080";
                Log.i("RadioCaribeWeb", "Wi-Fi admin available: " + address);
                while (running && bindIp.equals(wifiAddress())) {
                    try {
                        Socket client = srv.accept();
                        try { clients.execute(() -> handle(client)); }
                        catch (RejectedExecutionException e) { client.close(); }
                    } catch (SocketTimeoutException ignored) { }
                }
                srv.close();
            } catch (Exception e) {
                if (running) Log.w("RadioCaribeWeb", "Wi-Fi listener retry: " + e);
            } finally {
                address = "";
                try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
                serverSocket = null;
            }
            if (running) try { Thread.sleep(1800); } catch (InterruptedException ignored) { break; }
        }
    }
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b, previous = -1;
        while ((b = in.read()) != -1) {
            if (b == 10) break;
            if (b != 13) buf.write(b);
            if (buf.size() > 8192) throw new IOException("HTTP header too long");
            previous = b;
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void answer(OutputStream out, int status, String type, byte[] body) throws IOException {
        String label = status == 200 ? "OK" : status == 400 ? "Bad Request" :
                status == 403 ? "Forbidden" : status == 404 ? "Not Found" :
                status == 413 ? "Payload Too Large" : "Server Error";
        String header = "HTTP/1.1 " + status + " " + label + "\r\n" +
                "Content-Type: " + type + "\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Connection: close\r\nCache-Control: no-store\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "X-Frame-Options: DENY\r\n\r\n";
        out.write(bytes(header));
        out.write(body);
        out.flush();
    }
    private static void error(OutputStream out, int status, String message) throws IOException {
        JSONObject j = new JSONObject();
        try { j.put("ok", false).put("error", message); } catch (Exception ignored) {}
        answer(out, status, "application/json; charset=utf-8", bytes(j.toString()));
    }
    private JSONObject uiCall(String operation, JSONObject data) throws Exception {
        FutureTask<JSONObject> task = new FutureTask<>(() -> controller.execute(operation, data));
        mainHandler.post(task);
        return task.get(6, TimeUnit.SECONDS);
    }
    private static byte[] loadFile(File file, int max) throws IOException {
        if (!file.isFile() || file.length() > max) throw new IOException("image unavailable");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > max) throw new IOException("Image too large");
            }
        }
        return out.toByteArray();
    }
    private void handle(Socket socket) {
        try (Socket peer = socket) {
            peer.setSoTimeout(8000);
            InputStream in = new BufferedInputStream(peer.getInputStream());
            OutputStream out = new BufferedOutputStream(peer.getOutputStream());
            String first = line(in);
            String[] parts = first.split(" ");
            if (parts.length < 2) { error(out, 400, "Invalid request"); return; }
            String method = parts[0], path = parts[1].split("\\?")[0];
            int length = -1; String marker = "";
            for (int i = 0; i < 70; i++) {
                String h = line(in);
                if (h.isEmpty()) break;
                int colon = h.indexOf(':');
                if (colon < 0) continue;
                String key = h.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String val = h.substring(colon + 1).trim();
                if (key.equals("content-length")) length = Integer.parseInt(val);
                if (key.equals("x-radiocaribe-admin")) marker = val;
            }
            if ("GET".equals(method) && (path.equals("/") || path.equals("/index.html"))) {
                try (InputStream asset = context.getAssets().open("web-admin.html")) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] block = new byte[8192]; int n;
                    while ((n = asset.read(block)) > 0) buf.write(block, 0, n);
                    answer(out, 200, "text/html; charset=utf-8", buf.toByteArray());
                }
            } else if ("GET".equals(method) && path.equals("/api/stations")) {
                answer(out, 200, "application/json; charset=utf-8", bytes(uiCall("list", new JSONObject()).toString()));
            } else if ("GET".equals(method) && path.startsWith("/api/logo/")) {
                String id = path.substring("/api/logo/".length());
                if (!id.matches("[a-zA-Z0-9-]{8,60}")) { error(out, 400, "Invalid id"); return; }
                File file = controller.findLogo(id);
                if (file == null || !file.isFile()) { error(out, 404, "Logo not found"); return; }
                answer(out, 200, "image/jpeg", loadFile(file, 1500000));
            } else if ("POST".equals(method) && path.startsWith("/api/")) {
                // This custom request header prevents cross-site HTML forms
                // from silently editing stations. No PIN or account is required.
                if (!"1".equals(marker)) { error(out, 403, "Admin header missing"); return; }
                if (length < 0 || length > 1400000) { error(out, 413, "Upload too large"); return; }
                byte[] body = new byte[length]; int offset = 0;
                while (offset < length) {
                    int n = in.read(body, offset, length - offset);
                    if (n < 0) throw new EOFException();
                    offset += n;
                }
                JSONObject payload = new JSONObject(new String(body, StandardCharsets.UTF_8));
                String action = path.equals("/api/stations/save") ? "save" :
                        path.equals("/api/stations/delete") ? "delete" :
                        path.equals("/api/stations/move") ? "move" :
                        path.equals("/api/logo/upload") ? "upload" : "";
                if (action.isEmpty()) { error(out, 404, "Unknown endpoint"); return; }
                answer(out, 200, "application/json; charset=utf-8", bytes(uiCall(action, payload).toString()));
            } else error(out, 404, "Not found");
        } catch (Exception e) {
            Log.w("RadioCaribeWeb", "HTTP request failed", e);
            try { error(socket.getOutputStream(), 400, "Request failed: " + e.getClass().getSimpleName()); }
            catch (Exception ignored) {}
        }
    }
}
