package com.otpfetch.recvpay;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dashboard + history + settings. All network work off the main thread. */
public class MainActivity extends AppCompatActivity {
    private static final int REQ_SMS = 7001;
    private final ExecutorService net = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private LinearLayout listView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusView = findViewById(R.id.statusView);
        listView = findViewById(R.id.listView);
        Button syncBtn = findViewById(R.id.syncBtn);
        Button settingsBtn = findViewById(R.id.settingsBtn);
        Button testBtn = findViewById(R.id.testBtn);
        syncBtn.setOnClickListener(v -> {
            SyncService.kick(this);
            Toast.makeText(this, "Sync started", Toast.LENGTH_SHORT).show();
            refresh();
        });
        settingsBtn.setOnClickListener(v -> settingsDialog());
        testBtn.setOnClickListener(v -> testSmsDialog());
        askPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        SyncService.kick(this);
        refresh();
    }

    @Override
    protected void onDestroy() {
        net.shutdownNow();
        super.onDestroy();
    }

    private void askPermissions() {
        try {
            String[] perms;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms = new String[]{Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS, Manifest.permission.POST_NOTIFICATIONS};
            } else {
                perms = new String[]{Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS};
            }
            boolean need = false;
            for (String p : perms) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) { need = true; break; }
            }
            if (need) ActivityCompat.requestPermissions(this, perms, REQ_SMS);
        } catch (Exception ignored) {}
    }

    private void refresh() {
        net.execute(() -> {
            PendingStore store = new PendingStore(this);
            int[] c = new int[3];
            store.counts(c);
            List<PendingStore.Row> rows = store.all(100);
            String backendStatus = "checking…";
            try {
                RecvApi.Resp r = RecvApi.health(this);
                backendStatus = r.ok() ? "reachable (" + r.json.optString("db", "?") + ")" : "HTTP " + r.code;
            } catch (Exception e) {
                backendStatus = "unreachable (" + e.getMessage() + ")";
            }
            boolean smsOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
            String dashboard = "Backend: " + RecvPrefs.base(this) + "\nStatus: " + backendStatus
                    + "\nSMS listener: " + (smsOk ? "ACTIVE (granted)" : "BLOCKED — grant SMS permission")
                    + "\nDetected: " + c[0] + " · Synced: " + c[1] + " · Pending: " + c[2]
                    + "\nLast sync: " + RecvPrefs.lastSyncText(this)
                    + (RecvPrefs.lastTrx(this).isEmpty() ? "" : " (" + RecvPrefs.lastTrx(this) + ")");
            main.post(() -> {
                statusView.setText(dashboard);
                listView.removeAllViews();
                if (rows.isEmpty()) {
                    TextView t = new TextView(this);
                    t.setText("No payments yet. Send bKash money to this phone to test.");
                    listView.addView(t);
                    return;
                }
                for (PendingStore.Row r : rows) {
                    TextView t = new TextView(this);
                    t.setText("৳" + r.amount + " · " + r.trxId
                            + "\n" + r.sender + " · " + r.txnDate + " " + r.txnTime
                            + "\n" + (r.synced == 1 ? ("synced" + (r.duplicate == 1 ? " (duplicate)" : "") + " " + r.serverStatus) : "PENDING UPLOAD"));
                    t.setPadding(24, 24, 24, 24);
                    t.setClickable(true);
                    t.setOnClickListener(v -> {
                        Intent i = new Intent(this, PaymentDetailActivity.class);
                        i.putExtra("trxId", r.trxId);
                        startActivity(i);
                    });
                    listView.addView(t);
                }
            });
        });
    }

    private void settingsDialog() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(40, 20, 40, 20);
        EditText base = new EditText(this);
        base.setHint("Backend URL (http://192.168.x.x:4000)");
        base.setText(RecvPrefs.base(this));
        EditText key = new EditText(this);
        key.setHint("API key (RECV_API_KEY)");
        key.setText(RecvPrefs.apiKey(this));
        EditText dev = new EditText(this);
        dev.setHint("Device label");
        dev.setText(RecvPrefs.deviceLabel(this));
        f.addView(base);
        f.addView(key);
        f.addView(dev);
        new AlertDialog.Builder(this).setTitle("Backend settings").setView(f)
                .setPositiveButton("Save", (d, w) -> {
                    RecvPrefs.setBase(this, base.getText().toString());
                    RecvPrefs.setApiKey(this, key.getText().toString());
                    RecvPrefs.setDeviceLabel(this, dev.getText().toString());
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void testSmsDialog() {
        EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        in.setMinLines(3);
        in.setHint("Paste bKash SMS here to test the parser");
        in.setText("You have received Tk 480.00 from 01642188277. Fee Tk 0.00. Balance Tk 1,332.31. TrxID DIJ2N7GCGS at 19/09/2026 10:59");
        new AlertDialog.Builder(this).setTitle("Test parser + upload").setView(in)
                .setPositiveButton("Parse & queue", (d, w) -> net.execute(() -> {
                    String sms = in.getText().toString();
                    BkashParser.Result r = BkashParser.parse(sms);
                    if (!r.ok) {
                        main.post(() -> Toast.makeText(this, "Not a payment SMS: " + r.error, Toast.LENGTH_LONG).show());
                        return;
                    }
                    PendingStore store = new PendingStore(this);
                    boolean inserted = store.insert(r, sms, new java.util.Date().toString());
                    SyncService.kick(this);
                    main.post(() -> {
                        Toast.makeText(this, inserted ? ("Queued " + r.trxId) : ("Already queued " + r.trxId), Toast.LENGTH_LONG).show();
                        refresh();
                    });
                }))
                .setNegativeButton("Cancel", null).show();
    }
}
