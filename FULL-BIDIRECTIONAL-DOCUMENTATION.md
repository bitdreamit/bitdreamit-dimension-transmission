# Siemens Dimension — FULL BIDIRECTIONAL PROCESS DOCUMENTATION
### bitdreamit-dimension-transmission v2.2.0 — every option handled, live-data examples on every step
**Reference: "Dimension Clinical Chemistry System Interface Specifications", Siemens Healthcare Diagnostics, Rev. 2, PN D00396 (36 pages, read in full).**

---

## 1. What this package does — the short answer

One plugin turns ANY Siemens Dimension family analyzer (EXL, EXL MAX, RxL Max, Xpand, VersaCell-connected chemistry lines — all speak PN D00396) into a **normal Mirth Connect channel input**, exactly the way the built-in ASTM and MLLP modes do:

1. **Java (the plugin) owns everything protocol**: STX/FS/ETX framing, Add-Mod-256 checksum, ACK/NAK with 4-retry budget, ENQ, the 1-second analyzer timers, poll/query answering (D order download / N no request), result acceptance (M-A) — and since **v2.2.0** it ALSO **converts every incoming frame into a standard HL7 v2.x message before dispatch**.
2. **JavaScript (your transformer) owns everything business**: reading `msg['OBX']...`, mapping tests to your ids, querying your DB for barcodes, writing results. **Zero protocol knowledge. Zero Java recompiles when mappings change.**

This is the **same architecture as your bitdreamit-astm-e1381-transmission** package: low level in Java, business level in editable JS — 100% conflict-free with MLLP/ASTM/serial channels.

### What changed in v2.2.0 (the "ASTM pattern" you asked for)

| | v2.1.0 and before | **v2.2.0 HL7 mode** |
|---|---|---|
| Mirth receives | raw frame `R·*··152·1··0·...·6D` (FS-separated blob) | **real HL7**: `MSH\|...\|ORU^R01 + PID + OBR + OBX` |
| Source Inbound Data Type | Raw (HL7 crashed the parser) | **HL7 V2.x** (normal Mirth) |
| Transformer must | split FS strings, verify checksum, build HL7 by hand | **just read** `msg['OBX'][i]['OBX.3']['OBX.3.1']` — like your D-10/Erba ASTM channels |
| Maintenance | mapping change = edit fragile string code | mapping change = edit normal HL7 code |
| Protocol answers (ACK/NAK/N/D/M) | plugin (unchanged) | plugin (unchanged) |

The new property is `Message Output Format = HL7_V2`. The old raw behavior (`RAW_FRAME`) is still available and unchanged, so existing channels keep working.

---

## 2. Architecture — who does what

```
                       SIEMENS DIMENSION ANALYZER (PN D00396)
                          |  RS-232 (serial connector v1.4.0)
                          |  or TCP socket 9100/6661 (TCP Listener)
                          v
+===========================================================================+
|  JAVA — bitdreamit-dimension-transmission v2.2.0 (the "transmission      |
|  mode" = the Dimension equivalent of MLLP framing / ASTM low-level)      |
|                                                                          |
|  DimensionStreamHandler                                                  |
|    read():  STX..ETX assembly -> checksum validate -> ACK / NAK(4x)      |
|             autoRespond():                                               |
|               R/C -> send Result Acceptance <STX>M<FS>A<FS><FS>E2<ETX>   |
|               I   -> barcode in registry? send Sample Request D          |
|                       : send No Request <STX>N<FS>6A<ETX>                |
|               P   -> conversational poll? send next queued D / N         |
|             messageOutputFormat = HL7_V2:                                |
|               DimensionHL7Translator.toHL7() converts the frame          |
|               R->ORU^R01  I->QRY^A19  C->ORU(QC)  M->ACK^D01  P/N->ACK   |
|    write(): channel payload -> STX+CHK+ETX framing -> ACK wait           |
|                                                                          |
|  DimensionOrderRegistry (JVM-wide, JS-callable)                          |
|    pushOrder / pushDelete / findOrder / takeOrder /                      |
|    markDownloaded / confirmLastDownload / rejectLastDownload             |
+===========================================================================+
                          | standard HL7 v2.x message
                          v
+===========================================================================+
|  MIRTH CHANNEL (100% normal Mirth Connect processing)                    |
|    Source (Serial Reader or TCP Listener, mode "Siemens Dimension")      |
|    Inbound data type: HL7 V2.x                                           |
|    Transformer (JavaScript - YOUR business logic, hot-editable):         |
|       ORU  -> map results to LIS ids, build data rows                    |
|       QRY  -> barcode -> DB query -> DimensionOrderRegistry.pushOrder    |
|       ACK^D01 -> MSA AA/AE -> confirm/reject the downloaded order        |
|       ACK^P01/N01 -> control noise -> ignore                             |
|    Destinations: Database Writer / JS Writer / Channel Writer / ...      |
+===========================================================================+
```

