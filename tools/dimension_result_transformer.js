/**
 * Siemens Dimension -> HL7 v2 ORU^R01  (Mirth Connect source transformer)
 * ---------------------------------------------------------------------------
 * Reference transformer shipped with the bitdreamit-dimension-transmission
 * extension. Revision 6 - four repairs over the earlier revisions. FIX#4 is
 * Mirth-specific and CRITICAL:
 *
 * CHANNEL REQUIREMENTS (verified against Mirth 4.5.2 JavaScriptBuilder):
 *   Source Inbound Data Type = Raw   and   Source Response = None.
 *   With HL7 V2.x inbound, Mirth pre-serializes the payload into
 *   <HL7Message><R&#x1c;...></R&#x1c;...></HL7Message> (0x1C is illegal in an XML
 *   tag name) and the generated prelude
 *       msg = new XML(connectorMessage.getTransformedData());
 *   dies with 'TypeError: Element type "R" must be followed by either
 *   attribute specifications, ">" or "/>"' BEFORE this script runs. Raw inbound
 *   passes the payload as a plain string and this script reads
 *   connectorMessage.getRawData() directly.
 *
 *   FIX#1 (HIGH) Tolerant final test group. Real instruments (verified on a
 *                Dimension EXL capture) collapse the FINAL empty error field
 *                of the last test group, so the last group may carry only
 *                name + result + units. The old strict guard
 *                "base + 3 >= dataFields.length" rejected that group and
 *                silently dropped the LAST test (CREA / CRE) from every
 *                result frame.
 *   FIX#2 (MED)  OBX-11 = 'F' (Result Status) and OBX-14 = analysis
 *                datetime; 'F' was on OBX-9 and the date on OBX-13.
 *   FIX#3 (LOW)  Metadata columns SOURCE / TYPE are now filled
 *                (channelMap 'mirth_source' / 'mirth_type').
 *   FIX#4 (CRIT) Java String vs JS string. getRawData() arrives as a
 *                java.lang.String; un-coerced strict comparisons against
 *                split() tokens are object-vs-primitive and ALWAYS fail
 *                ('received CD, calculated CD'). Coerce once with String().
 *
 * Expects the RAW payload dispatched by the bitdreamit-dimension-transmission
 * mode: everything between <STX> and <ETX>, i.e.
 *
 *     TYPE <FS> data ... <FS> <CHK:2 hex>
 *
 * with FS = \u001C ("Checksum in Payload" must stay ON so the trailing
 * checksum arrives for re-verification). Frame types handled:
 *   R - Result              -> HL7 ORU^R01, one OBX per test
 *   C - Calibration Result  -> logged only (route to QC/calibration table)
 *   M - Request/Result Acceptance (from instrument, after our D download)
 *   P / I / N / D - control messages (normally consumed by the mode itself)
 *
 * Field layout of R (PN D00396 Table 1-22):
 *   R | Loadlist | PatientID | Sample# | SampleType | Location | Priority |
 *   DateTime(ssmmhhddmmyy) | #Cups | Dilution | #Tests |
 *   [ TestName | Result | Units | ErrCode ] x #Tests | CHK
 */

var FS = String.fromCharCode(0x1C);

// --- Error codes, Appendix III/IV of PN D00396 -----------------------------
var ERROR_CODES = {
    '1':'Temperature Out Of Range', '2':'Calibration Expired',
    '3':'Assay Out Of Range/Diluted', '4':'Absorbance',
    '5':'Measurement System (noise, cuvette, etc.)', '6':'Reagent QC',
    '7':'Arithmetic Error', '8':'Never Calibrated', '9':'No Reagent',
    '10':'Aborted Test', '11':'Processing Error', '12':'Software Error',
    '13':'Hemoglobin', '14':'Abnormal Reaction', '15':'Diluted',
    '16':'Below Assay Range', '17':'Above Assay Range', '18':'HIL Detected',
    '19':'Clot Detected'
};
// Error codes that suppress the result value (Appendix III)
var SUPPRESSING = ['6','7','8','9','10','11','12','16','17','19'];

