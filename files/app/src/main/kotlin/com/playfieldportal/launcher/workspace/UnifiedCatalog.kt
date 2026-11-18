package com.playfieldportal.launcher.workspace

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameContentType
import java.util.ArrayDeque
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * A non-destructive, unified INDEX of PFP game rows + Android apps + SAF documents + user actions.
 * PFP's GameRepository owns games. Android PackageManager owns apps. DocumentProviders own files.
 * We store only search mirrors, relationships, tags, aliases and user-defined actions.
 * Indexing cannot delete user data or PFP's game artwork/progress/emulator configurations.
 */
internal data class CatalogEntry(
    val key: String,
    val kind: String,
    val title: String,
    val subtitle: String,
    val target: String,
    val source: String,
    val missing: Boolean = false,
    val sourceFavorite: Boolean = false,
    val sourceRecent: Long = 0,
    val alias: String? = null,
    val favorite: Boolean = false,
    val pinned: Boolean = false,
    val lastOpened: Long = 0,
)

internal data class CatalogCollection(val id: Long, val name: String, val expression: String?)
internal data class CatalogRoot(val uri: String, val label: String, val lastScan: Long, val status: String)
internal data class ScanReport(val indexed: Int, val complete: Boolean, val status: String)

internal class UnifiedCatalog(context: Context) : SQLiteOpenHelper(context.applicationContext,
    "arcdeck_unified_v1.db", null, 1) {
    private val app = context.applicationContext

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE records(
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
        )""")
        db.execSQL("CREATE INDEX records_source ON records(source)")
        db.execSQL("CREATE INDEX records_kind ON records(kind,missing)")
        db.execSQL("""CREATE TABLE game_files(game_key TEXT PRIMARY KEY REFERENCES records(key) ON DELETE CASCADE,
            rom_uri TEXT, rom_path TEXT)""")
        db.execSQL("CREATE INDEX games_by_uri ON game_files(rom_uri)")
        db.execSQL("CREATE VIRTUAL TABLE record_fts USING fts4(key, title, subtitle, tokenize=unicode61)")
        db.execSQL("""CREATE TABLE metadata(
            key TEXT PRIMARY KEY NOT NULL REFERENCES records(key) ON DELETE CASCADE,
            alias TEXT,
            favorite INTEGER NOT NULL DEFAULT 0,
            pinned INTEGER NOT NULL DEFAULT 0,
            manual_order INTEGER NOT NULL DEFAULT 0,
            last_opened INTEGER NOT NULL DEFAULT 0,
            note TEXT NOT NULL DEFAULT ''
        )""")
        db.execSQL("""CREATE TABLE tags(
            key TEXT NOT NULL REFERENCES records(key) ON DELETE CASCADE,
            tag TEXT NOT NULL, PRIMARY KEY(key,tag))""")
        db.execSQL("CREATE INDEX tag_lookup ON tags(tag,key)")
        db.execSQL("""CREATE TABLE collections(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE COLLATE NOCASE,
            expression TEXT
        )""")
        db.execSQL("""CREATE TABLE memberships(
            collection_id INTEGER NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
            key TEXT NOT NULL REFERENCES records(key) ON DELETE CASCADE,
            position INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(collection_id,key))""")
        db.execSQL("""CREATE TABLE roots(
            uri TEXT PRIMARY KEY, label TEXT NOT NULL, last_scan INTEGER NOT NULL DEFAULT 0,
            status TEXT NOT NULL DEFAULT 'Not scanned')""")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // Deliberately no drop-and-recreate migrations: aliases/tags/collections are user data.
        throw IllegalStateException("Unsupported catalog schema upgrade $old to $new")
    }

    private fun flag(v: Boolean) = if (v) 1 else 0
    private fun upsert(db: SQLiteDatabase, entry: CatalogEntry, now: Long) {
        val values = ContentValues().apply {
            put("key", entry.key); put("kind", entry.kind)
            put("title", entry.title); put("subtitle", entry.subtitle)
            put("target", entry.target); put("source", entry.source)
            put("missing", flag(entry.missing)); put("source_favorite", flag(entry.sourceFavorite))
            put("source_recent", entry.sourceRecent); put("seen", now)
        }
        val changed = db.update("records", values, "key=?", arrayOf(entry.key))
        if (changed == 0) db.insertOrThrow("records", null, values)
        db.execSQL("DELETE FROM record_fts WHERE key=?", arrayOf(entry.key))
        db.execSQL("""INSERT INTO record_fts(key,title,subtitle)
            SELECT ?,?,? || ' ' || COALESCE((SELECT alias FROM metadata WHERE key=?),'')""",
            arrayOf(entry.key, entry.title, entry.subtitle, entry.key))
    }

    /** Idempotent source reconciliation; unseen items are marked missing rather than deleted. */
    @Synchronized
    fun syncSource(source: String, entries: List<CatalogEntry>, complete: Boolean = true) {
        val db = writableDatabase
        val stamp = System.currentTimeMillis()
        db.beginTransaction()
        try {
            entries.forEach { upsert(db, it, stamp) }
            if (complete) {
                db.execSQL("UPDATE records SET missing=1 WHERE source=? AND seen<>?",
                    arrayOf<Any?>(source, stamp))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** PFP is the authority: game IDs, launch settings and history stay in its own DB. */
    @Synchronized
    fun syncPfpGames(games: List<Game>) {
        // A multi-disc set appears once: use PFP's chosen primary game identity.
        val filtered = games.filter { it.discSetKey == null || it.isDiscPrimary }
        val entries = filtered.map { game ->
            val isAppShortcut = game.contentType == GameContentType.ANDROID_APP
            val key = if (isAppShortcut) "pfp-shortcut:${game.id}" else "pfp-game:${game.id}"
            CatalogEntry(key, if (isAppShortcut) "SHORTCUT" else "GAME", game.displayTitle,
                "${game.platformId} · ${game.displayGenre ?: ""}".trim().trimEnd('·',' '),
                game.id.toString(), "pfp", game.isMissing, game.isFavorite, game.lastPlayedAt ?: 0L)
        }
        syncSource("pfp", entries)
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (game in filtered) {
                if (game.contentType == GameContentType.ANDROID_APP) continue
                val v = ContentValues().apply {
                    put("game_key", "pfp-game:${game.id}")
                    put("rom_uri", game.romUri)
                    put("rom_path", game.romPath)
                }
                db.insertWithOnConflict("game_files", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun syncSystemTools() {
        val tools = listOf(
            Triple("settings", "Android settings", android.provider.Settings.ACTION_SETTINGS),
            Triple("home", "Change home launcher", android.provider.Settings.ACTION_HOME_SETTINGS),
            Triple("wifi", "Wi-Fi settings", android.provider.Settings.ACTION_WIFI_SETTINGS),
            Triple("bluetooth", "Bluetooth settings", android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
            Triple("storage", "Storage settings", android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
        ).map { (id, title, action) ->
            CatalogEntry("tool:$id", "TOOL", title, "Android system", action, "system")
        }
        val workspace = listOf(
            Triple("search", "Universal search", "arcdeck:search"),
            Triple("library", "Unified library", "arcdeck:library"),
            Triple("files", "Workspace file explorer", "arcdeck:files"),
            Triple("terminal", "Termux command panel", "arcdeck:terminal"),
            Triple("notes", "Workspace notes", "arcdeck:notes"),
            Triple("collections", "Collections and tags", "arcdeck:collections"),
            Triple("apps", "All installed apps", "arcdeck:apps")
        ).map { (id, title, target) ->
            CatalogEntry("workspace:$id", "TOOL", title, "ArcDeck XMB workspace", target, "system")
        }
        syncSource("system", tools + workspace)
    }

    @Suppress("DEPRECATION")
    @Synchronized
    fun syncAndroidApps() {
        val pm = app.packageManager
        val request = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(request, 0)
            .map { result ->
                val pkg = result.activityInfo.packageName
                CatalogEntry("android:$pkg", "APP", result.loadLabel(pm).toString(), pkg,
                    pkg, "android")
            }.distinctBy { it.key }
        syncSource("android", apps, complete = apps.isNotEmpty())
    }

    @Synchronized
    fun addRoot(uri: Uri, name: String) {
        val values = ContentValues().apply {
            put("uri", uri.toString()); put("label", name)
            put("status", "Not scanned")
        }
        val db = writableDatabase
        // Preserve scan time when relinking an existing root.
        db.insertWithOnConflict("roots", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun roots(): List<CatalogRoot> {
        val result = mutableListOf<CatalogRoot>()
        readableDatabase.rawQuery("SELECT uri,label,last_scan,status FROM roots ORDER BY label COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) result.add(CatalogRoot(c.getString(0), c.getString(1), c.getLong(2), c.getString(3)))
        }
        return result
    }

    /** Explicit user-requested SAF scan. Never guesses filesystem roots or crawls other app data. */
    @Synchronized
    fun scanRoot(rootUri: Uri, maxEntries: Int = 12000, maxDepth: Int = 16): ScanReport {
        val source = "saf:${rootUri}"
        val grant = app.contentResolver.persistedUriPermissions.any {
            it.uri == rootUri && it.isReadPermission
        }
        if (!grant) {
            setRootStatus(rootUri, "Permission missing; relink folder", false)
            return ScanReport(0, false, "Permission missing")
        }
        val root = DocumentFile.fromTreeUri(app, rootUri)
        if (root == null || !root.canRead()) {
            setRootStatus(rootUri, "Folder unavailable; existing index retained", false)
            return ScanReport(0, false, "Folder unavailable")
        }
        val results = mutableListOf<CatalogEntry>()
        val pending = ArrayDeque<Pair<DocumentFile, Int>>()
        pending.add(root to 0)
        var complete = true
        var failure = false
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty() && results.size < maxEntries) {
            val (folder, depth) = pending.removeFirst()
            if (!visited.add(folder.uri.toString())) continue
            val children = try { folder.listFiles() } catch (_: Exception) {
                failure = true; continue
            }
            for (child in children) {
                if (results.size >= maxEntries) { complete = false; break }
                val uri = child.uri.toString()
                val isDir = child.isDirectory
                results.add(CatalogEntry("file:$uri", if (isDir) "FOLDER" else "FILE",
                    child.name ?: "Unnamed", child.type ?: if (isDir) "Folder" else "File",
                    uri, source))
                if (isDir && depth < maxDepth) pending.add(child to depth + 1)
                else if (isDir) complete = false
            }
        }
        if (pending.isNotEmpty()) complete = false
        complete = complete && !failure
        syncSource(source, results, complete)
        val state = if (complete) "Indexed ${results.size} items" else
            "Partial scan (${results.size}); older entries retained"
        setRootStatus(rootUri, state, true)
        return ScanReport(results.size, complete, state)
    }

    private fun setRootStatus(uri: Uri, status: String, scanned: Boolean) {
        val values = ContentValues().apply {
            put("status", status)
            if (scanned) put("last_scan", System.currentTimeMillis())
        }
        writableDatabase.update("roots", values, "uri=?", arrayOf(uri.toString()))
    }

    fun get(key: String): CatalogEntry? {
        readableDatabase.rawQuery("""SELECT r.key,r.kind,r.title,r.subtitle,r.target,r.source,r.missing,
                m.alias,(COALESCE(m.favorite,0) | r.source_favorite),m.pinned,m.last_opened FROM records r
                LEFT JOIN metadata m ON m.key=r.key WHERE r.key=? LIMIT 1""", arrayOf(key)).use { c ->
            if (c.moveToFirst()) return mapRow(c)
        }
        return null
    }

    private fun mapRow(c: android.database.Cursor) = CatalogEntry(
        c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
        c.getString(5), c.getInt(6) != 0, false, 0L, if (c.isNull(7)) null else c.getString(7),
        c.getInt(8) != 0, c.getInt(9) != 0, c.getLong(10)
    )

    /** Search across all sources and filters. All SQL values use bound placeholders. */
    fun search(raw: String, limit: Int = 100, offset: Int = 0): List<CatalogEntry> {
        val parsed = UnifiedQuery.parse(raw)
        if (parsed.invalid.isNotEmpty()) return emptyList()
        val sql = StringBuilder("""SELECT r.key,r.kind,r.title,r.subtitle,r.target,r.source,r.missing,
            m.alias,(COALESCE(m.favorite,0) | r.source_favorite),m.pinned,m.last_opened FROM records r
            LEFT JOIN metadata m ON m.key=r.key""")
        val args = mutableListOf<String>()
        val fts = UnifiedQuery.toFts(parsed.words)
        if (fts.isNotBlank()) {
            sql.append(" JOIN record_fts f ON f.key=r.key")
        }
        val conditions = mutableListOf<String>()
        if (fts.isNotBlank()) { conditions.add("record_fts MATCH ?"); args.add(fts) }
        // A ROM indexed through SAF is the same object as its PFP game. Universal
        // results prefer the game entry; type:file still exposes the underlying file.
        if (parsed.kinds.isEmpty() && parsed.sources.isEmpty()) {
            conditions.add("NOT (r.kind='FILE' AND EXISTS(SELECT 1 FROM game_files gf WHERE gf.rom_uri=r.target))")
        }
        if (parsed.kinds.isNotEmpty()) {
            conditions.add("r.kind IN (${parsed.kinds.joinToString(",") { "?" }})")
            args.addAll(parsed.kinds)
        }
        if (parsed.sources.isNotEmpty()) {
            conditions.add("r.source IN (${parsed.sources.joinToString(",") { "?" }})")
            args.addAll(parsed.sources)
        }
        if (parsed.showMissing != null) {
            conditions.add("r.missing=?"); args.add(flag(parsed.showMissing).toString())
        } else conditions.add("r.missing=0")
        parsed.tags.forEach { tag ->
            conditions.add("EXISTS(SELECT 1 FROM tags t WHERE t.key=r.key AND t.tag=?)")
            args.add(tag)
        }
        if (parsed.collection != null) {
            conditions.add("""EXISTS(SELECT 1 FROM memberships mm
                JOIN collections cc ON cc.id=mm.collection_id
                WHERE mm.key=r.key AND (CAST(cc.id AS TEXT)=? OR cc.name=?))""")
            args.add(parsed.collection); args.add(parsed.collection)
        }
        if (conditions.isNotEmpty()) sql.append(" WHERE ").append(conditions.joinToString(" AND "))
        sql.append(" ORDER BY ")
        sql.append(when (parsed.sort) {
            UnifiedQuery.SortOrder.NAME -> "COALESCE(m.alias,r.title) COLLATE NOCASE ASC"
            UnifiedQuery.SortOrder.RECENT -> "MAX(r.source_recent,COALESCE(m.last_opened,0)) DESC, r.title COLLATE NOCASE"
            UnifiedQuery.SortOrder.MANUAL -> if (parsed.collection != null) {
                args.add(parsed.collection); args.add(parsed.collection)
                """COALESCE((SELECT mm.position FROM memberships mm JOIN collections cc
                    ON cc.id=mm.collection_id WHERE mm.key=r.key AND
                    (CAST(cc.id AS TEXT)=? OR cc.name=?) LIMIT 1),0) ASC,
                    r.title COLLATE NOCASE"""
            } else "COALESCE(m.manual_order,0) ASC, r.title COLLATE NOCASE"
            UnifiedQuery.SortOrder.RELEVANCE -> "COALESCE(m.pinned,0) DESC, (COALESCE(m.favorite,0) | r.source_favorite) DESC, MAX(r.source_recent,COALESCE(m.last_opened,0)) DESC, r.title COLLATE NOCASE"
        })
        sql.append(" LIMIT ? OFFSET ?")
        args.add(limit.coerceIn(1, 500).toString()); args.add(offset.coerceAtLeast(0).toString())
        val result = mutableListOf<CatalogEntry>()
        readableDatabase.rawQuery(sql.toString(), args.toTypedArray()).use { c ->
            while (c.moveToNext()) result.add(mapRow(c))
        }
        return result
    }

    fun gameIdForFile(uri: String): Long? {
        readableDatabase.rawQuery("""SELECT gf.game_key FROM game_files gf
                JOIN records r ON r.key=gf.game_key WHERE gf.rom_uri=? AND r.missing=0 LIMIT 1""",
                arrayOf(uri)).use { c ->
            if (c.moveToFirst()) return c.getString(0).removePrefix("pfp-game:").toLongOrNull()
        }
        return null
    }

    private fun makeMetadata(key: String) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO metadata(key) VALUES(?)", arrayOf(key))
    }

    @Synchronized
    fun rename(key: String, alias: String?) {
        makeMetadata(key)
        val v = ContentValues().apply { put("alias", alias?.trim()?.take(120)?.ifEmpty { null }) }
        writableDatabase.update("metadata", v, "key=?", arrayOf(key))
        // Search must find an alias too; explicit alias is joined into the FTS subtitle.
        val record = get(key) ?: return
        val db = writableDatabase
        db.execSQL("DELETE FROM record_fts WHERE key=?", arrayOf(key))
        db.execSQL("INSERT INTO record_fts(key,title,subtitle) VALUES(?,?,?)",
            arrayOf(key, record.title, record.subtitle + " " + (alias ?: "")))
    }

    @Synchronized
    fun setFavorite(key: String, value: Boolean) {
        makeMetadata(key)
        writableDatabase.execSQL("UPDATE metadata SET favorite=? WHERE key=?", arrayOf<Any?>(flag(value), key))
    }

    @Synchronized
    fun setPinned(key: String, value: Boolean) {
        makeMetadata(key)
        writableDatabase.execSQL("UPDATE metadata SET pinned=? WHERE key=?", arrayOf<Any?>(flag(value), key))
    }

    @Synchronized
    fun markOpened(key: String) {
        makeMetadata(key)
        writableDatabase.execSQL("UPDATE metadata SET last_opened=? WHERE key=?",
            arrayOf<Any?>(System.currentTimeMillis(), key))
    }

    fun tags(key: String): List<String> {
        val output = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT tag FROM tags WHERE key=? ORDER BY tag", arrayOf(key)).use { c ->
            while (c.moveToNext()) output.add(c.getString(0))
        }
        return output
    }

    @Synchronized
    fun addTag(key: String, tag: String) {
        val clean = UnifiedQuery.normalizeTag(tag)
        if (clean.isNotBlank()) writableDatabase.execSQL("INSERT OR IGNORE INTO tags(key,tag) VALUES(?,?)",
            arrayOf(key, clean))
    }

    @Synchronized
    fun removeTag(key: String, tag: String) = writableDatabase.execSQL(
        "DELETE FROM tags WHERE key=? AND tag=?", arrayOf(key, UnifiedQuery.normalizeTag(tag)))

    fun collections(): List<CatalogCollection> {
        val output = mutableListOf<CatalogCollection>()
        readableDatabase.rawQuery("SELECT id,name,expression FROM collections ORDER BY name COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) output.add(CatalogCollection(c.getLong(0), c.getString(1),
                if (c.isNull(2)) null else c.getString(2)))
        }
        return output
    }

    @Synchronized
    fun createCollection(name: String, expression: String? = null): Long {
        val clean = name.trim().take(80)
        require(clean.isNotEmpty()) { "Collection needs a name" }
        val values = ContentValues().apply {
            put("name", clean)
            put("expression", expression?.trim()?.takeIf { it.isNotEmpty() })
        }
        return writableDatabase.insertOrThrow("collections", null, values)
    }

    @Synchronized
    fun addToCollection(collectionId: Long, key: String) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO memberships(collection_id,key) VALUES (?,?)",
            arrayOf<Any?>(collectionId, key))
    }

    @Synchronized
    fun removeFromCollection(collectionId: Long, key: String) {
        writableDatabase.execSQL("DELETE FROM memberships WHERE collection_id=? AND key=?",
            arrayOf<Any?>(collectionId, key))
    }

    @Synchronized
    fun deleteCollection(collectionId: Long) {
        writableDatabase.execSQL("DELETE FROM collections WHERE id=?", arrayOf(collectionId))
    }

    /** Swap a membership with its neighbor. Uses a single transaction and contiguous positions. */
    @Synchronized
    fun shiftInCollection(collectionId: Long, key: String, delta: Int) {
        if (delta !in setOf(-1, 1)) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val order = mutableListOf<String>()
            db.rawQuery("""SELECT key FROM memberships WHERE collection_id=?
                ORDER BY position ASC, rowid ASC""", arrayOf(collectionId.toString())).use { c ->
                while (c.moveToNext()) order.add(c.getString(0))
            }
            val index = order.indexOf(key)
            val destination = index + delta
            if (index in order.indices && destination in order.indices) {
                java.util.Collections.swap(order, index, destination)
                for ((position, item) in order.withIndex()) {
                    db.execSQL("UPDATE memberships SET position=? WHERE collection_id=? AND key=?",
                        arrayOf<Any?>(position, collectionId, item))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun collectionEntries(collection: CatalogCollection): List<CatalogEntry> = if (collection.expression != null) {
        search(collection.expression, 250)
    } else search("in:${collection.id} sort:manual", 250)

    /** Portable user layer only. Never exports PFP ROMs, progress or private files. */
    @Synchronized
    fun exportUserLayer(): String {
        val db = readableDatabase
        val json = JSONObject().put("format", 1)
        fun table(name: String, cols: List<String>, where: String = "1=1"): JSONArray {
            val items = JSONArray()
            val columnSql = cols.joinToString(",")
            db.rawQuery("SELECT $columnSql FROM $name WHERE $where", null).use { c ->
                while (c.moveToNext()) {
                    val row = JSONObject()
                    for (i in cols.indices) {
                        val key = cols[i]
                        if (c.isNull(i)) row.put(key, JSONObject.NULL)
                        else if (c.getType(i) == android.database.Cursor.FIELD_TYPE_INTEGER)
                            row.put(key, c.getLong(i))
                        else row.put(key, c.getString(i))
                    }
                    items.put(row)
                }
            }
            return items
        }
        json.put("custom_records", table("records",
            listOf("key", "kind", "title", "subtitle", "target", "source"), "source='user'"))
        json.put("metadata", table("metadata", listOf("key", "alias", "favorite", "pinned", "manual_order", "last_opened", "note")))
        json.put("tags", table("tags", listOf("key", "tag")))
        json.put("collections", table("collections", listOf("id", "name", "expression")))
        json.put("memberships", table("memberships", listOf("collection_id", "key", "position")))
        return json.toString(2)
    }

    /** Atomic restore, merges collections by name. Command shortcuts never execute on import. */
    @Synchronized
    fun importUserLayer(raw: String) {
        require(raw.length <= 5_000_000) { "Backup too large" }
        val json = JSONObject(raw)
        require(json.optInt("format") == 1) { "Unknown backup format" }
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val oldToNew = mutableMapOf<Long, Long>()
        db.beginTransaction()
        try {
            val records = json.optJSONArray("custom_records") ?: JSONArray()
            require(records.length() <= 3000)
            for (i in 0 until records.length()) {
                val obj = records.getJSONObject(i)
                val key = obj.getString("key").take(160)
                val kind = obj.getString("kind")
                if (!key.startsWith("user:") || kind !in setOf("LINK", "COMMAND")) continue
                val target = obj.getString("target").take(2048)
                if (kind == "LINK" && !target.startsWith("https://") && !target.startsWith("http://")) continue
                upsert(db, CatalogEntry(key, kind, obj.getString("title").take(120),
                    obj.optString("subtitle").take(200), target, "user"), now)
            }
            val metas = json.optJSONArray("metadata") ?: JSONArray()
            require(metas.length() <= 250000)
            for (i in 0 until metas.length()) {
                val o = metas.getJSONObject(i)
                val key = o.getString("key")
                if (get(key) == null) {
                    upsert(db, CatalogEntry(key, "FILE", "Unlinked source", "Relink to restore",
                        "", "import", true), now)
                }
                db.execSQL("INSERT OR IGNORE INTO metadata(key) VALUES (?)", arrayOf(key))
                val v = ContentValues().apply {
                    put("alias", if (o.isNull("alias")) null else o.optString("alias").take(120))
                    put("favorite", o.optInt("favorite", 0))
                    put("pinned", o.optInt("pinned", 0))
                    put("manual_order", o.optInt("manual_order", 0))
                    put("last_opened", o.optLong("last_opened", 0))
                    put("note", o.optString("note").take(4000))
                }
                db.update("metadata", v, "key=?", arrayOf(key))
                // Restore alias searchability immediately, including imported custom shortcuts.
                db.execSQL("DELETE FROM record_fts WHERE key=?", arrayOf(key))
                db.execSQL("""INSERT INTO record_fts(key,title,subtitle)
                    SELECT r.key,r.title,r.subtitle || ' ' || COALESCE(m.alias,'')
                    FROM records r LEFT JOIN metadata m ON m.key=r.key WHERE r.key=?""",
                    arrayOf(key))
            }
            val tags = json.optJSONArray("tags") ?: JSONArray()
            require(tags.length() <= 300000)
            for (i in 0 until tags.length()) {
                val o = tags.getJSONObject(i)
                val key = o.getString("key")
                if (get(key) == null) continue
                db.execSQL("INSERT OR IGNORE INTO tags(key,tag) VALUES(?,?)",
                    arrayOf(key, UnifiedQuery.normalizeTag(o.getString("tag"))))
            }
            val col = json.optJSONArray("collections") ?: JSONArray()
            require(col.length() <= 3000)
            for (i in 0 until col.length()) {
                val o = col.getJSONObject(i)
                val name = o.getString("name").trim().take(80)
                if (name.isBlank()) continue
                val v = ContentValues().apply {
                    put("name", name)
                    put("expression", if (o.isNull("expression")) null else o.optString("expression").take(512))
                }
                db.insertWithOnConflict("collections", null, v, SQLiteDatabase.CONFLICT_IGNORE)
                db.rawQuery("SELECT id FROM collections WHERE name=? COLLATE NOCASE", arrayOf(name)).use { c ->
                    if (c.moveToFirst()) oldToNew[o.getLong("id")] = c.getLong(0)
                }
            }
            val mem = json.optJSONArray("memberships") ?: JSONArray()
            require(mem.length() <= 300000)
            for (i in 0 until mem.length()) {
                val o = mem.getJSONObject(i)
                val id = oldToNew[o.getLong("collection_id")] ?: continue
                val key = o.getString("key")
                if (get(key) == null) continue
                db.execSQL("INSERT OR IGNORE INTO memberships(collection_id,key,position) VALUES(?,?,?)",
                    arrayOf<Any?>(id, key, o.optInt("position", 0)))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun addCustom(kind: String, title: String, target: String): String {
        require(kind in setOf("LINK", "COMMAND")) { "Unsupported custom item kind" }
        require(title.isNotBlank() && title.length <= 120) { "Invalid title" }
        require(target.isNotBlank() && target.length <= 2048) { "Invalid target" }
        if (kind == "LINK") require(target.startsWith("https://") || target.startsWith("http://"))
        val key = "user:${java.util.UUID.randomUUID()}"
        val db = writableDatabase
        db.beginTransaction()
        try {
            upsert(db, CatalogEntry(key, kind, title.trim(), "User-created $kind", target, "user"),
                System.currentTimeMillis())
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return key
    }
}
