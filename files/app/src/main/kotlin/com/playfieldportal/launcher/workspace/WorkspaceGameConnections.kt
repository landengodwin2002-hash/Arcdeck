package com.playfieldportal.launcher.workspace

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.data.repository.WindowsLibrarySetup
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameContentType
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.launcher.PcLauncherAdapters
import com.playfieldportal.feature.launcher.PcLauncherCatalog
import com.playfieldportal.feature.launcher.PcLauncherType
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.ArrayDeque
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal data class GameConnectionApp(val app: String, val name: String, val installed: Boolean, val gameHub: Boolean,
    val provider: String? = null) {
    // This provider's public root document ID is the host package (verified in its queryRoots implementation).
    val initialUri: Uri? get() = provider?.let { DocumentsContract.buildDocumentUri(it, app) }
}
internal enum class GameFolderRole(val label: String) { GAMES("Games / ROMs"), IDS("PC game IDs"), SAVES("Saves"), ARTWORK("Artwork"), DATA("App files") }
internal data class GameFolderConnection(val app: String, val tree: String, val role: GameFolderRole)
internal data class PcIdCandidate(val uri: String, val title: String, val id: String, val source: String?)
internal data class PcIdScan(val games: List<PcIdCandidate>, val partial: Boolean, val unreadable: Int)

