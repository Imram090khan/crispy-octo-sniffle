package com.speedshield.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

/** Turns protection back on after a restart, if it was on before. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        boolean wanted = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("wanted", false);
        // prepare() returns null only while the user's VPN permission is still valid.
        if (!wanted || AdVpnService.running || VpnService.prepare(context) != null) return;
        context.startForegroundService(new Intent(context, AdVpnService.class));
    }
}
