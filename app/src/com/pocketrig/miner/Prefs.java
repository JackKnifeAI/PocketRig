package com.pocketrig.miner;

import android.content.Context;
import android.content.SharedPreferences;

/** Tiny typed view over SharedPreferences for the miner settings. */
public class Prefs {
    public String wallet;
    public String pool;
    public String mode;
    public boolean torProxy;
    public boolean autoCharge;

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("pocketrig", Context.MODE_PRIVATE);
    }

    public static Prefs read(Context c) {
        SharedPreferences s = sp(c);
        Prefs p = new Prefs();
        p.wallet = s.getString("wallet", "");
        p.pool = s.getString("pool", "gulf.moneroocean.stream:10001");
        p.mode = s.getString("mode", "adaptive");
        p.torProxy = s.getBoolean("tor", false);
        p.autoCharge = s.getBoolean("autocharge", false);  // opt-in; a fresh install never mines until the user turns it on
        return p;
    }

    public static void put(Context c, String key, String value) {
        sp(c).edit().putString(key, value).apply();
    }

    public static void putBool(Context c, String key, boolean value) {
        sp(c).edit().putBoolean(key, value).apply();
    }
}
