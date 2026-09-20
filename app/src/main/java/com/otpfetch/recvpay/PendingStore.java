package com.otpfetch.recvpay;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline outbox: every parsed SMS is stored locally first, then uploaded.
 * After a successful upload the row is marked synced (never deleted, so the
 * phone keeps an audit trail and never re-sends the same TrxID).
 */
public final class PendingStore extends SQLiteOpenHelper {
    private static final String DB = "recvpay.db";
    private static final int VER = 1;

    public static final class Row {
        public long rowId;
        public double amount;
        public String sender = "";
        public double fee;
        public String balance = "";
        public String trxId = "";
        public String txnDate = "";
        public String txnTime = "";
        public String original = "";
        public String receivedAt = "";
        public int synced; // 0 pending, 1 synced
        public String serverStatus = "";
        public int duplicate;
    }

    public PendingStore(Context ctx) {
        super(ctx.getApplicationContext(), DB, null, VER);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS payments (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "amount REAL NOT NULL," +
                "sender TEXT NOT NULL," +
                "fee REAL DEFAULT 0," +
                "balance TEXT DEFAULT ''," +
                "trxId TEXT NOT NULL UNIQUE," +
                "txnDate TEXT DEFAULT ''," +
                "txnTime TEXT DEFAULT ''," +
                "original TEXT DEFAULT ''," +
                "receivedAt TEXT DEFAULT ''," +
                "synced INTEGER DEFAULT 0," +
                "serverStatus TEXT DEFAULT ''," +
                "duplicate INTEGER DEFAULT 0," +
                "createdAt INTEGER DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        db.execSQL("CREATE TABLE IF NOT EXISTS payments (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "amount REAL NOT NULL," +
                "sender TEXT NOT NULL," +
                "fee REAL DEFAULT 0," +
                "balance TEXT DEFAULT ''," +
                "trxId TEXT NOT NULL UNIQUE," +
                "txnDate TEXT DEFAULT ''," +
                "txnTime TEXT DEFAULT ''," +
                "original TEXT DEFAULT ''," +
                "receivedAt TEXT DEFAULT ''," +
                "synced INTEGER DEFAULT 0," +
                "serverStatus TEXT DEFAULT ''," +
                "duplicate INTEGER DEFAULT 0," +
                "createdAt INTEGER DEFAULT 0)");
    }

    /** Insert; returns false when this TrxID is already stored locally (no resend). */
    public synchronized boolean insert(BkashParser.Result r, String original, String receivedAt) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("amount", r.amount);
            cv.put("sender", r.sender);
            cv.put("fee", r.hasFee ? r.fee : 0);
            cv.put("balance", r.balance == null ? "" : String.valueOf(r.balance));
            cv.put("trxId", r.trxId);
            cv.put("txnDate", r.transactionDate == null ? "" : r.transactionDate);
            cv.put("txnTime", r.transactionTime == null ? "" : r.transactionTime);
            cv.put("original", original == null ? "" : original);
            cv.put("receivedAt", receivedAt == null ? "" : receivedAt);
            cv.put("synced", 0);
            cv.put("createdAt", System.currentTimeMillis());
            long id = db.insertWithOnConflict("payments", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
            return id != -1;
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized List<Row> pending() {
        return query("SELECT * FROM payments WHERE synced=0 ORDER BY _id ASC LIMIT 100");
    }

    public synchronized List<Row> all(int limit) {
        return query("SELECT * FROM payments ORDER BY _id DESC LIMIT " + Math.max(1, Math.min(500, limit)));
    }

    public synchronized Row byTrx(String trxId) {
        List<Row> l = query("SELECT * FROM payments WHERE trxId=? LIMIT 1", new String[]{trxId});
        return l.isEmpty() ? null : l.get(0);
    }

    public synchronized void markSynced(String trxId, String serverStatus, boolean duplicate) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("synced", 1);
            cv.put("serverStatus", serverStatus == null ? "" : serverStatus);
            cv.put("duplicate", duplicate ? 1 : 0);
            db.update("payments", cv, "trxId=?", new String[]{trxId});
        } catch (Exception ignored) {}
    }

    public synchronized int counts(int[] outTotalSyncedPending) {
        // out: [total, synced, pending]
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor c = db.rawQuery("SELECT COUNT(*), SUM(synced) FROM payments", null);
            int total = 0, synced = 0;
            if (c.moveToFirst()) {
                total = c.getInt(0);
                synced = c.isNull(1) ? 0 : c.getInt(1);
            }
            c.close();
            outTotalSyncedPending[0] = total;
            outTotalSyncedPending[1] = synced;
            outTotalSyncedPending[2] = total - synced;
            return total;
        } catch (Exception e) {
            return 0;
        }
    }

    private List<Row> query(String sql) {
        return query(sql, null);
    }

    private List<Row> query(String sql, String[] args) {
        List<Row> out = new ArrayList<>();
        try {
            Cursor c = getReadableDatabase().rawQuery(sql, args);
            while (c.moveToNext()) {
                Row r = new Row();
                r.rowId = c.getLong(c.getColumnIndexOrThrow("_id"));
                r.amount = c.getDouble(c.getColumnIndexOrThrow("amount"));
                r.sender = c.getString(c.getColumnIndexOrThrow("sender"));
                r.fee = c.getDouble(c.getColumnIndexOrThrow("fee"));
                r.balance = c.getString(c.getColumnIndexOrThrow("balance"));
                r.trxId = c.getString(c.getColumnIndexOrThrow("trxId"));
                r.txnDate = c.getString(c.getColumnIndexOrThrow("txnDate"));
                r.txnTime = c.getString(c.getColumnIndexOrThrow("txnTime"));
                r.original = c.getString(c.getColumnIndexOrThrow("original"));
                r.receivedAt = c.getString(c.getColumnIndexOrThrow("receivedAt"));
                r.synced = c.getInt(c.getColumnIndexOrThrow("synced"));
                r.serverStatus = c.getString(c.getColumnIndexOrThrow("serverStatus"));
                r.duplicate = c.getInt(c.getColumnIndexOrThrow("duplicate"));
                out.add(r);
            }
            c.close();
        } catch (Exception ignored) {}
        return out;
    }

    public static JSONObject toPayload(Context ctx, Row r) {
        try {
            JSONObject o = new JSONObject();
            o.put("amount", r.amount);
            o.put("sender", r.sender);
            o.put("fee", r.fee);
            if (r.balance != null && !r.balance.isEmpty()) {
                try { o.put("balance", Double.parseDouble(r.balance)); }
                catch (Exception e) { o.put("balance", JSONObject.NULL); }
            }
            o.put("trxId", r.trxId);
            o.put("transactionDate", r.txnDate == null ? "" : r.txnDate);
            o.put("transactionTime", r.txnTime == null ? "" : r.txnTime);
            o.put("originalMessage", r.original == null ? "" : r.original);
            o.put("receivedAt", r.receivedAt == null ? "" : r.receivedAt);
            o.put("deviceInfo", RecvPrefs.deviceLabel(ctx));
            o.put("source", "bkash_sms");
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
