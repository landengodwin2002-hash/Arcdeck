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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.documentfile.provider.DocumentFile
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.domain.model.Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
 * Installed Android apps use private, Shizuku-authorized virtual displays and direct surfaces.
 * Phone games are organized through a read-only virtual ROM root backed by original files.
 * Android authorization remains a phone-side setup step; no accounts or app data are cloned.
 *
 * This source file contains no upstream Play Field Portal source code or artwork.
 */
private enum class Tool(val title: String, val symbol: String) {
    GAMES("Games", "▶"), SPACES("Spaces", "◈"), SEARCH("Search", "⌕"), LIBRARY("Library", "▤"), COLLECTIONS("Collections", "◇"),
    FILES("Files", "▣"), APPS("Apps", "▦"),
    TERMINAL("Terminal", ">_"), NOTES("Notes", "✎"), PFP("Console & editors", "⌂"),
    CUSTOMIZE("Customize", "◐"), PREVIEW("Preview", "◉"), SYSTEM("System", "⚙"),
    CHATGPT("ChatGPT", "✦"), DISCORD("Discord", "◉"), MEDIA("Media", "♫"),
    CLIPBOARD("Clipboard", "▧"), GAMING("Game connections", "▶"), AUTOMATION("Automation", "⇄"), NATIVE("Installed app", "▦"), AUTOLIB("Phone library", "↻"), PRESETS("Emulators & presets", "◫")
}
private fun canonical(tool: Tool): Tool = Tool.valueOf(WorkspaceWindowGeometry.canonical(tool.name))
private fun dockTools() = Tool.entries.filter { it !in setOf(Tool.SEARCH, Tool.COLLECTIONS, Tool.PREVIEW) }

private data class Panel(
    val id: Int,
    val tool: Tool,
    val x: Float,
    val y: Float,
    val widthDp: Float = 390f,
    val heightDp: Float = 305f,
    val minimized: Boolean = false,
    val maximized: Boolean = false,
    val order: Int = 0,
    val nativePackage: String = "",
    val nativeUser: Int = -1,
    val nativeTitle: String = "",
    val nativeIntent: String? = null,
    val fullscreen: Boolean = false,
    val appScale: Float = 1f,
    val phoneIme: Boolean = true,
    val profileRequest: Int = 0,
    val reloadRequest: Int = 0,
    val instance: String = java.util.UUID.randomUUID().toString()
) {
    val appPackage get() = nativePackage.ifBlank { when (tool) {
        Tool.CHATGPT -> "com.openai.chatgpt"; Tool.DISCORD -> "com.discord"; Tool.MEDIA -> "com.google.android.youtube"; else -> ""
    } }
    val native get() = tool in setOf(Tool.NATIVE, Tool.CHATGPT, Tool.DISCORD, Tool.MEDIA)
    val title get() = nativeTitle.ifBlank { tool.title }
    val windowKey get() = "native:$instance"
}


private val Ink: Color @Composable get() = LocalWorkspaceAppearance.current.ink
private val Ice = Color(0xFFE7EEE9)
private val Accent: Color @Composable get() = LocalWorkspaceAppearance.current.accent
private val Line: Color @Composable get() = LocalWorkspaceAppearance.current.accent.copy(alpha = 0.35f)
private val Tab: Color @Composable get() = LocalWorkspaceAppearance.current.surface

private fun loadPanels(context: Context, raw: String? = null): List<Panel> {
    return runCatching {
        val prefs = context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE)
        val density = if (prefs.getString("panel_units", "px") == "dp") 1f else context.resources.displayMetrics.density
        val array = JSONArray(raw ?: prefs.getString("panels", "[]"))
        (0 until array.length().coerceAtMost(32)).mapNotNull { i ->
            val item = array.getJSONObject(i)
            val tool = runCatching { canonical(Tool.valueOf(item.getString("tool"))) }.getOrNull()
                ?: return@mapNotNull null
            Panel(
                id = item.getInt("id"), tool = tool,
                x = (item.optDouble("x", 40.0).toFloat() / density).takeIf { it.isFinite() } ?: 40f,
                y = (item.optDouble("y", 70.0).toFloat() / density).takeIf { it.isFinite() } ?: 70f,
                widthDp = item.optDouble("w", 390.0).toFloat().coerceIn(100f, 1600f),
                heightDp = item.optDouble("h", 305.0).toFloat().coerceIn(100f, 1200f),
                minimized = item.optBoolean("min", false),
                maximized = item.optBoolean("max", false),
                order = item.optInt("z", i),
                nativePackage = item.optString("app").takeIf { it.isBlank() || runCatching { NativeProtocol.packageName(it) }.isSuccess }.orEmpty(),
                nativeUser = item.optInt("user", -1).coerceIn(-1, 9999), nativeTitle = item.optString("title").take(120),
                fullscreen = item.optBoolean("full", false),
                appScale = item.optDouble("appScale", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(.75f, 1.25f) ?: 1f,
                phoneIme = item.optBoolean("phoneIme", true),
                instance = item.optString("instance").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } ?: java.util.UUID.randomUUID().toString()
            )
        }.distinctBy { if (it.native) "native:${it.appPackage}:${it.nativeUser}" else it.tool.name }
    }.getOrDefault(emptyList())
}

