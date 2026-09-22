# Siemens Dimension - FULL BIDIRECTIONAL OPERATION GUIDE
### bitdreamit-dimension-transmission v2.1.0 - every option handled, with live data
Reference: "Dimension Clinical Chemistry System Interface Specifications", PN D00396 Rev. 2.
All frames below use real analyzer traffic (Instrument ID `DIM`) and every checksum shown was byte-verified against the documented Add-Mod-256 algorithm.

---

## 0. WHAT "100% BIDIRECTIONAL" MEANS - THE COMPLETE OPTION MAP

The analyzer has 4 host-communication modes (set on the instrument: Setup > Interface / Host):

| # | Instrument mode | Traffic | Package support |
|---|----------------|---------|-----------------|
| 1 | **Off** | nothing | nothing to do - no frames are sent |
| 2 | **Send Only** | results/calibrations only (R, C) | ACK + `M-A E2` acceptance automatic (Java read path); R -> HL7 ORU^R01; C -> QC decode |
| 3 | **Send/Receive** | polls (P) + order download (D) + results (R/C) | poll answered in read path: order from registry -> `D`, else -> `N`; deletes (Transaction=D) supported; M-A/M-R bookkeeping |
| 4 | **Send ID/Receive** | unknown-barcode queries (I) + everything in mode 3 | query answered in read path: barcode lookup in registry -> `D` with the scanned ID echoed, else -> `N` |

Every message type the protocol defines and where it is handled:

| Message | Direction | Handled by | Status in v2.1.0 |
|---------|-----------|-----------|-------------------|
| `P` Poll (5 field variants) | instrument -> computer | `DimensionStreamHandler.answerPollOrQuery` (Java, inside read path) | DONE - conversational (First=0, Request=1) downloads; initial/busy polls get `N` |
| `I` Query (normal + enhanced) | instrument -> computer | same | DONE - barcode lookup, scanned ID echoed, enhanced segment/position ignored per manual |
| `D` Sample Request ADD | computer -> instrument | registry -> `buildSampleRequestPayload(order,'A')` | DONE - doc-exact layout + trailing FS |
| `D` Sample Request DELETE | computer -> instrument | `pushDelete()` -> `buildSampleRequestPayload(order,'D')` | NEW in v2.1.0 - full-request delete, priority over adds |
| `N` No Request | computer -> instrument | `DimensionConstants.NO_REQUEST_PAYLOAD` (`N<FS>` chk `6A`) | DONE |
| `W` Wait | computer -> instrument | not auto-sent (the handler answers in milliseconds - a Wait would only delay the dialogue; supported by protocol, never needed) | DOCUMENTED |
| `M` Request Acceptance (A/R + 9 reasons) | instrument -> computer | transformer `M` branch -> `confirmLastDownload` / `rejectLastDownload` | NEW in v2.1.0 - rejections are archived, never silently lost |
| `M` Result Acceptance | computer -> instrument | `autoRespond` after R/C (`M-A E2`, or `M-R-1` when status=R) | DONE |
| `R` Result (incl. error codes, reruns) | instrument -> computer | ACK + auto `M-A E2`, then transformer -> HL7 ORU^R01 | DONE |
| `C` Calibration Result | instrument -> computer | ACK + auto `M-A E2`, then transformer QC decode to channelMap | DONE |
| `ENQ` / `NAK` / `ACK` byte level | both | `handleStrayByte` / `nakFrame` (4-retry budget) / immediate ACK | DONE |

---

## 1. SYSTEM ARCHITECTURE - WHO DOES WHAT

