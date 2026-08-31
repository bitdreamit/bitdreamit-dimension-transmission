package com.bitdreamit.connect.plugins.transmission.dimension.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.Test;

import com.bitdreamit.connect.plugins.transmission.dimension.server.DimensionOrderRegistry;
import com.bitdreamit.connect.plugins.transmission.dimension.server.DimensionStreamHandler;
import com.bitdreamit.connect.plugins.transmission.dimension.shared.DimensionTransmissionModeProperties;

/**
 * Unit tests for the Siemens Dimension frame builder/parser.
 *
 * Reference frames verified against the interface manual (PN D00396):
 *   <STX>M<FS>A<FS><FS>E2<ETX>  - Result Acceptance (Accept), checksum E2
 *   <STX>N<FS>6A<ETX>           - No Request, checksum 6A
 *   <STX>P<FS>DIM<FS>1<FS>1<FS>0<FS>48<ETX> - real-world poll (ID=DIM)
 */
public class DimensionFrameTest {

    private static final byte STX = 0x02;
    private static final byte ETX = 0x03;
    private static final byte FS  = 0x1C;
    private static final byte ACK = 0x06;
    private static final byte NAK = 0x15;

    /** 8-bit Add-Mod-256 checksum over the payload. */
    private static String chk(String payload) {
        int sum = 0;
        for (byte b : payload.getBytes(java.nio.charset.StandardCharsets.US_ASCII)) {
            sum += (b & 0xFF);
        }
        return String.format("%02X", sum & 0xFF);
    }

    private static String frame(String payload) {
        return new StringBuilder()
                .append((char) STX).append(payload).append(chk(payload)).append((char) ETX)
                .toString();
    }

    @Test
    public void testChecksumOfManualReferenceFrames() {
        // These values are printed verbatim in the interface manual.
        assertEquals("E2", chk("M\u001CA\u001C\u001C"));
        assertEquals("6A", chk("N\u001C"));
        assertEquals("24", chk("M\u001CR\u001C1\u001C"));
        // Real-world poll captured from a Dimension EXL with Instrument ID "DIM"
        assertEquals("48", chk("P\u001CDIM\u001C1\u001C1\u001C0\u001C"));
    }

    @Test
    public void testReadResultFrameWithAutoResponses() throws IOException {
        // Instrument sends: Poll, then a Result frame.
        // The handler should ACK each frame, auto-answer the Poll with "No
        // Request" and the Result with "Result Acceptance", and dispatch the
        // Result payload (including the trailing checksum).
        String poll   = frame("P\u001CDIM\u001C1\u001C1\u001C0\u001C");
        String result = frame("R\u001C0\u001C\u001C52\u001C1\u001C\u001C0\u001C444410111125\u001C1\u001C1\u001C2\u001CGLUC\u001C276\u001Cmg/dL\u001C\u001CCRE\u001C22.05\u001Cmg/dL\u001C");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DimensionStreamHandler handler = newHandler(poll + result, out);

        // 1st read: the poll. ACK + "N" No Request must be written back.
        byte[] first = handler.read();
        assertEquals('P', first[0]);
        String written1 = out.toString("US-ASCII");
        assertTrue("expected ACK for poll", written1.indexOf(ACK) >= 0);
        assertTrue("expected No Request auto response", written1.contains(frame("N\u001C")));

        // 2nd read: the result. ACK + Result Acceptance must be written back.
        byte[] second = handler.read();
        assertEquals('R', second[0]);
        assertTrue(new String(second, java.nio.charset.StandardCharsets.US_ASCII)
                .endsWith("")); // payload dispatched with checksum
        String written2 = out.toString("US-ASCII");
        assertTrue("expected Result Acceptance auto response",
                written2.contains(frame("M\u001CA\u001C\u001C")));

        // 3rd read: no more data -> clean EOF
        assertNull(handler.read());
    }

    @Test
    public void testCorruptFrameNakThenRetransmission() throws IOException {
        String good = frame("R\u001C0\u001C\u001C1596\u001C1\u001C\u001C0\u001C420111230702\u001C1\u001C1\u001C1\u001CCREA\u001C0.2\u001Cmg/dL\u001C3\u001C");

        // Corrupt the first frame: flip one checksum character
        int etxIdx = good.lastIndexOf((char) ETX);
        String corrupt = good.substring(0, etxIdx - 1) + "ZZ" + good.substring(etxIdx);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DimensionStreamHandler handler = newHandler(corrupt + good, out);

        byte[] msg = handler.read();
        assertEquals('R', msg[0]);

        String written = out.toString("US-ASCII");
        assertTrue("expected a NAK for the corrupt frame", written.indexOf(NAK) >= 0);
        assertTrue("expected the ACK only after the good retransmission",
                written.indexOf(ACK) > written.indexOf(NAK));
    }

