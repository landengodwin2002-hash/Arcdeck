package com.playfieldportal.launcher.workspace

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ComponentName
import android.content.pm.ProviderInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.StringReader

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WorkspaceSpacesTest {
    private lateinit var context: Context
    private lateinit var catalog: UnifiedCatalog
    private lateinit var spaces: WorkspaceSpaces
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("arcdeck_unified_v1.db")
        context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE).edit().clear().commit()
        catalog = UnifiedCatalog(context); spaces = WorkspaceSpaces.shared(context)
        // Robolectric gives each test a new data directory; attach a fresh real provider as Android does per process.
        val authority = "${context.packageName}.fileprovider"
        val info = requireNotNull(context.packageManager.resolveContentProvider(authority, android.content.pm.PackageManager.GET_META_DATA))
        val fileProvider = androidx.core.content.FileProvider(); fileProvider.attachInfo(context, info); fileProvider.onCreate()
        ShadowContentResolver.registerProviderInternal(authority, fileProvider)
    }
    @After fun cleanup() { catalog.close() }
    @Test fun sharedOrganizationRemainsConsistentAcrossAppSpaces() {
        catalog.addCustom("LINK", "Shared reference", "https://example.com").let { key ->
            val discord = spaces.create("Discord", "com.discord", catalog, key)
            val browser = spaces.create("Browser", "com.brave.browser", catalog, key)
            catalog.addTag(key, "research"); catalog.rename(key, "A shared name")
            assertEquals(key, spaces.items(discord, catalog).single().key)
            assertEquals("A shared name", spaces.items(browser, catalog).single().alias)
            assertEquals(listOf("research"), catalog.tags(key))
            spaces.notes(discord.id, "Discord notes"); spaces.notes(browser.id, "Browser notes")
            assertEquals("Discord notes", spaces.all().first { it.id == discord.id }.notes)
            assertEquals("Browser notes", spaces.all().first { it.id == browser.id }.notes)
        }
    }
    @Test fun aSpaceSearchIncludesItsConnectedFolderWithoutEscapingToOtherSources() {
        val root = Uri.parse("content://scope.test/tree/UpperCaseRoot")
        val doc = android.provider.DocumentsContract.buildDocumentUriUsingTree(root, "UpperCaseRoot")
        catalog.addRoot(root, "Connected root")
        catalog.registerDocument(WorkspaceDocument(doc, "Connected root", android.provider.DocumentsContract.Document.MIME_TYPE_DIR, 0, 0), "space:folders")
        catalog.syncSource("saf:$root", listOf(CatalogEntry("file:connected", "FILE", "Scope document", "text/plain", "content://scope.test/document/one", "saf:$root")))
        catalog.syncSource("outside", listOf(CatalogEntry("file:outside", "FILE", "Scope elsewhere", "text/plain", "content://other.test/document/two", "outside")))
        val space = spaces.create("Folder space", "", catalog, "file:$doc")
        assertEquals(setOf("file:connected", "file:$doc"), spaces.items(space, catalog, "document").map { it.key }.toSet())
        assertEquals("file:connected", spaces.items(space, catalog, "type:file document").single().key)
        assertFalse(spaces.items(space, catalog, "Scope").any { it.key == "file:outside" })
    }
    @Test fun anExplicitCollectionFilterCannotEscapeTheCurrentSpace() {
        val own = catalog.addCustom("LINK", "Own", "https://example.com/own")
        val outside = catalog.addCustom("LINK", "Outside", "https://example.com/outside")
        val space = spaces.create("Local", "", catalog, own)
        val other = catalog.createCollection("Outside"); catalog.addToCollection(other, outside)
        assertTrue(spaces.items(space, catalog, "in:$other").isEmpty())
    }
    @Test fun deletingASpacePreservesItsLibraryItemsAndOtherSpaces() {
        val key = catalog.addCustom("LINK", "Keep me", "https://example.com")
        val one = spaces.create("One", "", catalog, key); val two = spaces.create("Two", "", catalog, key)
        spaces.select(one.id); spaces.remove(one.id)
        assertEquals(two.id, spaces.active()!!.id)
        assertNotNull(catalog.get(key)); assertEquals(key, spaces.items(two, catalog).single().key)
    }
    @Test fun backupRestoresSpacesEvenWhenCollectionIdsChange() {
        val key = catalog.addCustom("LINK", "Reference", "https://example.com")
        val space = spaces.create("ChatGPT", "com.openai.chatgpt", catalog, key)
        spaces.notes(space.id, "Keep this context")
        val prefs = context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE)
        prefs.edit().putString("appearance", WorkspaceAppearance(palette = "Forest").json()).putBoolean("overlay_enabled", true).commit()
        val backup = WorkspaceBackup.export(context, catalog)
        catalog.close(); context.deleteDatabase("arcdeck_unified_v1.db"); catalog = UnifiedCatalog(context)
        catalog.createCollection("Unrelated collection")
        prefs.edit().clear().commit(); WorkspaceBackup.restore(context, catalog, backup)
        assertEquals("Keep this context", spaces.active()!!.notes)
        assertEquals(key, spaces.items(spaces.active()!!, catalog).single().key)
        assertEquals("Forest", WorkspaceAppearance.parse(prefs.getString("appearance", "{}")!!).palette)
        assertFalse(prefs.getBoolean("overlay_enabled", false))
    }
    @Test fun malformedSpaceOrAppearanceCannotPartiallyRestoreTheCatalog() {
        val key = catalog.addCustom("LINK", "Original", "https://example.com")
        val backup = JSONObject(WorkspaceBackup.export(context, catalog))
        catalog.rename(key, "Current name")
        backup.getJSONObject("preferences").put("appearance", "{\"opacity\":10}")
        assertThrows(IllegalArgumentException::class.java) { WorkspaceBackup.restore(context, catalog, backup.toString()) }
        assertEquals("Current name", catalog.get(key)!!.alias)
    }
    @Test fun anOlderWorkspaceBackupPreservesNewerAppSpacesAndTheirNotes() {
        val space = spaces.create("Keep this space", "", catalog); spaces.notes(space.id, "Keep these notes")
        val old = JSONObject().put("format", "arcdeck-workspace").put("version", 1)
            .put("catalog", JSONObject(catalog.exportUserLayer())).put("preferences", JSONObject().put("notes", "Old global notes").put("panels", "[]"))
        WorkspaceBackup.restore(context, catalog, old.toString())
        assertEquals(space.id, spaces.active()!!.id); assertEquals("Keep these notes", spaces.active()!!.notes)
    }
    @Test fun namedLayoutsRestoreGeometryAndAppearance() {
        val prefs = context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE)
        val panels = "[{\"id\":1,\"tool\":\"SPACES\",\"x\":5,\"y\":60,\"w\":330,\"h\":500}]"
        prefs.edit().putString("panels", panels).putString("appearance", WorkspaceAppearance(palette = "Violet").json()).commit()
        WorkspaceProfiles.save(context, "Gaming")
        val space = spaces.create("A game", "", catalog, profile = "Gaming")
        spaces.draft(space.id, "Unsaved draft")
        prefs.edit().putString("panels", "[]").putString("appearance", "{}").commit()
        WorkspaceProfiles.load(context, 0)
        assertEquals(panels, prefs.getString("panels", ""))
        assertEquals("Violet", WorkspaceAppearance.parse(prefs.getString("appearance", "{}")!!).palette)
        assertEquals("dp", prefs.getString("panel_units", ""))
        assertEquals("Gaming", spaces.active()!!.profile)
        assertEquals("Unsaved draft", spaces.noteText(space))
        assertEquals(0L, prefs.getLong("restore_epoch", 0L))
    }
    @Test fun incomingSharesRejectFileUrisAndDeduplicateContentGrants() {
        assertThrows(IllegalArgumentException::class.java) { WorkspaceSharing.parse(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///etc/passwd"))) }
        val uri = Uri.parse("content://example.provider/document/one")
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri).apply { clipData = ClipData.newRawUri("same", uri) }
        assertEquals(listOf(uri), WorkspaceSharing.parse(intent).uris)
        assertThrows(IllegalArgumentException::class.java) { WorkspaceSharing.parse(Intent(Intent.ACTION_VIEW, uri)) }
    }
    @Test fun sharedTextBecomesADurablePreviewableItem() = runBlocking {
        val space = spaces.create("Inbox", "", catalog)
        assertEquals(1, WorkspaceSharing.import(context, catalog, space, IncomingShare("A durable note", "Research", emptyList())))
        val entry = spaces.items(space, catalog).single()
        assertEquals("text/plain", entry.subtitle)
        assertEquals("A durable note", context.contentResolver.openInputStream(Uri.parse(entry.target))!!.bufferedReader().use { it.readText() })
        assertEquals("Research.txt", WorkspaceSharing.describe(context, Uri.parse(entry.target)).name)
    }
    @Test fun failedSharedFileImportRollsBackStagedFilesAndCatalogEntries() = runBlocking {
        val provider = FixtureDocumentsProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "arcdeck.test.documents"; exported = true; grantUriPermissions = true; readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = "android.permission.MANAGE_DOCUMENTS" })
        provider.onCreate(); ShadowContentResolver.registerProviderInternal("arcdeck.test.documents", provider)
        val id = provider.createDocument("root", "text/plain", "unreadable.txt"); provider.failRead = id
        val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(Uri.parse("content://arcdeck.test.documents/tree/root"), id)
        val space = spaces.create("Inbox", "", catalog)
        assertTrue(runCatching { WorkspaceSharing.import(context, catalog, space, IncomingShare("Staged text", "Text", listOf(uri))) }.isFailure)
        assertTrue(spaces.items(space, catalog).isEmpty())
        assertTrue(java.io.File(context.filesDir, "arcdeck_shared").listFiles()?.none { it.isDirectory && it.listFiles()?.isNotEmpty() == true } != false)
    }
    @Test fun previewBoundsTextWithoutReadingTheEntireFile() {
        val result = WorkspacePreview.readText(StringReader("x".repeat(WorkspacePreview.TEXT_LIMIT + 1000)))
        assertEquals(WorkspacePreview.TEXT_LIMIT, result.first.length); assertTrue(result.second)
        assertEquals("small" to false, WorkspacePreview.readText(StringReader("small")))
    }
    @Test fun sharedWebLinksBecomePortableLinksRatherThanTemporaryFiles() = runBlocking {
        val space = spaces.create("Brave", "com.brave.browser", catalog)
        WorkspaceSharing.import(context, catalog, space, IncomingShare("https://example.com/article", "Article", emptyList()))
        val entry = spaces.items(space, catalog).single()
        assertEquals("LINK", entry.kind); assertEquals("user", entry.source); assertEquals("https://example.com/article", entry.target)
        assertEquals("https://example.com/article", JSONObject(catalog.exportUserLayer()).getJSONArray("custom_records").getJSONObject(0).getString("target"))
    }
    @Test fun appearanceSliderEndpointsAndDraftContextRoundTrip() {
        val style = WorkspaceAppearance(opacity = 0.65f, dim = 0.8f, font = 1.35f)
        assertEquals(style, WorkspaceAppearance.parse(style.json()))
        val space = spaces.create("Notes", "", catalog); spaces.draft(space.id, "Before debounce")
        assertEquals("Before debounce", spaces.noteText(space))
        val backup = JSONObject(WorkspaceBackup.export(context, catalog))
        assertTrue(backup.getJSONObject("preferences").getString("spaces").contains("Before debounce"))
    }
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun permittedFloatingBubbleOpensAComposePanelWithoutAnActivity() {
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        val controller = Robolectric.buildService(WorkspaceOverlayService::class.java).create()
        try {
            spaces.create("Floating notes", "", catalog)
            val service = controller.get(); service.onStartCommand(Intent(), 0, 1)
            val bubble = WorkspaceOverlayService::class.java.getDeclaredField("bubble").apply { isAccessible = true }.get(service) as android.view.View
            assertTrue(bubble.performClick())
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val panel = WorkspaceOverlayService::class.java.getDeclaredField("panel").apply { isAccessible = true }.get(service)
            assertNotNull(panel)
            assertTrue(bubble.performClick())
        } finally { controller.destroy(); org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false) }
    }
    @Test fun floatingServiceProvidesComposeLifecycleAndStopsWithoutOverlayPermission() {
        val controller = Robolectric.buildService(WorkspaceOverlayService::class.java).create()
        val service = controller.get()
        assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, service.lifecycle.currentState)
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(Intent(), 0, 1))
        assertFalse(context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE).getBoolean("overlay_enabled", false))
        controller.destroy(); assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, service.lifecycle.currentState)
    }
}
