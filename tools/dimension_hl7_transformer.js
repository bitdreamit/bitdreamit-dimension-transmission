/**
 * Siemens Dimension (EXL / RxL / Xpand)  -  Mirth Source Transformer
 * HL7 mode (bitdreamit-dimension-transmission v2.2.0+, Message Output Format = HL7_V2)
 * ---------------------------------------------------------------------------
 * Pattern identical to the Bio-Rad D-10 / Erba ASTM transformers:
 * the JAVA plugin converts every raw Dimension frame into a standard HL7
 * v2.x message BEFORE dispatch (same idea as the ASTM E1394 datatype), so
 * this transformer just reads msg[...] with normal HL7 navigation - no frame
 * splitting, no checksum math, no FS characters, no protocol knowledge.
 *
 * CHANNEL REQUIREMENTS (v2.2.0 HL7 mode)
 *   Channel Source Inbound Data Type = HL7 V2.x   (the plugin emits real HL7)
 *   Source Response                  = None       (the plugin answers the analyzer
 *                                                  directly on the line: ACK/NAK,
 *                                                  N, D order download, M-A)
 *   Transmission mode                = Siemens Dimension
 *
 * WHAT ARRIVES HERE (every frame type, converted by the plugin):
 *   R  Result              -> ORU^R01  MSH + PID + OBR + OBX (one per test) + NTE
 *   C  Calibration Result  -> ORU^R01  OBR-4.2 = 'CALIBRATION'  (QC routing)
 *   I  Query (barcode scan)-> QRY^A19  barcode in PID-3.1 AND QRD-8
 *   M  Request Acceptance  -> ACK^D01  MSA|AA stored / MSA|AE rejected (+ ERR)
 *   P  Poll                -> ACK^P01  MSA|CA|<instrumentId>    (control, ignore)
 *   N  No Request          -> ACK^N01  MSA|CA|                  (control, ignore)
 *
 * HL7 FIELD MAP (PN D00396 -> HL7, all verified against live captures):
 *   barcode/sample#  = OBR-4.1  ('152^DIMENSIONSAMPLE')   [R frames]
 *   barcode (query)  = PID-3.1  ('26091827')              [I frames]
 *   patient id       = PID-3.1  (when the analyzer sends one)
 *   sample type      = NTE|SAMPLE_TYPE segment (Serum, Plasma, ...)
 *   priority         = NTE|PRIORITY segment    (Routine, STAT, ...)
 *   analysis ts      = OBR-22 (YYYYMMDDHHMMSS, converted from ssmmhhddmmyy)
 *   results          = OBX repetition: OBX-3.1 test, OBX-5 value, OBX-6 unit,
 *                      OBX-11 'F', OBX-14 analysis date
 *   errors           = NTE|'Error n: text' after the OBX (Appendix III/IV)
 */