```
 +------------------+        RS-232 or TCP          +-------------------------------------------+
 |  Dimension EXL / | <===========================> |  MIRTH CONNECT                            |
 |  RxL / Xpand     |   (serial via v1.4.0 serial   |                                           |
 |  (PN D00396)     |    connector, or TCP via      |  [Source connector]                       |
 |                  |    Moxa/NPort raw socket)     |    Transmission mode: Siemens Dimension   |
 +------------------+                               |    DimensionStreamHandler (Java):         |
                                                    |      - STX/FS/CHK/ETX framing             |
                                                    |      - ACK in <1 ms (1 s timer)           |
                                                    |      - NAK x4 budget, ENQ -> ACK          |
                                                    |      - P/I answered HERE (read path)      |
                                                    |      - R/C accepted HERE (M-A E2)         |
                                                    |                                           |
                                                    |  [Source transformer - JavaScript]        |
                                                    |      - R -> HL7 ORU^R01 -> LIS            |
                                                    |      - C -> QC/calibration fields         |
                                                    |      - M -> confirm/reject bookkeeping    |
                                                    |      - P/I/N -> logged, filtered          |
                                                    |                                           |
                                                    |  [DimensionOrderRegistry - JVM-wide]      |
                                                    |      add queue / delete queue /           |
                                                    |      in-flight / rejected archive         |
                                                    |         ^                                 |
                                                    |         | pushOrder / pushDelete          |
                                                    |  [Dimension Order Feeder channel]         |
                                                    |    Database Reader, poll 30-60 s          |
                                                    |    SELECT ... FROM lis_dimension_orders   |
 +------------------+                               |                                           |
 |  LIS database    | <---------------------------- |  [LIS destinations]                       |
 |  orders + results|        HL7 ORU / SQL          +-------------------------------------------+
 +------------------+
```

The one design rule that makes this work: **the Java handler answers the wire, the JavaScript never touches protocol frames.** The instrument allows 1 second for ACK/NAK and 15 seconds for a query answer; Mirth's asynchronous channel processing cannot guarantee that, but the read path (microseconds after frame receipt) always can.

---

## 2. FRAME FORMAT AND CHECKSUM (live bytes)

```
 <STX> TYPE <FS> field <FS> field ... <FS> CHK <ETX>
   STX = 0x02, ETX = 0x03, FS = 0x1C
   CHK = 2 uppercase hex chars of the 8-bit sum (mod 256) of EVERY character
         between STX and CHK - including all FS bytes and the trailing FS.
```

Your live poll proves the algorithm (verified byte-by-byte):

```
 P <FS> D I M <FS> 0 <FS> 1 <FS> 0 <FS>        -> sum = 0x47 -> "47"
```

Live analyzer frames from your site, all checksums verified:

| Frame | Content | CHK |
|-------|---------|-----|
| Poll (conversational, ready) | `P DIM 0 1 0` | `47` |
| Query (barcode 26091827) | `I 26091827` | `24` |
| Result (sample 152, ALTI + CRE2) | `R * .. 152 .. ALTI 72 U/L .. CRE2 0.59 mg/dL ..` | `6D` |
| Calibration (BUN lot GA6057) | `C BUN mg/dL GA6057 ...` | `2D` |
| No Request answer | `N` | `6A` |
| Result Acceptance (accept) | `M A ` (empty field) | `E2` |
| Result Acceptance (reject r1) | `M R 1` | `24` |

`xx` in the manual examples = the 2-char checksum placeholder, always the last 2 characters before ETX.

---

## 3. THE DATABASE QUERY FLOW - BARCODE TO ORDER (Send ID/Receive)

This is the flow you asked for: operator puts a tube on the analyzer -> scanner reads barcode `26091827` -> the order details travel from your LIS **database** to the analyzer.

### 3.1 One-time setup: the orders table

