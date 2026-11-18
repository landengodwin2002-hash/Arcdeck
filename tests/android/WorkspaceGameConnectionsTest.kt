package com.playfieldportal.launcher.workspace

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.launcher.PcLauncherCatalog
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WorkspaceGameConnectionsTest {
    private lateinit var context: Context
    private lateinit var provider: FixtureDocumentsProvider
    private lateinit var connections: WorkspaceGameConnections
    private lateinit var catalog: UnifiedCatalog
    private val tree = Uri.parse("content://arcdeck.test.documents/tree/root")
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("arcdeck_unified_v1.db"); catalog = UnifiedCatalog(context)
        provider = FixtureDocumentsProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = tree.authority; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        provider.onCreate(); ShadowContentResolver.registerProviderInternal(tree.authority!!, provider)
        connections = WorkspaceGameConnections(context)
    }
    @After fun close() { catalog.close() }
    private fun grant() { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    private fun file(name: String, body: String, parent: String = "root"): String = provider.createDocument(parent, "text/plain", name).also {
        provider.nodes.getValue(it).file.writeText(body)
    }
    private fun dir(name: String, parent: String = "root") = provider.createDocument(parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
    private fun install(pkg: String, generation: Int, withProvider: Boolean = false) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = pkg; versionName = "$generation.0"; lastUpdateTime = System.nanoTime()
            applicationInfo = ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = "GameHub Lite"; enabled = true }
            activities = arrayOf(ActivityInfo().apply { packageName = pkg; name = if (generation == 5) PcLauncherCatalog.V5_GAME_DETAIL_ACTIVITY else PcLauncherCatalog.V6_DEEP_LINK_ACTIVITY; exported = true; enabled = true })
            if (withProvider) providers = arrayOf(ProviderInfo().apply { packageName = pkg; name = WorkspaceGameConnections.MT_PROVIDER
                authority = "$pkg.MTDataFilesProvider"; enabled = true; exported = true; grantUriPermissions = true })
        })
    }
    @Test fun onlyBoundedExplicitIdsAreRecognized() {
        assertEquals("570" to "STEAM", WorkspaceGameConnections.parseId("Dota.steamappid", "\uFEFF570\n"))
        assertEquals("local_abc-123" to "LOCAL", WorkspaceGameConnections.parseId("Game.localgameid", "local_abc-123"))
        listOf("0", "-2", "2147483648", "570\n42", "https://example.com", "local_../path", "x".repeat(257)).forEach {
            assertNull(WorkspaceGameConnections.parseId("Game.txt", it))
        }
        assertNull(WorkspaceGameConnections.parseId("systeminfo.txt", "570"))
        assertNull(WorkspaceGameConnections.parseId("Disc.iso", "570"))
        assertNull(WorkspaceGameConnections.parseId("Game.steam", "local_abcd"))
    }
    @Test fun sharedExistingFolderCreatesTwoSpacesWithoutCopyingFiles() {
        grant(); file("Existing game.steam", "570")
        val papaya = GameConnectionApp(WorkspaceGameConnections.PAPAYA, "Papaya", true, false)
        val hub = GameConnectionApp("gamehub.lite", "GameHub Lite", true, true)
        connections.connect(papaya, tree, GameFolderRole.GAMES, catalog)
        connections.connect(hub, tree, GameFolderRole.IDS, catalog)
        assertEquals(2, connections.all().size); assertEquals(1, catalog.roots().size)
        assertEquals(2, provider.nodes.size)
        val spaces = WorkspaceSpaces.shared(context)
        assertEquals(2, spaces.all().size)
        spaces.all().forEach { assertEquals(1, spaces.items(it, catalog, "type:file").size) }
        connections.connect(papaya, tree, GameFolderRole.ARTWORK, catalog)
        assertEquals(2, spaces.all().size); assertEquals(2, connections.all().size)
        connections.forget(connections.all().first { it.app == papaya.app })
        assertEquals(2, provider.nodes.size); assertEquals(1, catalog.roots().size)
    }
    @Test fun scannerUsesOpaqueUrisAndDoesNotReadRomsOrTreatAnIsoAsText() = runBlocking {
        grant(); val folder = dir("PC games"); file("My game.steamappid", "570", folder)
        file("Duplicate.steam", "570", folder); file("Local game.localgameid", "local_abcd", folder)
        val iso = file("Real disc.iso", "570", folder); provider.failRead = iso
        file("Notes.txt", "My notes", folder); file("systeminfo.txt", "570", folder)
        file("Oversized.txt", "570" + " ".repeat(2048), folder)
        val result = connections.scanIds(tree)
        assertEquals(2, result.games.size); assertFalse(result.partial); assertEquals(0, result.unreadable)
        assertTrue(result.games.all { it.uri.contains("opaque-") })
        assertEquals("570", provider.nodes.values.first { it.name == "My game.steamappid" }.file.readText())
    }
    @Test fun permissionLossPreservesConnectionsAndAnUnavailableProviderIsReported() = runBlocking {
        grant(); file("Game.steam", "570")
        connections.connect(GameConnectionApp("gamehub.lite", "Hub", true, true), tree, GameFolderRole.IDS, catalog)
        provider.failQuery = true
        assertEquals(1, connections.scanIds(tree).unreadable)
        context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertFalse(connections.accessible(tree.toString())); assertEquals(1, connections.all().size)
        assertTrue(runCatching { connections.scanIds(tree) }.isFailure)
        assertEquals(1, catalog.search("type:file").size)
    }
    @Test fun knownGameHubFoldersAreResolvedByNamesNotConcatenatingIds() {
        val files = dir("files"); val usr = dir("usr", files); val home = dir("home", usr)
        dir("virtual_containers", home); dir("components", home)
        val steam = dir("Steam", files); dir("steamapps", steam)
        val found = connections.knownFolders(tree)
        assertEquals(setOf("Save containers", "Components", "Steam files"), found.map { it.first }.toSet())
        assertTrue(found.all { DocumentsContract.getDocumentId(it.second.uri).startsWith("opaque-") })
    }
    @Test fun providerHintUsesTheInstalledAuthorityAndLaunchNamespacesStayDistinct() {
        install("gamehub.lite", 5, true)
        val hub = connections.apps().first { it.app == "gamehub.lite" }
        assertEquals("content://gamehub.lite.MTDataFilesProvider/document/gamehub.lite", hub.initialUri.toString())
        val steam = WorkspaceGameImport.launchIntent(context, hub.app, PcIdCandidate("", "Steam", "570", "STEAM"))
        assertEquals(PcLauncherCatalog.V5_GAME_DETAIL_ACTIVITY, steam.component!!.className)
        assertEquals("570", steam.getStringExtra("steamAppId")); assertFalse(steam.hasExtra("localGameId"))
        val local = WorkspaceGameImport.launchIntent(context, hub.app, PcIdCandidate("", "Local", "570", "LOCAL"))
        assertEquals("570", local.getStringExtra("localGameId")); assertFalse(local.hasExtra("steamAppId"))
        install("banner.hub", 6)
        assertEquals(PcLauncherCatalog.V6_DEEP_LINK_ACTIVITY, WorkspaceGameImport.launchIntent(context, "banner.hub", PcIdCandidate("", "Local", "local_abcd", "LOCAL")).component!!.className)
    }
    @Test fun genuineSpoofedPackageIsNotPresentedAsGameHub() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.antutu.ABenchMark"; lastUpdateTime = System.nanoTime()
            applicationInfo = ApplicationInfo().apply { packageName = "com.antutu.ABenchMark"; nonLocalizedLabel = "AnTuTu Benchmark" }
        })
        assertFalse(connections.apps().any { it.app == "com.antutu.ABenchMark" })
    }
    @Test fun connectionBackupRestoresReferencesAndValidatesBeforeChangingTheCatalog() {
        grant(); connections.connect(GameConnectionApp("gamehub.lite", "Hub", true, true), tree, GameFolderRole.SAVES, catalog)
        val backup = WorkspaceBackup.export(context, catalog)
        context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE).edit().clear().commit()
        WorkspaceBackup.restore(context, catalog, backup)
        assertEquals(GameFolderRole.SAVES, connections.all().single().role)
        val before = catalog.exportUserLayer()
        val bad = JSONObject(backup); bad.getJSONObject("preferences").put("game_connections", "[{\"app\":\"gamehub.lite\",\"tree\":\"file:///private\",\"role\":\"SAVES\"}]")
        assertThrows(IllegalArgumentException::class.java) { WorkspaceBackup.restore(context, catalog, bad.toString()) }
        assertEquals(before, catalog.exportUserLayer())
    }
    @Test fun reviewedImportsUseTheSharedGameAuthorityPreserveMetadataAndMatchOnRescan() = runBlocking {
        install("gamehub.lite", 5)
        val rows = MutableStateFlow<List<Game>>(emptyList())
        val repo = Proxy.newProxyInstance(GameRepository::class.java.classLoader, arrayOf(GameRepository::class.java)) { _, method, args ->
            when (method.name) {
                "getByIntentUri" -> rows.value.firstOrNull { it.launchIntentUri == args[0] }
                "upsert" -> { val game = (args[0] as Game).copy(id = (rows.value.size + 1).toLong()); rows.value = rows.value + game; game.id }
                "observeAll" -> rows
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as GameRepository
        val space = connections.space(GameConnectionApp("gamehub.lite", "Hub", true, true), catalog)
        val candidates = listOf(PcIdCandidate("a", "Same title", "570", "STEAM"), PcIdCandidate("b", "Same title", "570", "LOCAL"))
        val reviewed = candidates.map { it to WorkspaceGameImport.launchIntent(context, space.app, it) }
        WorkspaceGameImport.importReviewed(space.app, reviewed, space, catalog, repo)
        assertEquals(2, rows.value.size); assertEquals(2, WorkspaceSpaces.shared(context).items(space, catalog, "type:game").size)
        catalog.rename("pfp-game:1", "Personal name"); catalog.addTag("pfp-game:1", "favorite-rpg")
        WorkspaceGameImport.importReviewed(space.app, reviewed, space, catalog, repo)
        assertEquals(2, rows.value.size); assertEquals("Personal name", catalog.get("pfp-game:1")!!.alias)
        assertEquals(listOf("favorite-rpg"), catalog.tags("pfp-game:1"))
    }
}
