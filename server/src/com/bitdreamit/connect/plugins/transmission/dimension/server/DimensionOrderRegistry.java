package com.bitdreamit.connect.plugins.transmission.dimension.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.log4j.Logger;

/**
 * Dynamic, in-memory order registry for bidirectional Dimension downloads.
 *
 * <p><b>Design goal (redesign rev 10):</b> the STREAM HANDLER answers Poll [P]
 * and Query [I] frames itself, directly inside the read path, exactly the way
 * Mirth's built-in ASTM/MLLP modes answer inside the frame layer. The channel
 * transformer is no longer involved in protocol replies at all - it only
 * reformats Result (R) frames to HL7. This removes every transformer-side
 * protocol error (row/map/strict-comparison failures) AND satisfies the
 * instrument's 1-second answer timer, because the Sample Request (D) or
 * No Request (N) goes on the wire milliseconds after the frame arrives -
 * long before Mirth's asynchronous channel processing would complete.</p>
 *
 * <p>Any other channel (HTTP receiver, Database Reader, TCP channel from the
 * HIS) pushes orders into this JVM-wide registry from JavaScript:</p>
 *
 * <pre>
 * // simplest form - tests as a comma-separated string (bullet-proof interop):
 * Packages.com.bitdreamit.connect.plugins.transmission.dimension.server
 *     .DimensionOrderRegistry.pushOrder('default', '043092011',
 *         'DOE,JOHN', '1', '0', 'GLU,CREA,F5');
 *
 * // or as a map (keys: sampleId|sample, patient, type, priority, tests):
 * var m = new java.util.HashMap();
 * m.put('sampleId', '043092011');
 * m.put('patient',  'DOE,JOHN');
 * m.put('type',     '1');          // Table 1-13 sample type
 * m.put('priority', '0');          // 0 routine .. 2 ASAP
 * m.put('tests',    'GLU,CREA');   // CSV string, java.util.List or Object[]
 * Packages.com.bitdreamit.connect.plugins.transmission.dimension.server
 *     .DimensionOrderRegistry.pushOrder('default', m);
 * </pre>
 *
 * <p>Queue key: channels pick their queue with the connector property
 * {@code orderQueueKey} (default {@code "default"}). The handler pops FIFO on
 * conversational polls and searches by sample ID on barcode queries [I].</p>
 *
 * <p><b>Demo mode:</b> starting Mirth with
 * {@code -Ddimension.demoOrders=true} pre-seeds the two demo orders
 * (012345, 043092011) once per queue, so barcode testing works before any
 * HIS integration exists.</p>
 *
 * <p>This class is not instantiable. All methods are static and thread-safe
 * (the stream handler runs on TCP reader threads, pushing channels on their
 * own threads).</p>
 */
public final class DimensionOrderRegistry {

    private static final Logger logger = Logger.getLogger(DimensionOrderRegistry.class);

    /** Default queue key used when the connector property is empty. */
    public static final String DEFAULT_KEY = "default";

    /** Hard limits from PN D00396 Table 1-12 / instrument constraints. */
    static final int MAX_SAMPLE_ID_LEN = 12;
    static final int MAX_PATIENT_LEN   = 27;
    static final int MAX_TEST_NAME_LEN = 5;
    static final int MAX_TESTS         = 36;

    private static final ConcurrentHashMap<String, ConcurrentLinkedQueue<DimensionOrder>> QUEUES =
            new ConcurrentHashMap<String, ConcurrentLinkedQueue<DimensionOrder>>();

    /**
     * Pending DELETE downloads (PN D00396 Table 1-12 Transaction = 'D').
     * The manual: "A request can only be deleted BEFORE processing starts;
     * resend the full request with D in the Transaction field." Each entry
     * carries the full original order so the delete frame is a byte-legal
     * D message (Sample ID + tests are mandatory even for a delete).
     */
    private static final ConcurrentHashMap<String, ConcurrentLinkedQueue<DimensionOrder>> DELETE_QUEUES =
            new ConcurrentHashMap<String, ConcurrentLinkedQueue<DimensionOrder>>();

