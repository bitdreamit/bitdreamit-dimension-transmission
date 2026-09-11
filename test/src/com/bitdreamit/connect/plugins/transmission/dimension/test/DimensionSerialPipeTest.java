package com.bitdreamit.connect.plugins.transmission.dimension.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import com.bitdreamit.connect.plugins.transmission.dimension.server.DimensionOrderRegistry;
import com.bitdreamit.connect.plugins.transmission.dimension.server.DimensionStreamHandler;
import com.bitdreamit.connect.plugins.transmission.dimension.shared.DimensionTransmissionModeProperties;

/**
 * Serial-transport integration test for DimensionStreamHandler.
 *
 * Proves the bitdreamit-dimension-transmission plugin works UNCHANGED over a
 * serial line (RS-232 style byte-drip) when driven exactly the way
 * SerialSourceConnector.providerReadLoop() drives a Mirth TransmissionModeProvider:
 *
 *   providerReadLoop                         (this test = the instrument side)
 *   ----------------------------             ------------------------------
 *   handler.read()  <-- PipedInputStream <-- analyzerToHandler (drip 2 bytes/2ms)
 *   handler.write() -> PipedOutputStream  -> fromHandler    -> auto-ACK
 *
 * Frames used are the user's REAL production captures, already verified by
 * DimensionSerialSelfTest against the PN D00396 checksum arithmetic
 * (poll chk 47, No Request chk 6A, Acceptance chk E2).
 */
public class DimensionSerialPipeTest {

    private static final char FS  = 0x1C;
    private static final byte STX = 0x02;
    private static final byte ETX = 0x03;
    private static final byte ENQ = 0x05;
    private static final byte ACK = 0x06;
    private static final byte NAK = 0x15;

    private static final String POLL_BODY  = "P" + FS + "DIM" + FS + "0" + FS + "1" + FS + "0" + FS;
    private static final String RESULT_BODY =
              "R" + FS + "*" + FS + FS + "61" + FS + "1" + FS + FS + "0" + FS
            + "281107111125" + FS + "1" + FS + "1" + FS + "2" + FS
            + "GLUC" + FS + "108" + FS + "mg/dL" + FS + FS
            + "CRE2" + FS + "1.40" + FS + "mg/dL" + FS + FS;
    private static final String CAL_BODY =
              "C" + FS + "AST" + FS + "U/L" + FS + "GA7033" + FS + "1" + FS + "2" + FS
            + "01-09-26" + FS + "083903111125" + FS + "1" + FS + "0" + FS + "5" + FS
            + "-4" + FS + "-4" + FS + FS + FS + FS + "3" + FS + "0" + FS + "3" + FS
            + "-2" + FS + "-1" + FS + "-1" + FS + "54" + FS + "3" + FS + "-125" + FS
            + "-125" + FS + "-126" + FS + "469" + FS + "3" + FS + "-15" + FS + "-15" + FS + "-16" + FS;

    // =====================================================================
    // Shared line harness
    // =====================================================================

    /** One bidirectional "serial line" with the handler on one side. */
    private static final class Line {
        final PipedInputStream handlerIn = new PipedInputStream();
        final PipedOutputStream analyzerOut = new PipedOutputStream();
        final PipedInputStream analyzerIn = new PipedInputStream();
        final PipedOutputStream handlerOut = new PipedOutputStream();
        final DimensionTransmissionModeProperties props;
        final DimensionStreamHandler handler;
        final List<byte[]> dispatched = Collections.synchronizedList(new ArrayList<byte[]>());
        final List<String> instrumentGot = Collections.synchronizedList(new ArrayList<String>());
        final AtomicBoolean running = new AtomicBoolean(true);
        Thread driver;
        Thread responder;
        private int keyCounter = 0;

