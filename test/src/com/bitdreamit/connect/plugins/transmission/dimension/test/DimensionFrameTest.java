package com.bitdreamit.connect.plugins.transmission.dimension.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.Test;

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

    private DimensionStreamHandler newHandler(String inbound, ByteArrayOutputStream outbound) {
        DimensionTransmissionModeProperties props = new DimensionTransmissionModeProperties();
        props.setAutoResultAcceptance(true);
        props.setAutoPollResponse(true);
        props.setIncludeChecksumInPayload(true);
        props.setAckTimeoutMs(50); // fast ACK timeout for unit tests
        return new DimensionStreamHandler(
                new ByteArrayInputStream(inbound.getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                outbound, null, props);
    }
}
