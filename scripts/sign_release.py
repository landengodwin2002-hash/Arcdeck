#!/usr/bin/env python3
"""Sign and cryptographically verify an ArcDeck release using the owner's private key."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
from zipfile import ZipFile


def initialize(directory):
    directory.mkdir(parents=True,exist_ok=True,mode=0o700)
    key=directory/'ArcDeck-release.p12'
    password=directory/'keystore.password'
    if key.exists():
        if not password.is_file(): raise SystemExit('Signing password is missing; restore the signing backup')
        return
    if password.exists(): raise SystemExit('Partial signing state; restore or choose an empty private directory')
    password.write_text(secrets.token_urlsafe(40)+'\n');password.chmod(0o600)
    subprocess.run(['keytool','-genkeypair','-alias','arcdeck','-keyalg','RSA','-keysize','4096',
        '-sigalg','SHA256withRSA','-validity','10000','-storetype','PKCS12','-keystore',str(key),
        '-storepass:file',str(password),'-dname','CN=ArcDeck Personal Release,O=ArcDeck'],check=True)
    key.chmod(0o600)
    public=directory/'ArcDeck-release.cer'
    subprocess.run(['keytool','-exportcert','-alias','arcdeck','-keystore',str(key),
        '-storepass:file',str(password),'-file',str(public)],check=True)
    (directory/'signing-info.json').write_text(json.dumps({'alias':'arcdeck','format':'PKCS12',
        'certificate_sha256':hashlib.sha256(public.read_bytes()).hexdigest(),
        'application_id':'dev.arcdeck.workspace','version':'1.0.0'},indent=2)+'\n')


def sign(source,output,tool,directory):
    key=directory/'ArcDeck-release.p12';password=directory/'keystore.password'
    if not key.is_file() or not password.is_file(): raise SystemExit('Restore the private signing backup before signing')
    with ZipFile(source) as archive:
        if archive.testzip() is not None: raise SystemExit('Corrupt unsigned APK')
        if not {'classes.dex','AndroidManifest.xml','resources.arsc'}<=set(archive.namelist()): raise SystemExit('Invalid APK structure')
    output.parent.mkdir(parents=True,exist_ok=True)
    subprocess.run(['java','-jar',str(tool),'sign','--ks',str(key),'--ks-type','PKCS12',
        '--ks-key-alias','arcdeck','--ks-pass',f'file:{password}','--min-sdk-version','29',
        '--v1-signing-enabled','false','--v2-signing-enabled','true','--v3-signing-enabled','true',
        '--out',str(output),str(source)],check=True)
    subprocess.run(['java','-jar',str(tool),'verify','--verbose','--print-certs',str(output)],check=True)
    with output.open('rb') as stream: digest=hashlib.file_digest(stream,'sha256').hexdigest()
    print(json.dumps({'apk':str(output),'sha256':digest,'bytes':output.stat().st_size,'signature_verified':True}))


if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--input',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--apksigner',type=Path,required=True)
    p.add_argument('--keydir',type=Path,required=True)
    p.add_argument('--initialize',action='store_true')
    a=p.parse_args()
    if a.initialize: initialize(a.keydir.resolve())
    sign(a.input.resolve(),a.output.resolve(),a.apksigner.resolve(),a.keydir.resolve())
