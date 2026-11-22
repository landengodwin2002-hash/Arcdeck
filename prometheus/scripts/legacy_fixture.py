#!/usr/bin/env python3
"""Create pre-update Nova data before the new application first opens its DB."""
import hashlib
from pathlib import Path
import sqlite3

root = Path('test-results')
root.mkdir(exist_ok=True)
database = root / 'legacy.db'
database.unlink(missing_ok=True)
book = root / 'legacy-original.txt'
book.write_bytes(b'Original Nova book: keep these exact bytes.\n')
digest = hashlib.sha256(book.read_bytes()).hexdigest()
with sqlite3.connect(database) as db:
    # Schema copied from Nova 1.6, before introducing the replacement runtime.
    db.executescript('''
        CREATE VIRTUAL TABLE passages USING fts4(book, body, tokenize=unicode61);
        CREATE TABLE messages(id INTEGER PRIMARY KEY, role TEXT, body TEXT);
        CREATE TABLE memory(id INTEGER PRIMARY KEY, kind TEXT, body TEXT);
        CREATE TABLE projects(id INTEGER PRIMARY KEY, name TEXT UNIQUE, description TEXT, created INTEGER);
        CREATE TABLE project_items(id INTEGER PRIMARY KEY, project_id INTEGER, kind TEXT, body TEXT, done INTEGER DEFAULT 0, created INTEGER);
        CREATE TABLE book_files(id INTEGER PRIMARY KEY, title TEXT, sha256 TEXT UNIQUE, path TEXT, indexed_at INTEGER);
        CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT);
        CREATE TABLE imported_migrations(sha256 TEXT PRIMARY KEY, imported INTEGER);
        CREATE TABLE imported_rows(fingerprint TEXT PRIMARY KEY);
        CREATE TABLE generation_state(id INTEGER PRIMARY KEY CHECK(id=1), prompt TEXT, partial TEXT, stage TEXT, started INTEGER);
        INSERT INTO messages(role,body) VALUES('user','Legacy question'),('assistant','Legacy answer retained');
        INSERT INTO memory(kind,body) VALUES('legacy','Keep my original memory');
        INSERT INTO projects VALUES(1,'Legacy project','Keep my project',1);
        INSERT INTO project_items VALUES(1,1,'note','Keep my project note',0,1);
        INSERT INTO passages(book,body) VALUES('Legacy original','Original searchable passage');
    ''')
    db.execute('INSERT INTO book_files(title,sha256,path,indexed_at) VALUES(?,?,?,1)',
               ('Legacy original', digest, 'files/books/legacy-original.txt'))
print('Created Nova 1.6 database and original-book preservation fixture')
