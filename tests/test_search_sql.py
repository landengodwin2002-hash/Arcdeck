"""SQLite FTS4/index contract smoke test, using the same DDL and query shape as UnifiedCatalog."""
import sqlite3

def main():
    c = sqlite3.connect(':memory:')
    c.executescript('''
        PRAGMA foreign_keys = ON;
        CREATE TABLE records(key TEXT PRIMARY KEY NOT NULL,kind TEXT NOT NULL,title TEXT NOT NULL,subtitle TEXT NOT NULL DEFAULT '',target TEXT NOT NULL DEFAULT '',source TEXT NOT NULL,missing INTEGER NOT NULL DEFAULT 0,source_favorite INTEGER NOT NULL DEFAULT 0,seen INTEGER NOT NULL DEFAULT 0);
        CREATE VIRTUAL TABLE record_fts USING fts4(key,title,subtitle, tokenize=unicode61);
        CREATE TABLE metadata(key TEXT PRIMARY KEY REFERENCES records(key) ON DELETE CASCADE,alias TEXT,favorite INTEGER NOT NULL DEFAULT 0,pinned INTEGER NOT NULL DEFAULT 0,manual_order INTEGER NOT NULL DEFAULT 0,last_opened INTEGER NOT NULL DEFAULT 0,note TEXT NOT NULL DEFAULT '');
        CREATE TABLE tags(key TEXT NOT NULL REFERENCES records(key) ON DELETE CASCADE,tag TEXT NOT NULL,PRIMARY KEY(key,tag));
        CREATE TABLE collections(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE COLLATE NOCASE,expression TEXT);
        CREATE TABLE memberships(collection_id INTEGER NOT NULL REFERENCES collections(id) ON DELETE CASCADE,key TEXT NOT NULL REFERENCES records(key) ON DELETE CASCADE,position INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(collection_id,key));
        CREATE TABLE game_files(game_key TEXT PRIMARY KEY REFERENCES records(key) ON DELETE CASCADE,rom_uri TEXT,rom_path TEXT);
    ''')
    games = [('pfp-game:1','GAME','Final Fantasy VII','PS1 RPG','1','pfp',0,1,1),('android:com.termux','APP','Termux','com.termux','com.termux','android',0,0,1),('file:some-uri','FILE','ff7.cue','Game image','some-uri','saf:root',0,0,1),('file:other','FILE','Lost Game','Game image','other','saf:root',1,0,1)]
    c.executemany('INSERT INTO records VALUES(?,?,?,?,?,?,?,?,?)',games)
    c.executemany('INSERT INTO record_fts(key,title,subtitle) VALUES(?,?,?)',[(x[0],x[2],x[3]) for x in games])
    c.execute('INSERT INTO game_files VALUES(?,?,?)',('pfp-game:1','some-uri','/games/ff7.cue'))
    c.execute('INSERT INTO tags VALUES(?,?)',('pfp-game:1','rpg'))
    c.execute('INSERT INTO collections(name,expression) VALUES(?,NULL)',('My Games',))
    c.execute('INSERT INTO memberships VALUES(?,?,?)',(1,'pfp-game:1',5))
    sql = '''SELECT r.key FROM records r LEFT JOIN metadata m ON m.key=r.key
    JOIN record_fts f ON f.key=r.key
    WHERE record_fts MATCH ? AND r.kind IN (?) AND r.missing=0
    AND EXISTS(SELECT 1 FROM tags t WHERE t.key=r.key AND t.tag=?)
    ORDER BY COALESCE(m.pinned,0) DESC, (COALESCE(m.favorite,0) | r.source_favorite) DESC, r.title COLLATE NOCASE LIMIT ? OFFSET ?'''
    assert c.execute(sql,('final* AND fantasy*','GAME','rpg','100','0')).fetchall() == [('pfp-game:1',)]
    sql2 = '''SELECT r.key FROM records r LEFT JOIN metadata m ON m.key=r.key
    WHERE r.missing=0 AND NOT (r.kind='FILE' AND EXISTS(SELECT 1 FROM game_files gf WHERE gf.rom_uri=r.target))
    ORDER BY r.title LIMIT ? OFFSET ?'''
    assert set(x[0] for x in c.execute(sql2,('100','0')).fetchall()) == {'pfp-game:1','android:com.termux'}
    sql3 = '''SELECT r.key FROM records r LEFT JOIN metadata m ON m.key=r.key WHERE
    EXISTS(SELECT 1 FROM memberships mm JOIN collections cc ON cc.id=mm.collection_id WHERE mm.key=r.key AND (CAST(cc.id AS TEXT)=? OR cc.name=?))
    AND r.missing=0 ORDER BY COALESCE((SELECT mm.position FROM memberships mm JOIN collections cc ON cc.id=mm.collection_id WHERE mm.key=r.key AND (CAST(cc.id AS TEXT)=? OR cc.name=?) LIMIT 1),0) ASC, r.title LIMIT ? OFFSET ?'''
    assert c.execute(sql3,('1','1','1','1','100','0')).fetchall() == [('pfp-game:1',)]
    c.execute('UPDATE records SET missing=1 WHERE source=? AND seen<>?',('pfp',1))
    assert c.execute('SELECT missing FROM records WHERE key=?',('pfp-game:1',)).fetchone()[0] == 0
    print('PASS: SQLite FTS, tag AND filter, ROM dedupe, manual collection and missing-source policy')

if __name__ == '__main__': main()
