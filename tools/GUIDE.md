# Dimension Transmission Plugin v2.0.0 — Complete Guide

**Siemens Dimension clinical chemistry system interface for Mirth Connect**
PN D00396 Rev.2 compatible · Dynamic bidirectional redesign (rev 10) · pluginVersion 2.0.0

---

## 1. What is this?

This is a Mirth Connect **transmission mode extension** that speaks the Siemens
Dimension frame protocol (`STX ... FS ... checksum ETX`) over a TCP connection —
the same job Mirth's built-in MLLP mode does for HL7, but for Dimension.

Version 2.0.0 is a **full dynamic redesign**: ALL protocol work (framing,
checksums, ACK/NAK, poll/query answers, order download) happens **inside the
Java plugin automatically**. The JavaScript transformer only reformats result
data to HL7. You never edit protocol code again.

### Key difference from the old version

| | Old (v1.x) | New (v2.0.0) |
|---|---|---|
| Order data | Hardcoded `QUERY_ORDERS` table in JS script | Dynamic `DimensionOrderRegistry` — push orders at runtime from any channel |
| Barcode scan (I frame) | JS builds D frame in script | Java finds the order and answers D automatically |
| Protocol answers | Split between JS (`responseMap`) and Java → conflicts → `IOException: Dimension frame not acknowledged after 4 attempts` | All answers in Java, inside the read path, in milliseconds |
| Timeout behavior | Blind re-send 4x then crash the connection | Retry only on real NAK; timeout = log + continue |
| JS transformer | ~700 lines of protocol + business mixed | Pure R→HL7 reformat only. Cannot throw. |

---

## 2. What is inside this ZIP?

```
bitdreamit-dimension-transmission/
├── GUIDE.md                        <-- this guide
├── Dimention-dynamic.xml           <-- ready-made Mirth channel (import this)
├── plugin.xml                      <-- extension descriptor
├── transmissionmode.xml            <-- transmission mode descriptor (required!)
├── bitdreamit-dimension-transmission-server.jar    <-- compiled, ready to use
├── bitdreamit-dimension-transmission-shared.jar
├── bitdreamit-dimension-transmission-client.jar
└── source/                         <-- FULL SOURCE CODE (buildable)
    ├── pom.xml                     <-- Maven multi-module root
    ├── README.md
    ├── plugin.xml / transmissionmode.xml
    ├── server/                     <-- Java: protocol engine + order registry
    │   ├── pom.xml
    │   ├── resources/              (plugin.xml, transmissionmode.xml)
    │   └── src/.../server/
    │       ├── DimensionStreamHandler.java        (protocol engine)
    │       ├── DimensionOrderRegistry.java        (dynamic order store)
    │       └── DimensionTransmissionModePlugin.java
    ├── shared/                     <-- Java: constants + properties
    │   ├── pom.xml
    │   └── src/.../shared/
    │       ├── DimensionConstants.java
    │       └── DimensionTransmissionModeProperties.java
    ├── client/                     <-- Java: Mirth Administrator UI panel
    │   ├── pom.xml
    │   └── src/.../client/ (4 files: settings panel, dialog, provider, plugin)
    ├── test/                       <-- JUnit tests (11 tests)
    │   ├── pom.xml
    │   └── src/.../test/DimensionFrameTest.java
    ├── distribution/
    │   ├── build.sh                <-- build + test script (recommended)
    │   ├── deploy.sh               <-- assemble extension folder / zip
    │   └── check_extension.sh      <-- diagnostic checker
    ├── tools/
    │   ├── compile-stubs/          <-- Mirth API stubs for standalone javac build
    │   ├── dimension_transformer_dynamic.js  <-- the slim JS transformer source
    │   ├── decode_dimension.py     <-- frame decoder (Python, for debugging)
    │   └── dimension_sample_frames.txt
    └── stubs/                      <-- extra Purgable stub
```

---

## 3. How it works (architecture)

```
   Siemens Dimension                       Mirth Connect
  ┌──────────────────┐            ┌────────────────────────────────────┐
  │  scan barcode    │  I frame   │  TCP Listener "Source"             │
  │  ─────────────►  │ ─────────► │  DimensionStreamHandler (JAVA):    │
  │                  │            │   • validate checksum              │
  │                  │  D frame   │   • I/P frame? → look up           │
  │  receive order   │ ◄───────── │    DimensionOrderRegistry          │
  │                  │  or N      │   • found? → send D frame          │
  │                  │            │   • not found? → send N frame      │
  │  send results    │  R frame   │   • R/C frame? → send ACK + M-A    │
  │  ─────────────►  │ ─────────► │   • bad checksum? → send NAK       │
  │                  │            ├────────────────────────────────────┤
  │                  │  HL7 ORU   │  JS Transformer (SLIM, safe):      │
  │                  │ ◄───────── │   • R frame fields → HL7 ORU^R01   │
  │                  │            │   • cannot throw, no protocol code │
  └──────────────────┘            └────────────────────────────────────┘
```