        Line() throws IOException {
            analyzerOut.connect(handlerIn);
            handlerOut.connect(analyzerIn);
            props = new DimensionTransmissionModeProperties();
            props.setOrderQueueKey("pipe-test-" + System.nanoTime() + "-" + (keyCounter++));
            handler = new DimensionStreamHandler(handlerIn, handlerOut, null, props);
        }

        /** Start the driver thread - a faithful copy of providerReadLoop's core. */
        void startDriver() {
            driver = new Thread(new Runnable() {
                public void run() {
                    while (running.get()) {
                        try {
                            byte[] message = handler.read();   // exactly like providerReadLoop
                            if (message == null) {
                                return;                        // clean EOF (port closed)
                            }
                            if (message.length > 0) {
                                dispatched.add(message);
                            }
                        } catch (IOException e) {
                            return;                            // connector would log + recover
                        }
                    }
                }
            }, "serial-providerReadLoop");
            driver.start();
        }

        /** Start the instrument-side responder: ACKs every complete frame it receives. */
        void startResponder() {
            responder = new Thread(new Runnable() {
                public void run() {
                    StringBuilder frameBuf = new StringBuilder();
                    boolean inFrame = false;
                    try {
                        int b;
                        while (running.get() && (b = analyzerIn.read()) != -1) {
                            if (b == STX) {
                                frameBuf.setLength(0);
                                frameBuf.append((char) b);
                                inFrame = true;
                            } else if (inFrame) {
                                frameBuf.append((char) (b & 0xFF));
                                if (b == ETX) {
                                    inFrame = false;
                                    instrumentGot.add(frameBuf.toString());
                                    ackLine();                 // instrument ACKs the frame
                                }
                            } else if (b == ACK || b == NAK) {
                                instrumentGot.add(String.valueOf((char) b));
                            }
                            // other stray bytes: real instruments discard them too
                        }
                    } catch (IOException ignored) {
                        // pipe closed - thread ends
                    }
                }
            }, "instrument-responder");
            responder.start();
        }

        /** Write bytes onto the line (drip = 9600-baud style fragmentation). */
        void drip(final byte[] data, final int chunk, final long pauseMs) throws Exception {
            int i = 0;
            while (i < data.length) {
                int len = Math.min(chunk, data.length - i);
                synchronized (analyzerOut) {
                    analyzerOut.write(data, i, len);
                    analyzerOut.flush();
                }
                i += len;
                Thread.sleep(pauseMs);
            }
        }

        void sendFrame(String body) throws Exception {
            drip(frame(body), 2, 2);
        }

        private void ackLine() throws IOException {
            synchronized (analyzerOut) {
                analyzerOut.write(ACK);
                analyzerOut.flush();
            }
        }

        void closeLine() throws InterruptedException, IOException {
            running.set(false);
            synchronized (analyzerOut) {
                analyzerOut.close();            // handler.read() -> clean EOF -> driver exits
            }
            if (driver != null) driver.join(3000);
            if (responder != null) {
                responder.join(300);
                if (responder.isAlive()) responder.interrupt();
            }
            handlerOut.close();
        }
    }

    // =====================================================================
    // Frame helpers (identical arithmetic to the handler / serial provider)
    // =====================================================================

    static String checksum(String body) {
        int sum = 0;
        for (int i = 0; i < body.length(); i++) {
            sum += (body.charAt(i) & 0xFF);
        }
        return String.format("%02X", sum & 0xFF);
    }

    static byte[] frame(String body) {
        String chk = checksum(body);
        byte[] out = new byte[body.length() + 4];
        int i = 0;
        out[i++] = STX;
        for (int j = 0; j < body.length(); j++) out[i++] = (byte) body.charAt(j);
        out[i++] = (byte) chk.charAt(0);
        out[i++] = (byte) chk.charAt(1);
        out[i] = ETX;
        return out;
    }

    static String str(byte[] b) {
        try { return new String(b, "US-ASCII"); } catch (Exception e) { return ""; }
    }

    static boolean contains(List<byte[]> list, String needle) {
        synchronized (list) {
            for (byte[] b : list) {
                if (str(b).contains(needle)) return true;
            }
        }
        return false;
    }