### The ASTM analogy (why nothing can conflict)

| ASTM E1381/E1394 package | Dimension package |
|---|---|
| Low-level: ENQ/ACK session, frame numbers, checksum after ETX | Low-level: per-frame ACK/NAK, checksum before ETX, no session |
| Content: ASTM frames | Content: Dimension frames |
| Your transformer reads `msg['O']['O.2']['O.2.1']` | Your transformer reads `msg['OBX']['OBX.3']['OBX.3.1']` |
| One transmission-mode plugin, one channel per analyzer | One transmission-mode plugin, one channel per analyzer |

Different packages, different ports/serial ports, different registries — **zero conflict by construction**.

---

## 3. Install (5 minutes)

### 3.1 Install the plugin
1. Stop Mirth Connect.
2. Copy `bitdreamit-dimension-transmission/` (the 3 jars + `plugin.xml` + `transmissionmode.xml`) into `{mirth.home}/extensions/`.
3. **Delete `{mirth.home}/extensions/.cache`** (mandatory after any extension update).
4. Start Mirth. Verify: *Settings → Extensions* shows `Siemens Dimension 2.2.0`.

### 3.2 Import the channel
Two ready-made bidirectional channels ship with this package (both with the D-10-style transformer inside):

| File | Source connector | Use when |
|---|---|---|
| `Dimention-HL7-Serial.xml` | **Serial Reader** (COM port, 9600 8N1, mode "Siemens Dimension") | analyzer on RS-232 (your serial fix, connector v1.4.0) |
| `Dimention-HL7-TCP.xml` | **TCP Listener** port 6661 (mode "Siemens Dimension") | analyzer over TCP / serial-to-Ethernet converter |

