#!/data/data/com.termux/files/usr/bin/python
"""Transfer Prometheus model, memories and books from Termux into independent Android APK.
No ADB, root, manual Android file picker, or persistent local server needed.
Launch the native APK after running this command; the app contacts this short-lived sender.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import sqlite3
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

PORT = 18765
HOME = Path.home()
ROOT = HOME / 'local-ai'
APP = ROOT / 'prometheus'
MODELROOT = ROOT / 'models'
MIGRATION = APP / 'native-sync-migration.jsonl'
BOOK_EXTS = {'.pdf','.epub','.docx','.odt','.txt','.md','.fb2','.rtf'}
SKIP_DIRS = {'android','games','game','mods','assets','textures','node_modules','.git','__pycache__','.cache','.gradle', 'build','dist', 'venv','.venv'}
BOOK_LIMIT=256*1024*1024
MAX_BOOKS=1500
MAX_SECONDS=45*60


def database_rows(file:Path,sql:str,table:str):
    if not file.is_file(): return
    try:
        with sqlite3.connect(f'file:{file}?mode=ro', uri=True, timeout=2) as con:
            exists=con.execute('SELECT 1 FROM sqlite_master WHERE type=? AND name=?',('table',table)).fetchone()
            if not exists:return
            yield from con.execute(sql)
    except sqlite3.DatabaseError as e:
        print(f'[skip database] {file.name}: {e}',file=sys.stderr)


def prepare_migration():
    MIGRATION.parent.mkdir(parents=True,exist_ok=True)
    temp=MIGRATION.with_suffix('.partial')
    count={}
    with temp.open('w',encoding='utf-8') as out:
        def add(typ, **fields):
            out.write(json.dumps({'type':typ,**fields},ensure_ascii=False)+'\n')
            count[typ]=count.get(typ,0)+1
        add('manifest',schema=1,source='Prometheus Termux',created=int(time.time()))
        for role,body in database_rows(APP/'prometheus_chats.sqlite3','SELECT role,content FROM chats ORDER BY id','chats') or ():
            if role in ('user','assistant') and body: add('message',role=role,body=body)
        for kind,title,body in database_rows(ROOT/'memory.sqlite3','SELECT kind,title,content FROM entries ORDER BY id','entries') or ():
            content=(str(title)+' — ' if title else '')+str(body or '')
            if content:add('memory',kind=kind or 'fact',body=content)
        for (correction,) in database_rows(APP/'companion.sqlite3','SELECT text FROM corrections ORDER BY id','corrections') or ():
            if correction:add('memory',kind='correction',body=correction)
        for pid,name,description in database_rows(APP/'companion.sqlite3','SELECT id,name,description FROM projects ORDER BY id','projects') or ():
            add('project',name=name,description=description or '')
            for (note,) in database_rows(APP/'companion.sqlite3',f'SELECT note FROM project_notes WHERE project_id={int(pid)} ORDER BY id','project_notes') or ():
                if note:add('project_item',project=name,kind='note',body=note)
            for task,done in database_rows(APP/'companion.sqlite3',f'SELECT task,done FROM project_tasks WHERE project_id={int(pid)} ORDER BY id','project_tasks') or ():
                if task:add('project_item',project=name,kind='task',body=task,done=int(bool(done)))
        for book,body in database_rows(ROOT/'books.sqlite3','SELECT source,body FROM passages','passages') or ():
            if body:add('passage',book=Path(book).name,body=body)
    temp.replace(MIGRATION)
    return count


def digest(p:Path):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()


def get_model(explicit):
    if explicit:
        p=Path(explicit).expanduser()
        choices=[p]
    else:
        choices=sorted(MODELROOT.glob('*.gguf')) if MODELROOT.is_dir() else []
    candidates=[]
    for p in choices:
        if not p.is_file() or not (p.name.lower().endswith('.gguf') and ('abliterat' in p.name.lower() or 'obliterat' in p.name.lower())):
            continue
        try:
            with p.open('rb') as f: good=(f.read(4)==b'GGUF')
            if good: candidates.append(p)
        except OSError:pass
    if not candidates:return None
    candidates.sort(key=lambda p:(0 if 'huihui' in p.name.lower() and ('4b' in p.name.lower() or '3.5-4b' in p.name.lower()) else 1,p.stat().st_size))
    return candidates[0]


def scan_books(extra_paths:list[str]):
    folders=[ROOT/'knowledge'/'originals',ROOT/'knowledge'/'downloaded',
             HOME/'storage'/'shared'/'Documents'/'PrometheusBooks',
             HOME/'storage'/'shared'/'Books',HOME/'storage'/'downloads'/'PrometheusBooks',
             HOME/'storage'/'downloads',HOME/'storage'/'shared'/'Documents']
    folders += [Path(p).expanduser() for p in extra_paths]
    visited=set();book_count=0
    for folder in folders:
        if not folder.exists():continue
        iterable=[]
        if folder.is_file(): iterable=[folder]
        else:
            # At the top of ordinary Downloads, only include root-level books and explicit book folders.
            for base,dirs,files in os.walk(folder):
                depth=len(Path(base).relative_to(folder).parts)
                if depth>=4:dirs[:]=[]
                else:dirs[:]=[d for d in sorted(dirs) if d.lower() not in SKIP_DIRS and not d.startswith('.')]
                for name in sorted(files):
                    p=Path(base)/name
                    if p.suffix.lower() in BOOK_EXTS and not p.name.startswith('.'):
                        iterable.append(p)
                if len(iterable)>MAX_BOOKS*3:break
        for p in iterable:
            try:
                full=p.resolve()
                if full in visited or p.suffix.lower() not in BOOK_EXTS or not p.is_file():continue
                visited.add(full)
                size=p.stat().st_size
                if size==0 or size>BOOK_LIMIT:continue
            except (OSError,ValueError):continue
            book_count+=1
            yield p
            if book_count>=MAX_BOOKS:return


def main():
    ap=argparse.ArgumentParser(description='Provision native Prometheus entirely from Termux; offline afterwards')
    g=ap.add_mutually_exclusive_group()
    g.add_argument('--model-only',action='store_true',help='Send only an abliterated GGUF')
    g.add_argument('--books-only',action='store_true',help='Send only library books')
    g.add_argument('--memory-only',action='store_true',help='Send chats, projects, corrections and indexed passages')
    ap.add_argument('--model',help='Explicit existing abliterated GGUF path')
    ap.add_argument('--include',action='append',default=[],metavar='BOOK_FOLDER',help='Extra book folder (repeatable)')
    ap.add_argument('--dry-run',action='store_true',help='Show what would be sent, without starting server')
    ap.add_argument('--status',action='store_true',help='Show available Termux model/book sources')
    ap.add_argument('--diagnostics',action='store_true',help='Read last native APK diagnostic report; captured on sync')
    args=ap.parse_args()
    if args.diagnostics:
        diag=APP/'native-diagnostics.json'
        print(diag.read_text(encoding='utf8') if diag.is_file() else 'No native diagnostics yet. Run prometheus-sync, open the APK, then retry this command.')
        return 0
    send_model=not(args.books_only or args.memory_only)
    send_books=not(args.model_only or args.memory_only)
    send_memory=not(args.model_only or args.books_only)
    if args.status:
        model=get_model(args.model)
        print('Selected abliterated model:',model or '(none found)')
        print('Termux library source files:',sum(1 for _ in scan_books(args.include)))
        print('Native app ID: ai.prometheus.offline.pet; model is private to native app after sync')
        return 0
    sources=[]
    if send_memory:
        count=prepare_migration()
        print('Memory export:',', '.join(f'{n} {k}' for k,n in count.items()),flush=True)
        sources.append(('migration', MIGRATION, 'Prometheus-Native-Migration.jsonl'))
    if send_books:
        for f in scan_books(args.include):sources.append(('book',f,f.name))
    if send_model:
        model=get_model(args.model)
        if model:sources.append(('model',model,model.name))
        else:print('WARNING: No locally accessible abliterated GGUF. Model not sent.',file=sys.stderr)
    if not sources:
        print('No model, migration or books to transfer. Try: prometheus-sync --status',file=sys.stderr)
        return 2
    print(f'Preparing {len(sources)} transfer file(s); verifying SHA-256 fingerprints...',flush=True)
    entries=[];pmap={};seen=set()
    for kind,p,display in sources:
        try:
            h=digest(p);size=p.stat().st_size
        except OSError as e:
            print(f'Skipping missing file {p}: {e}',file=sys.stderr);continue
        key=(kind,h)
        if key in seen:continue
        seen.add(key);ident=str(len(entries)+1)
        entries.append({'id':ident,'kind':kind,'name':display,'size':size,'sha256':h})
        pmap[ident]=p
    print(f'Ready: {sum(e["kind"]=="model" for e in entries)} model(s), '+
          f'{sum(e["kind"]=="book" for e in entries)} book(s), '+
          f'{sum(e["kind"]=="migration" for e in entries)} migration(s).',flush=True)
    print(f'Total: {sum(e["size"] for e in entries)/(1024**3):.2f} GiB. Termux files will NOT be deleted.')
    if args.dry_run:return 0
    if len(entries)>3000:print('Too many transfer items',file=sys.stderr);return 2
    complete=threading.Event();result={}
    manifest=json.dumps({'protocol':'prometheus-local-transfer-v1','items':entries},ensure_ascii=False).encode('utf8')
    if len(manifest)>2_000_000:print('Manifest too large',file=sys.stderr);return 2

    class Handler(BaseHTTPRequestHandler):
        def log_message(self,format,*args):pass
        def do_GET(self):
            path=urlparse(self.path).path
            if path=='/manifest':
                self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(manifest)));self.end_headers()
                self.wfile.write(manifest);return
            if path.startswith('/item/'):
                ident=path.split('/')[-1]
                item=pmap.get(ident)
                if item is None:self.send_error(404);return
                self.send_response(200);self.send_header('Content-Type','application/octet-stream');self.send_header('Content-Length',str(item.stat().st_size));self.end_headers()
                try:
                    with item.open('rb') as source:
                        for chunk in iter(lambda:source.read(256*1024),b''):
                            self.wfile.write(chunk)
                except (BrokenPipeError,ConnectionResetError):pass
                return
            self.send_error(404)
        def do_POST(self):
            if urlparse(self.path).path=='/diagnostics':
                try:
                    size=int(self.headers.get('Content-Length','0'))
                    if size<=0 or size>8192:raise ValueError('Invalid diagnostics length')
                    report=json.loads(self.rfile.read(size))
                    APP.mkdir(parents=True,exist_ok=True)
                    (APP/'native-diagnostics.json').write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding='utf8')
                    self.send_response(200);self.end_headers();self.wfile.write(b'ok')
                except Exception:self.send_error(400)
                return
            if urlparse(self.path).path!='/done':self.send_error(404);return
            try:
                size=int(self.headers.get('Content-Length','0'))
                if size<=0 or size>8192:raise ValueError('Bad confirmation length')
                result.update(json.loads(self.rfile.read(size)))
                complete.set();self.send_response(200);self.end_headers();self.wfile.write(b'ok')
            except Exception:self.send_error(400)

    try:
        server=ThreadingHTTPServer(('127.0.0.1',PORT),Handler)
    except OSError as e:
        print(f'Cannot start local transfer on 127.0.0.1:{PORT}: {e}',file=sys.stderr)
        print('Check for another running prometheus-sync command.',file=sys.stderr)
        return 2
    t=threading.Thread(target=server.serve_forever,kwargs={'poll_interval':0.2},daemon=True)
    t.start()
    print('\nLOCAL TRANSFER READY. Open the installed Prometheus APK now.',flush=True)
    print('It will automatically import when you return to it. No file picking or pairing.',flush=True)
    print('Do not close Termux until it reports completion. Works without internet.',flush=True)
    try:
        if not complete.wait(timeout=MAX_SECONDS):
            print('Timed out. Run prometheus-sync again when ready.',file=sys.stderr)
            return 3
        print('\nApp reported:',json.dumps(result,ensure_ascii=False),flush=True)
        if not result.get('success',False) or result.get('errors',0):return 4
        return 0
    except KeyboardInterrupt:
        print('\nTermux transfer cancelled. No originals deleted.')
        return 130
    finally:
        server.shutdown();server.server_close()

if __name__=='__main__':raise SystemExit(main())
