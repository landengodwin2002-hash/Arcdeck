package com.playfieldportal.launcher.workspace

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameContentType
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val artwork: String? = null,
    val parentUri: String? = null,
)

internal data class CatalogCollection(val id: Long, val name: String, val expression: String?)
internal data class CatalogRoot(val uri: String, val label: String, val lastScan: Long, val status: String)
internal data class ScanReport(val indexed: Int, val complete: Boolean, val status: String)

internal class UnifiedCatalog(context: Context) : SQLiteOpenHelper(context.applicationContext,
    "arcdeck_unified_v1.db", null, 2) {
    companion object {
        @Volatile private var shared: UnifiedCatalog? = null
        fun shared(context: Context): UnifiedCatalog = shared?.takeIf { it.app === context.applicationContext } ?: synchronized(this) {
            shared?.takeIf { it.app === context.applicationContext } ?: UnifiedCatalog(context.applicationContext).also { shared = it }
        }
    }
    private val app = context.applicationContext
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    private fun changed() { _revision.value = _revision.value + 1 }

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
            seen INTEGER NOT NULL DEFAULT 0,
            artwork TEXT,
            parent_uri TEXT
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
        require(new == 2 && old == 1) { "Unsupported catalog schema upgrade $old to $new" }
        db.execSQL("ALTER TABLE records ADD COLUMN artwork TEXT")
        db.execSQL("ALTER TABLE records ADD COLUMN parent_uri TEXT")
    }

    private fun flag(v: Boolean) = if (v) 1 else 0
    private fun upsert(db: SQLiteDatabase, entry: CatalogEntry, now: Long) {
        val values = ContentValues().apply {
            put("key", entry.key); put("kind", entry.kind)
            put("title", entry.title); put("subtitle", entry.subtitle)
            put("target", entry.target); put("source", entry.source)
            put("missing", flag(entry.missing)); put("source_favorite", flag(entry.sourceFavorite))
            put("source_recent", entry.sourceRecent); put("seen", now); put("artwork", entry.artwork); put("parent_uri", entry.parentUri)
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
        db.beginTransaction()
        try {
            val stamp = db.rawQuery("SELECT COALESCE(MAX(seen),0)+1 FROM records WHERE source=?",
                arrayOf(source)).use { it.moveToFirst(); it.getLong(0) }
            entries.forEach { upsert(db, it, stamp) }
            if (complete) {
                db.execSQL("UPDATE records SET missing=1 WHERE source=? AND seen<>?",
                    arrayOf<Any?>(source, stamp))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed()
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
                game.id.toString(), "pfp", game.isMissing, game.isFavorite, game.lastPlayedAt ?: 0L,
                artwork = game.iconUri ?: game.artworkUri ?: game.boxArtUri)
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
        changed()
    }

    @Synchronized
    fun syncSystemTools() {
        val tools = listOf(
            Triple("settings", "Android settings", android.provider.Settings.ACTION_SETTINGS),
            Triple("home", "Change home launcher", android.provider.Settings.ACTION_HOME_SETTINGS),
            Triple("wifi", "Wi-Fi settings", android.provider.Settings.ACTION_WIFI_SETTINGS),
            Triple("bluetooth", "Bluetooth settings", android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
            Triple("storage", "Storage settings", android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
            Triple("internet_panel", "Internet quick panel", android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY),
            Triple("volume_panel", "Volume quick panel", android.provider.Settings.Panel.ACTION_VOLUME),
            Triple("display", "Display settings", android.provider.Settings.ACTION_DISPLAY_SETTINGS),
            Triple("battery", "Battery settings", android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)
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
            Triple("apps", "All installed apps", "arcdeck:apps"),
            Triple("spaces", "App spaces", "arcdeck:spaces"),
            Triple("customize", "Customize workspace", "arcdeck:customize"),
            Triple("pfp", "Original PFP features", "arcdeck:pfp")
        ).map { (id, title, target) ->
            CatalogEntry("workspace:$id", "TOOL", title, "ArcDeck XMB workspace", target, "system")
        }
        syncSource("system", tools + workspace)
    }

    fun syncPfpFeatures(sections: List<Pair<String, String>>) {
        if (sections.isNotEmpty()) syncSource("pfp:features", sections.map { (id, title) ->
            CatalogEntry("pfp-section:$id", "TOOL", title, "Original PFP section", "pfp:category:$id", "pfp:features")
        })
    }

    @Synchronized fun registerDocument(document: WorkspaceDocument, source: String) {
        upsert(writableDatabase, CatalogEntry("file:${document.uri}", if (document.directory) "FOLDER" else "FILE",
            document.name, document.mime, document.uri.toString(), source), System.currentTimeMillis())
        changed()
    }

    @Synchronized fun attachIncoming(collection: Long, entries: List<CatalogEntry>) {
        require(entries.size <= 33)
        val db = writableDatabase
        db.rawQuery("SELECT expression FROM collections WHERE id=?", arrayOf(collection.toString())).use { c ->
            require(c.moveToFirst() && c.isNull(0)) { "Choose a manual space collection" }
        }
        db.beginTransaction()
        try {
            var position = 0
            db.rawQuery("SELECT COALESCE(MAX(position),-1)+1 FROM memberships WHERE collection_id=?", arrayOf(collection.toString())).use { if (it.moveToFirst()) position = it.getInt(0) }
            entries.forEach { entry ->
                if (entry.kind == "LINK" && entry.source == "user" && entry.key.startsWith("user:")) validateCustom("LINK", entry.title, entry.target)
                else require(entry.kind == "FILE" && entry.source == "share" && Uri.parse(entry.target).scheme == "content")
                upsert(db, entry, System.currentTimeMillis())
                db.execSQL("INSERT OR IGNORE INTO memberships(collection_id,key,position) VALUES(?,?,?)", arrayOf<Any?>(collection, entry.key, position++))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed()
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
        changed()
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
    fun scanRoot(rootUri: Uri, maxEntries: Int = 12000, maxDepth: Int = 16, cancelled: () -> Boolean = { false }): ScanReport {
        val source = "saf:${rootUri}"
        val grant = WorkspaceStorage.hasGrant(app, rootUri)
        if (!grant) {
            setRootStatus(rootUri, "Permission missing; relink folder", false)
            return ScanReport(0, false, "Permission missing")
        }
        val results = mutableListOf<CatalogEntry>()
        val documents = WorkspaceDocuments(app)
        val pending = ArrayDeque<Pair<Uri, Int>>()
        pending.add(documents.rootDocument(rootUri) to 0)
        var complete = true
        var failure = false
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty() && results.size < maxEntries) {
            if (cancelled()) { complete = false; break }
            val (folder, depth) = pending.removeFirst()
            if (!visited.add(folder.toString())) continue
            val listing = try { documents.children(folder, (maxEntries - results.size + 1).coerceAtLeast(1)) }
                catch (_: Exception) { failure = true; continue }
            if (listing.truncated) complete = false
            for (child in listing.items) {
                if (cancelled()) { complete = false; break }
                if (results.size >= maxEntries) { complete = false; break }
                results.add(CatalogEntry("file:${child.uri}", if (child.directory) "FOLDER" else "FILE",
                    child.name, child.mime, child.uri.toString(), source, parentUri = folder.toString()))
                if (child.directory && depth < maxDepth) pending.add(child.uri to depth + 1)
                else if (child.directory) complete = false
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
        changed()
    }

    fun get(key: String): CatalogEntry? {
        readableDatabase.rawQuery("""SELECT r.key,r.kind,r.title,r.subtitle,r.target,r.source,r.missing,
                m.alias,(COALESCE(m.favorite,0) | r.source_favorite),m.pinned,m.last_opened,
                r.source_favorite,r.source_recent,r.artwork,r.parent_uri FROM records r
                LEFT JOIN metadata m ON m.key=r.key WHERE r.key=? LIMIT 1""", arrayOf(key)).use { c ->
            if (c.moveToFirst()) return mapRow(c)
        }
        return null
    }

    private fun mapRow(c: android.database.Cursor) = CatalogEntry(
        c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
        c.getString(5), c.getInt(6) != 0, c.getInt(11) != 0, c.getLong(12), if (c.isNull(7)) null else c.getString(7),
        c.getInt(8) != 0, c.getInt(9) != 0, c.getLong(10), if (c.isNull(13)) null else c.getString(13), if (c.isNull(14)) null else c.getString(14)
    )

    /** Search across all sources and filters. All SQL values use bound placeholders. */
    fun search(raw: String, limit: Int = 100, offset: Int = 0, scopeCollection: Long? = null,
        scopeSources: Set<String> = emptySet()): List<CatalogEntry> {
        val parsed = resolveQuery(UnifiedQuery.parse(raw))
        if (parsed.invalid.isNotEmpty()) return emptyList()
        val sql = StringBuilder("""SELECT r.key,r.kind,r.title,r.subtitle,r.target,r.source,r.missing,
            m.alias,(COALESCE(m.favorite,0) | r.source_favorite),m.pinned,m.last_opened,
                r.source_favorite,r.source_recent,r.artwork,r.parent_uri FROM records r
            LEFT JOIN metadata m ON m.key=r.key""")
        val args = mutableListOf<String>()
        val fts = UnifiedQuery.toFts(parsed.words)
        if (fts.isNotBlank()) {
            sql.append(" JOIN record_fts f ON f.key=r.key")
        }
        val conditions = mutableListOf<String>()
        if (fts.isNotBlank()) { conditions.add("record_fts MATCH ?"); args.add(fts) }
        if (scopeCollection != null) {
            require(scopeSources.size <= 100)
            val scope = mutableListOf("EXISTS(SELECT 1 FROM memberships sc WHERE sc.key=r.key AND sc.collection_id=?)")
            args.add(scopeCollection.toString())
            if (scopeSources.isNotEmpty()) {
                val placeholders = scopeSources.joinToString(",") { "?" }
                scope.add("r.source IN ($placeholders)"); args.addAll(scopeSources)
                scope.add("EXISTS(SELECT 1 FROM game_files sg JOIN records sf ON sf.target=sg.rom_uri WHERE sg.game_key=r.key AND sf.source IN ($placeholders))")
                args.addAll(scopeSources)
            }
            conditions.add(scope.joinToString(" OR ", "(", ")"))
        }
        // A ROM indexed through SAF is the same object as its PFP game. Universal
        // results prefer the game entry; type:file still exposes the underlying file.
        if (parsed.kinds.isEmpty() && parsed.sources.isEmpty()) {
            conditions.add("NOT (r.kind='FILE' AND EXISTS(SELECT 1 FROM game_files gf JOIN records gr ON gr.key=gf.game_key WHERE gf.rom_uri=r.target AND gr.missing=0))")
        }
        if (parsed.kinds.isNotEmpty()) {
            conditions.add("r.kind IN (${parsed.kinds.joinToString(",") { "?" }})")
            args.addAll(parsed.kinds)
        }
        if (parsed.sources.isNotEmpty()) {
            conditions.add(parsed.sources.joinToString(" OR ", "(", ")") { source ->
                args.add(if (source == "files" || source == "saf") "saf:%" else source)
                if (source == "files" || source == "saf") "r.source LIKE ?" else "r.source=?"
            })
        }
        if (parsed.showMissing != null) {
            conditions.add("r.missing=?"); args.add(flag(parsed.showMissing).toString())
        } else conditions.add("r.missing=0")
        if (parsed.favorite == true) conditions.add("(COALESCE(m.favorite,0) | r.source_favorite)<>0")
        if (parsed.pinned == true) conditions.add("COALESCE(m.pinned,0)<>0")
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
        sql.append(", r.key ASC LIMIT ? OFFSET ?")
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

    fun collectionSources(collection: Long): Set<String> {
        val folders = mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT r.key FROM records r JOIN memberships m ON m.key=r.key WHERE m.collection_id=? AND r.kind='FOLDER'",
            arrayOf(collection.toString())).use { c -> while (c.moveToNext()) folders.add(c.getString(0)) }
        return roots().mapNotNull { root ->
            val uri = Uri.parse(root.uri)
            val document = android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, android.provider.DocumentsContract.getTreeDocumentId(uri))
            "saf:${root.uri}".takeIf { "file:$document" in folders }
        }.toSet()
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
        changed()
    }

    @Synchronized
    fun setFavorite(key: String, value: Boolean) {
        makeMetadata(key)
        writableDatabase.execSQL("UPDATE metadata SET favorite=? WHERE key=?", arrayOf<Any?>(flag(value), key))
        changed()
    }

    @Synchronized
    fun setPinned(key: String, value: Boolean) {
        makeMetadata(key)
        writableDatabase.execSQL("UPDATE metadata SET pinned=? WHERE key=?", arrayOf<Any?>(flag(value), key))
        changed()
    }

    @Synchronized
    fun markOpened(key: String) {
        makeMetadata(key)
        writableDatabase.execSQL("UPDATE metadata SET last_opened=? WHERE key=?",
            arrayOf<Any?>(System.currentTimeMillis(), key))
        changed()
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
        require(get(key) != null) { "Item no longer exists" }
        if (clean.isNotBlank()) {
            makeMetadata(key)
            writableDatabase.execSQL("INSERT OR IGNORE INTO tags(key,tag) VALUES(?,?)", arrayOf(key, clean))
            changed()
        }
    }

    @Synchronized
    fun removeTag(key: String, tag: String) {
        writableDatabase.execSQL("DELETE FROM tags WHERE key=? AND tag=?", arrayOf(key, UnifiedQuery.normalizeTag(tag)))
        changed()
    }

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
        validateExpression(expression)
        return writableDatabase.insertOrThrow("collections", null, values).also { changed() }
    }

    @Synchronized
    fun addToCollection(collectionId: Long, key: String) {
        val collection = collections().firstOrNull { it.id == collectionId }
            ?: error("Collection no longer exists")
        require(collection.expression == null) { "Smart collections are populated by their query" }
        makeMetadata(key)
        writableDatabase.execSQL("""INSERT OR IGNORE INTO memberships(collection_id,key,position)
            SELECT ?,?,COALESCE(MAX(position),-1)+1 FROM memberships WHERE collection_id=?""",
            arrayOf<Any?>(collectionId, key, collectionId))
        changed()
    }

    @Synchronized
    fun removeFromCollection(collectionId: Long, key: String) {
        writableDatabase.execSQL("DELETE FROM memberships WHERE collection_id=? AND key=?",
            arrayOf<Any?>(collectionId, key))
        changed()
    }

    @Synchronized
    fun deleteCollection(collectionId: Long) {
        writableDatabase.execSQL("DELETE FROM collections WHERE id=?", arrayOf(collectionId))
        changed()
    }

    /** Swap a membership with its neighbor. Uses a single transaction and contiguous positions. */
    @Synchronized
    fun shiftInCollection(collectionId: Long, key: String, delta: Int) {
        if (delta !in setOf(-1, 1)) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val order = mutableListOf<String>()
            db.rawQuery("""SELECT mm.key FROM memberships mm JOIN records r ON r.key=mm.key
                WHERE collection_id=? ORDER BY position ASC, r.title COLLATE NOCASE, r.key ASC""", arrayOf(collectionId.toString())).use { c ->
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
        changed()
    }

    private fun validateExpression(expression: String?) {
        if (!expression.isNullOrBlank()) require(UnifiedQuery.parse(expression).invalid.isEmpty()) {
            "Invalid saved query"
        }
    }

    private fun resolveQuery(query: UnifiedQuery, visited: Set<Long> = emptySet()): UnifiedQuery {
        val name = query.collection ?: return query
        val collection = collections().firstOrNull { it.id.toString() == name || it.name.equals(name, true) }
            ?: return query.copy(invalid = query.invalid + "Collection not found")
        val expression = collection.expression ?: return query.copy(collection = collection.id.toString())
        if (collection.id in visited || visited.size >= 8)
            return query.copy(invalid = query.invalid + "Circular saved query")
        val child = resolveQuery(UnifiedQuery.parse(expression), visited + collection.id)
        return query.copy(collection = null).intersect(child)
    }

    @Synchronized
    fun updateCollection(id: Long, name: String, expression: String?) {
        val clean = name.trim().take(80)
        require(clean.isNotEmpty()) { "Collection needs a name" }
        validateExpression(expression)
        val values = ContentValues().apply {
            put("name", clean); put("expression", expression?.trim()?.takeIf { it.isNotEmpty() })
        }
        writableDatabase.update("collections", values, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun collectionEntries(collection: CatalogCollection): List<CatalogEntry> = if (collection.expression != null) {
        search(collection.expression, 250)
    } else search("in:${collection.id} sort:manual", 250)

    /** References preserve tags/memberships even when the original source is not mounted yet. */
    @Synchronized
    fun exportUserLayer(): String {
        val db = readableDatabase
        fun table(name: String, cols: List<String>, where: String = "1=1"): JSONArray {
            val items = JSONArray()
            db.rawQuery("SELECT ${cols.joinToString(",")} FROM $name WHERE $where", null).use { c ->
                while (c.moveToNext()) {
                    val row = JSONObject()
                    for (i in cols.indices) {
                        row.put(cols[i], if (c.isNull(i)) JSONObject.NULL
                            else if (c.getType(i) == android.database.Cursor.FIELD_TYPE_INTEGER) c.getLong(i)
                            else c.getString(i))
                    }
                    items.put(row)
                }
            }
            return items
        }
        val identity = listOf("key", "kind", "title", "subtitle", "target", "source", "artwork")
        return JSONObject().put("format", 2)
            .put("custom_records", table("records", identity, "source='user'"))
            .put("references", table("records", identity, """key IN (SELECT key FROM metadata
                UNION SELECT key FROM tags UNION SELECT key FROM memberships) AND source<>'user'"""))
            .put("metadata", table("metadata", listOf("key", "alias", "favorite", "pinned", "manual_order", "last_opened", "note")))
            .put("tags", table("tags", listOf("key", "tag")))
            .put("collections", table("collections", listOf("id", "name", "expression")))
            .put("memberships", table("memberships", listOf("collection_id", "key", "position")))
            .put("roots", table("roots", listOf("uri", "label"))).toString(2)
    }

    /** A validated, transactional merge. Importing a command never launches it. */
    @Synchronized
    fun importUserLayer(raw: String) {
        require(raw.length <= 5_000_000) { "Backup too large" }
        val json = JSONObject(raw)
        require(json.optInt("format") in 1..2) { "Unknown backup format" }
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val oldToNew = mutableMapOf<Long, Long>()
        fun rows(name: String, max: Int): JSONArray = (json.optJSONArray(name) ?: JSONArray()).also {
            require(it.length() <= max) { "Too many $name in backup" }
        }
        fun key(obj: JSONObject): String = obj.getString("key").also {
            require(it.length in 1..4096 && ':' in it) { "Invalid item identity" }
        }
        fun ensureReference(id: String) {
            if (get(id) == null) upsert(db, CatalogEntry(id, "FILE", "Unlinked source",
                "Reconnect the original app, game or folder", "", "import", true), now)
        }
        db.beginTransaction()
        try {
            val custom = rows("custom_records", 3000)
            for (i in 0 until custom.length()) {
                val o = custom.getJSONObject(i)
                val id = key(o)
                val kind = o.getString("kind")
                require(id.startsWith("user:") && kind in setOf("LINK", "COMMAND")) { "Invalid custom shortcut" }
                val title = o.getString("title")
                val target = o.getString("target")
                validateCustom(kind, title, target)
                val existing = get(id)
                require(existing == null || existing.source == "user" || (existing.source == "import" && existing.missing)) { "Conflicting shortcut identity" }
                upsert(db, CatalogEntry(id, kind, title, o.optString("subtitle").take(200), target, "user"), now)
            }
            val refs = rows("references", 50000)
            for (i in 0 until refs.length()) {
                val o = refs.getJSONObject(i)
                val id = key(o)
                val kind = o.optString("kind", "FILE")
                require(kind in UnifiedQuery.validKinds && !id.startsWith("user:")) { "Invalid source reference" }
                if (get(id) == null) upsert(db, CatalogEntry(id, kind, o.optString("title", "Unlinked source").take(120),
                    "Reconnect its original source", "", "import", true), now)
            }
            val metas = rows("metadata", 50000)
            for (i in 0 until metas.length()) {
                val o = metas.getJSONObject(i)
                val id = key(o)
                ensureReference(id)
                makeMetadata(id)
                val v = ContentValues().apply {
                    put("alias", if (o.isNull("alias")) null else o.optString("alias").take(120))
                    put("favorite", flag(o.optInt("favorite") != 0))
                    put("pinned", flag(o.optInt("pinned") != 0))
                    put("manual_order", o.optInt("manual_order"))
                    put("last_opened", o.optLong("last_opened").coerceAtLeast(0))
                    put("note", o.optString("note").take(4000))
                }
                db.update("metadata", v, "key=?", arrayOf(id))
                db.execSQL("DELETE FROM record_fts WHERE key=?", arrayOf(id))
                db.execSQL("""INSERT INTO record_fts(key,title,subtitle)
                    SELECT r.key,r.title,r.subtitle || ' ' || COALESCE(m.alias,'')
                    FROM records r LEFT JOIN metadata m ON m.key=r.key WHERE r.key=?""", arrayOf(id))
            }
            val tags = rows("tags", 100000)
            for (i in 0 until tags.length()) {
                val o = tags.getJSONObject(i)
                val id = key(o)
                ensureReference(id)
                val tag = UnifiedQuery.normalizeTag(o.getString("tag"))
                if (tag.isNotEmpty()) db.execSQL("INSERT OR IGNORE INTO tags(key,tag) VALUES(?,?)", arrayOf(id, tag))
            }
            val cols = rows("collections", 3000)
            for (i in 0 until cols.length()) {
                val o = cols.getJSONObject(i)
                val name = o.getString("name").trim().take(80)
                require(name.isNotBlank()) { "Collection needs a name" }
                val expr = if (o.isNull("expression")) null else o.optString("expression").take(512).ifBlank { null }
                validateExpression(expr)
                val v = ContentValues().apply { put("name", name); put("expression", expr) }
                db.insertWithOnConflict("collections", null, v, SQLiteDatabase.CONFLICT_IGNORE)
                db.rawQuery("SELECT id FROM collections WHERE name=? COLLATE NOCASE", arrayOf(name)).use {
                    if (it.moveToFirst()) oldToNew[o.getLong("id")] = it.getLong(0)
                }
            }
            val members = rows("memberships", 100000)
            for (i in 0 until members.length()) {
                val o = members.getJSONObject(i)
                val id = oldToNew[o.getLong("collection_id")] ?: error("Collection reference missing")
                val item = key(o)
                ensureReference(item)
                db.execSQL("INSERT OR IGNORE INTO memberships(collection_id,key,position) VALUES(?,?,?)",
                    arrayOf<Any?>(id, item, o.optInt("position").coerceAtLeast(0)))
            }
            val roots = rows("roots", 100)
            for (i in 0 until roots.length()) {
                val o = roots.getJSONObject(i)
                val uri = Uri.parse(o.getString("uri"))
                require(uri.scheme == "content" && android.provider.DocumentsContract.isTreeUri(uri)) { "Invalid folder reference" }
                val v = ContentValues().apply {
                    put("uri", uri.toString()); put("label", o.optString("label", "Folder").take(120))
                    put("status", "Reconnect folder permission")
                }
                db.insertWithOnConflict("roots", null, v, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed()
    }

    fun queryErrors(raw: String): List<String> = resolveQuery(UnifiedQuery.parse(raw)).invalid

    @Synchronized
    fun addCustom(kind: String, title: String, target: String): String {
        validateCustom(kind, title, target)
        val key = "user:${java.util.UUID.randomUUID()}"
        val db = writableDatabase
        db.beginTransaction()
        try {
            upsert(db, CatalogEntry(key, kind, title.trim(), "User-created $kind", target, "user"),
                System.currentTimeMillis())
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed()
        return key
    }

    @Synchronized
    fun updateCustom(key: String, title: String, target: String) {
        val old = get(key) ?: error("Shortcut no longer exists")
        require(old.source == "user" && old.kind in setOf("LINK", "COMMAND"))
        validateCustom(old.kind, title, target)
        upsert(writableDatabase, old.copy(title = title.trim(), target = target), System.currentTimeMillis())
        changed()
    }

    @Synchronized
    fun deleteCustom(key: String) {
        require(get(key)?.source == "user") { "Only your custom shortcuts can be removed here" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM record_fts WHERE key=?", arrayOf(key))
            db.delete("records", "key=? AND source='user'", arrayOf(key))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed()
    }

    @Synchronized
    fun removeRoot(uri: String) {
        writableDatabase.delete("roots", "uri=?", arrayOf(uri))
        writableDatabase.execSQL("UPDATE records SET missing=1 WHERE source=?", arrayOf("saf:$uri"))
        changed()
    }

    @Synchronized
    fun renamedDocument(before: String, document: WorkspaceDocument, parent: String?) {
        val oldKey = "file:$before"
        val newKey = "file:${document.uri}"
        val previous = get(oldKey) ?: return
        val db = writableDatabase
        db.beginTransaction()
        try {
            upsert(db, previous.copy(key = newKey, title = document.name, target = document.uri.toString(),
                missing = false, parentUri = parent), System.currentTimeMillis())
            if (oldKey != newKey) {
                db.execSQL("""INSERT OR IGNORE INTO metadata(key,alias,favorite,pinned,manual_order,last_opened,note)
                    SELECT ?,alias,favorite,pinned,manual_order,last_opened,note FROM metadata WHERE key=?""", arrayOf(newKey, oldKey))
                db.execSQL("INSERT OR IGNORE INTO tags(key,tag) SELECT ?,tag FROM tags WHERE key=?", arrayOf(newKey, oldKey))
                db.execSQL("""INSERT OR IGNORE INTO memberships(collection_id,key,position)
                    SELECT collection_id,?,position FROM memberships WHERE key=?""", arrayOf(newKey, oldKey))
                db.execSQL("DELETE FROM record_fts WHERE key=?", arrayOf(oldKey))
                db.delete("records", "key=?", arrayOf(oldKey))
            }
            rename(newKey, previous.alias)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed()
    }

    fun stats(): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        readableDatabase.rawQuery("SELECT kind,COUNT(*) FROM records WHERE missing=0 GROUP BY kind", null).use {
            while (it.moveToNext()) result[it.getString(0)] = it.getInt(1)
        }
        return result
    }

    @Synchronized fun movedDocuments(mappings: List<Triple<Uri, Uri, Uri>>, documents: WorkspaceDocuments) {
        val db = writableDatabase; db.beginTransaction()
        try {
            mappings.forEach { (before, after, parent) ->
                if (get("file:$before") != null) renamedDocument(before.toString(), documents.stat(after), parent.toString())
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed()
    }

    private fun validateCustom(kind: String, title: String, target: String) {
        require(kind in setOf("LINK", "COMMAND")) { "Unsupported shortcut type" }
        require(title.isNotBlank() && title.length <= 120) { "Enter a name up to 120 characters" }
        require(target.isNotBlank() && target.length <= 2048) { "Enter a target up to 2048 characters" }
        if (kind == "LINK") {
            val uri = Uri.parse(target)
            require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank()) { "Enter a complete https:// or http:// URL" }
        }
    }
}
