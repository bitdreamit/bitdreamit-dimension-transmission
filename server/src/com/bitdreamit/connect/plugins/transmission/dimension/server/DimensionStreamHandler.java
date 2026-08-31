package com.bitdreamit.connect.plugins.transmission.dimension.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketException;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.util.Arrays;

import org.apache.log4j.Logger;

import com.bitdreamit.connect.plugins.transmission.dimension.shared.DimensionConstants;
import com.bitdreamit.connect.plugins.transmission.dimension.shared.DimensionTransmissionModeProperties;
import com.mirth.connect.donkey.server.message.StreamHandler;
import com.mirth.connect.donkey.server.message.batch.BatchStreamReader;

/**
 * Siemens Dimension native-protocol StreamHandler.
 *
 * <p>Implements the Dimension data-link control (DLC) layer exactly as
 * specified in "Dimension Clinical Chemistry System Interface
 * Specifications" (PN D00396, Rev. 2):</p>
 *
 * <pre>
 *     Frame : <STX> TYPE <FS> data ... <FS> <CHK> <ETX>
 *     CHK   : 8-bit sum (mod 256) of every char between STX and CHK,
 *             rendered as two ASCII hex characters. Uppercase per the
 *             manual; lower-case hex received from the instrument is
 *             accepted as well (case-insensitive comparison).
 *     ACK   : one 0x06 byte per valid frame, sent immediately.
 *     NAK   : one 0x15 byte per invalid checksum; sender retransmits
 *             the frame up to 4 times.
 *     ENQ   : sent by the receiver when it expected ACK/NAK and saw a
 *             line error; answered here with ACK when auto-enq is on.
 * </pre>
 *
 * <p>Unlike ASTM E1381 there is NO session establishment, NO frame
 * sequencing (0-7) and NO EOT - every frame is acknowledged individually,
 * which makes this handler nearly stateless (only a NAK-retry counter).</p>
 *
 * <p>The input stream is wrapped in a {@link PushbackInputStream} so that
 * bytes consumed while waiting for an ACK (for example the STX of the
 * instrument's next frame) can be pushed back and never lost.</p>
 *
 * <p>Optional application-level auto responses (the instrument requires
 * them in Send/Receive and Send ID/Receive modes):</p>
 * <ul>
 *   <li>Result (R) / Calibration Result (C) frame received ->
 *       send Result Acceptance {@code <STX>M<FS>A<FS><FS>E2<ETX>}
 *       right after the DLC ACK (the instrument starts a 1-second timer
 *       for it and reports error 320 if it never arrives).</li>
 *   <li>Poll (P) / Query (I) frame received ->
 *       send No Request {@code <STX>N<FS>6A<ETX>} ("nothing to download").
 *       Disable via properties when the channel itself builds Sample
 *       Request (D) responses for order download.</li>
 * </ul>
 */
public class DimensionStreamHandler extends StreamHandler {

    private static final Logger logger = Logger.getLogger(DimensionStreamHandler.class);

    private final DimensionTransmissionModeProperties props;
    private final PushbackInputStream in;

    /** Consecutive NAKs issued for the frame currently being received. */
    private int nakRetries = 0;

    public DimensionStreamHandler(InputStream inputStream,
                                  OutputStream outputStream,
                                  BatchStreamReader batchStreamReader,
                                  DimensionTransmissionModeProperties props) {
        super(inputStream, outputStream, batchStreamReader);
        this.props = props;
        this.in = new PushbackInputStream(inputStream, 8);
    }

    // ==================================================================
    // READ (receive one frame from the instrument)
    // ==================================================================

    @Override
    public byte[] read() throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        boolean inFrame = false;
        long frameStartTime = System.currentTimeMillis();
        int frameTimeout = props.getFrameTimeoutMs() > 0 ? props.getFrameTimeoutMs()
                                                         : DimensionConstants.DEFAULT_FRAME_TIMEOUT_MS;

