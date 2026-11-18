package com.playfieldportal.launcher.workspace

import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun SpaceNotesEditor(space: AppSpace, store: WorkspaceSpaces, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE) }
    var text by remember(space.id) { mutableStateOf(space.notes) }
    var pending by remember(space.id) { mutableStateOf<String?>(null) }
    fun save() { pending?.let { store.notes(space.id, it) }; pending = null }
    LaunchedEffect(space.notes) { if (pending == null) text = space.notes }
    LaunchedEffect(text) { if (pending != null) { delay(450); save() } }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, space.id) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) save() }
        owner.lifecycle.addObserver(observer)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "restore_epoch") { pending = null; text = store.all().find { it.id == space.id }?.notes ?: "" }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { save(); owner.lifecycle.removeObserver(observer); prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    OutlinedTextField(text, { if (it.length <= 50_000) { text = it; pending = it; store.draft(space.id, it) } },
        label = { Text("${space.name} notes") }, modifier = modifier.fillMaxWidth().heightIn(min = 130.dp, max = 260.dp))
}

internal fun spaceContext(space: AppSpace, items: List<CatalogEntry>): String = buildString {
    append(space.name)
    if (space.notes.isNotBlank()) append("\n\n").append(space.notes)
    val links = items.filter { !it.missing && it.kind == "LINK" }
    if (links.isNotEmpty()) append("\n\nLinks:\n").append(links.joinToString("\n") { "${it.alias ?: it.title}: ${it.target}" })
}

