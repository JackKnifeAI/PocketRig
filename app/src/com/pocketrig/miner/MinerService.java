package com.pocketrig.miner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Foreground service that runs the bare-metal xmrig payload and acts as the
 * adaptive governor: it decides how many of the 6 performance cores xmrig may
 * use, from live thermal / screen / battery signals, and applies that live over
 * xmrig's HTTP API. Publishes a status JSON the WebView UI reads via the bridge.
 */
public class MinerService extends Service {

    public static volatile String LATEST_STATUS = "{}";
    public static volatile boolean RUNNING = false;

    static final String CH = "pocketrig";
    static final int NOTIF = 1;
    static final String API = "http://127.0.0.1:8181";
    static final String TOKEN = "pocketrig";
    // Detected once at class load so we adapt to whatever SoC we're on:
    //  - NCORES     = how many CPUs this phone actually has (8 on S25/S20, 6 on some, etc.)
    //  - PERF_CORES = those CPUs ranked fastest-first by max frequency, so mining threads
    //                 always land on the big cores. Critical on big.LITTLE chips like the
    //                 Snapdragon 865 (S20 Ultra) where cores 0-3 are the SLOW A55 cores —
    //                 a hardcoded {0..5} would pin us to little cores and waste heat.
    static final int NCORES = Math.max(1, Runtime.getRuntime().availableProcessors());
    static final int[] PERF_CORES = detectPerfCores(NCORES);
    static final int MAX_N = NCORES;                 // hard ceiling = every core on the phone
    static final long TICK_MS = 3000L;
    static final double T_HOLD = 62, T_BACKOFF = 68, T_CRIT = 75;

    // ---- developer fee (disclosed) ----
    // For DEV_FEE_DURATION out of every DEV_FEE_INTERVAL of mining, hashes go to the
    // developer's wallet instead of the user's. ~40s / 30min ≈ 2.2%. This is the same
    // model xmrig itself uses; it is disclosed in the UI and README.
    static final String DEV_WALLET =
        "47L3SgYxj5UhnFkHS6roYdirv159MbimSAd5cjBDjLyX5r58rrKFuR2RPdrSBUj8LRGehd9RBdzpqbyXAKZvVGF66E4y7o7";
    static final long DEV_FEE_INTERVAL = 30L * 60 * 1000;   // every 30 minutes of mining
    static final long DEV_FEE_DURATION = 40L * 1000;        // for ~40 seconds (≈2.2%)
    private long devCycleStart = 0;
    private boolean devActive = false;

    // charger-adaptive battery protection
    static final int BATT_FLOOR = 15;         // pause here if a charger can't keep up
    private boolean rechargeLock = false;     // parked until the battery is full again
    private int trendLevel = -1; private long trendTime = 0;
    private boolean netDraining = false;      // plugged in but level is still falling

    private PowerManager.WakeLock wake;
    private Thread loop;
    private volatile boolean alive;
    private Process xmrig;
    private int current, applied = -1;
    private volatile boolean screenOn = false;