        while (true) {
            if (inFrame && System.currentTimeMillis() - frameStartTime > frameTimeout) {
                logger.error("Dimension frame timeout (" + frameTimeout + " ms) - discarding partial frame");
                throw new IOException("Dimension frame timeout");
            }

            int b;
            try {
                b = in.read();
            } catch (SocketException e) {
                // The instrument (or the Administrator, on a channel
                // redeploy/stop) closed the TCP connection while this thread
                // was blocked waiting for the next frame. That is a normal
                // disconnect, not a protocol error: return a clean EOF instead
                // of letting TcpReceiver log "SocketException: Socket closed"
                // at ERROR level on every disconnect.
                logger.info("Dimension socket closed while waiting for a frame - clean disconnect");
                return null;
            }

            if (b == -1) {
                if (inFrame) {
                    throw new IOException("EOF inside Dimension frame");
                }
                return null; // clean EOF
            }

            if (b == props.getStartOfFrameByte()) {
                // Start of a new frame - discard anything buffered before STX.
                // STX is kept in the buffer and stripped after ETX arrives.
                frame.reset();
                frame.write(b);
                inFrame = true;
                frameStartTime = System.currentTimeMillis();
                continue;
            }

            if (!inFrame) {
                // Outside a frame only control bytes are meaningful.
                handleStrayByte(b);
                continue;
            }

            if (b == props.getEndOfFrameByte()) {
                // Frame complete. raw = STX + payload bytes (ETX is NOT in the
                // buffer - it was consumed by this branch).
                byte[] raw = frame.toByteArray();
                inFrame = false;
                frame.reset();

                if (raw.length < 4) {
                    // Shorter than STX + TYPE + CHK(2)
                    logger.warn("Dimension frame too short (" + raw.length + " bytes) - NAK");
                    nakFrame();
                    continue;
                }

                byte[] payload = new byte[raw.length - 1]; // without STX
                System.arraycopy(raw, 1, payload, 0, payload.length);

                if (props.isUseChecksum() && !validateChecksum(payload)) {
                    logger.warn("Dimension checksum mismatch on frame type '"
                            + (char) (payload[0] & 0xFF) + "' - NAK (retry "
                            + Math.max(0, nakRetries) + "/" + props.getMaxRetransmissions() + ")");
                    nakFrame();
                    continue; // instrument retransmits the same frame
                }

                // Frame is good: ACK it immediately (1-second instrument timer),
                // then fire the optional application-level responses.
                sendByte(props.getPositiveAckByte());
                nakRetries = 0;

                autoRespond(payload);

                if (!props.isIncludeChecksumInPayload() && payload.length > 2) {
                    byte[] stripped = new byte[payload.length - 2];
                    System.arraycopy(payload, 0, stripped, 0, stripped.length);
                    return stripped;
                }
                return payload;
            }

            // Regular payload byte
            frame.write(b);
        }
    }

    /**
     * Bytes outside a frame: ENQ requests a retry (answer ACK), stray
     * ACK/NAK are the instrument acknowledging OUR frames (consume).
     */
    private void handleStrayByte(int b) throws IOException {
        if (b == props.getEnquiryByte()) {
            logger.debug("ENQ received outside frame");
            if (props.isAutoEnqAck()) {
                sendByte(props.getPositiveAckByte());
            }
        } else if (b == props.getPositiveAckByte() || b == props.getNegativeAckByte()) {
            logger.debug("Stray ACK/NAK consumed outside frame (instrument acknowledged our response)");
        } else {
            logger.debug("Discarded stray byte outside frame: 0x" + Integer.toHexString(b));
        }
    }

    /** NAK the current frame and abort after the configured retry budget. */
    private void nakFrame() throws IOException {
        sendByte(props.getNegativeAckByte());
        nakRetries++;
        if (nakRetries > props.getMaxRetransmissions()) {
            nakRetries = 0;
            // Instrument error 318 equivalent: give up, let the connector recover.
            throw new IOException("Dimension frame still corrupt after "
                    + props.getMaxRetransmissions() + " NAKs - aborting read cycle");
        }
    }

    /**
     * Application-level auto responses, sent AFTER the DLC ACK exactly in
     * the order the instrument expects them.
     */
    private void autoRespond(byte[] payload) throws IOException {
        if (payload == null || payload.length < 1) {
            return;
        }
        char type = (char) (payload[0] & 0xFF);

        try {
            if ((type == DimensionConstants.MSG_RESULT
                    || type == DimensionConstants.MSG_CALIBRATION_RESULT)
                    && props.isAutoResultAcceptance()) {

                String status = props.getResultAcceptanceStatus();
                if (status == null || status.trim().isEmpty()) {
                    status = "A";
                }
                status = status.trim().substring(0, 1).toUpperCase();

                String acceptancePayload;
                if ("A".equals(status)) {
                    // <STX>M<FS>A<FS><FS>E2<ETX> - checksum verified against the manual
                    acceptancePayload = DimensionConstants.ACCEPTANCE_PAYLOAD_ACCEPT;
                } else {
                    // <STX>M<FS>R<FS>1<FS>24<ETX> - reject, reason 1 ("Not accepted by Computer")
                    acceptancePayload = "M\u001CR\u001C1\u001C";
                }
                logger.debug("Auto Result Acceptance for frame type '" + type + "': " + status);
                sendApplicationFrame(acceptancePayload);

            } else if ((type == DimensionConstants.MSG_POLL
                    || type == DimensionConstants.MSG_QUERY)
                    && props.isAutoPollResponse()) {

                // <STX>N<FS>6A<ETX> - checksum verified against the manual
                logger.debug("Auto No Request response for frame type '" + type + "'");
                sendApplicationFrame(DimensionConstants.NO_REQUEST_PAYLOAD);
            }
        } catch (IOException e) {
            // The inbound frame was valid and ACKed - an auto-response failure
            // must not lose the result. The instrument will retransmit.
            logger.error("Failed to send Dimension auto response: " + e.getMessage());
        }
    }

    // ==================================================================
    // WRITE (send one frame to the instrument, e.g. Sample Request "D")
    // ==================================================================

    @Override
    public void write(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            throw new IOException("Cannot write empty Dimension payload");
        }

        // The channel supplies the payload WITHOUT STX/ETX/checksum, e.g.
        // "D<FS>0<FS>0<FS>A<FS>Doe,John<FS>012345<FS>2<FS>...".
        for (int attempt = 1; attempt <= Math.max(1, props.getMaxRetransmissions()); attempt++) {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(props.getStartOfFrameByte());
            frame.write(data);
            if (props.isUseChecksum()) {
                frame.write(calculateChecksum(data).getBytes("US-ASCII"));
            }
            frame.write(props.getEndOfFrameByte());

            outputStream.write(frame.toByteArray());
            outputStream.flush();
            logger.debug("Dimension frame sent (attempt " + attempt + "), waiting for ACK");

            int response = readAckResponse(props.getAckTimeoutMs() > 0 ? props.getAckTimeoutMs()
                                                                       : DimensionConstants.DEFAULT_ACK_TIMEOUT_MS);

            if (response == props.getPositiveAckByte()) {
                logger.debug("Dimension frame ACKed");
                return;
            } else if (response == props.getNegativeAckByte()) {
                logger.warn("Dimension frame NAKed, retransmitting (attempt " + attempt + ")");
            } else {
                logger.warn("Dimension no ACK within " + props.getAckTimeoutMs()
                        + " ms (contention), retransmitting (attempt " + attempt + ")");
            }
        }

        throw new IOException("Dimension frame not acknowledged after "
                + props.getMaxRetransmissions() + " attempts");
    }

    @Override
    public void commit(boolean success) throws IOException {
        if (!success) {
            // Ask the instrument to retransmit the last frame.
            sendByte(props.getNegativeAckByte());
        }
        // success: the ACK was already sent inside read()
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    /** 8-bit Add-Mod-256 checksum over the payload (chars between STX and CHK). */
    private static String calculateChecksum(byte[] payload) {
        int sum = 0;
        for (byte b : payload) {
            sum += (b & 0xFF);
        }
        return String.format("%02X", sum & 0xFF);
    }

    /**
     * Validates the checksum of a received payload that still carries the
     * two trailing ASCII-hex checksum characters.
     */
    private boolean validateChecksum(byte[] payload) {
        int chkLen = props.getChecksumByteLength() > 0 ? props.getChecksumByteLength() : 2;
        if (payload.length <= chkLen) {
            return false;
        }

        int sum = 0;
        for (int i = 0; i < payload.length - chkLen; i++) {
            sum += (payload[i] & 0xFF);
        }
        String expected = String.format("%02X", sum & 0xFF);

        StringBuilder received = new StringBuilder();
        for (int i = payload.length - chkLen; i < payload.length; i++) {
            received.append((char) (payload[i] & 0xFF));
        }

        boolean ok = expected.equalsIgnoreCase(received.toString());
        if (!ok) {
            logger.debug("Checksum expected " + expected + " but received " + received);
        }
        return ok;
    }

    private void sendByte(int b) throws IOException {
        outputStream.write(b);
        outputStream.flush();
    }

    /**
     * Sends one complete application-level frame (payload without
     * STX/ETX/checksum, e.g. "M\u001CA\u001C\u001C") and briefly waits for
     * the instrument's data-link ACK.
     */
    private void sendApplicationFrame(String payload) throws IOException {
        byte[] payloadBytes = payload.getBytes("US-ASCII");

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(props.getStartOfFrameByte());
        frame.write(payloadBytes);
        if (props.isUseChecksum()) {
            frame.write(calculateChecksum(payloadBytes).getBytes("US-ASCII"));
        }
        frame.write(props.getEndOfFrameByte());

        int ackTimeout = props.getAckTimeoutMs() > 0 ? props.getAckTimeoutMs()
                                                     : DimensionConstants.DEFAULT_ACK_TIMEOUT_MS;

        // Send ONCE; retransmit only on an explicit NAK. On a plain timeout we
        // stop waiting instead of duplicating the frame - a duplicate could
        // race with the instrument's next transmission on the line.
        outputStream.write(frame.toByteArray());
        outputStream.flush();

        int response = readAckResponse(ackTimeout);
        for (int attempt = 1; response == props.getNegativeAckByte() && attempt <= 2; attempt++) {
            logger.debug("Auto response frame NAKed, resending (attempt " + attempt + ")");
            outputStream.write(frame.toByteArray());
            outputStream.flush();
            response = readAckResponse(ackTimeout);
        }

        if (response != props.getPositiveAckByte()) {
            logger.debug("Instrument did not acknowledge the auto response frame (response="
                    + response + ") - continuing");
        }
    }

    /**
     * Waits up to {@code timeout} ms for an ACK/NAK byte. Any other byte is
     * pushed back onto the stream (never lost) and -1 is returned, so the
     * next {@link #read()} sees it in perfect order.
     */
    private int readAckResponse(int timeout) throws IOException {
        long deadline = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < deadline) {
            if (in.available() > 0) {
                int b = in.read();
                if (b == -1) {
                    return -1; // stream closed
                }
                if (b == props.getPositiveAckByte() || b == props.getNegativeAckByte()) {
                    return b;
                }
                // Not an ACK/NAK: push it back so read() handles it in order,
                // then stop waiting for the acknowledgement.
                in.unread(b);
                return -1;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for ACK", e);
            }
        }
        return -1;
    }

    /** Exposed for unit tests. */
    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
