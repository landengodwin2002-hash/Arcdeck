package com.playfieldportal.launcher.workspace

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.*
import com.playfieldportal.core.data.platform.PlatformFolderHintResolver
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.data.repository.RomRootRepository
import com.playfieldportal.core.domain.model.Platform
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.launcher.EmulatorAutoConfigService
import com.playfieldportal.feature.launcher.EmulatorDetector
import com.playfieldportal.feature.launcher.PcLauncherCatalog
import com.playfieldportal.feature.library.scanner.RomRootDiscoveryScanner
import com.playfieldportal.feature.settings.pc.PcGameScanner
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

@EntryPoint @InstallIn(SingletonComponent::class)
internal interface AutoLibraryDependencies {
    fun cards(): MemoryCardRepository
    fun roots(): RomRootRepository
    fun discovery(): RomRootDiscoveryScanner
    fun pc(): PcGameScanner
    fun games(): GameRepository
    fun emulators(): EmulatorAutoConfigService
    fun detector(): EmulatorDetector
}

internal class PhoneGameClassifier(platforms: List<Platform>, private val hints: PlatformFolderHintResolver = PlatformFolderHintResolver()) {
    private val catalog = platforms.associateBy { it.id }
    private val byExtension = platforms.filter { it.id !in setOf("android", "windows") }.flatMap { platform -> platform.romExtensions.map { it.lowercase().removePrefix(".") to platform.id } }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.distinct() }
    val extensions = byExtension.keys
    fun classify(path: String, name: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase()
        val candidates = byExtension[extension].orEmpty()
        if (candidates.isEmpty() || name.lowercase().matches(Regex("(?:scph.*|bios.*|firmware.*)\\.(?:bin|rom|img)"))) return null
        path.split('/').asReversed().forEach { segment ->
            if (segment.lowercase() !in setOf("game", "games", "rom", "roms", "iso", "isos")) {
                val cleaned = segment.lowercase().replace(Regex("[ _-]*(?:roms|games|isos)$"), "").trim()
                val hint = hints.detectFromFolderName(segment) ?: hints.detectFromFolderName(cleaned)
                if (hint != null && hint in candidates && hint in catalog) return hint
            }
        }
        return candidates.singleOrNull()
    }
    fun skipFolder(name: String): Boolean = name.lowercase() in setOf(".git", ".cache", ".trash", "cache", "shadercache", "shaders", "system32", "syswow64", "savestates", "saves", "screenshots", "thumbnails", "artwork", "bios", "firmware")
}

