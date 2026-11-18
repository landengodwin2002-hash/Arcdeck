package com.playfieldportal.launcher.workspace

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun WorkspaceGameConnectionsPane(catalog: UnifiedCatalog, back: () -> Unit,
    browse: (String) -> Unit, importLibrary: () -> Unit) {
    val context = LocalContext.current; val connections = remember { WorkspaceGameConnections(context) }
    val spaces = remember { WorkspaceSpaces.shared(context) }; val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<GameConnectionApp>>(emptyList()) }
    var selected by rememberSaveable { mutableStateOf(WorkspaceGameConnections.PAPAYA) }
    var revision by remember { mutableIntStateOf(0) }; var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }; var role by rememberSaveable { mutableStateOf(GameFolderRole.GAMES.name) }
    var pendingApp by rememberSaveable { mutableStateOf<String?>(null) }; var pendingRole by rememberSaveable { mutableStateOf(GameFolderRole.GAMES.name) }
    var reuse by remember { mutableStateOf(false) }; var availableRoots by remember { mutableStateOf<List<CatalogRoot>>(emptyList()) }
    var scan by remember { mutableStateOf<PcIdScan?>(null) }
    var scanApp by remember { mutableStateOf<GameConnectionApp?>(null) }; var launchApp by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<Set<String>>(emptySet()) }
    var shortcuts by remember { mutableStateOf<List<Pair<String, WorkspaceDocument>>>(emptyList()) }
    fun work(block: suspend () -> String) { if (!busy) scope.launch {
        busy = true
        workspaceResult { block() }.onSuccess { status = it }.onFailure { status = it.message ?: "Connection failed" }
        busy = false; revision++
    } }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(revision) { workspaceResult { withContext(Dispatchers.IO) { connections.apps() } }
        .onSuccess { apps = it }.onFailure { status = it.message ?: "Could not detect apps" } }
    val app = apps.firstOrNull { it.app == selected } ?: apps.firstOrNull()
    val linked = remember(selected, revision) { connections.all().filter { it.app == (app?.app ?: selected) } }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val pkg = pendingApp; val chosenRole = pendingRole; pendingApp = null
        if (uri != null && pkg != null) work { withContext(Dispatchers.IO) {
            val target = connections.apps().firstOrNull { it.app == pkg }
                ?: GameConnectionApp(pkg, if (pkg == WorkspaceGameConnections.PAPAYA) "Papaya" else "GameHub", false, pkg != WorkspaceGameConnections.PAPAYA)
            WorkspaceDocuments(context).grant(uri)
            val report = connections.connect(target, uri, GameFolderRole.valueOf(chosenRole), catalog)
            "Connected to ${target.name}. ${report.status}. No files copied."
        } }
        else status = "Folder selection cancelled; existing connections preserved"
    }
    fun pick(target: GameConnectionApp, hint: Uri? = null, chosenRole: GameFolderRole = GameFolderRole.valueOf(role)) {
        if (busy) return
        pendingApp = target.app; pendingRole = chosenRole.name
        try { folder.launch(hint) } catch (e: Exception) { pendingApp = null; status = e.message ?: "Android's folder picker is unavailable" }
    }
    BackHandler { if (scan != null) scan = null else if (reuse) reuse = false else back() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { if (scan != null) scan = null else if (reuse) reuse = false else back() }) { Text("Back to spaces") }
            TextButton(enabled = !busy, onClick = { revision++ }) { Text("Refresh apps") }
        }
        Text("Game connections", style = MaterialTheme.typography.titleMedium)
        if (scan == null && !reuse) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                apps.forEach { item -> FilterChip(item.app == (app?.app ?: selected), { selected = item.app; shortcuts = emptyList(); revision++ }, label = { Text(item.name) }) }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val review = scan
        if (review != null) {
            Text("Review ${review.games.size} PC game IDs")
            Text("Launch with the selected GameHub build. Existing source files stay in place. Only recognized small ID files are listed.", style = MaterialTheme.typography.bodySmall)
            if (review.partial || review.unreadable > 0) Text("Scan incomplete: ${review.unreadable} unreadable folders/files. Pick a smaller folder if games are missing.")
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                apps.filter { it.gameHub && it.installed }.forEach { hub ->
                    FilterChip(launchApp == hub.app, { launchApp = hub.app }, label = { Text(hub.name) })
                }
            }
            if (launchApp.isBlank()) Text("Install/open GameHub Lite, then refresh apps to import these IDs. ROMs use PFP Library Manager.")
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { chosen = review.games.map { it.uri }.toSet() }) { Text("Select all") }
                TextButton(onClick = { chosen = emptySet() }) { Text("Select none") }
                TextButton(enabled = !busy && chosen.isNotEmpty() && launchApp.isNotBlank(), onClick = { work { withContext(Dispatchers.IO) {
                    val host = requireNotNull(scanApp); val targetSpace = connections.space(host, catalog)
                    val message = WorkspaceGameImport.import(context, launchApp, review.games.filter { it.uri in chosen }, targetSpace, catalog)
                    scan = null; message
                } } }) { Text("Import ${chosen.size} games") }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(review.games, key = { it.uri }) { game -> Row(Modifier.fillMaxWidth()) {
                    Checkbox(game.uri in chosen, { checked -> chosen = if (checked) chosen + game.uri else chosen - game.uri })
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(game.title); Text("${game.source ?: "Auto"} · ${game.id}", style = MaterialTheme.typography.bodySmall)
                    }
                } }
                if (review.games.isEmpty()) item { Text("No supported IDs found. Use .steam, .steamappid, .localgameid or a small .txt containing only a game ID. ROMs and real ISO images are indexed as files.") }
            }
        } else if (reuse) {
            Text("Choose an already connected folder")
            LazyColumn(Modifier.weight(1f)) {
                items(availableRoots, key = { it.uri }) { root -> TextButton(enabled = !busy, onClick = { val target = app
                    if (target != null) work { withContext(Dispatchers.IO) {
                        val report = connections.connect(target, Uri.parse(root.uri), GameFolderRole.valueOf(role), catalog)
                        reuse = false; "Folder shared with ${target.name}. ${report.status}"
                    } }
                }) { Text("${root.label} · ${if (connections.accessible(root.uri)) "use existing permission" else "reconnect permission first"}") } }
                if (availableRoots.isEmpty()) item { Text("Connect a folder first using Android's folder picker.") }
            }
        } else if (app != null) {
            Text("${if (app.installed) "Installed" else "Not detected"} · ${app.app}", style = MaterialTheme.typography.bodySmall)
            Text(if (app.gameHub) "Connect existing game folders or this build's file provider. Saves and components stay with GameHub."
                else "Choose the same ROM, PC-ID, artwork and save folders you already use in Papaya. Papaya's private library/settings stay in Papaya.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(enabled = app.installed && !busy, onClick = { work {
                    val space = withContext(Dispatchers.IO) { connections.space(app, catalog) }
                    WorkspaceOverlayService.ensureEnabled(context); WorkspaceApps.launch(context, space); ""
                } }) { Text("Open ${app.name}") }
                TextButton(enabled = !busy, onClick = { work {
                    val space = withContext(Dispatchers.IO) { connections.space(app, catalog).also { spaces.select(it.id) } }
                    back(); "${space.name} space ready"
                } }) { Text("Open app space") }
                TextButton(onClick = importLibrary) { Text("Import ROMs / PFP exports") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) { GameFolderRole.entries.forEach { item ->
                FilterChip(role == item.name, { role = item.name }, label = { Text(item.label) })
            } }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(enabled = !busy, onClick = { pick(app) }) { Text("Connect existing folder") }
                TextButton(enabled = !busy, onClick = { work { availableRoots = withContext(Dispatchers.IO) { catalog.roots() }; reuse = true; "" } }) { Text("Use connected folder") }
                if (app.provider != null) TextButton(enabled = !busy, onClick = { pick(app, app.initialUri, GameFolderRole.DATA) }) { Text("Connect GameHub provider") }
            }
            Text(if (app.provider != null) "GameHub file provider detected. Android will ask which folder to allow."
                else "Android blocks other apps' private data. Use shared folders, or enable a file provider/export in the source app. No root access is needed for shared folders.", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f)) {
                items(linked, key = { it.tree }) { link ->
                    val root = remember(link.tree, revision) { catalog.roots().firstOrNull { it.uri == link.tree } }
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text("${link.role.label} · ${root?.label ?: "Saved folder"}")
                        Text(if (connections.accessible(link.tree)) root?.status ?: "Ready" else "Permission missing · reconnect this folder", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(enabled = !busy && connections.accessible(link.tree), onClick = { browse(WorkspaceDocuments(context).rootDocument(Uri.parse(link.tree)).toString()) }) { Text("Browse") }
                            TextButton(enabled = !busy, onClick = { pick(app, Uri.parse(link.tree), link.role) }) { Text("Reconnect") }
                            TextButton(enabled = !busy && connections.accessible(link.tree), onClick = { work { withContext(Dispatchers.IO) { catalog.scanRoot(Uri.parse(link.tree)).status } } }) { Text("Rescan") }
                            if (link.role == GameFolderRole.IDS || link.role == GameFolderRole.GAMES) TextButton(enabled = !busy && connections.accessible(link.tree), onClick = { work {
                                val result = withContext(Dispatchers.IO) { connections.scanIds(Uri.parse(link.tree)) }
                                scan = result; scanApp = app; chosen = result.games.map { it.uri }.toSet()
                                launchApp = if (app.gameHub && app.installed) app.app else apps.firstOrNull { it.gameHub && it.installed }?.app ?: ""
                                ""
                            } }) { Text("Review PC IDs") }
                            if (app.gameHub) TextButton(enabled = !busy && connections.accessible(link.tree), onClick = { work {
                                shortcuts = withContext(Dispatchers.IO) { connections.knownFolders(Uri.parse(link.tree)) }
                                if (shortcuts.isEmpty()) "No standard GameHub folders here. Browse to choose the correct app/files directory." else "Located ${shortcuts.size} GameHub folders"
                            } }) { Text("Find saves / components") }
                            TextButton(enabled = !busy, onClick = { work { withContext(Dispatchers.IO) {
                                connections.forget(link)
                                spaces.all().filter { it.app == app.app }.forEach { space ->
                                    catalog.removeFromCollection(spaces.collectionId(space, catalog), "file:${WorkspaceDocuments(context).rootDocument(Uri.parse(link.tree))}")
                                }
                                "Disconnected from this app. Folder, files and global Library index preserved."
                            } } }) { Text("Disconnect") }
                        }
                    }
                }
                items(shortcuts, key = { it.second.uri.toString() }) { (label, doc) -> Row {
                    TextButton(onClick = { browse(doc.uri.toString()) }) { Text("Browse $label") }
                    TextButton(enabled = !busy, onClick = { work { withContext(Dispatchers.IO) {
                        catalog.registerDocument(doc, "space:folders"); spaces.attach(connections.space(app, catalog), "file:${doc.uri}", catalog)
                        "$label linked to ${app.name} space"
                    } } }) { Text("Pin folder to space") }
                } }
                if (linked.isEmpty()) item { Text("Connect folders already on your phone. You can connect the same folder to both apps without making a copy.", Modifier.padding(8.dp)) }
            }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 4)
    }
}
