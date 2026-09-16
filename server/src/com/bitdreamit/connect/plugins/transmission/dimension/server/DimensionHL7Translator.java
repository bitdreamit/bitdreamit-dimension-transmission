package com.bitdreamit.connect.plugins.transmission.dimension.server;

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

import com.bitdreamit.connect.plugins.transmission.dimension.shared.DimensionConstants;
import com.bitdreamit.connect.plugins.transmission.dimension.shared.DimensionTransmissionModeProperties;

/**
 * Dimension frame -> HL7 v2.x translator (the "ASTM transmission" pattern).
 *
 * <p>Converts every instrument frame ONCE, inside the plugin (Java), into a
 * standard HL7 v2.x message, so the Mirth channel can use the NORMAL HL7
 * V2.x inbound data type and the transformer reads the data exactly the way
 * an ASTM/HL7 channel does - no frame splitting, no checksum math, no
 * protocol knowledge left in JavaScript:</p>
 *
 * <pre>
 *   var test  = msg['OBX']['OBX.3']['OBX.3.1'].toString();   // 'ALTI'
 *   var value = msg['OBX']['OBX.5']['OBX.5.1'].toString();   // '72'
 *   var unit  = msg['OBX']['OBX.6']['OBX.6.1'].toString();   // 'U/L'
 * </pre>
 *
 * <p>Frame type mapping (PN D00396 Rev. 2):</p>
 * <pre>
 *   R (Result)             -> ORU^R01  MSH + PID + OBR + OBX(one per test) [+ NTE on errors]
 *   C (Calibration Result) -> ORU^R01  MSH + OBR(test^CALIBRATION) + OBX(lot/units/...)
 *   I (Query, barcode)     -> QRY^A19  MSH + PID(barcode in PID-3) + QRD
 *   M (Request Acceptance) -> ACK^D01  MSH + MSA(AA accepted / AE rejected + reason)
 *   P (Poll)               -> ACK^P01  MSH + MSA(CA)          [control - filter in JS]
 *   N (No Request)         -> ACK^N01  MSH + MSA(CA)          [control - filter in JS]
 * </pre>
 *
 * <p>The R -&gt; ORU mapping is byte-compatible with the field layout proven in
 * production by the reference transformer (tools/dimension_result_transformer.js,
 * FIX#1/FIX#2 lineage) and with the user's live capture:</p>
 *
 * <pre>
 *   R *  152 1  0 192902111125 1 1 2 ALTI 72 U/L  CRE2 0.59 mg/dL  6D
 *   -&gt;  MSH|^~\&amp;|DimensionEXL|Dimension|LIS|LIS|20251111||ORU^R01|DIM152...|P|2.5.1
 *       PID||||||
 *       OBR|1|||152^DIMENSIONSAMPLE|...|20251111022919
 *       OBX|1|NM|ALTI^^LN:ALTI|1|72|U/L|||||F|||20251111
 *       OBX|2|NM|CRE2^^LN:CRE2|2|0.59|mg/dL|||||F|||20251111
 * </pre>
 *
 * <p><b>Safety contract:</b> {@link #toHL7(byte[], DimensionTransmissionModeProperties)}
 * never throws. Any parse problem falls back to the raw frame payload (the
 * pre-v2.2 behavior), so a malformed field can never drop a result or break
 * the read loop.</p>
 *
 * <p>This class is not instantiable.</p>
 */
public final class DimensionHL7Translator {

    private static final Logger logger = Logger.getLogger(DimensionHL7Translator.class);

    /** FS as a char (0x1C) - mirrors DimensionStreamHandler.FS_CH. */
    private static final char FS_CH = 0x1C;

    /** Segment separator of HL7 v2.x ER7 messages. */
    static final char CR = '\r';

    /** Output format values for {@link DimensionTransmissionModeProperties#getMessageOutputFormat()}. */
    public static final String FORMAT_RAW_FRAME = "RAW_FRAME";
    public static final String FORMAT_HL7_V2    = "HL7_V2";

    private DimensionHL7Translator() {
        // utility class - no instances
    }

