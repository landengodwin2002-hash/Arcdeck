#!/usr/bin/env python3
"""Sign the tested ARM64 APK with the privately backed-up permanent key."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--apk', type=Path, required=True)
parser.add_argument('--credentials', type=Path, required=True)
parser.add_argument('--apksigner', required=True)
parser.add_argument('--zipalign', required=True)
parser.add_argument('--aapt2', required=True)
parser.add_argument('--out', type=Path, required=True)
args = parser.parse_args()
credentials = json.loads(args.credentials.read_text())
expected = Path(__file__).with_name('release-certificate.sha256').read_text().strip()
if credentials['application_id'] != 'ai.prometheus.offline.chat' or credentials['certificate_sha256'] != expected:
    raise SystemExit('The private signing backup does not match this Prometheus application')
if args.apk.resolve() == args.out.resolve():
    raise SystemExit('Input and signed output must be different files')
badging = subprocess.check_output([args.aapt2, 'dump', 'badging', str(args.apk)], text=True)
if "package: name='ai.prometheus.offline.chat'" not in badging or 'application-debuggable' in badging:
    raise SystemExit('Expected the non-debuggable independent Prometheus release APK')
key = args.credentials.parent / credentials['keystore_file']
args.out.parent.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory() as private:
    store_password = Path(private) / 'store.pass'
    key_password = Path(private) / 'key.pass'
    store_password.write_text(credentials['keystore_password']); store_password.chmod(0o600)
    key_password.write_text(credentials['key_password']); key_password.chmod(0o600)
    subprocess.run([args.apksigner, 'sign', '--ks', str(key), '--ks-key-alias', credentials['key_alias'],
                    '--ks-pass', 'file:' + str(store_password), '--key-pass', 'file:' + str(key_password),
                    '--out', str(args.out), str(args.apk)], check=True)
subprocess.run(['python3', str(Path(__file__).with_name('check_signing.py')),
                '--apk', str(args.out), '--apksigner', args.apksigner], check=True)
subprocess.run([args.zipalign, '-c', '-P', '16', '4', str(args.out)], check=True)
print('Signed and verified Prometheus release: ' + str(args.out))