// --- Sample types (Table 1-23) ---------------------------------------------
var SAMPLE_TYPES = {
    'W':'Whole Blood','1':'Serum','2':'Plasma','3':'Urine','4':'CSF',
    '5':'SerumQC1','6':'SerumQC2','7':'SerumQC3','8':'UrineQC1','9':'UrineQC2'
};

// --- Priorities (Table 1-24) ------------------------------------------------
var PRIORITIES = { '0':'Routine','1':'STAT','2':'ASAP','3':'QC','4':'XQC' };

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------
// FIX#4 (CRIT, rev 6): connectorMessage.getRawData() reaches the script as a
// java.lang.String (Rhino default javaPrimitiveWrap). Without String()
// coercion raw.split(FS) calls JAVA String.split and the tokens are Java
// objects, so the strict "chkCalc !== chkReceived" compares a JS primitive
// with a Java object and is ALWAYS unequal. Real Mirth run threw
//   Dimension checksum mismatch: received CD, calculated CD   (both 'CD'!)
// on perfectly good frames (reproduced in Rhino 1.7.14, Mirth's JS engine).
// String() unboxes to a native JS string so split/charCodeAt/=== behave.
var rawObj = connectorMessage.getRawData();
if (rawObj == null) {
    throw 'Empty or truncated Dimension frame';
}
var raw = String(rawObj);
if (raw.length < 3) {
    throw 'Empty or truncated Dimension frame';
}

var tokens = raw.split(FS);
if (tokens.length < 3) {
    throw 'Not a Dimension frame (no field separators): ' + raw;
}

// 1) Verify the Add-Mod-256 checksum (last token) over
//    "TYPE (FS data)* FS" - i.e. everything between STX and CHK.
// normalize: native string, trimmed, upper-case (the mode already accepts
// lower-case hex via equalsIgnoreCase since rev 5 - mirror that here)
var chkReceived = String(tokens[tokens.length - 1]).trim().toUpperCase();
var chkRegion = tokens.slice(0, tokens.length - 1).join(FS) + FS;
var sum = 0;
for (var i = 0; i < chkRegion.length; i++) {
    sum += (chkRegion.charCodeAt(i) & 0xFF);
}
var chkCalc = ('0' + (sum & 0xFF).toString(16).toUpperCase()).slice(-2);
if (chkCalc !== chkReceived) {
    throw 'Dimension checksum mismatch: received ' + chkReceived + ', calculated ' + chkCalc;
}

var msgType = tokens[0];
channelMap.put('dimensionChecksum', chkCalc);
channelMap.put('dimensionMessageType', msgType);
// FIX#3: feed the channel metadata columns (SOURCE / TYPE)
channelMap.put('mirth_source', 'DimensionEXL');
channelMap.put('mirth_type', msgType);

if (msgType === 'C') {
    // Calibration Result - store raw and stop (route to your QC system here)
    channelMap.put('calibrationRaw', raw);
    return;
}

if (msgType !== 'R') {
    // P/I/N/D/M control traffic - nothing to map to HL7
    channelMap.put('controlMessage', raw);
    return;
}

// ---------------------------------------------------------------------------
// Result (R) frame -> ORU^R01
// ---------------------------------------------------------------------------
// tokens:      [R, loadlist, pid, sampleNo, sType, loc, pri, dt, cups, dil,
//               nTests, (name, result, units, err)*, CHK]
// dataFields = everything between TYPE and CHK:
var dataFields = tokens.slice(1, tokens.length - 1);
if (dataFields.length < 11) {
    throw 'Truncated R frame: only ' + dataFields.length + ' data fields';
}

var loadlist   = dataFields[0];
var patientId  = dataFields[1];
var sampleNo   = dataFields[2];
var sTypeCode  = dataFields[3];
var location   = dataFields[4];
var priorityC  = dataFields[5];
var dt         = dataFields[6];            // ssmmhhddmmyy
var cups       = dataFields[7];
var dilution   = dataFields[8];
var nTests     = parseInt(dataFields[9], 10);