    // ==================================================================
    // Entry point (called from DimensionStreamHandler.read())
    // ==================================================================

    /**
     * Converts one received frame payload into an HL7 v2.x ER7 message.
     * The payload layout is {@code TYPE (FS data)* (FS)? (CHK)?} exactly as
     * delivered by the read path (checksum included per
     * {@code includeChecksumInPayload}).
     *
     * <p>NEVER throws: on any parse problem the raw payload string is
     * returned so the frame is still dispatched (pre-v2.2 behavior).</p>
     */
    public static String toHL7(byte[] payload, DimensionTransmissionModeProperties props) {
        try {
            if (payload == null || payload.length == 0) {
                return rawFallback(payload);
            }
            String[] tokens = splitFields(payload, props);
            if (tokens.length < 1) {
                return rawFallback(payload);
            }

            char type = tokens[0].isEmpty() ? '?' : tokens[0].charAt(0);

            switch (type) {
                case DimensionConstants.MSG_RESULT:
                    return buildResultORU(tokens);
                case DimensionConstants.MSG_CALIBRATION_RESULT:
                    return buildCalibrationORU(tokens);
                case DimensionConstants.MSG_QUERY:
                    return buildQueryQRY(tokens);
                case DimensionConstants.MSG_ACCEPTANCE:
                    return buildAcceptanceACK(tokens, props);
                case DimensionConstants.MSG_POLL:
                    return buildPollACK(tokens);
                case DimensionConstants.MSG_NO_REQUEST:
                    return buildNoRequestACK();
                default:
                    // D/W or unknown type - keep raw (no HL7 meaning defined)
                    return rawFallback(payload);
            }
        } catch (Throwable t) {
            // Safety contract: a conversion problem must never lose a frame.
            logger.warn("Dimension HL7 conversion failed, dispatching raw frame: "
                    + t.getMessage());
            return rawFallback(payload);
        }
    }

    /** True when the properties select HL7_V2 output. */
    public static boolean isHl7Output(DimensionTransmissionModeProperties props) {
        return props != null && FORMAT_HL7_V2.equalsIgnoreCase(props.getMessageOutputFormat());
    }

    // ==================================================================
    // R - Result frame -> ORU^R01
    // ==================================================================

    /**
     * R frame layout (PN D00396 Table 1-22), tokens = TYPE + data + CHK:
     * R | Loadlist | PatientID | Sample# | SampleType | Location | Priority |
     * DateTime(ssmmhhddmmyy) | #Cups | Dilution | #Tests |
     * [ TestName | Result | Units | ErrCode ] x #Tests | CHK
     */
    private static String buildResultORU(String[] tokens) {
        // dataFields = everything between TYPE and CHK (checksum already stripped)
        List<String> f = new ArrayList<String>();
        for (int i = 1; i < tokens.length; i++) {
            f.add(tokens[i]);
        }
        if (f.size() < 10) {
            throw new IllegalArgumentException("Truncated R frame: " + f.size() + " fields");
        }

        String patientId = f.get(1);
        String sampleNo  = f.get(2);
        String sTypeCode = f.get(3);
        String priorityC = f.get(5);
        String dt        = f.get(6);              // ssmmhhddmmyy (11 chars, may be shorter)
        String nTestsStr = f.get(9);

        int nTests = parseIntSafe(nTestsStr);
        String hl7ts = toHl7Timestamp(dt);        // YYYYMMDDHHMMSS

        List<String> seg = new ArrayList<String>();
        seg.add(msh("ORU^R01", "DIM" + sampleNo + System.currentTimeMillis()));
        seg.add(pid(patientId));
        seg.add(obr(sampleNo + "^DIMENSIONSAMPLE", hl7ts));

        int obxIndex = 0;
        for (int t = 0; t < nTests; t++) {
            int base = 10 + t * 4;
            if (base >= f.size()) {
                logger.warn("R frame declares " + nTests + " tests but only "
                        + t + " complete test groups present - continuing with "
                        + t + " OBX");
                break;
            }
            String testName = f.get(base);
            String result   = base + 1 < f.size() ? f.get(base + 1) : "";
            String units    = base + 2 < f.size() ? f.get(base + 2) : "";
            String errCode  = base + 3 < f.size() ? f.get(base + 3) : "";

            obxIndex++;
            String valueType = isNumeric(result) ? "NM" : "ST";
            seg.add(obx(String.valueOf(obxIndex), valueType,
                    testName + "^^LN:" + testName, String.valueOf(t + 1),
                    result, units, hl7ts));

            if (!errCode.isEmpty()) {
                String text = errorText(errCode);
                if (text != null) {
                    seg.add("NTE|||" + escapeHl7("Error " + errCode + ": " + text));
                }
            }
        }

        // Extra raw fields for downstream routing (sample type / priority)
        String sampleType = sampleTypeText(sTypeCode);
        seg.add("NTE|||SAMPLE_TYPE|" + escapeHl7(sampleType));
        seg.add("NTE|||PRIORITY|" + escapeHl7(priorityText(priorityC)));

        return join(seg);
    }

