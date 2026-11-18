package com.playfieldportal.launcher.workspace

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** One search surface for all entities and relationships; rendered inside the existing XMB workspace. */
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
) {
    val context = LocalContext.current
    var revision by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<CatalogEntry>>(emptyList()) }
    var collections by remember { mutableStateOf<List<CatalogCollection>>(emptyList()) }
    var roots by remember { mutableStateOf<List<CatalogRoot>>(emptyList()) }
    var selected by remember { mutableStateOf<CatalogEntry?>(null) }
    var showCollections by remember { mutableStateOf(initiallyCollections) }
    var showNewCollection by remember { mutableStateOf(false) }
    var showNewShortcut by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var scanRequest by remember { mutableStateOf<String?>(null) }
    var backupWriteUri by remember { mutableStateOf<Uri?>(null) }
    var backupReadUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(query, revision, sourceRevision) { page = 0 }
    LaunchedEffect(query, revision, sourceRevision, page) {
        delay(130)
        val snapshot = withContext(Dispatchers.IO) {
            Triple(catalog.search(query, limit = 100, offset = page * 100),
                catalog.collections(), catalog.roots())
        }
        results = if (page == 0) snapshot.first else (results + snapshot.first).distinctBy { it.key }
        hasMore = snapshot.first.size == 100
        collections = snapshot.second
        roots = snapshot.third
    }

    LaunchedEffect(scanRequest) {
        val request = scanRequest ?: return@LaunchedEffect
        status = runCatching { withContext(Dispatchers.IO) { catalog.scanRoot(Uri.parse(request)) } }
            .fold(onSuccess = { it.status }, onFailure = { "Scan failed; index retained: ${it.message}" })
        scanRequest = null
        revision++
    }

    LaunchedEffect(backupWriteUri) {
        val uri = backupWriteUri ?: return@LaunchedEffect
        status = runCatching {
            withContext(Dispatchers.IO) {
                val data = catalog.exportUserLayer()
                context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(data) }
                    ?: error("Cannot open backup destination")
            }
            "Organization backup exported. Keep it private if it contains command shortcuts."
        }.getOrElse { "Backup failed: ${it.message}" }
        backupWriteUri = null
    }
    LaunchedEffect(backupReadUri) {
        val uri = backupReadUri ?: return@LaunchedEffect
        status = runCatching {
            withContext(Dispatchers.IO) {
                val data = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    reader.readText().also { require(it.length <= 5_000_000) { "Backup is too large" } }
                } ?: error("Cannot read backup")
                catalog.importUserLayer(data)
            }
            revision++
            "Organization backup restored; relink any file roots on this device."
        }.getOrElse { "Restore failed: ${it.message}" }
        backupReadUri = null
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        backupWriteUri = it
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        backupReadUri = it
    }

    val pickRoot = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.onFailure { status = "Folder permission denied: ${it.message}" }
            runCatching { catalog.addRoot(uri, uri.lastPathSegment ?: "Selected folder") }
                .onFailure { status = "Cannot register folder: ${it.message}" }
            revision++
        }
    }

    fun open(entry: CatalogEntry) {
        catalog.markOpened(entry.key)
        when (entry.kind) {
            "GAME", "SHORTCUT" -> entry.target.toLongOrNull()?.let(onOpenPfpGame)
            "APP" -> runCatching {
                context.packageManager.getLaunchIntentForPackage(entry.target)?.let(context::startActivity)
                    ?: error("App not installed")
            }.onFailure { status = "App unavailable: ${it.message}" }
            "FILE" -> runCatching {
                val knownGameId = catalog.gameIdForFile(entry.target)
                if (knownGameId != null) {
                    onOpenPfpGame(knownGameId)
                    return@runCatching
                }
                val uri = Uri.parse(entry.target)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            }.onFailure { status = "Cannot open file: ${it.message}" }
            "FOLDER" -> status = "Folder indexed. Use Files to browse this folder and its children."
            "COMMAND" -> onRunCommand(entry.target) // Confirmation is in the existing Terminal panel.
            "LINK" -> runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.target)))
            }.onFailure { status = "Cannot open link: ${it.message}" }
            "TOOL" -> runCatching {
                if (entry.target.startsWith("arcdeck:")) {
                    onOpenWorkspaceTool(entry.target.removePrefix("arcdeck:"))
                    return@runCatching
                }
                context.startActivity(Intent(entry.target))
            }.onFailure { status = "Cannot open settings: ${it.message}" }
        }
        revision++
    }

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        OutlinedTextField(value = query, onValueChange = { query = it },
            label = { Text("Search EVERYTHING · type:game tag:rpg sort:recent") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick = { showCollections = !showCollections }) {
                Text(if (showCollections) "Search results" else "Collections")
            }
            TextButton(onClick = { pickRoot.launch(null) }) { Text("+ Folder") }
            TextButton(onClick = { showNewShortcut = true }) { Text("+ Shortcut") }
            TextButton(onClick = { revision++ }) { Text("Refresh") }
        }
        if (status.isNotBlank()) Text(status, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (sourceError != null) Text(sourceError, maxLines = 2, overflow = TextOverflow.Ellipsis)

        if (showCollections) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                TextButton(onClick = { showNewCollection = true }) { Text("+ Collection") }
                TextButton(onClick = { export.launch("arcdeck-organization.json") }) { Text("Backup") }
                TextButton(onClick = { import.launch(arrayOf("application/json", "text/plain")) }) {
                    Text("Restore")
                }
            }
            LazyColumn {
                items(collections, key = { it.id }) { collection ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "▤ ${collection.name} ${if (collection.expression != null) "◇" else ""}",
                            modifier = Modifier.weight(1f).clickable {
                                query = if (collection.expression != null) collection.expression
                                    else "in:${collection.id} sort:manual"
                                showCollections = false
                            }.padding(6.dp), maxLines = 1,
                        )
                        TextButton(onClick = {
                            catalog.deleteCollection(collection.id); revision++
                        }) { Text("Delete") }
                    }
                }
                items(roots, key = { it.uri }) { root ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("▣ ${root.label} (${root.status})", modifier = Modifier.weight(1f), maxLines = 2)
                        TextButton(onClick = {
                            // Scans run in coroutine below; no synchronous document traversal on UI thread.
                            status = "Indexing ${root.label}…"
                            scanRequest = root.uri
                        }) { Text("Scan") }
                    }
                }
            }
        } else {
            Text("${results.size} result(s) · Game/App/File/Folder/Command/Link", fontWeight = FontWeight.Medium)
            LazyColumn {
                items(results, key = { it.key }) { entry ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            "${entry.kind.take(3)}   ${entry.alias ?: entry.title}${if (entry.favorite) " ★" else ""}" +
                                if (entry.missing) "  [missing]" else "",
                            modifier = Modifier.weight(1f).clickable { open(entry) }.padding(7.dp),
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { selected = entry }) { Text("⋮") }
                    }
                }
                item {
                    if (hasMore) TextButton(onClick = { page++ }) {
                        Text("Load more results")
                    }
                }
            }
        }
    }
    if (selected != null) {
        val entry = selected!!
        var alias by remember(entry.key) { mutableStateOf(entry.alias ?: "") }
        var tag by remember(entry.key) { mutableStateOf("") }
        var localTags by remember(entry.key, revision) { mutableStateOf(catalog.tags(entry.key)) }
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(entry.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("${entry.kind} · ${entry.source}")
                    OutlinedTextField(alias, { alias = it }, label = { Text("Custom display name") })
                    Row {
                        TextButton(onClick = { catalog.rename(entry.key, alias); revision++ }) { Text("Rename") }
                        TextButton(onClick = {
                            if (entry.kind == "GAME" || entry.kind == "SHORTCUT") {
                                entry.target.toLongOrNull()?.let { onSetPfpFavorite(it, !entry.favorite) }
                            } else catalog.setFavorite(entry.key, !entry.favorite)
                            revision++; selected=null
                        }) {
                            Text(if (entry.favorite) "Unfavorite" else "Favorite")
                        }
                        TextButton(onClick = { catalog.setPinned(entry.key, !entry.pinned); revision++; selected=null }) {
                            Text(if (entry.pinned) "Unpin" else "Pin")
                        }
                    }
                    Text("Tags: ${localTags.joinToString(", ")}")
                    localTags.forEach { t ->
                        TextButton(onClick = { catalog.removeTag(entry.key, t); revision++ }) {
                            Text("Remove tag: $t")
                        }
                    }
                    Row {
                        OutlinedTextField(tag, { tag = it }, label = { Text("Tag") },
                            modifier = Modifier.weight(1f), singleLine = true)
                        TextButton(onClick = {
                            catalog.addTag(entry.key, tag); tag = ""; revision++
                            localTags = catalog.tags(entry.key)
                        }) { Text("Add") }
                    }
                    val activeCollection = UnifiedQuery.parse(query).collection?.let { id ->
                        collections.find { it.expression == null && (it.id.toString() == id || it.name == id) }
                    }
                    if (activeCollection != null) {
                        Row {
                            TextButton(onClick = { catalog.shiftInCollection(activeCollection.id, entry.key, -1); revision++; selected = null }) {
                                Text("Move up")
                            }
                            TextButton(onClick = { catalog.shiftInCollection(activeCollection.id, entry.key, 1); revision++; selected = null }) {
                                Text("Move down")
                            }
                            TextButton(onClick = { catalog.removeFromCollection(activeCollection.id, entry.key); revision++; selected = null }) {
                                Text("Remove from collection")
                            }
                        }
                    }
                    if (collections.isNotEmpty()) {
                        Text("Add to a manual collection:")
                        collections.filter { it.expression == null }.take(12).forEach { collection ->
                            TextButton(onClick = { catalog.addToCollection(collection.id, entry.key); revision++ }) {
                                Text("+ ${collection.name}")
                            }
                        }
                    }
                    Text("The original game, app or file is never moved or deleted by these actions.")
                }
            },
            confirmButton = { TextButton(onClick = { open(entry); selected = null }) { Text("Open") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
        )
    }
    if (showNewCollection) {
        var name by remember { mutableStateOf("") }
        var filter by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showNewCollection = false },
            title = { Text("New collection") },
            text = { Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") })
                OutlinedTextField(filter, { filter = it },
                    label = { Text("Smart query (blank = manual collection)") })
            } },
            confirmButton = { TextButton(onClick = {
                runCatching { catalog.createCollection(name, filter) }
                    .onSuccess { showNewCollection = false; revision++ }
                    .onFailure { status = it.message ?: "Could not create collection" }
            }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { showNewCollection = false }) { Text("Cancel") } })
    }
    if (showNewShortcut) {
        var title by remember { mutableStateOf("") }
        var target by remember { mutableStateOf("") }
        var asCommand by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { showNewShortcut = false },
            title = { Text("New shortcut") },
            text = { Column {
                OutlinedTextField(title, { title = it }, label = { Text("Name") })
                OutlinedTextField(target, { target = it },
                    label = { Text(if (asCommand) "Termux command" else "https:// URL") })
                TextButton(onClick = { asCommand = !asCommand }) {
                    Text(if (asCommand) "Type: command (tap for link)" else "Type: website (tap for command)")
                }
            } },
            confirmButton = { TextButton(onClick = {
                runCatching { catalog.addCustom(if (asCommand) "COMMAND" else "LINK", title, target) }
                    .onSuccess { showNewShortcut = false; revision++ }
                    .onFailure { status = it.message ?: "Could not create shortcut" }
            }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { showNewShortcut = false }) { Text("Cancel") } })
    }
}
