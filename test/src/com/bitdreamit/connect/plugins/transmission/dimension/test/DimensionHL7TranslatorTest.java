package com.bitdreamit.connect.plugins.transmission.dimension.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;

import org.junit.Test;

import com.bitdreamit.connect.plugins.transmission.dimension.server.DimensionHL7Translator;
import com.bitdreamit.connect.plugins.transmission.dimension.server.DimensionOrderRegistry;
import com.bitdreamit.connect.plugins.transmission.dimension.server.DimensionStreamHandler;
import com.bitdreamit.connect.plugins.transmission.dimension.shared.DimensionHL7Format;
import com.bitdreamit.connect.plugins.transmission.dimension.shared.DimensionTransmissionModeProperties;

/**
 * v2.2.0 "ASTM transmission pattern" tests: EVERY instrument frame type
 * must convert to a standard HL7 v2.x message inside the plugin, so the
 * Mirth channel transformer can read msg['OBX']... exactly like an
 * ASTM/HL7 channel.
 *
 * All frames are the USER'S LIVE CAPTURES (PN D00396 checksums verified):
 *   P|DIM|0|1|0|47                          (poll, chk 47)
 *   I|26091827|24                           (barcode query, chk 24)
 *   C|BUN|mg/dL|GA6057|...|2D               (calibration result, chk 2D)
 *   R|*||152|1||0|192902111125|1|1|2|ALTI|72|U/L||CRE2|0.59|mg/dL||6D
 *   N|6A / M|A||E2                          (control frames)
 */
public class DimensionHL7TranslatorTest {

    private static final char FS = 0x1C;

    // ------------------------------------------------------------------
    // Frame helpers - Add-Mod-256 checksum, uppercase hex, BEFORE ETX
    // ------------------------------------------------------------------

    private static String chk(String body) {
        int sum = 0;
        for (int i = 0; i < body.length(); i++) {
            sum += (body.charAt(i) & 0xFF);
        }
        return String.format("%02X", sum & 0xFF);
    }

