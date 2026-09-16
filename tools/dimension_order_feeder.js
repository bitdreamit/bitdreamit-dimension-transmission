/**
 * Dimension ORDER FEEDER - LIS database  ->  DimensionOrderRegistry
 * ---------------------------------------------------------------------------
 * This is the "DB query for barcode" half of the bidirectional link.
 *
 * HOW THE PUZZLE FITS (Send ID/Receive + Send/Receive modes):
 *
 *   1. The HIS/LIS writes the order into your orders table
 *      (status = 'NEW'), e.g. from your LIS front end or billing system.
 *   2. THIS script runs inside a Mirth "Dimension Order Feeder" channel
 *      (Database Reader source, poll 30-60 s). It SELECTs every NEW /
 *      CANCELLED row and pushes it into the JVM-wide registry:
 *          NEW       -> DimensionOrderRegistry.pushOrder(...)
 *          CANCELLED -> DimensionOrderRegistry.pushDelete(...)
 *      and marks the rows QUEUED / DELETED so each is pushed exactly once.
 *   3. When the operator puts the tube on the analyzer, the instrument
 *      sends Query [I <barcode>] (Send ID/Receive) or Poll [P] (Send/
 *      Receive). DimensionStreamHandler answers INSIDE the read path,
 *      milliseconds after the frame - the registry lookup IS the DB data
 *      that was pushed in step 2. This pre-push design is what makes the
 *      1-second instrument timers unbreakable; a live SQL query inside
 *      those timers cannot guarantee that.
 *   4. The instrument replies M-A (stored) or M-R (rejected). The channel
 *      transformer calls confirmLastDownload / rejectLastDownload.
 *   5. When the sample finishes, the Result [R] frame flows to the LIS as
 *      HL7 ORU^R01 through the main Dimension channel.
 *
 * ---------------------------------------------------------------------------
 * CHANNEL SETUP (Mirth Administrator)
 * ---------------------------------------------------------------------------
 *   Channel name : Dimension Order Feeder
 *   Source       : Database Reader
 *       - Database Driver : pick yours (MySQL, PostgreSQL, Oracle, SQL Server)
 *       - Connection URL  : jdbc:mysql://127.0.0.1:3306/lis  (example)
 *       - Username / Password
 *       - Poll On New Data ("Interval"), e.g. 30 seconds
 *       - SQL (SELECT statement) - the script reads rows from 'msg':
 *
 *             SELECT order_id, barcode, patient_name, sample_type,
 *                    priority, tests, status
 *             FROM lis_dimension_orders
 *             WHERE status IN ('NEW', 'CANCELLED')
 *             ORDER BY order_id
 *
 *   Inbound data type : Raw (or any - the script only reads the ResultSet
 *   through the Database Reader's row binding: each row arrives as
 *   <result><field><name>..</name><value>..</value></field>... in msg).
 *   Destination       : Channel Writer = None / JavaScript Writer that does
 *   nothing - ALL work happens in this source transformer; the row is then
 *   marked in the DB by direct UPDATE from here.
 *
 * ---------------------------------------------------------------------------
 * ORDERS TABLE (run once in your LIS database)
 * ---------------------------------------------------------------------------
 *   CREATE TABLE lis_dimension_orders (
 *       order_id     INT AUTO_INCREMENT PRIMARY KEY,   -- BIGSERIAL on PG
 *       barcode      VARCHAR(12)  NOT NULL,            -- Sample ID, max 12
 *       patient_name VARCHAR(27)  DEFAULT '',          -- max 27 chars
 *       sample_type  CHAR(1)      DEFAULT '1',         -- 1 serum, 2 plasma,
 *                                                      -- 3 urine, 4 CSF, W WB
 *       priority     CHAR(1)      DEFAULT '0',         -- 0 routine 1 STAT
 *                                                      -- 2 ASAP 3 QC 4 XQC
 *       tests        VARCHAR(255) NOT NULL,            -- 'GLU,CREA' upper
 *       status       VARCHAR(10)  DEFAULT 'NEW',       -- NEW/QUEUED/DELETED
 *                                                      -- /DONE/CANCELLED
 *       updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
 *                 ON UPDATE CURRENT_TIMESTAMP
 *   );
 *
 *   Your LIS writes:  INSERT INTO lis_dimension_orders
 *                     (barcode, patient_name, sample_type, priority, tests)
 *                     VALUES ('26091827','DOE,JOHN','1','1','GLU,CREA');
 *   A cancel is:      UPDATE lis_dimension_orders SET status='CANCELLED'
 *                     WHERE barcode='26091827' AND status='QUEUED';
 * ---------------------------------------------------------------------------
 */

