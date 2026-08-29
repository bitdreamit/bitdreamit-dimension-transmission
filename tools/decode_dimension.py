#!/usr/bin/env python3
"""
Siemens Dimension frame decoder / checksum verifier
====================================================
Companion tool for bitdreamit-dimension-transmission.

Use it to decode raw captures from the instrument (serial logger files,
hex dumps) or to re-verify frames that were re-typed from a terminal
emulator paste (PuTTY hides STX/FS/ETX, which is why captures look mangled).

Examples
--------
    # decode frames from a raw byte capture
    python3 decode_dimension.py capture.bin

    # decode a hex dump (one frame per line, control chars as <02> etc.)
    python3 decode_dimension.py --hex capture_hex.txt

    # verify the well-known reference frames
    python3 decode_dimension.py --selftest
"""
import sys

STX, ETX, ENQ, ACK, NAK, FS = 0x02, 0x03, 0x05, 0x06, 0x15, 0x1C

MSG_TYPES = {
    'P': 'Poll', 'D': 'Sample Request', 'N': 'No Request', 'W': 'Wait',
    'M': 'Request/Result Acceptance', 'I': 'Query', 'R': 'Result',
    'C': 'Calibration Result'
}

SAMPLE_TYPES = {'W': 'Whole Blood', '1': 'Serum', '2': 'Plasma', '3': 'Urine',
                '4': 'CSF', '5': 'SerumQC1', '6': 'SerumQC2', '7': 'SerumQC3',
                '8': 'UrineQC1', '9': 'UrineQC2'}
PRIORITIES = {'0': 'Routine', '1': 'STAT', '2': 'ASAP', '3': 'QC', '4': 'XQC'}

ERROR_CODES = {
    '1': 'Temperature Out Of Range', '2': 'Calibration Expired',
    '3': 'Assay Out Of Range/Diluted', '4': 'Absorbance',
    '5': 'Measurement System (noise, cuvette, etc.)', '6': 'Reagent QC',
    '7': 'Arithmetic Error', '8': 'Never Calibrated', '9': 'No Reagent',
    '10': 'Aborted Test', '11': 'Processing Error', '12': 'Software Error',
    '13': 'Hemoglobin', '14': 'Abnormal Reaction', '15': 'Diluted',
    '16': 'Below Assay Range', '17': 'Above Assay Range', '18': 'HIL Detected',
    '19': 'Clot Detected'
}


def checksum(payload: bytes) -> str:
    """8-bit Add-Mod-256 sum, two uppercase hex characters."""
    return format(sum(payload) & 0xFF, '02X')


def make_frame(payload: str) -> bytes:
    """Build a full frame: <STX> payload <CHK> <ETX>."""
    p = payload.encode('ascii')
    return bytes([STX]) + p + checksum(p).encode('ascii') + bytes([ETX])


def parse_frames(data: bytes):
    """Split a raw byte stream into frames + control-byte events."""
    frames, control, buf, in_frame = [], [], bytearray(), False
    for b in data:
        if b == STX:
            buf = bytearray()
            in_frame = True
            continue
        if not in_frame:
            if b in (ENQ, ACK, NAK):
                control.append((b, None))
            continue
        if b == ETX:
            frames.append(bytes(buf))
            buf = bytearray()
            in_frame = False
            continue
        buf.append(b)
    return frames, control


