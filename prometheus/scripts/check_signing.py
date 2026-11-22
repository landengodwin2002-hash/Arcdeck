#!/usr/bin/env python3
"""Verify the permanent signing identity of the independent Prometheus application."""
from pathlib import Path
import argparse
import re
import subprocess

parser=argparse.ArgumentParser()
parser.add_argument('--apk',required=True)
parser.add_argument('--apksigner',required=True)
args=parser.parse_args()
expected=Path(__file__).with_name('release-certificate.sha256').read_text().strip()
result=subprocess.check_output([args.apksigner,'verify','--print-certs',args.apk],text=True)
found=re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)',result)
if found != [expected]:
    raise SystemExit('Signing mismatch: this APK must use the permanent Prometheus release key.')
print('Prometheus permanent release certificate verified.')
