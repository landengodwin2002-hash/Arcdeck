package com.playfieldportal.launcher.workspace

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal data class WorkspaceTransfer(val items: List<WorkspaceDocument>, val move: Boolean)
internal object WorkspaceFileClipboard { val pending = MutableStateFlow<WorkspaceTransfer?>(null) }

internal data class WorkspaceDocument(val uri: Uri, val name: String, val mime: String, val size: Long, val flags: Int) {
    val directory get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
}
internal data class DocumentListing(val items: List<WorkspaceDocument>, val truncated: Boolean)

/** Queries fail explicitly: a disconnected provider is never interpreted as an empty directory. */
internal class WorkspaceDocuments(private val context: Context) {
    private val resolver get() = context.contentResolver
    private fun identity(uri: Uri) = "${uri.authority}:${DocumentsContract.getDocumentId(uri)}"
    private val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_FLAGS)

    fun rootDocument(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree,
        DocumentsContract.getTreeDocumentId(tree))

    fun treeFor(document: Uri): Uri = DocumentsContract.buildTreeDocumentUri(
        requireNotNull(document.authority), DocumentsContract.getTreeDocumentId(document))

    fun stat(uri: Uri): WorkspaceDocument {
        if (!DocumentsContract.isTreeUri(uri)) return WorkspaceSharing.describe(context, uri)
        val cursor = resolver.query(uri, columns, android.os.Bundle.EMPTY, null) ?: error("Folder or file provider is unavailable")
        return cursor.use {
            check(it.moveToFirst()) { "File or folder no longer exists" }
            WorkspaceDocument(uri, it.getString(1) ?: "Unnamed", it.getString(2) ?: "application/octet-stream",
                if (it.isNull(3)) 0 else it.getLong(3), it.getInt(4))
        }
    }

    fun children(folder: Uri, limit: Int = 2000): DocumentListing {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getDocumentId(folder))
        val out = mutableListOf<WorkspaceDocument>()
        val cursor = resolver.query(uri, columns, android.os.Bundle.EMPTY, null) ?: error("Cannot read folder; reconnect its permission")
        var truncated = false
        cursor.use {
            while (it.moveToNext()) {
                if (out.size >= limit.coerceIn(1, 20001)) { truncated = true; break }
                val child = DocumentsContract.buildDocumentUriUsingTree(folder, it.getString(0))
                out.add(WorkspaceDocument(child, it.getString(1) ?: "Unnamed", it.getString(2) ?: "application/octet-stream",
                    if (it.isNull(3)) 0 else it.getLong(3), it.getInt(4)))
            }
        }
        return DocumentListing(out.sortedWith(compareBy<WorkspaceDocument> { !it.directory }
            .thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.uri.toString() }), truncated)
    }

    fun grant(tree: Uri) {
        require(DocumentsContract.isTreeUri(tree)) { "Choose a storage folder" }
        runCatching { resolver.takePersistableUriPermission(tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .getOrElse { resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    fun validateName(raw: String): String = raw.trim().also {
        require(it.isNotEmpty() && it !in setOf(".", "..") && it.length <= 255 &&
            it.none { c -> c == '/' || c == '\\' || c.isISOControl() }) { "Enter a valid file or folder name" }
    }

    fun createFolder(parent: Uri, name: String): Uri = DocumentsContract.createDocument(resolver, parent,
        DocumentsContract.Document.MIME_TYPE_DIR, validateName(name)) ?: error("This folder is read-only")

    fun rename(document: WorkspaceDocument, name: String): Uri = DocumentsContract.renameDocument(resolver,
        document.uri, validateName(name)) ?: error("This provider cannot rename the item")

    fun delete(document: WorkspaceDocument) {
        check(DocumentsContract.deleteDocument(resolver, document.uri)) { "This provider could not delete the item" }
    }

    /** Snapshot first, reject descendant targets, roll back our new target if copying fails. */
    suspend fun copy(source: WorkspaceDocument, destination: Uri, move: Boolean,
        onMoved: ((List<Triple<Uri, Uri, Uri>>) -> Unit)? = null, progress: (String) -> Unit): String {
        check(stat(destination).directory) { "Destination must be a folder" }
        check(identity(source.uri) != identity(destination)) { "Choose a different destination" }
        data class Node(val doc: WorkspaceDocument, val path: List<String>)
        val nodes = mutableListOf<Node>()
        val pending = ArrayDeque<Node>()
        pending.add(Node(source, emptyList()))
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val node = pending.removeFirst()
            check(visited.add(identity(node.doc.uri))) { "Folder contains a document cycle" }
            check(identity(node.doc.uri) != identity(destination)) { "A folder cannot be copied into itself or its children" }
            check(nodes.size < 12000 && node.path.size <= 32) { "Copy is limited to 12,000 items and 32 folder levels" }
            nodes.add(node)
            if (node.doc.directory) {
                val children = children(node.doc.uri, 12001 - nodes.size)
                check(!children.truncated) { "Folder exceeds the copy item limit" }
                children.items.forEach { pending.add(Node(it, node.path + it.name)) }
            }
        }
        val existing = children(destination, 20001).items.map { it.name }.toSet()
        var targetName = source.name
        var suffix = 1
        while (targetName in existing) { targetName = "${source.name} (copy ${suffix++})" }
        val folders = mutableMapOf<List<String>, Uri>()
        val moved = mutableListOf<Triple<Uri, Uri, Uri>>()
        var created: Uri? = null
        try {
            for ((index, node) in nodes.withIndex()) {
                currentCoroutineContext().ensureActive()
                val parent = if (node.path.isEmpty()) destination else folders[node.path.dropLast(1)]
                    ?: error("Copy parent was not created")
                val name = if (node.path.isEmpty()) targetName else node.doc.name
                val uri = DocumentsContract.createDocument(resolver, parent, node.doc.mime, name)
                    ?: error("Destination cannot create ${node.doc.name}")
                if (created == null) created = uri
                moved.add(Triple(node.doc.uri, uri, parent))
                if (node.doc.directory) folders[node.path] = uri
                else {
                    val input = resolver.openInputStream(node.doc.uri) ?: error("Cannot read ${node.doc.name}")
                    input.use { from ->
                        val output = resolver.openOutputStream(uri, "wt") ?: error("Cannot write ${node.doc.name}")
                        output.use { to ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = from.read(buffer)
                                if (n < 0) break
                                to.write(buffer, 0, n)
                            }
                        }
                    }
                }
                progress("Copied ${index + 1}/${nodes.size}: ${node.doc.name}")
            }
        } catch (e: Exception) {
            created?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            throw e
        }
        if (move) {
            return if (runCatching { delete(source) }.isSuccess) { onMoved?.invoke(moved); "Moved ${source.name}" }
                else "Copied ${source.name}; source could not be removed and was retained"
        }
        return "Copied ${source.name}"
    }
}

internal fun openWorkspaceDocument(context: Context, document: WorkspaceDocument, share: Boolean = false) {
    require(!document.directory) { "Open the folder to choose a file" }
    val intent = Intent(if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
        if (share) { type = document.mime; putExtra(Intent.EXTRA_STREAM, document.uri) }
        else setDataAndType(document.uri, document.mime)
        clipData = ClipData.newRawUri(document.name, document.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, if (share) "Share ${document.name}" else "Open ${document.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun WorkspaceFilesPane(catalog: UnifiedCatalog, location: String?, onOpenGame: (Long) -> Unit, requestId: Int = 0, onPreview: (String) -> Unit = {}, onLocationChanged: (String?) -> Unit = {}) {
    val context = LocalContext.current
    val documents = remember { WorkspaceDocuments(context) }
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE) }
    val history = rememberSaveable(saver = listSaver<SnapshotStateList<String>, String>(
        save = { it.toList() }, restore = { it.toMutableStateList() })) { mutableStateListOf<String>() }
    var current by rememberSaveable { mutableStateOf(location ?: prefs.getString("file_location", null)) }
    var listing by remember { mutableStateOf(DocumentListing(emptyList(), false)) }
    var title by remember { mutableStateOf("Choose a folder") }
    var query by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var limit by remember(current) { mutableIntStateOf(2000) }
    var selected by remember { mutableStateOf<WorkspaceDocument?>(null) }
    var prompt by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var transfer by remember { mutableStateOf<Pair<WorkspaceDocument, Boolean>?>(null) }
    var chosen by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectMode by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(0) }
    var batchDelete by remember { mutableStateOf(false) }
    var tagDialog by remember { mutableStateOf(false) }; var batchTag by remember { mutableStateOf("") }
    var breadcrumbs by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val clipboard by WorkspaceFileClipboard.pending.collectAsState()
    val picked = listing.items.filter { it.uri.toString() in chosen }
    fun toggle(doc: WorkspaceDocument) { val id = doc.uri.toString(); chosen = if (id in chosen) chosen - id else chosen + id }
    fun work(block: suspend () -> String) {
        if (busy) return
        scope.launch {
            busy = true
            workspaceResult { block() }.onSuccess { status = it; revision++ }
                .onFailure { status = it.message ?: "File operation failed" }
            busy = false
        }
    }
    fun navigate(uri: String) {
        current?.let { history.add(it) }
        current = uri; query = ""
        prefs.edit().putString("file_location", uri).apply()
    }
    LaunchedEffect(location, requestId) { if (location != null && current != location) navigate(location) }
    LaunchedEffect(current) { chosen = emptySet(); selectMode = false; onLocationChanged(current) }
    LaunchedEffect(current, revision, limit) {
        val uri = current?.let(Uri::parse) ?: return@LaunchedEffect
        workspaceResult { withContext(Dispatchers.IO) { documents.stat(uri) to documents.children(uri, limit) } }
            .onSuccess { title = it.first.name; listing = it.second }
            .onFailure { listing = DocumentListing(emptyList(), false); status = it.message ?: "Reconnect folder permission" }
    }
    LaunchedEffect(current, history.toList(), revision) {
        breadcrumbs = withContext(Dispatchers.IO) { (history.toList() + listOfNotNull(current)).distinct().takeLast(8).map { uri ->
            uri to runCatching { documents.stat(Uri.parse(uri)).name }.getOrDefault("Folder")
        } }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(current, owner) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper()); val reload = Runnable { revision++ }
        val observer = object : android.database.ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { handler.removeCallbacks(reload); handler.postDelayed(reload, 400) }
        }
        val listener = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        owner.lifecycle.addObserver(listener)
        current?.let { runCatching { context.contentResolver.registerContentObserver(Uri.parse(it), true, observer) } }
        onDispose { handler.removeCallbacks(reload); context.contentResolver.unregisterContentObserver(observer); owner.lifecycle.removeObserver(listener) }
    }
    BackHandler(enabled = history.isNotEmpty() && !busy) { current = history.removeAt(history.lastIndex) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) work {
            val folder = withContext(Dispatchers.IO) {
                documents.grant(uri)
                documents.stat(documents.rootDocument(uri)).also { catalog.addRoot(uri, it.name) }
            }
            history.clear(); navigate(folder.uri.toString())
            "Folder connected. Use Index to include its files in universal search."
        }
    }
    val destination = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val job = transfer
        transfer = null
        if (uri != null && job != null) work {
            withContext(Dispatchers.IO) {
                documents.grant(uri)
                val result = documents.copy(job.first, documents.rootDocument(uri), job.second,
                    onMoved = { catalog.movedDocuments(it, documents) }) { status = it }
                catalog.addRoot(uri, documents.stat(documents.rootDocument(uri)).name)
                catalog.scanRoot(uri)
                current?.let { here -> catalog.scanRoot(documents.treeFor(Uri.parse(here))) }
                result
            }
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextButton(enabled = !busy, onClick = { picker.launch(null) }) { Text("Choose folder") }
            TextButton(enabled = !busy && history.isNotEmpty(), onClick = { current = history.removeAt(history.lastIndex) }) { Text("Back") }
            TextButton(enabled = !busy && current != null, onClick = {
                current?.let { current = documents.rootDocument(Uri.parse(it)).toString(); history.clear() }
            }) { Text("Root") }
            TextButton(enabled = !busy, onClick = { revision++ }) { Text("Refresh") }
            TextButton(enabled = !busy, onClick = { selectMode = !selectMode; chosen = emptySet() }) { Text(if (selectMode) "Done selecting" else "Select files") }
            TextButton(onClick = { sort = (sort + 1) % 4 }) { Text("Sort: ${listOf("Name ↑", "Name ↓", "Size", "Type")[sort]}") }
            TextButton(enabled = !busy && current != null, onClick = { name = ""; prompt = "New folder" }) { Text("New folder") }
            TextButton(enabled = !busy && current != null, onClick = { work {
                withContext(Dispatchers.IO) { catalog.scanRoot(documents.treeFor(Uri.parse(current!!))).status }
            } }) { Text("Index") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            breadcrumbs.forEach { (uri, label) -> TextButton(onClick = {
                val index = history.indexOf(uri)
                if (index >= 0) while (history.size > index) history.removeAt(history.lastIndex)
                current = uri; query = ""; prefs.edit().putString("file_location", uri).apply()
            }) { Text(label) } }
        }
        if (selectMode || chosen.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState())) {
            Text("${picked.size} selected", Modifier.padding(top = 12.dp))
            TextButton(onClick = { chosen = listing.items.filter { it.name.contains(query, true) }.map { it.uri.toString() }.toSet(); selectMode = true }) { Text("Select visible") }
            TextButton(enabled = picked.isNotEmpty(), onClick = { WorkspaceFileClipboard.pending.value = WorkspaceTransfer(picked, false); status = "${picked.size} items copied to file clipboard; browse a destination and Paste here" }) { Text("Copy") }
            TextButton(enabled = picked.isNotEmpty(), onClick = { WorkspaceFileClipboard.pending.value = WorkspaceTransfer(picked, true); status = "${picked.size} items ready to move; browse a destination and Paste here" }) { Text("Cut") }
            TextButton(enabled = picked.isNotEmpty() && picked.none { it.directory }, onClick = { work { WorkspaceApps.shareFiles(context, picked); "" } }) { Text("Share") }
            TextButton(enabled = picked.isNotEmpty(), onClick = { tagDialog = true }) { Text("Tag") }
            TextButton(enabled = picked.isNotEmpty(), onClick = { batchDelete = true }) { Text("Delete…") }
        }
        clipboard?.let { pending -> Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(enabled = !busy && current != null, onClick = { val destinationUri = current!!; work { withContext(Dispatchers.IO) {
                val errors = mutableListOf<String>(); var complete = 0
                for ((index, source) in pending.items.withIndex()) {
                    status = "${if (pending.move) "Moving" else "Copying"} ${index + 1}/${pending.items.size}: ${source.name}"
                    workspaceResult { documents.copy(documents.stat(source.uri), Uri.parse(destinationUri), pending.move,
                        onMoved = { catalog.movedDocuments(it, documents) }) {} }
                        .onSuccess { complete++; if (it.contains("could not")) errors.add(it) }.onFailure { errors.add("${source.name}: ${it.message}") }
                }
                if (pending.move) WorkspaceFileClipboard.pending.value = null
                catalog.scanRoot(documents.treeFor(Uri.parse(destinationUri)))
                pending.items.map { documents.treeFor(it.uri) }.distinct().forEach { catalog.scanRoot(it) }
                "$complete/${pending.items.size} complete" + if (errors.isEmpty()) "" else " · ${errors.joinToString("; ")}"
            } } }) { Text("Paste here (${pending.items.size})") }
            TextButton(onClick = { WorkspaceFileClipboard.pending.value = null }) { Text("Clear file clipboard") }
        } }
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        OutlinedTextField(query, { query = it }, label = { Text("Find in this folder") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 3)
        LazyColumn(Modifier.weight(1f)) {
            items(listing.items.filter { it.name.contains(query, true) }.sortedWith(compareBy<WorkspaceDocument> { !it.directory }.then(
                when (sort) { 1 -> compareByDescending<WorkspaceDocument> { it.name.lowercase() }; 2 -> compareByDescending { it.size }; 3 -> compareBy { it.mime }; else -> compareBy { it.name.lowercase() } }
            ).thenBy { it.uri.toString() }), key = { it.uri.toString() }) { doc ->
                Row(Modifier.fillMaxWidth()) {
                    if (selectMode || chosen.isNotEmpty()) Checkbox(doc.uri.toString() in chosen, { toggle(doc) }, enabled = !busy)
                    Column(Modifier.weight(1f).combinedClickable(enabled = !busy, onLongClick = { selectMode = true; toggle(doc) }, onClick = {
                        if (selectMode || chosen.isNotEmpty()) toggle(doc)
                        else if (doc.directory) navigate(doc.uri.toString())
                        else work {
                            val game = withContext(Dispatchers.IO) { catalog.gameIdForFile(doc.uri.toString()) }
                            if (game != null) onOpenGame(game) else onPreview(doc.uri.toString())
                            ""
                        }
                    }).padding(8.dp)) {
                        Text((if (doc.directory) "▣  " else "◇  ") + doc.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!doc.directory) Text("${doc.size / 1024} KB · ${doc.mime}", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(enabled = !busy, onClick = { selected = doc }) { Text("⋮") }
                }
            }
            if (listing.truncated) item { TextButton(onClick = { limit = (limit + 2000).coerceAtMost(20000) }, enabled = limit < 20000) {
                Text(if (limit < 20000) "Show more files" else "20,000 items shown; index this folder for universal search")
            } }
            if (current != null && listing.items.isEmpty()) item { Text("No files to show. Refresh or reconnect the folder if needed.", Modifier.padding(8.dp)) }
        }
    }
    selected?.let { doc ->
        WorkspaceDialog(onDismissRequest = { selected = null }, title = { Text(doc.name) }, text = {
            Column {
                Text(if (doc.directory) "Folder" else "${doc.mime} · ${doc.size} bytes")
                if (!doc.directory) {
                    TextButton(onClick = { selected = null; onPreview(doc.uri.toString()) }) { Text("Preview in workspace") }
                    TextButton(onClick = { selected = null; work { openWorkspaceDocument(context, doc); "" } }) { Text("Open with…") }
                    TextButton(onClick = { selected = null; work { openWorkspaceDocument(context, doc, true); "" } }) { Text("Share") }
                }
                TextButton(onClick = { name = doc.name; prompt = "Rename" }) { Text("Rename") }
                TextButton(onClick = { transfer = doc to false; selected = null; destination.launch(null) }) { Text("Copy to folder…") }
                TextButton(onClick = { transfer = doc to true; selected = null; destination.launch(null) }) { Text("Move to folder…") }
                TextButton(onClick = { prompt = "Delete" }) { Text("Delete…") }
            }
        }, confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } })
    }
    prompt?.let { action ->
        WorkspaceDialog(onDismissRequest = { prompt = null }, title = { Text(action) }, text = {
            if (action == "Delete") Text("Permanently delete ${selected?.name} and its contents? This cannot be undone.")
            else Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                if (action == "Rename") Text("Renamed ROM files may need a game-library rescan.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = {
            val doc = selected
            val folder = current
            prompt = null; selected = null
            work { withContext(Dispatchers.IO) {
                when (action) {
                    "Delete" -> documents.delete(requireNotNull(doc))
                    "Rename" -> {
                        check(listing.items.none { it.name == name.trim() && it.uri != doc?.uri }) { "A file with that name already exists" }
                        val uri = documents.rename(requireNotNull(doc), name)
                        catalog.renamedDocument(doc.uri.toString(), documents.stat(uri), folder)
                    }
                    else -> documents.createFolder(Uri.parse(requireNotNull(folder)), name)
                }
                folder?.let { catalog.scanRoot(documents.treeFor(Uri.parse(it))) }
                "$action complete"
            } }
        }) { Text(if (action == "Delete") "Delete permanently" else "Save") } },
            dismissButton = { TextButton(onClick = { prompt = null }) { Text("Cancel") } })
    }
    if (tagDialog) WorkspaceDialog({ tagDialog = false }, { Text("Tag ${picked.size} items") }, {
        OutlinedTextField(batchTag, { batchTag = it.take(48) }, label = { Text("Shared library tag") })
    }, { TextButton(enabled = batchTag.isNotBlank(), onClick = { tagDialog = false; work { withContext(Dispatchers.IO) {
        picked.forEach { doc ->
            if (catalog.get("file:${doc.uri}") == null) catalog.registerDocument(doc, "saf:${documents.treeFor(doc.uri)}")
            val game = catalog.gameIdForFile(doc.uri.toString()); val key = if (game == null) "file:${doc.uri}" else "pfp-game:$game"
            catalog.addTag(key, batchTag)
        }
        "Tag applied to ${picked.size} items"
    } } }) { Text("Apply tag") } }, { TextButton(onClick = { tagDialog = false }) { Text("Cancel") } })
    if (batchDelete) WorkspaceDialog({ batchDelete = false }, { Text("Delete ${picked.size} items permanently?") }, {
        Text("The selected files and folders will be deleted from their source. This cannot be undone.")
    }, { TextButton(onClick = { val targets = picked.toList(); batchDelete = false; work { withContext(Dispatchers.IO) {
        val failures = mutableListOf<String>(); targets.forEach { doc -> runCatching { documents.delete(doc) }.onFailure { failures.add("${doc.name}: ${it.message}") } }
        current?.let { catalog.scanRoot(documents.treeFor(Uri.parse(it))) }; chosen = emptySet()
        "Deleted ${targets.size - failures.size}/${targets.size}" + if (failures.isEmpty()) "" else " · ${failures.joinToString("; ")}"
    } } }) { Text("Delete permanently") } }, { TextButton(onClick = { batchDelete = false }) { Text("Cancel") } })
}
