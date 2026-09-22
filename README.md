# bitdreamit-dimension-transmission

**Siemens Dimension native host-interface transmission mode for Mirth Connect 4.5+**

A Mirth Connect transmission-mode plugin that speaks the **native Siemens
Dimension protocol** (Dimension EXL / RxL / Xpand Clinical Chemistry Systems),
modeled after the `bitdreamit-astm-e1381-transmission` plugin architecture.

> **NEW in v2.2.0 — the "ASTM pattern" (Message Output Format = HL7_V2):**
> the plugin now converts EVERY incoming frame into a standard HL7 v2.x
> message *before* dispatch, so the channel uses the normal HL7 V2.x data
> type and the transformer reads `msg['OBX']['OBX.3']['OBX.3.1']` exactly
> like an ASTM/HL7 channel — no more raw-frame parsing in JavaScript:
>
> | Frame | Mirth receives |
> |---|---|
> | R Result | `ORU^R01` — MSH + PID + OBR + one OBX per test |
> | I Query (barcode) | `QRY^A19` — barcode in PID-3.1 / QRD-8 |
> | C Calibration | `ORU^R01` — `OBR-4 = <test>^CALIBRATION` |
> | M Request Acceptance | `ACK^D01` — `MSA\|AA` stored / `MSA\|AE\|reason` rejected |
> | P Poll / N No Request | `ACK^P01` / `ACK^N01` control messages (filter) |
>
> Full documentation with live-data dialogues, the DB-query-for-barcode
> flows and both ready-made channel XMLs:
> **[FULL-BIDIRECTIONAL-DOCUMENTATION.md](FULL-BIDIRECTIONAL-DOCUMENTATION.md)**,
> `tools/dimension_hl7_transformer.js`,
> `tools/Dimention-HL7-Serial.xml`, `tools/Dimention-HL7-TCP.xml`.
> The previous raw-frame behavior remains available (`RAW_FRAME`) and is
> covered by regression tests.

It appears in the **Transmission Mode** dropdown of:

* the standard **TCP Listener / TCP Sender** connectors, and
* the **BitDreamIT Serial Reader / Serial Writer** connectors (v1.3.0+,
  which resolve Mirth transmission-mode providers through the same
  ExtensionController registry and drive them via `getStreamHandler()`).

> **Protocol reference:** *Dimension® Clinical Chemistry System Interface
> Specifications*, Siemens Healthcare Diagnostics, 2011/08 Rev. 2, PN D00396.

---

## 1. Why not ASTM E1381?

The Dimension native protocol is **not** ASTM E1381/1394. The frames look
similar but the data-link layer is completely different:

| Aspect | ASTM E1381 | Siemens Dimension (this plugin) |
|---|---|---|
| Frame | `<STX> FN text <ETX/ETB> CHK <CR><LF>` | `<STX> TYPE <FS> data <FS> CHK <ETX>` |
| Checksum position | **after** ETX, followed by CR LF | **inside** the frame, immediately before ETX |
| Checksum scope | frame number + text + ETX/ETB | every character between STX and CHK (FS included) |
| Session | ENQ → ACK, EOT at the end, per transfer | **none** — continuous, every frame ACKed individually |
| Frame numbers | 0–7 cycling, ETB for intermediate frames | **none**, single frame per message |
| Retry | NAK → resend frame | NAK → resend frame, max **4** times |
| Timer | 15 s establishment, 15 s response | **1 second** ACK/NAK timer |
| Field separator | `\F\` (ASTM record fields) | `FS = 0x1C` between **every** data field |

Feeding a Dimension to an ASTM mode produces NAK storms and lost results —
this plugin implements the Dimension DLC layer exactly.

### Frame anatomy (verified)

```
<STX> R <FS> 0 <FS> <FS> 52 <FS> 1 <FS> <FS> 0 <FS> 444410111125 <FS> 1 <FS> 1 <FS> 2
      <FS> GLUC <FS> 276 <FS> mg/dL <FS> <FS> CRE <FS> 22.05 <FS> mg/dL <FS> <FS> 33 <ETX>
      │   │  │     │    │    │    │  │      │              │    │    │
      │   │  │     │    │    │    │  │      │              └────┴────┴── #Cups=1, Dilution=1, #Tests=2
      │   │  │     │    │    │    │  │      └── Date/Time ssmmhhddmmyy = 10:44:44 on 11-Nov-25
      │   │  │     │    │    │    │  └── Priority (0=Routine … 1=STAT)
      │   │  │     │    │    │    └── Location (may be empty)
      │   │  │     │    └── Sample Type (1=Serum … W=Whole Blood)
      │   │  │     └── Sample Number (barcode)
      │   │  └── Patient ID (may be empty)
      │   └── Loadlist ID (always 0)
      └── Message type: R = Result