def decode_payload(payload: bytes) -> dict:
    """Decode one dispatched payload (TYPE ... FS CHK)."""
    out = {'payload': payload.decode('ascii', 'replace')}
    if len(payload) < 3:
        out['error'] = 'too short'
        return out
    chk = payload[-2:].decode('ascii', 'replace')
    body = payload[:-2]
    calc = checksum(body)
    out['type'] = chr(payload[0]) if 32 <= payload[0] < 127 else '?'
    out['type_name'] = MSG_TYPES.get(out['type'], 'Unknown')
    out['chk_ok'] = (calc == chk.upper())
    out['chk_received'], out['chk_calc'] = chk, calc
    fields = body.decode('ascii', 'replace').split(chr(FS))
    out['fields'] = fields          # fields[0] is the message TYPE
    out['data'] = fields[1:]        # data fields after the TYPE

    if out['type'] == 'R' and len(fields) >= 12:
        data = fields[1:]
        (loadlist, pid, sample_no, stype, loc, pri, dt, cups, dil, ntests) = data[:10]
        out['sample'] = {
            'loadlist': loadlist, 'patient_id': pid, 'sample_no': sample_no,
            'sample_type': SAMPLE_TYPES.get(stype, stype), 'location': loc,
            'priority': PRIORITIES.get(pri, pri),
            'datetime': '%s:%s:%s on %s/%s/20%s (ss mm hh dd mm yy)' % (
                dt[0:2], dt[2:4], dt[4:6], dt[6:8], dt[8:10], dt[10:12]),
            'cups': cups, 'dilution': dil, 'n_tests': ntests,
        }
        tests = []
        tail = data[10:]
        for i in range(0, len(tail) - 3, 4):
            name, result, units, err = tail[i:i + 4]
            tests.append({'test': name, 'result': result, 'units': units,
                          'error': err if not err else
                          '%s (%s)' % (err, ERROR_CODES.get(err, 'unknown'))})
        out['tests'] = tests
    return out


def print_report(data: bytes, title: str):
    print('=' * 72)
    print(title)
    print('=' * 72)
    frames, control = parse_frames(data)
    for c, ctx in control:
        names = {ENQ: 'ENQ', ACK: 'ACK', NAK: 'NAK'}
        print('[control] %s%s' % (names.get(c, hex(c)), ' ' + ctx if ctx else ''))
    for idx, fr in enumerate(frames, 1):
        d = decode_payload(fr)
        status = 'OK ' if d.get('chk_ok') else 'BAD'
        print('\n--- frame %d: type %s (%s)  checksum %s  [%s] ---'
              % (idx, d.get('type'), d.get('type_name'),
                 '%s/%s' % (d.get('chk_received'), d.get('chk_calc')), status))
        if d.get('error'):
            print('    error:', d['error'])
            continue
        if 'sample' in d:
            for k, v in d['sample'].items():
                print('    %-12s: %s' % (k, v))
            for t in d.get('tests', []):
                print('    test       : %-6s %-10s %-8s %s'
                      % (t['test'], t['result'], t['units'], t['error']))
        else:
            for i, f in enumerate(d['fields']):
                print('    field[%02d]: %r' % (i, f))


def selftest():
    """Reference frames verified against PN D00396 and a live capture."""
    print('Checksum self-test (PN D00396 + captured poll)')
    checks = [
        ('Result Acceptance Accept', make_frame('M\x1cA\x1c\x1c'), 'E2'),
        ('Result Acceptance Reject', make_frame('M\x1cR\x1c1\x1c'), '24'),
        ('No Request',               make_frame('N\x1c'),           '6A'),
        ('Poll (ID=DIM, captured)',  make_frame('P\x1cDIM\x1c1\x1c1\x1c0\x1c'), '48'),
    ]
    ok = True
    for name, frame_bytes, expected in checks:
        payload = frame_bytes[1:-1]
        actual = checksum(payload[:-2])
        status = 'PASS' if actual == expected else 'FAIL'
        ok &= (actual == expected)
        print('  %-28s chk=%s (expected %s)  %s' % (name, actual, expected, status))
    print('\nAll %s' % ('PASSED' if ok else 'FAILED'))
    return 0 if ok else 1


def read_hex_file(path):
    """Read a text file with hex or <02>-style control-char annotations."""
    import re
    raw = bytearray()
    text = open(path, 'r', errors='replace').read()
    text = re.sub(r'<([0-9A-Fa-f]{2})>', r' \1 ', text)
    for tok in text.split():
        try:
            raw.append(int(tok, 16) & 0xFF)
        except ValueError:
            pass
    return bytes(raw)


def main():
    if '--selftest' in sys.argv:
        sys.exit(selftest())
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    if not args:
        print(__doc__)
        sys.exit(1)
    path = args[0]
    if '--hex' in sys.argv:
        data = read_hex_file(path)
    else:
        data = open(path, 'rb').read()
    print_report(data, 'Frames in %s' % path)


if __name__ == '__main__':
    main()
