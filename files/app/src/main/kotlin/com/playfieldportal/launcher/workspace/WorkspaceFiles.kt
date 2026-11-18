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
    suspend fun copy(source: WorkspaceDocument, destination: Uri, move: Boolean, progress: (String) -> Unit): String {
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
            return if (runCatching { delete(source) }.isSuccess) "Moved ${source.name}"
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
    context.startActivity(Intent.createChooser(intent, if (share) "Share ${document.name}" else "Open ${document.name}"))
}

@Composable
internal fun WorkspaceFilesPane(catalog: UnifiedCatalog, location: String?, onOpenGame: (Long) -> Unit, requestId: Int = 0) {
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
    LaunchedEffect(current, revision, limit) {
        val uri = current?.let(Uri::parse) ?: return@LaunchedEffect
        workspaceResult { withContext(Dispatchers.IO) { documents.stat(uri) to documents.children(uri, limit) } }
            .onSuccess { title = it.first.name; listing = it.second }
            .onFailure { listing = DocumentListing(emptyList(), false); status = it.message ?: "Reconnect folder permission" }
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
                val result = documents.copy(job.first, documents.rootDocument(uri), job.second) { status = it }
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
            TextButton(enabled = !busy && current != null, onClick = { name = ""; prompt = "New folder" }) { Text("New folder") }
            TextButton(enabled = !busy && current != null, onClick = { work {
                withContext(Dispatchers.IO) { catalog.scanRoot(documents.treeFor(Uri.parse(current!!))).status }
            } }) { Text("Index") }
        }
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        OutlinedTextField(query, { query = it }, label = { Text("Find in this folder") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 3)
        LazyColumn(Modifier.weight(1f)) {
            items(listing.items.filter { it.name.contains(query, true) }, key = { it.uri.toString() }) { doc ->
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f).clickable(enabled = !busy) {
                        if (doc.directory) navigate(doc.uri.toString())
                        else work {
                            val game = withContext(Dispatchers.IO) { catalog.gameIdForFile(doc.uri.toString()) }
                            if (game != null) onOpenGame(game) else openWorkspaceDocument(context, doc)
                            ""
                        }
                    }.padding(8.dp)) {
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
}
