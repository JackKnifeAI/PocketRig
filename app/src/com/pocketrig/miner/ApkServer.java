package com.pocketrig.miner;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

/** Tiny HTTP server that serves this app's own APK so a nearby phone can
 *  install it by scanning a QR of the URL (same Wi-Fi or hotspot). */
public class ApkServer {
    private ServerSocket server;
    private Thread thread;
    private final String apkPath;
    private volatile boolean running;
    public final int port;

    public ApkServer(String apkPath, int port) { this.apkPath = apkPath; this.port = port; }

    public void start() throws IOException {
        server = new ServerSocket(port);
        running = true;
        thread = new Thread(new Runnable() { public void run() { loop(); } }, "apk-server");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignore) {}
    }

    public boolean isRunning() { return running; }

    private void loop() {
        while (running) {
            Socket s = null;
            try { s = server.accept(); serve(s); }
            catch (Exception e) { /* closed or client error */ }
            finally { try { if (s != null) s.close(); } catch (Exception ignore) {} }
        }
    }

    private void serve(Socket s) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
        String reqLine = in.readLine();
        while (true) { String h = in.readLine(); if (h == null || h.isEmpty()) break; } // drain headers
        OutputStream out = s.getOutputStream();
        String path = (reqLine != null && reqLine.contains(" ")) ? reqLine.split(" ")[1] : "/";
        File apk = new File(apkPath);
        if (path.startsWith("/PocketRig.apk")) {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\n"
                + "Content-Disposition: attachment; filename=PocketRig.apk\r\n"
                + "Content-Length: " + apk.length() + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            FileInputStream fis = new FileInputStream(apk);
            byte[] buf = new byte[65536]; int r;
            while ((r = fis.read(buf)) > 0) out.write(buf, 0, r);
            fis.close();
        } else {
            String html = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<body style='font-family:sans-serif;background:#0b0e13;color:#eaf1f8;text-align:center;padding:44px 24px'>"
                + "<h2 style='letter-spacing:.1em'>POCKET<span style='color:#FF7A1A'>RIG</span></h2>"
                + "<p style='color:#8394ab'>On-device Monero miner</p>"
                + "<a href='/PocketRig.apk' style='display:inline-block;margin-top:20px;padding:16px 30px;"
                + "background:#FF7A1A;color:#000;border-radius:14px;text-decoration:none;font-weight:700'>Download APK</a>"
                + "<p style='color:#5c6b82;font-size:12px;margin-top:28px'>If prompted, allow \"install unknown apps\".</p></body>";
            byte[] body = html.getBytes("UTF-8");
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                + body.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            out.write(body);
        }
        out.flush();
    }
}