private fun savePanels(context: Context, panels: List<Panel>, space: String = WorkspaceSessions.active(context)) {
    val array = JSONArray()
    panels.forEach { p ->
        array.put(JSONObject().apply {
            put("id", p.id); put("tool", canonical(p.tool).name)
            put("x", p.x); put("y", p.y)
            put("w", p.widthDp); put("h", p.heightDp)
            put("min", p.minimized); put("max", p.maximized); put("z", p.order)
            put("app", p.nativePackage); put("user", p.nativeUser); put("title", p.nativeTitle)
            put("full", p.fullscreen); put("appScale", p.appScale); put("phoneIme", p.phoneIme); put("instance", p.instance)
        })
    }
    context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE)
        .edit().putString("panels", array.toString()).putString("panel_units", "dp").apply()
    WorkspaceSessions.save(context, space, array.toString())
}

private fun openSystem(context: Context, action: String) {
    runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
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
    modifier: Modifier = Modifier,
    pfpSections: List<Pair<String, String>> = emptyList(),
    onOpenPfpFeature: (String) -> Unit = {},
    onSystemUiChanged: () -> Unit = {},
    pfpContent: @Composable () -> Unit = {},
    backgroundPath: String? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val appearance = rememberWorkspaceAppearance()
    val nativeBars = workspaceFlag("system_bars", false)
    val commandBar = workspaceFlag("command_bar", true)
    val autoIndex = workspaceFlag("auto_index", true)
    val indexStatus by WorkspaceIndexing.status.collectAsState()
    val externalRequest by WorkspaceRequests.requests.collectAsState()
    LaunchedEffect(nativeBars) { onSystemUiChanged() }
    val paneState = rememberSaveableStateHolder()
    var sessionId by remember { mutableStateOf(WorkspaceSessions.active(context)) }
    var menuVisible by rememberSaveable { mutableStateOf(true) }
    var commandPalette by remember { mutableStateOf(false) }
    val spaceStore = remember { WorkspaceSpaces.shared(context) }
    val spaceRevision by spaceStore.revision.collectAsState()
    val spaces = remember(spaceRevision) { spaceStore.all() }
    val panels = remember { mutableStateListOf<Panel>().apply {
        addAll(loadPanels(context, WorkspaceSessions.read(context, sessionId)))
    } }
    var nextId by remember { mutableStateOf((panels.maxOfOrNull { it.id } ?: 0) + 1) }
    var topOrder by remember { mutableStateOf((panels.maxOfOrNull { it.order } ?: 0) + 1) }
    var queuedCommand by remember { mutableStateOf<String?>(null) }
    var commandRequest by remember { mutableStateOf(0) }
    var fileLocation by remember { mutableStateOf<String?>(null) }
    var fileRequest by remember { mutableStateOf(0) }
    var previewUri by remember { mutableStateOf<String?>(null) }
    var queuedQuery by remember { mutableStateOf("") }; var queryRequest by remember { mutableStateOf(0) }
    var layoutRequest by remember { mutableStateOf("") }
    var adjusting by remember { mutableStateOf<Int?>(null) }
    val catalog = remember { UnifiedCatalog.shared(context) }
    var catalogRevision by remember { mutableStateOf(0) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    // Index once per real source update, not once per window (Search/Library/Collections
    // may all be visible simultaneously). The underlying PFP database is never modified.
    val lifecycleOwner = LocalLifecycleOwner.current
    var launcherActive by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var resumeRevision by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner, catalog) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { resumeRevision++; WorkspaceNative.refresh(); WorkspaceAutoLibrary.schedule(context); WorkspaceAutoLibrary.kickoff(context) }
            if (event == Lifecycle.Event.ON_START) launcherActive = true
            if (event == Lifecycle.Event.ON_STOP) {
                launcherActive = false
                WorkspaceIndexing.cancel()
                WorkspaceWebPolicy.suspendAll()
                WorkspaceNative.suspendAll(); WorkspaceAutoLibrary.stopForeground()
                if (context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE).getBoolean("pause_scripts", true) && !WorkspaceAutomation.connected(context)) TermuxBridge.pauseManaged(context)
            }
            if (event == Lifecycle.Event.ON_PAUSE) savePanels(context, panels.toList(), sessionId)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(Unit) { WorkspaceNative.initialize(context); WorkspaceAutoLibrary.schedule(context); WorkspaceAutoLibrary.kickoff(context) }
    LaunchedEffect(games) {
        val snapshot = games ?: return@LaunchedEffect
        workspaceResult { withContext(Dispatchers.IO) { catalog.syncPfpGames(snapshot) } }
            .onSuccess { catalogRevision++; catalogError = null }
            .onFailure { catalogError = "Game indexing failed: ${it.message}" }
    }
    LaunchedEffect(resumeRevision) {
        workspaceResult {
            withContext(Dispatchers.IO) {
                catalog.syncAndroidApps()
                catalog.syncSystemTools()
            }
        }.onSuccess { catalogRevision++; catalogError = null }
            .onFailure { catalogError = "App indexing failed: ${it.message}" }
    }
    LaunchedEffect(pfpSections) { withContext(Dispatchers.IO) { catalog.syncPfpFeatures(pfpSections) } }
    LaunchedEffect(resumeRevision, autoIndex, expanded, launcherActive) {
        if (autoIndex && expanded && launcherActive) workspaceResult { WorkspaceIndexing.refresh(catalog) }
            .onFailure { catalogError = "Folder refresh failed: ${it.message}" }
    }

    DisposableEffect(context) {
        val pref = context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE)
        var observedRestore = pref.getLong("restore_epoch", 0)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != "restore_epoch" && pref.getLong("restore_epoch", 0) != observedRestore) return@OnSharedPreferenceChangeListener
            if (key == "active_space" && WorkspaceSessions.active(context) == sessionId) return@OnSharedPreferenceChangeListener
            if (key in setOf("restore_epoch", "layout_epoch", "active_space")) {
                observedRestore = pref.getLong("restore_epoch", 0)
                val switching = key == "active_space" && WorkspaceSessions.active(context) != sessionId
                if (switching) savePanels(context, panels.toList(), sessionId)
                if (!switching) panels.filter { it.native }.forEach { WorkspaceNative.close(it.windowKey) }
                else panels.filter { it.native }.forEach { WorkspaceNative.detach(it.windowKey) }
                sessionId = WorkspaceSessions.active(context)
                val restored = if (switching || key == "restore_epoch") WorkspaceSessions.read(context, sessionId) ?: "[]" else null
                panels.clear(); panels.addAll(loadPanels(context, restored))
                menuVisible = panels.none { !it.minimized }
                nextId = (panels.maxOfOrNull { it.id } ?: 0) + 1
                topOrder = (panels.maxOfOrNull { it.order } ?: 0) + 1
            }
        }
        pref.registerOnSharedPreferenceChangeListener(listener)
        onDispose { pref.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // A short debounce keeps window dragging from writing flash storage every frame.
    val saveKey = panels.toList().hashCode()
    LaunchedEffect(saveKey, sessionId) {
        delay(550)
        savePanels(context, panels.toList(), sessionId)
    }

    fun update(id: Int, transform: (Panel) -> Panel) {
        val index = panels.indexOfFirst { it.id == id }
        if (index >= 0) panels[index] = transform(panels[index])
    }

    fun bringToFront(id: Int) {
        if (panels.filterNot { it.minimized }.maxByOrNull { it.order }?.id != id) update(id) { it.copy(order = topOrder++) }
    }
    fun activate(requested: Tool) {
        menuVisible = false
        val tool = canonical(requested)
        val old = panels.firstOrNull { it.tool == tool }
        if (old != null) {
            update(old.id) { it.copy(minimized = false, order = topOrder++) }
        } else {
            val stagger = (panels.size % 5) * 23f
            panels.add(Panel(nextId++, tool, 12f + stagger, 64f + stagger, widthDp = if (tool in setOf(Tool.GAMES, Tool.LIBRARY, Tool.PFP)) 640f else 390f, maximized = tool == Tool.PFP || (configuration.screenWidthDp < 600 && appearance.maximizePhone), order = topOrder++,
                fullscreen = appearance.nativeFullscreen && tool in setOf(Tool.NATIVE, Tool.CHATGPT, Tool.DISCORD, Tool.MEDIA)))
        }
        onSound(MenuSound.SELECT)
    }
    fun minimize(panel: Panel) {
        if (panel.tool == Tool.TERMINAL) TermuxBridge.pauseManaged(context)
        if (panel.native) WorkspaceNative.detach(panel.windowKey)
        update(panel.id) { it.copy(minimized = true) }
    }
    fun close(panel: Panel) {
        if (panel.tool == Tool.TERMINAL) TermuxBridge.stopManaged(context)
        if (panel.native) WorkspaceNative.close(panel.windowKey)
        panels.removeAll { it.id == panel.id }
        paneState.removeState("$sessionId:${panel.tool.name}:${panel.id}")
    }
    val nativeRequests by WorkspaceNativeRequests.requests.collectAsState()
    LaunchedEffect(nativeRequests) {
        if (nativeRequests.isNotEmpty()) menuVisible = false
        nativeRequests.forEach { request -> request.target?.let { target ->
            val old = panels.firstOrNull { it.native && it.appPackage == target.pkg && (it.nativeUser < 0 || it.nativeUser == target.user) }
            if (old != null) {
                if (request.intent != null) WorkspaceNative.close(old.windowKey)
                update(old.id) { it.copy(minimized = false, nativePackage = target.pkg, nativeUser = target.user,
                    nativeTitle = target.title, nativeIntent = request.intent, order = topOrder++) }
            } else {
                panels.add(Panel(nextId++, Tool.NATIVE, 20f, 64f, maximized = configuration.screenWidthDp < 600 && appearance.maximizePhone,
                    order = topOrder++, nativePackage = target.pkg, nativeUser = target.user, nativeTitle = target.title, nativeIntent = request.intent, fullscreen = appearance.nativeFullscreen))
            }
        } }
        if (nativeRequests.isNotEmpty() && !expanded) onToggle()
        WorkspaceNativeRequests.acknowledge(nativeRequests.map { it.serial }.toSet())
    }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("arcdeck_workspace", 0)
        if (context.applicationContext is dagger.hilt.internal.GeneratedComponentManager<*> && !prefs.getBoolean("native_setup_140", false)) {
            activate(Tool.AUTOLIB); prefs.edit().putBoolean("native_setup_140", true).apply()
            if (!expanded) onToggle()
        }
    }
    val controls by WorkspaceControls.requests.collectAsState()
    LaunchedEffect(controls) {
        var show = false
        controls.forEach { control -> when (control.action) {
            "open" -> { activate(Tool.valueOf(control.target)); show = true }
            "minimize" -> panels.firstOrNull { it.tool.name == WorkspaceWindowGeometry.canonical(control.target) }?.let(::minimize)
            "close" -> panels.firstOrNull { it.tool.name == WorkspaceWindowGeometry.canonical(control.target) }?.let(::close)
            "tile" -> { layoutRequest = control.target; show = true }
            "stop" -> { panels.toList().forEach(::minimize); TermuxBridge.stopManaged(context) }
        } }
        if (show && !expanded) onToggle()
        WorkspaceControls.acknowledge(controls.map { it.serial }.toSet())
    }
    LaunchedEffect(expanded) { if (!expanded) { TermuxBridge.pauseManaged(context); WorkspaceNative.suspendAll() } }

    LaunchedEffect(externalRequest.serial) {
        if (externalRequest.serial > 0 && externalRequest.tool != "NATIVE_PENDING") activate(Tool.entries.firstOrNull { it.name == externalRequest.tool } ?: Tool.SPACES)
    }
    fun preview(uri: String) { previewUri = uri; activate(Tool.PREVIEW) }
    CompositionLocalProvider(LocalWorkspaceAppearance provides appearance,
        LocalDensity provides Density(density.density, density.fontScale * appearance.font),
        androidx.compose.material3.LocalContentColor provides Ice) {
    MaterialTheme(colorScheme = darkColorScheme(primary = appearance.accent, onPrimary = Color(0xFF081C30),
        surface = appearance.surface, onSurface = Ice, background = appearance.ink, onBackground = Ice)) {
    val active = panels.filterNot { it.minimized }.maxByOrNull { it.order }
    val hostKeyboard = WindowInsets.ime.getBottom(density) > 0
    val fullscreenPanel = active?.takeIf { expanded && !menuVisible && (it.fullscreen || (hostKeyboard && it.native)) }
    val immersive = fullscreenPanel != null
    DisposableEffect(expanded, menuVisible, active?.tool) {
        WorkspaceInputFocus.pfpActive = expanded && !menuVisible && active?.tool == Tool.PFP
        onDispose { WorkspaceInputFocus.pfpActive = false }
    }
    androidx.activity.compose.BackHandler(expanded && !menuVisible && !immersive) { menuVisible = true }
    LaunchedEffect(immersive) { WorkspacePresentation.setImmersive(immersive); onSystemUiChanged() }
    DisposableEffect(Unit) { onDispose { WorkspacePresentation.setImmersive(false); runCatching { onSystemUiChanged() } } }
    BoxWithConstraints(modifier.fillMaxSize().then(if (immersive) Modifier.displayCutoutPadding() else if (nativeBars) Modifier.safeDrawingPadding() else Modifier).imePadding().zIndex(10000f)) {
        val viewportWidth = maxWidth.value
        val viewportHeight = maxHeight.value
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val keyboardShown = WindowInsets.ime.getBottom(density) > 0
        val chrome = WorkspaceDesktopGeometry.insets(appearance.dockPosition, !keyboardShown && !immersive, viewportWidth)
        val canvasWidth = (viewportWidth - chrome.left - chrome.right).coerceAtLeast(100f)
        val canvasHeight = (viewportHeight - chrome.top - chrome.bottom).coerceAtLeast(100f)
        LaunchedEffect(layoutRequest) {
            if (layoutRequest.isNotBlank()) {
                val visible = panels.filterNot { it.minimized }.sortedBy { it.order }
                visible.forEachIndexed { index, panel ->
                    val bounds = WorkspaceDesktopGeometry.tile(index, visible.size, viewportWidth, viewportHeight, layoutRequest, chrome)
                    update(panel.id) { it.copy(x = bounds.x, y = bounds.y, widthDp = bounds.width, heightDp = bounds.height, maximized = false, fullscreen = false) }
                }
                layoutRequest = ""
            }
        }
        if (!expanded && commandBar) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Tab).padding(horizontal = 8.dp)) {
            WorkspaceCommandBar(onSearch = { queuedQuery = it; queryRequest++; activate(Tool.LIBRARY); onToggle() },
                onTerminal = { queuedCommand = it; commandRequest++; activate(Tool.TERMINAL); onToggle() },
                onTool = { activate(Tool.valueOf(it)); onToggle() })
        }
        if (!expanded) Box(Modifier.align(Alignment.CenterEnd).background(Tab).clickable { onToggle() }.padding(12.dp)) { Text("ArcDeck", color = Ice) }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(appearance.motionMs)) + scaleIn(tween(appearance.motionMs), initialScale = 0.975f),
            exit = fadeOut(tween(appearance.motionMs)) + scaleOut(tween(appearance.motionMs), targetScale = 0.975f)
        ) {
            Box(Modifier.fillMaxSize().background(Ink.copy(alpha = 1f))) {
                if (backgroundPath != null) coil3.compose.AsyncImage(backgroundPath, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = appearance.dim)))
                if (!immersive && indexStatus.isNotBlank()) Text(indexStatus,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp).background(Tab).padding(6.dp),
                    color = Ice, fontSize = 11.sp, maxLines = 1)
                if (menuVisible || panels.none { !it.minimized }) Box(Modifier.fillMaxSize()
                    .padding(start = chrome.left.dp, top = chrome.top.dp, end = chrome.right.dp, bottom = chrome.bottom.dp)) {
                    WorkspaceMainMenu(
                        actions = dockTools().filter { it.name !in appearance.hidden || it in setOf(Tool.PFP, Tool.CUSTOMIZE) }
                            .sortedBy { appearance.dockOrder.indexOf(it.name).takeIf { n -> n >= 0 } ?: (100 + it.ordinal) }
                            .map { DesktopAction(it.name, it.title, it.symbol) },
                        games = games.orEmpty(), spaces = spaces,
                        onOpen = { id -> Tool.entries.firstOrNull { it.name == id }?.let(::activate) },
                        onSelectSpace = { spaceStore.select(it) }, onGame = onOpenPfpGame,
                        onSearch = { queuedQuery = it; queryRequest++; activate(Tool.LIBRARY) },
                        onTerminal = { queuedCommand = it; commandRequest++; activate(Tool.TERMINAL) },
                        pfpSections = pfpSections, onPfp = onOpenPfpFeature)
                }

                panels.sortedBy { it.order }.forEach { panel ->
                    androidx.compose.runtime.key(panel.id) {
                    val full = fullscreenPanel?.id == panel.id
                    if (!menuVisible && !panel.minimized && (fullscreenPanel == null || full) &&
                        ((active?.maximized != true && !keyboardShown) || active?.id == panel.id || full)) {
                        val effectiveMax = panel.maximized || keyboardShown || full
                        val widthDp = min(panel.widthDp, canvasWidth - 6f).coerceAtLeast(100f)
                        val heightDp = min(panel.heightDp, canvasHeight - 6f).coerceAtLeast(100f)
                        val actualWidth = with(density) { widthDp.dp.toPx() }
                        val actualHeight = with(density) { heightDp.dp.toPx() }
                        val top = with(density) { (if (keyboardShown || full) 0f else chrome.top).dp.toPx() }
                        val bottom = with(density) { (if (keyboardShown || full) 0f else chrome.bottom).dp.toPx() }
                        val left = with(density) { chrome.left.dp.toPx() }; val right = with(density) { chrome.right.dp.toPx() }
                        val x = if (effectiveMax) left else with(density) { panel.x.dp.toPx() }.coerceIn(left, max(left, screenWidthPx - actualWidth - right))
                        val y = if (effectiveMax) top else with(density) { panel.y.dp.toPx() }.coerceIn(top, max(top, screenHeightPx - actualHeight - bottom))
                        val drawWidth = if (effectiveMax) (if (full || keyboardShown) viewportWidth else canvasWidth).dp else widthDp.dp
                        val drawHeight = if (effectiveMax) (viewportHeight - if (keyboardShown || full) 0f else (chrome.top + chrome.bottom)).coerceAtLeast(100f).dp else heightDp.dp
                        WorkspaceWindowFrame(panel.title, panel.tool.symbol, full, effectiveMax,
                            Modifier.zIndex(1f + panel.order).offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                                .width(drawWidth).height(drawHeight).testTag("workspace-panel-${panel.tool.name}")
                                .then(if (full) Modifier.testTag("workspace-fullscreen") else Modifier)
                                .pointerInput(panel.id) { awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); bringToFront(panel.id)
                                } },
                            corners = appearance.corners,
                            onArrange = { adjusting = panel.id }, onMinimize = { minimize(panel) },
                            onMaximize = { update(panel.id) { it.copy(maximized = !it.maximized) } },
                            onFullscreen = { update(panel.id) { it.copy(fullscreen = true, order = topOrder++) } },
                            onClose = { close(panel); onSound(MenuSound.BACK) },
                            onMove = { delta -> update(panel.id) { p -> p.copy(
                                x = (p.x + with(density) { delta.x.toDp().value }).coerceIn(chrome.left, max(chrome.left, viewportWidth - widthDp - chrome.right)),
                                y = (p.y + with(density) { delta.y.toDp().value }).coerceIn(chrome.top, max(chrome.top, viewportHeight - heightDp - chrome.bottom))) } },
                            onResize = { delta -> update(panel.id) { p -> p.copy(
                                maximized = false, fullscreen = false,
                                widthDp = (p.widthDp + with(density) { delta.x.toDp().value }).coerceIn(220f, max(220f, canvasWidth - 6f)),
                                heightDp = (p.heightDp + with(density) { delta.y.toDp().value }).coerceIn(160f, max(160f, canvasHeight - 6f))) } }) {
                                paneState.SaveableStateProvider("$sessionId:${panel.tool.name}:${panel.id}") {
                                when (panel.tool) {
                                    Tool.SEARCH, Tool.LIBRARY, Tool.COLLECTIONS, Tool.GAMES -> UnifiedLibraryPane(
                                        catalog = catalog,
                                        sourceRevision = catalogRevision,
                                        sourceError = catalogError,
                                        initiallyCollections = panel.tool == Tool.COLLECTIONS,
                                        onOpenPfpGame = onOpenPfpGame,
                                        onSetPfpFavorite = onSetPfpFavorite,
                                        onRunCommand = { command ->
                                            queuedCommand = command
                                            commandRequest++
                                            activate(Tool.TERMINAL)
                                        },
                                        onBrowseFolder = { uri ->
                                            fileLocation = uri; fileRequest++; activate(Tool.FILES)
                                        },
                                        onOpenWorkspaceTool = { id ->
                                            val tool = Tool.entries.firstOrNull { it.name.equals(id, true) }
                                            if (tool != null) activate(tool)
                                        },
                                        onPreview = ::preview,
                                        onOpenPfpFeature = onOpenPfpFeature,
                                        onSpaceCreated = { activate(Tool.SPACES) }, initialQuery = if (panel.tool == Tool.GAMES) "type:game" else queuedQuery, queryRequest = queryRequest, gameArt = games.orEmpty(),
                                    )
                                    Tool.FILES -> WorkspaceFilesPane(catalog, fileLocation, onOpenPfpGame, fileRequest, ::preview)
                                    Tool.APPS -> UnifiedLibraryPane(catalog, catalogRevision, catalogError, false,
                                        onOpenPfpGame, onSetPfpFavorite, { queuedCommand = it; commandRequest++; activate(Tool.TERMINAL) },
                                        { id -> Tool.entries.firstOrNull { it.name.equals(id, true) }?.let { activate(it) } },
                                        { uri -> fileLocation = uri; fileRequest++; activate(Tool.FILES) }, initialQuery = "type:app", gameArt = games.orEmpty(),
                                        onPreview = ::preview, onOpenPfpFeature = onOpenPfpFeature, onSpaceCreated = { activate(Tool.SPACES) })
                                    Tool.SPACES -> WorkspaceSpacePane(catalog, ::preview,
                                        { uri -> fileLocation = uri; fileRequest++; activate(Tool.FILES) }, onOpenPfpGame,
                                        { command -> queuedCommand = command; commandRequest++; activate(Tool.TERMINAL) },
                                        { target -> when {
                                            target.startsWith("arcdeck:") -> Tool.entries.firstOrNull { it.name.equals(target.removePrefix("arcdeck:"), true) }?.let { activate(it) }
                                            target.startsWith("pfp:") -> onOpenPfpFeature(target.removePrefix("pfp:"))
                                            else -> openSystem(context, target)
                                        } })
                                    Tool.PREVIEW -> WorkspacePreviewPane(previewUri)
                                    Tool.PFP -> pfpContent()
                                    Tool.PRESETS -> WorkspacePapayaPane(onOpenPfpFeature)
                                    Tool.CUSTOMIZE -> WorkspaceAppearancePane(dockTools().map { it.name to it.title },
                                        { savePanels(context, panels.toList(), sessionId) }, onOpenPfpFeature)
                                    Tool.TERMINAL -> WorkspaceTerminalPane(queuedCommand, commandRequest)
                                    Tool.NOTES -> WorkspaceNotesPane()
                                    Tool.CLIPBOARD -> WorkspaceClipboardPane()
                                    Tool.AUTOMATION -> WorkspaceAutomationPane(catalog)
                                    Tool.GAMING -> WorkspaceGameConnectionsPane(catalog, { activate(Tool.SPACES) },
                                        { uri -> fileLocation = uri; fileRequest++; activate(Tool.FILES) }, { onOpenPfpFeature("library") })
                                    Tool.NATIVE, Tool.CHATGPT, Tool.DISCORD, Tool.MEDIA -> if (launcherActive) WorkspaceNativePane(
                                        panel.windowKey, panel.appPackage, panel.nativeUser, panel.nativeIntent, focused = active?.id == panel.id, showToolbar = false,
                                        profileRequest = panel.profileRequest, reloadRequest = panel.reloadRequest, appScale = panel.appScale, phoneIme = panel.phoneIme,
                                        onProfileRequestConsumed = { update(panel.id) { it.copy(profileRequest = 0) } },
                                        onSelect = { app -> update(panel.id) { it.copy(nativePackage = app.pkg, nativeUser = app.user, nativeTitle = app.title, nativeIntent = null) } })
                                        else Text("Native window suspended while ArcDeck is in the background")
                                    Tool.AUTOLIB -> WorkspaceAutoLibraryPane(catalog,
                                        { uri -> fileLocation = uri; fileRequest++; activate(Tool.FILES) }, { onOpenPfpFeature("library") })
                                    Tool.SYSTEM -> SystemTool(onClose, onSystemUiChanged) {
                                        panels.filter { it.native }.forEach { WorkspaceNative.close(it.windowKey) }; panels.clear()
                                        panels.add(Panel(nextId++, Tool.LIBRARY, 12f, 64f, maximized = configuration.screenWidthDp < 600))
                                    }
                                }
                            }
                        }
                    }
                    }
                }
                fullscreenPanel?.let { current ->
                    fun park() {
                        if (keyboardShown && current.native) WorkspaceNative.key(current.windowKey, android.view.KeyEvent.KEYCODE_BACK)
                        minimize(current)
                    }
                    WorkspaceFullscreenControls(onPfp = { park(); menuVisible = true }, onWorkspace = { park(); menuVisible = false },
                        onWindowed = { update(current.id) { it.copy(fullscreen = false, maximized = false) } },
                        onArrange = { adjusting = current.id }, onClose = { close(current) })
                }

                if (!keyboardShown && !immersive) {
                    val barModifier = when (appearance.dockPosition) {
                        "Left" -> Modifier.align(Alignment.CenterStart).width(chrome.left.dp).fillMaxHeight()
                        "Right" -> Modifier.align(Alignment.CenterEnd).width(chrome.right.dp).fillMaxHeight()
                        "Bottom" -> Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(56.dp)
                        else -> Modifier.align(Alignment.TopCenter).fillMaxWidth().height(56.dp)
                    }
                    WorkspaceDesktopBar(appearance.dockPosition, barModifier.zIndex(100000f),
                        panels.sortedBy { it.order }.map { it.id to it.title }, active?.id,
                        onMenu = { menuVisible = !menuVisible }, onFocus = { id -> menuVisible = false; update(id) { it.copy(minimized = false, order = topOrder++) } },
                        onCommand = { commandPalette = true }, onArrange = { menuVisible = false; layoutRequest = it }, onEdit = { activate(Tool.CUSTOMIZE) })
                }
                if (commandPalette) WorkspaceDialog({ commandPalette = false }, { Text("Search and commands") }, {
                    WorkspaceCommandBar(
                        { queuedQuery = it; queryRequest++; commandPalette = false; activate(Tool.LIBRARY) },
                        { queuedCommand = it; commandRequest++; commandPalette = false; activate(Tool.TERMINAL) },
                        { id -> commandPalette = false; Tool.entries.firstOrNull { it.name == id }?.let(::activate) }, showShortcuts = false)
                }, { TextButton(onClick = { commandPalette = false }) { Text("Done") } })
                adjusting?.let { id -> panels.firstOrNull { it.id == id }?.let { selected ->
                    WorkspaceDialog({ adjusting = null }, { Text("Arrange ${selected.title}") }, {
                        Column(Modifier.heightIn(max = (viewportHeight - 150f).coerceAtLeast(120f).dp).verticalScroll(rememberScrollState())) {
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                TextButton(onClick = { minimize(selected); adjusting = null }) { Text("Minimize") }
                                TextButton(onClick = { update(id) { it.copy(fullscreen = true, order = topOrder++) }; adjusting = null }) { Text("Full screen") }
                                TextButton(onClick = { update(id) { it.copy(fullscreen = false, maximized = false) }; adjusting = null }) { Text("Windowed") }
                            }
                            if (selected.native) {
                                Row(Modifier.horizontalScroll(rememberScrollState())) {
                                    TextButton(onClick = { WorkspaceNative.key(selected.windowKey, android.view.KeyEvent.KEYCODE_BACK) }) { Text("App Back") }
                                    TextButton(onClick = { update(id) { it.copy(profileRequest = it.profileRequest + 1) }; adjusting = null }) { Text("App / profile") }
                                    TextButton(onClick = { WorkspaceNative.close(selected.windowKey); update(id) { it.copy(reloadRequest = it.reloadRequest + 1) }; adjusting = null }) { Text("Reload app") }
                                    TextButton(onClick = { runCatching { WorkspaceNative.openNormally(context, selected.appPackage, selected.nativeUser) }; adjusting = null }) { Text("Open normally") }
                                }
                                Text("App text size · ${(selected.appScale * 100).roundToInt()}%")
                                androidx.compose.material3.Slider(selected.appScale, { value -> update(id) { it.copy(appScale = value) } }, valueRange = .75f..1.25f)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.Switch(selected.phoneIme, { value -> update(id) { it.copy(phoneIme = value) } })
                                    Text("Use phone-sized Android keyboard", Modifier.padding(start = 8.dp))
                                }
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                TextButton(onClick = { layoutRequest = "side"; adjusting = null }) { Text("Tile side by side") }
                                TextButton(onClick = { layoutRequest = "grid"; adjusting = null }) { Text("Tile grid") }
                                TextButton(onClick = { layoutRequest = "cascade"; adjusting = null }) { Text("Cascade") }
                            }
                            Text("Horizontal position")
                            androidx.compose.material3.Slider(selected.x.coerceIn(chrome.left, max(chrome.left, viewportWidth - chrome.right - selected.widthDp)), { value -> update(id) { it.copy(x = value, maximized = false, fullscreen = false) } }, valueRange = chrome.left..max(chrome.left, viewportWidth - chrome.right - selected.widthDp))
                            Text("Vertical position")
                            androidx.compose.material3.Slider(selected.y.coerceIn(chrome.top, max(chrome.top, viewportHeight - selected.heightDp - chrome.bottom)), { value -> update(id) { it.copy(y = value, maximized = false, fullscreen = false) } }, valueRange = chrome.top..max(chrome.top, viewportHeight - selected.heightDp - chrome.bottom))
                            Text("Window width")
                            androidx.compose.material3.Slider(selected.widthDp.coerceIn(160f, max(160f, canvasWidth - 6f)), { value -> update(id) { it.copy(widthDp = value, maximized = false, fullscreen = false) } }, valueRange = 160f..max(160f, canvasWidth - 6f))
                            Text("Window height")
                            androidx.compose.material3.Slider(selected.heightDp.coerceIn(100f, max(100f, canvasHeight - 6f)), { value -> update(id) { it.copy(heightDp = value, maximized = false, fullscreen = false) } }, valueRange = 100f..max(100f, canvasHeight - 6f))
                        }
                    }, { TextButton(onClick = { adjusting = null }) { Text("Done") } })
                } }
            }
        }
    }
    }
    }
}

