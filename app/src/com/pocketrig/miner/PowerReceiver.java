package com.pocketrig.miner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Auto-starts mining when the charger is connected — but only if the user
 *  enabled it, the miner isn't already running, and a wallet is set. Relies on
 *  the app's battery-optimization exemption to be allowed to start the
 *  foreground service from the background on Android 12+. */
public class PowerReceiver extends BroadcastReceiver {
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) return;
        Prefs p = Prefs.read(ctx);
        if (!p.autoCharge) return;                                   // user opted out
        if (MinerService.RUNNING) return;                            // already mining
        if (p.wallet == null || p.wallet.trim().isEmpty()) return;   // nowhere to pay out
        try {
            Intent i = new Intent(ctx, MinerService.class).setAction("START");
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception ignore) {}
    }
}
