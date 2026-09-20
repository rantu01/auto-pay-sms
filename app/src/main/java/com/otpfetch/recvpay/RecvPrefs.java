package com.otpfetch.recvpay;

import android.content.Context;
import android.content.SharedPreferences;

/** Backend URL + API key + device label (no secrets hardcoded). */
public final class RecvPrefs {
    private static final String P = "recvpay_prefs";
    private static final String K_BASE = "baseUrl";
    private static final String K_KEY = "apiKey";
    private static final String K_DEVICE = "deviceLabel";
    private static final String K_LAST_SYNC = "lastSync";
    private static final String K_LAST_TRX = "lastTrx";

    public static final String DEFAULT_BASE = "http://10.0.2.2:4000";

    private RecvPrefs() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    public static String base(Context c) {
        String v = p(c).getString(K_BASE, DEFAULT_BASE);
        if (v == null || v.isEmpty()) return DEFAULT_BASE;
        return v.replaceAll("/+$", "");
    }

    public static void setBase(Context c, String v) {
        p(c).edit().putString(K_BASE, v == null ? DEFAULT_BASE : v.trim().replaceAll("/+$", "")).apply();
    }

    public static String apiKey(Context c) {
        return p(c).getString(K_KEY, "");
    }

    public static void setApiKey(Context c, String v) {
        p(c).edit().putString(K_KEY, v == null ? "" : v.trim()).apply();
    }

    public static String deviceLabel(Context c) {
        String v = p(c).getString(K_DEVICE, "");
        if (v == null || v.isEmpty()) {
            v = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
            p(c).edit().putString(K_DEVICE, v).apply();
        }
        return v;
    }

    public static void setDeviceLabel(Context c, String v) {
        p(c).edit().putString(K_DEVICE, v == null ? "" : v.trim()).apply();
    }

    public static void markSync(Context c, String trxId) {
        p(c).edit().putString(K_LAST_SYNC, org.json.JSONObject.quote(new java.util.Date().toString()))
                .putLong("lastSyncMs", System.currentTimeMillis())
                .putString(K_LAST_TRX, trxId == null ? "" : trxId).apply();
    }

    public static String lastSyncText(Context c) {
        long ms = p(c).getLong("lastSyncMs", 0);
        if (ms == 0) return "never";
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault());
        return f.format(new java.util.Date(ms));
    }

    public static String lastTrx(Context c) {
        return p(c).getString(K_LAST_TRX, "");
    }
}
