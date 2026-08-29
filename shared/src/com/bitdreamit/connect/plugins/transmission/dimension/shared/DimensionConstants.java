package com.bitdreamit.connect.plugins.transmission.dimension.shared;

/**
 * Siemens Dimension (EXL / RxL / Xpand) Native Host Interface - Lower-Layer
 * Protocol Constants.
 *
 * <p>Source: "Dimension Clinical Chemistry System Interface Specifications",
 * Siemens Healthcare Diagnostics, 2011/08 Rev. 2, PN D00396.</p>
 *
 * <p>The Dimension native protocol is NOT ASTM E1381. The main differences
 * that this transmission mode implements:</p>
 *
 * <ul>
 *   <li>Frame layout: {@code <STX> TYPE <FS> data... <FS> <CHK> <ETX>} -
 *       the 2-character ASCII-hex checksum sits INSIDE the frame, immediately
 *       BEFORE the ETX (ASTM places it AFTER ETX followed by CR LF).</li>
 *   <li>Field separator FS is 0x1C and separates EVERY data field.</li>
 *   <li>Checksum = 8-bit binary sum (mod 256) of every character between
 *       STX and CHK, printed as two uppercase ASCII hex characters.</li>
 *   <li>No session establishment: there is no ENQ-driven transfer session,
 *       no frame sequence numbers 0-7 and no EOT. Every single frame is
 *       acknowledged individually with a single ACK (0x06) or NAK (0x15).</li>
 *   <li>On NAK the sender retransmits the same frame up to four times.</li>
 *   <li>1-second ACK/NAK timer after each frame transmission.</li>
 *   <li>Application-level messages: P (Poll), D (Sample Request),
 *       N (No Request), W (Wait), M (Request/Result Acceptance),
 *       I (Query), R (Result), C (Calibration Result).</li>
 * </ul>
 *
 * <p>This class is not instantiable.</p>
 */
public final class DimensionConstants {

    private DimensionConstants() {
        // utility class - no instances
    }

    // ------------------------------------------------------------------
    // Control characters (Dimension native protocol)
    // ------------------------------------------------------------------
    public static final byte STX = 0x02;   // Start of Transmission
    public static final byte ETX = 0x03;   // End of Transmission
    public static final byte ENQ = 0x05;   // Enquiry (retry request from receiver)
    public static final byte ACK = 0x06;   // Positive Acknowledge (data link layer)
    public static final byte NAK = 0x15;   // Negative Acknowledge (bad checksum)
    public static final byte FS  = 0x1C;   // Field Separator (between every field)

    // ------------------------------------------------------------------
    // Protocol timing (per PN D00396)
    // ------------------------------------------------------------------
    /** The instrument starts a 1-second ACK/NAK timer after each transmission. */
    public static final int  DEFAULT_ACK_TIMEOUT_MS        = 1000;
    /** Safety window for receiving one complete frame at 9600 baud. */
    public static final int  DEFAULT_FRAME_TIMEOUT_MS      = 5000;
    /**
     * "Upon receipt of a NAK, the transmitting device retransmits the message
     * a maximum of four times" (instrument error 318 = "Received fourth NAK").
     */
    public static final int  DEFAULT_MAX_RETRANSMISSIONS   = 4;

    // ------------------------------------------------------------------
    // Application level message types
    // ------------------------------------------------------------------
    public static final char MSG_POLL                 = 'P'; // instrument -> computer
    public static final char MSG_SAMPLE_REQUEST       = 'D'; // computer -> instrument
    public static final char MSG_NO_REQUEST           = 'N'; // computer -> instrument
    public static final char MSG_WAIT                 = 'W'; // computer -> instrument
    public static final char MSG_ACCEPTANCE           = 'M'; // both directions (request/result acceptance)
    public static final char MSG_QUERY                = 'I'; // instrument -> computer
    public static final char MSG_RESULT               = 'R'; // instrument -> computer
    public static final char MSG_CALIBRATION_RESULT   = 'C'; // instrument -> computer

    // Pre-verified response frames (checksums confirmed against the manual):
    //   <STX>M<FS>A<FS><FS>E2<ETX>  - Result/Request Acceptance (Accept)
    //   <STX>M<FS>R<FS>1<FS>24<ETX> - Result Acceptance (Reject, reason 1)
    //   <STX>N<FS>6A<ETX>           - No Request
    public static final String ACCEPTANCE_PAYLOAD_ACCEPT  = "M\u001CA\u001C\u001C";
    public static final String NO_REQUEST_PAYLOAD         = "N\u001C";

    // ------------------------------------------------------------------
    // Sample types (Result / Sample Request messages), index = code digit
    // ------------------------------------------------------------------
    public static final String[] SAMPLE_TYPES = {
        "", "Serum", "Plasma", "Urine", "CSF",
        "SerumQC1", "SerumQC2", "SerumQC3", "UrineQC1", "UrineQC2", "Whole Blood"
    };

    public static final String CHECKSUM_ADD_MOD_256 = "Add Mod 256"; // the only algorithm defined
    public static final String CHECKSUM_NONE        = "None";

    // ------------------------------------------------------------------
    // Plugin identity
    // ------------------------------------------------------------------
    public static final String PLUGIN_NAME    = "Siemens Dimension";
    public static final String PLUGIN_VERSION = "1.0.0";
}