    // ==================================================================
    // C - Calibration Result frame -> ORU^R01 (QC)
    // ==================================================================

    /**
     * C frame layout (PN D00396 Table 1-25):
     * C | Test | Units | Lot | Calibrator | ? | Operator | DateTime |
     * coefficients / cuvette values ... | CHK
     *
     * Emitted as an ORU^R01 whose OBR-4 is {@code <test>^CALIBRATION} so the
     * transformer can route QC/calibration data with one simple check:
     * {@code msg['OBR']['OBR.4']['OBR.4.2'].toString() == 'CALIBRATION'}.
     */
    private static String buildCalibrationORU(String[] tokens) {
        List<String> f = new ArrayList<String>();
        for (int i = 1; i < tokens.length; i++) {
            f.add(tokens[i]);
        }
        String test  = field(f, 0);
        String units = field(f, 1);
        String lot   = field(f, 2);
        String calib = field(f, 3);
        String oper  = field(f, 5);
        String dt    = field(f, 6);
        String hl7ts = toHl7Timestamp(dt);

        List<String> seg = new ArrayList<String>();
        seg.add(msh("ORU^R01", "DIMCAL" + test + System.currentTimeMillis()));
        seg.add("PID||||||");
        seg.add(obr(test + "^CALIBRATION", hl7ts));

        int n = 1;
        seg.add(obx(String.valueOf(n++), "ST", "LOT^^LN:LOT", "1", lot, "", hl7ts));
        if (!units.isEmpty()) {
            seg.add(obx(String.valueOf(n++), "ST", "UNITS^^LN:UNITS", "2", units, "", hl7ts));
        }
        if (!calib.isEmpty()) {
            seg.add(obx(String.valueOf(n++), "ST", "CALIBRATOR^^LN:CALIBRATOR", "3", calib, "", hl7ts));
        }
        if (!oper.isEmpty()) {
            seg.add(obx(String.valueOf(n++), "ST", "OPERATOR^^LN:OPERATOR", "4", oper, "", hl7ts));
        }
        // remaining numeric fields (slope / intercept / coefficients / cuvette)
        StringBuilder rest = new StringBuilder();
        for (int i = 7; i < f.size(); i++) {
            if (rest.length() > 0) {
                rest.append(' ');
            }
            rest.append(f.get(i));
        }
        if (rest.length() > 0) {
            seg.add(obx(String.valueOf(n), "ST", "CALDATA^^LN:CALDATA", "5",
                    escapeHl7(rest.toString()), "", hl7ts));
        }
        return join(seg);
    }

    // ==================================================================
    // I - Query frame -> QRY^A19
    // ==================================================================

