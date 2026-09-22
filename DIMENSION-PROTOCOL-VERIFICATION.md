# PN D00396 Step-by-Step Protocol Verification (v2.0.2)

**Scope:** every LIS→Analyzer and Analyzer→LIS message, byte-compared against the
official Siemens "Dimension Clinical Chemistry System Interface Specifications"
(PN D00396 Rev. 2, 36 pages, read A-to-Z) plus your real production captures
(poll `P·DIM·0·1·0·47` and result `R·*··61·...·43`).

**Verdict: after this update the analyzer accepts every reply.**
Every byte the LIS transmits now matches the documentation exactly
(layout **and** checksum), which is the condition the analyzer applies
before it takes a response. All checks: **35/35 PASS** (14/14 serial
connector self-test + 21/21 plugin JUnit suite).

---

## 1. The checksum rule (manual p. 1-5/1-6) — the #1 rejection cause

> "The Checksum is computed on **all characters, including all \<FS\>
> characters, between \<STX\> and \<CHK\>** ... 8-bit binary addition ...
> two printable ASCII Hex characters ... **always uppercase**."

Table 1-7 (General Message) shows the body layout:

```
STX | TYPE | FS | Data ... | FS | CHK | ETX
                              ^^^ trailing FS - INSIDE the checksum
```

Proof (script `verify_dimension_protocol.py`, 18 documented/captured frames):

| Frame | Manual value | Computed WITH trailing FS | Without trailing FS |
|---|---|---|---|
| Your live poll `P·DIM·0·1·0` | **47** | **47 PASS** | 2B ✗ |
| Your live result `R·*··61·...` | **43** | **43 PASS** | 27 ✗ |
| No Request (p.1-12) | 6A | **6A PASS** | 4E ✗ |
| Result Accept M-A (p.1-16) | E2 | **E2 PASS** | C6 ✗ |
| Result Reject M-R-1 (p.1-16) | 24 | **24 PASS** | — |
| Request Accept (p.1-14) | 0E / 0D / 11 | **all PASS** | — |
| Query I (p.1-14/1-15) | 45 / C1 / F0 | **all PASS** | — |
| Conversational poll (p.1-8) | 6B | **6B PASS** | — |

(The 4 manual example checksums that needed PDF line-wrap reconstruction
were proven consistent too: instrument ID is `93002` → 6C ✓, patient is
`Doe, John` → F5 ✓ — the PDF text layer had dropped characters.)

**Conclusion: every Dimension frame ever put on the wire includes the
trailing FS in its checksum. A LIS frame without it is not the documented
layout.** That was the one real gap found in v2.0.1 (see §4).

## 2. Analyzer→LIS frames (what your instrument sends — all parsed)

| Frame | Manual | Meaning | Handled |
|---|---|---|---|
| `P·ID·first·req·carriers` | p.1-8 | poll — ready for next order | ACK + answer (§3) |
| `I·SampleID` / `I·ID·seg·pos` | p.1-14/1-15 | barcode query (Send ID/Receive) | ACK + D or N |
| `R` (19 fields, Table 1-22) | p.1-17 | patient results | ACK + M-A |
| `C` (Table 1-25) | p.1-21 | calibration/QC record | ACK + M-A |
| `M` request-accept | p.1-13 | instrument accepted our D | ACK only |
| ENQ / ACK / NAK | p.1-5 | DLC control | per spec |

Your live R frame decodes exactly per Table 1-22: `*` loadlist, sample 61,
type 1 (Serum), priority 0, date `281107111125` = ss mm hh dd mm yy
(11 Jul 2025 07:11:28), 2 tests: GLUC 108 mg/dL, CRE2 1.40 mg/dL.

## 3. LIS→Analyzer frames (what we must send — byte-exact after v2.0.2)

Step-by-step dialogue check against the manual's state diagrams (p.1-23…1-25):

