package com.playfieldportal.launcher.workspace

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class PapayaPlatform(val key: String, val name: String, val formats: List<String>)
internal data class PapayaEmulator(val name: String, val app: String, val core: String, val platforms: List<String>, val installed: Boolean)
internal data class PapayaCatalog(val platforms: List<PapayaPlatform>, val emulators: List<PapayaEmulator>, val streaming: List<String>, val additions: Int) {
    companion object {
        fun load(context: Context): PapayaCatalog {
            val root = JSONObject(context.assets.open("arcdeck/papaya-presets.json").bufferedReader().use { it.readText() })
            require(root.getInt("presetSchemaVersion") == 4)
            val platforms = root.getJSONArray("platforms").let { list -> (0 until list.length()).map { n -> list.getJSONObject(n).let { p ->
                PapayaPlatform(p.getString("key"), p.getString("name"), p.getJSONArray("supportedFiles").let { formats -> (0 until formats.length()).map { formats.getJSONObject(it).getString("type") } })
            } } }
            val installed = mutableMapOf<String, Boolean>()
            val emulators = root.getJSONArray("emulatorApps").let { list -> (0 until list.length()).map { n -> list.getJSONObject(n).let { e ->
                val app = e.getString("packageName")
                PapayaEmulator(e.getString("name"), app, e.optString("core"), e.getJSONArray("supportedPlatforms").let { ids -> (0 until ids.length()).map(ids::getString) },
                    installed.getOrPut(app) { runCatching { context.packageManager.getApplicationInfo(app, 0).enabled }.getOrDefault(false) })
            } } }
            val streaming = root.getJSONArray("streamingApps").let { list -> (0 until list.length()).map { list.getJSONObject(it).getString("packageName") } }
            val additions = runCatching { JSONObject(context.assets.open("arcdeck/papaya-adaptation.json").bufferedReader().use { it.readText() }).getJSONArray("compatible_additions").length() }.getOrDefault(0)
            return PapayaCatalog(platforms, emulators, streaming, additions)
        }
    }
}

@Composable internal fun WorkspacePapayaPane(onPfp: (String) -> Unit) {
    val context = LocalContext.current
    var catalog by remember { mutableStateOf<PapayaCatalog?>(null) }
    var error by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("Platforms") }
    var query by remember { mutableStateOf("") }
    var installedOnly by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { workspaceResult { withContext(Dispatchers.IO) { PapayaCatalog.load(context) } }
        .onSuccess { catalog = it }.onFailure { error = it.message ?: "Catalog unavailable" } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = maxHeight < 360.dp
    var viewMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (compact) "Papaya presets" else "Papaya compatibility catalog", style = MaterialTheme.typography.titleMedium)
        if (!compact) Text("Public presets adapted into ArcDeck's existing emulator system. Papaya app data stays in Papaya.", style = MaterialTheme.typography.bodySmall)
        val data = catalog
        if (data == null) { if (error.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth()) else Text(error); return@Column }
        Text("${data.platforms.size} platforms · ${data.emulators.size} recipes · ${data.additions} compatible additions", style = MaterialTheme.typography.labelSmall)
        if (!compact) WorkspaceScrollableRow {
            listOf("Platforms", "Emulators", "Streaming").forEach { name -> FilterChip(tab == name, { tab = name }, label = { Text(name) }) }
            TextButton(onClick = { onPfp("category:settings") }) { Text("Configure launchers") }
        }
        Row(Modifier.fillMaxWidth()) {
            OutlinedTextField(query, { query = it.take(100) }, label = { Text("Find platform or emulator") }, singleLine = true, modifier = Modifier.weight(1f))
            if (compact) Box {
                TextButton(onClick = { viewMenu = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tab) }
                DropdownMenu(viewMenu, { viewMenu = false }) {
                    listOf("Platforms", "Emulators", "Streaming").forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { tab = name; viewMenu = false }) }
                    DropdownMenuItem(text = { Text(if (installedOnly) "Show all recipes" else "Installed packages only") }, onClick = { installedOnly = !installedOnly; viewMenu = false })
                    DropdownMenuItem(text = { Text("Configure launchers") }, onClick = { viewMenu = false; onPfp("category:settings") })
                }
            }
        }
        if (tab == "Emulators" && !compact) Row { Checkbox(installedOnly, { installedOnly = it }); Text("Installed packages only", Modifier.padding(top = 12.dp)) }
        LazyColumn(Modifier.weight(1f)) {
            when (tab) {
                "Platforms" -> items(data.platforms.filter { it.name.contains(query, true) || it.key.contains(query, true) }, key = { it.key }) { p ->
                    ListItem(headlineContent = { Text(p.name) }, supportingContent = { Text("${p.key} · ${p.formats.joinToString(", ").ifBlank { "Installed apps" }}") })
                }
                "Emulators" -> items(data.emulators.withIndex().filter { (_, e) -> (!installedOnly || e.installed) && (e.name.contains(query, true) || e.app.contains(query, true)) }, key = { it.index }) { (_, e) ->
                    ListItem(headlineContent = { Text(e.name) }, supportingContent = { Text("${e.platforms.joinToString()} · ${e.app}${if (e.core.isBlank()) "" else " · ${e.core}"}") },
                        trailingContent = { if (e.installed) Text("Installed", style = MaterialTheme.typography.labelSmall) })
                }
                else -> items(data.streaming.filter { it.contains(query, true) }) { app -> ListItem(headlineContent = { Text(app) }) }
            }
        }
    }
    }
}
