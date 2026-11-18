-- Frozen database schema from ArcDeck debug build 667e3e2
CREATE TABLE records(
            key TEXT PRIMARY KEY NOT NULL,
            kind TEXT NOT NULL,
            title TEXT NOT NULL,
            subtitle TEXT NOT NULL DEFAULT '',
            target TEXT NOT NULL DEFAULT '',
            source TEXT NOT NULL,
            missing INTEGER NOT NULL DEFAULT 0,
            source_favorite INTEGER NOT NULL DEFAULT 0,
            source_recent INTEGER NOT NULL DEFAULT 0,
            seen INTEGER NOT NULL DEFAULT 0
        );
CREATE INDEX records_source ON records(source);
CREATE INDEX records_kind ON records(kind,missing);
CREATE TABLE game_files(game_key TEXT PRIMARY KEY REFERENCES records(key) ON DELETE CASCADE,
            rom_uri TEXT, rom_path TEXT);
CREATE INDEX games_by_uri ON game_files(rom_uri);
CREATE VIRTUAL TABLE record_fts USING fts4(key, title, subtitle, tokenize=unicode61);
CREATE TABLE metadata(
            key TEXT PRIMARY KEY NOT NULL REFERENCES records(key) ON DELETE CASCADE,
            alias TEXT,
            favorite INTEGER NOT NULL DEFAULT 0,
            pinned INTEGER NOT NULL DEFAULT 0,
            manual_order INTEGER NOT NULL DEFAULT 0,
            last_opened INTEGER NOT NULL DEFAULT 0,
            note TEXT NOT NULL DEFAULT ''
        );
CREATE TABLE tags(
            key TEXT NOT NULL REFERENCES records(key) ON DELETE CASCADE,
            tag TEXT NOT NULL, PRIMARY KEY(key,tag));
CREATE INDEX tag_lookup ON tags(tag,key);
CREATE TABLE collections(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE COLLATE NOCASE,
            expression TEXT
        );
CREATE TABLE memberships(
            collection_id INTEGER NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
            key TEXT NOT NULL REFERENCES records(key) ON DELETE CASCADE,
            position INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(collection_id,key));
CREATE TABLE roots(
            uri TEXT PRIMARY KEY, label TEXT NOT NULL, last_scan INTEGER NOT NULL DEFAULT 0,
            status TEXT NOT NULL DEFAULT 'Not scanned');