@Composable
private fun FrameControl(label: String, onClick: () -> Unit) {
    Text(label, Modifier.semantics { contentDescription = when (label) { "—" -> "Minimize window"; "×" -> "Close window"; "⋮" -> "Arrange window"; else -> "Toggle window size" } }
        .clickable(onClick = onClick).size(44.dp).wrapContentSize(Alignment.Center),
        color = Ice, fontSize = 16.sp)
}

@Composable
private fun SystemTool(onClose: () -> Unit, onSystemUiChanged: () -> Unit, onReset: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE) }
    var shortcut by remember { mutableStateOf(preferences.getInt("workspace_key", android.view.KeyEvent.KEYCODE_F10)) }
    var landscape by remember { mutableStateOf(preferences.getBoolean("landscape_prompt", false)) }
    var editShortcut by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        ListAction("⚙  Android settings") { openSystem(context, Settings.ACTION_SETTINGS) }
        ListAction("⌂  Change default home app") { openSystem(context, Settings.ACTION_HOME_SETTINGS) }
        ListAction("▣  File manager") { openSystem(context, Settings.ACTION_INTERNAL_STORAGE_SETTINGS) }
        ListAction("▤  Wi-Fi settings") { openSystem(context, Settings.ACTION_WIFI_SETTINGS) }
        ListAction("◉  Bluetooth settings") { openSystem(context, Settings.ACTION_BLUETOOTH_SETTINGS) }
        WorkspaceAndroidControls(onSystemUiChanged)
        ListAction("⌨  Workspace shortcut: ${when (shortcut) {
            android.view.KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
            android.view.KeyEvent.KEYCODE_BUTTON_START -> "Start"
            android.view.KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
            android.view.KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
            else -> "F10"
        }}") { editShortcut = true }
        ListAction("▣  Landscape reminder: ${if (landscape) "on" else "off"}") {
            landscape = !landscape; preferences.edit().putBoolean("landscape_prompt", landscape).apply()
        }
        ListAction("▦  Reset window layout") { onReset() }
        Text("ArcDeck Workspace 1.5 · Native app windows, full-screen focus and one game library. Use Library → Collections for backup and storage roots.",
            color = Ice, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        ListAction("✕  Close workspace") { onClose() }
    }
    if (editShortcut) WorkspaceDialog(onDismissRequest = { editShortcut = false }, title = { Text("Workspace shortcut") },
        text = { Column {
            listOf("F10" to android.view.KeyEvent.KEYCODE_F10, "Controller Select" to android.view.KeyEvent.KEYCODE_BUTTON_SELECT,
                "Controller Start" to android.view.KeyEvent.KEYCODE_BUTTON_START, "L3" to android.view.KeyEvent.KEYCODE_BUTTON_THUMBL,
                "R3" to android.view.KeyEvent.KEYCODE_BUTTON_THUMBR).forEach { (label, code) ->
                TextButton(onClick = { shortcut = code; preferences.edit().putInt("workspace_key", code).apply(); editShortcut = false }) { Text(label) }
            }
        } }, confirmButton = { TextButton(onClick = { editShortcut = false }) { Text("Close") } })
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
