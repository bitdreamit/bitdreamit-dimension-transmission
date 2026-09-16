package com.bitdreamit.connect.plugins.transmission.dimension.shared;

/**
 * Message output format options for the Dimension transmission mode
 * (v2.2.0 - the "ASTM transmission" pattern).
 *
 * <p>Lives in the SHARED module so the client settings dialog and the
 * server-side translator ({@code DimensionHL7Translator}, server module)
 * can both reference the constants without a server dependency.</p>
 */
public final class DimensionHL7Format {

    private DimensionHL7Format() {
        // utility class - no instances
    }

    /**
     * Default (pre-2.2 behavior): the channel receives the raw Dimension
     * frame payload {@code TYPE FS data FS CHK}. The transformer does all
     * parsing; the source Inbound Data Type must be <b>Raw</b>.
     */
    public static final String RAW_FRAME = "RAW_FRAME";

    /**
     * The plugin converts every frame to a standard HL7 v2.x message before
     * dispatch (R-&gt;ORU^R01, I-&gt;QRY^A19, C-&gt;ORU^R01 QC, M-&gt;ACK^D01,
     * P/N-&gt;ACK), so the channel uses the normal <b>HL7 V2.x</b> inbound
     * data type and the transformer reads
     * {@code msg['OBX']['OBX.3']['OBX.3.1']} exactly like an ASTM/HL7 channel.
     */
    public static final String HL7_V2 = "HL7_V2";
}
