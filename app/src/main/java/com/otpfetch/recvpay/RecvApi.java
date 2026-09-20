package com.otpfetch.recvpay;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Minimal backend client: backend -> MongoDB, phone never touches Mongo directly. */
public final class RecvApi {
    private RecvApi() {}

    public static final class Resp {
        public final int code;
        public final JSONObject json;
        Resp(int c, JSONObject j) { code = c; json = j; }
        public boolean ok() { return code >= 200 && code < 300; }
    }

    public static Resp postPayment(Context ctx, JSONObject body) throws Exception {
        String url = RecvPrefs.base(ctx) + "/api/received-payments";
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        String key = RecvPrefs.apiKey(ctx);
        if (key != null && !key.isEmpty()) conn.setRequestProperty("x-api-key", key);
        String token = ctx.getApplicationContext()
                .getSharedPreferences("recvpay_prefs", Context.MODE_PRIVATE)
                .getString("adminToken", "");
        if ((key == null || key.isEmpty()) && token != null && !token.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        conn.setDoOutput(true);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream os = conn.getOutputStream();
        os.write(bytes);
        os.flush();
        os.close();
        int code = conn.getResponseCode();
        InputStream in = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String ln;
            while ((ln = br.readLine()) != null) sb.append(ln);
            br.close();
        }
        conn.disconnect();
        JSONObject json;
        try {
            json = new JSONObject(sb.length() == 0 ? "{}" : sb.toString());
        } catch (Exception e) {
            json = new JSONObject();
            json.put("_raw", sb.toString());
        }
        return new Resp(code, json);
    }

    public static Resp health(Context ctx) throws Exception {
        String url = RecvPrefs.base(ctx) + "/api/health";
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        int code = conn.getResponseCode();
        InputStream in = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String ln;
            while ((ln = br.readLine()) != null) sb.append(ln);
            br.close();
        }
        conn.disconnect();
        JSONObject json;
        try {
            json = new JSONObject(sb.length() == 0 ? "{}" : sb.toString());
        } catch (Exception e) {
            json = new JSONObject();
        }
        return new Resp(code, json);
    }
}