Everything on the left wire is owned by **Java**. The JS transformer receives
already-parsed frame data and only formats HL7. This is exactly how Mirth's
built-in ASTM/MLLP modes separate transport from business logic.

### Wire behavior table (all automatic, all in Java)

| Instrument sends | Host answers (immediately, inside read path) |
|---|---|
| `P` conversational poll (FirstPoll=0, Request=1) | queued order → `D` frame (FIFO), else `N` |
| `P` plain poll | `N` (`<STX>N<FS>6A<ETX>`) |
| `I <barcode>` (scan) | matching order → `D` frame (echoes scanned ID), else `N` |
| `R` (results) / `C` (header) | `<ACK>` + `<STX>M<FS>A<FS><FS>E2<ETX>` (Result Acceptance) |
| frame with bad checksum | `<NAK>` (instrument retransmits, up to 4) |
| `D` (order download to host, deletion mode) | `<ACK>` |

---

## 4. Install (step by step)

1. **Stop Mirth Connect.**
2. Copy the folder `bitdreamit-dimension-transmission/` (the 3 jars + 2 XMLs)
   into `MIRTH_HOME/extensions/`. If the folder already exists (old version),
   replace it — old jars must be removed.
3. **Start Mirth.**
4. Import the channel: Mirth Administrator → Channels → Import →
   choose `Dimention-dynamic.xml` from this zip.
5. Check the Source connector settings:
   - Transmission mode: **Dimension** (the new mode appears in the dropdown)
   - **Response: None**  ← important! The plugin owns the wire. Mirth must not write.
   - autoPollResponse = true, autoResultAcceptance = true
   - orderLookupEnabled = true, orderQueueKey = default
6. Deploy the channel. Port in the sample channel is `6661` — change to yours.

### Demo mode (test without HIS)

Start Mirth with `-Ddimension.demoOrders=true` (add to `mcserver.java.vmargs`
or service wrapper). The registry auto-seeds barcodes `012345` and `043092011`
with tests BUN/CREA/F5. Scan one of these → you get a D frame immediately.

---

## 5. Pushing orders dynamically (the replacement for the hardcoded table)

Call `DimensionOrderRegistry` from ANY channel's JavaScript — HTTP Listener,
Database Reader, ADT/ORM processor, global deploy script, anywhere.

```javascript
var Reg = Packages.com.bitdreamit.connect.plugins.transmission.dimension.server.DimensionOrderRegistry;

// ---- Simplest form: CSV string of tests -------------------------------
Reg.pushOrder('default',              // queue key = connector's orderQueueKey
              '043092011',            // sample ID (the barcode)  max 12 chars
              'DOE,JOHN',             // patient name             max 27 chars
              '1',                    // type: 1=routine, 2=stat
              '0',                    // priority: 0=normal, 1=stat
              'GLU,CREA,F5');         // tests, comma separated   max 36 tests, 5 chars each

// ---- Or push a map (keys: sampleId|sample, patient, type, priority, tests)
var m = new java.util.HashMap();
m.put('sampleId', '043092011');
m.put('patient',  'DOE,JOHN');
m.put('type',     '1');
m.put('priority', '0');
m.put('tests',    'GLU,CREA');
Reg.pushOrder('default', m);

// ---- Maintenance -------------------------------------------------------
Reg.queueSize('default');   // how many orders waiting
Reg.clear('default');       // empty the queue
```

Returns the normalized `DimensionOrder` object (or null on error). Sample IDs
are normalized: uppercase, trimmed, max 12 chars per the manual.

### Typical producer A — Database Reader channel

```javascript
// Database Reader polls:  SELECT sample_id, patient, tests FROM orders
//                         WHERE status='NEW'
// In the destination transformer:
for each (var row in msg) {
    Reg.pushOrder('default', row['sample_id'], row['patient'], '1', '0', row['tests']);
}
// then UPDATE orders SET status='SENT' WHERE ...
```

### Typical producer B — HTTP Listener channel (HIS posts JSON)

```javascript
var o = JSON.parse(connectorMessage.getRawData());
Reg.pushOrder('default', o.barcode, o.patientName, o.type || '1', o.priority || '0',
              o.testCodes.join(','));
```

**The queue key must match** the connector's `orderQueueKey` property
(default `default`). Per-channel keys let several Dimension channels have
separate queues.

---

## 6. Connector properties reference

| Property | Default | Meaning |
|---|---|---|
| `orderLookupEnabled` | true | answer P/I frames from the registry (D) or fall back to N |
| `orderQueueKey` | default | which registry queue this connector consumes |
| `autoPollResponse` | true | send N when no order is queued for a poll |
| `autoResultAcceptance` | true | send ACK + M-A after every R/C result frame |
| Source connector **Response** | **None** | Mirth must NOT write to the wire — the plugin owns the line |

These appear in the Mirth Administrator Source connector panel (the client
module adds the settings UI) and in the channel XML.

---

## 7. Building from source

