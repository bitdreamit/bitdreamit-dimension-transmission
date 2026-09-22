# bitdreamit-dimension-transmission v2.0.1 — Serial Compatibility

## Verdict

**The plugin was already transport-agnostic.** Its `DimensionTransmissionModePlugin`
extends Mirth's standard `TransmissionModeProvider` and builds a
`DimensionStreamHandler` on plain `InputStream`/`OutputStream` — no socket
dependency. The Serial Connector v1.4.0 discovers it through Mirth's global
transmission-mode registry **by name** ("Siemens Dimension") and drives it
through `getStreamHandler()` exactly like the TCP Listener does.

**No configuration change is needed. No code change was strictly required.**
v2.0.1 adds one robustness hardening + a full serial-proof test suite.

## What changed in 2.0.1 (vs 2.0.0)

| File | Change |
|---|---|
| `server/.../DimensionStreamHandler.java` | `splitFields()` hardened: strips the 2 checksum characters when an analyzer omits the trailing FS (they would glue onto the last field and break exact Sample-ID matching, manual p.1-14), and normalizes a missing trailing FS — same tolerance as the Serial Connector's built-in DimensionProvider. |
| `test/.../DimensionSerialPipeTest.java` | **NEW — 10 tests**: drives the handler over a virtual RS-232 line (2-byte drips, 2 ms cadence) with an instrument-side auto-ACK responder, byte-identical to `SerialSourceConnector.providerReadLoop()`'s driving loop. Uses your REAL production frames (poll `P·DIM·0·1·0·` chk `47`, result `R…GLUC…CRE2…` chk `43`, calibration `C…AST…GA7033…`, No Request `N6A`, Acceptance `M·A··E2`). |

Test suite result: **21/21 PASS** (10 serial-pipe + 11 original frame tests).

Covered scenarios: poll→No Request · result→ACK+M-A acceptance · calibration→acceptance ·
conversational poll→order download (D frame)→then N · barcode query (I)→D echo ·
barcode query **without** trailing FS→still resolves · bad checksum→NAK then recovery ·
stray ENQ→ACK · stray ACK consumed · outbound `write()` framing + ACK wait.

## Running Dimension over SERIAL (with Serial Connector v1.4.0)

| Goal | Serial channel setting | Behaviour |
|---|---|---|
| **Bidirectional** (results + order download + barcode) | Transmission Mode Properties → **Siemens Dimension** | This plugin's full engine: poll→N/D, R/C→ACK+M-A, `DimensionOrderRegistry.pushOrder(...)` works unchanged |
| Unidirectional results (fallback, no plugin needed) | Transmission Mode → **DIMENSION** (built-in) | Serial connector's self-contained tolerant engine |

Both can be installed side by side — the serial built-ins live in the serial
connector's **private** registry and never collide with this plugin's
"Siemens Dimension" registration in Mirth's global registry. TCP channels are
untouched.

## Install

1. Undeploy Dimension channels. 2. Stop Mirth. 3. Replace
   `extensions/bitdreamit-dimension-transmission/` with this folder
   (plugin.xml + transmissionmode.xml + 3 jars). 4. Delete `extensions/.cache/`.
5. Start Mirth, redeploy. Channel XML needs **no changes**.

## Full source

`source/` contains shared/server/client/test trees + compile stubs + poms.
Rebuild the server jar after any change:

```
javac -d build $(find source/stubs source/shared/src source/server/src -name '*.java')
jar cf bitdreamit-dimension-transmission-server.jar -C build com/bitdreamit/connect/plugins/transmission/dimension/server \
                                                    -C build com/bitdreamit/connect/plugins/transmission/dimension/shared
```

(server and client jars carry a merged copy of the shared classes — keep that
layout so Mirth loads one self-contained jar per side.)