var sampleType = SAMPLE_TYPES[sTypeCode] || ('Unknown(' + sTypeCode + ')');
var isQC = (sTypeCode >= '5' && sTypeCode <= '9');

// ssmmhhddmmyy -> HL7 TS: YYYYMMDDHHMMSS (instrument year is 2-digit)
var hl7ts = '20' + dt.substring(10, 12) + dt.substring(8, 10) + dt.substring(6, 8)
          + dt.substring(4, 6) + dt.substring(2, 4) + dt.substring(0, 2);

// ---------------------------------------------------------------------------
// Build the ORU^R01
// ---------------------------------------------------------------------------
var sendingApp = 'DimensionEXL';          // or read the instrument ID from Poll frames
var msh = ['MSH|^~\\&', sendingApp, 'Dimension', 'LIS', 'LIS',
           hl7ts.substring(0, 8), '', 'ORU^R01', 'DIM' + sampleNo + Date.now(), 'P', '2.5.1'].join('|');

var pid = 'PID|||' + patientId + '|||';
// Barcode-only runs often have an empty Patient ID - keep it empty then.

// OBR: 17 empty fields -> hl7ts lands on OBR-22 (Results Rpt/Status Chng DT)
var obr = ['OBR', '1', '', '', sampleNo + '^DIMENSIONSAMPLE',
           '', '', '', '', '',                                  //  5 -  9
           '', '', '', '', '',                                  // 10 - 14
           '', '', '', '', '', '', '',                          // 15 - 21
           hl7ts].join('|');                                    // 22

var segments = [msh, pid, obr];
var obxIndex = 0;

for (var t = 0; t < nTests; t++) {
    var base = 10 + t * 4;

    // FIX#1: tolerant final group. The instrument collapses the FINAL empty
    // error field (verified on the captured frame), so the last group may
    // carry only name + result + units.
    var errCode = '';
    if (base + 3 < dataFields.length) {
        errCode = dataFields[base + 3];
    } else if (t === nTests - 1 && base + 2 < dataFields.length) {
        // final empty error field collapsed by the instrument - acceptable
    } else {
        logger.warn('R frame declares ' + nTests + ' tests but only ' +
                Math.floor((dataFields.length - 10) / 4) + ' complete test groups present');
        break;
    }

    var testName = dataFields[base];
    var result   = dataFields[base + 1];
    var units    = dataFields[base + 2];

    obxIndex++;
    var valueType = /^[0-9.\-eE]+$/.test(result) ? 'NM' : 'ST';

    // FIX#2: OBX-11 = 'F' (Result Status), OBX-14 = analysis datetime
    var obx = ['OBX', String(obxIndex), valueType,
               testName + '^^LN:' + testName,   // OBX-3: map to your LOINC dictionary
               String(t + 1),                    // OBX-4: sub-id
               result,                           // empty when suppressed by an error
               units, '', '', '', '',            // OBX-7..OBX-10
               'F',                              // OBX-11: Result Status
               '',                               // OBX-12
               '',                               // OBX-13
               hl7ts.substring(0, 8)].join('|'); // OBX-14: Date/Time of Analysis
    segments.push(obx);

    if (errCode !== '' && ERROR_CODES[errCode]) {
        var note = 'Error ' + errCode + ': ' + ERROR_CODES[errCode]
                 + (SUPPRESSING.indexOf(errCode) >= 0 ? ' (result suppressed)' : '');
        segments.push('NTE|||' + note);
    }
}

channelMap.put('sampleNumber', sampleNo);
channelMap.put('sampleType', sampleType);
channelMap.put('priority', PRIORITIES[priorityC] || 'Routine');
channelMap.put('isQC', isQC);
channelMap.put('dilution', dilution);
channelMap.put('testCount', obxIndex);

// Route QC samples differently if your destination needs it:
// if (isQC) { ... }

msg = segments.join('\r');   // HL7 segment terminator