internal object WorkspaceAutoLibrary {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var foreground: Job? = null
    private val updates = MutableStateFlow("")
    val status = updates.asStateFlow()
    private var last = 0L
    fun enabled(context: Context) = context.getSharedPreferences("arcdeck_workspace", 0).getBoolean("phone_auto_library", true)
    fun schedule(context: Context) {
        runCatching { scheduleInternal(context) }
    }
    private fun scheduleInternal(context: Context) {
        if (!enabled(context)) { WorkManager.getInstance(context).cancelUniqueWork("arcdeck-phone-library"); return }
        val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("arcdeck-phone-library", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PhoneLibraryWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
    }
    fun kickoff(context: Context, force: Boolean = false) {
        if (context.applicationContext !is dagger.hilt.internal.GeneratedComponentManager<*>) return
        if (!enabled(context) || foreground?.isActive == true || (!force && System.currentTimeMillis() - last < 300_000)) return
        val app = context.applicationContext
        foreground = scope.launch {
            try {
                do { val more = scan(app, force); if (more) delay(250) } while (more && isActive)
                last = System.currentTimeMillis()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { updates.value = e.message ?: "Automatic library update could not finish" }
        }
    }
    fun stopForeground() { foreground?.cancel(); foreground = null }
    private fun identity(uri: Uri): String {
        val id = DocumentsContract.getDocumentId(uri)
        return RomRootRepository.rawPathOfDocument(uri.authority, id)?.let { "path:$it" } ?: "${uri.authority}:$id"
    }
    suspend fun scan(context: Context, force: Boolean = false): Boolean = lock.withLock {
        if (!enabled(context)) return@withLock false
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, AutoLibraryDependencies::class.java)
        val catalog = UnifiedCatalog.shared(context); val documents = WorkspaceDocuments(context)
        val store = WorkspacePhoneStore(context)
        try {
            val platforms = deps.cards().availablePlatformCatalog()
            if (platforms.isEmpty()) { updates.value = "PFP is preparing its console catalog; automatic discovery will retry"; return@withLock false }
            val classifier = PhoneGameClassifier(platforms)
            workspaceResult { deps.emulators().runOnStartup() }
            val connections = WorkspaceGameConnections(context)
            val installedGameApps = connections.apps().filter { it.installed }
            val gamePackages = (deps.detector().detect().map { it.packageName } + installedGameApps.map { it.app }).distinct()
            if (WorkspaceNative.ready()) runCatching { WorkspaceNative.files(JSONObject().put("op", "configure_games").put("packages", JSONArray(gamePackages))) }
            if (store.pending() == 0) {
                val roots = mutableListOf<Uri>()
                context.contentResolver.persistedUriPermissions.filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) && !WorkspaceStorage.owns(context, it.uri) }.forEach { roots.add(it.uri) }
                if (WorkspaceStorage.allowed(context)) {
                    WorkspaceStorage.roots(context).forEach { roots.add(WorkspacePhoneStore.tree(context, it)) }
                    if (WorkspaceNative.ready()) gamePackages.forEach { pkg ->
                        val path = "${android.os.Environment.getExternalStorageDirectory().absolutePath}/Android/data/$pkg/files"
                        if (runCatching { WorkspaceStorage.stat(context, path).directory }.getOrDefault(false)) {
                            val tree = WorkspacePhoneStore.tree(context, path); roots.add(tree)
                            installedGameApps.firstOrNull { it.app == pkg }?.let { gameApp ->
                                val source = documents.stat(documents.rootDocument(tree)); val space = connections.space(gameApp, catalog)
                                catalog.addRoot(tree, "${gameApp.name} · app files"); catalog.registerDocument(source, "space:folders")
                                WorkspaceSpaces.shared(context).attach(space, "file:${source.uri}", catalog)
                                connections.remember(GameFolderConnection(pkg, tree.toString(), GameFolderRole.DATA))
                            }
                        }
                    }
                }
                if (roots.isEmpty()) { updates.value = "Allow phone storage, connect Shizuku, or grant an existing game folder once"; return@withLock false }
                store.begin(roots.distinct().map { documents.rootDocument(it).toString() })
            }
            val generation = store.getState("generation", "0").toLong()
            var failures = store.getState("errors", "0").toInt(); var visited = store.getState("visited", "0").toInt()
            var entries = 0; val start = android.os.SystemClock.elapsedRealtime()
            while (store.pending() > 0 && entries < 20000 && android.os.SystemClock.elapsedRealtime() - start < 20000) {
                currentCoroutineContext().ensureActive()
                val next = store.next() ?: break; val folder = Uri.parse(next.first); val folderIdentity = identity(folder)
                if (store.visited(folderIdentity)) { store.done(next.first); continue }
                updates.value = "Discovering phone games · ${store.count("ROM")} recognized · $visited folders"
                val children = try { documents.children(folder, 20001) } catch (_: Exception) {
                    failures++; store.setState("errors", "$failures"); store.done(next.first); continue
                }
                if (children.truncated) failures++
                val records = mutableListOf<PhoneGameFile>(); val dirs = mutableListOf<String>(); val indexed = mutableListOf<CatalogEntry>()
                val folderPath = RomRootRepository.rawPathOfDocument(folder.authority, DocumentsContract.getDocumentId(folder)) ?: DocumentsContract.getDocumentId(folder)
                val companionPlatform = children.items.filter { !it.directory && it.name.substringAfterLast('.', "").lowercase() in setOf("cue", "m3u", "ccd", "gdi") }
                    .mapNotNull { classifier.classify(folderPath, it.name) }.distinct().singleOrNull()
                for (file in children.items) {
                    currentCoroutineContext().ensureActive(); entries++
                    if (file.directory) { if (next.second < 32 && !classifier.skipFolder(file.name)) dirs.add(file.uri.toString()); else if (next.second >= 32) failures++; continue }
                    val ext = file.name.substringAfterLast('.', "").lowercase()
                    val appIdText = ext == "txt" && file.size <= 1024 && installedGameApps.any { it.gameHub && folderPath.split('/').contains(it.app) }
                    val pc = appIdText || ext in setOf("steam", "steamappid", "localgameid", "desktop", "pfpgame", "droiddeck", "pcgame", "gog", "epic", "amazon")
                    val known = ext in classifier.extensions
                    val companion = companionPlatform != null && ext in setOf("wav", "ogg", "flac", "ape", "sub", "sbi", "raw")
                    if (!pc && !known && !companion) continue
                    val platform = if (pc) "windows" else if (companion) companionPlatform.orEmpty() else classifier.classify(folderPath, file.name).orEmpty()
                    val raw = RomRootRepository.rawPathOfDocument(file.uri.authority, DocumentsContract.getDocumentId(file.uri))
                    val id = raw ?: WorkspacePhoneStore.sourceId(file.uri)
                    val kind = if (pc) "PC" else if (companion) "AUX" else if (platform.isEmpty()) "UNSORTED" else "ROM"
                    records.add(PhoneGameFile(id, file.uri.toString(), file.name, platform, file.size, 0, kind))
                    indexed.add(CatalogEntry("file:${file.uri}", "FILE", file.name, if (platform.isEmpty()) "Unsorted game candidate" else "Automatic $platform game source", file.uri.toString(), "phone:auto", parentUri = folder.toString()))
                }
                val db = store.writableDatabase; db.beginTransaction()
                try {
                    records.forEach { store.upsert(it, generation) }; dirs.forEach { store.enqueue(it, next.second + 1) }
                    store.visit(folderIdentity); store.done(next.first); visited++; store.setState("visited", "$visited"); store.setState("errors", "$failures"); db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                if (indexed.isNotEmpty()) catalog.syncSource("phone:auto", indexed, complete = false)
            }
            val more = store.pending() > 0
            if (!more) {
                store.finish(failures == 0, generation)
                val tree = WorkspacePhoneStore.tree(context)
                deps.roots().add(tree.toString())
                val report = deps.discovery().discover(listOf(tree.toString()))
                workspaceResult { deps.emulators().runOnStartup() }
                workspaceResult { deps.pc().scan(WorkspacePhoneStore.tree(context, "auto/windows/import")) }
                workspaceResult { importIds(context, store, catalog) }
                catalog.syncPfpGames(deps.games().observeAll().first())
                val doc = documents.stat(documents.rootDocument(tree)); catalog.addRoot(tree, "Phone games · automatic"); catalog.registerDocument(doc, "space:folders")
                updates.value = "Phone library updated · ${store.count("ROM")} ROM files · ${report.discoveredPlatforms.size} consoles · ${store.count("UNSORTED")} unclassified · $failures unreadable sources"
            }
            catalog.syncAndroidApps()
            more
        } finally { store.close() }
    }
    private suspend fun importIds(context: Context, store: WorkspacePhoneStore, catalog: UnifiedCatalog) {
        val connections = WorkspaceGameConnections(context)
        val hubs = connections.apps().filter { it.installed && it.gameHub }
        if (hubs.isEmpty()) return
        val byApp = mutableMapOf<String, MutableList<PcIdCandidate>>()
        for (file in store.files(kind = "PC")) {
            currentCoroutineContext().ensureActive()
            if (file.name.substringAfterLast('.', "").lowercase() !in setOf("steamappid", "localgameid", "txt") || file.size > 1024) continue
            val path = runCatching { DocumentsContract.getDocumentId(Uri.parse(file.uri)) }.getOrDefault(file.uri)
            val hub = hubs.firstOrNull { path.split('/').contains(it.app) || path.contains(":" + it.app + "/") } ?: hubs.singleOrNull() ?: continue
            val text = runCatching { context.contentResolver.openInputStream(Uri.parse(file.uri))?.use { input ->
                val bytes = ByteArray(1025); var used = 0
                while (used < bytes.size) { val n = input.read(bytes, used, bytes.size - used); if (n <= 0) break; used += n }
                if (used > 1024) null else String(bytes, 0, used, Charsets.UTF_8)
            } }.getOrNull() ?: continue
            val parsed = WorkspaceGameConnections.parseId(file.name, text) ?: continue
            byApp.getOrPut(hub.app) { mutableListOf() }.add(PcIdCandidate(file.uri, file.name.substringBeforeLast('.').take(160), parsed.first, parsed.second))
        }
        for ((pkg, candidates) in byApp) {
            val space = connections.space(hubs.first { it.app == pkg }, catalog)
            candidates.distinctBy { it.source to it.id }.chunked(500).forEach { WorkspaceGameImport.import(context, pkg, it, space, catalog) }
        }
    }
}

class PhoneLibraryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        withContext(Dispatchers.Main) { WorkspaceNative.initialize(applicationContext) }
        WorkspaceAutoLibrary.scan(applicationContext); Result.success()
    } catch (e: CancellationException) { throw e } catch (_: Exception) { Result.retry() }
}