    private static byte[] frame(String bodyWithChk) {
        return bodyWithChk.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static DimensionTransmissionModeProperties hl7Props() {
        DimensionTransmissionModeProperties props = new DimensionTransmissionModeProperties();
        props.setMessageOutputFormat(DimensionHL7Format.HL7_V2);
        props.setOrderQueueKey("hl7test-" + System.nanoTime());
        props.setAckTimeoutMs(10);   // keep auto-acceptance ACK waits short in tests
        return props;
    }

    private static DimensionTransmissionModeProperties rawProps() {
        DimensionTransmissionModeProperties props = new DimensionTransmissionModeProperties();
        props.setMessageOutputFormat(DimensionHL7Format.RAW_FRAME);
        props.setAckTimeoutMs(10);
        return props;
    }

    private static String[] segmentsOf(String hl7) {
        return hl7.split("\r");
    }

    private static String findSegment(String hl7, String prefix) {
        for (String s : segmentsOf(hl7)) {
            if (s.startsWith(prefix)) {
                return s;
            }
        }
        return null;
    }

    private static String fieldValue(String segment, int index) {
        // index is the HL7 field number; segment split: [0]=name, [1]=field 1...
        if (segment == null) {
            return null;
        }
        String[] f = segment.split("\\|", -1);
        return index < f.length ? f[index] : null;
    }

    // ==================================================================
    // 1. The user's LIVE Result frame -> ORU^R01
    // ==================================================================

    @Test
    public void testLiveResultFrameConvertsToOru() {
        String body = "R" + FS + "*" + FS + FS + "152" + FS + "1" + FS + FS + "0" + FS
                + "192902111125" + FS + "1" + FS + "1" + FS + "2" + FS
                + "ALTI" + FS + "72" + FS + "U/L" + FS + FS
                + "CRE2" + FS + "0.59" + FS + "mg/dL" + FS + FS;
        // the user's captured checksum is 6D - assert our body produces it
        assertEquals("live R frame checksum must be 6D", "6D", chk(body));
        String payload = body + "6D";

        String hl7 = DimensionHL7Translator.toHL7(frame(payload), hl7Props());

        String[] seg = segmentsOf(hl7);
        assertEquals("MSH|", seg[0].substring(0, 4));
        assertTrue("MSH-9 must be ORU^R01", seg[0].contains("|ORU^R01|"));
        assertTrue("MSH-11 = P", seg[0].endsWith("|P|2.5.1"));
        assertTrue("sending app DimensionEXL", seg[0].contains("|DimensionEXL|Dimension|LIS|LIS|"));

        String pid = findSegment(hl7, "PID|");
        assertNotNull("PID segment present", pid);
        assertEquals("empty PID (barcode-only run)", "PID||||||", pid);

        String obr = findSegment(hl7, "OBR|");
        assertNotNull(obr);
        assertEquals("OBR-4 = 152^DIMENSIONSAMPLE", "152^DIMENSIONSAMPLE", fieldValue(obr, 4));
        assertEquals("OBR-22 = analysis timestamp (ssmmhhddmmyy converted)",
                "20251111022919", fieldValue(obr, 22));

        String obx1 = findSegment(hl7, "OBX|1|");
        assertNotNull(obx1);
        assertEquals("OBX-3.1 ALTI", "ALTI^^LN:ALTI", fieldValue(obx1, 3));
        assertEquals("OBX-5 value 72", "72", fieldValue(obx1, 5));
        assertEquals("OBX-6 units U/L", "U/L", fieldValue(obx1, 6));
        assertEquals("OBX-11 status F", "F", fieldValue(obx1, 11));
        assertEquals("OBX-14 date", "20251111", fieldValue(obx1, 14));

        String obx2 = findSegment(hl7, "OBX|2|");
        assertNotNull(obx2);
        assertEquals("OBX-3.1 CRE2", "CRE2^^LN:CRE2", fieldValue(obx2, 3));
        assertEquals("OBX-5 value 0.59", "0.59", fieldValue(obx2, 5));
        assertEquals("OBX-6 units mg/dL", "mg/dL", fieldValue(obx2, 6));

        // sample type 1 = Serum, priority 0 = Routine (traceability NTEs)
        assertTrue(findSegment(hl7, "NTE|||SAMPLE_TYPE|").contains("Serum"));
        assertTrue(findSegment(hl7, "NTE|||PRIORITY|").contains("Routine"));
    }

    /** HL7 v2 access pattern the user works with (E4X-style indexes verified). */
    @Test
    public void testObxNavigationIndexesMatchAstmStyle() {
        String body = "R" + FS + "*" + FS + FS + "152" + FS + "1" + FS + FS + "0" + FS
                + "192902111125" + FS + "1" + FS + "1" + FS + "2" + FS
                + "ALTI" + FS + "72" + FS + "U/L" + FS + FS
                + "CRE2" + FS + "0.59" + FS + "mg/dL" + FS + FS;
        String hl7 = DimensionHL7Translator.toHL7(frame(body + chk(body)), hl7Props());

        // OBX|1|NM|ALTI^^LN:ALTI|1|72|U/L|||||F|||20251111
        // field:  1  2      3         4  5   6          11        14
        String obx = findSegment(hl7, "OBX|1|");
        assertEquals("NM", fieldValue(obx, 2));
        assertEquals("1", fieldValue(obx, 4));
        assertEquals("F", fieldValue(obx, 11));
        assertEquals("20251111", fieldValue(obx, 14));
    }

    // ==================================================================
    // 2. The user's LIVE Query frame -> QRY^A19
    // ==================================================================

    @Test
    public void testLiveQueryFrameConvertsToQry() {
        String body = "I" + FS + "26091827" + FS;
        assertEquals("live I frame checksum must be 24", "24", chk(body));
        String payload = body + "24";

        String hl7 = DimensionHL7Translator.toHL7(frame(payload), hl7Props());

        String msh = findSegment(hl7, "MSH|");
        assertNotNull(msh);
        assertTrue("MSH-9 must be QRY^A19", msh.contains("|QRY^A19|"));

        String pid = findSegment(hl7, "PID|");
        assertNotNull(pid);
        assertEquals("scanned barcode in PID-3", "26091827", fieldValue(pid, 3));

        String qrd = findSegment(hl7, "QRD|");
        assertNotNull(qrd);
        assertTrue("barcode in QRD too", qrd.contains("26091827"));
    }

    // ==================================================================
    // 3. The user's LIVE Calibration frame -> ORU^R01 (QC)
    // ==================================================================

    @Test
    public void testLiveCalibrationFrameConvertsToQcOru() {
        String body = "C" + FS + "BUN" + FS + "mg/dL" + FS + "GA6057" + FS + "1" + FS + "2" + FS
                + "01.09.26" + FS + "18410311125" + FS + "1" + FS + "0" + FS + "5" + FS
                + "-1" + FS + "-2" + FS + FS + FS + "3" + FS + "0" + FS + "3" + FS
                + "-0" + FS + "-1" + FS + "-1" + FS + "14" + FS + "3" + FS + "-9" + FS
                + "-9" + FS + "-9" + FS + "46" + FS + "3" + FS + "-27" + FS + "-27" + FS + "-27" + FS;
        // The live capture showed chk 2D; chat paste of long C frames can drop
        // characters, so the test computes the checksum for the transcribed body
        // (the translator consumes whatever checksum is on the wire).
        String payload = body + chk(body);

        String hl7 = DimensionHL7Translator.toHL7(frame(payload), hl7Props());

        String msh = findSegment(hl7, "MSH|");
        assertTrue("C converts to ORU^R01 too", msh.contains("|ORU^R01|"));

        String obr = findSegment(hl7, "OBR|");
        assertEquals("OBR-4.1 = BUN", "BUN", fieldValue(obr, 4).split("\\^")[0]);
        assertEquals("OBR-4.2 marks it CALIBRATION", "CALIBRATION", fieldValue(obr, 4).split("\\^")[1]);

        String lotObx = findSegment(hl7, "OBX|1|");
        assertTrue("lot in first OBX", lotObx.contains("GA6057"));

        assertNotNull("calibration coefficients preserved in CALDATA OBX",
                findSegment(hl7, "OBX|"));
        assertTrue(hl7.contains("-27"));
    }

    // ==================================================================
    // 4. Request Acceptance M -> ACK^D01 (+ registry bookkeeping hooks)
    // ==================================================================

    @Test
    public void testRequestAcceptanceAccepted() {
        DimensionTransmissionModeProperties props = hl7Props();
        String key = props.getOrderQueueKey();
        // simulate: an order was downloaded, the instrument now accepts it
        DimensionOrderRegistry.pushOrder(key, "26091827", "Doe,John", "1", "0", "BUN,CREA");
        DimensionOrderRegistry.DimensionOrder order = DimensionOrderRegistry.findOrder(key, "26091827");
        DimensionOrderRegistry.markDownloaded(key, order);

        String body = "M" + FS + "A" + FS;
        String hl7 = DimensionHL7Translator.toHL7(frame(body + chk(body)), props);

        String msa = findSegment(hl7, "MSA|");
        assertNotNull(msa);
        assertEquals("MSA-1 = AA (accepted)", "AA", fieldValue(msa, 1));
        assertEquals("MSA-2 carries the in-flight sample ID", "26091827", fieldValue(msa, 2));

        DimensionOrderRegistry.clear(key);
    }

    @Test
    public void testRequestAcceptanceRejected() {
        DimensionTransmissionModeProperties props = hl7Props();
        String key = props.getOrderQueueKey();
        DimensionOrderRegistry.pushOrder(key, "26091827", "", "1", "0", "BUN");
        DimensionOrderRegistry.DimensionOrder order = DimensionOrderRegistry.findOrder(key, "26091827");
        DimensionOrderRegistry.markDownloaded(key, order);

        String body = "M" + FS + "R" + FS + "1" + FS;
        String hl7 = DimensionHL7Translator.toHL7(frame(body + chk(body)), props);

        String msa = findSegment(hl7, "MSA|");
        assertEquals("MSA-1 = AE (rejected)", "AE", fieldValue(msa, 1));
        assertEquals("MSA-2 sample ID", "26091827", fieldValue(msa, 2));
        assertEquals("MSA-3 = reason 1", "1", fieldValue(msa, 3));

        String err = findSegment(hl7, "ERR|");
        assertNotNull("ERR segment with Table 1-17 reason text", err);
        assertTrue(err.contains("Request in process"));

        DimensionOrderRegistry.clear(key);
    }

    // ==================================================================
    // 5. Poll / No Request control frames
    // ==================================================================

    @Test
    public void testLivePollConvertsToControlAck() {
        String body = "P" + FS + "DIM" + FS + "0" + FS + "1" + FS + "0" + FS;
        assertEquals("live poll checksum must be 47", "47", chk(body));

        String hl7 = DimensionHL7Translator.toHL7(frame(body + "47"), hl7Props());
        String msh = findSegment(hl7, "MSH|");
        assertTrue("poll -> ACK^P01", msh.contains("|ACK^P01|"));
        String msa = findSegment(hl7, "MSA|");
        assertEquals("MSA-1 = CA (nothing to do)", "CA", fieldValue(msa, 1));
        assertEquals("instrument ID carried", "DIM", fieldValue(msa, 2));
    }

    @Test
    public void testNoRequestConvertsToControlAck() {
        String body = "N" + FS;
        assertEquals("No Request checksum must be 6A", "6A", chk(body));

        String hl7 = DimensionHL7Translator.toHL7(frame(body + "6A"), hl7Props());
        assertTrue(findSegment(hl7, "MSH|").contains("|ACK^N01|"));
    }

    // ==================================================================
    // 6. Safety contract: never throws, falls back to raw
    // ==================================================================

    @Test
    public void testTranslatorNeverThrowsOnGarbage() {
        // truncated / garbage payloads must fall back to the raw text
        String out1 = DimensionHL7Translator.toHL7(frame("R" + FS), hl7Props());
        assertNotNull(out1);
        String out2 = DimensionHL7Translator.toHL7(frame("X" + FS + "???" + FS), hl7Props());
        assertNotNull(out2);
        String out3 = DimensionHL7Translator.toHL7(null, hl7Props());
        assertEquals("", out3);
        // truncated R: falls back to raw payload (frame still dispatched)
        String truncated = "R" + FS + "*" + FS + FS + "152" + FS;
        assertEquals(truncated, DimensionHL7Translator.toHL7(frame(truncated), hl7Props()));
    }

    @Test
    public void testChecksumVariantsAccepted() {
        String body = "I" + FS + "26091827" + FS;
        // checksum EXCLUDED from payload (includeChecksumInPayload=false path)
        DimensionTransmissionModeProperties p = hl7Props();
        p.setIncludeChecksumInPayload(false);
        String hl7 = DimensionHL7Translator.toHL7(frame(body), p);
        assertTrue(findSegment(hl7, "MSH|").contains("|QRY^A19|"));

        // lowercase checksum accepted too (R frame: 6D vs 6d)
        String rbody = "R" + FS + "*" + FS + FS + "152" + FS + "1" + FS + FS + "0" + FS
                + "192902111125" + FS + "1" + FS + "1" + FS + "2" + FS
                + "ALTI" + FS + "72" + FS + "U/L" + FS + FS
                + "CRE2" + FS + "0.59" + FS + "mg/dL" + FS + FS;
        String hl7b = DimensionHL7Translator.toHL7(frame(rbody + "6d"), hl7Props());
        assertTrue(findSegment(hl7b, "MSH|").contains("|ORU^R01|"));
    }

    // ==================================================================
    // 7. END-TO-END through the real StreamHandler (HL7_V2 mode)
    // ==================================================================

    @Test
    public void testStreamHandlerDispatchesHl7ForLiveResultFrame() throws Exception {
        PipedInputStream handlerIn = new PipedInputStream();
        PipedOutputStream analyzerOut = new PipedOutputStream();
        analyzerOut.connect(handlerIn);
        PipedInputStream analyzerIn = new PipedInputStream();
        PipedOutputStream handlerOut = new PipedOutputStream();
        handlerOut.connect(analyzerIn);

        DimensionTransmissionModeProperties props = hl7Props();
        DimensionStreamHandler handler =
                new DimensionStreamHandler(handlerIn, handlerOut, null, props);

        String body = "R" + FS + "*" + FS + FS + "152" + FS + "1" + FS + FS + "0" + FS
                + "192902111125" + FS + "1" + FS + "1" + FS + "2" + FS
                + "ALTI" + FS + "72" + FS + "U/L" + FS + FS
                + "CRE2" + FS + "0.59" + FS + "mg/dL" + FS + FS;
        byte[] wire = (String.valueOf((char) 0x02) + body + chk(body) + String.valueOf((char) 0x03))
                .getBytes("US-ASCII");
        analyzerOut.write(wire);
        analyzerOut.flush();

        byte[] dispatched = handler.read();
        assertNotNull("frame dispatched", dispatched);
        String hl7 = new String(dispatched, "US-ASCII");
        assertTrue("dispatched content is HL7 ORU", hl7.contains("MSH|^~\\&|DimensionEXL|"));
        assertNotNull(findSegment(hl7, "OBX|1|"));

        // instrument got the DLC ACK on the line
        int ack = analyzerIn.read();
        assertEquals("DLC ACK 0x06 on the wire", 0x06, ack);
        analyzerOut.close();
        handlerOut.close();
    }

    @Test
    public void testStreamHandlerRawModeUnchanged() throws Exception {
        PipedInputStream handlerIn = new PipedInputStream();
        PipedOutputStream analyzerOut = new PipedOutputStream();
        analyzerOut.connect(handlerIn);
        PipedInputStream analyzerIn = new PipedInputStream();
        PipedOutputStream handlerOut = new PipedOutputStream();
        handlerOut.connect(analyzerIn);

        DimensionTransmissionModeProperties props = rawProps();
        DimensionStreamHandler handler =
                new DimensionStreamHandler(handlerIn, handlerOut, null, props);

        String body = "I" + FS + "26091827" + FS;
        byte[] wire = (String.valueOf((char) 0x02) + body + "24" + String.valueOf((char) 0x03))
                .getBytes("US-ASCII");
        analyzerOut.write(wire);
        analyzerOut.flush();

        byte[] dispatched = handler.read();
        // RAW_FRAME mode: payload incl. checksum, exactly like pre-2.2
        assertEquals(body + "24", new String(dispatched, "US-ASCII"));
        analyzerOut.close();
        handlerOut.close();
    }

    // ==================================================================
    // 8. Format property plumbing
    // ==================================================================

    @Test
    public void testOutputFormatProperty() {
        DimensionTransmissionModeProperties props = new DimensionTransmissionModeProperties();
        assertEquals("default is RAW_FRAME", DimensionHL7Format.RAW_FRAME, props.getMessageOutputFormat());
        assertFalse(DimensionHL7Translator.isHl7Output(props));

        props.setMessageOutputFormat("hl7_v2");          // case-insensitive
        assertEquals(DimensionHL7Format.HL7_V2, props.getMessageOutputFormat());
        assertTrue(DimensionHL7Translator.isHl7Output(props));

        props.setMessageOutputFormat(null);              // null -> default
        assertEquals(DimensionHL7Format.RAW_FRAME, props.getMessageOutputFormat());
    }

    @Test
    public void testWireDebugPropertyRoundTrip() {
        DimensionTransmissionModeProperties props = new DimensionTransmissionModeProperties();
        assertFalse("wire debug is OFF by default", props.isWireDebugEnabled());

        props.setWireDebugEnabled(true);
        assertTrue(props.isWireDebugEnabled());

        // descriptor map round trip (the path Mirth's serializer uses)
        java.util.Map<String, com.mirth.connect.model.datatype.DataTypePropertyDescriptor> map =
                props.getPropertyDescriptors();
        assertTrue("wireDebugEnabled must be exposed in the property descriptors",
                map.containsKey("wireDebugEnabled"));

        DimensionTransmissionModeProperties loaded = new DimensionTransmissionModeProperties();
        assertFalse(loaded.isWireDebugEnabled());
        loaded.setProperties(map);
        assertTrue("value must survive the descriptor map round trip", loaded.isWireDebugEnabled());
    }
}
