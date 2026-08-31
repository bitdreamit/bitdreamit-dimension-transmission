package com.bitdreamit.connect.plugins.transmission.dimension.shared;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.mirth.connect.model.datatype.DataTypePropertyDescriptor;
import com.mirth.connect.model.datatype.PropertyEditorType;
import com.mirth.connect.model.transmission.framemode.FrameModeProperties;

/**
 * Siemens Dimension native transmission-mode properties.
 *
 * <p>This class is the source of truth for every knob exposed to Mirth
 * administrators through the channel editor. It is shared between the
 * server-side provider ({@code DimensionTransmissionModePlugin} /
 * {@code DimensionStreamHandler}) and the client-side provider
 * ({@code DimensionClientProvider} / settings dialog).</p>
 *
 * <p>Every byte-typed property is stored as {@code int} so the value
 * round-trips through Mirth's XML serializer without sign-extension
 * surprises. Hex parsing helpers are null-safe and fall back to the
 * documented default on parse failure.</p>
 *
 * <p><b>Build prerequisite (Mirth 4.5+):</b> the parent class
 * {@code TransmissionModeProperties} (in {@code mirth-client-core.jar})
 * implements {@code com.mirth.connect.donkey.util.purge.Purgable}
 * (in {@code donkey-server.jar}). Both jars MUST be on the compile
 * classpath of this module - see the project README.</p>
 */
public class DimensionTransmissionModeProperties extends FrameModeProperties {

    // --- Frame bytes ---
    private int startOfFrameByte   = DimensionConstants.STX;   // 0x02
    private int endOfFrameByte     = DimensionConstants.ETX;   // 0x03
    private int fieldSeparatorByte = DimensionConstants.FS;    // 0x1C
    private int enquiryByte        = DimensionConstants.ENQ;   // 0x05

    // --- Data link handshake ---
    private boolean useChecksum        = true;
    private int     checksumByteLength = 2;
    private int     positiveAckByte    = DimensionConstants.ACK;  // 0x06
    private int     negativeAckByte    = DimensionConstants.NAK;  // 0x15
    private int     maxRetransmissions = DimensionConstants.DEFAULT_MAX_RETRANSMISSIONS; // 4
    private int     ackTimeoutMs       = DimensionConstants.DEFAULT_ACK_TIMEOUT_MS;      // 1000
    private int     frameTimeoutMs     = DimensionConstants.DEFAULT_FRAME_TIMEOUT_MS;    // 5000

    // --- Application level auto-responses (computer -> instrument) ---
    /**
     * When true, the mode answers every Result (R) / Calibration Result (C)
     * frame with a Result Acceptance message {@code <STX>M<FS>A<FS><FS>E2<ETX>}
     * right after the data-link ACK. This satisfies the instrument's 1-second
     * response timer in Send/Receive and Send ID/Receive modes.
     */
    private boolean autoResultAcceptance = true;
    /** Status byte of the auto Result Acceptance: A = accept, R = reject. */
    private String  resultAcceptanceStatus = "A";
    /**
     * When true, the mode answers Poll (P) and Query (I) frames with a
     * No Request message {@code <STX>N<FS>6A<ETX>} ("nothing to download").
     * Disable this when the LIS downloads orders to the instrument and the
     * channel itself builds Sample Request (D) responses.
     */
    private boolean autoPollResponse = true;
    /** When true, a stray ENQ (0x05) from the instrument is answered with ACK. */
    private boolean autoEnqAck = true;

    // --- Dynamic bidirectional order download (redesign rev 10) ---
    /**
     * When true (default), Poll [P] and Query [I] frames are answered from
     * the {@code DimensionOrderRegistry}: a queued/matching order becomes a
     * Sample Request (D) frame sent inside the read path; an empty registry
     * falls back to No Request (N) when {@code autoPollResponse} is on.
     * The channel transformer never builds protocol frames in this mode.
     */
    private boolean orderLookupEnabled = true;
    /**
     * Registry queue key. Every channel (or connector) can have its own
     * order queue; pushing channels must push with the SAME key. Empty =
     * {@code "default"}.
     */
    private String  orderQueueKey = "default";

    // --- Message dispatch ---
    /**
     * When true the dispatched message still carries the trailing 2-character
     * checksum (payload as received, without STX/ETX). The channel transformer
     * can re-verify it. When false the checksum is stripped before dispatch.
     */
    private boolean includeChecksumInPayload = true;

    // --- Mode ---
    private boolean serverMode = true; // true = listener/receiver, false = sender

