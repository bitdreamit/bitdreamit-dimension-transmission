/**
 * Siemens Dimension -> HL7 v2 ORU^R01  (Mirth Connect source transformer)
 * ---------------------------------------------------------------------------
 * REDESIGN rev 9 (dynamic, rev 10 extension) - BUILT TO NEVER THROW.
 *
 * The transformer is now PURELY a business formatter: Result (R) frames in,
 * HL7 ORU^R01 out. EVERY protocol duty moved into the Java extension:
 *
 *   - Framing, checksum validation, ACK/NAK/ENQ handshaking
 *       -> DimensionStreamHandler (Java, data-link layer)
 *   - Result Acceptance <STX|M|A|E2> after R/C frames
 *       -> DimensionStreamHandler.autoRespond (Java, read path)
 *   - Barcode Query [I] / Poll [P] answering with Sample Request [D]
 *       -> DimensionStreamHandler.answerPollOrQuery (Java, read path),
 *          orders come from DimensionOrderRegistry:
 *          Packages.com.bitdreamit.connect.plugins.transmission.dimension.server
 *              .DimensionOrderRegistry.pushOrder('default', '043092011',
 *                  'DOE,JOHN', '1', '0', 'GLU,CREA,F5');
 *   - No Request <STX|N|6A> when nothing is queued
 *       -> DimensionStreamHandler (Java)
 *
 * Because the Java handler answers INSIDE the read path (milliseconds after
 * frame receipt), the instrument's 1-second timers are always met and no
 * Mirth processing delay or transformer error can ever break a download.
 * This transformer therefore contains NO responseMap.put, NO order tables,
 * NO frame building - none of the code that caused the row/map/strict-
 * comparison failures of earlier revisions. Unknown sample IDs, unknown
 * test codes, missing fields: logged, never thrown.
 *
 * IMPORT WITH: Dimention-dynamic.xml (RAW in/out, Source Response = None,
 * autoPollResponse=true, orderLookupEnabled=true).
 *
 * Expects the RAW payload dispatched by the transmission mode: everything
 * between <STX> and <ETX>, i.e.
 *
 *     TYPE <FS> data ... <FS> <CHK:2 hex>
 *
 * with FS = \u001C ("Checksum in Payload" stays ON so the trailing checksum
 * arrives; it is re-computed here for LOGGING only - the mode already NAKed
 * bad frames, so a mismatch here is informational, never an error).
 *
 * Field layout of R (PN D00396 Table 1-22):
 *   R | Loadlist | PatientID | Sample# | SampleType | Location | Priority |
 *   DateTime(ssmmhhddmmyy) | #Cups | Dilution | #Tests |
 *   [ TestName | Result | Units | ErrCode ] x #Tests | CHK
 *
 * Kept repairs from rev 8:
 *   FIX#1 tolerant final test group (instruments collapse the final empty
 *        error field - the old strict guard dropped the LAST test).
 *   FIX#2 OBX-11='F', OBX-14=analysis datetime.
 *   FIX#3 metadata columns SOURCE/TYPE filled.
 *   FIX#4 String() unboxing of getRawData() (Java String vs JS string).
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

// --- Request Acceptance rejection reasons (Table 1-17) ---------------------
var REQUEST_REJECT_REASONS = {
    '1':'Request in process','2':'Result no longer available',
    '3':'Sample carrier in use','4':'No memory to store request',
    '5':'Error in test request','6':'Reserved','7':'Sample carrier full',
    '8':'No known carriers','9':'Incorrect fluid type'
};

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------
var rawObj = connectorMessage.getRawData();
if (rawObj == null) {
    logger.error('Dimension transformer: empty payload - skipping (no throw)');
    return;
}
var raw = String(rawObj); // FIX#4: unbox java.lang.String -> JS string
if (raw.length < 3) {
    logger.error('Dimension transformer: payload too short (' + raw.length + ') - skipping');
    return;
}

var tokens = raw.split(FS);
if (tokens.length < 2) {
    // Not frame-shaped: keep it visible but harmless.
    logger.error('Dimension transformer: not a Dimension frame (no FS): ' + raw);
    channelMap.put('parseError', raw);
    return;
}

// Checksum: INFORMATIONAL ONLY. The Java mode validates + NAKs bad frames
// before they ever reach this script; a mismatch here would mean the frame
// was re-cut in transit, so log a warning and carry on.
var chkReceived = String(tokens[tokens.length - 1]).trim().toUpperCase();
var chkRegion = tokens.slice(0, tokens.length - 1).join(FS) + FS;
var sum = 0;
for (var i = 0; i < chkRegion.length; i++) {
    sum += (chkRegion.charCodeAt(i) & 0xFF);
}
var chkCalc = ('0' + (sum & 0xFF).toString(16).toUpperCase()).slice(-2);
if (chkCalc !== chkReceived) {
    logger.warn('Dimension checksum informational mismatch (mode still ACKed): received '
            + chkReceived + ', calculated ' + chkCalc);
}

var msgType = String(tokens[0]).trim();
channelMap.put('dimensionChecksum', chkCalc);
channelMap.put('dimensionMessageType', msgType);
// FIX#3b: feed the channel metadata columns (SOURCE / TYPE)
channelMap.put('mirth_source', 'DimensionEXL');
channelMap.put('mirth_type', msgType);

// ---------------------------------------------------------------------------
// Control frames: metadata only. ALL wire answers were already sent by the
// Java handler inside the read path - the transformer adds no responses.
// ---------------------------------------------------------------------------
if (msgType === 'P') {
    // Table 1-11: P | Instrument ID | First Poll | Request | # Of Carriers
    channelMap.put('pollInstrumentId', String(tokens[1] || ''));
    channelMap.put('pollFirstPoll',    String(tokens[2] || ''));
    channelMap.put('pollRequest',      String(tokens[3] || ''));
    channelMap.put('controlMessage', raw);
    return;
}

if (msgType === 'I') {
    // Table 1-18: I | Sample ID | [Segment | Position] (barcode scan)
    channelMap.put('queriedSampleId', String(tokens[1] || ''));
    if (tokens.length >= 5) {
        channelMap.put('querySegment', String(tokens[2] || ''));
        channelMap.put('queryPosition', String(tokens[3] || ''));
    }
    channelMap.put('controlMessage', raw);
    return;
}

if (msgType === 'M') {
    // Request Acceptance (Table 1-16) after our D download. Status located
    // by VALUE (A/R) because the manual's own accept example carries an
    // extra empty field (M<FS><FS>A<FS>A<FS>1<FS>42).
    var mFields = tokens.slice(1, tokens.length - 1);
    var mStatus = '';
    var mReason = '';
    for (var mi = 0; mi < Math.min(2, mFields.length); mi++) {
        var mv = String(mFields[mi]).trim().toUpperCase();
        if (mv === 'A' || mv === 'R') {
            mStatus = mv;
            if (mv === 'R') { mReason = String(mFields[mi + 1] || '').trim(); }
            break;
        }
    }
    channelMap.put('acceptanceStatus', mStatus);
    channelMap.put('acceptanceReason', mReason);
    channelMap.put('acceptanceRaw', raw);
    if (mStatus === 'R') {
        logger.warn('Instrument REJECTED the sample request: reason ' + mReason
                + ' (' + (REQUEST_REJECT_REASONS[mReason] || 'unknown') + ')'
                + ' - re-push the order into DimensionOrderRegistry');
    }
    channelMap.put('controlMessage', raw);
    return;
}

if (msgType === 'C') {
    // Calibration Result (Table 1-25) - accepted by the mode (auto M|A).
    // Header decoded for QC routing; full payload stays in calibrationRaw.
    channelMap.put('calibrationRaw', raw);
    channelMap.put('calibrationTest', String(tokens[1] || ''));
    channelMap.put('calibrationUnits', String(tokens[2] || ''));
    channelMap.put('calibrationLot', String(tokens[3] || ''));
    channelMap.put('calibrationCalibrator', String(tokens[4] || ''));
    channelMap.put('calibrationOperator', String(tokens[6] || ''));
    channelMap.put('calibrationDateTime', String(tokens[7] || ''));
    channelMap.put('controlMessage', raw);
    return;
}

if (msgType !== 'R') {
    // N / D / W and anything else - the mode handled the wire; log and go.
    channelMap.put('controlMessage', raw);
    return;
}

// ---------------------------------------------------------------------------
// Result (R) frame -> ORU^R01
// ---------------------------------------------------------------------------
try {
    var dataFields = tokens.slice(1, tokens.length - 1);
    if (dataFields.length < 10) {
        logger.error('Dimension R frame truncated (' + dataFields.length
                + ' data fields, need >= 10): ' + raw);
        channelMap.put('parseError', raw);
        return;
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
    var nTestsRaw  = parseInt(String(dataFields[9]).replace(/[^0-9]/g, ''), 10);
    var nTests     = isNaN(nTestsRaw) ? 0 : nTestsRaw;

    var sampleType = SAMPLE_TYPES[sTypeCode] || ('Unknown(' + sTypeCode + ')');
    var isQC = (sTypeCode >= '5' && sTypeCode <= '9');

    // ssmmhhddmmyy -> HL7 TS: YYYYMMDDHHMMSS (instrument year is 2-digit)
    var hl7ts = '';
    if (dt && dt.length >= 12) {
        hl7ts = '20' + dt.substring(10, 12) + dt.substring(8, 10) + dt.substring(6, 8)
              + dt.substring(4, 6) + dt.substring(2, 4) + dt.substring(0, 2);
    } else {
        logger.warn('Dimension R frame: short datetime "' + dt + '" - OBX-14 left empty');
    }

    var sendingApp = 'DimensionEXL';
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

        // FIX#1: tolerant final group. The instrument collapses the FINAL
        // empty error field (verified on a real EXL capture), so the last
        // group may carry only name + result + units.
        var errCode = '';
        if (base + 3 < dataFields.length) {
            errCode = dataFields[base + 3];
        } else if (t === nTests - 1 && base + 2 < dataFields.length) {
            // final empty error field collapsed by the instrument - acceptable
        } else {
            logger.warn('R frame declares ' + nTests + ' tests but only '
                    + Math.max(0, Math.floor((dataFields.length - 10) / 4))
                    + ' complete test groups present - mapping what is there');
            break;
        }

        var testName = String(dataFields[base] || '');
        if (testName === '') { continue; } // skip empty group silently

        var result   = dataFields[base + 1];
        var units    = dataFields[base + 2];

        obxIndex++;
        var valueType = /^[0-9.\-eE]+$/.test(result) ? 'NM' : 'ST';

        // FIX#2: OBX-11 = 'F' (Result Status), OBX-14 = analysis datetime.
        // Unknown test codes pass through untouched - map to your LOINC
        // dictionary at the destination or extend OBX-3 here.
        var obx = ['OBX', String(obxIndex), valueType,
                   testName + '^^LN:' + testName,   // OBX-3
                   String(t + 1),                    // OBX-4: sub-id
                   result,                           // OBX-5
                   units, '', '', '', '',            // OBX-7..OBX-10
                   'F',                              // OBX-11: Result Status
                   '',                               // OBX-12
                   '',                               // OBX-13
                   hl7ts.substring(0, 8)].join('|'); // OBX-14
        segments.push(obx);

        if (errCode !== '' && ERROR_CODES[errCode]) {
            var note = 'Error ' + errCode + ': ' + ERROR_CODES[errCode]
                     + (SUPPRESSING.indexOf(errCode) >= 0 ? ' (result suppressed)' : '');
            segments.push('NTE|||' + note);
        }
    }

    channelMap.put('loadlist', loadlist);
    channelMap.put('sampleNumber', sampleNo);
    channelMap.put('sampleType', sampleType);
    channelMap.put('location', location);
    channelMap.put('priority', PRIORITIES[priorityC] || 'Routine');
    channelMap.put('isQC', isQC);
    channelMap.put('dilution', dilution);
    channelMap.put('testCount', obxIndex);

    msg = segments.join('\r');   // HL7 segment terminator
} catch (e) {
    // Last-resort guard: a malformed R frame must NEVER error the channel.
    logger.error('Dimension R frame parse failure (no throw): ' + e + ' - raw: ' + raw);
    channelMap.put('parseError', raw);
}