    /**
     * I frame layout (Table 1-18 normal / Table 1-19 enhanced):
     * I | Sample ID [ | Segment | Position ] | CHK
     *
     * The scanned barcode is placed in BOTH PID-3.1 and QRD-8 so the
     * transformer can read it the obvious way:
     * {@code msg['PID']['PID.3']['PID.3.1'].toString()}.
     */
    private static String buildQueryQRY(String[] tokens) {
        String sampleId = field(tokens, 1);
        String segment  = field(tokens, 2);
        String position = field(tokens, 3);
        String now = nowTs();

        List<String> seg = new ArrayList<String>();
        seg.add(msh("QRY^A19", "DIMQ" + sampleId + System.currentTimeMillis()));
        seg.add(pid(sampleId));
        // QRD: 1=DTM 2=Format(R) 3=Priority(S) 4=QueryID 5=Deferred(I) 6=-
        //      7=- 8=Who subject filter (the barcode) 9=What subject filter
        //      (enhanced query segment^position)
        seg.add("QRD|" + now + "|R|S|DIM" + sampleId + "|I|||" + escapeHl7(sampleId)
                + "|" + escapeHl7(segment + (position.isEmpty() ? "" : "^" + position)));
        return join(seg);
    }

    // ==================================================================
    // M - Request Acceptance -> ACK^D01
    // ==================================================================

    /**
     * M frame layout (Table 1-16): M | Status(A|R) | Reason | CHK.
     * The manual's own accept example carries an extra empty field
     * (M&lt;FS&gt;&lt;FS&gt;A&lt;FS&gt;A&lt;FS&gt;1&lt;FS&gt;42), so the status is located by VALUE (A/R),
     * exactly like the reference transformer.
     *
     * Emitted as HL7 ACK: MSA|AA|&lt;sampleId&gt; (accepted) or
     * MSA|AE|&lt;sampleId&gt;|&lt;reason&gt; (rejected). The sample ID is peeked
     * from the registry's in-flight download (M frames carry no ID).
     */
    private static String buildAcceptanceACK(String[] tokens, DimensionTransmissionModeProperties props) {
        String status = "";
        String reason = "";
        for (int i = 1; i < Math.min(3, tokens.length); i++) {
            String v = tokens[i].trim().toUpperCase();
            if ("A".equals(v) || "R".equals(v)) {
                status = v;
                if ("R".equals(v) && i + 1 < tokens.length) {
                    reason = tokens[i + 1].trim();
                }
                break;
            }
        }

        String sampleId = "";
        if (props != null) {
            DimensionOrderRegistry.InFlight inFlight =
                    DimensionOrderRegistry.getInFlight(props.getOrderQueueKey());
            if (inFlight != null && inFlight.order != null) {
                sampleId = inFlight.order.getSampleId();
            }
        }

        List<String> seg = new ArrayList<String>();
        seg.add(msh("ACK^D01", "DIMM" + System.currentTimeMillis()));
        if ("R".equals(status)) {
            seg.add("MSA|AE|" + escapeHl7(sampleId) + "|" + escapeHl7(reason));
            String text = rejectReasonText(reason);
            if (text != null) {
                seg.add("ERR|||" + escapeHl7("Reason " + reason + ": " + text));
            }
        } else if ("A".equals(status)) {
            seg.add("MSA|AA|" + escapeHl7(sampleId));
        } else {
            seg.add("MSA|CA|");
        }
        return join(seg);
    }

    // ==================================================================
    // P / N - control frames -> small ACK messages
    // ==================================================================

    /**
     * P poll (Table 1-11): P | InstrumentID | FirstPoll | Request | #Carriers.
     * Emitted as ACK^P01 + MSA|CA|&lt;instrumentId&gt; - pure control traffic;
     * the transformer filters it out (see the reference transformer).
     */
    private static String buildPollACK(String[] tokens) {
        String instrumentId = field(tokens, 1);
        List<String> seg = new ArrayList<String>();
        seg.add(msh("ACK^P01", "DIMP" + System.currentTimeMillis()));
        seg.add("MSA|CA|" + escapeHl7(instrumentId));
        return join(seg);
    }

    /** N (No Request from the instrument) -> ACK^N01 + MSA|CA. */
    private static String buildNoRequestACK() {
        List<String> seg = new ArrayList<String>();
        seg.add(msh("ACK^N01", "DIMN" + System.currentTimeMillis()));
        seg.add("MSA|CA|");
        return join(seg);
    }

    // ==================================================================
    // Segment builders
    // ==================================================================