    public DimensionTransmissionModeProperties() {
        super(DimensionConstants.PLUGIN_NAME);
        // Set FrameModeProperties fields for Mirth compatibility
        // (the built-in MLLP mode does the same in its constructor).
        setStartOfMessageBytes(String.format("%02X", DimensionConstants.STX));
        setEndOfMessageBytes(String.format("%02X", DimensionConstants.ETX));
    }

    /**
     * Metadata describing every editable property of this transmission mode
     * (display name, description, editor type, default value).
     *
     * <p><b>Not an override.</b> Mirth Connect 4.5+'s
     * {@code TransmissionModeProperties} base class only declares the two
     * {@code Purgable} methods. This method is used by the settings dialog
     * as a single source of truth for field metadata.</p>
     */
    public Map<String, DataTypePropertyDescriptor> getPropertyDescriptors() {
        Map<String, DataTypePropertyDescriptor> props = new LinkedHashMap<>();

        props.put("startOfFrameByte",        new DataTypePropertyDescriptor(formatHex(startOfFrameByte),   "Start of Frame (STX)",          "Start of Transmission byte.", PropertyEditorType.STRING));
        props.put("endOfFrameByte",          new DataTypePropertyDescriptor(formatHex(endOfFrameByte),     "End of Frame (ETX)",            "End of Transmission byte.", PropertyEditorType.STRING));
        props.put("fieldSeparatorByte",      new DataTypePropertyDescriptor(formatHex(fieldSeparatorByte), "Field Separator (FS)",          "Field Separator byte between data fields.", PropertyEditorType.STRING));
        props.put("enquiryByte",             new DataTypePropertyDescriptor(formatHex(enquiryByte),        "Enquiry (ENQ)",                 "Enquiry / retry-request byte.", PropertyEditorType.STRING));
        props.put("useChecksum",             new DataTypePropertyDescriptor(useChecksum,                   "Use Checksum",                  "Validate / compute the 8-bit Add-Mod-256 checksum.", PropertyEditorType.BOOLEAN));
        props.put("checksumByteLength",      new DataTypePropertyDescriptor(checksumByteLength,            "Checksum Byte Length",          "Number of ASCII-hex checksum characters (2).", PropertyEditorType.STRING));
        props.put("positiveAckByte",         new DataTypePropertyDescriptor(formatHex(positiveAckByte),    "Positive Acknowledge (ACK)",    "Data-link positive acknowledge byte.", PropertyEditorType.STRING));
        props.put("negativeAckByte",         new DataTypePropertyDescriptor(formatHex(negativeAckByte),    "Negative Acknowledge (NAK)",    "Data-link negative acknowledge byte.", PropertyEditorType.STRING));
        props.put("maxRetransmissions",      new DataTypePropertyDescriptor(maxRetransmissions,            "Max Retransmissions",           "NAK retries before aborting (protocol allows 4).", PropertyEditorType.STRING));
        props.put("ackTimeoutMs",            new DataTypePropertyDescriptor(ackTimeoutMs,                  "ACK Timeout (ms)",              "Wait for ACK/NAK after sending a frame (protocol timer is 1000 ms).", PropertyEditorType.STRING));
        props.put("frameTimeoutMs",          new DataTypePropertyDescriptor(frameTimeoutMs,                "Frame Timeout (ms)",            "Wait for a complete frame once STX was received.", PropertyEditorType.STRING));
        props.put("autoResultAcceptance",    new DataTypePropertyDescriptor(autoResultAcceptance,          "Auto Result Acceptance",        "Answer R/C frames with <STX>M<FS>A<FS><FS>.. (Result Acceptance).", PropertyEditorType.BOOLEAN));
        props.put("resultAcceptanceStatus",  new DataTypePropertyDescriptor(resultAcceptanceStatus,        "Result Acceptance Status",      "Status of the auto acceptance: A = accept, R = reject.", PropertyEditorType.STRING));
        props.put("autoPollResponse",        new DataTypePropertyDescriptor(autoPollResponse,              "Auto Poll/Query Response",      "Answer P/I frames with <STX>N<FS>.. (No Request).", PropertyEditorType.BOOLEAN));
        props.put("autoEnqAck",              new DataTypePropertyDescriptor(autoEnqAck,                    "Auto ENQ Acknowledge",          "Answer a stray ENQ from the instrument with ACK.", PropertyEditorType.BOOLEAN));
        props.put("orderLookupEnabled",      new DataTypePropertyDescriptor(orderLookupEnabled,            "Dynamic Order Lookup",          "Answer P/I with Sample Request (D) from the DimensionOrderRegistry, else No Request (N).", PropertyEditorType.BOOLEAN));
        props.put("orderQueueKey",           new DataTypePropertyDescriptor(orderQueueKey,                 "Order Queue Key",               "Registry queue key - pushing channels must push with the same key.", PropertyEditorType.STRING));
        props.put("includeChecksumInPayload",new DataTypePropertyDescriptor(includeChecksumInPayload,      "Checksum in Payload",           "Keep the 2-character checksum at the end of the dispatched message.", PropertyEditorType.BOOLEAN));
        props.put("serverMode",              new DataTypePropertyDescriptor(serverMode,                    "Server Mode",                   "true = receiver (listener/source), false = sender.", PropertyEditorType.BOOLEAN));

        return props;
    }

