package com.playfieldportal.launcher.workspace

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun WorkspaceAutoLibraryPane(catalog: UnifiedCatalog, onBrowse: (String) -> Unit, onPfp: () -> Unit) {
    val context = LocalContext.current; val prefs = remember { context.getSharedPreferences("arcdeck_workspace", 0) }
    val status by WorkspaceAutoLibrary.status.collectAsState(); val nativeStatus by WorkspaceNative.status.collectAsState()
    val enabled = workspaceFlag("phone_auto_library", true)
    var notice by remember { mutableStateOf("") }; var stats by remember { mutableStateOf("") }
    var unsorted by remember { mutableStateOf<List<PhoneGameFile>>(emptyList()) }; val scope = rememberCoroutineScope()
    fun scan() { WorkspaceAutoLibrary.schedule(context); WorkspaceAutoLibrary.kickoff(context, true) }
    LaunchedEffect(status, nativeStatus) { withContext(Dispatchers.IO) { WorkspacePhoneStore(context).use { store ->
        stats = "${store.count("ROM")} recognized ROM files · ${store.platforms().size} consoles · ${store.count("PC")} PC exports · ${store.pending()} folders queued"
        unsorted = store.files(kind = "UNSORTED", limit = 40)
    } } }
    val storage = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { scan() }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) scope.launch {
        workspaceResult { withContext(Dispatchers.IO) {
            val docs = WorkspaceDocuments(context); docs.grant(uri); val root = docs.stat(docs.rootDocument(uri))
            catalog.addRoot(uri, root.name); catalog.registerDocument(root, "space:folders")
        } }.onSuccess { scan() }.onFailure { notice = it.message ?: "Folder permission failed" }
    } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("Automatic phone library", style = MaterialTheme.typography.titleMedium)
        Text("ArcDeck discovers existing game folders, sorts recognized ROMs into PFP consoles, detects installed emulators and refreshes the library on return and through Android's background scheduler. Your original files stay where they are.")
        Row { Switch(enabled, { prefs.edit().putBoolean("phone_auto_library", it).apply(); if (it) scan() else { WorkspaceAutoLibrary.stopForeground(); WorkspaceAutoLibrary.schedule(context) } }); Text("Keep phone games organized automatically", Modifier.padding(10.dp)) }
        Text(stats, style = MaterialTheme.typography.bodySmall)
        Text(status.ifBlank { "Allow a source below. The scan runs on this phone after access is available." })
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Button(onClick = { scan() }) { Text("Discover and update now") }
            TextButton(onClick = onPfp) { Text("Open PFP consoles") }
            TextButton(onClick = { onBrowse(WorkspaceDocuments(context).rootDocument(WorkspacePhoneStore.tree(context)).toString()) }) { Text("Browse organized games") }
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) TextButton(onClick = { runCatching { storage.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))) }
            .onFailure { notice = "Open Android Settings → Special app access → All files access → ArcDeck" } }) { Text(if (WorkspaceStorage.allFiles(context)) "Phone storage allowed" else "Allow phone storage once") }
        TextButton(onClick = { folder.launch(null) }) { Text("Connect an existing game/app folder") }
        WorkspaceNativeSetup()
        Text("Shizuku also enables reading connected game/emulator external data folders that ordinary storage access cannot open. Native windows use the original installed apps and their existing Android profiles. App-private account data is not copied.", style = MaterialTheme.typography.bodySmall)
        Text("Recognition uses console folder names and unambiguous ROM types. Disc images and archives without enough platform information stay unclassified instead of being placed in the wrong console.", style = MaterialTheme.typography.bodySmall)
        if (unsorted.isNotEmpty()) {
            Text("Unclassified candidates", style = MaterialTheme.typography.titleSmall)
            unsorted.forEach { file -> Text(file.name, style = MaterialTheme.typography.bodySmall) }
        }
        if (notice.isNotBlank()) Text(notice)
    }
}
