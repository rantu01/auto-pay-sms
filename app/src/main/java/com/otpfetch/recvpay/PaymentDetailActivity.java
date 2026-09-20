package com.otpfetch.recvpay;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** Full original SMS + extracted fields for one TrxID. */
public class PaymentDetailActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setPadding(32, 32, 32, 32);
        tv.setTextSize(14);
        setContentView(tv);
        String trxId = getIntent().getStringExtra("trxId");
        PendingStore.Row r = new PendingStore(this).byTrx(trxId == null ? "" : trxId);
        if (r == null) {
            tv.setText("Payment not found: " + trxId);
            return;
        }
        tv.setText("Amount: ৳" + r.amount
                + "\nSender: " + r.sender
                + "\nFee: ৳" + r.fee
                + "\nBalance: " + (r.balance == null || r.balance.isEmpty() ? "—" : "৳" + r.balance)
                + "\nTrxID: " + r.trxId
                + "\nDate: " + r.txnDate
                + "\nTime: " + r.txnTime
                + "\nReceived at: " + r.receivedAt
                + "\nSync: " + (r.synced == 1 ? "synced (" + r.serverStatus + (r.duplicate == 1 ? ", duplicate" : "") + ")" : "pending upload")
                + "\n\nOriginal SMS:\n" + r.original);
    }
}