    /**
     * Download bookkeeping: the most recently DOWNLOADED order per queue key
     * (the D frame was sent but no Request Acceptance [M] seen yet). The M
     * frame carries NO sample ID, so acceptance bookkeeping works on the
     * "most recent download per key" model - which matches the wire reality,
     * because the instrument answers each download with exactly one M frame
     * in the same dialogue before the next one starts.
     */
    private static final ConcurrentHashMap<String, InFlight> IN_FLIGHT =
            new ConcurrentHashMap<String, InFlight>();

    /** One downloaded-but-not-yet-accepted order. */
    public static final class InFlight {
        public final DimensionOrder order;
        public final long downloadedAt;
        public volatile String status;      // PENDING / ACCEPTED / REJECTED
        public volatile String rejectReason;
        InFlight(DimensionOrder order) {
            this.order = order;
            this.downloadedAt = System.currentTimeMillis();
            this.status = "PENDING";
        }
    }

    /** Orders whose Sample Request was REJECTED by the instrument (diagnostics). */
    private static final ConcurrentHashMap<String, List<InFlight>> REJECTED =
            new ConcurrentHashMap<String, List<InFlight>>();

    private static final ConcurrentHashMap<String, Boolean> DEMO_SEEDED =
            new ConcurrentHashMap<String, Boolean>();

    private DimensionOrderRegistry() {
        // utility class - no instances
    }

    // ------------------------------------------------------------------
    // One order (immutable value object)
    // ------------------------------------------------------------------
    public static final class DimensionOrder {
        private final String sampleId;
        private final String patient;
        private final String type;
        private final String priority;
        private final List<String> tests;

        DimensionOrder(String sampleId, String patient, String type,
                       String priority, List<String> tests) {
            this.sampleId = sampleId;
            this.patient = patient;
            this.type = type;
            this.priority = priority;
            this.tests = tests;
        }

        public String getSampleId() { return sampleId; }
        public String getPatient()  { return patient; }
        public String getType()     { return type; }
        public String getPriority() { return priority; }
        public List<String> getTests() { return new ArrayList<String>(tests); }
        public int getTestCount()   { return tests.size(); }

        @Override
        public String toString() {
            return "DimensionOrder{sampleId=" + sampleId + ", patient=" + patient
                    + ", type=" + type + ", priority=" + priority
                    + ", tests=" + tests + "}";
        }
    }

    // ------------------------------------------------------------------
    // PUSH API (called from JavaScript / other channels)
    // ------------------------------------------------------------------

    /**
     * Pushes one order with explicit fields. {@code testsCsv} is a
     * comma-separated analyzer test list, e.g. {@code "GLU,CREA,F5"}.
     * Null/empty optional fields fall back to the documented defaults
     * (type = 1 serum, priority = 0 routine).
     */
    public static DimensionOrder pushOrder(String key, String sampleId,
                                           String patient, String type,
                                           String priority, String testsCsv) {
        List<String> tests = new ArrayList<String>();
        if (testsCsv != null && !testsCsv.trim().isEmpty()) {
            for (String t : testsCsv.split("[,;]")) {
                if (!t.trim().isEmpty()) { tests.add(t.trim()); }
            }
        }
        DimensionOrder order = normalize(sampleId, patient, type, priority, tests);
        if (order == null) {
            throw new IllegalArgumentException(
                    "Dimension order needs a sample ID and at least one test");
        }
        queue(key).add(order);
        logger.info("Dimension order queued: key=" + key + " " + order);
        return order;
    }

