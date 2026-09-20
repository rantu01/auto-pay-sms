package com.otpfetch.recvpay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * System SMS entry point. No polling: Android delivers SMS_RECEIVED to this
 * receiver even when the app is in the background (as long as RECEIVE_SMS
 * is granted and the app is not force-stopped).
 */
public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "RecvPaySms";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;
        try {
            Bundle b = intent.getExtras();
            if (b == null) return;
            Object[] pdus = (Object[]) b.get("pdus");
            if (pdus == null || pdus.length == 0) return;
            String format = b.getString("format");
            StringBuilder full = new StringBuilder();
            for (Object pdu : pdus) {
                SmsMessage msg = SmsMessage.createFromPdu((byte[]) pdu, format);
                if (msg != null && msg.getMessageBody() != null) full.append(msg.getMessageBody());
            }
            String sms = full.toString();
            if (sms.isEmpty()) return;
            Log.i(TAG, "SMS received (" + sms.length() + " chars)");
            BkashParser.Result r = BkashParser.parse(sms);
            if (!r.ok) {
                Log.i(TAG, "ignored non-payment SMS: " + r.error);
                return;
            }
            String receivedAt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                    .format(new Date());
            PendingStore store = new PendingStore(context);
            boolean inserted = store.insert(r, sms, receivedAt);
            if (!inserted) {
                Log.i(TAG, "duplicate SMS ignored locally: " + r.trxId);
            }
            // Best-effort immediate upload; failures stay in the outbox for retry.
            SyncService.kick(context);
        } catch (Exception e) {
            Log.w(TAG, "onReceive failed: " + e.getMessage());
        }
    }
}