CHK "33" = 8-bit sum (mod 256) of all characters between STX and CHK,
printed as two uppercase ASCII-hex characters.
```

### Pre-verified checksum reference

| Frame | Payload | CHK | Verified against |
|---|---|---|---|
| Result Acceptance (Accept) | `M<FS>A<FS><FS>` | `E2` | Manual example, p. 1-16 |
| Result Acceptance (Reject) | `M<FS>R<FS>1<FS>` | `24` | Manual example, p. 1-16 |
| No Request | `N<FS>` | `6A` | Manual example, p. 1-12 |
| Real-world Poll (Instrument ID `DIM`) | `P<FS>DIM<FS>1<FS>1<FS>0<FS>` | `48` | Captured from a Dimension EXL with LM |

The last one is the actual poll captured with a terminal emulator from a
Dimension EXL with LM (`FirstPoll=1, Request=1`) — the unit tests assert
all four values, so a regression can never ship silently.

---

## 2. What the plugin does

### Receiver (Serial Reader source / TCP Listener)

1. Waits for `<STX>`, accumulates until `<ETX>` (noise and stray control
   bytes between frames are discarded; ACK/NAK from the instrument are
   consumed as the acknowledgements of our own frames).
2. Validates the Add-Mod-256 checksum.
   * Valid → sends **ACK (0x06)** immediately (the instrument's 1-second
     timer), then dispatches the payload to the channel.
   * Invalid → sends **NAK (0x15)**; the instrument retransmits; after
     `maxRetransmissions` (default 4, protocol limit) the read cycle aborts
     and is retried by the connector.
3. Answers a stray **ENQ (0x05)** with ACK (optional, on by default).
4. **Application-level auto responses** (optional, on by default — required
   in Send/Receive and Send ID/Receive instrument modes):
   * `R` (Result) / `C` (Calibration Result) received → sends Result
     Acceptance `<STX>M<FS>A<FS><FS>E2<ETX>` right after the DLC ACK.
     Without it the instrument waits 1 s, retries, and finally raises host
     error **320** ("Did not receive acceptance message from DMW/Host").
   * `P` (Poll) / `I` (Query) received → sends No Request
     `<STX>N<FS>6A<ETX>` ("nothing to download"). Turn this off when your
     channel downloads orders (Sample Request `D` messages) to the analyzer.

### Sender (Serial Writer destination / TCP Sender)

Frames the payload supplied by the channel (without STX/ETX/checksum),
computes and appends the checksum, waits for the instrument's ACK within
`ackTimeoutMs` (1000 ms per protocol) and retransmits on NAK/timeout up to
4 times. Use this for order download (`D` messages).

The dispatched message (what your source transformer sees) is the payload
**without** STX/ETX, e.g. `R<FS>0<FS><FS>52<FS>…<FS><FS>33` — checksum kept
(configurable) so the transformer can re-verify and audit.

---

## 2.1 IntelliJ IDEA Setup

The repo ships a ready-to-use plain-Java IDEA project (same pattern as
`bitdreamit-astm-e1381-transmission`): four modules (`shared`, `server`,
`client`, `test`) wired to three project libraries via the `.iml` files
in `.idea/libraries/`.

1. Copy Mirth jars to a sibling `mirth-libs/` folder (see section 3 for
   the exact layout). The project libraries in `.idea/libraries/` point
   at `../mirth-libs/` relative to the repo root.
2. Open the repo root in IntelliJ IDEA (File → Open). The four modules
   are picked up from `.idea/modules.xml`; project JDK is `1.8`
   (File → Project Structure → SDKs if you need to register one).
3. The libraries must resolve to:
   - `mirth-server` = `mirth-server.jar` + `donkey-server.jar` +
     `mirth-client-core.jar` + `log4j-1.2-api-2.17.2.jar`
   - `mirth-client` = `mirth-client.jar` + `mirth-client-core.jar` +
     `miglayout-core-4.2.jar` + `miglayout-swing-4.2.jar` +
     `log4j-1.2-api-2.17.2.jar`
   - `junit-4`      = `junit-4.13.2.jar` + `hamcrest-core-1.3.jar`

   > **Critical:** `donkey-server.jar` MUST be in the `mirth-server`
   > library. Without it the `shared` module fails to compile with
   > `cannot access com.mirth.connect.donkey.util.purge.Purgable`.
4. Build → Build Artifacts is not required for production (use
   `distribution/build.sh`); for ad-hoc runs, module `test` contains the
   JUnit sources and runs with the `junit-4` library.
5. If Mirth jars are unavailable and you only want a syntax check, the
   minimal API stubs under `tools/compile-stubs/` compile the whole
   plugin (see `tools/compile-stubs/README.txt`).

---

## 3. Building

**Prerequisites**

1. JDK 8+ (tested with OpenJDK 17). No Maven required - the production
   build is plain `javac` + `jar` (same pattern as
   `bitdreamit-astm-e1381-transmission`).
2. Mirth Connect 4.5.x jars extracted to `~/mirth-libs/` (override with
   `MIRTH_LIBS_DIR=/path/to/mirth-libs`):

```
mirth-libs/
├── client/
│   ├── mirth-client-core.jar
│   ├── mirth-client.jar
│   ├── miglayout-core-4.2.jar
│   └── miglayout-swing-4.2.jar
├── server/
│   ├── mirth-server.jar
│   ├── donkey-server.jar
│   └── log4j-1.2-api-2.17.2.jar
└── test/
    ├── junit-4.13.2.jar
    └── hamcrest-core-1.3.jar