```sql
CREATE TABLE lis_dimension_orders (
    order_id     INT AUTO_INCREMENT PRIMARY KEY,
    barcode      VARCHAR(12)  NOT NULL,   -- Sample ID, max 12 chars (manual limit)
    patient_name VARCHAR(27)  DEFAULT '', -- max 27 chars
    sample_type  CHAR(1)      DEFAULT '1',-- 1 serum 2 plasma 3 urine 4 CSF W whole blood
    priority     CHAR(1)      DEFAULT '0',-- 0 routine 1 STAT 2 ASAP 3 QC 4 XQC
    tests        VARCHAR(255) NOT NULL,   -- 'GLU,CREA' UPPERCASE Appendix-II mnemonics
    status       VARCHAR(10)  DEFAULT 'NEW',
                             -- NEW -> QUEUED -> (DONE | REJECTED<n> | DELETED)
    updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

Your LIS application writes one row when an order is created:

```sql
INSERT INTO lis_dimension_orders (barcode, patient_name, sample_type, priority, tests)
VALUES ('26091827', 'DOE,JOHN', '1', '1', 'GLU,CREA');      -- STAT serum GLU+CREA
```

### 3.2 The Feeder channel (runs every 30-60 s)

Channel `Dimension Order Feeder`: source = **Database Reader** (driver, JDBC URL, credentials, poll interval), source transformer = **`tools/dimension_order_feeder.js`** shipped in the package. Its job in one sentence: `SELECT` every `NEW`/`CANCELLED` row -> push into the registry -> mark `QUEUED`/`DELETED`.

```javascript
// essence of the feeder (full file: tools/dimension_order_feeder.js)
var Reg = Packages.com.bitdreamit.connect.plugins.transmission.dimension.server
              .DimensionOrderRegistry;

// status NEW      -> order download
Reg.pushOrder('default', '26091827', 'DOE,JOHN', '1', '1', 'GLU,CREA');
// UPDATE lis_dimension_orders SET status='QUEUED' WHERE order_id=...

// status CANCELLED -> full-request DELETE download (Transaction = D)
Reg.pushDelete('default', '26091827', 'DOE,JOHN', '1', '1', 'GLU,CREA');
// UPDATE lis_dimension_orders SET status='DELETED' WHERE order_id=...
```

Why pre-push instead of querying the DB when the barcode arrives? The manual gives only **one 15-second timer** for the whole query dialogue and **no retransmission** of the query - but the ACK must still leave in 1 s. Pushing the order list into the JVM registry means the answer is already in memory when the scanner beeps: the `D` frame leaves the wire in single-digit milliseconds, and a DB outage can never break the analyzer dialogue. This is exactly how Mirth's built-in modes treat pre-loaded state, and it is the only design that makes the timers unbreakable.

### 3.3 The live dialogue, step by step (real bytes)

```
Dimension EXL                                        Mirth (plugin + feeder + LIS DB)
     |                                                        |
     | -- <STX>P<FS>DIM<FS>0<FS>1<FS>0<FS>47<ETX> ----------> | (1) 15 s idle poll (Request=1:
     |                                                        |     "ready, may I have work?")
     | <-- ACK (0x06) --------------------------------------- | (2) DLC ack, <1 ms - Java
     |                                                        | (3) registry: queue empty for
     |                                                        |     'default' -> nothing to send
     | <-- <STX>N<FS>6A<ETX> -------------------------------- | (4) No Request (chk 6A)
     | -- ACK (0x06) ---------------------------------------> | (5)
     |                                                        |
     |                                     ... operator loads tube, scanner reads 26091827 ...
     |                                                        |
     | -- <STX>I<FS>26091827<FS>24<ETX> --------------------> | (6) Query: "who is 26091827?"
     | <-- ACK (0x06) --------------------------------------- | (7) DLC ack, <1 ms
     |                                                        | (8) registry.findOrder: HIT -
     |                                                        |     order (DOE,JOHN / serum /
     |                                                        |     STAT / GLU+CREA) was pushed
     |                                                        |     by the feeder at step 0
     | <-- <STX>D<FS>0<FS>0<FS>A<FS>DOE,JOHN<FS>26091827<FS>1<FS><FS>1<FS>1<FS>**<FS>1<FS>2<FS>GLU<FS>CREA<FS>4B<ETX>
     |                                                        | (9) Sample Request, chk 4B;
     |                                                        |     Sample # = 26091827 EXACTLY
     |                                                        |     (manual p.1-14: must match
     |                                                        |     the queried ID or reject)
     | -- ACK (0x06) ---------------------------------------> | (10) instrument ACKed our D
     | -- <STX>M<FS>A<FS><FS>A<FS>1<FS>42<FS>0E<ETX> -------- | (11) Request Acceptance:
     |                                                        |     A=stored, carrier A, cup 42
     | <-- ACK (0x06) --------------------------------------- | (12) we ACK their M
     |                                                        | (13) transformer M branch ->
     |                                                        |     confirmLastDownload('default')
     |                                                        |     -> order state ACCEPTED
     |                                                        |
     |                       ... instrument aspirates, runs GLU + CREA ...
     |                                                        |
     | -- <STX>R<FS>0<FS>...<FS>26091827...GLU=4.8 mmol/L...<FS>xx<ETX> --> | (14) Result frame
     | <-- ACK (0x06) --------------------------------------- | (15)
     | <-- <STX>M<FS>A<FS><FS>E2<ETX> ----------------------- | (16) Result Acceptance (E2)
     | -- ACK (0x06) ---------------------------------------> | (17)
     |                                                        | (18) transformer -> HL7 ORU^R01
     |                                                        |     -> LIS results table
