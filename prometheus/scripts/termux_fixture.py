#!/usr/bin/env python3
"""Create old Termux schemas only on a clean disposable acceptance host."""
import hashlib
import json
from pathlib import Path
import sqlite3

root = Path.home() / 'local-ai'
if root.exists():
    raise SystemExit('Termux fixture requires a clean test host; existing local-ai data was left untouched')
app = root / 'prometheus'
app.mkdir(parents=True)
fixtures = {
    app / 'prometheus_chats.sqlite3': "CREATE TABLE chats(id INTEGER PRIMARY KEY, role TEXT, content TEXT); INSERT INTO chats VALUES(1,'user','Termux question'),(2,'assistant','Termux chat retained');",
    app / 'memory.sqlite3': "CREATE TABLE entries(id INTEGER PRIMARY KEY, kind TEXT, title TEXT, content TEXT); INSERT INTO entries VALUES(1,'fact','Termux','Reuse this memory');",
    root / 'books.sqlite3': "CREATE TABLE passages(source TEXT, body TEXT); INSERT INTO passages VALUES('termux-original.txt','Termux indexed passage retained');",
    app / 'companion.sqlite3': "CREATE TABLE corrections(id INTEGER PRIMARY KEY, text TEXT); INSERT INTO corrections VALUES(1,'Termux correction retained'); CREATE TABLE projects(id INTEGER PRIMARY KEY, name TEXT, description TEXT); INSERT INTO projects VALUES(1,'Termux project','Termux project retained'); CREATE TABLE project_notes(id INTEGER PRIMARY KEY, project_id INTEGER, note TEXT); INSERT INTO project_notes VALUES(1,1,'Termux note retained'); CREATE TABLE project_tasks(id INTEGER PRIMARY KEY, project_id INTEGER, task TEXT, done INTEGER); INSERT INTO project_tasks VALUES(1,1,'Termux task retained',1);",
}
for path, sql in fixtures.items():
    with sqlite3.connect(path) as db:
        db.executescript(sql)
hashes = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in fixtures}
Path('test-results/termux-source-hashes.json').write_text(json.dumps(hashes, indent=2))
print('Created original Termux database schemas for real transfer acceptance')
