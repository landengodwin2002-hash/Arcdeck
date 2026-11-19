package com.playfieldportal.launcher.workspace

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject

internal data class PhoneGameFile(val id: String, val uri: String, val name: String, val platform: String,
    val size: Long, val modified: Long, val kind: String = "ROM")

/** A virtual, sorted ROM-root view. It refers to originals and never relocates a game file. */
internal class WorkspacePhoneStore(context: Context) : SQLiteOpenHelper(context, "arcdeck_phone_library.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE files(id TEXT PRIMARY KEY,uri TEXT NOT NULL,name TEXT NOT NULL,platform TEXT NOT NULL,size INTEGER NOT NULL,modified INTEGER NOT NULL,kind TEXT NOT NULL,seen INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX files_platform ON files(platform,kind)")
        db.execSQL("CREATE TABLE pending(id INTEGER PRIMARY KEY AUTOINCREMENT,uri TEXT UNIQUE NOT NULL,depth INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE state(name TEXT PRIMARY KEY,value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE visited(identity TEXT PRIMARY KEY)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun getState(name: String, default: String = "") = readableDatabase.rawQuery("SELECT value FROM state WHERE name=?", arrayOf(name)).use { if (it.moveToFirst()) it.getString(0) else default }
    fun setState(name: String, value: String) { writableDatabase.execSQL("INSERT OR REPLACE INTO state(name,value) VALUES(?,?)", arrayOf(name, value)) }
    fun pending(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM pending", null).use { it.moveToFirst(); it.getInt(0) }
    fun begin(roots: List<String>): Long {
        val generation = maxOf(System.currentTimeMillis(), getState("generation", "0").toLong() + 1)
        val db = writableDatabase; db.beginTransaction()
        try { db.delete("pending", null, null); db.delete("visited", null, null); roots.distinct().forEach { enqueue(it, 0) }; setState("sources", JSONArray(roots.map { raw -> val uri = Uri.parse(raw); DocumentsContract.buildTreeDocumentUri(requireNotNull(uri.authority), DocumentsContract.getTreeDocumentId(uri)).toString() }).toString()); setState("generation", "$generation"); setState("errors", "0"); setState("visited", "0"); db.setTransactionSuccessful() }
        finally { db.endTransaction() }
        return generation
    }
    fun enqueue(uri: String, depth: Int) {
        writableDatabase.insertWithOnConflict("pending", null, ContentValues().apply { put("uri", uri); put("depth", depth) }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun next(): Pair<String, Int>? = readableDatabase.rawQuery("SELECT uri,depth FROM pending ORDER BY id LIMIT 1", null).use { if (it.moveToFirst()) it.getString(0) to it.getInt(1) else null }
    fun done(uri: String) { writableDatabase.delete("pending", "uri=?", arrayOf(uri)) }
    fun visited(identity: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM visited WHERE identity=?", arrayOf(identity)).use { it.moveToFirst() }
    fun visit(identity: String) { writableDatabase.execSQL("INSERT OR IGNORE INTO visited(identity) VALUES(?)", arrayOf(identity)) }
    fun upsert(file: PhoneGameFile, generation: Long) {
        writableDatabase.insertWithOnConflict("files", null, ContentValues().apply {
            put("id", file.id); put("uri", file.uri); put("name", file.name); put("platform", file.platform)
            put("size", file.size); put("modified", file.modified); put("kind", file.kind); put("seen", generation)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun finish(trusted: Boolean, generation: Long) {
        if (trusted) {
            val sources = JSONArray(getState("sources", "[]"))
            for (i in 0 until sources.length()) {
                val prefix = sources.getString(i).trimEnd('/') + "/document/"
                writableDatabase.delete("files", "seen<>? AND substr(uri,1,?)=?", arrayOf("$generation", "${prefix.length}", prefix))
            }
        }
        setState("completed", "${System.currentTimeMillis()}")
    }
    fun stale(generation: Long): List<Pair<String, String>> {
        val sources = JSONArray(getState("sources", "[]")); val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until sources.length()) {
            val prefix = sources.getString(i).trimEnd('/') + "/document/"
            readableDatabase.rawQuery("SELECT id,uri FROM files WHERE seen<>? AND substr(uri,1,?)=?", arrayOf("$generation", "${prefix.length}", prefix)).use { c -> while (c.moveToNext()) out.add(c.getString(0) to c.getString(1)) }
        }
        return out.distinct()
    }
    fun seen(id: String, generation: Long): Boolean = readableDatabase.rawQuery("SELECT 1 FROM files WHERE id=? AND seen=?", arrayOf(id, "$generation")).use { it.moveToFirst() }
    fun platforms(): List<String> = readableDatabase.rawQuery("SELECT DISTINCT platform FROM files WHERE kind='ROM' AND platform<>'' ORDER BY platform", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    fun files(platform: String? = null, kind: String? = null, limit: Int = 20001): List<PhoneGameFile> {
        val clauses = mutableListOf<String>(); val args = mutableListOf<String>()
        platform?.let { clauses.add("platform=?"); args.add(it) }; kind?.let { clauses.add("kind=?"); args.add(it) }
        return readableDatabase.rawQuery("SELECT id,uri,name,platform,size,modified,kind FROM files" + (if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")) + " ORDER BY name COLLATE NOCASE LIMIT ${limit.coerceIn(1,20001)}", args.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(PhoneGameFile(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5), c.getString(6))) }
        }
    }
    fun get(id: String): PhoneGameFile? = readableDatabase.rawQuery("SELECT id,uri,name,platform,size,modified,kind FROM files WHERE id=?", arrayOf(id)).use { c ->
        if (!c.moveToFirst()) null else PhoneGameFile(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5), c.getString(6)) }
    fun count(kind: String): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM files WHERE kind=?", arrayOf(kind)).use { it.moveToFirst(); it.getInt(0) }
    companion object {
        const val ROOT = "auto"
        fun authority(context: Context) = "${context.packageName}.storage"
        fun tree(context: Context, id: String = ROOT): Uri = DocumentsContract.buildTreeDocumentUri(authority(context), id)
        fun doc(context: Context, id: String): Uri = DocumentsContract.buildDocumentUri(authority(context), id)
        fun sourceId(uri: Uri): String = "source:" + android.util.Base64.encodeToString(uri.toString().toByteArray(), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
    }
}