```

Every reply above (ACK, `N 6A`, `D ... 4B`, `M-A E2`) is byte-exact with the documentation - the analyzer accepts them all; nothing is rejected, no error 316-323 is raised.

### 3.4 Unknown barcode -> `N` -> manual operator entry

If the scanned ID is NOT in the registry (no order in the DB):

```
 I <FS> 99999999 <FS> chk   ->   ACK + <STX>N<FS>6A<ETX>
```

The instrument flags the sample unidentified and the operator selects the tests on the analyzer keypad (or cancels). The result still arrives later as an `R` frame with that sample number and flows to the LIS as HL7 - nothing is lost. This is also the complete **manual barcode entry** workflow: order optional, result delivery always works.

---

## 4. SEND/RECEIVE MODE - POLL-DRIVEN DOWNLOAD (no barcode)

In Send/Receive the instrument does not query per tube; it asks the LIS for work on every conversational poll (First Poll=0, Request=1 - your `P DIM 0 1 0 47`). The registry works as a FIFO work list:

```
 P DIM 0 1 0 47  ->  ACK + D(order 1)      <- FIFO head
 P DIM 0 1 0 47  ->  ACK + D(order 2)
 P DIM 0 1 0 47  ->  ACK + N 6A            <- queue empty again
```

Poll variants and the exact answers:

| Poll fields | Meaning | Answer |
|-------------|---------|--------|
| `P DIM 1 1 0` | FIRST poll after power-up/recovery, ready | `N` (recovery - do not assume state; documented default) |
| `P DIM 1 0 0` | first poll, instrument busy | `N` |
| `P DIM 0 1 0` | conversational, ready | `D` if a delete or order is queued, else `N` |
| `P DIM 0 0 0` | conversational, busy (running sample) | `N` |

Pending **deletes have priority over adds** in the same poll cycle - the manual allows deleting a request only BEFORE processing starts, so it must not wait behind new orders.

The `<1 s` re-poll: after a download the instrument polls again within a second. The FIFO pop makes each poll return the NEXT order or `N` - no duplicates, no re-sends.

---

## 5. ORDER DELETE (Transaction = D) - cancelling a downloaded order

A `D` frame with Transaction `A` stores a request in the instrument's 500-sample buffer; it is removed only when (a) all results transmitted (auto-delete FIFO), or (b) the LIS sends the SAME request again with Transaction `D` - allowed only BEFORE processing starts.

The v2.1.0 delete flow:

```javascript
// 1. LIS cancels the order:
//    UPDATE lis_dimension_orders SET status='CANCELLED' WHERE barcode='012345'
// 2. Feeder pushes the full original request as a delete:
Reg.pushDelete('default', '012345', 'Doe,John', '2', '0', 'BUN,CREA,F5');
// 3. Next conversational poll sends (priority over adds):
```

```
 <STX>D<FS>0<FS>0<FS>D<FS>Doe,John<FS>012345<FS>2<FS><FS>0<FS>1<FS>**<FS>1<FS>2<FS>BUN<FS>CREA<FS>F5<FS>chk<ETX>
          ^^^ Transaction = D  (every other field identical to the ADD)