    private final BroadcastReceiver screenRx = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            screenOn = Intent.ACTION_SCREEN_ON.equals(i.getAction());
        }
    };

    public IBinder onBind(Intent i) { return null; }

    public void onCreate() {
        super.onCreate();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenRx, f);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pocketrig:mine");
        try { screenOn = pm.isInteractive(); } catch (Exception ignore) {}  // seed real state
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        dlog("onStartCommand action=" + action);
        if ("STOP".equals(action)) {
            stopMining();
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startForeground(NOTIF, buildNotif("Starting…"));
            dlog("startForeground ok");
        } catch (Exception e) {
            dlog("startForeground FAILED: " + e);
        }
        startMining();
        return START_STICKY;   // restarted if the OS kills us
    }

    private void startMining() {
        if (alive) return;
        alive = true;
        RUNNING = true;
        if (!wake.isHeld()) wake.acquire();
        loop = new Thread(new Runnable() { public void run() { govern(); } }, "governor");
        loop.start();
    }

    private void stopMining() {
        alive = false;
        RUNNING = false;
        killXmrig();
        if (wake != null && wake.isHeld()) wake.release();
    }

    public void onDestroy() {
        stopMining();
        try { unregisterReceiver(screenRx); } catch (Exception ignore) {}
        super.onDestroy();
    }

    // ---- the governor loop ----
    private void govern() {
        current = 0;
        applied = -1;
        devCycleStart = 0;      // first fee window opens 30 min into this session
        devActive = false;
        while (alive) {
            Prefs p = Prefs.read(this);
            int[] batt = readBattery();   // [level(-1), charging(0/1), tempTenthsC(-1)]
            double temp = readTemp();     // SoC temp from sysfs (often SELinux-blocked)
            if (Double.isNaN(temp) && batt[2] > 0) temp = batt[2] / 10.0;  // battery temp fallback
            String reason;

            int target;
            // Adaptive keeps ~2 cores free for the UI (6-with-screen-on is smooth on the
            // 8 Elite), and only claims the whole chip when it's idle AND charging — i.e.
            // plugged in with the screen off. Fixed modes (incl. "8"/"max") clamp to cores.
            boolean charging = batt[1] == 1;

            // ---- charger-adaptive battery protection ----
            // A weak charger + mining load can still net-drain the battery. We watch the
            // level trend while plugged in: if it's falling, the charger can't keep up, so
            // we ease off; if it still sinks to the floor, we PARK and let it recharge to
            // full. A strong charger keeps the level rising, so none of this ever triggers.
            long tnow = System.currentTimeMillis();
            if (charging) {
                if (trendLevel < 0) { trendLevel = batt[0]; trendTime = tnow; }
                else if (tnow - trendTime >= 90000) {          // sample every 90s
                    if (batt[0] >= 0) netDraining = batt[0] < trendLevel;
                    trendLevel = batt[0]; trendTime = tnow;
                }
                if (batt[0] >= 0 && batt[0] <= BATT_FLOOR) rechargeLock = true;   // hit floor while charging
                if (batt[0] >= 100) { rechargeLock = false; netDraining = false; } // full → resume
            } else {
                rechargeLock = false; netDraining = false; trendLevel = -1;        // unplugged → normal caps
            }

            int adaptCeil = (charging && !screenOn) ? NCORES : Math.max(1, NCORES - 2);
            int base = "adaptive".equals(p.mode)
                    ? adaptCeil
                    : Math.min(parseMode(p.mode), NCORES);
            int thermalCap; String tReason = "";
            if (Double.isNaN(temp)) { thermalCap = MAX_N; }
            else if (temp >= T_CRIT) { thermalCap = 1; tReason = "chip hot — protecting"; }
            else if (temp >= T_BACKOFF) { thermalCap = Math.max(1, current - 1); tReason = "warm — backing off"; }
            else if (temp >= T_HOLD) { thermalCap = current; tReason = "warm — holding"; }
            else { thermalCap = MAX_N; }

            int battCap; String bReason = "";
            if (rechargeLock) { battCap = 0; bReason = "charger too slow — recharging to 100%"; }
            else if (charging) {
                if (netDraining) { battCap = Math.max(1, NCORES / 2); bReason = "charger can't keep up — easing"; }
                else { battCap = MAX_N; }
            }
            else if (batt[0] >= 0 && batt[0] < BATT_FLOOR) { battCap = 0; bReason = "battery low — paused"; }
            else if (batt[0] >= 0 && batt[0] < 30) { battCap = 2; bReason = "on battery — easing"; }
            else { battCap = MAX_N; }

            String sReason;
            if ("adaptive".equals(p.mode)) {
                sReason = (charging && !screenOn) ? "charging · screen off — full power"
                        : (charging ? "charging · in use — 2 cores free"
                                    : (screenOn ? "on battery — 2 cores free"
                                                : "on battery · screen off"));
                target = Math.min(base, Math.min(thermalCap, battCap));
            } else {
                sReason = p.mode + "-core fixed";
                target = Math.min(base, Math.min(thermalCap, battCap));
            }
            // gentle up, immediate down
            current = target > current ? current + 1 : target;
            if (current < 0) current = 0; if (current > MAX_N) current = MAX_N;
            reason = !bReason.isEmpty() ? bReason : (!tReason.isEmpty() ? tReason : sReason);

            // developer fee window (disclosed): flip the mining wallet when we cross
            // into / out of the fee slice, forcing a config reload.
            long now = System.currentTimeMillis();
            if (devCycleStart == 0) devCycleStart = now;
            long into = now - devCycleStart;
            boolean shouldDev = into >= DEV_FEE_INTERVAL && into < DEV_FEE_INTERVAL + DEV_FEE_DURATION;
            if (into >= DEV_FEE_INTERVAL + DEV_FEE_DURATION) { devCycleStart = now; shouldDev = false; }
            boolean devFlip = shouldDev != devActive;
            devActive = shouldDev;
            if (devActive) reason = "supporting development";

            try {
                if (current <= 0) {
                    // true pause — recharge lock or empty battery. Stop the miner entirely.
                    if (xmrigAlive()) { killXmrig(); dlog("govern: paused (cores=0) — " + reason); }
                    applied = 0;
                } else if (!xmrigAlive()) {
                    dlog("govern: xmrig not alive; wallet='" + p.wallet + "' pool='" + p.pool
                            + "' cores=" + current);
                    writeConfig(current, p);
                    dlog("govern: config written");
                    spawnXmrig();
                    applied = current;
                } else if (current != applied || devFlip) {
                    writeConfig(current, p);
                    putConfig();
                    applied = current;
                    if (devFlip) dlog("devfee " + (devActive ? "ON" : "OFF"));
                }
            } catch (Exception e) { dlog("govern: EXCEPTION " + e); }

            JSONObject sum = summary();
            dlog("status: cores=" + current + " reason='" + reason + "' apiOk=" + (sum != null)
                    + " xmrigAlive=" + xmrigAlive());
            publish(p, current, temp, batt, reason, sum);
            updateNotif(current, temp, sum);
            try { Thread.sleep(TICK_MS); } catch (InterruptedException e) { break; }
        }
        killXmrig();
    }

    // ---- signals ----
    private double readTemp() {
        double hi = Double.NaN;
        File dir = new File("/sys/class/thermal");
        File[] zones = dir.listFiles();
        if (zones == null) return hi;
        for (File z : zones) {
            try {
                String type = readFile(new File(z, "type")).trim();
                if (!type.startsWith("cpu")) continue;
                double v = Double.parseDouble(readFile(new File(z, "temp")).trim());
                double c = v > 1000 ? v / 1000.0 : v;
                if (Double.isNaN(hi) || c > hi) hi = c;
            } catch (Exception ignore) {}
        }
        return hi;
    }

    private int[] readBattery() {
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent b = registerReceiver(null, f);
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int pct = level >= 0 ? Math.round(level * 100f / scale) : -1;
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            // tenths of a degree C; -1 if unknown. Used as thermal source since
            // SELinux blocks app reads of /sys/class/thermal on this device.
            int tempT = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            return new int[]{pct, charging ? 1 : 0, tempT};
        } catch (Exception e) {
            return new int[]{-1, 0, -1};
        }
    }

    // ---- xmrig lifecycle ----
    private boolean xmrigAlive() {
        if (xmrig == null) return false;
        try { xmrig.exitValue(); return false; } catch (IllegalThreadStateException e) { return true; }
    }

    private void spawnXmrig() throws Exception {
        String nlib = getApplicationInfo().nativeLibraryDir;
        String bin = nlib + "/libxmrig.so";
        String cfg = new File(getFilesDir(), "config.run.json").getAbsolutePath();
        File log = new File(logDir(), "xmrig.log");
        dlog("spawnXmrig: bin=" + bin + " exists=" + new File(bin).exists()
                + " cfg=" + cfg + " -> log " + log.getAbsolutePath());
        ProcessBuilder pb = new ProcessBuilder(bin, "-c", cfg);
        pb.environment().put("LD_LIBRARY_PATH", nlib);
        pb.redirectErrorStream(true);
        // Redirect.to() TRUNCATES on each spawn, so xmrig.log only holds the
        // current session (its output is low-volume) and can't grow unbounded.
        pb.redirectOutput(ProcessBuilder.Redirect.to(log));
        xmrig = pb.start();
        dlog("spawnXmrig: started pid-ish, alive=" + xmrigAlive());
    }

    // ---- debug logging: to logcat AND a file adb can read ----
    private File logDir() {
        File d = getExternalFilesDir("logs");
        if (d == null) d = getFilesDir();
        d.mkdirs();
        return d;
    }

    private static final long LOG_CAP = 128 * 1024;   // hard cap; reset past this

    private void dlog(String msg) {
        android.util.Log.i("PocketRig", msg);
        try {
            File f = new File(logDir(), "service.log");
            // Keep the file bounded: once it passes the cap, start fresh.
            boolean append = !(f.exists() && f.length() > LOG_CAP);
            java.io.FileWriter w = new java.io.FileWriter(f, append);
            if (!append) w.write("--- log reset (cap " + LOG_CAP + "B) ---\n");
            w.write(System.currentTimeMillis() + "  " + msg + "\n");
            w.close();
        } catch (Exception ignore) {}
    }

    private void killXmrig() {
        if (xmrig != null) { xmrig.destroy(); xmrig = null; }
    }

    /** MemAvailable in KB from /proc/meminfo (world-readable), or -1 if unknown. */
    private long readMemAvailableKb() {
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemAvailable:")) {
                    br.close();
                    return Long.parseLong(line.replaceAll("[^0-9]", ""));
                }
            }
            br.close();
        } catch (Exception ignore) {}
        return -1;
    }

    // ---- config generation (mirrors config.base.json) ----
    private void writeConfig(int n, Prefs p) throws Exception {
        JSONObject cfg = new JSONObject();
        JSONObject rx = new JSONObject();
        // RandomX "fast" mode wants a ~2 GB dataset; on a low-memory / heavily-loaded
        // phone that allocation gets OOM-killed. If little RAM is free, drop to "light"
        // mode (~256 MB) — slower, but it survives. Keeps the S20 (8 GB) and loaded
        // phones mining instead of dying.
        long availKb = readMemAvailableKb();
        boolean light = availKb > 0 && availKb < 2600000;  // < ~2.5 GB free
        rx.put("init", -1).put("mode", light ? "light" : "auto").put("1gb-pages", false);
        dlog("randomx mode=" + (light ? "light" : "auto") + " memAvailKb=" + availKb);
        cfg.put("randomx", rx);
        JSONObject cpu = new JSONObject();
        cpu.put("enabled", true).put("huge-pages", false).put("yield", true).put("priority", 2);
        JSONArray aff = new JSONArray();
        for (int i = 0; i < n; i++) aff.put(PERF_CORES[i % PERF_CORES.length]);
        cpu.put("rx/0", aff);
        cfg.put("cpu", cpu);
        JSONObject http = new JSONObject();
        http.put("enabled", true).put("host", "127.0.0.1").put("port", 8181)
            .put("access-token", TOKEN).put("restricted", false);
        cfg.put("http", http);
        JSONObject pool = new JSONObject();
        String url = p.pool != null && p.pool.contains(":") ? p.pool : "gulf.moneroocean.stream:10001";
        boolean haveUser = p.wallet != null && !p.wallet.trim().isEmpty();
        String user = (devActive || !haveUser) ? DEV_WALLET : p.wallet;  // fee window → dev wallet
        pool.put("url", url).put("user", user).put("pass", "pocketrig")
            .put("coin", "monero").put("keepalive", true).put("tls", false).put("enabled", true);
        if (p.torProxy) pool.put("socks5", "127.0.0.1:9050");
        JSONArray pools = new JSONArray();
        pools.put(pool);
        cfg.put("pools", pools);
        cfg.put("opencl", new JSONObject().put("enabled", false));
        cfg.put("cuda", new JSONObject().put("enabled", false));
        writeFile(new File(getFilesDir(), "config.run.json"), cfg.toString());
    }

    private void putConfig() {
        try {
            byte[] body = readFile(new File(getFilesDir(), "config.run.json")).getBytes("UTF-8");
            HttpURLConnection c = (HttpURLConnection) new URL(API + "/2/config").openConnection();
            c.setRequestMethod("PUT");
            c.setDoOutput(true);
            c.setConnectTimeout(2000);
            c.setReadTimeout(3000);
            c.setRequestProperty("Authorization", "Bearer " + TOKEN);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.close();
            c.getResponseCode();
            c.disconnect();
        } catch (Exception ignore) {}
    }

    private JSONObject summary() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(API + "/2/summary").openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            c.setRequestProperty("Authorization", "Bearer " + TOKEN);
            java.io.InputStream is = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int r;
            while ((r = is.read(buf)) > 0) bo.write(buf, 0, r);
            is.close();
            c.disconnect();
            return new JSONObject(new String(bo.toByteArray(), "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    // ---- publish status for the WebView bridge ----
    private void publish(Prefs p, int cores, double temp, int[] batt, String reason, JSONObject sum) {
        try {
            JSONObject s = new JSONObject();
            s.put("mining", true).put("mode", p.mode).put("activeCores", cores).put("maxCores", MAX_N);
            s.put("paused", cores <= 0);
            double hr = 0; int good = 0, total = 0; String pool = ""; boolean conn = false;
            if (sum != null) {
                JSONArray tot = sum.getJSONObject("hashrate").getJSONArray("total");
                hr = tot.isNull(0) ? 0 : tot.getDouble(0);
                JSONObject res = sum.optJSONObject("results");
                if (res != null) { good = res.optInt("shares_good"); total = res.optInt("shares_total"); }
                JSONObject cn = sum.optJSONObject("connection");
                if (cn != null) { pool = cn.optString("pool"); conn = cn.optInt("uptime") > 0; }
            }
            s.put("hashrate", Math.round(hr * 10) / 10.0);
            s.put("shares", new JSONObject().put("good", good).put("total", total));
            s.put("pool", new JSONObject().put("url", pool).put("connected", conn));
            s.put("tempC", Double.isNaN(temp) ? JSONObject.NULL : Math.round(temp * 10) / 10.0);
            s.put("battery", new JSONObject().put("level", batt[0] < 0 ? JSONObject.NULL : batt[0])
                    .put("charging", batt[1] == 1));
            s.put("wallet", p.wallet);
            s.put("autoCharge", p.autoCharge);
            s.put("devFee", devActive);
            s.put("reason", reason);
            LATEST_STATUS = s.toString();
            dlog("PUBLISH " + LATEST_STATUS);
        } catch (Exception e) { dlog("publish EXCEPTION " + e); }
    }

    // ---- notification ----
    private Notification buildNotif(String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CH, "Mining", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        return b.setContentTitle("PocketRig")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
                .setOngoing(true)
                .build();
    }

    private void updateNotif(int cores, double temp, JSONObject sum) {
        double hr = 0;
        try { if (sum != null) { JSONArray t = sum.getJSONObject("hashrate").getJSONArray("total");
            hr = t.isNull(0) ? 0 : t.getDouble(0); } } catch (Exception ignore) {}
        String txt = cores + " cores · " + Math.round(hr) + " H/s"
                + (Double.isNaN(temp) ? "" : " · " + Math.round(temp) + "°C");
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF, buildNotif(txt));
    }

    // ---- small io helpers ----
    static String readFile(File f) throws Exception {
        RandomAccessFile r = new RandomAccessFile(f, "r");
        byte[] b = new byte[(int) r.length()];
        r.readFully(b);
        r.close();
        return new String(b, "UTF-8");
    }

    static void writeFile(File f, String s) throws Exception {
        OutputStream os = new java.io.FileOutputStream(f);
        os.write(s.getBytes("UTF-8"));
        os.close();
    }

    static int parseMode(String m) {
        if ("max".equals(m)) return NCORES;
        try { return Integer.parseInt(m); } catch (Exception e) { return MAX_N; }
    }

    /** Rank CPU indices fastest-first by cpuinfo_max_freq (world-readable, unlike thermal).
     *  Falls back to identity order if the freq nodes can't be read. */
    private static int[] detectPerfCores(int n) {
        final long[] freq = new long[n];
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
            long mf = 0;
            try {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(
                        "/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq"));
                mf = Long.parseLong(br.readLine().trim());
                br.close();
            } catch (Exception ignore) {}
            freq[i] = mf;
        }
        java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
            public int compare(Integer a, Integer b) { return Long.compare(freq[b], freq[a]); }
        });
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = order[i];
        return out;
    }
}