    /**
     * MSH|^~\&amp;|DimensionEXL|Dimension|LIS|LIS|&lt;date&gt;||&lt;type&gt;|&lt;controlId&gt;|P|2.5.1
     * (identical field-for-field to the user's live ORU capture).
     */
    private static String msh(String messageType, String controlId) {
        String now = nowTs();
        return "MSH|^~\\&|DimensionEXL|Dimension|LIS|LIS|" + now.substring(0, 8)
                + "||" + messageType + "|" + controlId + "|P|2.5.1";
    }

    /**
     * PID segment. patientId goes to PID-3.1 (patient identifier list),
     * the standard slot analyzers/LIS use for the barcode.
     */
    private static String pid(String patientId) {
        return "PID|||" + escapeHl7(nullSafe(patientId)) + "|||";
    }

    /**
     * OBR|1|||&lt;universalServiceId&gt;| ... 17 empty ... |&lt;ts&gt;  so the
     * analysis timestamp lands on OBR-22 (Results Rpt/Status Chng DT),
     * verified against the production transformer and the user's ORU.
     */
    private static String obr(String universalServiceId, String hl7ts) {
        StringBuilder sb = new StringBuilder("OBR|1|||").append(universalServiceId);
        for (int i = 5; i <= 21; i++) {
            sb.append('|');
        }
        sb.append('|').append(hl7ts);              // OBR-22
        return sb.toString();
    }

    /**
     * OBX|n|&lt;type&gt;|&lt;observationId&gt;|&lt;subId&gt;|&lt;value&gt;|&lt;units&gt;|........|F|||&lt;date&gt;
     * - OBX-11 = F (final result status), OBX-14 = analysis date. Byte layout
     * identical to the user's live ORU capture.
     */
    private static String obx(String setId, String valueType, String observationId,
                              String subId, String value, String units, String hl7ts) {
        // observationId carries INTENTIONAL HL7 components (test^^LN:test) -
        // never escaped; value/units are DATA and escaped defensively.
        return "OBX|" + setId + "|" + valueType + "|" + observationId + "|"
                + subId + "|" + escapeHl7(value) + "|" + escapeHl7(units)
                + "|||||F|||" + hl7ts.substring(0, 8);
    }

    private static String join(List<String> segments) {
        StringBuilder sb = new StringBuilder();
        for (String s : segments) {
            if (sb.length() > 0) {
                sb.append(CR);
            }
            sb.append(s);
        }
        return sb.toString();
    }

    // ==================================================================
    // Field / value helpers
    // ==================================================================