    interface Cond { boolean ok(); }

    static void waitFor(String what, Cond cond, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.ok()) return;
            try { Thread.sleep(20); } catch (InterruptedException e) { break; }
        }
        fail("Timed out after " + timeoutMs + " ms waiting for: " + what);
    }

    // =====================================================================
    // Tests - every test = one full serial conversation
    // =====================================================================

    /** Manual check: the checksum arithmetic matches PN D00396 (locked values). */
    @Test
    public void checksumArithmeticMatchesManual() {
        assertEquals("47", checksum(POLL_BODY));                          // user's live poll
        assertEquals("6A", checksum("N" + FS));                           // No Request
        assertEquals("E2", checksum("M" + FS + "A" + FS + FS));           // Result Acceptance
        assertEquals("43", checksum(RESULT_BODY));                        // user's live result
    }

    /** The analyzer's repeating poll -> DLC ACK + auto "No Request" (N 6A). */
    @Test
    public void pollOverDrippedSerialLineGetsNoRequest() throws Exception {
        Line line = new Line();
        line.startDriver();
        line.startResponder();
        line.sendFrame(POLL_BODY);

        waitFor("poll payload dispatched to channel", new Cond() { public boolean ok() {
            return !line.dispatched.isEmpty() && str(line.dispatched.get(0)).startsWith("P"); } }, 5000);
        waitFor("instrument received N frame", new Cond() { public boolean ok() {
            return line.instrumentGot.contains("\u0002N\u001C6A\u0003"); } }, 5000);

        line.closeLine();
        assertEquals("poll dispatched exactly once", 1, line.dispatched.size());
        assertTrue("ACK must precede the N frame",
                line.instrumentGot.indexOf(String.valueOf((char) ACK))
              < line.instrumentGot.indexOf("\u0002N\u001C6A\u0003"));
    }

    /** User's real R frame (GLUC 108 / CRE2 1.40) -> ACK + M-A E2 acceptance. */
    @Test
    public void resultFrameOverSerialGetsAutoAcceptance() throws Exception {
        Line line = new Line();
        line.startDriver();
        line.startResponder();
        line.sendFrame(RESULT_BODY);

        waitFor("R payload dispatched", new Cond() { public boolean ok() {
            return contains(line.dispatched, "GLUC") && contains(line.dispatched, "1.40"); } }, 5000);
        waitFor("instrument received M-A acceptance", new Cond() { public boolean ok() {
            return line.instrumentGot.contains("\u0002M\u001CA\u001C\u001CE2\u0003"); } }, 5000);

        line.closeLine();
        String payload = str(line.dispatched.get(0));
        assertTrue("payload keeps both analytes:\n" + payload,
                payload.contains("GLUC") && payload.contains("CRE2"));
        assertTrue("ACK sent before acceptance frame",
                line.instrumentGot.indexOf(String.valueOf((char) ACK))
              < line.instrumentGot.indexOf("\u0002M\u001CA\u001C\u001CE2\u0003"));
    }

    /** User's real C frame (AST calibration record) -> ACK + M-A E2 acceptance. */
    @Test
    public void calibrationFrameOverSerialGetsAutoAcceptance() throws Exception {
        Line line = new Line();
        line.startDriver();
        line.startResponder();
        line.sendFrame(CAL_BODY);

        waitFor("C payload dispatched", new Cond() { public boolean ok() {
            return contains(line.dispatched, "GA7033"); } }, 5000);
        waitFor("instrument received M-A acceptance", new Cond() { public boolean ok() {
            return line.instrumentGot.contains("\u0002M\u001CA\u001C\u001CE2\u0003"); } }, 5000);

        line.closeLine();
    }

    /** Conversational poll downloads a queued order (D frame), then N again. */
    @Test
    public void conversationalPollDownloadsOrderThenNoRequest() throws Exception {
        Line line = new Line();
        DimensionOrderRegistry.clear(line.props.getOrderQueueKey());
        DimensionOrderRegistry.pushOrder(line.props.getOrderQueueKey(),
                "043092011", "Doe^John", "S", "R", "GLUC,CRE2");
        line.startDriver();
        line.startResponder();

        line.sendFrame(POLL_BODY);                          // 1st poll -> D frame
        waitFor("instrument received D frame", new Cond() { public boolean ok() {
            boolean found = false;
            synchronized (line.instrumentGot) {
                for (String s : line.instrumentGot) {
                    if (s.startsWith("\u0002D\u001C0\u001C0\u001CA\u001C")) { found = true; break; }
                }
            }
            return found; } }, 5000);

        line.sendFrame(POLL_BODY);                          // 2nd poll (queue empty) -> N
        waitFor("instrument received N after queue drained", new Cond() { public boolean ok() {
            return line.instrumentGot.contains("\u0002N\u001C6A\u0003"); } }, 5000);

        line.closeLine();
        boolean dSeen = false;
        synchronized (line.instrumentGot) {
            for (String s : line.instrumentGot) {
                if (s.startsWith("\u0002D\u001C0\u001C0\u001CA\u001C")) {
                    assertTrue("D frame carries sample id:\n" + s, s.contains("\u001C043092011\u001C"));
                    assertTrue("D frame carries both tests:\n" + s, s.contains("GLUC\u001CCRE2"));
                    assertTrue("D frame ends with ETX", s.charAt(s.length() - 1) == ETX);
                    // v2.0.2 doc-exact layout (PN D00396 Table 1-7 p.1-5): the body
                    // ends with a trailing FS and the checksum covers it - the same
                    // rule the analyzer applies to every LIS frame (manual p.1-6).
                    String wire = s.substring(1, s.length() - 1);          // strip STX/ETX
                    String body = wire.substring(0, wire.length() - 2);    // strip CHK
                    String chk  = wire.substring(wire.length() - 2);
                    assertTrue("D frame body must end with trailing FS:\n" + s,
                            body.endsWith(String.valueOf(FS)));
                    assertEquals("D frame checksum must cover the trailing FS:\n" + s,
                            checksum(body), chk);
                    dSeen = true;
                }
            }
        }
        assertTrue("D frame must have been sent", dSeen);
    }

    /** Manual barcode entry: analyzer scans -> I frame -> D frame echoes the scanned ID. */
    @Test
    public void barcodeQueryEchoesScannedId() throws Exception {
        Line line = new Line();
        DimensionOrderRegistry.clear(line.props.getOrderQueueKey());
        DimensionOrderRegistry.pushOrder(line.props.getOrderQueueKey(),
                "043092011", "Doe^John", "S", "R", "GLUC");
        line.startDriver();
        line.startResponder();

        line.sendFrame("I" + FS + "043092011" + FS);        // barcode scan at the instrument

        waitFor("instrument received D frame for scan", new Cond() { public boolean ok() {
            boolean found = false;
            synchronized (line.instrumentGot) {
                for (String s : line.instrumentGot) {
                    if (s.startsWith("\u0002D") && s.contains("043092011")) { found = true; break; }
                }
            }
            return found; } }, 5000);

        line.closeLine();
    }

    /** Tolerance: some analyzers omit the trailing FS - the scan must still resolve. */
    @Test
    public void barcodeQueryToleratesMissingTrailingFs() throws Exception {
        Line line = new Line();
        DimensionOrderRegistry.clear(line.props.getOrderQueueKey());
        DimensionOrderRegistry.pushOrder(line.props.getOrderQueueKey(),
                "043092011", "Doe^John", "S", "R", "GLUC");
        line.startDriver();
        line.startResponder();

        line.sendFrame("I" + FS + "043092011");             // NO trailing FS before chk

        waitFor("instrument received D frame (tolerant parse)", new Cond() { public boolean ok() {
            boolean found = false;
            synchronized (line.instrumentGot) {
                for (String s : line.instrumentGot) {
                    if (s.startsWith("\u0002D") && s.contains("043092011")) { found = true; break; }
                }
            }
            return found; } }, 5000);

        line.closeLine();
    }

    /** Corrupted checksum -> NAK to the instrument, good retransmit -> ACK + M-A. */
    @Test
    public void badChecksumNaksThenGoodRetransmitAccepted() throws Exception {
        Line line = new Line();
        line.startDriver();
        line.startResponder();

        byte[] bad = frame("R" + FS + "x" + FS);
        bad[bad.length - 3] = '0';
        bad[bad.length - 2] = '0';                          // corrupt the 2 chk chars, keep ETX
        line.drip(bad, 2, 2);

        waitFor("instrument got NAK", new Cond() { public boolean ok() {
            return line.instrumentGot.contains(String.valueOf((char) NAK)); } }, 5000);

        line.sendFrame(RESULT_BODY);                        // instrument retransmits correctly

        waitFor("good frame accepted after NAK", new Cond() { public boolean ok() {
            return contains(line.dispatched, "GLUC"); } }, 5000);
        waitFor("M-A acceptance after recovery", new Cond() { public boolean ok() {
            return line.instrumentGot.contains("\u0002M\u001CA\u001C\u001CE2\u0003"); } }, 5000);

        line.closeLine();
        assertEquals("corrupt frame must NOT reach the channel", 1, line.dispatched.size());
    }

    /** Stray ENQ outside a frame -> ACK; stray ACK consumed without dispatch. */
    @Test
    public void strayEnqGetsAckAndStrayAckConsumed() throws Exception {
        Line line = new Line();
        line.startDriver();
        line.startResponder();

        synchronized (line.analyzerOut) {
            line.analyzerOut.write(ENQ);
            line.analyzerOut.flush();
        }
        waitFor("instrument got ACK for ENQ", new Cond() { public boolean ok() {
            return line.instrumentGot.contains(String.valueOf((char) ACK)); } }, 5000);

        synchronized (line.analyzerOut) {
            line.analyzerOut.write(ACK);                    // stray ACK - must be consumed
            line.analyzerOut.flush();
        }
        Thread.sleep(300);

        line.closeLine();
        assertEquals("no dispatch for control bytes", 0, line.dispatched.size());
    }

    /** Destination direction: handler.write() sends a framed D frame and waits for the ACK. */
    @Test
    public void outboundWriteFramingWaitsForInstrumentAck() throws Exception {
        final Line line = new Line();
        line.startResponder();                              // instrument ACKs whatever arrives

        final AtomicBoolean done = new AtomicBoolean(false);
        final AtomicBoolean failed = new AtomicBoolean(false);
        Thread sender = new Thread(new Runnable() {
            public void run() {
                try {
                    line.handler.write(("D" + FS + "0" + FS + "0" + FS + "A" + FS
                            + "Doe^John" + FS + "043092011" + FS + "S" + FS + FS + "R"
                            + FS + "1" + FS + "**" + FS + "1" + FS + "1" + FS + "GLUC").getBytes("US-ASCII"));
                    done.set(true);
                } catch (IOException e) {
                    failed.set(true);
                }
            }
        }, "outbound-write");
        sender.start();
        sender.join(5000);

        line.running.set(false);
        synchronized (line.analyzerOut) { line.analyzerOut.close(); }
        line.handlerOut.close();

        assertTrue("write() must ACK-complete without IOException", done.get() && !failed.get());
        boolean framedD = false;
        synchronized (line.instrumentGot) {
            for (String s : line.instrumentGot) {
                if (s.startsWith("\u0002D") && s.contains("043092011") && s.endsWith(String.valueOf((char) ETX))) {
                    framedD = true;
                }
            }
        }
        assertTrue("instrument must receive the framed D frame", framedD);
    }
}