    /**
     * Pushes one order from a Map. Accepted keys (case-insensitive, trimmed):
     * {@code sampleId} / {@code sample}, {@code patient}, {@code type},
     * {@code priority}, {@code tests} (java.util.Collection, Object[],
     * or CSV string). Returns the normalized order, never null.
     *
     * @throws IllegalArgumentException when the map carries no usable order
     */
    public static DimensionOrder pushOrder(String key, Map<?, ?> order) {
        if (order == null) {
            throw new IllegalArgumentException("order map is null");
        }
        String sampleId = firstString(order, "sampleId", "sample", "sampleid", "SampleID");
        String patient  = firstString(order, "patient", "patientName", "PatientID");
        String type     = firstString(order, "type", "sampleType");
        String priority = firstString(order, "priority", "stat");
        List<String> tests = new ArrayList<String>();
        Object t = getIgnoreCase(order, "tests", "test", "testNames");
        if (t instanceof Collection) {
            for (Object o : (Collection<?>) t) {
                if (o != null && !o.toString().trim().isEmpty()) { tests.add(o.toString().trim()); }
            }
        } else if (t instanceof Object[]) {
            for (Object o : (Object[]) t) {
                if (o != null && !o.toString().trim().isEmpty()) { tests.add(o.toString().trim()); }
            }
        } else if (t != null && !t.toString().trim().isEmpty()) {
            for (String s : t.toString().split("[,;]")) {
                if (!s.trim().isEmpty()) { tests.add(s.trim()); }
            }
        }
        DimensionOrder normalized = normalize(sampleId, patient, type, priority, tests);
        if (normalized == null) {
            throw new IllegalArgumentException(
                    "Dimension order map needs 'sampleId' (or 'sample') and at least one test");
        }
        queue(key).add(normalized);
        logger.info("Dimension order queued: key=" + key + " " + normalized);
        return normalized;
    }

    // ------------------------------------------------------------------
    // DELETE API (PN D00396 Table 1-12, Transaction = 'D')
    // ------------------------------------------------------------------

    /**
     * Queues a DELETE for an already-downloaded order. Per the manual the
     * delete must "resend the full request with D in the Transaction field",
     * so the same fields as an add are required (Sample ID + at least one
     * test name). The delete goes out on the NEXT conversational Poll [P] -
     * deletes can only be sent when the instrument asks (Request = 1).
     *
     * @throws IllegalArgumentException when no usable order is supplied
     */
    public static DimensionOrder pushDelete(String key, String sampleId,
                                            String patient, String type,
                                            String priority, String testsCsv) {
        List<String> tests = new ArrayList<String>();
        if (testsCsv != null && !testsCsv.trim().isEmpty()) {
            for (String t : testsCsv.split("[,;]")) {
                if (!t.trim().isEmpty()) { tests.add(t.trim()); }
            }
        }
        DimensionOrder order = normalize(sampleId, patient, type, priority, tests);
        if (order == null) {
            throw new IllegalArgumentException(
                    "Dimension delete needs a sample ID and the original test list");
        }
        deleteQueue(key).add(order);
        logger.info("Dimension DELETE queued: key=" + key + " " + order);
        return order;
    }

    /** FIFO pop of the next pending delete - used by the poll answer path. */
    public static DimensionOrder takeNextDelete(String key) {
        DimensionOrder o = deleteQueue(key).poll();
        if (o != null) { logger.info("Dimension DELETE taken (FIFO): " + o); }
        return o;
    }

    public static int deleteQueueSize(String key) { return deleteQueue(key).size(); }

    // ------------------------------------------------------------------
    // DOWNLOAD BOOKKEEPING (Request Acceptance [M] follow-up)
    // ------------------------------------------------------------------

    /**
     * Marks an order as DOWNLOADED (its Sample Request [D] was just sent).
     * Called by the stream handler right after the frame left the wire.
     * The entry stays PENDING until the transformer sees the instrument's
     * Request Acceptance [M] and calls {@link #confirmLastDownload(String)}
     * or {@link #rejectLastDownload(String, String)}.
     */
    public static void markDownloaded(String key, DimensionOrder order) {
        if (order == null) { return; }
        String k = normalizeKey(key);
        IN_FLIGHT.put(k, new InFlight(order));
        logger.info("Dimension download in flight: key=" + k + " " + order);
    }

    /**
     * Request Acceptance [M] with Status = 'A' arrived: the instrument
     * STORED the last download. Confirms (and clears) the in-flight entry.
     *
     * @param key queue key; null/"default" resolves to "default"
     * @return the confirmed order, or null when nothing was in flight
     */
    public static DimensionOrder confirmLastDownload(String key) {
        String k = normalizeKey(key);
        InFlight f = IN_FLIGHT.remove(k);
        if (f == null) { return null; }
        f.status = "ACCEPTED";
        logger.info("Dimension download ACCEPTED by instrument: " + f.order);
        return f.order;
    }