try {

    // -------------------------------------------------------------------
    // 0. Route on the converted message type (MSH-9.1 / MSH-9.2)
    // -------------------------------------------------------------------
    var mshType = msg['MSH']['MSH.9']['MSH.9.1'].toString();   // ORU | QRY | ACK
    var mshSub  = msg['MSH']['MSH.9']['MSH.9.2'].toString();   // R01 | A19 | D01/P01/N01

    channelMap.put('mirth_source', 'DimensionEXL');
    channelMap.put('mirth_type', mshType + '^' + mshSub);
    channelMap.put('messageControlId', msg['MSH']['MSH.10']['MSH.10.1'].toString());

    var data = [];

    // ===================================================================
    // A. Control frames (poll P / no-request N) - ignore quietly.
    //    The analyzer polls every 15 s; these messages carry no business
    //    data (the plugin already answered them on the line).
    // ===================================================================
    if (mshType === 'ACK' && (mshSub === 'P01' || mshSub === 'N01')) {
        channelMap.put('controlFrame', mshSub);
        return;

    // ===================================================================
    // B. Request Acceptance (M frame, answer to our order download)
    //    MSA|AA|<sampleId>        -> the instrument STORED the order
    //    MSA|AE|<sampleId>|<code> -> the instrument REFUSED it (ERR text)
    // ===================================================================
    } else if (mshType === 'ACK' && mshSub === 'D01') {
        var Reg = Packages.com.bitdreamit.connect.plugins.transmission.dimension.server
                      .DimensionOrderRegistry;
        var queueKey = String(globalMap.get('dimensionOrderQueueKey') || 'default');
        var ackCode  = msg['MSA']['MSA.1']['MSA.1.1'].toString();   // AA | AE
        var sampleId = msg['MSA']['MSA.2']['MSA.2.1'].toString();

        if (ackCode === 'AA') {
            var confirmed = Reg.confirmLastDownload(queueKey);
            channelMap.put('downloadAccepted', sampleId || (confirmed != null ? confirmed.getSampleId() : ''));
            logger.info('Dimension order ACCEPTED by instrument: ' + sampleId);
        } else if (ackCode === 'AE') {
            var reason = msg['MSA']['MSA.3']['MSA.3.1'].toString();
            var errText = msg['ERR']['ERR.3']['ERR.3.1'].toString();
            var rejected = Reg.rejectLastDownload(queueKey, reason);
            channelMap.put('downloadRejected', rejected != null ? rejected.getSampleId() : sampleId);
            channelMap.put('downloadRejectReason', reason);
            logger.error('Dimension order REJECTED by instrument: reason ' + reason
                    + ' (' + errText + ') - correct it in the LIS and push again '
                    + '(DimensionOrderRegistry.pushOrder / requeueOrder)');
        }
        return;

    // ===================================================================
    // C. Barcode Query (I frame, Send ID/Receive mode) -> DB lookup.
    //    The analyzer scanned barcode <PID-3.1> and asked for the order.
    //    ANSWERING: the plugin answers the QUERY ITSELF inside the read
    //    path from the DimensionOrderRegistry (Sample Request D, or N when
    //    unknown) - the 1-second instrument timer can never be missed.
    //    THIS transformer loads the order from the LIS DB into the
    //    registry so the CURRENT query (if the analyzer re-polls) and all
    //    future queries find it. For zero-retry answers use the order
    //    feeder channel (pre-push) - see FULL-BIDIRECTIONAL-DOCUMENTATION.md.
    // ===================================================================
    } else if (mshType === 'QRY') {
        var barcode = msg['PID']['PID.3']['PID.3.1'].toString() || null;
        channelMap.put('queriedBarcode', barcode);

        if (barcode) {
            var RegQ = Packages.com.bitdreamit.connect.plugins.transmission.dimension.server
                           .DimensionOrderRegistry;
            var queueKeyQ = String(globalMap.get('dimensionOrderQueueKey') || 'default');

            // -----------------------------------------------------------
            // LIVE DB QUERY - replace the connection details with yours.
            // Example LIS schema:
            //   orders(barcode, patient_name, sample_type, priority, tests, status)
            //   tests CSV example: 'BUN,CREA,GLU'   (max 36 x 5 chars)
            // -----------------------------------------------------------
            var dbConn = null;
            try {
                dbConn = DatabaseConnectionFactory.createDatabaseConnection(
                        'com.mysql.cj.jdbc.Driver',                    // driver
                        'jdbc:mysql://127.0.0.1:3306/lis',             // url
                        'lis', 'secret');                              // user/pass
                var rs = dbConn.executeCachedQuery(
                        "SELECT patient_name, sample_type, priority, tests FROM orders " +
                        "WHERE barcode = '" + barcode.replace(/'/g, "''") + "' AND status = 'NEW' LIMIT 1");
                if (rs.next()) {
                    var patient  = String(rs.getString('patient_name') || '');
                    var type     = String(rs.getString('sample_type') || '1');  // 1=Serum
                    var priority = String(rs.getString('priority') || '0');     // 0=Routine
                    var tests    = String(rs.getString('tests') || '');
                    if (tests) {
                        RegQ.pushOrder(queueKeyQ, barcode, patient, type, priority, tests);
                        channelMap.put('orderPushed', tests);
                        logger.info('DB order pushed for query ' + barcode + ': ' + tests);
                    }
                } else {
                    channelMap.put('orderPushed', '');
                    logger.warn('No LIS order for scanned barcode ' + barcode
                            + ' - instrument receives No Request (N)');
                }
            } finally {
                if (dbConn != null) { dbConn.close(); }
            }
        }
        return;

    // ===================================================================
    // D. ORU^R01
    // ===================================================================
    } else if (mshType === 'ORU') {
        var obr4 = msg['OBR']['OBR.4']['OBR.4.1'].toString() || '';
        var obr42 = msg['OBR']['OBR.4']['OBR.4.2'].toString() || '';

        // -----------------------------------------------------------
        // D1. Calibration/QC result (C frame) - OBR-4.2 = CALIBRATION
        // -----------------------------------------------------------
        if (obr42 === 'CALIBRATION') {
            channelMap.put('calibrationTest', obr4);
            channelMap.put('calibrationLot', msg['OBX'][0]['OBX.5']['OBX.5.1'].toString());
            channelMap.put('analysisDateTime', msg['OBR']['OBR.22']['OBR.22.1'].toString());
            logger.info('Calibration/QC result received for ' + obr4
                    + ' - route to your QC table here');
            return;
        }

        // -----------------------------------------------------------
        // D2. Patient result (R frame) - the D-10 style mapping
        // -----------------------------------------------------------
        var barcodeR = obr4;                                          // '152'
        var patientId = msg['PID']['PID.3']['PID.3.1'].toString() || null;
        var analysisTs = msg['OBR']['OBR.22']['OBR.22.1'].toString() || '';
        var analysisDate = analysisTs.substring(0, 8) || null;

        // Sample type / priority are carried as traceability NTE segments
        var sampleType = null;
        var priority = null;
        for each (var nte in msg['NTE']) {
            var nteType = nte['NTE.3']['NTE.3.1'].toString();
            if (nteType === 'SAMPLE_TYPE') { sampleType = nte['NTE.4']['NTE.4.1'].toString(); }
            if (nteType === 'PRIORITY')    { priority    = nte['NTE.4']['NTE.4.1'].toString(); }
        }

        // idMapping / testMapping - map analyzer test names to YOUR ids
        // (same structure as the D-10 and Erba transformers)
        const idMapping = {
            'ALTI': 101, 'CRE2': 102, 'GLUC': 103, 'BUN': 104, 'GLU': 105
            // ... add every test your laboratory runs ...
        };
        const testMapping = {
            'ALTI': 100, 'CRE2': 100, 'GLUC': 100, 'BUN': 100, 'GLU': 100
            // ... parent panel ids ...
        };

        // Value formatter (same shape as the D-10 transformer)
        function formatValue(value) {
            if (!isNaN(value)) {
                var numValue = parseFloat(value);
                return Number.isInteger(numValue) ? numValue : numValue.toFixed(2);
            }
            return value;
        }

        // Iterate the OBX repetitions - one row per test
        for each (var obx in msg['OBX']) {
            var name  = obx['OBX.3']['OBX.3.1'].toString() || null;   // ALTI / CRE2
            var value = obx['OBX.5']['OBX.5.1'].toString() || null;   // 72 / 0.59
            var unit  = obx['OBX.6']['OBX.6.1'].toString() || '';     // U/L / mg/dL
            var status = obx['OBX.11']['OBX.11.1'].toString() || 'F';
            if (!name) { continue; }

            data.push({
                id:      idMapping[name] || null,
                test_id: testMapping[name] || null,
                name:    name,
                value:   value != null ? formatValue(value) : null,
                unit:    unit,
                status:  status,
                date:    analysisDate
            });
        }

        channelMap.put('barcode', barcodeR);
        channelMap.put('patientId', patientId);
        channelMap.put('sampleType', sampleType);
        channelMap.put('priority', priority);
        channelMap.put('analysisDateTime', analysisTs);
        channelMap.put('testCount', data.length);
        channelMap.put('data', JSON.stringify(data));

        // -----------------------------------------------------------
        // WRITE TO YOUR LIS DATABASE (destination side example)
        // -----------------------------------------------------------
        // Put a JavaScript Writer destination on the channel with:
        //   var rows = JSON.parse(channelMap.get('data'));
        //   var barcode = channelMap.get('barcode');
        //   for (var i = 0; i < rows.length; i++) {
        //       var r = rows[i];
        //       // INSERT INTO results(barcode, test_id, name, value, unit, date)
        //       // VALUES ('...', ..., ..., ..., ..., '...');
        //   }
        // Or map rows[i].value directly in a Database Writer template.
        // -----------------------------------------------------------

    } else {
        // Unknown converted type - log and keep the message for inspection
        logger.warn('Unexpected Dimension message type: ' + mshType + '^' + mshSub);
    }

} catch (error) {
    // A conversion problem must never silently drop a result: log loudly
    // and rethrow so the message shows ERROR in the Mirth dashboard.
    logger.error('Error parsing Dimension HL7 message: ' + error.message);
    throw error;
}