```

The delete frame is a full request frame (manual: "resend the full request with D in the Transaction field") - Sample # and the test list are mandatory, and the `#Tests` count must still match the test names or the instrument rejects it with reason 5.

---

## 6. REQUEST ACCEPTANCE [M] - the order was stored or refused

After every `D` download the instrument answers with exactly one `M` frame in the same dialogue (fields: `M | Status | Reason | Carrier | #Cups | Position`):

```
accept:  M <FS> A <FS> <FS> A <FS> 1 <FS> 42 <FS> chk 0E   -> carrier A, cup 42
reject:  M <FS> R <FS> 5 <FS> 0 <FS> 1 <FS> 0 <FS> chk     -> refused, reason 5
```

v2.1.0 bookkeeping (closed loop - no order can silently disappear):

| M frame | Transformer action | Registry effect | What you see |
|---------|-------------------|-----------------|--------------|
| `M-A` | `confirmLastDownload(queueKey)` | in-flight cleared, state ACCEPTED | log "download ACCEPTED by instrument"; `channelMap.downloadConfirmed = <sampleId>` |
| `M-R` | `rejectLastDownload(queueKey, reason)` | order moved to the rejected archive | ERROR log with the decoded reason; `channelMap.downloadRejected`; `Reg.getRejected(key)` lists it |
| neither (restart etc.) | - | in-flight stays PENDING, harmless | `Reg.getInFlight(key)` shows it for diagnostics |

Rejection reasons (Table 1-17) and what to do:

| Reason | Meaning | Action |
|--------|---------|--------|
| 1 | request in process | too late to change - let it run |
| 2 | result no longer available | - |
| 3 | sample carrier in use | retry later |
| 4 | no memory | too many buffered requests (500 max) - retry on next poll |
| **5** | **error in test request** | **typical: bad test mnemonic, `#Tests` mismatch, wrong sample type - fix the row, re-push (`pushOrder`/`requeueOrder`)** |
| 7 | carrier full | retry later |
| 8 | no known carriers | instrument state - retry |
| 9 | incorrect fluid type | sample type code does not match the rack/fluid - fix and re-push |

To re-send a corrected order after fixing it in the LIS:

```javascript
Packages.com.bitdreamit.connect.plugins.transmission.dimension.server
    .DimensionOrderRegistry.requeueOrder('default', rejectedOrder);
// or simply pushOrder again with corrected tests - the feeder does this
// automatically once you set the row status back to 'NEW'.
```


---

## 7. RESULT UPLOAD (R) - live frame to HL7, field by field

Your live result frame:

```
<STX>R<FS>*<FS><FS>152<FS>1<FS><FS>0<FS>192902111125<FS>1<FS>1<FS>2<FS>ALTI<FS>72<FS>U/L<FS><FS>CRE2<FS>0.59<FS>mg/dL<FS><FS>6D<ETX>
```