    /**
     * Request Acceptance [M] with Status = 'R' arrived: the instrument
     * REFUSED the last download (Table 1-17 reason). The order is moved to
     * the rejected list for diagnostics - it is NOT silently lost and NOT
     * blindly re-sent (a rejected request would be rejected again; per the
     * manual the LIS must correct or re-queue it deliberately).
     *
     * @param reason Table 1-17 rejection reason code ('1'..'9', may be empty)
     * @return the rejected order, or null when nothing was in flight
     */
    public static DimensionOrder rejectLastDownload(String key, String reason) {
        String k = normalizeKey(key);
        InFlight f = IN_FLIGHT.remove(k);
        if (f == null) { return null; }
        f.status = "REJECTED";
        f.rejectReason = reason == null ? "" : reason.trim();
        List<InFlight> list = REJECTED.get(k);
        if (list == null) {
            list = java.util.Collections.synchronizedList(new ArrayList<InFlight>());
            REJECTED.put(k, list);
        }
        list.add(f);
        logger.error("Dimension download REJECTED by instrument: reason=" + f.rejectReason
                + " order=" + f.order + " - order NOT stored on instrument; correct it "
                + "and push again (pushOrder) or check test mnemonics/sample type");
        return f.order;
    }

    /** Most recent in-flight download for the key (or null). */
    public static InFlight getInFlight(String key) {
        return IN_FLIGHT.get(normalizeKey(key));
    }

    /** Rejected downloads for the key, most recent last (diagnostics). */
    public static List<InFlight> getRejected(String key) {
        List<InFlight> list = REJECTED.get(normalizeKey(key));
        return list == null ? new ArrayList<InFlight>() : new ArrayList<InFlight>(list);
    }

    /**
     * Re-queues a previously REJECTED order (e.g. after fixing a test
     * mnemonic in the LIS). Convenience over {@link #pushOrder(String, String, String, String, String, String)}.
     */
    public static DimensionOrder requeueOrder(String key, DimensionOrder order) {
        if (order == null) {
            throw new IllegalArgumentException("cannot requeue a null order");
        }
        DimensionOrder copy = normalize(order.getSampleId(), order.getPatient(),
                order.getType(), order.getPriority(), order.getTests());
        if (copy == null) {
            throw new IllegalArgumentException("order has no sample ID or tests");
        }
        queue(key).add(copy);
        logger.info("Dimension order re-queued: key=" + key + " " + copy);
        return copy;
    }

    // ------------------------------------------------------------------
    // TAKE API (called by DimensionStreamHandler in the read path)
    // ------------------------------------------------------------------

    /** FIFO pop - used for conversational Poll [P] downloads. */
    public static DimensionOrder takeOrder(String key) {
        DimensionOrder o = queue(key).poll();
        if (o != null) { logger.info("Dimension order taken (FIFO): " + o); }
        return o;
    }

    /**
     * Search + remove by sample ID - used for barcode Query [I] downloads.
     * The scan may carry padding/whitespace; comparison is exact after trim
     * (analyzer barcodes are fixed-width, no case folding by protocol).
     */
    public static DimensionOrder findOrder(String key, String sampleId) {
        if (sampleId == null) { return null; }
        String want = sampleId.trim();
        if (want.isEmpty()) { return null; }
        ConcurrentLinkedQueue<DimensionOrder> q = queue(key);
        for (DimensionOrder o : q) {
            if (want.equals(o.getSampleId().trim())) {
                if (q.remove(o)) {
                    logger.info("Dimension order matched query " + want + ": " + o);
                    return o;
                }
                // another reader thread won the race - fall through to retry loop
            }
        }
        return null;
    }

    public static int queueSize(String key) { return queue(key).size(); }

    /** Drops every queued order AND every pending delete for the key (maintenance/testing). */
    public static void clear(String key) {
        String k = normalizeKey(key);
        queue(k).clear();
        deleteQueue(k).clear();
        IN_FLIGHT.remove(k);
        REJECTED.remove(k);
        logger.info("Dimension order queue cleared: key=" + k);
    }

    // ------------------------------------------------------------------
    // Demo seeding (-Ddimension.demoOrders=true)
    // ------------------------------------------------------------------

