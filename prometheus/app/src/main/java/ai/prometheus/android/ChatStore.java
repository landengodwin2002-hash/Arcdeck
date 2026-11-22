package ai.prometheus.android;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;

/** Existing Nova data, with additive setup only. Never drops a user table. */
final class ChatStore {
    private static SQLiteDatabase database;
    static synchronized SQLiteDatabase open(Context context) {
        if (database != null && database.isOpen()) return database;
        database = context.getApplicationContext().openOrCreateDatabase("prometheus-native.db", 0, null);
        database.enableWriteAheadLogging();
        database.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS passages USING fts4(book, body, tokenize=unicode61)");
        database.execSQL("CREATE TABLE IF NOT EXISTS messages(id INTEGER PRIMARY KEY, role TEXT, body TEXT)");
        database.execSQL("CREATE TABLE IF NOT EXISTS memory(id INTEGER PRIMARY KEY, kind TEXT, body TEXT)");
        database.execSQL("CREATE TABLE IF NOT EXISTS projects(id INTEGER PRIMARY KEY, name TEXT UNIQUE, description TEXT, created INTEGER)");
        database.execSQL("CREATE TABLE IF NOT EXISTS project_items(id INTEGER PRIMARY KEY, project_id INTEGER, kind TEXT, body TEXT, done INTEGER DEFAULT 0, created INTEGER)");
        database.execSQL("CREATE TABLE IF NOT EXISTS book_files(id INTEGER PRIMARY KEY, title TEXT, sha256 TEXT UNIQUE, path TEXT, indexed_at INTEGER)");
        database.execSQL("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT)");
        database.execSQL("CREATE TABLE IF NOT EXISTS imported_migrations(sha256 TEXT PRIMARY KEY, imported INTEGER)");
        database.execSQL("CREATE TABLE IF NOT EXISTS imported_rows(fingerprint TEXT PRIMARY KEY)");
        database.execSQL("CREATE TABLE IF NOT EXISTS generation_state(id INTEGER PRIMARY KEY CHECK(id=1), prompt TEXT, partial TEXT, stage TEXT, started INTEGER)");
        return database;
    }

    private final SQLiteDatabase db;
    ChatStore(Context context) { db = open(context); }
    String setting(String key, String fallback) {
        try (Cursor c = db.rawQuery("SELECT value FROM settings WHERE key=?", new String[]{key})) {
            return c.moveToFirst() ? c.getString(0) : fallback;
        }
    }
    void putSetting(String key, String value) {
        db.execSQL("INSERT OR REPLACE INTO settings(key,value) VALUES(?,?)", new Object[]{key, value});
    }
    void begin(String question) {
        db.beginTransaction();
        try {
            db.execSQL("INSERT INTO messages(role,body) VALUES('user',?)", new Object[]{question});
            db.execSQL("INSERT OR REPLACE INTO generation_state(id,prompt,partial,stage,started) VALUES(1,?,'','Preparing response',?)",
                    new Object[]{question, System.currentTimeMillis()});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    void partial(String body, String stage) {
        db.execSQL("UPDATE generation_state SET partial=?,stage=? WHERE id=1", new Object[]{body, stage});
    }
    void finish(String body, String error, boolean stopped) {
        // Commit the reply and remove its recovery record together, without a UI callback.
        db.beginTransaction();
        try {
            if (!body.trim().isEmpty()) db.execSQL("INSERT INTO messages(role,body) VALUES('assistant',?)", new Object[]{body});
            if (error != null) db.execSQL("INSERT INTO messages(role,body) VALUES('error',?)", new Object[]{error});
            else if (body.trim().isEmpty() && stopped) db.execSQL("INSERT INTO messages(role,body) VALUES('error','(Stopped)')");
            db.execSQL("DELETE FROM generation_state WHERE id=1");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    JSONArray history(String profile) throws Exception {
        JSONArray messages = new JSONArray();
        String system = "You are Prometheus, an offline assistant. Answer directly. Do not claim actions you have not performed.";
        if (!profile.trim().isEmpty()) system += "\n" + profile;
        messages.put(new JSONObject().put("role", "system").put("content", system));
        String boundary = setting("conversation_start", "0");
        try (Cursor c = db.rawQuery("SELECT role,body FROM (SELECT id,role,body FROM messages WHERE id>? AND role IN ('user','assistant') ORDER BY id DESC LIMIT 100) ORDER BY id",
                new String[]{boundary})) {
            while (c.moveToNext()) messages.put(new JSONObject().put("role", c.getString(0)).put("content", c.getString(1)));
        }
        return messages;
    }
    void archiveChat() {
        try (Cursor c = db.rawQuery("SELECT COALESCE(MAX(id),0) FROM messages", null)) {
            c.moveToFirst(); putSetting("conversation_start", c.getString(0));
        }
    }
}
