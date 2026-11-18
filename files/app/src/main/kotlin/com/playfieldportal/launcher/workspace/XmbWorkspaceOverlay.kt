package com.playfieldportal.launcher.workspace

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.documentfile.provider.DocumentFile
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.domain.model.Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ArcDeck Workspace, an additive extension to Play Field Portal's existing XMB.
 *
 * NO external Android application is embedded in a fake window. Our windows contain
 * our own UI; Android applications still launch through normal system intents.
 * Files are visible only through an explicitly granted Storage Access Framework tree.
 * Termux execution is opt-in and every command asks for confirmation.
 *
 * This source file contains no upstream Play Field Portal source code or artwork.
 */
private enum class Tool(val title: String, val symbol: String) {
    SEARCH("Search", "⌕"), LIBRARY("Library", "▤"), COLLECTIONS("Collections", "◇"),
    FILES("Files", "▣"), APPS("Apps", "▦"),
    TERMINAL("Terminal", ">_"), NOTES("Notes", "✎"), SYSTEM("System", "⚙")
}

private data class Panel(
    val id: Int,
    val tool: Tool,
    val x: Float,
    val y: Float,
    val widthDp: Float = 390f,
    val heightDp: Float = 305f,
    val minimized: Boolean = false,
    val maximized: Boolean = false,
    val order: Int = 0
)

private data class AppEntry(val label: String, val packageName: String)

private val Ink = Color(0xE811203D)
private val Ice = Color(0xFFCAE8FF)
private val Accent = Color(0xFF75BEFF)
private val Line = Color(0x5579BFFF)
private val Tab = Color(0xEE152C4B)

private fun loadPanels(context: Context): List<Panel> {
    return runCatching {
        val array = JSONArray(context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE)
            .getString("panels", "[]"))
        (0 until array.length()).mapNotNull { i ->
            val item = array.getJSONObject(i)
            val tool = runCatching { Tool.valueOf(item.getString("tool")) }.getOrNull()
                ?: return@mapNotNull null
            Panel(
                id = item.getInt("id"), tool = tool,
                x = item.optDouble("x", 40.0).toFloat(),
                y = item.optDouble("y", 70.0).toFloat(),
                widthDp = item.optDouble("w", 390.0).toFloat().coerceIn(260f, 900f),
                heightDp = item.optDouble("h", 305.0).toFloat().coerceIn(180f, 700f),
                minimized = item.optBoolean("min", false),
                maximized = item.optBoolean("max", false),
                order = item.optInt("z", i)
            )
        }
    }.getOrDefault(emptyList())
}

private fun savePanels(context: Context, panels: List<Panel>) {
    val array = JSONArray()
    panels.forEach { p ->
        array.put(JSONObject().apply {
            put("id", p.id); put("tool", p.tool.name)
            put("x", p.x); put("y", p.y)
            put("w", p.widthDp); put("h", p.heightDp)
            put("min", p.minimized); put("max", p.maximized); put("z", p.order)
        })
    }
    context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE)
        .edit().putString("panels", array.toString()).apply()
}

private fun openSystem(context: Context, action: String) {
    runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
}