    /**
     * Seeds the two demo orders (012345, 043092011) once per key when the
     * system property {@code dimension.demoOrders} is {@code true} and the
     * queue is currently empty. Called by the stream handler before every
     * lookup - cheap (two map reads) and idempotent.
     */
    public static void seedDemoOrdersIfEnabled(String key) {
        String flag = System.getProperty("dimension.demoOrders", "false");
        if (!"true".equalsIgnoreCase(flag)) { return; }
        if (queue(key).isEmpty() && DEMO_SEEDED.putIfAbsent(key, Boolean.TRUE) == null) {
            queue(key).add(normalize("012345", "Doe,John", "2", "0", list("BUN", "CREA", "F5")));
            queue(key).add(normalize("043092011", "", "1", "0", list("BUN", "CREA", "F5")));
            logger.info("Demo Dimension orders seeded into queue '" + key + "' "
                    + "(-Ddimension.demoOrders=true)");
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static ConcurrentLinkedQueue<DimensionOrder> queue(String key) {
        String k = normalizeKey(key);
        ConcurrentLinkedQueue<DimensionOrder> q = QUEUES.get(k);
        if (q == null) {
            q = new ConcurrentLinkedQueue<DimensionOrder>();
            ConcurrentLinkedQueue<DimensionOrder> prev = QUEUES.putIfAbsent(k, q);
            if (prev != null) { q = prev; }
        }
        return q;
    }

    private static ConcurrentLinkedQueue<DimensionOrder> deleteQueue(String key) {
        String k = normalizeKey(key);
        ConcurrentLinkedQueue<DimensionOrder> q = DELETE_QUEUES.get(k);
        if (q == null) {
            q = new ConcurrentLinkedQueue<DimensionOrder>();
            ConcurrentLinkedQueue<DimensionOrder> prev = DELETE_QUEUES.putIfAbsent(k, q);
            if (prev != null) { q = prev; }
        }
        return q;
    }

    private static String normalizeKey(String key) {
        return (key == null || key.trim().isEmpty()) ? DEFAULT_KEY : key.trim();
    }

    private static DimensionOrder normalize(String sampleId, String patient,
                                            String type, String priority,
                                            List<String> rawTests) {
        String id = sampleId == null ? "" : sampleId.trim();
        if (id.isEmpty() || rawTests.isEmpty()) { return null; }

        List<String> tests = new ArrayList<String>();
        for (String t : rawTests) {
            if (tests.size() >= MAX_TESTS) { break; }
            String name = t == null ? "" : t.trim().toUpperCase();
            if (name.isEmpty()) { continue; }
            if (name.length() > MAX_TEST_NAME_LEN) { name = name.substring(0, MAX_TEST_NAME_LEN); }
            tests.add(name);
        }
        if (tests.isEmpty()) { return null; }

        String pat = patient == null ? "" : patient.trim();
        if (pat.length() > MAX_PATIENT_LEN) { pat = pat.substring(0, MAX_PATIENT_LEN); }

        String ty = (type == null || type.trim().isEmpty()) ? "1" : type.trim();
        String pr = (priority == null || priority.trim().isEmpty()) ? "0" : priority.trim();

        if (id.length() > MAX_SAMPLE_ID_LEN) { id = id.substring(0, MAX_SAMPLE_ID_LEN); }
        return new DimensionOrder(id, pat, ty, pr, tests);
    }

    private static List<String> list(String... items) {
        List<String> out = new ArrayList<String>(items.length);
        for (String i : items) { out.add(i); }
        return out;
    }

    private static Object getIgnoreCase(Map<?, ?> m, String... keys) {
        for (String k : keys) {
            if (m.containsKey(k)) { return m.get(k); }
        }
        // last resort: case-insensitive scan
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String mk = String.valueOf(e.getKey()).trim();
            for (String k : keys) {
                if (mk.equalsIgnoreCase(k)) { return e.getValue(); }
            }
        }
        return null;
    }

    private static String firstString(Map<?, ?> m, String... keys) {
        Object v = getIgnoreCase(m, keys);
        return v == null ? null : String.valueOf(v);
    }

    /** Visible for tests: snapshot of all queue keys (diagnostics). */
    static Map<String, Integer> snapshot() {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, ConcurrentLinkedQueue<DimensionOrder>> e : QUEUES.entrySet()) {
            out.put(e.getKey(), e.getValue().size());
        }
        return out;
    }
}