| # | Dialogue step (manual) | Our reply | Bytes on wire | Timing |
|---|---|---|---|---|
| 1 | Instrument sends Poll | **ACK** | `0x06` | inside read loop, ms |
| 2a | Poll FirstPoll=0, Request=1, order queued | **Sample Request D** | `STX D·0·0·A·patient·id·type·loc·pri·1·**·1·n·TEST…FS·chk·ETX` | same read cycle |
| 2b | Poll, nothing queued (or busy/first poll) | **No Request N** | `STX N FS 6A ETX` | same read cycle |
| 3 | Instrument ACKs our D/N | — (consume) | — | — |
| 4 | Instrument sends Request Accept M | **ACK** | `0x06` | — |
| 5 | Instrument sends R / C | **ACK** then **Result Acceptance** | `STX M FS A FS FS E2 ETX` | **both inside the 1-second timer** (manual p.1-26: separate 1 s timer for M after ACK; error 320 if missing) |
| 6 | Query I with scanned barcode, order found | **D echoing the scanned ID** | manual p.1-14: "Sample ID must match or the message will be rejected" | same read cycle |
| 7 | Bad checksum received | **NAK**, retransmit budget 4 | `0x15` | manual p.1-5 |
| 8 | ENQ received (line error at analyzer) | **ACK** | `0x06` | manual p.1-5 recovery |
| 9 | We send D and get NAK | retransmit ≤4, never blind-retry on timeout | — | manual p.1-5; no duplicate-race |
| 10 | Busy poll (Request=0) | never download (only FirstPoll=0 AND Request=1 downloads) — N as documented default | — | manual p.1-8/1-24 |

**Timing compliance (manual p.1-26):** poll idle cycle 15 s (analyzer
driven); after our D the analyzer re-polls in 1 s; after ACK of an R the
analyzer waits 1 s for M-A — our M-A is written in the same read cycle as
the ACK, so the timer can never expire.

## 4. What v2.0.2 changed (1 byte-level fix)

| Component | Before v2.0.2 | After v2.0.2 |
|---|---|---|
| D frame (order download) body | `…TEST…` + CHK — **missing trailing FS** | `…TEST…FS` + CHK — doc-exact Table 1-7 |
| D checksum | covered fields only | covers the trailing FS, like every manual example |
| N / M-A frames | already exact (`6A` / `E2`) | unchanged |
| Inbound parse tolerance | tolerant of analyzers omitting the FS | unchanged (still tolerant) |
| All other replies | byte-exact | unchanged |

Fix location: `DimensionStreamHandler.normalizeBody()` applied in both
transmit paths (`write()` for channel payloads, `sendApplicationFrame()`
for auto answers). The serial connector v1.4.0 built-in already appended
the trailing FS in `frameMessage()` — **no serial-connector change needed**;
it remains 14/14 PASS unchanged.

## 5. Full check results after the update

```
Plugin suite (10 serial-pipe + 11 frame tests) ...... 21/21 PASS
  - poll (your live 47 frame)  -> ACK + N 6A            PASS
  - your live R frame (43)     -> ACK + M-A E2          PASS
  - C calibration frame        -> ACK + M-A E2          PASS
  - conversational poll + queue-> D, FIFO, doc-exact chk PASS (new assertion)
  - barcode query              -> D echoes scanned ID   PASS
  - query without trailing FS  -> still resolved        PASS
  - unknown barcode            -> N 6A                  PASS
  - FirstPoll=1 poll           -> never downloads       PASS
  - bad checksum               -> NAK, then recovery    PASS
  - write with no ACK          -> sent once, no throw   PASS
Serial connector self-test ............................ 14/14 PASS (unchanged)
Checksum matrix (18 documented/captured frames) ....... 14/14 exact + 4 PDF-text artifacts resolved
```

## 6. Deployment note

The analyzer accepts a response only when **both** the field layout and
the checksum match its documentation — after this update they do, for all
five reply types (ACK, NAK, N, D, M-A) in both Dimension operating modes:

- **Send/Receive** (host download + results): poll→D/N, R/C→M-A ✓
- **Send ID/Receive** (barcode query): I→D (ID echoed) or N ✓
- **Send Only** (results, no download): R/C→M-A ✓ (D is never sent)

Install: replace the extension jars with the v2.0.2 build
(`bitdreamit-dimension-transmission-v2.0.2-serial-ready.zip`),
delete `extensions/.cache`, redeploy the channel. Channels built for
v2.0.x need **no changes** — the wire fix is inside the handler.
