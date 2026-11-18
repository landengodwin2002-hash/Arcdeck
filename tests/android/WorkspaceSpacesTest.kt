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
    @Test fun namedLayoutsRestoreGeometryAndAppearance() {
        val prefs = context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE)
        val panels = "[{\"id\":1,\"tool\":\"SPACES\",\"x\":5,\"y\":60,\"w\":330,\"h\":500}]"
        prefs.edit().putString("panels", panels).putString("appearance", WorkspaceAppearance(palette = "Violet").json()).commit()
        WorkspaceProfiles.save(context, "Gaming")
        prefs.edit().putString("panels", "[]").putString("appearance", "{}").commit()
        WorkspaceProfiles.load(context, 0)
        assertEquals(panels, prefs.getString("panels", ""))
        assertEquals("Violet", WorkspaceAppearance.parse(prefs.getString("appearance", "{}")!!).palette)
        assertEquals("dp", prefs.getString("panel_units", ""))
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
        provider.attachInfo(context, ProviderInfo().apply { authority = "arcdeck.test.documents"; exported = true; grantUriPermissions = true })
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
    @Test fun floatingServiceProvidesComposeLifecycleAndStopsWithoutOverlayPermission() {
        val controller = Robolectric.buildService(WorkspaceOverlayService::class.java).create()
        val service = controller.get()
        assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, service.lifecycle.currentState)
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(Intent(), 0, 1))
        assertFalse(context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE).getBoolean("overlay_enabled", false))
        controller.destroy(); assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, service.lifecycle.currentState)
    }
}