/** References only: no game, save, ROM, database or component bytes are moved on connection. */
internal class WorkspaceGameConnections(private val context: Context) {
    private val prefs = context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE)
    fun all(): List<GameFolderConnection> = decode(prefs.getString("game_connections", "[]") ?: "[]")
    fun remember(connection: GameFolderConnection) {
        val next = all().filterNot { it.app == connection.app && it.tree == connection.tree } + connection
        require(next.size <= 500) { "Up to 500 game folder connections are supported" }
        check(prefs.edit().putString("game_connections", encode(next)).commit()) { "Could not save folder connection" }
    }
    fun forget(connection: GameFolderConnection) {
        check(prefs.edit().putString("game_connections", encode(all().filterNot { it == connection })).commit())
    }
    fun accessible(tree: String): Boolean = context.contentResolver.persistedUriPermissions.any { it.uri.toString() == tree && it.isReadPermission }
    fun space(app: GameConnectionApp, catalog: UnifiedCatalog): AppSpace {
        val spaces = WorkspaceSpaces.shared(context)
        return spaces.all().firstOrNull { it.app == app.app } ?: spaces.create(app.name, app.app, catalog)
    }
    fun connect(app: GameConnectionApp, tree: Uri, role: GameFolderRole, catalog: UnifiedCatalog): ScanReport {
        require(accessible(tree.toString())) { "Choose this folder again to reconnect its Android permission" }
        val docs = WorkspaceDocuments(context); val root = docs.stat(docs.rootDocument(tree))
        require(root.directory) { "Choose a folder" }
        val space = space(app, catalog)
        catalog.addRoot(tree, root.name); catalog.registerDocument(root, "space:folders")
        WorkspaceSpaces.shared(context).attach(space, "file:${root.uri}", catalog)
        remember(GameFolderConnection(app.app, tree.toString(), role))
        return catalog.scanRoot(tree)
    }
    @Suppress("DEPRECATION") fun apps(): List<GameConnectionApp> {
        val pm = context.packageManager
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }
        val hubs = (PcLauncherCatalog.installedGameHubFamilyPackages(pm) + launchable.filter {
            PcLauncherCatalog.gameHubGeneration(it, pm) != null
        }).distinct()
        val papaya = runCatching { pm.getApplicationInfo(PAPAYA, 0) }.getOrNull()
        val result = mutableListOf(GameConnectionApp(PAPAYA, "Papaya", papaya?.enabled == true, false))
        for (pkg in hubs) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_PROVIDERS)
            val provider = info.providers.orEmpty().firstOrNull {
                it.enabled && it.exported && it.grantUriPermissions && it.name == MT_PROVIDER
            }?.authority?.split(';')?.firstOrNull()
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("GameHub Lite")
            result.add(GameConnectionApp(pkg, label, true, true, provider))
        }
        if (hubs.isEmpty()) result.add(GameConnectionApp("gamehub.lite", "GameHub Lite", false, true))
        all().map { it.app }.distinct().filter { pkg -> result.none { it.app == pkg } }.forEach { pkg ->
            result.add(GameConnectionApp(pkg, "GameHub ($pkg)", false, true))
        }
        return result
    }
    /** Resolve names through provider queries. Document IDs can be opaque and are never path-appended. */
    fun knownFolders(tree: Uri): List<Pair<String, WorkspaceDocument>> {
        val docs = WorkspaceDocuments(context); val root = docs.rootDocument(tree)
        val cache = mutableMapOf<String, List<WorkspaceDocument>>()
        fun children(uri: Uri): List<WorkspaceDocument> = cache.getOrPut(uri.toString()) {
            val listing = docs.children(uri, 2000)
            check(!listing.truncated) { "Too many folders here; select the GameHub files folder directly" }
            listing.items.filter { it.directory }
        }
        fun descend(start: Uri, path: List<String>): WorkspaceDocument? {
            var doc = docs.stat(start)
            for (name in path) doc = children(doc.uri).firstOrNull { it.name == name } ?: return null
            return doc
        }
        val anchors = listOf(emptyList(), listOf("files"), listOf("android_data", "files"), listOf("data", "files"))
        val paths = listOf("Save containers" to listOf("usr", "home", "virtual_containers"),
            "Components" to listOf("usr", "home", "components"), "Steam files" to listOf("Steam", "steamapps"), "Logs" to listOf("logs"))
        return paths.mapNotNull { (label, path) ->
            anchors.firstNotNullOfOrNull { descend(root, it + path) }?.let { label to it }
        }.distinctBy { it.second.uri }
    }
    companion object {
        const val PAPAYA = "com.sebschaef.papaya"
        const val MT_PROVIDER = "bin.mt.file.content.MTDataFilesProvider"
        fun decode(raw: String): List<GameFolderConnection> {
            require(raw.length <= 1_000_000) { "Too many folder connections" }
            val array = JSONArray(raw); require(array.length() <= 500)
            val seen = mutableSetOf<Pair<String, String>>()
            return (0 until array.length()).map { i ->
                val item = array.getJSONObject(i); val app = item.getString("app"); val tree = item.getString("tree")
                require(app.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) && tree.length <= 4096)
                val uri = Uri.parse(tree); require(uri.scheme == "content" && !uri.authority.isNullOrBlank() && DocumentsContract.isTreeUri(uri))
                require(seen.add(app to tree)) { "Duplicate folder connection" }
                GameFolderConnection(app, tree, GameFolderRole.valueOf(item.getString("role")))
            }
        }
        private fun encode(items: List<GameFolderConnection>): String = JSONArray().apply {
            items.forEach { put(JSONObject().put("app", it.app).put("tree", it.tree).put("role", it.role.name)) }
        }.toString()
        fun parseId(name: String, text: String): Pair<String, String?>? {
            if (name.length > 255 || text.length > 256) return null
            val extension = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
            if (extension !in setOf("steam", "steamappid", "localgameid", "txt") || name.substringBeforeLast('.').equals("systeminfo", true)) return null
            val id = text.removePrefix("\uFEFF").trim()
            val numeric = id.matches(Regex("[1-9][0-9]{0,9}")) && id.toIntOrNull() != null
            val local = id.matches(Regex("local_[A-Za-z0-9][A-Za-z0-9_-]{0,119}"))
            if (!numeric && !local) return null
            val source = when (extension) { "steam", "steamappid" -> "STEAM"; "localgameid" -> "LOCAL"; else -> if (local) "LOCAL" else null }
            if (source == "STEAM" && !numeric) return null
            return id to source
        }
    }
    /** Explicit PC-ID review only; generic folder indexing never reads text/ROM payloads. */
    suspend fun scanIds(tree: Uri): PcIdScan {
        require(accessible(tree.toString())) { "Reconnect this folder's permission first" }
        val docs = WorkspaceDocuments(context); val pending = ArrayDeque<Pair<Uri, Int>>()
        pending.add(docs.rootDocument(tree) to 0)
        val visited = mutableSetOf<String>(); val games = mutableListOf<PcIdCandidate>()
        var seen = 0; var unreadable = 0; var partial = false
        while (pending.isNotEmpty() && seen < 6000 && games.size < 500) {
            currentCoroutineContext().ensureActive()
            val (folder, depth) = pending.removeFirst()
            if (!visited.add(folder.toString())) continue
            val listing = try { docs.children(folder, (6000 - seen).coerceAtLeast(1)) } catch (_: Exception) { unreadable++; continue }
            if (listing.truncated) partial = true
            for (file in listing.items) {
                currentCoroutineContext().ensureActive(); seen++
                if (games.size >= 500) { partial = true; break }
                if (file.directory) {
                    if (depth < 12) pending.add(file.uri to depth + 1) else partial = true
                    continue
                }
                if (file.size > 1024 || file.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) !in setOf("steam", "steamappid", "localgameid", "txt")) continue
                try {
                    val text = context.contentResolver.openInputStream(file.uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream(); val bytes = ByteArray(1025)
                        while (out.size() <= 1024) { val n = input.read(bytes, 0, minOf(bytes.size, 1025 - out.size())); if (n < 0) break; if (n == 0) break; out.write(bytes, 0, n) }
                        if (out.size() > 1024) null else out.toString("UTF-8")
                    } ?: continue
                    val parsed = parseId(file.name, text) ?: continue
                    games.add(PcIdCandidate(file.uri.toString(), file.name.substringBeforeLast('.').take(160), parsed.first, parsed.second))
                } catch (_: Exception) { unreadable++ }
            }
        }
        return PcIdScan(games.distinctBy { it.source to it.id }, partial || pending.isNotEmpty(), unreadable)
    }
}