    /**
     * Bulk-loads every property from a descriptor map (inverse of
     * {@link #getPropertyDescriptors()}). Not an override - see above.
     */
    public void setProperties(Map<String, DataTypePropertyDescriptor> properties) {
        if (properties == null) return;

        if (has(properties, "startOfFrameByte"))         this.startOfFrameByte         = parseHex(value(properties, "startOfFrameByte"), startOfFrameByte);
        if (has(properties, "endOfFrameByte"))           this.endOfFrameByte           = parseHex(value(properties, "endOfFrameByte"), endOfFrameByte);
        if (has(properties, "fieldSeparatorByte"))       this.fieldSeparatorByte       = parseHex(value(properties, "fieldSeparatorByte"), fieldSeparatorByte);
        if (has(properties, "enquiryByte"))              this.enquiryByte              = parseHex(value(properties, "enquiryByte"), enquiryByte);
        if (has(properties, "useChecksum"))              this.useChecksum              = toBoolean(value(properties, "useChecksum"), useChecksum);
        if (has(properties, "checksumByteLength"))       this.checksumByteLength       = parseInt(value(properties, "checksumByteLength"), checksumByteLength);
        if (has(properties, "positiveAckByte"))          this.positiveAckByte          = parseHex(value(properties, "positiveAckByte"), positiveAckByte);
        if (has(properties, "negativeAckByte"))          this.negativeAckByte          = parseHex(value(properties, "negativeAckByte"), negativeAckByte);
        if (has(properties, "maxRetransmissions"))       this.maxRetransmissions       = parseInt(value(properties, "maxRetransmissions"), maxRetransmissions);
        if (has(properties, "ackTimeoutMs"))             this.ackTimeoutMs             = parseInt(value(properties, "ackTimeoutMs"), ackTimeoutMs);
        if (has(properties, "frameTimeoutMs"))           this.frameTimeoutMs           = parseInt(value(properties, "frameTimeoutMs"), frameTimeoutMs);
        if (has(properties, "autoResultAcceptance"))     this.autoResultAcceptance     = toBoolean(value(properties, "autoResultAcceptance"), autoResultAcceptance);
        if (has(properties, "resultAcceptanceStatus"))   this.resultAcceptanceStatus   = String.valueOf(value(properties, "resultAcceptanceStatus"));
        if (has(properties, "autoPollResponse"))         this.autoPollResponse         = toBoolean(value(properties, "autoPollResponse"), autoPollResponse);
        if (has(properties, "autoEnqAck"))               this.autoEnqAck               = toBoolean(value(properties, "autoEnqAck"), autoEnqAck);
        if (has(properties, "orderLookupEnabled"))       this.orderLookupEnabled       = toBoolean(value(properties, "orderLookupEnabled"), orderLookupEnabled);
        Object orderKey = has(properties, "orderQueueKey") ? value(properties, "orderQueueKey") : null;
        if (orderKey != null)                            this.orderQueueKey            = String.valueOf(orderKey);
        if (has(properties, "includeChecksumInPayload")) this.includeChecksumInPayload = toBoolean(value(properties, "includeChecksumInPayload"), includeChecksumInPayload);
        if (has(properties, "serverMode"))               this.serverMode               = toBoolean(value(properties, "serverMode"), serverMode);
    }

    private static boolean has(Map<String, DataTypePropertyDescriptor> m, String key) {
        return m.containsKey(key) && m.get(key) != null;
    }

    private static Object value(Map<String, DataTypePropertyDescriptor> m, String key) {
        DataTypePropertyDescriptor d = m.get(key);
        return d == null ? null : d.getValue();
    }

    private static String formatHex(int b) {
        return String.format("0x%02X", b);
    }