```

> The shared module's `DimensionTransmissionModeProperties` extends
> `TransmissionModeProperties` (mirth-client-core.jar) which implements
> `Purgable` (donkey-server.jar). Both jars must be on the compile
> classpath or javac fails with `cannot access Purgable`.

**Build & test**

```bash
cd distribution
./build.sh            # -> out/ (3 jars + plugin.xml + transmissionmode.xml)
./build.sh clean      # remove out/
./build.sh test       # build + run the frame/checksum JUnit tests
./build.sh rebuild    # clean + build

./deploy.sh           # build + assemble ready-to-drop extension folder
./deploy.sh zip       # ... + production ZIP (Extension Manager install)
./deploy.sh install   # ... + copy to $MIRTH_HOME/extensions/ (backup included)

MIRTH_HOME=/opt/mirth-connect ./check_extension.sh   # diagnose a deployed extension
```

Maven is still supported for IDE/CI convenience (`mvn package` from the
repo root produces the same three jars under `*/target/`), but the
canonical artifacts come from `distribution/build.sh`.

---

## 4. Deploying

1. Stop Mirth Connect.
2. Copy the contents of `out/` (or run `./deploy.sh install`) to
   `<mirth>/extensions/bitdreamit-dimension-transmission/`.
3. Delete `<mirth>/extensions/.cache/`.
4. Start Mirth Connect, restart the Mirth Administrator.
5. `Siemens Dimension` now appears in the Transmission Mode dropdown.

The client plugin registers its classes with the Administrator's XStream
security framework (`ForbiddenClassException` prevention) in its
constructor — the same mechanism the ASTM E1381 plugin uses.

---

## 5. Channel setup

### 5.1 Serial (direct RS-232 — recommended)

Instrument side (Dimension EXL): **9600 baud, 8 data bits, no parity,
1 stop bit** (mandatory for all EXL models per PN D00396 §Communication
Parameters). RTS/DTR are asserted by the instrument but are NOT flow
control signals.

Mirth channel:

| Setting | Value |
|---|---|
| Source connector | BitDreamIT **Serial Reader** |
| Port | e.g. `COM3` / `/dev/ttyUSB0` (9600, 8, N, 1, no flow control) |
| Transmission Mode | **Siemens Dimension** (wrench icon → frame settings) |
| Connector commit-ACK / MLLPv2 option | **OFF** (the mode sends the ACK itself — double-ACK confuses the instrument) |
| Source data type | **Raw** — both Inbound **and** Outbound on the source transformer (Outbound HL7 V2.x makes Mirth run the string output through `ER7Serializer.fromXML` → `Content is not allowed in prolog`) |
| Source transformer | see `tools/dimension_result_transformer.js` |
| Response | **None** — auto responses are handled inside the mode |

> Close PuTTY / the DatReadFromMachine debug tool first — two applications
> cannot share one COM port, which is exactly why a raw PuTTY capture shows
> duplicated frames (nobody ACKs, the instrument retransmits) and why
> nobody answers the instrument's polls.

### 5.2 TCP (through a serial device server or Siemens middleware link)

If the instrument is connected via a Moxa/NPort-style device server in TCP
server mode, or the site already runs a TCP link:

| Setting | Value |
|---|---|
| Source connector | TCP Listener |
| Transmission Mode | **Siemens Dimension** |
| Response | None |
| Data type | **Raw** — Inbound **and** Outbound |

The mode frames `<STX>…<ETX>` on the socket — stock MLLP (`<VT>…<FS><CR>`)
would never match a Dimension stream.

### 5.3 Instrument communication modes (PN D00396 Table 1-2)

| Instrument mode | What flows | Plugin configuration |
|---|---|---|
| **Send Only** | Results only, no polls | Defaults work; `Auto Result Acceptance` is harmless (no `M` is defined for Send Only — disable it to keep the line strictly DLC-only) |
| **Send/Receive** | Polls + orders + results | Two options: defaults (`Auto Poll/Query Response` on) answer every `P` with `N`; or set it **off** and let the channel answer - transformer rev 8+ replies to a **conversational poll** (First Poll = 0, Request = 1, manual p. 1-8/1-24) with the next queued **Sample Request (D)** (or `N`), and never downloads on an initial/busy poll |
| **Send ID/Receive** | like Send/Receive + Query (`I`) | With `Auto Poll/Query Response` off, transformer rev 7+ replies to `I` with a **Sample Request (D)** echoing the queried Sample # (manual p. 1-14) or `N` when unknown, via the `dimensionResponse` response-map variable |

### 5.4 Order download (optional, bidirectional sites)

Three supported paths:

1. **Query-driven (Send ID/Receive):** with `Auto Poll/Query Response` off and
   source Response = `dimensionResponse`, the source transformer builds the `D`
   payload (Table 1-12) in `responseMap` for `I` queries - Mirth writes it back
   on the SAME socket through the mode (framed + checksum + instrument ACK).
   See `tools/dimension_result_transformer.js` (`QUERY_ORDERS` demo table,
   replace with your LIS lookup).
2. **Poll-driven (Send/Receive, rev 8):** the same response variable answers a
   conversational poll with the next order. Push orders from any script:

   ```javascript
   var q = globalMap.get('dimensionOrderQueue') || [];
   q.push({ sampleId: '043092011', patient: 'Doe,John', type: '1',
            priority: '1', tests: ['GLU','CREA'] });
   globalMap.put('dimensionOrderQueue', q);
   ```

   Each conversational poll (First Poll = 0, Request = 1) consumes the first
   entry (a `java.util.List` from a database-reader channel works too); an
   initial poll (First Poll = 1) or busy poll (Request = 0) always gets `N`
   per the manual. Orders in the built-in demo table are downloaded at most
   once per Mirth runtime (sent-markers prevent the 1-second re-poll loop,
   p. 1-26). If the instrument rejects the download it answers `M\|R<reason>`
   (Table 1-17) - the transformer logs the decoded reason.
3. **Destination-driven (serial):** build the `D` payload in a destination
   JavaScript step and send it through a **Serial Writer** using the same
   transmission mode. Manual worked example (p. 1-11): `<STX>D<FS>0<FS>0<FS>A<FS>Doe,John<FS>012345<FS>2<FS><FS>0<FS>1<FS>**<FS>1<FS>2<FS>BUN<FS>CREA<FS>F5<ETX>`
2. **Destination-driven (serial):** build the `D` payload in a destination
   JavaScript step and send it through a **Serial Writer** using the same
   transmission mode. Manual worked example (p. 1-11): `<STX>D<FS>0<FS>0<FS>A<FS>Doe,John<FS>012345<FS>2<FS><FS>0<FS>1<FS>**<FS>1<FS>2<FS>BUN<FS>CREA<FS>F5<ETX>`

```javascript
// payload WITHOUT STX/ETX/checksum — the mode frames it and waits for ACK
var fs = String.fromCharCode(0x1C);
var payload = ['D','0','0','A','Doe,John','012345','2','','0','1','**','1','2','BUN','CREA'].join(fs);
// + one trailing FS before the checksum position
payload += fs;
```

(Field order per Table 1-12: Type, Carrier ID, Loadlist ID, Transaction
`A`/`D`, Patient ID, Sample #, Sample Type, Location, Priority, #Cups,
Cup Position `**` for barcoded tubes, Dilution, #Tests, Test Names…)

---

## 6. Property reference

| Property | Default | Meaning |
|---|---|---|
| Start of Frame (STX) | `0x02` | Start of Transmission |
| End of Frame (ETX) | `0x03` | End of Transmission |
| Field Separator (FS) | `0x1C` | Between every data field |
| Enquiry (ENQ) | `0x05` | Retry request byte |
| Use Checksum | `true` | Add-Mod-256 validate/compute |
| Checksum Byte Length | `2` | ASCII-hex checksum characters |
| ACK / NAK | `0x06` / `0x15` | Data-link handshake bytes |
| Max Retransmissions | `4` | Protocol limit (instrument error 318 at the 4th NAK) |
| ACK Timeout (ms) | `1000` | Instrument 1-second ACK/NAK timer |
| Frame Timeout (ms) | `5000` | Abort window for a partial frame |
| Auto Result Acceptance | `true` | `R`/`C` → ACK + `<STX>M<FS>A<FS><FS>E2<ETX>` |
| Result Acceptance Status | `A` | `A` = accept, `R` = reject (reason 1) |
| Auto Poll/Query Response | `true` | `P`/`I` → ACK + `<STX>N<FS>6A<ETX>` |
| Auto ENQ Acknowledge | `true` | Stray ENQ → ACK |
| Checksum in Payload | `true` | Keep `CHK` in the dispatched message |
| Server Mode | `true` | receiver (source) vs sender |

---

## 7. Parsing the Result message in a transformer

See `tools/dimension_result_transformer.js` (rev 8) for a complete, commented
implementation that maps an `R` frame to an HL7 v2 `ORU^R01` (one OBX per
test, error-code handling with the Appendix III/IV table, QC routing via
Sample Type, `ssmmhhddmmyy` → HL7 TS conversion), answers `P`/`I` with `D`/`N`
(order download), and decodes `M` Request Acceptances (Table 1-17) and
`C` Calibration headers (Table 1-25).

A matching offline tool, `tools/decode_dimension.py`, decodes raw captures
or re-typed frames and verifies their checksums — handy when all you have
is a PuTTY-style paste where STX/FS/ETX are invisible.

---

## 8. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Instrument error **321** ("Did not receive ACK or NAK") | Nothing is answering the port: another program (PuTTY/debug tool) holds the COM port, wrong port, or connector commit-ACK disabled while the mode is not selected |
| Instrument error **320** ("Did not receive acceptance message") | `Auto Result Acceptance` off in Send/Receive mode |
| Instrument error **318** ("Received fourth NAK") | 4 consecutive bad checksums — wrong baud/parity, or a mode mismatch (ASTM framing on a Dimension) |
| Instrument error **319** ("Received invalid message") | Unexpected app-level frame sent to the instrument (e.g. `M` responses enabled in Send Only mode) |
| Repeated identical frames in captures | Normal: nobody ACKed within 1 s, the instrument retransmits up to 4 times |
| `ForbiddenClassException` in Administrator | The client plugin registers the package automatically; make sure the extension is **enabled** in the Administrator's Extension list |
| Multiple result messages per sample | The instrument's **priority panel** feature sends partial results — your transformer must merge or accept duplicates |
| Message Source tab shows a flattened blob like `R0DOE,JOHN10011ER1...CD` with no delimiters | **Normal display** — `<FS>` (0x1C) is a non-printable control character, so the Administrator renders the fields stuck together. Export the message and check the RAW `content`: the `&#x1c;` entities are the field separators and the payload is intact |
| Every message ends **ERROR** with `Transformer error ... TypeError: Element type "R" must be followed by either attribute specifications, ">" or "/>"` | Source **Inbound Data Type is HL7 V2.x (or XML)**. Mirth pre-serializes the non-HL7 payload to `<HL7Message><R&#x1c;0&#x1c;...></R&#x1c;...></HL7Message>` — `0x1C` is illegal in an XML tag name — and the generated prelude `msg = new XML(connectorMessage.getTransformedData());` crashes before your script runs. Fix: **Source → Set Data Types → Inbound Data Type = Raw** and **Response = None** (Mirth then passes the payload as a plain string). See section 5 tables |
| Checksum error **`Dimension checksum mismatch: received CD, calculated CD`** (identical values!) with Inbound Data Type = Raw | The transformer compared a **java.lang.String** token with a native JS string using `!==` — strict comparison never coerces, so object vs primitive is always unequal even when both print as `CD`. Fixed in reference transformer **rev 6** (`String()` coercion of `getRawData()`); or add `var raw = String(connectorMessage.getRawData());` to your own script |
| Transformed Data shows the correct ORU but the message is **ERROR** with `ER7Serializer error - Error converting XML to ER7 ... Content is not allowed in prolog` | Source **Outbound** Data Type is HL7 V2.x: after the transformer Mirth converts the string output with `ER7Serializer.fromXML()` (see `FilterTransformerExecutor`), and plain ER7 is not XML. Set **Outbound Data Type = Raw** on the source transformer (destinations can stay HL7 V2.x — they receive valid ER7) |
| Log shows `SocketException: Socket closed` at `DimensionStreamHandler.read` when a client disconnects or the channel is redeployed | Benign disconnect noise — since plugin build 2026-08 the handler converts it to a clean EOF (logged at INFO). Older builds just log it at ERROR; safe to ignore |
| Poll storm (`P` every 15 s) in logs | Normal idle behaviour; `Auto Poll/Query Response` answers it with `N` |

---

## 9. Project structure

```
bitdreamit-dimension-transmission/
├── plugin.xml                     # root extension descriptor
├── transmissionmode.xml           # transmission-mode registration
├── pom.xml                        # Maven parent (shared, server, client, test)
├── shared/                        # constants + DimensionTransmissionModeProperties
├── server/                        # DimensionTransmissionModePlugin + DimensionStreamHandler
├── client/                        # admin UI: client plugin, provider, settings panel/dialog
├── test/                          # JUnit: checksums, round-trip, NAK retry, ACK wait
├── distribution/build.sh          # build + deploy helper
├── tools/
│   ├── dimension_result_transformer.js   # R/C frame → HL7 ORU^R01
│   └── decode_dimension.py               # capture decoder / checksum verifier
└── stubs/                         # Purgable compile stub (fallback)
```
