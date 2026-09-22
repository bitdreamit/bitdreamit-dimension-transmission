package com.bitdreamit.connect.plugins.transmission.dimension.server;

import org.apache.log4j.Logger;

import com.bitdreamit.connect.plugins.transmission.dimension.shared.DimensionTransmissionModeProperties;

/**
 * Wire-level debug logger for the Dimension transmission mode.
 *
 * <p>Answers exactly one question: <b>what did the analyzer ACTUALLY put on
 * the wire, and what did Mirth do with it?</b> - before/after the plugin's
 * automatic conversion work, so no internal step is a black box.</p>
 *
 * <p>Every line goes to the dedicated log4j logger {@code dimension.wire} at
 * INFO level, so it shows up in Mirth's normal server log (and the
 * Administrator's log view) WITHOUT changing any log configuration.</p>
 *
 * <p><b>On/off:</b></p>
 * <ul>
 *   <li>Per channel: the {@code wireDebugEnabled} property (settings dialog
 *       checkbox "Wire Debug (raw frames to log)", default OFF).</li>
 *   <li>Global override: JVM system property {@code -Ddimension.wireDebug=true}
 *       enables it for ALL channels without redeploying anything (same
 *       pattern as {@code dimension.pollDownload}).</li>
 * </ul>
 *
 * <p><b>Line tags:</b></p>
 * <pre>
 * [WIRE-IN ] raw frame bytes received FROM the analyzer (hex + ASCII + note)
 * [WIRE-OUT] raw frame bytes sent TO the analyzer (hex + ASCII + note)
 * [CONTROL ] single control byte both directions (ACK/NAK/ENQ/STX/ETX/FS)
 * [DISPATCH] the conversion pair: original raw payload -&gt; message handed to
 *            the channel (converted HL7 v2.x or stripped/raw payload)
 * [EVENT   ] registry lookups, download decisions, ACK timings, retries
 * </pre>
 */
final class DimensionWireDebug {

    /** Dedicated logger - filterable in log4j.conf without touching Mirth's root level. */
    private static final Logger WIRE_LOG = Logger.getLogger("dimension.wire");

    /** Global override system property (per-channel property OR's into this). */
    private static final String GLOBAL_FLAG = "dimension.wireDebug";

    private DimensionWireDebug() { }

    /**
     * Master switch: per-channel {@code wireDebugEnabled} property OR the
     * global {@code -Ddimension.wireDebug=true} system property.
     */
    static boolean enabled(DimensionTransmissionModeProperties props) {
        if (Boolean.parseBoolean(System.getProperty(GLOBAL_FLAG, "false").trim())) {
            return true;
        }
        return props != null && props.isWireDebugEnabled();
    }

    /** Complete raw frame received FROM the analyzer (STX..ETX). */
    static void in(byte[] rawFrame, String note) {
        frame("WIRE-IN ", rawFrame, note);
    }

    /** Complete raw frame sent TO the analyzer (STX..ETX). */
    static void out(byte[] rawFrame, String note) {
        frame("WIRE-OUT", rawFrame, note);
    }

    /** Single control byte on the wire in either direction (ACK/NAK/ENQ/...). */
    static void control(String direction, int b, String note) {
        WIRE_LOG.info("[" + direction + "] " + byteName(b) + " (0x" + twoHex(b) + ")"
                + (note == null || note.isEmpty() ? "" : " - " + note));
    }

    /**
     * Shows BOTH sides of the plugin's internal conversion: the original raw
     * payload exactly as received (TYPE FS data FS CHK) and the message that
     * was actually dispatched to the channel (converted HL7 v2.x, or the
     * raw/stripped payload in RAW_FRAME mode).
     */
    static void dispatch(byte[] originalPayload, Object dispatched, String note) {
        WIRE_LOG.info("[DISPATCH] original payload : " + printable(originalPayload));
        WIRE_LOG.info("[DISPATCH] to channel       : "
                + singleLine(String.valueOf(dispatched))
                + (note == null || note.isEmpty() ? "" : "   - " + note));
    }

    /** Registry lookups, download decisions, ACK timings, retries, timeouts. */
    static void event(String note) {
        WIRE_LOG.info("[EVENT   ] " + note);
    }

    // ------------------------------------------------------------------
    // Formatting helpers
    // ------------------------------------------------------------------

    private static void frame(String tag, byte[] rawFrame, String note) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(tag).append("] ");
        if (rawFrame == null) {
            sb.append("(null)");
        } else {
            for (byte b : rawFrame) {
                sb.append(twoHex(b)).append(' ');
            }
            sb.append("| ASCII: ").append(printable(rawFrame));
        }
        if (note != null && !note.isEmpty()) {
            sb.append("  - ").append(note);
        }
        WIRE_LOG.info(sb.toString());
    }

    /**
     * ASCII view of the bytes: printable characters as-is, protocol control
     * bytes as &lt;STX&gt; &lt;ETX&gt; &lt;FS&gt; &lt;ACK&gt; &lt;NAK&gt; &lt;ENQ&gt;
     * and everything else as &lt;0xNN&gt; - so a raw frame is readable at a glance.
     */
    private static String printable(byte[] data) {
        if (data == null) {
            return "(null)";
        }
        StringBuilder sb = new StringBuilder(data.length + 16);
        for (byte b : data) {
            int v = b & 0xFF;
            switch (v) {
                case 0x02: sb.append("<STX>"); break;
                case 0x03: sb.append("<ETX>"); break;
                case 0x05: sb.append("<ENQ>"); break;
                case 0x06: sb.append("<ACK>"); break;
                case 0x15: sb.append("<NAK>"); break;
                case 0x1C: sb.append("<FS>");  break;
                default:
                    if (v >= 0x20 && v <= 0x7E) {
                        sb.append((char) v);
                    } else {
                        sb.append("<0x").append(twoHex(v)).append('>');
                    }
            }
        }
        return sb.toString();
    }

    private static String singleLine(String s) {
        return s.replace("\r", "\\r").replace("\n", "\\n").replace("\u001C", "<FS>");
    }

    private static String twoHex(int b) {
        return String.format("%02X", b & 0xFF);
    }

    private static String byteName(int b) {
        switch (b & 0xFF) {
            case 0x02: return "<STX>";
            case 0x03: return "<ETX>";
            case 0x05: return "<ENQ>";
            case 0x06: return "<ACK>";
            case 0x15: return "<NAK>";
            case 0x1C: return "<FS>";
            default:   return "0x" + twoHex(b);
        }
    }
}