    private static int parseHex(Object o, int defaultValue) {
        if (o == null) return defaultValue;
        try {
            String s = o.toString().trim().replace("0x", "").replace("0X", "");
            if (s.isEmpty()) return defaultValue;
            return Integer.parseInt(s, 16) & 0xFF;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static int parseInt(Object o, int defaultValue) {
        if (o == null) return defaultValue;
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(o.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean toBoolean(Object o, boolean defaultValue) {
        if (o == null) return defaultValue;
        if (o instanceof Boolean) return (Boolean) o;
        return Boolean.parseBoolean(o.toString());
    }

    // ------------------------------------------------------------------
    // Getters
    // ------------------------------------------------------------------
    public int getStartOfFrameByte() { return startOfFrameByte; }
    public int getEndOfFrameByte() { return endOfFrameByte; }
    public int getFieldSeparatorByte() { return fieldSeparatorByte; }
    public int getEnquiryByte() { return enquiryByte; }
    public boolean isUseChecksum() { return useChecksum; }
    public int getChecksumByteLength() { return checksumByteLength; }
    public int getPositiveAckByte() { return positiveAckByte; }
    public int getNegativeAckByte() { return negativeAckByte; }
    public int getMaxRetransmissions() { return maxRetransmissions; }
    public int getAckTimeoutMs() { return ackTimeoutMs; }
    public int getFrameTimeoutMs() { return frameTimeoutMs; }
    public boolean isAutoResultAcceptance() { return autoResultAcceptance; }
    public String getResultAcceptanceStatus() { return resultAcceptanceStatus; }
    public boolean isAutoPollResponse() { return autoPollResponse; }
    public boolean isAutoEnqAck() { return autoEnqAck; }
    public boolean isOrderLookupEnabled() { return orderLookupEnabled; }
    public String getOrderQueueKey() { return orderQueueKey; }
    public boolean isIncludeChecksumInPayload() { return includeChecksumInPayload; }
    public boolean isServerMode() { return serverMode; }

    // ------------------------------------------------------------------
    // Setters
    // ------------------------------------------------------------------
    public void setStartOfFrameByte(int startOfFrameByte) { this.startOfFrameByte = startOfFrameByte; }
    public void setEndOfFrameByte(int endOfFrameByte) { this.endOfFrameByte = endOfFrameByte; }
    public void setFieldSeparatorByte(int fieldSeparatorByte) { this.fieldSeparatorByte = fieldSeparatorByte; }
    public void setEnquiryByte(int enquiryByte) { this.enquiryByte = enquiryByte; }
    public void setUseChecksum(boolean useChecksum) { this.useChecksum = useChecksum; }
    public void setChecksumByteLength(int checksumByteLength) { this.checksumByteLength = checksumByteLength; }
    public void setPositiveAckByte(int positiveAckByte) { this.positiveAckByte = positiveAckByte; }
    public void setNegativeAckByte(int negativeAckByte) { this.negativeAckByte = negativeAckByte; }
    public void setMaxRetransmissions(int maxRetransmissions) { this.maxRetransmissions = maxRetransmissions; }
    public void setAckTimeoutMs(int ackTimeoutMs) { this.ackTimeoutMs = ackTimeoutMs; }
    public void setFrameTimeoutMs(int frameTimeoutMs) { this.frameTimeoutMs = frameTimeoutMs; }
    public void setAutoResultAcceptance(boolean autoResultAcceptance) { this.autoResultAcceptance = autoResultAcceptance; }
    public void setResultAcceptanceStatus(String resultAcceptanceStatus) { this.resultAcceptanceStatus = resultAcceptanceStatus; }
    public void setAutoPollResponse(boolean autoPollResponse) { this.autoPollResponse = autoPollResponse; }
    public void setAutoEnqAck(boolean autoEnqAck) { this.autoEnqAck = autoEnqAck; }
    public void setOrderLookupEnabled(boolean orderLookupEnabled) { this.orderLookupEnabled = orderLookupEnabled; }
    public void setOrderQueueKey(String orderQueueKey) { this.orderQueueKey = orderQueueKey; }
    public void setIncludeChecksumInPayload(boolean includeChecksumInPayload) { this.includeChecksumInPayload = includeChecksumInPayload; }
    public void setServerMode(boolean serverMode) { this.serverMode = serverMode; }

    @Override
    public Map<String, Object> getPurgedProperties() {
        Map<String, Object> purged = new HashMap<>();
        purged.put("pluginPointName", DimensionConstants.PLUGIN_NAME);
        purged.put("useChecksum", useChecksum);
        purged.put("maxRetransmissions", maxRetransmissions);
        purged.put("ackTimeoutMs", ackTimeoutMs);
        purged.put("frameTimeoutMs", frameTimeoutMs);
        purged.put("autoResultAcceptance", autoResultAcceptance);
        purged.put("autoPollResponse", autoPollResponse);
        purged.put("autoEnqAck", autoEnqAck);
        purged.put("orderLookupEnabled", orderLookupEnabled);
        purged.put("orderQueueKey", orderQueueKey);
        purged.put("includeChecksumInPayload", includeChecksumInPayload);
        purged.put("serverMode", serverMode);
        return purged;
    }
}
