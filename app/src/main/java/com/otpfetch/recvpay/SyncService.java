package com.otpfetch.recvpay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Uploads pending rows to POST /api/received-payments with retry.
 * Triggered by: SmsReceiver, BootReceiver, MainActivity (manual + on-open),
 * and connectivity changes. Runs as a short foreground service so uploads
 * survive background restrictions on Android 8+.
 */
public class SyncService extends Service {
    private static final String TAG = "RecvPaySync";
    private static final String CH = "recvpay_sync";
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    public static void kick(Context ctx) {
        try {
            Intent i = new Intent(ctx.getApplicationContext(), SyncService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.getApplicationContext().startForegroundService(i);
            } else {
                ctx.getApplicationContext().startService(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "kick failed: " + e.getMessage());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startFg();
        POOL.execute(() -> {
            try {
                syncAll(getApplicationContext());
            } finally {
                stopSelf(startId);
            }
        });
        return START_NOT_STICKY;
    }

    private void startFg() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    NotificationChannel ch = new NotificationChannel(CH, "Payment sync", NotificationManager.IMPORTANCE_LOW);
                    nm.createNotificationChannel(ch);
                }
            }
            Notification n = new NotificationCompat.Builder(this, CH)
                    .setContentTitle("Syncing bKash payments")
                    .setContentText("Uploading pending transactions…")
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .build();
            startForeground(6101, n);
        } catch (Exception e) {
            Log.w(TAG, "foreground failed: " + e.getMessage());
        }
    }

    public static void syncAll(Context ctx) {
        if (!online(ctx)) {
            Log.i(TAG, "offline — keeping payments in local outbox");
            return;
        }
        PendingStore store = new PendingStore(ctx);
        List<PendingStore.Row> rows = store.pending();
        for (PendingStore.Row r : rows) {
            try {
                JSONObject payload = PendingStore.toPayload(ctx, r);
                RecvApi.Resp resp = RecvApi.postPayment(ctx, payload);
                if (resp.code == 200 || resp.code == 201) {
                    boolean dup = resp.json.optBoolean("duplicate", false);
                    JSONObject pay = resp.json.optJSONObject("payment");
                    String status = pay == null ? "pending" : pay.optString("status", "pending");
                    store.markSynced(r.trxId, status, dup);
                    RecvPrefs.markSync(ctx, r.trxId);
                    Log.i(TAG, "synced " + r.trxId + (dup ? " (duplicate)" : ""));
                } else if (resp.code == 400 || resp.code == 401 || resp.code == 403) {
                    // Auth/validation errors will never succeed on retry — log and stop.
                    Log.w(TAG, "upload rejected (" + resp.code + "): " + resp.json.optString("error"));
                    break;
                } else {
                    Log.w(TAG, "upload failed (" + resp.code + "), will retry later");
                    break; // server/network error: back off, keep the rest queued
                }
            } catch (Exception e) {
                Log.w(TAG, "upload error, will retry: " + e.getMessage());
                break;
            }
        }
    }

    private static boolean online(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return false;
        }
    }
}
