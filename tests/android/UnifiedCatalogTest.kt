package com.playfieldportal.launcher.workspace

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.domain.model.Game
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringReader

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UnifiedCatalogTest {
    private lateinit var context: Context
    private lateinit var db: UnifiedCatalog
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("arcdeck_unified_v1.db")
        context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE).edit().clear().commit()
        db = UnifiedCatalog(context)
    }
    @After fun close() { db.close() }
    private fun record(id: String, title: String = id, source: String = "android", kind: String = "APP", target: String = id) =
        CatalogEntry(id, kind, title, "", target, source)

    @Test fun schemaMigrationPreservesExistingUserOrganization() {
        db.close(); context.deleteDatabase("arcdeck_unified_v1.db")
        val path = context.getDatabasePath("arcdeck_unified_v1.db")
        path.parentFile!!.mkdirs()
        val legacy = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null)
        val schema = javaClass.classLoader!!.getResourceAsStream("catalog-v1.sql")!!.bufferedReader().use { it.readText() }
        schema.split(';').filter { it.isNotBlank() }.forEach { legacy.execSQL(it) }
        legacy.execSQL("INSERT INTO records(key,kind,title,source) VALUES('android:old','APP','Old app','android')")
        legacy.execSQL("INSERT INTO metadata(key,alias) VALUES('android:old','Legacy alias')")
        legacy.execSQL("INSERT INTO record_fts(key,title,subtitle) VALUES('android:old','Old app','Legacy alias')")
        legacy.execSQL("INSERT INTO tags(key,tag) VALUES('android:old','legacy')")
        legacy.execSQL("INSERT INTO collections(id,name) VALUES(1,'Old collection')")
        legacy.execSQL("INSERT INTO memberships(collection_id,key,position) VALUES(1,'android:old',0)")
        legacy.version = 1; legacy.close()
        db = UnifiedCatalog(context)
        assertEquals(2, db.writableDatabase.version)
        assertEquals("Legacy alias", db.search("alias tag:legacy in:1").single().alias)
    }
    @Test fun aRescanPreservesAliasTagsAndMemberships() {
        db.syncSource("android", listOf(record("android:one", "Original")))
        db.rename("android:one", "My alias")
        db.addTag("android:one", "RPG")
        val col = db.createCollection("Collection")
        db.addToCollection(col, "android:one")
        db.syncSource("android", listOf(record("android:one", "Renamed by app")))
        assertEquals("android:one", db.search("alias tag:rpg in:$col").single().key)
        assertEquals("My alias", db.get("android:one")!!.alias)
    }
    @Test fun missingSourcesKeepOrganizationAndCanReturn() {
        db.syncSource("android", listOf(record("android:one")))
        db.addTag("android:one", "keep")
        db.syncSource("android", emptyList())
        assertTrue(db.search("").isEmpty())
        assertEquals("android:one", db.search("is:missing tag:keep").single().key)
        db.syncSource("android", listOf(record("android:one")))
        assertEquals("android:one", db.search("tag:keep").single().key)
    }
    @Test fun incompleteReconciliationNeverMarksUnvisitedRecordsMissing() {
        db.syncSource("android", listOf(record("android:one"), record("android:two")))
        db.syncSource("android", listOf(record("android:one")), complete = false)
        assertEquals(2, db.search("").size)
    }
    @Test fun manualOrderReflectsInsertionAndMovesTheVisibleNeighbor() {
        db.syncSource("android", listOf(record("android:z", "Zebra"), record("android:a", "Apple")))
        val col = db.createCollection("Order")
        db.addToCollection(col, "android:z"); db.addToCollection(col, "android:a")
        assertEquals(listOf("android:z", "android:a"), db.search("in:$col sort:manual").map { it.key })
        db.shiftInCollection(col, "android:a", -1)
        assertEquals(listOf("android:a", "android:z"), db.search("in:$col sort:manual").map { it.key })
        db.addToCollection(col, "android:a")
        assertEquals(2, db.search("in:$col").size)
    }
    @Test fun smartCollectionsReevaluateAndCyclesAreReported() {
        db.syncSource("android", listOf(record("android:one")))
        val smart = db.createCollection("Live", "tag:chosen")
        assertTrue(db.search("in:$smart").isEmpty())
        db.addTag("android:one", "chosen")
        assertEquals(1, db.search("in:$smart").size)
        db.updateCollection(smart, "Live", "in:$smart")
        assertTrue(db.queryErrors("in:$smart").any { "Circular" in it })
        assertTrue(db.search("in:$smart").isEmpty())
    }
    @Test fun favoritesPinsAndSourceFiltersUseRealDatabaseState() {
        db.syncSource("saf:root", listOf(record("file:one", "Document", "saf:root", "FILE")))
        db.setFavorite("file:one", true); db.setPinned("file:one", true)
        assertEquals("file:one", db.search("source:files is:favorite is:pinned").single().key)
        assertTrue(db.search("source:android").isEmpty())
    }
    @Test fun pageBoundariesAreStableWhenAllTitlesMatch() {
        val entries = (0..209).map { record("android:${it.toString().padStart(3,'0')}", "Same") }
        db.syncSource("android", entries)
        val all = db.search("", 100, 0) + db.search("", 100, 100) + db.search("", 100, 200)
        assertEquals(210, all.map { it.key }.distinct().size)
        assertEquals(entries.map { it.key }, all.map { it.key })
    }
    @Test fun romDeduplicationAppliesOnlyToAvailableGames() {
        val uri = "content://roms/tree/root/document/game"
        db.syncPfpGames(listOf(Game(id = 1, title = "Game", platformId = "ps1", romUri = uri)))
        db.syncSource("saf:root", listOf(record("file:$uri", "ROM", "saf:root", "FILE", uri)))
        assertEquals(listOf("pfp-game:1"), db.search("").map { it.key })
        assertEquals(1L, db.gameIdForFile(uri))
        assertEquals(1, db.search("type:file").size)
        db.syncPfpGames(emptyList())
        assertEquals(listOf("file:$uri"), db.search("").map { it.key })
    }
    @Test fun gameArtworkAndSourceFavoriteAreMirrored() {
        db.syncPfpGames(listOf(Game(id = 3, title = "Art", platformId = "gba", isFavorite = true,
            artworkUri = "/covers/art.png", lastPlayedAt = 42L)))
        val item = db.search("is:favorite").single()
        assertEquals("/covers/art.png", item.artwork)
        assertTrue(item.sourceFavorite)
        assertEquals(42L, item.sourceRecent)
    }
    @Test fun portableBackupRestoresTagsWithoutRequiringMountedSources() {
        db.syncSource("android", listOf(record("android:one", "Remember me")))
        db.addTag("android:one", "portable")
        val col = db.createCollection("Portable")
        db.addToCollection(col, "android:one")
        val raw = db.exportUserLayer()
        db.close(); context.deleteDatabase("arcdeck_unified_v1.db"); db = UnifiedCatalog(context)
        db.importUserLayer(raw)
        assertEquals("Remember me", db.search("is:missing in:Portable tag:portable").single().title)
        db.syncSource("android", listOf(record("android:one", "Returned")))
        assertEquals("Returned", db.search("in:Portable tag:portable").single().title)
    }
    @Test fun legacyTagOnlyBackupDoesNotDiscardItsReferences() {
        db.importUserLayer("""{"format":1,"tags":[{"key":"android:legacy","tag":"kept"}]}""")
        assertEquals("android:legacy", db.search("is:missing tag:kept").single().key)
    }
    @Test fun aBadRestoreRollsBackEarlierValidRows() {
        val key = db.addCustom("LINK", "Keep", "https://example.com")
        val invalid = JSONObject().put("format", 2)
            .put("custom_records", JSONArray().put(JSONObject().put("key", "user:new").put("kind", "COMMAND")
                .put("title", "Do not import").put("target", "echo test")))
            .put("tags", JSONArray().put(JSONObject().put("key", "invalid").put("tag", "bad")))
        assertThrows(IllegalArgumentException::class.java) { db.importUserLayer(invalid.toString()) }
        assertNull(db.get("user:new")); assertNotNull(db.get(key))
    }
    @Test fun shortcutsCanBeEditedDeletedAndNeverExecuteDuringRestore() {
        val key = db.addCustom("COMMAND", "Draft", "echo hello")
        db.addTag(key, "command"); db.updateCustom(key, "Changed", "printf test")
        val backup = db.exportUserLayer()
        db.deleteCustom(key)
        assertTrue(db.search("type:command").isEmpty())
        db.importUserLayer(backup)
        assertEquals("printf test", db.search("type:command tag:command").single().target)
        assertThrows(IllegalArgumentException::class.java) { db.addCustom("LINK", "Bad", "javascript:alert(1)") }
    }
    @Test fun reconnectingPermissionIsRequiredButDoesNotDestroyAnIndex() {
        val uri = Uri.parse("content://test.documents/tree/root")
        db.addRoot(uri, "Disconnected")
        db.syncSource("saf:$uri", listOf(record("file:old", "Old", "saf:$uri", "FILE")))
        assertFalse(db.scanRoot(uri).complete)
        assertEquals(1, db.search("source:files").size)
    }
    @Test fun workspaceBackupIncludesNotesAndLayoutAndReadsAreBounded() {
        val prefs = context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE)
        prefs.edit().putString("notes", "Important notes").putString("panels", "[]").commit()
        val raw = WorkspaceBackup.export(context, db)
        prefs.edit().putString("notes", "Replacement").commit()
        WorkspaceBackup.restore(context, db, raw)
        assertEquals("Important notes", prefs.getString("notes", ""))
        assertThrows(IllegalArgumentException::class.java) { WorkspaceBackup.readBounded(StringReader("a".repeat(5_000_001))) }
    }
    @Test fun malformedWorkspaceLayoutDoesNotOverwriteExistingNotesOrCatalog() {
        val prefs = context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE)
        prefs.edit().putString("notes", "Keep").commit()
        val catalog = JSONObject().put("format", 2).put("custom_records", JSONArray().put(JSONObject()
            .put("key", "user:layout-test").put("kind", "COMMAND").put("title", "Uncommitted").put("target", "echo test")))
        val raw = JSONObject().put("format", "arcdeck-workspace").put("version", 1).put("catalog", catalog)
            .put("preferences", JSONObject().put("notes", "Overwrite").put("panels", "[{\"id\":1,\"tool\":\"UNKNOWN\"}]"))
        assertThrows(IllegalArgumentException::class.java) { WorkspaceBackup.restore(context, db, raw.toString()) }
        assertEquals("Keep", prefs.getString("notes", "")); assertNull(db.get("user:layout-test"))
    }
    @Test fun aLegacyMissingCustomReferenceCanLaterBeRestored() {
        db.importUserLayer("""{"format":1,"tags":[{"key":"user:legacy","tag":"kept"}]}""")
        db.importUserLayer("""{"format":2,"custom_records":[{"key":"user:legacy","kind":"COMMAND","title":"Recovered","target":"echo ok"}]}""")
        assertEquals("Recovered", db.search("tag:kept type:command").single().title)
    }
    @Test fun renamingAProviderDocumentPreservesItsOrganization() {
        db.syncSource("saf:root", listOf(record("file:old", "Old", "saf:root", "FILE", "old")))
        db.addTag("file:old", "keep"); db.rename("file:old", "Alias")
        val col = db.createCollection("Renamed"); db.addToCollection(col, "file:old")
        db.renamedDocument("old", WorkspaceDocument(Uri.parse("content://provider/new"), "New", "text/plain", 1, 0), "parent")
        assertNull(db.get("file:old"))
        assertEquals("Alias", db.search("tag:keep in:Renamed").single().alias)
    }
}
