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
 * them in Send/Receive and Send ID/Receive modes) - ALL sent inside the
 * read path, milliseconds after frame receipt, so the instrument's
 * 1-second timers are always met and Mirth's asynchronous processing
 * can never delay a protocol answer:</p>
 * <ul>
 *   <li>Result (R) / Calibration Result (C) frame received ->
 *       send Result Acceptance {@code <STX>M<FS>A<FS><FS>E2<ETX>}
 *       right after the DLC ACK (the instrument starts a 1-second timer
 *       for it and reports error 320 if it never arrives).</li>
 *   <li>Query (I) frame received (barcode scan, Send ID/Receive mode) ->
 *       DYNAMIC order download: look up the scanned sample ID in the
 *       {@link DimensionOrderRegistry}; if found, build and send the
 *       Sample Request {@code <STX>D<FS>...<ETX>} immediately, otherwise
 *       send No Request {@code <STX>N<FS>6A<ETX>}.</li>
 *   <li>Poll (P) frame received ->
 *       on a conversational poll (First Poll = 0, Request = 1) pop the
 *       next queued order from the registry and send its Sample Request;
 *       otherwise No Request. (PN D00396 p.1-8/1-9, p.1-24.)</li>
 * </ul>
 *
 * <p>The channel transformer is therefore PURELY a business formatter
 * (barcode/test mapping to the LIS) - it never builds protocol frames and
 * never touches responseMap. With {@code messageOutputFormat = HL7_V2}
 * (default RAW_FRAME, v2.2.0) the plugin ALSO converts every frame to a
 * standard HL7 v2.x message (DimensionHL7Translator) before dispatch, so
 * the transformer reads msg['OBX']... exactly like an ASTM/HL7 channel.</p>
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
                    if (DimensionWireDebug.enabled(props)) {
                        DimensionWireDebug.in(withEtx(raw),
                                "FRAME TOO SHORT (" + raw.length + " bytes) -> NAK (retry "
                                + (nakRetries + 1) + "/" + props.getMaxRetransmissions() + ")");
                    }
                    nakFrame();
                    continue;
                }

                byte[] payload = new byte[raw.length - 1]; // without STX
                System.arraycopy(raw, 1, payload, 0, payload.length);

                if (props.isUseChecksum() && !validateChecksum(payload)) {
                    logger.warn("Dimension checksum mismatch on frame type '"
                            + (char) (payload[0] & 0xFF) + "' - NAK (retry "
                            + Math.max(0, nakRetries) + "/" + props.getMaxRetransmissions() + ")");
                    if (DimensionWireDebug.enabled(props)) {
                        DimensionWireDebug.in(withEtx(raw),
                                "CHECKSUM FAIL on type '" + (char) (payload[0] & 0xFF)
                                + "' -> NAK (retry " + Math.max(0, nakRetries)
                                + "/" + props.getMaxRetransmissions() + ")");
                    }
                    nakFrame();
                    continue; // instrument retransmits the same frame
                }

                if (DimensionWireDebug.enabled(props)) {
                    DimensionWireDebug.in(withEtx(raw),
                            "valid frame from analyzer, type '"
                            + (char) (payload[0] & 0xFF) + "'");
                }

                // Frame is good: ACK it immediately (1-second instrument timer),
                // then fire the application-level responses (order download,
                // result acceptance) while the instrument timer is still running.
                sendByte(props.getPositiveAckByte());
                nakRetries = 0;

                autoRespond(payload);

                // v2.2.0 - the "ASTM transmission" pattern: convert the frame
                // to a standard HL7 v2.x message INSIDE the plugin so the
                // channel uses the normal HL7 V2.x data type and the transformer
                // reads msg['OBX']... like any ASTM/HL7 channel. The translator
                // never throws (raw payload fallback), so the read loop cannot
                // break on a malformed field.
                if (DimensionHL7Translator.isHl7Output(props)) {
                    String hl7 = DimensionHL7Translator.toHL7(payload, props);
                    if (DimensionWireDebug.enabled(props)) {
                        DimensionWireDebug.dispatch(payload, hl7,
                                "internal conversion: raw frame -> HL7 v2.x (messageOutputFormat=HL7_V2)");
                    }
                    return hl7.getBytes("US-ASCII");
                }

                if (!props.isIncludeChecksumInPayload() && payload.length > 2) {
                    byte[] stripped = new byte[payload.length - 2];
                    System.arraycopy(payload, 0, stripped, 0, stripped.length);
                    if (DimensionWireDebug.enabled(props)) {
                        DimensionWireDebug.dispatch(payload,
                                new String(stripped, java.nio.charset.StandardCharsets.US_ASCII),
                                "dispatched with trailing checksum stripped (RAW_FRAME)");
                    }
                    return stripped;
                }
                if (DimensionWireDebug.enabled(props)) {
                    DimensionWireDebug.dispatch(payload,
                            new String(payload, java.nio.charset.StandardCharsets.US_ASCII),
                            "dispatched as-is (RAW_FRAME, checksum kept)");
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
        boolean wire = DimensionWireDebug.enabled(props);
        if (b == props.getEnquiryByte()) {
            logger.debug("ENQ received outside frame");
            if (wire) {
                DimensionWireDebug.control("WIRE-IN ", b, "ENQ from analyzer"
                        + (props.isAutoEnqAck() ? " - answering ACK (autoEnqAck)" : " - ignored"));
            }
            if (props.isAutoEnqAck()) {
                sendByte(props.getPositiveAckByte());
            }
        } else if (b == props.getPositiveAckByte() || b == props.getNegativeAckByte()) {
            logger.debug("Stray ACK/NAK consumed outside frame (instrument acknowledged our response)");
            if (wire) {
                DimensionWireDebug.control("WIRE-IN ", b,
                        "analyzer acknowledged our frame");
            }
        } else {
            logger.debug("Discarded stray byte outside frame: 0x" + Integer.toHexString(b));
            if (wire) {
                DimensionWireDebug.control("WIRE-IN ", b, "stray byte discarded");
            }
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
     * the order the instrument expects them. All Dimension answers go out
     * HERE, inside the read path (see class javadoc): result acceptance
     * after R/C, dynamic order download or No Request after P/I.
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

            } else if (type == DimensionConstants.MSG_POLL
                    || type == DimensionConstants.MSG_QUERY) {

                // Dynamic bidirectional order download (redesign rev 10):
                // registry lookup -> Sample Request (D), else No Request (N).
                answerPollOrQuery(payload, type);
            }
        } catch (IOException e) {
            // The inbound frame was valid and ACKed - an auto-response failure
            // must not lose the result. The instrument will retransmit.
            logger.error("Failed to send Dimension auto response: " + e.getMessage());
        }
    }

    /**
     * Answers a Poll (P) or Query (I) frame from the dynamic order registry.
     *
     * <ul>
     *   <li>Query [I] (barcode scan): field 1 is the scanned Sample ID -
     *       find + remove the matching order. The D frame echoes the QUERIED
     *       ID (manual p.1-14: "From the computer the Sample ID and Sample ID
     *       Field must match or the message will be rejected").</li>
     *   <li>Poll [P]: only a conversational poll (First Poll = 0 AND
     *       Request = 1, manual p.1-8) downloads; it pops the FIFO head.
     *       Every other poll gets No Request (the documented default).
     *       Disable with -Ddimension.pollDownload=false for pure Send
     *       ID/Receive labs (see {@link #pollDownloadEnabled()}).</li>
     *   <li>No order -> No Request (N) when autoPollResponse is on.</li>
     * </ul>
     */
    private void answerPollOrQuery(byte[] payload, char type) throws IOException {
        String[] fields = splitFields(payload);

        if (props.isOrderLookupEnabled()) {
            String key = props.getOrderQueueKey();
            DimensionOrderRegistry.seedDemoOrdersIfEnabled(key);

            DimensionOrderRegistry.DimensionOrder order = null;
            String sampleId = null;

            if (type == DimensionConstants.MSG_QUERY) {
                // Table 1-18: I | Sample ID | [Segment | Position (enhanced)]
                sampleId = fields.length > 1 ? fields[1] : "";
                order = DimensionOrderRegistry.findOrder(key, sampleId);
                if (order != null) {
                    // echo the scanned ID, not the stored one
                    order = echoSampleId(order, sampleId);
                }
                if (DimensionWireDebug.enabled(props)) {
                    DimensionWireDebug.event("QUERY [I] sampleId='" + sampleId
                            + "' -> registry "
                            + (order != null
                                ? "HIT (" + order.getTestCount() + " test(s))"
                                : "MISS (no queued order for this barcode)")
                            + (order != null && sampleId != null
                                && !sampleId.equals(order.getSampleId())
                                ? ", D echoes scanned ID" : ""));
                }
            } else { // poll - Table 1-11: P | Instrument ID | First Poll | Request | #Carriers
                String firstPoll = fields.length > 2 ? fields[2] : "";
                String request   = fields.length > 3 ? fields[3] : "";
                if (DimensionWireDebug.enabled(props)) {
                    DimensionWireDebug.event("POLL [P] firstPoll='" + firstPoll + "' request='"
                            + request + "'");
                }
                if ("0".equals(firstPoll) && "1".equals(request)) {
                    // DELETE downloads have priority: the manual allows deleting
                    // a request only BEFORE processing starts, so a pending
                    // delete must not wait behind new adds.
                    DimensionOrderRegistry.DimensionOrder del =
                            DimensionOrderRegistry.takeNextDelete(key);
                    if (del != null) {
                        String dPayload = buildSampleRequestPayload(del, 'D');
                        logger.info("Dimension DELETE download for poll: " + del);
                        sendApplicationFrame(dPayload);
                        return;
                    }
                    order = null;
                    if (pollDownloadEnabled()) {
                        order = DimensionOrderRegistry.takeOrder(key);
                        if (order != null) { sampleId = order.getSampleId(); }
                    } else {
                        logger.debug("Poll order download disabled "
                                + "(dimension.pollDownload=false) - No Request");
                    }
                }
            }

            if (order != null && !order.getTests().isEmpty()) {
                String dPayload = buildSampleRequestPayload(order, 'A');
                logger.info("Dimension order download for "
                        + (type == DimensionConstants.MSG_QUERY ? "query " : "poll ")
                        + sampleId + " (" + order.getTestCount() + " test(s))");
                sendApplicationFrame(dPayload);
                // Bookkeeping: the D frame is on the wire - remember it until
                // the instrument's Request Acceptance [M] arrives (the M frame
                // itself is dispatched to the channel transformer, which calls
                // confirmLastDownload / rejectLastDownload).
                DimensionOrderRegistry.markDownloaded(key, order);
                return;
            }
        }

        if (props.isAutoPollResponse()) {
            logger.debug("No order for frame type '" + type + "' - auto No Request");
            sendApplicationFrame(DimensionConstants.NO_REQUEST_PAYLOAD);
        }
    }

    /**
     * Poll-driven order download switch (manual Send/Receive mode).
     *
     * <p>Some labs run the pure Send ID/Receive workflow, where every order
     * must be delivered as the DIRECT answer to a tray/barcode query [I] -
     * the instrument links the downloaded order to the scanned tray position
     * and only then starts the tube. An order delivered by a Request=1 poll
     * is NOT linked to any tray position: the instrument lists it but never
     * runs it, and the later scan of the same barcode gets No Request (the
     * queue is already empty) - the classic "order in list, tube never runs"
     * field report.</p>
     *
     * <p>Setting the JVM system property {@code -Ddimension.pollDownload=false}
     * disables taking ADD orders from the queue on Request=1 polls (such
     * polls then get No Request). Query [I] delivery and DELETE downloads
     * are unaffected. Default: {@code true} (manual Send/Receive behavior,
     * unchanged for existing installs).</p>
     */
    private static boolean pollDownloadEnabled() {
        return Boolean.parseBoolean(
                System.getProperty("dimension.pollDownload", "true").trim());
    }

    /**
     * Builds the Sample Request (D) payload exactly as PN D00396 Table 1-12
     * (p. 1-9) lays it out - NO STX/ETX/checksum (sendApplicationFrame adds
     * those). Layout (FS-separated):
     * <pre>D | Carrier(0) | Loadlist(0) | Transaction | Patient | SampleID |
     *     Type | Location | Priority | #Cups(1) | Cup(**) | Dilution(1) |
     *     #Tests | TestName...</pre>
     *
     * @param transaction 'A' = add the request, 'D' = delete an already
     *        downloaded request (manual: a delete must resend the FULL
     *        request with D in the Transaction field, so the same layout
     *        applies)
     */
    static String buildSampleRequestPayload(DimensionOrderRegistry.DimensionOrder order, char transaction) {
        StringBuilder sb = new StringBuilder();
        sb.append('D')
          .append(FS_CH).append('0')                     // Sample Carrier ID (always 0)
          .append(FS_CH).append('0')                     // Loadlist ID (always 0)
          .append(FS_CH).append(transaction)             // A = Add, D = Delete
          .append(FS_CH).append(nullSafe(order.getPatient()))
          .append(FS_CH).append(nullSafe(order.getSampleId()))
          .append(FS_CH).append(nullSafe(order.getType()))
          .append(FS_CH)                                 // Location (optional, empty)
          .append(FS_CH).append(nullSafe(order.getPriority()))
          .append(FS_CH).append('1')                     // # Of Cups For Sample
          .append(FS_CH).append("**")                    // Cup Position (any cup)
          .append(FS_CH).append('1')                     // Dilution
          .append(FS_CH).append(order.getTestCount());   // # Of Tests
        for (String test : order.getTests()) {
            sb.append(FS_CH).append(test);
        }
        return sb.toString();
    }

    /** Add-transaction convenience overload. */
    static String buildSampleRequestPayload(DimensionOrderRegistry.DimensionOrder order) {
        return buildSampleRequestPayload(order, 'A');
    }

    /** Returns a copy of the order whose sample ID echoes the scanned/queried one. */
    private static DimensionOrderRegistry.DimensionOrder echoSampleId(
            DimensionOrderRegistry.DimensionOrder order, String queriedId) {
        String scanned = queriedId == null ? "" : queriedId.trim();
        if (scanned.isEmpty() || scanned.equals(order.getSampleId())) {
            return order;
        }
        return new DimensionOrderRegistry.DimensionOrder(
                scanned, order.getPatient(), order.getType(),
                order.getPriority(), order.getTests());
    }

    /**
     * Splits the payload (TYPE + trailing checksum included) on FS and trims
     * every field.
     *
     * Tolerant-parse hardening (serial-transport verification, 2025-09):
     * the documented PN D00396 layout always ends the body with a trailing FS
     * before the 2 checksum characters, but the two checksum bytes glue onto
     * the last field when an analyzer omits that FS (or when the checksum is
     * disabled but the frame still carries separators inconsistently). To keep
     * Sample-ID matching exact (manual p.1-14: IDs must match or the message
     * is rejected) we strip a trailing checksum and normalize a missing
     * trailing FS before splitting - same tolerance as the serial connector's
     * built-in DimensionProvider.
     */
    private String[] splitFields(byte[] payload) {
        String s = new String(payload, java.nio.charset.StandardCharsets.US_ASCII);

        if (props.isUseChecksum() && props.isIncludeChecksumInPayload() && s.length() > 2) {
            s = s.substring(0, s.length() - 2);     // drop glued-on checksum chars
        }
        if (s.length() > 0 && s.charAt(s.length() - 1) != FS_CH) {
            s = s + FS_CH;                          // normalize to the documented layout
        }

        String[] parts = s.split("\u001C");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /** Re-attaches the frame-terminator byte for WIRE debug display (read() consumed it). */
    private byte[] withEtx(byte[] raw) {
        byte[] wire = Arrays.copyOf(raw, raw.length + 1);
        wire[raw.length] = (byte) props.getEndOfFrameByte();
        return wire;
    }

    /** FS as a char constant for payload building. */
    private static final char FS_CH = 0x1C;

    /**
     * PN D00396 Table 1-7 (p.1-5, General Message): the transmitted body is
     * {@code STX | TYPE | FS | Data | FS | CHK | ETX} - it ALWAYS ends with a
     * trailing field separator before the 2 checksum characters, and the
     * checksum (p.1-6) is computed on ALL characters between STX and CHK
     * INCLUDING that trailing FS. Every example frame in the manual ends with
     * {@code <FS>CHK} (verified byte-exact: N=6A, M-A=E2, M-R-1=24, and the
     * field captures P...47 / R...43). Frames that omit it may still pass the
     * receiver's DLC re-check (the sum covers whatever was sent), but they are
     * NOT the documented layout, so analyzers with a strict application
     * parser can reject them (error 319, "invalid message"). This guard makes
     * every frame this handler transmits byte-exact with the documentation.
     */
    static byte[] normalizeBody(byte[] body) {
        if (body == null || body.length == 0) {
            return body;
        }
        if (body[body.length - 1] == 0x1C) {
            return body;
        }
        byte[] out = new byte[body.length + 1];
        System.arraycopy(body, 0, out, 0, body.length);
        out[body.length] = 0x1C;
        return out;
    }

    // ==================================================================
    // WRITE (send one frame to the instrument, e.g. Sample Request "D")
    // ==================================================================

    @Override
    public void write(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            throw new IOException("Cannot write empty Dimension payload");
        }
        data = normalizeBody(data);

        // The channel supplies the payload WITHOUT STX/ETX/checksum, e.g.
        // "D<FS>0<FS>0<FS>A<FS>Doe,John<FS>012345<FS>2<FS>...".
        //
        // DLC policy (mirrors sendApplicationFrame): retransmit ONLY on an
        // explicit NAK. A plain timeout is line contention - a blind
        // retransmit would duplicate the frame and can race with the
        // instrument's next transmission. If the instrument really missed
        // the frame it NAKs, ENQs or simply re-polls, which re-triggers
        // write() naturally. A timeout therefore never escalates to an
        // IOException that would tear down the connection.
        int maxAttempts = Math.max(1, props.getMaxRetransmissions());
        int ackTimeout = props.getAckTimeoutMs() > 0 ? props.getAckTimeoutMs()
                                                     : DimensionConstants.DEFAULT_ACK_TIMEOUT_MS;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(props.getStartOfFrameByte());
            frame.write(data);
            if (props.isUseChecksum()) {
                frame.write(calculateChecksum(data).getBytes("US-ASCII"));
            }
            frame.write(props.getEndOfFrameByte());
            // (data was normalized by normalizeBody: trailing FS present, so the
            // checksum covers it exactly like every PN D00396 example frame.)

            byte[] wireFrame = frame.toByteArray();
            long sentAt = System.currentTimeMillis();
            outputStream.write(wireFrame);
            outputStream.flush();
            logger.debug("Dimension frame sent (attempt " + attempt + "), waiting for ACK");
            if (DimensionWireDebug.enabled(props)) {
                DimensionWireDebug.out(wireFrame, "channel/registry frame to analyzer, type '"
                        + (char) (data[0] & 0xFF) + "' (attempt " + attempt + ")");
            }

            int response = readAckResponse(ackTimeout);

            if (response == props.getPositiveAckByte()) {
                logger.debug("Dimension frame ACKed");
                if (DimensionWireDebug.enabled(props)) {
                    DimensionWireDebug.event("frame type '" + (char) (data[0] & 0xFF)
                            + "' ACKed by analyzer in "
                            + (System.currentTimeMillis() - sentAt) + " ms");
                }
                return;
            } else if (response == props.getNegativeAckByte()) {
                logger.warn("Dimension frame NAKed, retransmitting (attempt " + attempt + ")");
                if (DimensionWireDebug.enabled(props)) {
                    DimensionWireDebug.event("frame type '" + (char) (data[0] & 0xFF)
                            + "' NAKed by analyzer after "
                            + (System.currentTimeMillis() - sentAt) + " ms - retransmitting ("
                            + attempt + "/" + maxAttempts + ")");
                }
            } else {
                logger.warn("Dimension no ACK within " + ackTimeout
                        + " ms (contention) - stopping wait, NOT retransmitting");
                if (DimensionWireDebug.enabled(props)) {
                    DimensionWireDebug.event("no ACK within " + ackTimeout
                            + " ms (line contention) - stopping wait, NOT retransmitting");
                }
                return;
            }
        }

        // Every attempt was explicitly NAKed: the instrument refuses this
        // frame. Surface it as a message error so Mirth logs/queues it,
        // instead of silently pretending the transfer succeeded.
        throw new IOException("Dimension frame NAKed "
                + maxAttempts + " times - refused by instrument");
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
        if (DimensionWireDebug.enabled(props)) {
            DimensionWireDebug.control("WIRE-OUT", b,
                (b == props.getPositiveAckByte() ? "ACK to analyzer"
                : b == props.getNegativeAckByte() ? "NAK to analyzer (retransmit request)"
                : "control byte"));
        }
        outputStream.write(b);
        outputStream.flush();
    }

    /**
     * Sends one complete application-level frame (payload without
     * STX/ETX/checksum, e.g. "M\u001CA\u001C\u001C") and briefly waits for
     * the instrument's data-link ACK.
     */
    private void sendApplicationFrame(String payload) throws IOException {
        byte[] payloadBytes = normalizeBody(payload.getBytes("US-ASCII"));

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
        byte[] wireFrame = frame.toByteArray();
        long sentAt = System.currentTimeMillis();
        outputStream.write(wireFrame);
        outputStream.flush();
        if (DimensionWireDebug.enabled(props)) {
            DimensionWireDebug.out(wireFrame, "auto response to analyzer, type '"
                    + (char) payloadBytes[0] + "'");
        }

        int response = readAckResponse(ackTimeout);
        for (int attempt = 1; response == props.getNegativeAckByte() && attempt <= 2; attempt++) {
            logger.debug("Auto response frame NAKed, resending (attempt " + attempt + ")");
            if (DimensionWireDebug.enabled(props)) {
                DimensionWireDebug.event("auto response type '" + (char) payloadBytes[0]
                        + "' NAKed - resending (" + attempt + "/2)");
            }
            outputStream.write(wireFrame);
            outputStream.flush();
            response = readAckResponse(ackTimeout);
        }

        if (response != props.getPositiveAckByte()) {
            logger.debug("Instrument did not acknowledge the auto response frame (response="
                    + response + ") - continuing");
            if (DimensionWireDebug.enabled(props)) {
                DimensionWireDebug.event("auto response type '" + (char) payloadBytes[0]
                        + "' NOT acknowledged within " + ackTimeout + " ms - continuing");
            }
        } else if (DimensionWireDebug.enabled(props)) {
            DimensionWireDebug.event("auto response type '" + (char) payloadBytes[0]
                    + "' ACKed by analyzer in " + (System.currentTimeMillis() - sentAt) + " ms");
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