    /**
     * Splits the payload (TYPE + optional trailing checksum) on FS.
     * Tolerant hardening identical to DimensionStreamHandler.splitFields:
     * strips a glued-on trailing checksum and normalizes a missing
     * trailing FS before splitting; every field is trimmed.
     */
    private static String[] splitFields(byte[] payload, DimensionTransmissionModeProperties props) {
        String s = new String(payload, java.nio.charset.StandardCharsets.US_ASCII);

        boolean checksumIncluded = props == null || props.isIncludeChecksumInPayload();
        boolean checksumUsed     = props == null || props.isUseChecksum();
        if (checksumUsed && checksumIncluded && s.length() > 2) {
            // drop the 2 glued-on checksum characters at the end
            s = s.substring(0, s.length() - 2);
        }
        if (s.length() > 0 && s.charAt(s.length() - 1) == FS_CH) {
            s = s.substring(0, s.length() - 1);    // drop the trailing FS (split artifact)
        }
        String[] parts = s.split("\u001C");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    private static String rawFallback(byte[] payload) {
        return payload == null ? "" : new String(payload, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static String field(String[] tokens, int index) {
        return index >= 0 && index < tokens.length ? nullSafe(tokens[index]) : "";
    }

    private static String field(List<String> f, int index) {
        return index >= 0 && index < f.size() ? nullSafe(f.get(index)) : "";
    }

    /**
     * ssmmhhddmmyy -&gt; YYYYMMDDHHMMSS (instrument year is 2-digit, 2000+).
     * Digits-only extraction; short strings are left-padded (analyzers that
     * drop a leading zero), long strings keep the last 12 digits. Anything
     * undecodable falls back to "now" - a timestamp problem must never drop
     * a result. (The raw value stays available for QC frames in CALDATA.)
     */
    static String toHl7Timestamp(String dt) {
        String s = nullSafe(dt).trim();
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        s = digits.toString();
        if (s.length() < 10) {
            return nowTs();                        // undecodable - use "now"
        }
        if (s.length() > 12) {
            s = s.substring(s.length() - 12);
        }
        while (s.length() < 12) {
            s = "0" + s;
        }
        try {
            String ss = s.substring(0, 2);
            String mm = s.substring(2, 4);
            String hh = s.substring(4, 6);
            String dd = s.substring(6, 8);
            String mo = s.substring(8, 10);
            String yy = s.substring(10, 12);
            return "20" + yy + mo + dd + hh + mm + ss;
        } catch (Throwable t) {
            return nowTs();
        }
    }

    private static String nowTs() {
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyyMMddHHmmss");
        return fmt.format(new java.util.Date());
    }

    private static boolean isNumeric(String v) {
        if (v == null || v.isEmpty()) {
            return false;
        }
        return v.matches("[0-9.\\-eE+]+");
    }

    private static int parseIntSafe(String v) {
        try {
            return Integer.parseInt(nullSafe(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** HL7 value escape: | ^ ~ &amp; \ are the five reserved characters. */
    private static String escapeHl7(String v) {
        if (v == null || v.indexOf('|') < 0 && v.indexOf('^') < 0 && v.indexOf('~') < 0
                && v.indexOf('&') < 0 && v.indexOf('\\') < 0) {
            return v == null ? "" : v;
        }
        return v.replace("\\", "\\E\\").replace("|", "\\F\\")
                .replace("^", "\\S\\").replace("&", "\\T\\").replace("~", "\\R\\");
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /** Sample type text (Table 1-23) - subset, fallback is the raw code. */
    static String sampleTypeText(String code) {
        if (code == null) return "";
        switch (code.trim()) {
            case "W": return "Whole Blood";
            case "1": return "Serum";
            case "2": return "Plasma";
            case "3": return "Urine";
            case "4": return "CSF";
            case "5": return "SerumQC1";
            case "6": return "SerumQC2";
            case "7": return "SerumQC3";
            case "8": return "UrineQC1";
            case "9": return "UrineQC2";
            default:  return code.trim();
        }
    }

    /** Priority text (Table 1-24). */
    static String priorityText(String code) {
        if (code == null) return "";
        switch (code.trim()) {
            case "0": return "Routine";
            case "1": return "STAT";
            case "2": return "ASAP";
            case "3": return "QC";
            case "4": return "XQC";
            default:  return code.trim();
        }
    }

    /** Error code text (Appendix III/IV) or null when unknown. */
    static String errorText(String code) {
        if (code == null) return null;
        String[] codes = {
            "Temperature Out Of Range", "Calibration Expired",
            "Assay Out Of Range/Diluted", "Absorbance",
            "Measurement System (noise, cuvette, etc.)", "Reagent QC",
            "Arithmetic Error", "Never Calibrated", "No Reagent",
            "Aborted Test", "Processing Error", "Software Error",
            "Hemoglobin", "Abnormal Reaction", "Diluted",
            "Below Assay Range", "Above Assay Range", "HIL Detected",
            "Clot Detected"
        };
        try {
            int i = Integer.parseInt(code.trim());
            return (i >= 1 && i <= codes.length) ? codes[i - 1] : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Request Acceptance rejection reason text (Table 1-17) or null. */
    static String rejectReasonText(String code) {
        if (code == null) return null;
        String[] reasons = {
            "Request in process", "Result no longer available",
            "Sample carrier in use", "No memory to store request",
            "Error in test request", "Reserved", "Sample carrier full",
            "No known carriers", "Incorrect fluid type"
        };
        try {
            int i = Integer.parseInt(code.trim());
            return (i >= 1 && i <= reasons.length) ? reasons[i - 1] : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
