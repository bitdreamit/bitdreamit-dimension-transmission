# Dimension Transmission v2 — Dynamic Bidirectional Redesign (rev 10)

## What changed (why errors are gone)

| Before (rev 8/9) | Now (v2 / rev 10) |
|---|---|
| JS transformer built D/N frames + put them in `responseMap` → Mirth wrote them after full processing (slow, and `write()` retransmitted 4x blindly on timeout → `IOException: Dimension frame not acknowledged after 4 attempts`) | **Java handler answers P/I INSIDE the read path** (milliseconds after the frame arrives). `write()` only used for manual sends; timeout = stop waiting, never duplicate, never throw |
| Static `QUERY_ORDERS` table hardcoded in JS — any unknown barcode/error cascaded | **`DimensionOrderRegistry`** — dynamic JVM-wide queue, pushed from any channel; unknown ID → clean N frame |
| Checksum re-validated in JS with strict compare (`!==`) → "received CD, calculated CD" errors | Checksum handled by Java (NAK/retransmit); JS check is informational-only, never throws |
| Transformer did protocol + formatting | Transformer is **purely R→HL7 ORU** formatting. Zero protocol code. Cannot throw (all parse failures → `logger.error` + continue) |

Wire behavior now (all answered by Java inside `read()`):

| Instrument sends | Host answers (immediately, in read path) |
|---|---|
| `P` conversational (FirstPoll=0, Request=1) | queued order → `<STX>D...<ETX>` (FIFO pop), else `<STX>N 6A<ETX>` |
| `P` plain (e.g. `P\FS\DIM\FS\1\FS\1\FS\0`) | `<STX>N 6A<ETX>` |
| `I <barcode>` (scan) | matching order → `<STX>D...<ETX>` (echoes scanned ID), else N |
| `R` / `C` | `<ACK>` + `<STX>M A <FS> E2<ETX>` (Result Acceptance) |
| bad checksum | `<NAK>` (instrument retransmits, up to 4) |

## Install

1. Unzip `bitdreamit-dimension-transmission-v2-dynamic.zip` into `MIRTH_HOME/extensions/`
   (creates `extensions/bitdreamit-dimension-transmission/` with 3 jars + 2 XMLs), restart Mirth.
2. Import `Dimention-dynamic.xml` (RAW in/out, **Source Response = None**,
   `autoPollResponse=true`, `orderLookupEnabled=true`, `orderQueueKey=default`).
3. Test scan: push an order (below), scan barcode `043092011` → D frame downloads.

## Pushing orders dynamically (from ANY channel's JavaScript)

```javascript
var Reg = Packages.com.bitdreamit.connect.plugins.transmission.dimension.server.DimensionOrderRegistry;

// simplest: CSV tests
Reg.pushOrder('default', '043092011', 'DOE,JOHN', '1', '0', 'GLU,CREA,F5');

// or a map (keys: sampleId|sample, patient, type, priority, tests)
var m = new java.util.HashMap();
m.put('sampleId', '043092011'); m.put('patient', 'DOE,JOHN');
m.put('type', '1'); m.put('priority', '0'); m.put('tests', 'GLU,CREA');
Reg.pushOrder('default', m);

// inspect / maintain
Reg.queueSize('default'); Reg.clear('default');
```

Typical producers: an HTTP Listener channel receiving orders from HIS, a
Database Reader polling `SELECT ... FROM orders WHERE status='NEW'` (call
pushOrder in its transformer, then mark rows sent), or any existing ADT/ORM feed.
The key must match the connector's `orderQueueKey` (default `default`).

## Demo mode (no HIS yet)

Start Mirth with `-Ddimension.demoOrders=true` → the registry auto-seeds
`012345` and `043092011` (BUN/CREA/F5) once per queue, so barcode tests work
immediately.

## Connector properties (channel XML)

| Property | Default | Meaning |
|---|---|---|
| `orderLookupEnabled` | true | answer P/I from the registry (D) or fall back to N |
| `orderQueueKey` | default | which registry queue this connector consumes |
| `autoPollResponse` | true | send N when no order is queued |
| `autoResultAcceptance` | true | send M-A after every R/C |
| Source `Response` | **None** | Mirth must NOT write to the wire — the plugin owns the line |

## Verification

- `distribution/build.sh test` → **JUnit 11/11 OK** (barcode D download, FIFO
  poll download, lookup-off fallback, write-sent-once-on-timeout, registry
  variants, demo seed idempotent).
- Rhino 21/21 transformer assertions (R→ORU byte-exact, corrupt/truncated/
  garbage/empty inputs never throw).
- Live: `python3 dimension_live_test.py --host <ip> --port 6661` — push an
  order first, then the query test downloads D and ACKs it.

## Files in this delivery

- `bitdreamit-dimension-transmission-v2-dynamic.zip` — ready-to-drop extension (3 jars + plugin.xml + transmissionmode.xml, pluginVersion 2.0.0)
- `Dimention-dynamic.xml` — Mirth channel (revision 11, slim transformer embedded)
- `transformer-dynamic.js` — the slim transformer source (same as embedded)
- `dimension-dynamic-redesign.patch` — full source diff vs rev 8 (git)
- `bitdreamit-dimension-transmission-{server,shared,client}.jar` — individual jars
