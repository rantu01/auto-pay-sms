package com.otpfetch.recvpay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After reboot, retry any payments that were queued while offline. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)
                || "android.net.conn.CONNECTIVITY_CHANGE".equals(a)) {
            SyncService.kick(context);
        }
    }
}