@Composable internal fun WorkspaceSpacePane(catalog: UnifiedCatalog, onPreview: (String) -> Unit,
    onBrowse: (String) -> Unit, onGame: (Long) -> Unit, onCommand: (String) -> Unit, onTool: (String) -> Unit = {}) {
    val context = LocalContext.current; val store = remember { WorkspaceSpaces.shared(context) }
    val revision by store.revision.collectAsState(); val catalogRevision by catalog.revision.collectAsState()
    val spaces = remember(revision) { store.all() }; val active = remember(revision) { store.active() }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<CatalogEntry>>(emptyList()) }
    var query by rememberSaveable(active?.id) { mutableStateOf("") }; var status by remember { mutableStateOf("") }
    var showingNotes by rememberSaveable(active?.id) { mutableStateOf(false) }
    var editor by remember { mutableStateOf(false) }; var edit by remember { mutableStateOf<AppSpace?>(null) }
    var attaching by remember { mutableStateOf(false) }; var removing by remember { mutableStateOf(false) }
    var shortcut by remember { mutableStateOf(false) }; var busy by remember { mutableStateOf(false) }
    fun work(block: suspend () -> String) { if (!busy) scope.launch {
        busy = true; workspaceResult { block() }.onSuccess { status = it }.onFailure { status = it.message ?: "Action failed" }; busy = false
    } }
    LaunchedEffect(active?.id, revision, catalogRevision, query) {
        workspaceResult { withContext(Dispatchers.IO) { active?.let { store.items(it, catalog, query) } ?: emptyList() } }
            .onSuccess { entries = it }.onFailure { status = it.message ?: "Space could not be loaded" }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val space = store.active()
        if (uri != null && space != null) work { withContext(Dispatchers.IO) {
            val docs = WorkspaceDocuments(context); docs.grant(uri)
            val doc = docs.stat(docs.rootDocument(uri)); catalog.addRoot(uri, doc.name)
            catalog.registerDocument(doc, "space:folders")
            store.attach(space, "file:${doc.uri}", catalog); catalog.scanRoot(uri)
            "Folder connected to ${space.name}"
        } }
    }
    fun open(entry: CatalogEntry) = work {
        require(!entry.missing) { "Reconnect this item's source first" }
        when (entry.kind) {
            "GAME", "SHORTCUT" -> onGame(entry.target.toLong())
            "APP" -> {
                val intent = context.packageManager.getLaunchIntentForPackage(entry.target) ?: error("App is not installed")
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            "FOLDER" -> onBrowse(entry.target)
            "FILE" -> onPreview(entry.target)
            "LINK" -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.target)))
            "COMMAND" -> onCommand(entry.target)
            "TOOL" -> onTool(entry.target)
            else -> error("Open this item through the main Library")
        }
        ""
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { edit = null; editor = true }) { Text("New app space") }
            if (active != null) {
                TextButton(onClick = { edit = active; editor = true }) { Text("Edit space") }
                TextButton(onClick = { removing = true }) { Text("Remove space…") }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            spaces.forEach { space -> FilterChip(active?.id == space.id, { store.select(space.id); query = "" }, label = { Text(space.name) }) }
        }
        if (active == null) {
            Text("Create a space for Discord, Brave, ChatGPT, a game, or any installed app. Keep its files, notes and links together.", Modifier.padding(8.dp))
            Text("Enable the floating workspace in System to reach these spaces while another app is open.", Modifier.padding(8.dp))
        } else {
            Text(active.name, style = MaterialTheme.typography.titleMedium)
            Text(if (active.app.isBlank()) "Independent space · connect an app in Edit space" else "Connected app: ${active.app}", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(enabled = active.app.isNotBlank(), onClick = { work {
                    WorkspaceOverlayService.ensureEnabled(context); WorkspaceApps.launch(context, active); ""
                } }) { Text("Open app") }
                TextButton(enabled = active.app.isNotBlank(), onClick = { work {
                    WorkspaceApps.launch(context, active, true); "Adjacent launch requested. Android must already be in split-screen mode."
                } }) { Text("Open adjacent") }
                TextButton(onClick = { work { WorkspaceApps.copy(context, spaceContext(active.copy(notes = store.noteText(active)), entries)); "Space notes and links copied" } }) { Text("Copy context") }
                TextButton(onClick = { work { WorkspaceApps.shareText(context, spaceContext(active.copy(notes = store.noteText(active)), entries), active.app); "" } }) { Text("Share context to app") }
                if (active.profile.isNotBlank()) TextButton(onClick = { work {
                    if (WorkspaceProfiles.apply(context, active.profile)) "Layout applied" else "Linked layout was removed; choose another in Edit space"
                } }) { Text("Layout: ${active.profile}") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(!showingNotes, { showingNotes = false }, label = { Text("Items") })
                FilterChip(showingNotes, { showingNotes = true }, label = { Text("Notes") })
            }
            if (showingNotes) SpaceNotesEditor(active, store, Modifier.weight(1f))
            else {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { attaching = true }) { Text("Attach library item") }
                TextButton(enabled = !busy, onClick = { folder.launch(null) }) { Text("Connect folder") }
                TextButton(onClick = { shortcut = true }) { Text("Add link") }
            }
            OutlinedTextField(query, { query = it.take(512) }, label = { Text("Search this space") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f)) {
                items(entries, key = { it.key }) { entry -> Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f).clickable { open(entry) }.padding(8.dp)) {
                        Text(entry.alias ?: entry.title)
                        Text("${entry.kind.lowercase()} · ${if (entry.missing) "reconnect source" else entry.subtitle}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    if (entry.kind == "FILE" && !entry.missing) TextButton(onClick = { work {
                        val doc = withContext(Dispatchers.IO) { WorkspaceDocuments(context).stat(Uri.parse(entry.target)) }
                        WorkspaceApps.shareFiles(context, listOf(doc), active.app); ""
                    } }) { Text("Share") }
                    TextButton(onClick = { work { withContext(Dispatchers.IO) { catalog.removeFromCollection(store.collectionId(active, catalog), entry.key) }; "Removed from this space; source preserved" } }) { Text("Unlink") }
                } }
                if (entries.isEmpty()) item { Text("Attach an existing library item, connect a folder, or share something to ArcDeck from another app.", Modifier.padding(8.dp)) }
            }
            }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 4)
    }
    if (editor) SpaceEditor(catalog, edit, { editor = false }) { name, app, profile -> work { withContext(Dispatchers.IO) {
        if (edit == null) store.create(name, app, catalog, profile = profile) else store.edit(edit!!.id, name, app, profile)
        editor = false; "Space saved"
    } } }
    if (attaching && active != null) SpaceItemPicker(catalog, { attaching = false }) { entry -> work {
        withContext(Dispatchers.IO) { store.attach(active, entry.key, catalog) }; "Added ${entry.alias ?: entry.title}"
    } }
    if (shortcut && active != null) {
        var name by remember { mutableStateOf("") }; var url by remember { mutableStateOf("") }
        WorkspaceDialog({ shortcut = false }, { Text("Add a link to ${active.name}") }, {
            Column { OutlinedTextField(name, { name = it.take(120) }, label = { Text("Link name") }); OutlinedTextField(url, { url = it.take(2048) }, label = { Text("https:// URL") }) }
        }, { TextButton(enabled = name.isNotBlank() && url.isNotBlank(), onClick = { work { withContext(Dispatchers.IO) {
            val key = catalog.addCustom("LINK", name, url); store.attach(active, key, catalog); shortcut = false; "Link added"
        } } }) { Text("Add") } }, { TextButton(onClick = { shortcut = false }) { Text("Cancel") } })
    }
    if (removing && active != null) WorkspaceDialog({ removing = false }, { Text("Remove ${active.name}?") }, {
        Text("Remove this space and its notes. Its collection, source files, apps and global tags remain available in Library.")
    }, { TextButton(onClick = { store.remove(active.id); removing = false }) { Text("Remove space") } }, { TextButton(onClick = { removing = false }) { Text("Cancel") } })
}

