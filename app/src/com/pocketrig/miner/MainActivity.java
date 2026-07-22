package com.pocketrig.miner;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebSettings;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/** Hosts the WebView UI and bridges it to MinerService. */
public class MainActivity extends Activity {

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;   // for the screenshot QR picker
    private ApkServer apkServer;
    private static final int FILE_REQ = 7;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestPermissionsIfNeeded();
        askIgnoreBatteryOptimizations();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);   // allow camera preview to play
        s.setAllowFileAccess(true);
        web.setBackgroundColor(0xFF0B0E13);
        web.addJavascriptInterface(new Bridge(), "PocketRig");

        // Grant the WebView camera access (for the QR scanner) and wire the file
        // chooser (for scanning a QR from a screenshot).
        web.setWebChromeClient(new WebChromeClient() {
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() { public void run() { request.grant(request.getResources()); } });
            }
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> cb, FileChooserParams params) {
                fileCallback = cb;
                try {
                    Intent i = params.createIntent();
                    startActivityForResult(i, FILE_REQ);
                } catch (Exception e) { fileCallback = null; return false; }
                return true;
            }
        });

        web.loadUrl("file:///android_asset/ui/index.html");
        setContentView(web);
    }

    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == FILE_REQ && fileCallback != null) {
            Uri[] result = (res == RESULT_OK && data != null && data.getData() != null)
                    ? new Uri[]{ data.getData() } : null;
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                java.util.ArrayList<String> want = new java.util.ArrayList<String>();
                want.add("android.permission.CAMERA");
                if (Build.VERSION.SDK_INT >= 33) want.add("android.permission.POST_NOTIFICATIONS");
                requestPermissions(want.toArray(new String[0]), 1);
            } catch (Exception ignore) {}
        }
    }

    private void askIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception ignore) {}
        }
    }

    private void startMiner() {
        Intent i = new Intent(this, MinerService.class).setAction("START");
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private void stopMiner() {
        startService(new Intent(this, MinerService.class).setAction("STOP"));
    }

    /** First private (192./10./172.) IPv4 on an up, non-loopback interface (Wi-Fi or hotspot). */
    private String getLocalIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface nif = ifs.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address) {
                        String ip = a.getHostAddress();
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) return ip;
                    }
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    public void onDestroy() {
        if (apkServer != null) { apkServer.stop(); apkServer = null; }
        super.onDestroy();
    }

    /** Methods callable from the page's JS as window.PocketRig.* */
    public class Bridge {
        @JavascriptInterface public void start() { startMiner(); }
        @JavascriptInterface public void stop() { stopMiner(); }
        @JavascriptInterface public void setMode(String m) { Prefs.put(MainActivity.this, "mode", m); }
        @JavascriptInterface public void setWallet(String w) { Prefs.put(MainActivity.this, "wallet", w); }
        @JavascriptInterface public void setPool(String p) { Prefs.put(MainActivity.this, "pool", p); }
        @JavascriptInterface public void setTor(boolean on) { Prefs.putBool(MainActivity.this, "tor", on); }
        @JavascriptInterface public void setAutoCharge(boolean on) { Prefs.putBool(MainActivity.this, "autocharge", on); }

        /** Start serving this app's APK on the LAN; returns {"url":...} or {"error":...}. */
        @JavascriptInterface public String shareApp() {
            try {
                if (apkServer == null || !apkServer.isRunning()) {
                    apkServer = new ApkServer(getPackageCodePath(), 8777);
                    apkServer.start();
                }
                String ip = getLocalIp();
                if (ip == null) return "{\"error\":\"Connect to Wi-Fi or turn on a hotspot first.\"}";
                return "{\"url\":\"http://" + ip + ":8777/\"}";
            } catch (Exception e) { return "{\"error\":\"" + e.getMessage() + "\"}"; }
        }

        @JavascriptInterface public String status() {
            if (MinerService.RUNNING) return MinerService.LATEST_STATUS;
            Prefs p = Prefs.read(MainActivity.this);
            return "{\"mining\":false,\"mode\":\"" + p.mode
                    + "\",\"wallet\":\"" + p.wallet
                    + "\",\"autoCharge\":" + p.autoCharge + "}";
        }
    }
}
