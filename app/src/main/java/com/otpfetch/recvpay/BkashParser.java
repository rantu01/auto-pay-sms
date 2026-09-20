package com.otpfetch.recvpay;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Robust parser for bKash "money received" SMS.
 * Handles spacing/case/wording variations, rejects non-receive messages
 * (cash-out, send-money, recharge, promos, OTP, security alerts).
 *
 * Canonical example:
 * "You have received Tk 480.00 from 01642188277. Fee Tk 0.00.
 *  Balance Tk 1,332.31. TrxID DIJ2N7GCGS at 19/09/2026 10:59"
 */
public final class BkashParser {

    public static final class Result {
        public double amount;
        public String sender = "";
        public double fee;
        public boolean hasFee;
        public Double balance;
        public String trxId = "";
        public String transactionDate = ""; // normalized YYYY-MM-DD
        public String transactionTime = ""; // HH:MM
        public String rawDate = "";
        public boolean ok;
        public String error = "";
    }

    private BkashParser() {}

    // Reject patterns: anything that is clearly NOT an incoming payment.
    private static final Pattern[] REJECT = new Pattern[]{
            Pattern.compile("cash\\s*out", Pattern.CASE_INSENSITIVE),
            Pattern.compile("send\\s*money", Pattern.CASE_INSENSITIVE),
            Pattern.compile("you\\s+have\\s+sent", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bsent\\b.*\\bto\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("recharge", Pattern.CASE_INSENSITIVE),
            Pattern.compile("top\\s*-?\\s*up", Pattern.CASE_INSENSITIVE),
            Pattern.compile("payment.*successful.*to\\s+merchant", Pattern.CASE_INSENSITIVE),
            Pattern.compile("you.*paid.*to\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bOTP\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("one\\s*-?\\s*time\\s+password", Pattern.CASE_INSENSITIVE),
            Pattern.compile("verification\\s*code", Pattern.CASE_INSENSITIVE),
            Pattern.compile("offer|promo|discount|bonus.*claim|dial\\s*\\*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("pin|password.*(chang|reset|block|lock)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("account.*(suspend|block|locked|deactivat)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("insufficient.*balance|failed|declined|cancelled", Pattern.CASE_INSENSITIVE),
    };

    // Positive gate: must look like money coming IN.
    private static final Pattern RECEIVE_GATE = Pattern.compile(
            "(you\\s+have\\s+received|received\\s+tk|received\\s+bdt|tk\\s+[\\d,]+\\.\\d{2}\\s+received|funds?\\s+received|payment\\s+received\\s+from)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern AMOUNT = Pattern.compile(
            "(?:received|receive)\\s+(?:tk\\.?|bdt|rs\\.?)?\\s*([\\d,]+(?:\\.\\d{1,2})?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT_ALT = Pattern.compile(
            "(?:tk\\.?|bdt)\\s*([\\d,]+(?:\\.\\d{1,2})?)\\s*(?:has\\s+been\\s+)?(?:received|credited)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SENDER = Pattern.compile(
            "from\\s*\\D{0,12}?((?:\\+?880|0)\\s?1\\s?\\d(?:[\\s-]?\\d){8})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FEE = Pattern.compile(
            "fee\\s*(?:tk\\.?|bdt)?\\s*([\\d,]+(?:\\.\\d{1,2})?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BALANCE = Pattern.compile(
            "balance\\s*(?:tk\\.?|bdt|is)?\\s*([\\d,]+(?:\\.\\d{1,2})?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRXID = Pattern.compile(
            "tr\\s*x\\s*(?:id|no)?\\s*[:#\\-]?\\s*([A-Z0-9]{6,20})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DATETIME = Pattern.compile(
            "(?:at\\s+)?(\\d{1,2}[/\\-\\.]\\d{1,2}[/\\-\\.]\\d{2,4})\\s+(\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:AM|PM|am|pm)?)");

    private static double num(String s) {
        return Double.parseDouble(s.replace(",", "").trim());
    }

    public static boolean looksLikeReceive(String sms) {
        if (sms == null) return false;
        String s = sms.trim();
        if (s.isEmpty()) return false;
        // bKash sender IDs are usually "bKash", "BKASH", "16247" etc.
        boolean bkashMention = s.toLowerCase().contains("bkash") || s.contains("16247");
        if (!RECEIVE_GATE.matcher(s).find()) return false;
        for (Pattern p : REJECT) {
            // "Fee" contains no reject word; but "paid ... to" must reject.
            if (p.matcher(s).find()) {
                // Allow the word "payment received from" (positive) even though
                // it contains "payment".
                if (p.pattern().contains("paid") || p.pattern().contains("merchant")) return false;
                if (!s.toLowerCase().contains("you have received")) return false;
            }
        }
        // Extra OTP guard: a pure OTP message never has TrxID+amount+sender together.
        if (s.toUpperCase().contains("OTP") && !TRXID.matcher(s).find()) return false;
        return bkashMention || RECEIVE_GATE.matcher(s).find();
    }

    public static Result parse(String sms) {
        Result r = new Result();
        if (sms == null || sms.trim().isEmpty()) {
            r.error = "Empty message.";
            return r;
        }
        String s = sms.trim().replaceAll("[ \\t\\u00A0]+", " ");
        if (!looksLikeReceive(s)) {
            r.error = "Not a bKash received-payment SMS.";
            return r;
        }
        Matcher m = AMOUNT.matcher(s);
        if (!m.find()) {
            m = AMOUNT_ALT.matcher(s);
        }
        if (!m.find()) {
            // fallback: first Tk amount = received amount
            Matcher any = Pattern.compile("(?:tk\\.?|bdt)\\s*([\\d,]+(?:\\.\\d{1,2})?)", Pattern.CASE_INSENSITIVE).matcher(s);
            if (any.find()) m = any;
            else {
                r.error = "Amount not found.";
                return r;
            }
        }
        try {
            r.amount = num(m.group(1));
        } catch (Exception e) {
            r.error = "Invalid amount.";
            return r;
        }
        if (!(r.amount > 0)) {
            r.error = "Invalid amount.";
            return r;
        }
        Matcher sm = SENDER.matcher(s);
        if (sm.find()) {
            r.sender = sm.group(1).replaceAll("[\\s-]", "");
        } else {
            // Some formats: "... from 016XXXXXXXXX ..." without "from" spacing variants
            Matcher fallback = Pattern.compile("((?:\\+?880|0)1\\d{9})").matcher(s.replaceAll("[\\s-]", ""));
            // re-run on compacted string against original spacing
            Matcher f2 = Pattern.compile("((?:\\+?880|0)1\\d{9})").matcher(s);
            if (f2.find()) r.sender = f2.group(1).replaceAll("[\\s-]", "");
            else {
                r.error = "Sender number not found.";
                return r;
            }
        }
        Matcher fm = FEE.matcher(s);
        if (fm.find()) {
            try {
                r.fee = num(fm.group(1));
                r.hasFee = true;
            } catch (Exception ignored) {
                r.fee = 0;
            }
        }
        Matcher bm = BALANCE.matcher(s);
        if (bm.find()) {
            try {
                r.balance = num(bm.group(1));
            } catch (Exception ignored) {}
        }
        Matcher tm = TRXID.matcher(s);
        if (!tm.find()) {
            r.error = "TrxID not found.";
            return r;
        }
        r.trxId = tm.group(1).trim().toUpperCase().replaceAll("[\\s-]", "");
        if (!r.trxId.matches("^[A-Z0-9]{6,20}$")) {
            r.error = "Invalid TrxID.";
            return r;
        }
        Matcher dm = DATETIME.matcher(s);
        if (dm.find()) {
            r.rawDate = dm.group(1) + " " + dm.group(2);
            r.transactionDate = normalizeDate(dm.group(1));
            r.transactionTime = normalizeTime(dm.group(2));
        } else {
            r.error = "Transaction date/time not found.";
            return r;
        }
        r.ok = true;
        return r;
    }

    static String normalizeDate(String d) {
        String[] p = d.trim().split("[/\\-.]");
        if (p.length != 3) return d.trim();
        String a = p[0].trim(), b = p[1].trim(), c = p[2].trim();
        if (c.length() == 2) c = "20" + c;
        // Assume DD/MM/YYYY (bKash Bangladesh format). If first part > 31, swap.
        try {
            int first = Integer.parseInt(a);
            if (first > 31) { // YYYY/MM/DD unlikely; keep as-is
                return c + "-" + pad(b) + "-" + pad(a);
            }
        } catch (Exception ignored) {}
        return c + "-" + pad(b) + "-" + pad(a);
    }

    static String normalizeTime(String t) {
        t = t.trim().toUpperCase().replaceAll("\\s+", "");
        boolean pm = t.endsWith("PM");
        boolean am = t.endsWith("AM");
        t = t.replace("AM", "").replace("PM", "");
        String[] p = t.split(":");
        int h = Integer.parseInt(p[0]);
        String mm = p[1];
        if (pm && h < 12) h += 12;
        if (am && h == 12) h = 0;
        return String.format("%02d:%s", h, mm.length() >= 2 ? mm.substring(0, 2) : mm);
    }

    private static String pad(String s) {
        return s.length() >= 2 ? s : "0" + s;
    }
}