Requirements: **JDK 8+** (tested with OpenJDK 17 and 21).

### Method A — build.sh (recommended, no Maven needed)

The script resolves Mirth jars from a `mirth-libs/` folder next to the project:

```
mirth-libs/
├── server/   <- mirth-server jars unpacked (donkey, mirth-core, ...)
├── client/   <- mirth-client jars unpacked (mirth-client, ui jars)
└── test/     <- junit-4.x.jar, hamcrest-core-1.3.jar
```

(If you do not have Mirth unpacked, the script can fall back to the bundled
`tools/compile-stubs/` API stubs for compilation — see script header.)

```bash
cd source/distribution
chmod +x build.sh deploy.sh
./build.sh            # builds all 3 jars into out/
./build.sh test       # build + run the 11 JUnit tests
./build.sh clean      # remove build output
../deploy.sh          # assemble ready-to-drop extension folder + zip
```

Output: `out/bitdreamit-dimension-transmission/` with the 3 jars +
plugin.xml + transmissionmode.xml — the same layout as the prebuilt jars in
this zip. Drop that folder into `MIRTH_HOME/extensions/`.

### Method B — Maven

```bash
cd source
mvn clean package           # builds shared -> server -> client -> test
mvn -pl test test           # run the JUnit suite
```

The root pom builds the 4 modules in order. You still need the Mirth jars in
the local classpath — edit the `mirth-libs` system-scope paths in the module
poms to point at your Mirth installation.

### Method C — IntelliJ IDEA

The project already contains the IntelliJ module pattern (`.iml` files with
`mirth-server`, `mirth-client`, `junit-4` library entries). Open the project,
define the 3 libraries pointing at your Mirth jars, then Build.

### Which source file does what

| File | Role |
|---|---|
| `server/DimensionStreamHandler.java` | The protocol engine: reads frames, validates checksums, answers P/I/R/C/D, NAK/retransmit policy, builds D frames from the registry |
| `server/DimensionOrderRegistry.java` | Thread-safe JVM-wide order store. `pushOrder` / `takeOrder` (FIFO) / `findOrder` (barcode search) / `clear` / `queueSize`. Demo seeding via `-Ddimension.demoOrders=true` |
| `server/DimensionTransmissionModePlugin.java` | Registers the transmission mode with Mirth server side |
| `shared/DimensionTransmissionModeProperties.java` | Connector properties (orderLookupEnabled, orderQueueKey, ...) shown in Administrator |
| `shared/DimensionConstants.java` | STX/FS/ETX/ACK/NAK bytes, frame types, version |
| `client/Dimension*Panel/Dialog/Provider` | The settings UI in Mirth Administrator |

---

## 8. Running tests

```bash
cd source/distribution && ./build.sh test
```

11 JUnit tests cover: barcode query → D download, unknown barcode → N,
conversational FIFO poll download, plain poll → N, lookup-disabled fallback,
write-sent-once-on-timeout (no crash), registry push/find/normalize variants,
demo seed idempotency, frame checksum round-trip.

For the JS transformer there is a Rhino suite (21 assertions) —
`scripts/rhino-test/test_dynamic_transformer.js` in the delivery workspace,
covering R→ORU byte-exact formatting and corrupt/truncated/garbage/empty
input safety.

---

## 9. Troubleshooting

**`IOException: Dimension frame not acknowledged after 4 attempts`**
This was the v1 defect. v2 answers everything inside the read path and never
blindly retransmits. If you still see it: your Source **Response is not None**
— set it to None. Only a future manual order-download mode (Mirth writing D
frames through `write()`) can reach that code path, and then the peer must ACK.

**Scanner sends I but gets N**
No order was in the registry for that barcode. Check `orderQueueKey` matches
the key you pushed with, and `orderLookupEnabled=true`. Debug: call
`Reg.queueSize('default')` from any channel and check the Mirth log — the
handler logs every answer.

**`ForbiddenClassException` when importing the channel**
`transmissionmode.xml` is missing from the extension folder. It whitelists the
Properties class for XStream. Copy it from this zip.

**Channel import fails / mode not in dropdown**
Extension folder name must be exactly `bitdreamit-dimension-transmission`
(matching `plugin.xml`), and Mirth restarted after copying.

**No D frame content / wrong tests**
Check normalization: sample ID max 12 chars, tests max 36 items, each test
code max 5 chars (uppercased). Longer values are clipped per the manual.

**Instrument keeps retransmitting the same frame (NAK loop)**
The host line is corrupted or the connector port is duplicated. Check only
one channel listens on that port; verify with `tools/decode_dimension.py`.

---

## 10. Versions

| Version | Date | Change |
|---|---|---|
| 2.0.0 (rev 10) | 2026-08-31 | Full dynamic redesign: DimensionOrderRegistry, in-read-path answers, timeout policy fix, slim transformer, 11/11 tests |
| 1.x (rev ≤9) | earlier | Static JS tables, responseMap-based answers (obsolete) |