| # | Field | Value | Goes to |
|---|-------|-------|---------|
| 1 | Loadlist ID | `*` | (ignored, legacy) |
| 2 | Patient ID | (empty) | PID-3 (empty - barcode-only run) |
| 3 | Sample # | `152` | OBR-4 `152^DIMENSIONSAMPLE`, MSH-10 |
| 4 | Sample type | `1` = Serum | channelMap `sampleType` |
| 5 | Location | (empty) | channelMap `location` |
| 6 | Priority | `0` = Routine | channelMap `priority` |
| 7 | Date/Time | `192902111125` = ss mm hh dd mm yy -> 2025-11-11 02:29:19 | OBR-22 / OBX-14 |
| 8 | # Cups | `1` | (fixed) |
| 9 | Dilution | `1` | channelMap `dilution` |
| 10 | # Tests | `2` | loop count |
| 11+ | test groups: `ALTI / 72 / U/L / (err)` then `CRE2 / 0.59 / mg/dL / (err)` | | one OBX each |

The wire answers (both automatic, both in the Java read path):

```
ACK (0x06)                                  <- DLC, immediately
<STX>M<FS>A<FS><FS>E2<ETX>                  <- Result Acceptance, chk E2
```

HL7 v2.5.1 ORU^R01 produced by the transformer (your real output):

```
MSH|^~\&|DimensionEXL|Dimension|LIS|LIS|20251111||ORU^R01|DIM1521789366370209|P|2.5.1
PID||||||
OBR|1|||152^DIMENSIONSAMPLE||||||||||||||||||20251111022919
OBX|1|NM|ALTI^^LN:ALTI|1|72|U/L|||||F|||20251111
OBX|2|NM|CRE2^^LN:CRE2|2|0.59|mg/dL|||||F|||20251111
```

How the LIS reads results: the ORU^R01 arrives on your destination (TCP MLLP to the LIS, or a Database Writer into `lis_results`) - each OBX becomes one result row keyed by the barcode in OBR-4. Duplicate sample numbers ARE possible (Priority Panel sends a partial then a final panel) - the LIS must upsert by (barcode, test), which this mapping supports.

Error codes inside a test group (e.g. `...CRE2<FS><FS>mg/dL<FS>9...`) map to an NTE note (`9 = No Reagent`); codes 6-19 that suppress the value leave OBX-5 empty with the NTE explaining why.

## 8. CALIBRATION RESULT (C) - your live BUN frame

```
<STX>C<FS>BUN<FS>mg/dL<FS>GA6057<FS>1<FS>2<FS>01.09.26<FS>184103111125<FS>1<FS>0<FS>5<FS>-1<FS>-2<FS><FS><FS><FS>3<FS>0<FS>3<FS>-0<FS>-1<FS>-1<FS>14<FS>3<FS>-9<FS>-9<FS>-9<FS>46<FS>3<FS>-27<FS>-27<FS>-27<FS>2D<ETX>
```

Fields (Table 1-25): test `BUN`, units `mg/dL`, reagent lot `GA6057`, calibrator `1`, calibrator lot `2`, calibration expiry `01.09.26`, datetime `184103111125`, operator `1`, then slope/intercept/coefficients and cuvette bottle values.

Handled: ACK + `M-A E2` (automatic - the C frame is acceptance-tracked exactly like R), and the header is decoded into channelMap (`calibrationTest`, `calibrationLot`, `calibrationCalibrator`, `calibrationOperator`, `calibrationDateTime`, `calibrationRaw`) so you can route it to a QC/calibration destination. Calibration frames are NOT patient results and are correctly NOT converted to ORU.

## 9. CHANNEL HANDLING - what happens to every frame Mirth receives

| Incoming frame | Java handler (read path) | Transformer | Destination |
|----------------|--------------------------|-------------|-------------|
| `P` poll | ACK + `D` (queue hit) or `N 6A` | logged: `pollInstrumentId/FirstPoll/Request`; filtered | none |
| `I` query | ACK + `D` (barcode hit, ID echoed) or `N 6A` | logged: `queriedSampleId`; enhanced seg/pos logged; filtered | none |
| `D`/`N` control | (we never receive these; ACKed if they appear) | `controlMessage` kept; filtered | none |
| `M` acceptance | ACK (DLC) | status decoded; `confirmLastDownload` / `rejectLastDownload`; filtered | none |
| `R` result | ACK + `M-A E2` | checksum logged; full ORU^R01 built; `sampleNumber`, `sampleType`, `isQC`, `testCount` in channelMap | LIS (HL7 or SQL) |
| `C` calibration | ACK + `M-A E2` | header decoded to `calibration*` maps; raw kept | QC destination (optional) |

