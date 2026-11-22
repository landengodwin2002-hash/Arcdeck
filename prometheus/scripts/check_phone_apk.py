#!/usr/bin/env python3
"""Check the phone artifact's ABI and native 16 KiB load alignment."""
import argparse
import json
from pathlib import Path
import struct
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('apk', type=Path)
args = parser.parse_args()
with zipfile.ZipFile(args.apk) as apk:
    libraries = [name for name in apk.namelist() if name.startswith('lib/') and name.endswith('.so')]
    if libraries != ['lib/arm64-v8a/libprometheus.so']:
        raise SystemExit(f'Unexpected phone native libraries: {libraries}')
    data = apk.read(libraries[0])
if data[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<H', data, 18)[0] != 183:
    raise SystemExit('The phone runtime must be a little-endian 64-bit ARM ELF')
offset = struct.unpack_from('<Q', data, 32)[0]
entry_size, count = struct.unpack_from('<HH', data, 54)
segments = []
for i in range(count):
    kind, flags, file_offset, virtual, _, file_size, memory_size, alignment = struct.unpack_from('<IIQQQQQQ', data, offset + i * entry_size)
    if kind != 1:  # PT_LOAD
        continue
    if alignment < 16384 or file_offset % 16384 != virtual % 16384:
        raise SystemExit(f'Native segment is not compatible with 16 KiB pages: {alignment}')
    segments.append({'flags': flags, 'file_bytes': file_size, 'memory_bytes': memory_size, 'alignment': alignment})
if not segments:
    raise SystemExit('No native load segments found')
Path('test-results').mkdir(exist_ok=True)
Path('test-results/phone-native-layout.json').write_text(json.dumps({'abi': 'arm64-v8a', 'library': libraries[0], 'segments': segments}, indent=2))
print('ARM64 phone runtime and 16 KiB ELF alignment verified')
