package com.otpfetch.recvpay;

import static org.junit.Assert.*;
import org.junit.Test;

/** Parser unit tests (mirror of backend recvpay-test.js cases 1-9, 13-14). */
public class BkashParserTest {
    private static final String VALID =
            "You have received Tk 480.00 from 01642188277. Fee Tk 0.00. " +
            "Balance Tk 1,332.31. TrxID DIJ2N7GCGS at 19/09/2026 10:59";

    @Test public void validSms() {
        BkashParser.Result r = BkashParser.parse(VALID);
        assertTrue(r.error, r.ok);
        assertEquals(480.0, r.amount, 0.001);
        assertEquals("01642188277", r.sender);
        assertEquals(0.0, r.fee, 0.001);
        assertEquals(1332.31, r.balance, 0.001);
        assertEquals("DIJ2N7GCGS", r.trxId);
        assertEquals("2026-09-19", r.transactionDate);
        assertEquals("10:59", r.transactionTime);
    }

    @Test public void amountFormats() {
        assertEquals(1250.5, BkashParser.parse(
                "You have received Tk 1,250.50 from 01711111111. TrxID AAA111BBB at 01/01/2026 01:05").amount, 0.001);
        assertEquals(5000.0, BkashParser.parse(
                "You have received BDT 5000 from 01822222222 TrxID CCC333DDD at 02-02-2026 14:30").amount, 0.001);
    }

    @Test public void nonBkashIgnored() {
        assertFalse(BkashParser.parse("Your parcel has arrived.").ok);
    }

    @Test public void promoOtpCashoutIgnored() {
        assertFalse(BkashParser.parse("bKash offer! 20% bonus. Dial *247# to claim.").ok);
        assertFalse(BkashParser.parse("Your bKash OTP is 482913. Do not share it.").ok);
        assertFalse(BkashParser.parse(
                "Cash Out Tk 500.00 to 01999999999 successful. TrxID OUT123XYZ at 01/01/2026 10:00").ok);
        assertFalse(BkashParser.parse(
                "Send Money Tk 200.00 to 01888888888 successful. TrxID SEND001AB at 01/01/2026 10:00").ok);
    }

    @Test public void extraSpacesAndCase() {
        BkashParser.Result r = BkashParser.parse(
                "  you   HAVE received   Tk   480.00   FROM  01642188277. fee Tk 0.00. trxid dij2n7gcgs AT 19/09/2026 10:59  ");
        assertTrue(r.error, r.ok);
        assertEquals("DIJ2N7GCGS", r.trxId);
    }

    @Test public void burstDistinct() {
        for (int i = 0; i < 20; i++) {
            String tid = "BURST" + (1000 + i);
            BkashParser.Result r = BkashParser.parse(
                    "You have received Tk 10.00 from 01700000001. TrxID " + tid + " at 19/09/2026 10:00");
            assertTrue(r.ok);
            assertEquals(tid, r.trxId);
        }
    }

    @Test public void doubleDetectSame() {
        assertEquals(BkashParser.parse(VALID).trxId, BkashParser.parse(VALID).trxId);
    }
}