Filters you should set on destinations: HL7 destination filter `dimensionMessageType === 'R'`; QC destination filter `dimensionMessageType === 'C'`. Everything else is control traffic - it has already been answered on the wire by the time the transformer runs.

## 10. EVERY SETTING OF THE TRANSMISSION MODE (and when to change it)

| Property (channel editor) | Default | Meaning | Touch when |
|---------------------------|---------|---------|------------|
| Start/End of Frame | 0x02 / 0x03 | STX/ETX | never (protocol fixed) |
| Field Separator | 0x1C | FS | never |
| Use Checksum | on | Add-Mod-256 validate/compute | never |
| Checksum Byte Length | 2 | hex chars | never |
| ACK/NAK bytes | 0x06 / 0x15 | DLC handshake | never |
| Max Retransmissions | 4 | NAK budget (error 318 at 4th) | never |
| ACK Timeout (ms) | 1000 | 1 s protocol timer | never |
| Frame Timeout (ms) | 5000 | incomplete-frame abort | noisy lines only |
| **Auto Result Acceptance** | on | R/C -> `M-A E2` | off ONLY if your LIS must reject specific results (`resultAcceptanceStatus=R` sends `M-R-1`) |
| Result Acceptance Status | A | A=accept / R=reject | A for production |
| **Auto Poll/Query Response** | on | P/I -> `N` when nothing queued | keep ON - it is the safe fallback; the registry path answers first anyway |
| Auto ENQ Acknowledge | on | stray ENQ -> ACK | on (error 323 prevention) |
| **Dynamic Order Lookup** | on | P/I answered from the registry | off = pure unidirectional (Send Only) |
| **Order Queue Key** | default | registry queue name | multi-analyzer setups: one key per instrument, feeder pushes per key |
| **Checksum in Payload** | on | transformer re-verifies CHK | on (recommended) |
| Server Mode | true | listener/receiver | true for source connectors |

## 11. THE THREE DEPLOYMENT MODES - NORMAL USER SETTINGS

**A. Bidirectional (Send/Receive + Send ID/Receive) - the full workflow**
1. Instrument host mode: `Send/Receive` (poll downloads) or `Send ID/Receive` (+ barcode queries; recommended).
2. Deploy 2 channels: main `Dimention` (source TCP Listener or Serial with mode `Siemens Dimension`; transformer as shipped; HL7/SQL destination for R) + `Dimension Order Feeder` (Database Reader, 30-60 s).
3. Main channel source settings: `Dynamic Order Lookup = on`, `Order Queue Key = default`, `Auto Poll/Query Response = on`, `Auto Result Acceptance = on`.
4. LIS writes orders to `lis_dimension_orders`; feeder pushes them; results return as ORU^R01.

**B. Unidirectional (Send Only) - results only**
1. Instrument host mode: `Send Only`.
2. One channel only - no feeder needed. `Dynamic Order Lookup` can stay on (no P/I will ever arrive in Send Only mode).
3. Results flow exactly as in section 7. Every R/C is ACKed and accepted automatically.

**C. Manual barcode entry (no LIS orders)**
1. Instrument host mode: `Send ID/Receive` or `Send Only`.
2. No feeder. Operator enters/scan-selects tests on the analyzer keypad; for unknown barcodes the query gets `N 6A` and the operator proceeds manually.
3. Results still arrive as R frames and flow to the LIS - the barcode in OBR-4 lets your LIS match them to the patient later.

## 12. TIMING RULES (all guaranteed by the read-path design)