@EntryPoint @InstallIn(SingletonComponent::class)
internal interface WorkspaceGameImportDependencies {
    fun games(): GameRepository
    fun windows(): WindowsLibrarySetup
    fun memoryCards(): MemoryCardRepository
}

internal object WorkspaceGameImport {
    private val lock = Mutex()
    fun launchIntent(context: Context, pkg: String, candidate: PcIdCandidate): Intent {
        require(PcLauncherCatalog.gameHubGeneration(pkg, context.packageManager) != null) { "Install or open the selected GameHub build first" }
        require(WorkspaceGameConnections.parseId("game.${when (candidate.source) { "STEAM" -> "steamappid"; "LOCAL" -> "localgameid"; else -> "txt" }}", candidate.id) != null) { "Invalid game ID" }
        val intent = PcLauncherAdapters.forType(PcLauncherType.GAMEHUB_LITE, context.packageManager)
            ?.buildLaunchIntent(pkg, candidate.id, candidate.source) ?: error("Unsupported game ID")
        // An explicit localGameId export must never be tried as a Steam appid on V5.
        if (candidate.source == "LOCAL") intent.removeExtra("steamAppId")
        return intent
    }
    suspend fun import(context: Context, pkg: String, candidates: List<PcIdCandidate>, space: AppSpace, catalog: UnifiedCatalog): String = lock.withLock {
        require(candidates.size in 1..500)
        val intents = candidates.map { it to launchIntent(context, pkg, it) } // validate before the first database write
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, WorkspaceGameImportDependencies::class.java)
        val result = importReviewed(context, pkg, intents, space, catalog, deps.games())
        runCatching { deps.windows().ensure() }
        runCatching { deps.memoryCards().recountGames("windows") }
        result
    }
    internal suspend fun importReviewed(context: Context, pkg: String, intents: List<Pair<PcIdCandidate, Intent>>, space: AppSpace,
        catalog: UnifiedCatalog, repo: GameRepository): String {
        var added = 0; var matched = 0; val ids = mutableListOf<Long>()
        for ((candidate, intent) in intents) {
            currentCoroutineContext().ensureActive()
            val handle = intent.toUri(Intent.URI_INTENT_SCHEME)
            val existing = repo.getByIntentUri(handle)
            val id = existing?.id ?: repo.upsert(Game(title = candidate.title.ifBlank { candidate.id }, platformId = "windows",
                packageName = pkg, isManualEntry = true, contentType = GameContentType.GAME, launchIntentUri = handle,
                storefront = if (candidate.source == "STEAM") "STEAM" else null,
                storefrontGameId = if (candidate.source == "STEAM") candidate.id else null))
            if (existing == null) added++ else matched++
            ids.add(id)
        }
        catalog.syncPfpGames(repo.observeAll().first())
        ids.forEach { WorkspaceSpaces.shared(context).attach(space, "pfp-game:$it", catalog) }
        return "$added games added, $matched already connected. Original files preserved."
    }
}
