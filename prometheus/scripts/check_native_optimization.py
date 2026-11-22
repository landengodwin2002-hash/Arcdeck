#!/usr/bin/env python3
"""Verify the actual generated C/C++ commands, not just Gradle's requested flags."""
import json
from pathlib import Path
import re
import shlex

checked = []
for database in Path('app/.cxx').rglob('compile_commands.json'):
    for entry in json.loads(database.read_text()):
        if '/ggml/' not in entry['file']:
            continue
        args = entry.get('arguments') or shlex.split(entry['command'])
        levels = [arg for arg in args if re.fullmatch(r'-O(?:[0-3szg]|fast)', arg)]
        if not levels or levels[-1] not in ('-O2', '-O3', '-Ofast'):
            raise SystemExit(f"Unoptimized inference code: {entry['file']} {levels}")
        checked.append({'file': entry['file'], 'optimization': levels[-1]})
if not checked:
    raise SystemExit('No generated ggml compiler commands found')
Path('test-results').mkdir(exist_ok=True)
Path('test-results/native-build-config.json').write_text(json.dumps(checked, indent=2))
print(f'Verified optimized native compilation for {len(checked)} ggml sources')