| Timer | Value | Who guarantees it |
|-------|-------|-------------------|
| ACK/NAK after any frame | 1 s | Java handler ACKs in <1 ms |
| Result Acceptance after R/C | 1 s (error 320 if missing) | sent in the same read-path pass |
| Poll cycle idle | 15 s | instrument |
| Re-poll after a download | < 1 s | FIFO pop - next poll gets the next order or N |
| Query answer window | 15 s, ONE-SHOT (never retransmitted) | registry answer in <10 ms |
| Rejected result retry | 15 s x 50 (~12 min) then error 322 + operator Reset | only if you set `resultAcceptanceStatus=R` |
| NAK budget | 4 retransmits then error 318 | `nakFrame()` |

## 13. ERROR CODES 316-323 - CAUSE AND FIX

| Analyzer error | Meaning | Root cause | Fix with this package |
|----------------|---------|-----------|----------------------|
| 316 | cannot communicate with host | cable/port dead | check COM wiring / NPort session; Mirth logs show connect state |
| 317 | host port receiver error | line noise | check baud/parity (EXL: fixed 9600 8N1), shielding |
| 318 | 4th NAK from host | OUR frames corrupt OR instrument sent 4 bad frames | with v2.1.0 our frames are doc-exact; if the instrument's frames arrive corrupt, check encoding (must be 7/8-bit clean, ISO-8859-1/US-ASCII) |
| 319 | invalid message from host | frame checksum OK but application layout wrong | v2.1.0 sends only documented layouts (trailing FS included, verified byte-exact) |
| 320 | no acceptance message from host | we never sent M-A after R | Auto Result Acceptance = on |
| 321 | no ACK/NAK from host | connector did not answer at all | this was the v1.3.0 serial bug - fixed by the built-in DimensionProvider in serial v1.4.0 |
| 322 | host rejected result 50x | `resultAcceptanceStatus=R` left on | set status back to A |
| 323 | 4th ENQ from host | ENQ storm unanswered | Auto ENQ Acknowledge = on |

## 14. VERIFY IT YOURSELF (60-second self-test)

```bash
# inside the package: tools/decode_dimension.py verifies any captured frame
python3 tools/decode_dimension.py < paste-your-frame.txt

# full JUnit suite (25 tests, incl. delete download + M bookkeeping):
bash scripts/build_and_test_dimension_plugin.sh    # -> OK (25 tests)

# smoke test without an analyzer:
#   1. deploy the channel, start with -Ddimension.demoOrders=true
#   2. connect with: python3 scripts/mock_dimension_server.py (acts as the EXL)
#   3. watch Mirth ACK + answer D/N and print the mock's R frame as HL7
```

Registry quick-reference (any JavaScript in Mirth):

```javascript
var Reg = Packages.com.bitdreamit.connect.plugins.transmission.dimension.server.DimensionOrderRegistry;
Reg.pushOrder('default', '26091827', 'DOE,JOHN', '1', '1', 'GLU,CREA'); // add
Reg.pushDelete('default', '012345', 'Doe,John', '2', '0', 'BUN,CREA,F5'); // cancel
Reg.queueSize('default');  Reg.deleteQueueSize('default');
Reg.getInFlight('default');  Reg.getRejected('default');
Reg.confirmLastDownload('default');  Reg.rejectLastDownload('default', '5');
Reg.requeueOrder('default', order);  Reg.clear('default');
```

## 15. VERSION HISTORY

| Version | Change |
|---------|--------|
| 2.0.0 | dynamic redesign: P/I answered in the Java read path from the registry |
| 2.0.1 | serial-transport verification, tolerant parse (split glued checksums) |
| 2.0.2 | doc-exact outbound frames (trailing FS before CHK, byte-verified) |
| **2.1.0** | **every option handled: full-request DELETE downloads (Transaction=D, priority over adds), M-A/M-R download bookkeeping (confirm/reject archive - no order silently lost), order-feeder DB-query implementation, this guide; 25/25 tests** |
