package com.playfieldportal.launcher.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class LibrarySnapshot(val entries: List<CatalogEntry>, val collections: List<CatalogCollection>,
    val roots: List<CatalogRoot>, val errors: List<String>)

@Composable
internal fun UnifiedLibraryPane(
    catalog: UnifiedCatalog,
    sourceRevision: Int,
    sourceError: String?,
    initiallyCollections: Boolean,
    onOpenPfpGame: (Long) -> Unit,
    onSetPfpFavorite: (Long, Boolean) -> Unit,
    onRunCommand: (String) -> Unit,
    onOpenWorkspaceTool: (String) -> Unit,
    onBrowseFolder: (String) -> Unit,
    initialQuery: String = "",
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val revision by catalog.revision.collectAsState()
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var page by remember(query, revision, sourceRevision) { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<CatalogEntry>>(emptyList()) }
    var collections by remember { mutableStateOf<List<CatalogCollection>>(emptyList()) }
    var roots by remember { mutableStateOf<List<CatalogRoot>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var queryError by remember { mutableStateOf("") }
    var showCollections by rememberSaveable { mutableStateOf(initiallyCollections) }
    var selected by remember { mutableStateOf<CatalogEntry?>(null) }
    var collectionEditor by remember { mutableStateOf<CatalogCollection?>(null) }
    var newShortcut by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }

    fun work(block: suspend () -> String) {
        if (busy) return
        scope.launch {
            busy = true
            workspaceResult { block() }.onSuccess { notice = it }.onFailure { notice = it.message ?: "Operation failed" }
            busy = false
        }
    }
    fun mutate(message: String = "Saved", block: () -> Unit) = work {
        withContext(Dispatchers.IO) { block() }; message
    }
    LaunchedEffect(query, revision, sourceRevision, page) {
        loading = true
        delay(120)
        workspaceResult { withContext(Dispatchers.IO) {
            val errors = catalog.queryErrors(query)
            val found = if (errors.isEmpty()) catalog.search(query, 101, page * 100) else emptyList()
            LibrarySnapshot(found, catalog.collections(), catalog.roots(), errors)
        } }.onSuccess { snapshot ->
            val found = snapshot.entries
            val cols = snapshot.collections
            val folders = snapshot.roots
            val errors = snapshot.errors
            results = if (page == 0) found.take(100) else (results + found.take(100)).distinctBy { it.key }
            collections = cols; roots = folders; queryError = errors.joinToString(" · ")
            hasMore = found.size > 100
        }.onFailure { queryError = it.message ?: "Search unavailable" }
        loading = false
    }
    fun open(entry: CatalogEntry) = work {
        require(!entry.missing) { "Source is unavailable. Reconnect its app, game library or storage folder." }
        when (entry.kind) {
            "GAME", "SHORTCUT" -> {
                val id = entry.target.toLongOrNull() ?: error("Game reference is unavailable")
                withContext(Dispatchers.IO) { catalog.markOpened(entry.key) }
                onOpenPfpGame(id)
            }
            "APP" -> {
                val intent = context.packageManager.getLaunchIntentForPackage(entry.target) ?: error("App is no longer installed")
                withContext(Dispatchers.IO) { catalog.markOpened(entry.key) }
                context.startActivity(intent)
            }
            "FOLDER" -> { onBrowseFolder(entry.target) }
            "FILE" -> {
                val game = withContext(Dispatchers.IO) { catalog.gameIdForFile(entry.target) }
                if (game != null) onOpenPfpGame(game)
                else {
                    val document = withContext(Dispatchers.IO) { WorkspaceDocuments(context).stat(Uri.parse(entry.target)) }
                    openWorkspaceDocument(context, document)
                }
            }
            "COMMAND" -> onRunCommand(entry.target)
            "LINK" -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.target)))
            "TOOL" -> if (entry.target.startsWith("arcdeck:")) onOpenWorkspaceTool(entry.target.removePrefix("arcdeck:"))
                else context.startActivity(Intent(entry.target))
        }
        ""
    }
    BackHandler(enabled = showCollections) { showCollections = false }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) work { withContext(Dispatchers.IO) {
            val data = WorkspaceBackup.export(context, catalog)
            (context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot write backup"))
                .bufferedWriter().use { it.write(data) }
            "Backup saved: organization, notes and window layout"
        } }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { restoreUri = it }
    val pickRoot = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) work { withContext(Dispatchers.IO) {
            val documents = WorkspaceDocuments(context)
            documents.grant(uri)
            catalog.addRoot(uri, documents.stat(documents.rootDocument(uri)).name)
            catalog.scanRoot(uri).status
        } }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(query, { if (it.length <= 512) query = it }, label = { Text("Search games, apps, files and tools") },
            modifier = Modifier.fillMaxWidth(), singleLine = true, isError = queryError.isNotBlank(),
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") } })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("All" to "", "Games" to "type:game", "Apps" to "type:app", "Files" to "source:files",
                "Favorites" to "is:favorite", "Pinned" to "is:pinned", "Recent" to "sort:recent", "Missing" to "is:missing")
                .forEach { (label, filter) -> FilterChip(selected = query == filter && !showCollections,
                    onClick = { query = filter; showCollections = false }, label = { Text(label) }) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { focus.clearFocus(); showCollections = !showCollections }) { Text(if (showCollections) "Search" else "Collections & storage") }
            TextButton(enabled = !busy, onClick = { pickRoot.launch(null) }) { Text("Add folder") }
            TextButton(enabled = !busy, onClick = { newShortcut = true }) { Text("Add shortcut") }
            TextButton(onClick = { collectionEditor = CatalogCollection(0, "", query.takeIf { it.isNotBlank() }) }) { Text("Save collection") }
            TextButton(enabled = !busy, onClick = { mutate("Apps refreshed") { catalog.syncAndroidApps(); catalog.syncSystemTools() } }) { Text("Refresh apps") }
        }
        if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (queryError.isNotBlank()) Text(queryError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (notice.isNotBlank()) Row(Modifier.fillMaxWidth()) {
            Text(notice, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 3)
            TextButton(onClick = { notice = "" }) { Text("×") }
        }
        if (sourceError != null) Text(sourceError, style = MaterialTheme.typography.bodySmall)
        if (showCollections) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(enabled = !busy, onClick = { collectionEditor = CatalogCollection(0, "", null) }) { Text("New collection") }
                TextButton(enabled = !busy, onClick = { export.launch("ArcDeck-backup.json") }) { Text("Backup") }
                TextButton(enabled = !busy, onClick = { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Restore") }
                TextButton(enabled = !busy && roots.isNotEmpty(), onClick = { work {
                    withContext(Dispatchers.IO) { roots.forEach { catalog.scanRoot(Uri.parse(it.uri)) } }
                    "Storage scan complete"
                } }) { Text("Scan all folders") }
            }
            LazyColumn(Modifier.weight(1f)) {
                item { Text("Collections", fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp)) }
                if (collections.isEmpty()) item { Text("Create a manual collection or save a search that updates automatically.", Modifier.padding(8.dp)) }
                items(collections, key = { "collection:${it.id}" }) { col ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { query = "in:${col.id}" + if (col.expression == null) " sort:manual" else ""; showCollections = false }.padding(8.dp)) {
                            Text((if (col.expression != null) "◇  " else "▤  ") + col.name)
                            if (col.expression != null) Text(col.expression, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                        TextButton(onClick = { collectionEditor = col }) { Text("Edit") }
                    }
                }
                item { Text("Indexed folders", fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp)) }
                if (roots.isEmpty()) item { Text("Add a folder to index files and ROMs in universal search.", Modifier.padding(8.dp)) }
                items(roots, key = { "root:${it.uri}" }) { root ->
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        Text(root.label, fontWeight = FontWeight.Medium)
                        Text(root.status, style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(onClick = { onBrowseFolder(WorkspaceDocuments(context).rootDocument(Uri.parse(root.uri)).toString()) }) { Text("Browse") }
                            TextButton(enabled = !busy, onClick = { work { withContext(Dispatchers.IO) { catalog.scanRoot(Uri.parse(root.uri)).status } } }) { Text("Scan") }
                            TextButton(enabled = !busy, onClick = { confirm = "Remove ${root.label} from the index? Files remain in storage." to {
                                mutate("Folder removed from index") { catalog.removeRoot(root.uri) }
                            } }) { Text("Remove") }
                        }
                    }
                }
            }
        } else {
            Text("${results.size}${if (hasMore) "+" else ""} results", style = MaterialTheme.typography.labelSmall)
            LazyColumn(Modifier.weight(1f)) {
                items(results, key = { it.key }) { entry ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clickable(enabled = !busy) { open(entry) }.padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            CatalogArtwork(entry)
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text((entry.alias ?: entry.title) + (if (entry.favorite) " ★" else "") + (if (entry.pinned) " · pinned" else ""),
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${entry.kind.lowercase()} · ${if (entry.missing) "source missing" else entry.subtitle.ifBlank { entry.source }}",
                                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        TextButton(enabled = !busy, onClick = { focus.clearFocus(); selected = entry }) { Text("⋮") }
                    }
                }
                if (results.isEmpty() && !loading) item {
                    Text(if (query.isBlank()) "Connect a game library or add a storage folder to begin." else "No matching items. Change the filters or scan a folder.", Modifier.padding(10.dp))
                }
                if (hasMore) item { TextButton(enabled = !loading && !busy, onClick = { page++ }) { Text("Load more") } }
            }
        }
    }
    selected?.takeIf { confirm == null }?.let { entry ->
        var alias by remember(entry.key) { mutableStateOf(entry.alias ?: "") }
        var title by remember(entry.key) { mutableStateOf(entry.title) }
        var target by remember(entry.key) { mutableStateOf(entry.target) }
        var tag by remember(entry.key) { mutableStateOf("") }
        var tags by remember(entry.key) { mutableStateOf<List<String>>(emptyList()) }
        var collectionFilter by remember(entry.key) { mutableStateOf("") }
        LaunchedEffect(entry.key, revision) { tags = withContext(Dispatchers.IO) { catalog.tags(entry.key) } }
        WorkspaceDialog(onDismissRequest = { selected = null }, title = { Text(entry.title, maxLines = 2) }, text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${entry.kind.lowercase()} · ${entry.source}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(alias, { alias = it.take(120) }, label = { Text("Display alias") }, singleLine = true)
                TextButton(enabled = !busy, onClick = { mutate { catalog.rename(entry.key, alias) } }) { Text("Save display alias") }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(enabled = !busy, onClick = {
                        if (entry.kind in setOf("GAME", "SHORTCUT")) {
                            entry.target.toLongOrNull()?.let { onSetPfpFavorite(it, !entry.favorite) }
                        } else mutate { catalog.setFavorite(entry.key, !entry.favorite) }
                        selected = null
                    }) { Text(if (entry.favorite) "Unfavorite" else "Favorite") }
                    TextButton(enabled = !busy, onClick = { mutate { catalog.setPinned(entry.key, !entry.pinned) }; selected = null }) {
                        Text(if (entry.pinned) "Unpin" else "Pin")
                    }
                }
                if (entry.kind in setOf("FILE", "FOLDER") && entry.target.isNotBlank()) {
                    TextButton(onClick = { onBrowseFolder(if (entry.kind == "FOLDER") entry.target else entry.parentUri
                        ?: WorkspaceDocuments(context).rootDocument(Uri.parse(entry.target)).toString()); selected = null }) { Text("Show in Files") }
                }
                if (entry.source == "user") {
                    OutlinedTextField(title, { title = it.take(120) }, label = { Text("Shortcut name") })
                    OutlinedTextField(target, { target = it.take(2048) }, label = { Text(if (entry.kind == "COMMAND") "Command" else "URL") })
                    TextButton(enabled = !busy, onClick = { mutate { catalog.updateCustom(entry.key, title, target) }; selected = null }) { Text("Save shortcut") }
                    TextButton(enabled = !busy, onClick = { confirm = "Delete this custom shortcut?" to {
                        mutate("Shortcut deleted") { catalog.deleteCustom(entry.key) }; selected = null
                    } }) { Text("Delete shortcut…") }
                }
                Text("Tags", fontWeight = FontWeight.Medium)
                tags.forEach { value -> TextButton(enabled = !busy, onClick = { mutate { catalog.removeTag(entry.key, value) } }) { Text("Remove: $value") } }
                Row {
                    OutlinedTextField(tag, { tag = it.take(48) }, label = { Text("Add a tag") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(enabled = tag.isNotBlank() && !busy, onClick = { val value = tag; tag = ""; mutate { catalog.addTag(entry.key, value) } }) { Text("Add") }
                }
                val active = UnifiedQuery.parse(query).collection?.let { id -> collections.find { it.id.toString() == id && it.expression == null } }
                if (active != null) Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(enabled = !busy, onClick = { mutate { catalog.shiftInCollection(active.id, entry.key, -1) }; selected = null }) { Text("Move up") }
                    TextButton(enabled = !busy, onClick = { mutate { catalog.shiftInCollection(active.id, entry.key, 1) }; selected = null }) { Text("Move down") }
                    TextButton(enabled = !busy, onClick = { mutate { catalog.removeFromCollection(active.id, entry.key) }; selected = null }) { Text("Remove here") }
                }
                Text("Add to a manual collection", fontWeight = FontWeight.Medium)
                OutlinedTextField(collectionFilter, { collectionFilter = it }, label = { Text("Find collection") }, singleLine = true)
                collections.filter { it.expression == null && it.name.contains(collectionFilter, true) }.take(50).forEach { col ->
                    TextButton(enabled = !busy, onClick = { mutate("Added to ${col.name}") { catalog.addToCollection(col.id, entry.key) } }) { Text("+ ${col.name}") }
                }
            }
        }, confirmButton = { TextButton(enabled = !busy && !entry.missing, onClick = { selected = null; open(entry) }) { Text("Open") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Close") } })
    }
    collectionEditor?.takeIf { confirm == null }?.let { col ->
        var name by remember(col.id) { mutableStateOf(col.name) }
        var expression by remember(col.id) { mutableStateOf(col.expression ?: "") }
        var error by remember(col.id) { mutableStateOf("") }
        WorkspaceDialog(onDismissRequest = { collectionEditor = null }, title = { Text(if (col.id == 0L) "New collection" else "Edit collection") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("Name") })
                OutlinedTextField(expression, { expression = it.take(512) }, label = { Text("Smart query; leave blank for manual") })
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (col.id != 0L) TextButton(onClick = { confirm = "Delete ${col.name}? Its games and files stay in their original locations." to {
                    mutate("Collection deleted") { catalog.deleteCollection(col.id) }; collectionEditor = null
                } }) { Text("Delete collection…") }
            }
        }, confirmButton = { TextButton(enabled = !busy && name.isNotBlank(), onClick = {
            scope.launch {
                workspaceResult { withContext(Dispatchers.IO) {
                    if (col.id == 0L) catalog.createCollection(name, expression) else catalog.updateCollection(col.id, name, expression)
                } }.onSuccess { collectionEditor = null }.onFailure { error = it.message ?: "Could not save collection" }
            }
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = { collectionEditor = null }) { Text("Cancel") } })
    }
    if (newShortcut) {
        var name by remember { mutableStateOf("") }
        var target by remember { mutableStateOf("") }
        var command by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        WorkspaceDialog(onDismissRequest = { newShortcut = false }, title = { Text("New shortcut") }, text = {
            Column {
                OutlinedTextField(name, { name = it.take(120) }, label = { Text("Name") })
                OutlinedTextField(target, { target = it.take(2048) }, label = { Text(if (command) "Termux command" else "https:// URL") })
                TextButton(onClick = { command = !command }) { Text(if (command) "Type: command" else "Type: website") }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(enabled = name.isNotBlank() && target.isNotBlank(), onClick = { scope.launch {
            workspaceResult { withContext(Dispatchers.IO) { catalog.addCustom(if (command) "COMMAND" else "LINK", name, target) } }
                .onSuccess { newShortcut = false }.onFailure { error = it.message ?: "Could not add shortcut" }
        } }) { Text("Add") } }, dismissButton = { TextButton(onClick = { newShortcut = false }) { Text("Cancel") } })
    }
    confirm?.let { (text, action) -> WorkspaceDialog(onDismissRequest = { confirm = null }, title = { Text("Confirm") }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }) }
    restoreUri?.let { uri -> WorkspaceDialog(onDismissRequest = { restoreUri = null }, title = { Text("Restore workspace backup?") },
        text = { Text("Collections and organization will merge. Backed-up notes and window layout replace the current ones. Storage permissions must be reconnected on a new device.") },
        confirmButton = { TextButton(onClick = { restoreUri = null; work { withContext(Dispatchers.IO) {
            val raw = (context.contentResolver.openInputStream(uri) ?: error("Cannot read backup"))
                .bufferedReader().use(WorkspaceBackup::readBounded)
            WorkspaceBackup.restore(context, catalog, raw)
            "Backup restored. Reconnect storage folders if needed."
        } } }) { Text("Restore") } }, dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("Cancel") } }) }
}

@Composable
private fun CatalogArtwork(entry: CatalogEntry) {
    val shape = RoundedCornerShape(8.dp)
    val modifier = Modifier.size(44.dp).clip(shape)
    if (!entry.artwork.isNullOrBlank()) AsyncImage(model = entry.artwork, contentDescription = null,
        modifier = modifier, contentScale = ContentScale.Crop)
    else if (entry.kind == "APP") {
        val context = LocalContext.current
        val icon by produceState<android.graphics.Bitmap?>(null, entry.target) {
            value = withContext(Dispatchers.IO) { runCatching { context.packageManager.getApplicationIcon(entry.target).toBitmap(48, 48) }.getOrNull() }
        }
        if (icon != null) Image(icon!!.asImageBitmap(), null, modifier = modifier)
        else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text("▦") }
    } else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Text(when (entry.kind) { "GAME" -> "▶"; "FOLDER" -> "▣"; "COMMAND" -> ">_"; "LINK" -> "↗"; "TOOL" -> "⚙"; else -> "◇" })
    }
}
