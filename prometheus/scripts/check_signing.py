#!/usr/bin/env python3
"""Reject a phone-update artifact unless it is signed as the existing Nova APK."""
from pathlib import Path
import argparse
import re
import subprocess

parser=argparse.ArgumentParser()
parser.add_argument('--apk',required=True)
parser.add_argument('--apksigner',required=True)
args=parser.parse_args()
expected=Path(__file__).with_name('nova-certificate.sha256').read_text().strip()
result=subprocess.check_output([args.apksigner,'verify','--print-certs',args.apk],text=True)
found=re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)',result)
if found != [expected]:
    raise SystemExit('Signing mismatch: this APK cannot update the existing Nova installation. Do not uninstall Nova. Supply its original signing key.')
print('Nova signing certificate matches the existing installation baseline.')