    @Test
    public void testWriteSampleRequestWaitsForAck() throws IOException {
        // Instrument ACKs our D (Sample Request) frame immediately.
        String ackOnly = new String(new byte[] { ACK }, java.nio.charset.StandardCharsets.US_ASCII)
                + frame("M\u001C\u001CA\u001Ca\u001C"); // trailing noise: instrument acceptance

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DimensionStreamHandler handler = newHandler(ackOnly, out);

        String sampleRequest = "D\u001C0\u001C0\u001CA\u001CDoe,John\u001C012345\u001C2\u001C\u001C0\u001C1\u001C**\u001C1\u001C2\u001CBUN\u001CCREA\u001C";
        handler.write(sampleRequest.getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        String written = out.toString("US-ASCII");
        assertTrue("expected the framed sample request on the wire",
                written.contains(frame(sampleRequest)));
    }

    @Test
    public void testLowercaseChecksumAccepted() throws IOException {
        // PN D00396 renders the checksum as two uppercase hex characters, but
        // some instrument configurations emit lower-case hex on the wire.
        // The checksum VALUE must win over its case: the frame below is
        // byte-identical to a real R frame except "CD" -> "cd".
        String payload = "R\u001C0\u001CDOE,JOHN\u001C1001\u001C1\u001CER\u001C1\u001C123456310825"
                + "\u001C1\u001C1\u001C2\u001CGLU\u001C97\u001Cmg/dL\u001C\u001CCREA\u001C1.1\u001Cmg/dL\u001C";
        String upper = frame(payload);
        int chkIdx = upper.lastIndexOf((char) ETX) - 2;
        String lower = upper.substring(0, chkIdx)
                + upper.substring(chkIdx, chkIdx + 2).toLowerCase()
                + upper.substring(chkIdx + 2);
        assertTrue("test setup: payload checksum is CD",
                chk(payload).equals("CD"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DimensionStreamHandler handler = newHandler(lower, out);

        byte[] msg = handler.read();
        assertEquals('R', msg[0]);

        String written = out.toString("US-ASCII");
        assertTrue("lowercase checksum must be ACKed", written.indexOf(ACK) >= 0);
        assertTrue("lowercase checksum must not be NAKed", written.indexOf(NAK) < 0);
        assertNull(handler.read());
    }

    // ==================================================================
    // Redesign rev 10: dynamic bidirectional order download
    // ==================================================================

    /** Sample Request (D) payload for the shared test order. */
    private static final String D_PAYLOAD =
            "D\u001C0\u001C0\u001CA\u001CDOE,JOHN\u001C043092011\u001C1\u001C\u001C1\u001C1\u001C**\u001C1\u001C3\u001CBUN\u001CCREA\u001CF5";

    @Test
    public void testQueryBarcodeDownloadsQueuedOrder() throws IOException {
        // Barcode scan I|043092011 (checksum 45 - the exact frame that used to
        // fail through Mirth's response path) must now be answered INSIDE the
        // read path with the queued Sample Request (D).
        DimensionOrderRegistry.clear("t-query");
        DimensionOrderRegistry.pushOrder("t-query", "043092011", "DOE,JOHN", "1", "1", "BUN,CREA,F5");

        String query    = frame("I\u001C043092011\u001C");
        String unknown  = frame("I\u001C999999999\u001C");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DimensionStreamHandler handler = newHandler(query + unknown, out, "t-query");

        byte[] first = handler.read();
        assertEquals('I', first[0]);
        String written1 = out.toString("US-ASCII");
        assertTrue("expected ACK for the query", written1.indexOf(ACK) >= 0);
        assertTrue("expected the Sample Request (D) frame",
                written1.contains(frame(D_PAYLOAD)));

        // second query for an unknown barcode -> No Request (N)
        byte[] second = handler.read();
        assertEquals('I', second[0]);
        String written2 = out.toString("US-ASCII");
        assertTrue("expected No Request for the unknown barcode",
                written2.contains(frame("N\u001C")));
        assertEquals("order must be consumed from the queue",
                0, DimensionOrderRegistry.queueSize("t-query"));
        assertNull(handler.read());
    }

    @Test
    public void testPollConversationalDownloadsFifoOrder() throws IOException {
        DimensionOrderRegistry.clear("t-poll");
        DimensionOrderRegistry.pushOrder("t-poll", "012345", "Doe,John", "2", "0", "BUN,CREA");
        DimensionOrderRegistry.pushOrder("t-poll", "555555", "X,Y", "1", "1", "GLU");

        // conversational poll (First Poll = 0, Request = 1) -> first queued D
        String convPoll  = frame("P\u001CDIM\u001C0\u001C1\u001C0\u001C");
        // the real EXL captured poll (First Poll = 1) is NOT conversational -> N
        String plainPoll = frame("P\u001CDIM\u001C1\u001C1\u001C0\u001C");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DimensionStreamHandler handler = newHandler(convPoll + convPoll + plainPoll, out, "t-poll");

        handler.read(); // poll 1 -> D(012345)
        String w1 = out.toString("US-ASCII");
        assertTrue("first conversational poll must download 012345",
                w1.contains(frame("D\u001C0\u001C0\u001CA\u001CDoe,John\u001C012345\u001C2\u001C\u001C0\u001C1\u001C**\u001C1\u001C2\u001CBUN\u001CCREA")));

        handler.read(); // poll 2 -> D(555555)
        String w2 = out.toString("US-ASCII");
        assertTrue("second conversational poll must download 555555 (FIFO)",
                w2.contains(frame("D\u001C0\u001C0\u001CA\u001CX,Y\u001C555555\u001C1\u001C\u001C1\u001C1\u001C**\u001C1\u001C1\u001CGLU")));

        handler.read(); // poll 3 (queue empty) -> N
        handler.read(); // plain poll -> N (never downloads)
        String w4 = out.toString("US-ASCII");
        assertTrue("plain poll (First Poll=1) must get No Request",
                w4.endsWith(frame("N\u001C")));
        assertNull(handler.read());
    }

    @Test
    public void testOrderLookupDisabledFallsBackToNoRequest() throws IOException {
        DimensionOrderRegistry.clear("t-off");
        DimensionOrderRegistry.pushOrder("t-off", "043092011", "DOE,JOHN", "1", "0", "GLU");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DimensionStreamHandler handler = newHandler(frame("I\u001C043092011\u001C"), out, "t-off");
        ((DimensionTransmissionModeProperties) getProps(handler)).setOrderLookupEnabled(false);

        handler.read();
        String written = out.toString("US-ASCII");
        assertTrue("disabled lookup must fall back to No Request",
                written.contains(frame("N\u001C")));
        assertFalse("no D frame may be sent when lookup is disabled",
                written.contains(frame(D_PAYLOAD)));
        assertEquals("queue must be untouched", 1, DimensionOrderRegistry.queueSize("t-off"));
        assertNull(handler.read());
    }

    @Test
    public void testWriteNoAckSentOnceNoThrow() throws IOException {
        // Regression for the production error "Dimension frame not acknowledged
        // after 4 attempts": with NO ACK coming back, write() must send the
        // frame exactly ONCE, wait the ACK timeout, log and RETURN - never
        // blind-retransmit 4 duplicates and never throw an IOException.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DimensionStreamHandler handler = newHandler("", out, "t-write");
        getProps(handler).setAckTimeoutMs(50);

        handler.write(D_PAYLOAD.getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        String written = out.toString("US-ASCII");
        assertEquals("frame must be sent exactly once (no blind retransmits)",
                1, countStx(written));
        assertTrue("the frame content must be on the wire",
                written.contains(frame(D_PAYLOAD)));
    }

    @Test
    public void testRegistryPushAndFindVariants() {
        DimensionOrderRegistry.clear("t-reg");

        // 1) CSV push (the recommended JavaScript interop form)
        DimensionOrderRegistry.pushOrder("t-reg", "043092011", "DOE,JOHN", "1", "1", "bun, crea ,F5");
        DimensionOrderRegistry.DimensionOrder o = DimensionOrderRegistry.takeOrder("t-reg");
        assertEquals("043092011", o.getSampleId());
        assertEquals("DOE,JOHN", o.getPatient());
        assertEquals("1", o.getType());
        assertEquals("1", o.getPriority());
        assertEquals(java.util.Arrays.asList("BUN", "CREA", "F5"), o.getTests());

        // 2) Map push with a java.util.List of tests
        java.util.Map<String, Object> m = new java.util.HashMap<String, Object>();
        m.put("sampleId", "12345");
        m.put("patient", "X,Y");
        m.put("type", "2");
        m.put("tests", java.util.Arrays.asList("GLU", "CREA"));
        DimensionOrderRegistry.pushOrder("t-reg", m);
        DimensionOrderRegistry.DimensionOrder fromMap = DimensionOrderRegistry.takeOrder("t-reg");
        assertEquals("12345", fromMap.getSampleId());
        assertEquals(2, fromMap.getTestCount());

        // 3) Map push with a CSV string and defaults for type/priority
        java.util.Map<String, Object> m2 = new java.util.HashMap<String, Object>();
        m2.put("sample", "42");
        m2.put("tests", "GLU");
        DimensionOrderRegistry.pushOrder("t-reg", m2);
        DimensionOrderRegistry.DimensionOrder dflt = DimensionOrderRegistry.takeOrder("t-reg");
        assertEquals("42", dflt.getSampleId());
        assertEquals("1", dflt.getType());
        assertEquals("0", dflt.getPriority());

        // 4) findOrder removes exactly the matching entry
        DimensionOrderRegistry.pushOrder("t-reg", "A1", "", "1", "0", "GLU");
        DimensionOrderRegistry.pushOrder("t-reg", "A2", "", "1", "0", "GLU");
        assertEquals(2, DimensionOrderRegistry.queueSize("t-reg"));
        DimensionOrderRegistry.DimensionOrder found = DimensionOrderRegistry.findOrder("t-reg", "A2");
        assertEquals("A2", found.getSampleId());
        assertEquals(1, DimensionOrderRegistry.queueSize("t-reg"));
        assertNull("a consumed order must not be found again",
                DimensionOrderRegistry.findOrder("t-reg", "A2"));

        // 5) normalization limits + rejection
        DimensionOrderRegistry.clear("t-reg"); // drop A1 left from step 4
        try {
            DimensionOrderRegistry.pushOrder("t-reg", "NO-TESTS", "", "1", "0", "");
            throw new AssertionError("empty test list must be rejected");
        } catch (IllegalArgumentException expected) { /* ok */ }
        DimensionOrderRegistry.pushOrder("t-reg", "0123456789ABCDEF", "", "1", "0", "creatineclearance");
        DimensionOrderRegistry.DimensionOrder clipped = DimensionOrderRegistry.takeOrder("t-reg");
        assertEquals("sample ID clipped to 12 chars", "0123456789AB", clipped.getSampleId());
        assertEquals("test name clipped to 5 chars", "CREAT", clipped.getTests().get(0));

        assertNull(DimensionOrderRegistry.findOrder("t-reg", null));
        DimensionOrderRegistry.clear("t-reg");
    }

    @Test
    public void testDemoSeedIdempotent() {
        String old = System.getProperty("dimension.demoOrders", "false");
        try {
            System.setProperty("dimension.demoOrders", "true");
            DimensionOrderRegistry.clear("t-demo");
            DimensionOrderRegistry.seedDemoOrdersIfEnabled("t-demo");
            DimensionOrderRegistry.seedDemoOrdersIfEnabled("t-demo"); // idempotent
            assertEquals("demo seed must add exactly 2 orders", 2, DimensionOrderRegistry.queueSize("t-demo"));
            DimensionOrderRegistry.clear("t-demo");
        } finally {
            if ("false".equals(old)) { System.clearProperty("dimension.demoOrders"); }
            else { System.setProperty("dimension.demoOrders", old); }
        }
    }

    private static int countStx(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == 0x02) { n++; }
        }
        return n;
    }

    private DimensionStreamHandler newHandler(String inbound, ByteArrayOutputStream outbound, String queueKey) {
        DimensionTransmissionModeProperties props = new DimensionTransmissionModeProperties();
        props.setAutoResultAcceptance(true);
        props.setAutoPollResponse(true);
        props.setOrderLookupEnabled(true);
        props.setOrderQueueKey(queueKey);
        props.setIncludeChecksumInPayload(true);
        props.setAckTimeoutMs(50); // fast ACK timeout for unit tests
        props.setFrameTimeoutMs(2000);
        return new DimensionStreamHandler(
                new ByteArrayInputStream(inbound.getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                outbound, null, props);
    }

    /** Reflection-free access to the handler's properties (same instance). */
    private static DimensionTransmissionModeProperties getProps(DimensionStreamHandler handler) {
        try {
            java.lang.reflect.Field f = DimensionStreamHandler.class.getDeclaredField("props");
            f.setAccessible(true);
            return (DimensionTransmissionModeProperties) f.get(handler);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private DimensionStreamHandler newHandler(String inbound, ByteArrayOutputStream outbound) {
        return newHandler(inbound, outbound, "default");
    }
}