@Composable private fun SpaceEditor(catalog: UnifiedCatalog, space: AppSpace?, dismiss: () -> Unit, save: (String, String, String) -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(space?.name ?: "") }; var app by remember { mutableStateOf(space?.app ?: "") }
    var profile by remember { mutableStateOf(space?.profile ?: "") }
    val profiles = remember { org.json.JSONArray(context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE).getString("profiles", "[]")) }
    var query by remember { mutableStateOf("") }; var apps by remember { mutableStateOf<List<CatalogEntry>>(emptyList()) }
    LaunchedEffect(query) { apps = withContext(Dispatchers.IO) { catalog.search("type:app $query", 300) } }
    WorkspaceDialog(dismiss, { Text(if (space == null) "New app space" else "Edit app space") }, {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(name, { name = it.take(60) }, label = { Text("Space name") })
            Text("Connected app: ${app.ifBlank { "none" }}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { app = "" }) { Text("Use without a connected app") }
            if (profiles.length() > 0) {
                Text("Linked layout applies when opening the connected app.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { profile = "" }) { Text(if (profile.isBlank()) "✓ Keep current layout" else "Keep current layout") }
                for (i in 0 until profiles.length()) {
                    val title = profiles.getJSONObject(i).getString("name")
                    TextButton(onClick = { profile = title }) { Text((if (profile == title) "✓ " else "") + title) }
                }
            }
            OutlinedTextField(query, { query = it.take(100) }, label = { Text("Find any installed app") }, singleLine = true)
            apps.forEach { entry -> TextButton(onClick = { app = entry.target; if (name.isBlank()) name = entry.title.take(60) }) { Text((if (app == entry.target) "✓ " else "") + entry.title) } }
        }
    }, { TextButton(enabled = name.isNotBlank(), onClick = { save(name, app, profile) }) { Text("Save space") } }, { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable private fun SpaceItemPicker(catalog: UnifiedCatalog, dismiss: () -> Unit, attach: (CatalogEntry) -> Unit) {
    var query by remember { mutableStateOf("") }; var entries by remember { mutableStateOf<List<CatalogEntry>>(emptyList()) }
    LaunchedEffect(query) { entries = withContext(Dispatchers.IO) { catalog.search(query, 100) } }
    WorkspaceDialog(dismiss, { Text("Attach from the shared library") }, {
        Column(Modifier.heightIn(max = 420.dp)) {
            OutlinedTextField(query, { query = it.take(512) }, label = { Text("Find an item") }, singleLine = true)
            LazyColumn { items(entries, key = { it.key }) { entry -> TextButton(onClick = { attach(entry) }) { Text("${entry.title} · ${entry.kind.lowercase()}") } } }
        }
    }, { TextButton(onClick = dismiss) { Text("Done") } })
}
