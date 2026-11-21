#!/data/data/com.termux/files/usr/bin/python
"""Make an offline JSONL migration from an existing Termux Prometheus installation.
No network, no deletion, no passwords/API keys. Writes local document text, notes and chats.
"""
from __future__ import annotations
from pathlib import Path
import json,sqlite3,sys,time

ROOT=Path.home()/'local-ai'
APP=ROOT/'prometheus'
DEST=Path.home()/'storage'/'downloads'/'Prometheus-Native-Migration.jsonl'

def table(db,name):
    return bool(db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",(name,)).fetchone())

def rows(path, sql, source):
    if not path.is_file():return
    try:
        with sqlite3.connect(f'file:{path}?mode=ro',uri=True) as con:
            if not table(con, source):return
            for row in con.execute(sql): yield row
    except sqlite3.DatabaseError as e:
        print(f'Skipping damaged database {path.name}: {e}',file=sys.stderr)

def main():
    DEST.parent.mkdir(parents=True,exist_ok=True)
    counts={}
    with DEST.open('w',encoding='utf-8') as f:
        def add(kind,**kw):
            f.write(json.dumps({'type':kind,**kw},ensure_ascii=False)+'\n')
            counts[kind]=counts.get(kind,0)+1
        add('manifest',schema=1,created=int(time.time()),source='Prometheus Termux')
        for role,body in rows(APP/'prometheus_chats.sqlite3','SELECT role,content FROM chats ORDER BY id','chats') or []:
            if role in {'user','assistant','system'} and body:add('message',role=role,body=body)
        for kind,title,body in rows(ROOT/'memory.sqlite3','SELECT kind,title,content FROM entries ORDER BY id','entries') or []:
            content=(title+' — ' if title else '')+(body or '')
            if content:add('memory',kind=kind or 'fact',body=content)
        for (correction,) in rows(APP/'companion.sqlite3','SELECT text FROM corrections ORDER BY id','corrections') or []:
            if correction:add('memory',kind='correction',body=correction)
        for pid,name,description in rows(APP/'companion.sqlite3','SELECT id,name,description FROM projects ORDER BY id','projects') or []:
            add('project',name=name,description=description or '')
            for (note,) in rows(APP/'companion.sqlite3',f'SELECT note FROM project_notes WHERE project_id={int(pid)} ORDER BY id','project_notes') or []:
                add('project_item',project=name,kind='note',body=note)
            for task,done in rows(APP/'companion.sqlite3',f'SELECT task,done FROM project_tasks WHERE project_id={int(pid)} ORDER BY id','project_tasks') or []:
                add('project_item',project=name,kind='task',body=task,done=int(bool(done)))
        for book,body in rows(ROOT/'books.sqlite3','SELECT source,body FROM passages','passages') or []:
            if body:add('passage',book=Path(book).name,body=body)
    print(f'Migration saved: {DEST}')
    print('Record counts:',counts)
    print('This file may contain private chats or notes. Keep it on your phone.')
    print('In native Prometheus, open Settings > Import Termux migration and select this file.')

if __name__=='__main__':main()
