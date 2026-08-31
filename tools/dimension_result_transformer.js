/**
 * Siemens Dimension -> HL7 v2 ORU^R01  (Mirth Connect source transformer)
 * ---------------------------------------------------------------------------
 * Reference transformer shipped with the bitdreamit-dimension-transmission
 * extension. Revision 8 - four repairs (FIX#4 is Mirth-specific and
 * CRITICAL) plus full PN D00396 application-layer coverage:
 *   Poll/Query answering (P -> D/N, I -> D/N), Request Acceptance (M)
 *   decoding, Enhanced Query logging, R Location field surfaced.
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
 *   C - Calibration Result  -> header decoded to channelMap (QC routing)
 *   M - Request Acceptance (instrument -> computer, after our D) -> decoded,
 *       rejection reason (Table 1-17) logged
 *   P - Poll  -> D (next queued order, conversational poll only) or N
 *   I - Query -> D (Sample Request for the queried ID) or N
 *   N / D - control messages (no computer response defined in the manual)
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

// --- Request Acceptance rejection reasons (Table 1-17) ---------------------
var REQUEST_REJECT_REASONS = {
    '1':'Request in process','2':'Result no longer available',
    '3':'Sample carrier in use','4':'No memory to store request',
    '5':'Error in test request','6':'Reserved','7':'Sample carrier full',
    '8':'No known carriers','9':'Incorrect fluid type'
};

// Coerce a tests field (JS array OR java.util.List) into a real JS array so
// length/indexing work the same for both (Rhino interop, see FIX#4).
function toJsArray(v) {
    if (v && v.size) { var a = []; for (var i = 0; i < v.size(); i++) { a.push(String(v.get(i))); } return a; }
    if (v && v.length !== undefined) { return v; }
    return null;
}

// ---------------------------------------------------------------------------
// Order lookup for Query [I] answering (REV 7)
// ---------------------------------------------------------------------------
// DEMO table - REPLACE with your LIS/order lookup. Anything declared here is
// answered with a Sample Request (D); unknown sample IDs get No Request (N).
// You can also inject orders at runtime:
//   globalMap.put('dimensionOrders', { '12345': {patient:'X', tests:['GLU']} });
var QUERY_ORDERS = {
    '012345':    { patient: 'Doe,John', type: '2', priority: '0', tests: ['BUN', 'CREA', 'F5'] },
    '043092011': { patient: '',         type: '1', priority: '0', tests: ['BUN', 'CREA', 'F5'] }
};

// PN D00396 Table 1-12 (p. 1-9): the channel supplies the payload WITHOUT
// STX/ETX/checksum - DimensionStreamHandler.write() frames it and waits for
// the instrument ACK (retransmitting on NAK).
function buildSampleRequest(order, sampleId) {
    var tests = toJsArray(order.tests) || [];
    var names = [];
    for (var i = 0; i < tests.length && names.length < 36; i++) {
        var t = String(tests[i]).toUpperCase().substring(0, 5);
        names.push(t);
    }
    return ['D',
            '0',                                       // Sample Carrier ID (always 0)
            '0',                                       // Loadlist ID (always 0)
            'A',                                       // Transaction: A=Add, D=Delete
            String(order.patient || '').substring(0, 27),
            String(sampleId).substring(0, 12),
            String(order.type || '1'),                 // Sample Type (Table 1-13)
            '',                                        // Location (optional)
            String(order.priority || '0'),             // Priority 0-4
            '1',                                       // # Of Cups For Sample
            '**',                                      // Cup Position (any cup)
            '1',                                       // Dilution
            String(names.length)                       // # Of Tests
           ].concat(names).join(FS);
}

// ---------------------------------------------------------------------------
// Poll-driven order download (Send/Receive mode) - REV 8
// ---------------------------------------------------------------------------
// PN D00396 p.1-8/1-9: "A Sample Request Message is sent to an instrument in
// response to a Poll [P] or Query [I] message." The download dialogue
// (p.1-24) runs on a CONVERSATIONAL poll: "The value of this field must be
// (0) to download a request from the computer" (First Poll field, p.1-8)
// and Request = 1 (instrument ready, p.1-8). Every other poll is answered
// with No Request (N), the documented default response (p.1-12).
//
// Order sources, tried in order:
//   1. globalMap 'dimensionOrderQueue' - push orders from any script:
//        var q = globalMap.get('dimensionOrderQueue') || [];
//        q.push({ sampleId: '043092011', patient: 'Doe,John', type: '1',
//                 priority: '1', tests: ['GLU','CREA'] });
//        globalMap.put('dimensionOrderQueue', q);
//      (a java.util.List pushed by a database-reader channel works too;
//       each conversational poll consumes the FIRST entry)
//   2. QUERY_ORDERS below, each sent at most once per Mirth runtime
//      (sent-markers in globalMap 'dimensionSentPollOrders' prevent the
//      same order being re-downloaded on the 1-second re-poll cycle,
//      p.1-26 Timing).
function pollRequestFields(tokens) {
    // Table 1-11: P | Instrument ID | First Poll | Request | # Of Carriers
    return {
        instrumentId: String(tokens[1] || '').trim(),
        firstPoll:    String(tokens[2] || '0').trim(),
        request:      String(tokens[3] || '0').trim()
    };
}

function mapGet(obj, key) {
    // java.util.Map instances need .get(); JS objects use property access
    if (obj && obj.get && obj.containsKey) { return obj.get(key); }
    return obj ? obj[key] : undefined;
}

function normalizeOrder(order, sampleId) {
    if (!order) { return null; }
    var id = String(sampleId !== undefined && sampleId !== null
                    ? sampleId : (mapGet(order, 'sampleId') || mapGet(order, 'sample') || '')).trim();
    var tests = toJsArray(mapGet(order, 'tests'));
    if (!id || !tests || !tests.length) { return null; }
    return { sampleId: id,
             patient:  String(mapGet(order, 'patient') || ''),
             type:     String(mapGet(order, 'type') || '1'),
             priority: String(mapGet(order, 'priority') || '0'),
             tests:    tests };
}

function takeNextPollOrder() {
    var queue = globalMap.get('dimensionOrderQueue');
    if (queue) {
        var items = [];
        if (queue.size) {                            // java.util.List
            for (var i = 0; i < queue.size(); i++) { items.push(queue.get(i)); }
        } else if (queue.length !== undefined) {     // JS array
            for (var j = 0; j < queue.length; j++) { items.push(queue[j]); }
        }
        if (items.length) {
            var next = normalizeOrder(items.shift());
            globalMap.put('dimensionOrderQueue', items);
            if (next) { return next; }
        }
    }
    var sentRaw = globalMap.get('dimensionSentPollOrders');
    var sent = {};
    if (sentRaw) {
        var parts = String(sentRaw).split(',');
        for (var p = 0; p < parts.length; p++) { if (parts[p]) { sent[parts[p]] = true; } }
    }
    for (var id in QUERY_ORDERS) {
        if (!sent[id]) {
            sent[id] = true;
            var keys = [];
            for (var k in sent) { keys.push(k); }
            globalMap.put('dimensionSentPollOrders', keys.join(','));
            return normalizeOrder(QUERY_ORDERS[id], id);
        }
    }
    return null;
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------
// FIX#4 (CRIT): connectorMessage.getRawData() reaches the script as a
// java.lang.String (Rhino default javaPrimitiveWrap). Feeding it straight
// into split() calls JAVA String.split and yields Java String tokens, so the
// strict comparison "chkCalc !== chkReceived" compares a JS primitive with a
// Java object and is ALWAYS unequal - real Mirth run threw:
//   Dimension checksum mismatch: received CD, calculated CD   (both 'CD'!)
// String() unboxes it to a native JS string, making every downstream
// operation (split/charCodeAt/=== comparisons) behave as the code intends.
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
// normalize: native string, trimmed, upper-case (mode already accepts
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
// FIX#3b: feed the channel metadata columns (SOURCE / TYPE)
channelMap.put('mirth_source', 'DimensionEXL');
channelMap.put('mirth_type', msgType);

if (msgType === 'C') {
    // Calibration Result (Table 1-25) - accepted by the mode (auto M|A).
    // Decode the header for QC routing; the full payload (slope, intercept,
    // coefficients, bottle values) stays in calibrationRaw.
    channelMap.put('calibrationRaw', raw);
    channelMap.put('calibrationTest', String(tokens[1] || ''));
    channelMap.put('calibrationUnits', String(tokens[2] || ''));
    channelMap.put('calibrationLot', String(tokens[3] || ''));
    channelMap.put('calibrationCalibrator', String(tokens[4] || ''));
    channelMap.put('calibrationOperator', String(tokens[6] || ''));
    channelMap.put('calibrationDateTime', String(tokens[7] || ''));
    return;
}

if (msgType === 'P') {
    // Poll (Table 1-11) -> Sample Request (D) or No Request (N).
    // Download only on a conversational poll (First Poll = 0) with
    // Request = 1; everything else gets the default No Request answer.
    var pf = pollRequestFields(tokens);
    channelMap.put('pollInstrumentId', pf.instrumentId);
    channelMap.put('pollFirstPoll', pf.firstPoll);
    channelMap.put('pollRequest', pf.request);
    var pollOrder = null;
    if (pf.firstPoll === '0' && pf.request === '1') {
        pollOrder = takeNextPollOrder();
    }
    if (pollOrder) {
        channelMap.put('pollDownloadedSample', pollOrder.sampleId);
        responseMap.put('dimensionResponse', buildSampleRequest(pollOrder, pollOrder.sampleId));
    } else {
        responseMap.put('dimensionResponse', 'N' + FS);
    }
    channelMap.put('controlMessage', raw);
    return;
}

if (msgType === 'I') {
    // Query (Send ID/Receive) -> Sample Request (D) or No Request (N).
    // Manual p.1-14: "From the computer the Sample ID and Sample ID Field
    // must match or the message will be rejected" - the D frame below
    // echoes the queried ID as its Sample # field.
    var queryId = String(tokens[1] || '').trim();
    channelMap.put('queriedSampleId', queryId);
    if (tokens.length >= 5) {
        // Enhanced Query (Table 1-19): I | Sample ID | Segment | Position.
        // Disabled by default on the instrument (p.1-15); the answer only
        // has to match the Sample ID, so segment/position are logged only.
        channelMap.put('querySegment', String(tokens[2] || ''));
        channelMap.put('queryPosition', String(tokens[3] || ''));
    }
    var table = {};
    var injected = globalMap.get('dimensionOrders');
    if (injected) {
        for (var k in injected) { table[k] = injected[k]; }
    }
    for (var d in QUERY_ORDERS) { if (!table[d]) { table[d] = QUERY_ORDERS[d]; } }
    var order = table[queryId];
    if (order && order.tests && order.tests.length) {
        channelMap.put('queriedOrder', 'D');
        responseMap.put('dimensionResponse', buildSampleRequest(order, queryId));
    } else {
        channelMap.put('queriedOrder', 'N');
        responseMap.put('dimensionResponse', 'N' + FS);
    }
    channelMap.put('controlMessage', raw);
    return;
}

if (msgType === 'M') {
    // Request Acceptance (instrument -> computer, Table 1-16) after our D
    // download. The data-link ACK was already sent by the mode; the manual
    // defines NO computer response to this message - decode and log only.
    // Note: the manual's own accept example carries an extra empty field
    // (M<FS><FS>A<FS>A<FS>1<FS>42), so the status is located by VALUE
    // (A/R) instead of by position.
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
        logger.warn('Instrument REJECTED the sample request: reason ' + mReason +
                ' (' + (REQUEST_REJECT_REASONS[mReason] || 'unknown') + ')' +
                ' - the order was NOT stored, re-queue or correct the request');
    }
    channelMap.put('controlMessage', raw);
    return;
}

if (msgType !== 'R') {
    // N/D and other control traffic - nothing to map to HL7,
    // and NO channel response (the mode already handled its ACK)
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
// (verified: the imported revision already had this right - re-formatted only)
var obr = ['OBR', '1', '', '', sampleNo + '^DIMENSIONSAMPLE',
           '', '', '', '', '',                                  //  5 -  9
           '', '', '', '', '',                                  // 10 - 14
           '', '', '', '', '', '', '',                          // 15 - 21
           hl7ts].join('|');                                    // 22

// Array literal verified balanced (28 x open/close brackets); kept as-is.
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
channelMap.put('location', location);
channelMap.put('priority', PRIORITIES[priorityC] || 'Routine');
channelMap.put('isQC', isQC);
channelMap.put('dilution', dilution);
channelMap.put('testCount', obxIndex);

// Route QC samples differently if your destination needs it:
// if (isQC) { ... }

msg = segments.join('\r');   // HL7 segment terminator