private fun openApp(context: Context, packageName: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
fun XmbWorkspaceOverlay(
    expanded: Boolean,
    onToggle: () -> Unit,
    onClose: () -> Unit,
    onSound: (MenuSound) -> Unit,
    games: List<Game>?,
    onOpenPfpGame: (Long) -> Unit,
    onSetPfpFavorite: (Long, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val panels = remember { mutableStateListOf<Panel>().apply {
        val saved = loadPanels(context)
        addAll(if (saved.isEmpty()) listOf(Panel(1, Tool.SEARCH, 52f, 76f)) else saved)
    } }
    var nextId by remember { mutableStateOf((panels.maxOfOrNull { it.id } ?: 0) + 1) }
    var topOrder by remember { mutableStateOf((panels.maxOfOrNull { it.order } ?: 0) + 1) }
    var queuedCommand by remember { mutableStateOf<String?>(null) }
    val catalog = remember { UnifiedCatalog(context) }
    var catalogRevision by remember { mutableStateOf(0) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    // Index once per real source update, not once per window (Search/Library/Collections
    // may all be visible simultaneously). The underlying PFP database is never modified.
    LaunchedEffect(games) {
        val snapshot = games ?: return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { catalog.syncPfpGames(snapshot) } }
            .onSuccess { catalogRevision++ }
            .onFailure { catalogError = "Game indexing failed: ${it.message}" }
    }
    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) {
                catalog.syncAndroidApps()
                catalog.syncSystemTools()
            }
        }.onSuccess { catalogRevision++ }
            .onFailure { catalogError = "App indexing failed: ${it.message}" }
    }

    // A short debounce keeps window dragging from writing flash storage every frame.
    val saveKey = panels.toList().hashCode()
    LaunchedEffect(saveKey) {
        delay(550)
        savePanels(context, panels.toList())
    }

    fun update(id: Int, transform: (Panel) -> Panel) {
        val index = panels.indexOfFirst { it.id == id }
        if (index >= 0) panels[index] = transform(panels[index])
    }

    fun activate(tool: Tool) {
        val old = panels.firstOrNull { it.tool == tool }
        if (old != null) {
            update(old.id) { it.copy(minimized = false, order = topOrder++) }
        } else {
            val stagger = (panels.size % 5) * 23f
            panels.add(Panel(nextId++, tool, 42f + stagger, 65f + stagger, order = topOrder++))
        }
        onSound(MenuSound.SELECT)
    }

    Box(modifier.fillMaxSize()) {
        // The always-visible grip gives touch users a reliable entry without overriding PFP's
        // Start/Select mappings, boot visuals, navigation, or its own categories.
        Box(
            Modifier.align(Alignment.CenterEnd).zIndex(1000f)
                .clip(RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp))
                .background(Tab)
                .border(1.dp, Line, RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp))
                .clickable { onSound(MenuSound.SELECT); onToggle() }
                .padding(horizontal = 11.dp, vertical = 17.dp)
        ) { Text(if (expanded) "‹" else "WS", color = Ice, fontWeight = FontWeight.Bold) }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + scaleIn(initialScale = 0.985f),
            exit = fadeOut() + scaleOut(targetScale = 0.985f)
        ) {
            Box(Modifier.fillMaxSize().background(Color(0x66040917))) {
                Row(
                    Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Tab).border(1.dp, Line, RoundedCornerShape(24.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Tool.entries.forEach { tool ->
                        Text(
                            "${tool.symbol} ${tool.title}",
                            Modifier.clip(RoundedCornerShape(14.dp))
                                .clickable { activate(tool) }
                                .padding(horizontal = 9.dp, vertical = 7.dp),
                            color = Ice, fontSize = 12.sp
                        )
                    }
                    Text("×", Modifier.clickable { onSound(MenuSound.BACK); onClose() }
                        .padding(horizontal = 9.dp, vertical = 7.dp), color = Ice)
                }

                panels.sortedBy { it.order }.forEach { panel ->
                    if (!panel.minimized) {
                        val widthDp = min(panel.widthDp, configuration.screenWidthDp.toFloat() - 18f)
                            .coerceAtLeast(220f)
                        val heightDp = min(panel.heightDp, configuration.screenHeightDp.toFloat() - 70f)
                            .coerceAtLeast(160f)
                        val actualWidth = with(density) { widthDp.dp.toPx() }
                        val actualHeight = with(density) { heightDp.dp.toPx() }
                        val x = if (panel.maximized) 0f else panel.x.coerceIn(0f, max(0f, screenWidthPx - actualWidth))
                        val y = if (panel.maximized) 50f else panel.y.coerceIn(0f, max(0f, screenHeightPx - actualHeight))
                        val drawWidth = if (panel.maximized) configuration.screenWidthDp.dp else widthDp.dp
                        val drawHeight = if (panel.maximized) (configuration.screenHeightDp - 52).dp else heightDp.dp

                        Column(
                            Modifier.zIndex(1f + panel.order)
                                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                                .width(drawWidth).height(drawHeight)
                                .clip(RoundedCornerShape(15.dp))
                                .background(Brush.verticalGradient(listOf(Color(0xF21E3B60), Ink)))
                                .border(1.dp, Line, RoundedCornerShape(15.dp))
                                .clickable { update(panel.id) { it.copy(order = topOrder++) } }
                        ) {
                            Row(
                                Modifier.fillMaxWidth().height(42.dp)
                                    .background(Color(0xAA143255))
                                    .pointerInput(panel.id, panel.maximized) {
                                        if (!panel.maximized) detectDragGestures { change, delta ->
                                            change.consume()
                                            update(panel.id) { p -> p.copy(
                                                x = (p.x + delta.x).coerceIn(0f, max(0f, screenWidthPx - actualWidth)),
                                                y = (p.y + delta.y).coerceIn(0f, max(0f, screenHeightPx - actualHeight))
                                            ) }
                                        }
                                    }
                                    .padding(start = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(panel.tool.symbol + "  " + panel.tool.title.uppercase(),
                                    Modifier.weight(1f), color = Ice, fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold)
                                FrameControl("—") { update(panel.id) { it.copy(minimized = true) } }
                                FrameControl(if (panel.maximized) "▢" else "□") {
                                    update(panel.id) { it.copy(maximized = !it.maximized) }
                                }
                                FrameControl("×") {
                                    panels.removeAll { it.id == panel.id }
                                    onSound(MenuSound.BACK)
                                }
                            }
                            Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
                                when (panel.tool) {
                                    Tool.SEARCH, Tool.LIBRARY, Tool.COLLECTIONS -> UnifiedLibraryPane(
                                        catalog = catalog,
                                        sourceRevision = catalogRevision,
                                        sourceError = catalogError,
                                        initiallyCollections = panel.tool == Tool.COLLECTIONS,
                                        onOpenPfpGame = onOpenPfpGame,
                                        onSetPfpFavorite = onSetPfpFavorite,
                                        onRunCommand = { command ->
                                            queuedCommand = command
                                            activate(Tool.TERMINAL)
                                        },
                                        onOpenWorkspaceTool = { id ->
                                            val tool = Tool.entries.firstOrNull { it.name.equals(id, true) }
                                            if (tool != null) activate(tool)
                                        },
                                    )
                                    Tool.FILES -> FileTool()
                                    Tool.APPS -> AppsTool()
                                    Tool.TERMINAL -> TerminalTool(queuedCommand)
                                    Tool.NOTES -> NotesTool()
                                    Tool.SYSTEM -> SystemTool(onClose)
                                }
                            }
                            if (!panel.maximized) {
                                Box(
                                    Modifier.align(Alignment.End).size(26.dp)
                                        .pointerInput(panel.id) {
                                            detectDragGestures { change, delta ->
                                                change.consume()
                                                update(panel.id) { p ->
                                                    p.copy(widthDp = (p.widthDp + with(density) { delta.x.toDp().value })
                                                        .coerceIn(260f, configuration.screenWidthDp.toFloat() - 12f),
                                                        heightDp = (p.heightDp + with(density) { delta.y.toDp().value })
                                                        .coerceIn(180f, configuration.screenHeightDp.toFloat() - 65f))
                                                }
                                            }
                                        }.clickable { }
                                ) { Text("◢", Modifier.align(Alignment.Center), color = Accent) }
                            }
                        }
                    }
                }

                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp)
                        .clip(RoundedCornerShape(16.dp)).background(Tab)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    panels.sortedBy { it.order }.forEach { panel ->
                        Text(
                            panel.tool.title,
                            Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                                update(panel.id) { it.copy(minimized = false, order = topOrder++) }
                            }.padding(horizontal = 11.dp, vertical = 7.dp),
                            color = if (panel.minimized) Color.LightGray else Accent,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameControl(label: String, onClick: () -> Unit) {
    Text(label, Modifier.clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 9.dp),
        color = Ice, fontSize = 16.sp)
}

@Composable
private fun SearchTool(onOpen: (Tool) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var installed by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        installed = withContext(Dispatchers.IO) { getApps(context) }
    }
    Column {
        OutlinedTextField(query, { query = it }, label = { Text("Search apps, tools, commands") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        val commands = Tool.entries.filter { it.title.contains(query, true) }
        val apps = installed.filter {
            query.isNotBlank() && (it.label.contains(query, true) || it.packageName.contains(query, true))
        }.take(30)
        LazyColumn {
            items(commands) { tool -> ListAction("${tool.symbol}  ${tool.title}") { onOpen(tool) } }
            items(apps) { app -> ListAction("▦  ${app.label}") { openApp(context, app.packageName) } }
        }
    }
}

private fun getApps(context: Context): List<AppEntry> = runCatching {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    context.packageManager.queryIntentActivities(intent, 0)
        .map { AppEntry(it.loadLabel(context.packageManager).toString(), it.activityInfo.packageName) }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}.getOrDefault(emptyList())

@Composable
private fun AppsTool() {
    val context = LocalContext.current
    var filter by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { getApps(context) } }
    Column {
        OutlinedTextField(filter, { filter = it }, label = { Text("Find installed applications") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        LazyColumn {
            items(apps.filter { it.label.contains(filter, true) || it.packageName.contains(filter, true) }) { app ->
                ListAction("${app.label}   ·   ${app.packageName}") { openApp(context, app.packageName) }
            }
        }
    }
}

@Composable
private fun FileTool() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE) }
    var rootUri by remember { mutableStateOf(preferences.getString("file_tree", null)) }
    val history = remember { mutableStateListOf<DocumentFile>() }
    var files by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            rootUri = uri.toString()
            preferences.edit().putString("file_tree", rootUri).apply()
        }
    }
    LaunchedEffect(rootUri) {
        history.clear()
        rootUri?.let { uri ->
            runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uri)) }
                .getOrNull()?.let { history.add(it) }
        }
    }
    val current = history.lastOrNull()
    LaunchedEffect(current?.uri) {
        loading = true
        files = withContext(Dispatchers.IO) {
            runCatching { current?.listFiles()?.sortedWith(
                compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name?.lowercase() }
            ) ?: emptyList() }.getOrDefault(emptyList())
        }
        loading = false
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { picker.launch(null) }) { Text("Choose folder") }
            if (history.size > 1) TextButton(onClick = { history.removeAt(history.lastIndex) }) { Text("Back") }
        }
        Text(current?.name ?: "No folder selected", fontSize = 12.sp, color = Ice,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (loading) Text("Reading folder...", color = Accent)
        LazyColumn {
            items(files, key = { it.uri.toString() }) { file ->
                ListAction((if (file.isDirectory) "▣  " else "◈  ") + (file.name ?: "Unnamed")) {
                    if (file.isDirectory) {
                        history.add(file)
                    } else {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(file.uri, file.type ?: "application/octet-stream")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(intent) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotesTool() {
    val context = LocalContext.current
    val pref = remember { context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE) }
    var text by remember { mutableStateOf(pref.getString("notes", "") ?: "") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(text, { text = it }, label = { Text("Workspace notes") },
            modifier = Modifier.fillMaxWidth().weight(1f), maxLines = 50)
        Button(onClick = { pref.edit().putString("notes", text).apply() }) { Text("Save notes") }
    }
}

@Composable
private fun TerminalTool(initialCommand: String? = null) {
    val context = LocalContext.current
    var command by remember { mutableStateOf("") }
    LaunchedEffect(initialCommand) { if (initialCommand != null) command = initialCommand }
    var confirm by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Run your commands through Termux. Permission and Termux settings required.") }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("TERMUX / BASH", fontWeight = FontWeight.Bold, color = Accent)
        OutlinedTextField(command, { command = it }, label = { Text("Shell command") },
            modifier = Modifier.fillMaxWidth(), maxLines = 3)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { if (command.isNotBlank()) confirm = true }) { Text("Review & run") }
            TextButton(onClick = { openApp(context, "com.termux") }) { Text("Open Termux") }
        }
        Text(status, fontSize = 12.sp, color = Ice)
        Text("Commands execute in Termux, not inside this window. Never paste commands you don't trust.",
            fontSize = 11.sp, color = Color.LightGray)
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Run in Termux?") },
            text = { Text("This command runs with your Termux permissions:\n\n$command") },
            confirmButton = {
                TextButton(onClick = {
                    val result = runTermux(context, command)
                    status = result
                    confirm = false
                }) { Text("Run") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
}

private fun runTermux(context: Context, command: String): String {
    if (command.isBlank()) return "Empty command."
    val permission = "com.termux.permission.RUN_COMMAND"
    if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
        return "Permission not granted. Android Settings → Apps → Play Field Portal → Permissions → Additional permissions → Run commands in Termux. Then enable allow-external-apps in Termux."
    }
    val intent = Intent("com.termux.RUN_COMMAND").apply {
        setClassName("com.termux", "com.termux.app.RunCommandService")
        putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
        putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
        putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
        putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
        putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
    }
    return runCatching { context.startService(intent); "Command sent to Termux. See its session for output." }
        .getOrElse { "Couldn't start Termux: ${it.message ?: it.javaClass.simpleName}" }
}

@Composable
private fun SystemTool(onClose: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        ListAction("⚙  Android settings") { openSystem(context, Settings.ACTION_SETTINGS) }
        ListAction("⌂  Change default home app") { openSystem(context, Settings.ACTION_HOME_SETTINGS) }
        ListAction("▣  File manager") { openSystem(context, Settings.ACTION_INTERNAL_STORAGE_SETTINGS) }
        ListAction("▤  Wi-Fi settings") { openSystem(context, Settings.ACTION_WIFI_SETTINGS) }
        ListAction("◉  Bluetooth settings") { openSystem(context, Settings.ACTION_BLUETOOTH_SETTINGS) }
        ListAction("✕  Close workspace") { onClose() }
    }
}

@Composable
private fun ListAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        color = Ice, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
    )
}