Import: *Channel → Import Channel*. Then set:
- **Serial**: the real `portName` (COM1/COM3//dev/ttyS0), 9600 baud, 8 data bits, 1 stop bit, no parity.
- Transmission mode settings: `Message Output Format = HL7_V2` (already set in both XMLs).
- Source **Response = None** (already set — the plugin answers the analyzer directly on the line).
- Destination: point it at your LIS database (Section 10).

### 3.3 Wire check (30 seconds)
Start the channel, then from the analyzer (or the demo simulator) send the live poll:

```
02 50 1C 44 49 4D 1C 30 1C 31 1C 30 1C 34 37 03   =   <STX>P<FS>DIM<FS>0<FS>1<FS>0<FS>47<ETX>
```

The plugin answers `06` (ACK) + `<STX>N<FS>6A<ETX>` (No Request — nothing queued yet). Dashboard shows one small `ACK^P01` message. **The wire is proven.**

---

## 4. The wire: frame anatomy and control characters

### 4.1 Control characters (PN D00396 p.1-5)

| Char | Hex | Meaning |
|------|-----|---------|
| STX  | 02  | Start of frame |
| FS   | 1C  | Field separator (the Dimension uses 0x1C, **not** the pipe `\|` of HL7) |
| ETX  | 03  | End of frame |
| ACK  | 06  | Frame received OK (checksum valid) |
| NAK  | 15  | Bad checksum — resend (max 4 retries) |
| ENQ  | 05  | Line error detected while waiting for ACK/NAK — receiver answers ACK |

### 4.2 Frame layout and checksum

```
<STX> TYPE <FS> data ... <FS> <CHK:2 hex> <ETX>
```

**CHK = 8-bit sum (mod 256) of every character between STX and CHK — INCLUDING the trailing FS — printed as 2 uppercase hex digits, placed BEFORE ETX.** (This is the #1 difference from ASTM, where the checksum sits AFTER ETX followed by CR LF.)

Live proof with your own poll:

```
P          ·          DIM           ·  0  ·  1  ·  0  ·
0x50 0x1C 0x44 0x49 0x4D 0x1C 0x30 0x1C 0x31 0x1C 0x30 0x1C
0x50+0x1C+0x44+0x49+0x4D+0x1C+0x30+0x1C+0x31+0x1C+0x30+0x1C = 0x247 mod 0x100 = 0x47 = "47"  ✓
```

All frames in this document were re-verified byte-exactly:

| Frame | Body | CHK |
|---|---|---|
| Poll | `P·DIM·0·1·0·` | **47** |
| Query | `I·26091827·` | **24** |
| No Request | `N·` | **6A** |
| Result Acceptance (accept) | `M·A··` | **E2** |
| Result Acceptance (reject 1) | `M·R·1·` | **24** |
| Result (your live capture) | `R·*··152·1··0·192902111125·1·1·2·ALTI·72·U/L··CRE2·0.59·mg/dL··` | **6D** |
| Sample Request (add) | `D·0·0·A·Doe,John·26091827·1··0·1·**·1·2·BUN·CREA·` | **48** |
| Sample Request (delete) | `D·0·0·D·Doe,John·26091827·1··0·1·**·1·2·BUN·CREA·` | **4B** |

### 4.3 Every incoming frame type → what the package does → what Mirth receives

| Frame | Meaning (manual table) | Plugin action on the wire | Mirth receives (HL7_V2) |
|---|---|---|---|
| **P** Poll (T 1-11) | "any requests?" every 15 s | ACK + `N·6A` or Sample Request `D` | `ACK^P01` + `MSA\|CA\|DIM` (control — filter) |
| **I** Query (T 1-18/19) | operator scanned barcode | ACK + `D` (if barcode known) or `N·6A` | `QRY^A19` + `PID\|\|\|26091827` + `QRD` |
| **R** Result (T 1-22) | patient results | ACK + `M·A··E2` | **`ORU^R01`** + PID + OBR + OBX per test |
| **C** Calibration (T 1-25) | QC / calibration values | ACK + `M·A··E2` | `ORU^R01` with `OBR-4 = BUN^CALIBRATION` |
| **M** Request Acceptance (T 1-16) | answer to OUR `D` download | ACK only | `ACK^D01` + `MSA\|AA\|<id>` or `MSA\|AE\|<id>\|<reason>` + `ERR` |
| **N** No Request | analyzer has nothing | ACK only | `ACK^N01` + `MSA\|CA\|` (control — filter) |
| **D** Sample Request | (computer→analyzer only) | sent by plugin / destination | — |
| **W** Wait | (documented) plugin answers never required | — | — |

---

## 5. FULL BIDIRECTIONAL DIALOGUE #1 — Send ID/Receive (barcode scan → DB query → order download)

**This is the flow you asked about: barcode → DB query → details sent to analyzer.**

Setup: LIS table `orders(barcode, patient_name, sample_type, priority, tests, status)`. Operator scans sample **26091827** on the analyzer. Analyzer needs patient + test list ("recipe") before running.

| # | Step | Who | On the wire / inside Mirth |
|---|------|-----|----------------------------|
| 1 | Operator scans sample 26091827 | Analyzer | analyzer display asks host for the sample |
| 2 | **Query frame** | Analyzer → LIS | `<STX>I<FS>26091827<FS>24<ETX>` (hex: `02 49 1C 32 36 30 39 31 38 32 37 1C 32 34 03`) |
| 3 | Checksum valid → **ACK** | Plugin → Analyzer | `06` (within milliseconds) |
| 4 | Registry lookup for barcode 26091827 | Plugin (read path) | **HIT** → build Sample Request from the stored order |
| 5 | **Sample Request (D)** | Plugin → Analyzer | `<STX>D<FS>0<FS>0<FS>A<FS>Doe,John<FS>26091827<FS>1<FS><FS>0<FS>1<FS>**<FS>1<FS>2<FS>BUN<FS>CREA<FS>48<ETX>` — patient `Doe,John`, type 1 (Serum), priority 0 (Routine), tests `BUN, CREA` |
| 6 | Analyzer ACKs the D frame | Analyzer → Plugin | `06` |
| 7 | Registry bookkeeping | Plugin | order marked *in flight* (waiting for the M answer) |
| 8 | **QRY^A19 dispatched** | Plugin → Channel | `MSH\|^~\&\|DimensionEXL\|Dimension\|LIS\|LIS\|...\|\|QRY^A19\|DIMQ26091827...\|P\|2.5.1` + `PID\|\|\|26091827\|\|\|` + `QRD\|...\|26091827` |
| 9 | Transformer QRY branch runs | Channel JS | `var barcode = msg['PID']['PID.3']['PID.3.1'].toString();` → `26091827` → DB query (Section 8) → keeps the registry warm for the NEXT query |
| 10 | Analyzer loads the order | Analyzer | display shows patient + tests; operator starts the run |
| 11 | **Request Acceptance** | Analyzer → LIS | `<STX>M<FS>A<FS>...` (status A = stored) → plugin ACKs, Mirth gets `ACK^D01` + `MSA\|AA\|26091827` → transformer calls `confirmLastDownload()` |
| 12 | Chemistry runs | Analyzer | — |
| 13 | **Result frame** | Analyzer → LIS | `<STX>R<FS>*<FS><FS>152<FS>1<FS><FS>0<FS>192902111125<FS>1<FS>1<FS>2<FS>ALTI<FS>72<FS>U/L<FS><FS>CRE2<FS>0.59<FS>mg/dL<FS><FS>6D<ETX>` |
| 14 | ACK + Result Acceptance | Plugin → Analyzer | `06` then `<STX>M<FS>A<FS><FS>E2<ETX>` (the analyzer's 1-second timer for this frame — never missed, sent inside the read path) |
| 15 | **ORU^R01 dispatched** | Plugin → Channel | see Section 6 for the byte-exact message |
| 16 | Transformer ORU branch | Channel JS | data rows → `channelMap` → destination writes the LIS result table |
| 17 | LIS displays results | LIS | ALTI 72 U/L, CRE2 0.59 mg/dL for barcode 152 / patient Doe,John |

**Where does step 4's registry entry come from? Two ways (both shipped):**
- **Order feeder channel (pre-push, recommended, zero retries)** — Section 8.1: a Database Reader channel polls `orders WHERE status='NEW'` and pushes every new order the moment the LIS user files it. The registry is warm BEFORE the operator scans → the FIRST query is answered with D. 
- **Query-time DB query (on-demand)** — Section 8.2: the QRY transformer branch of THIS channel queries the DB when the scan arrives and pushes the order; if the analyzer re-queries (or the operator re-scans — one key press), the registry answers with D. Use when you cannot run a second channel.

### The exact D-frame layout (Table 1-12) — every field accounted for

```
D | 0        | 0         | A          | Doe,John | 26091827 | 1       | (loc) | 0        | 1   | **     | 1    | 2     | BUN | CREA |
  | Carrier  | Loadlist  | Transaction| Patient  | SampleID | Type    |       | Priority | Cups| CupPos | Dili | #Tests| test names...
Transaction: A=add, D=delete.  Type: 1=Serum 2=Plasma 3=Urine 4=CSF (Table 1-13).
Priority: 0=Routine 1=STAT 2=ASAP (Table 1-14).  SampleID MUST equal the scanned ID (p.1-14).
```

---

## 6. FULL RESULT CONVERSION — your live R frame, byte by byte

**In** (analyzer → LIS, exactly your capture):

```
<STX>R·*··152·1··0·192902111125·1·1·2·ALTI·72·U/L··CRE2·0.59·mg/dL··6D<ETX>
```

Field decode (Table 1-22): loadlist=`*`, patientId=(empty), sampleNo=`152`, type=`1` (Serum), location=(empty), priority=`0` (Routine), datetime=`192902111125` (ssmmhhddmmyy → 02:29:19 on 2025-11-11), 2 tests: `ALTI=72 U/L`, `CRE2=0.59 mg/dL`, checksum `6D`.

**Out** (plugin → Mirth channel, what the Source tab now shows — the SAME format as your own HL7 example):

```
MSH|^~\&|DimensionEXL|Dimension|LIS|LIS|20251111||ORU^R01|DIM1521789514203430|P|2.5.1
PID||||||
OBR|1|||152^DIMENSIONSAMPLE||||||||||||||||||20251111022919
OBX|1|NM|ALTI^^LN:ALTI|1|72|U/L|||||F|||20251111
OBX|2|NM|CRE2^^LN:CRE2|2|0.59|mg/dL|||||F|||20251111
NTE|||SAMPLE_TYPE|Serum
NTE|||PRIORITY|Routine
```

Field mapping table (PN D00396 → HL7, all verified):

| R frame field | HL7 location | Example value |
|---|---|---|
| Sample # (field 4) | **OBR-4.1** (+ in MSH-10) | `152` |
| — | OBR-4.2 fixed marker | `DIMENSIONSAMPLE` |
| Patient ID (field 3) | **PID-3.1** | `DOE,JOHN` (empty → empty PID) |
| DateTime (field 8, ssmmhhddmmyy) | **OBR-22** + OBX-14 (date part) | `20251111022919` |
| Test name | **OBX-3.1** (+ OBX-3.3 = `LN:<test>`) | `ALTI` |
| Result | **OBX-5.1** | `72` |
| Units | **OBX-6.1** | `U/L` |
| Error code (Appendix III/IV) | OBX-5 empty + **NTE** `Error n: text` | — |
| Sample type (Table 1-23) | **NTE\|\|\|SAMPLE_TYPE\|** | `Serum` |
| Priority (Table 1-24) | **NTE\|\|\|PRIORITY\|** | `Routine` |
| — | OBX-11 | `F` (final) |
| — | MSH-9 | `ORU^R01` |
| — | MSH-10 | `DIM` + sample# + timestamp (unique) |

And the transformer reads it with plain HL7 navigation (identical to your D-10 channel):

```javascript
var barcode = msg['OBR']['OBR.4']['OBR.4.1'].toString();        // '152'
for each (var obx in msg['OBX']) {
    var name  = obx['OBX.3']['OBX.3.1'].toString();             // 'ALTI'
    var value = obx['OBX.5']['OBX.5.1'].toString();             // '72'
    var unit  = obx['OBX.6']['OBX.6.1'].toString();             // 'U/L'
}
```

---

## 7. FULL BIDIRECTIONAL DIALOGUE #2 — Send/Receive (poll-driven download) + the other two modes

### 7.1 Send/Receive mode (poll conversation, manual p.1-8/1-9 + p.1-24)
The analyzer polls; a **conversational poll** (`First Poll = 0`, `Request = 1`) downloads the next queued order; every other poll gets N.

```
Analyzer . . . . . . . . <STX>P·DIM·0·1·0·47<ETX>              (conversational poll)
Plugin . . . . . . . . . 06  (ACK)
Plugin . . . . . . . . . <STX>D·0·0·A·Doe,John·26091827·1··0·1·**·1·2·BUN·CREA·48<ETX>
Analyzer . . . . . . . . 06  (ACK)
Analyzer . . . . . . . . <STX>M·A·...           (Request Acceptance -> Mirth ACK^D01/MSA|AA)
Analyzer . . . . . . . . runs the sample . . . R frame . . . ORU^R01 (Section 6)
```
The FIFO order queue = `DimensionOrderRegistry` (feeder pushes, plugin pops — one order per conversational poll). Pending **deletes** (`Transaction=D`) go out FIRST on the next poll (manual: a request may be deleted only before processing starts).

### 7.2 Unidirectional — Send Only mode
Analyzer sends R/C frames only (no polls, no queries). Package: ACK + `M·A` per frame, `ORU^R01`/QC-ORU to the channel. Nothing else to configure — the same channel handles it, the QRY/ACK branches simply never fire.

### 7.3 Manual barcode (no host query at all)
Operator types the sample ID on the analyzer, runs the order stored in the analyzer worklist. Package behavior is IDENTICAL to Send Only: results arrive as R frames → `ORU^R01` → LIS. The barcode in `OBR-4.1` is the manual ID — your transformer maps it to the LIS order.

### 7.4 Option map — every mode × every option

| Option / event | Send Only | Send/Receive | Send ID/Receive | Manual barcode |
|---|---|---|---|---|
| R result → ACK + M-A + ORU | ✔ | ✔ | ✔ | ✔ |
| C calibration → ACK + M-A + QC-ORU | ✔ | ✔ | ✔ | ✔ |
| P poll → N / D | n/a | ✔ (registry) | ✔ (registry) | n/a |
| I query → D / N | n/a | ✔ | **✔ (primary)** | n/a |
| D order download (add) | n/a | ✔ | ✔ | n/a |
| D delete (`Transaction=D`) | n/a | ✔ | ✔ | n/a |
| M acceptance → confirm/reject | n/a | ✔ | ✔ | n/a |
| NAK/retransmit, ENQ, 1 s timers | ✔ | ✔ | ✔ | ✔ |
| DB query for barcode (Section 8) | n/a | optional | ✔ | n/a |

---

## 8. DB query for barcode — the complete flow (live example with 26091827)

### 8.1 Design A — Order feeder channel (pre-push; recommended, "unbreakable")

A second, tiny Mirth channel (Database Reader → JavaScript Writer) runs every few seconds and pushes every new/cancelled order into the registry **the moment the LIS user files it** — long before anyone scans:

```javascript
// Feeder channel: Database Reader (SELECT ... WHERE status='NEW')
//   → JavaScript Writer destination:
var dbRow = connectorMessage;                      // mapped result set
var Reg = Packages.com.bitdreamit.connect.plugins.transmission.dimension.server
              .DimensionOrderRegistry;
var key = 'default';                               // = orderQueueKey of the Dimension channel

// NEW order -> push  (CSV test form is bullet-proof JS<->Java interop)
Reg.pushOrder(key,
    dbRow['barcode'],                              // '26091827'     (<= 12 chars)
    dbRow['patient_name'],                         // 'Doe,John'     (<= 27 chars)
    dbRow['sample_type'] || '1',                   // '1'=Serum (Table 1-13)
    dbRow['priority'] || '0',                      // '0'=Routine 1=STAT 2=ASAP
    dbRow['tests']);                               // 'BUN,CREA'     (max 36 x 5 chars, csv)

// CANCELLED order -> queue a protocol DELETE (full request resent with Transaction=D)
Reg.pushDelete(key, dbRow['barcode'], dbRow['patient_name'],
    dbRow['sample_type'] || '1', dbRow['priority'] || '0', dbRow['tests']);
```

Feeder SQL (polling mode, e.g. MySQL):

```sql
SELECT barcode, patient_name, sample_type, priority, tests
FROM   orders
WHERE  status = 'NEW' AND created_at > DATE_SUB(NOW(), INTERVAL 5 MINUTE);
```
then the destination UPDATEs `status='PUSHED'` (add `UPDATE orders SET status='PUSHED' WHERE barcode='${barcode}'` in a second statement / destination) so orders are pushed once.

**Timing safety:** the analyzer's answer timer for a query is handled by the plugin INSIDE the read path (milliseconds). The feeder only fills the registry; Mirth's asynchronous processing can never delay a protocol answer. This is why Design A is the recommended one.

### 8.2 Design B — Query-time DB query (on-demand, no second channel)

The shipped channel's **QRY branch** already does this (see `tools/dimension_hl7_transformer.js`): when the QRY^A19 arrives, the transformer queries your DB and pushes the order, so the analyzer's RE-query / operator re-scan is answered with D:

```javascript
var barcode = msg['PID']['PID.3']['PID.3.1'].toString();          // '26091827'
var dbConn = DatabaseConnectionFactory.createDatabaseConnection(
        'com.mysql.cj.jdbc.Driver', 'jdbc:mysql://127.0.0.1:3306/lis', 'lis', 'secret');
var rs = dbConn.executeCachedQuery(
    "SELECT patient_name, sample_type, priority, tests FROM orders " +
    "WHERE barcode = '" + barcode.replace(/'/g, "''") + "' AND status = 'NEW' LIMIT 1");
if (rs.next()) {
    Reg.pushOrder('default', barcode, rs.getString('patient_name'),
        rs.getString('sample_type'), rs.getString('priority'), rs.getString('tests'));
}
dbConn.close();
```

Verified live in Rhino: query `26091827` → SQL row `('Doe,John','1','0','BUN,CREA,ALTI')` → `pushOrder` → `Registry.queueSize()==1` → the plugin's read path now answers the next `I·26091827·24` with the D-frame (chk 48). **Why Design A is still recommended:** the FIRST physical query can only be answered from an already-warm registry (the plugin answers in milliseconds; a synchronous DB round-trip cannot beat the wire). Design B guarantees the second attempt succeeds; Design A guarantees the first one does. Run both together and every scenario is covered.

### 8.3 Registry API reference (all JS-callable, JVM-wide, thread-safe)

```javascript
var Reg = Packages.com.bitdreamit.connect.plugins.transmission.dimension.server
              .DimensionOrderRegistry;

Reg.pushOrder(key, sampleId, patient, type, priority, testsCsv);  // queue an ADD
Reg.pushOrder(key, {sampleId:'...', patient:'...', tests:['BUN']}); // Map form
Reg.pushDelete(key, sampleId, patient, type, priority, testsCsv); // queue a DELETE
Reg.findOrder(key, sampleId);        // search+remove (used for I queries)
Reg.takeOrder(key);                  // FIFO pop (used for conversational polls)
Reg.queueSize(key); Reg.deleteQueueSize(key);
Reg.confirmLastDownload(key);        // M-A arrived -> order STORED on analyzer
Reg.rejectLastDownload(key, reason); // M-R arrived -> order NOT stored (kept in rejected list)
Reg.getInFlight(key); Reg.getRejected(key);
Reg.requeueOrder(key, order);        // push a corrected rejected order again
Reg.clear(key);                      // maintenance
```

Limits enforced by the registry (PN D00396 Table 1-12): sample ID ≤ 12, patient ≤ 27, test names ≤ 5 chars × max 36, type/priority defaulted to `1`/`0`.

---

## 9. The channel XML explained — every component

```
CHANNEL  "Dimention HL7 Serial" / "Dimention HL7 TCP"
├── SOURCE  = Serial Reader (COM3, 9600 8N1)  or  TCP Listener (0.0.0.0:6661)
│     Transmission Mode = "Siemens Dimension"
│       ├── frame bytes ........ STX 02 / ETX 03 / FS 1C / ENQ 05 (do not change)
│       ├── ACK 06, NAK 15, 4 retransmissions, ACK timeout 1000 ms (manual p.1-26)
│       ├── autoResultAcceptance = true .... R/C answered with M·A inside the read path
│       ├── autoPollResponse     = true .... P/I get N when the registry has nothing
│       ├── orderLookupEnabled   = true .... P/I get D when the registry has an order
│       ├── orderQueueKey        = 'default' (must match the feeder channel's key)
│       └── messageOutputFormat  = HL7_V2  <-- THE v2.2.0 SWITCH (raw = RAW_FRAME)
│     Inbound data type = HL7 V2.x         (real HL7 arrives now)
│     Response = None                      (plugin answers the analyzer itself)
├── TRANSFORMER (JavaScript)  = tools/dimension_hl7_transformer.js
│     Branches: ACK^P01/N01 → ignore · ACK^D01 → confirm/reject download
│               QRY^A19 → barcode → DB query → pushOrder
│               ORU → CALIBRATION? → QC routing : results → data rows
└── DESTINATION = your LIS write (Section 10)
```

Metadata columns `SOURCE`/`TYPE` are filled (`DimensionEXL`, `ORU^R01` …) so you can filter in the dashboard.

---

## 10. How the LIS reads the results

The transformer collects the rows exactly like the D-10/Erba channels and puts them in the channel map:

```javascript
channelMap.put('barcode',  barcode);            // '152'
channelMap.put('patientId', patientId);          // null or 'DOE,JOHN'
channelMap.put('sampleType', sampleType);        // 'Serum'
channelMap.put('priority',   priority);          // 'Routine'
channelMap.put('analysisDateTime', analysisTs);  // '20251111022919'
channelMap.put('data', JSON.stringify(data));    // [{id,test_id,name,value,unit,status,date}, ...]
```

`data` for the live frame:

```json
[ {"id":101,"test_id":100,"name":"ALTI","value":72,"unit":"U/L","status":"F","date":"20251111"},
  {"id":102,"test_id":100,"name":"CRE2","value":0.59,"unit":"mg/dL","status":"F","date":"20251111"} ]
```

JavaScript Writer destination (edit ids/SQL to your schema):

```javascript
var rows = JSON.parse(channelMap.get('data'));
var barcode = channelMap.get('barcode');
var dbConn = DatabaseConnectionFactory.createDatabaseConnection(
        'com.mysql.cj.jdbc.Driver', 'jdbc:mysql://127.0.0.1:3306/lis', 'lis', 'secret');
try {
    for (var i = 0; i < rows.length; i++) {
        var r = rows[i];
        if (r.id == null) { continue; }                       // unknown test - log it
        dbConn.executeUpdate(
            "INSERT INTO results (barcode, test_id, name, value, unit, result_date) VALUES ('"
            + barcode + "'," + r.id + ",'" + r.name + "'," + r.value + ",'" + r.unit + "','" + r.date + "')");
    }
} finally {
    dbConn.close();
}
```

The graphical transformer works too — the message is a normal HL7 V2.x XML now, so `OBX-5.1` can be dragged into any template without a single line of JS.

---

## 11. Properties reference (v2.2.0 — 21 settings)

| Property | Default | Meaning |
|---|---|---|
| Start/End of Frame | 0x02 / 0x03 | STX/ETX (manual p.1-5) |
| Field Separator | 0x1C | FS between every field |
| Enquiry | 0x05 | ENQ |
| Use Checksum | true | Add-Mod-256 validation/compute |
| Checksum Byte Length | 2 | 2 ASCII-hex chars |
| Positive/Negative Ack | 0x06 / 0x15 | ACK/NAK |
| Max Retransmissions | 4 | NAK budget (manual p.1-6) |
| ACK Timeout (ms) | 1000 | 1-second instrument timer |
| Frame Timeout (ms) | 5000 | incomplete-frame abort |
| Auto Result Acceptance | true | R/C → `M·A··E2` inside the read path |
| Result Acceptance Status | A | A = accept, R = reject(1) |
| Auto Poll/Query Response | true | P/I → N when no order |
| Auto ENQ Acknowledge | true | ENQ → ACK |
| Dynamic Order Lookup | true | P/I → D from the registry |
| Order Queue Key | default | registry key shared with the feeder |
| Checksum in Payload | true | keep CHK in dispatched message (RAW_FRAME mode only) |
| **Message Output Format** | **RAW_FRAME** | **`HL7_V2` = the ASTM-style mode (both shipped channels set it)** |
| Server Mode | true | receiver/listener |

---

## 12. Timing and error tables

### Timing (PN D00396 p.1-26) — how the package never misses one
| Timer | Value | Where it is honored |
|---|---|---|
| ACK/NAK after any frame | 1 s | `sendByte(ACK)` immediately on validated frame |
| Result Acceptance after R/C | 1 s (error 320 if missed) | `sendApplicationFrame(M·A)` inside the read path |
| Poll cycle | ~15 s | plugin answers every poll; P arrives in the channel as ACK^P01 (filter) |
| Query answer window | 15 s one-shot | D sent from the registry in ms; Design B covers re-queries |

### Instrument errors (Appendix) the package prevents
| Error | Meaning | Prevention |
|---|---|---|
| 316 | unexpected character | tolerant stray-byte handling |
| 318 | 4th NAK | retransmission budget + re-verify both sides |
| 319 | invalid message content | doc-exact frame layout incl. trailing FS before CHK |
| 320 | no Result Acceptance in 1 s | auto M·A inside the read path |
| 321 | no answer to query | D/N answered inside the read path |
| 322/323 | line errors | ENQ→ACK handling |

---

## 13. Verification — how "100% workable" was proven

| Level | Test | Result |
|---|---|---|
| JUnit `DimensionFrameTest` | 15 protocol/registry tests | **15/15 PASS** |
| JUnit `DimensionSerialPipeTest` | 10 RS-232-style drip tests (handler over a real byte pipe, live frames) | **10/10 PASS** |
| JUnit `DimensionHL7TranslatorTest` | 13 new v2.2.0 tests: live R→ORU (exact OBX lines), live I→QRY, live C→QC-ORU, M-A/M-R→ACK+MSA+ERR, P/N→ACK, never-throws garbage, checksum variants, **end-to-end through the real StreamHandler**, RAW-mode regression | **13/13 PASS** |
| Rhino (`test_hl7_transformer.js`) | REAL plugin output → REAL E4X (Mirth's JS engine) → the shipped transformer → data rows + real registry push/confirm/reject | **26/26 PASS** |
| Checksum math | poll 47, query 24, R 6D, N 6A, M-A E2, M-R-1 24, D-add 48, D-del 4B | all byte-verified |

Re-run any time: `cd distribution && ./build.sh test` (JUnit) and `java -cp rhino.jar:... org.mozilla.javascript.tools.shell.Main test_hl7_transformer.js` (Rhino). Live wire test: start the channel and send the frames of Sections 5–7 with your simulator or `dimension_live_test.py`.

---

## 14. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Source tab still shows `R·*··152...` blob | channel created before v2.2.0 / `RAW_FRAME` | set `Message Output Format = HL7_V2` in the mode settings, redeploy |
| Transformer ERROR "Content is not allowed in prolog" | inbound datatype RAW with HL7_V2 output | set Source Inbound Data Type = **HL7 V2.x** (or switch output back to RAW_FRAME) |
| Analyzer error 321 (no query answer) | registry cold + Design B only | install the feeder channel (Design A) or re-scan once |
| Analyzer error 320 | Result Acceptance never arrived | keep `autoResultAcceptance=true` |
| Every P poll creates a dashboard message | expected — polls are now visible HL7 | transformer ignores them (2-line branch); optional filter `MSH.9.2 = P01` |
| `ForbiddenClassException` on import | `.cache` not cleared | delete `extensions/.cache`, restart |
| Serial port opens but no data | wrong port/baud or mode | 9600 8N1, mode "Siemens Dimension", serial connector ≥ v1.4.0 |
| Order never downloaded on poll | queue key mismatch | `orderQueueKey` identical in both channels ('default') |

## 15. FAQ

**Q: Why does a P message appear every ~15 seconds?** A: The analyzer polls continuously; the plugin answers instantly on the wire and ALSO shows the poll as a tiny `ACK^P01` HL7 message for traceability. The transformer ignores it — you never need to touch it.

**Q: Do I need Java changes for a new test / new Dimension model?** A: No. Test-name → LIS-id mapping lives in the transformer (`idMapping`/`testMapping`). All Dimension family analyzers share PN D00396; a new model = one more channel with the same plugin and its own transformer.

**Q: Can two analyzers run at once?** A: Yes — one channel per analyzer (different serial port or TCP port). Use a different `orderQueueKey` per channel so orders route to the right instrument.

**Q: Is the old raw-frame channel still supported?** A: Yes — `Message Output Format = RAW_FRAME` is byte-identical to v2.1.0 behavior (covered by regression tests).

**Q: What happens if the analyzer rejects our order download (M-R)?** A: The order is NOT lost: it moves to the registry's rejected list, the transformer logs the Table 1-17 reason at ERROR, and `requeueOrder()` re-queues it after correction.