// ==== 1. CONFIGURATION =====================================================
var CFG = {
    queueKey: 'default',   // MUST match the Dimension channel's "Order Queue Key"
    // Columns of the SELECT above (Database Reader binds them by position
    // into msg['result']['field']); adjust if you renamed columns:
    col: { orderId: 0, barcode: 1, patient: 2, type: 3, priority: 4, tests: 5, status: 6 }
};

// JDBC access for the UPDATE statements. The Database Reader connection can
// be reused through DatabaseConnectionFactory - create one matching your URL:
var DB_URL  = 'jdbc:mysql://127.0.0.1:3306/lis';
var DB_USER = 'lis_user';
var DB_PASS = 'lis_pass';

// ==== 2. REGISTRY HANDLE ===================================================
var Reg = Packages.com.bitdreamit.connect.plugins.transmission.dimension.server
              .DimensionOrderRegistry;

// ==== 3. ROW HELPERS =======================================================
// Database Reader rows arrive as XML: <result><field>... - read by index.
function fieldValue(rowIndex, name) {
    try {
        var f = msg['result'][rowIndex]['field'];
        for (var i = 0; i < f.length(); i++) {
            if (String(f[i]['name'].toString()) === name) {
                return String(f[i]['value'].toString());
            }
        }
        return '';
    } catch (e) {
        return '';
    }
}

// Fallback when the Database Reader is configured with the legacy
// "use XPath iterator" mode - rows arrive one message per row.
function singleField(name) {
    try { return String(msg[name].toString()); } catch (e) { return ''; }
}

var orderId   = fieldValue(CFG.col.orderId, 'order_id')   || singleField('order_id');
var barcode   = fieldValue(CFG.col.barcode, 'barcode')    || singleField('barcode');
var patient   = fieldValue(CFG.col.patient, 'patient_name') || singleField('patient_name');
var sType     = fieldValue(CFG.col.type, 'sample_type')   || singleField('sample_type');
var priority  = fieldValue(CFG.col.priority, 'priority')  || singleField('priority');
var tests     = fieldValue(CFG.col.tests, 'tests')        || singleField('tests');
var status    = fieldValue(CFG.col.status, 'status')      || singleField('status');

if (!barcode || !tests) {
    logger.warn('Dimension feeder: row ' + orderId + ' skipped (no barcode/tests)');
    return;
}

// ==== 4. PUSH INTO THE REGISTRY ============================================
var dbConn = null;
try {
    dbConn = DatabaseConnectionFactory.createDatabaseConnection(
                 'com.mysql.cj.jdbc.Driver', DB_URL, DB_USER, DB_PASS);

    if (status === 'NEW') {
        Reg.pushOrder(CFG.queueKey, barcode, patient, sType, priority, tests);
        dbConn.executeUpdate(
            "UPDATE lis_dimension_orders SET status='QUEUED' WHERE order_id=" +
            orderId.replace(/[^0-9]/g, ''));
        logger.info('Feeder: pushed order ' + barcode + ' tests=' + tests);

    } else if (status === 'CANCELLED') {
        try {
            Reg.pushDelete(CFG.queueKey, barcode, patient, sType, priority, tests);
            dbConn.executeUpdate(
                "UPDATE lis_dimension_orders SET status='DELETED' WHERE order_id=" +
                orderId.replace(/[^0-9]/g, ''));
            logger.info('Feeder: pushed DELETE for ' + barcode);
        } catch (delErr) {
            // pushDelete throws when the row has no tests - the manual needs
            // the full request for a delete; fall back to marking it deleted.
            logger.warn('Feeder: DELETE for ' + barcode + ' skipped: ' + delErr);
            dbConn.executeUpdate(
                "UPDATE lis_dimension_orders SET status='DELETED' WHERE order_id=" +
                orderId.replace(/[^0-9]/g, ''));
        }
    }
} finally {
    if (dbConn != null) { dbConn.close(); }
}

// ==== 5. OPTIONAL: ACCEPTANCE FEEDBACK LOOP ================================
// The main Dimension channel's transformer sees the instrument's M frame.
// To sync acceptance back into the LIS DB, add this to a shared JavaScript
// there (already wired in dimension_result_transformer.js v2.1.0):
//
//   M-A (accepted)  -> UPDATE ... SET status='DONE'      WHERE barcode=?
//   M-R (rejected)  -> UPDATE ... SET status='REJECTED_' || reason
//                      and fix/re-insert the order.
//
// The rejected order also stays visible in memory:
//   Reg.getRejected('default')  -> list of rejected InFlight entries.
