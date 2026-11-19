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
    SPACES("Spaces", "◈"), SEARCH("Search", "⌕"), LIBRARY("Library", "▤"), COLLECTIONS("Collections", "◇"),
    FILES("Files", "▣"), APPS("Apps", "▦"),
    TERMINAL("Terminal", ">_"), NOTES("Notes", "✎"), PFP("PFP features", "⌂"),
    CUSTOMIZE("Customize", "◐"), PREVIEW("Preview", "◉"), SYSTEM("System", "⚙"),
    CHATGPT("ChatGPT", "✦"), DISCORD("Discord", "◉"), MEDIA("Media", "♫"),
    CLIPBOARD("Clipboard", "▧"), GAMING("Game connections", "▶"), AUTOMATION("Automation", "⇄"), NATIVE("Installed app", "▦"), AUTOLIB("Phone library", "↻")
}
private fun canonical(tool: Tool): Tool = Tool.valueOf(WorkspaceWindowGeometry.canonical(tool.name))
private fun dockTools() = Tool.entries.filter { it !in setOf(Tool.SEARCH, Tool.APPS, Tool.COLLECTIONS, Tool.PREVIEW) }

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
    val nativeIntent: String? = null
) {
    val appPackage get() = nativePackage.ifBlank { when (tool) {
        Tool.CHATGPT -> "com.openai.chatgpt"; Tool.DISCORD -> "com.discord"; Tool.MEDIA -> "com.google.android.youtube"; else -> ""
    } }
    val native get() = tool in setOf(Tool.NATIVE, Tool.CHATGPT, Tool.DISCORD, Tool.MEDIA)
    val title get() = nativeTitle.ifBlank { tool.title }
    val windowKey get() = "native:$id"
}


private val Ink: Color @Composable get() = LocalWorkspaceAppearance.current.ink
private val Ice = Color(0xFFCAE8FF)
private val Accent: Color @Composable get() = LocalWorkspaceAppearance.current.accent
private val Line: Color @Composable get() = LocalWorkspaceAppearance.current.accent.copy(alpha = 0.35f)
private val Tab: Color @Composable get() = LocalWorkspaceAppearance.current.surface

private fun loadPanels(context: Context): List<Panel> {
    return runCatching {
        val prefs = context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE)
        val density = if (prefs.getString("panel_units", "px") == "dp") 1f else context.resources.displayMetrics.density
        val array = JSONArray(prefs.getString("panels", "[]"))
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
                nativeUser = item.optInt("user", -1).coerceIn(-1, 9999), nativeTitle = item.optString("title").take(120)
            )
        }.distinctBy { if (it.native) "native:${it.appPackage}:${it.nativeUser}" else it.tool.name }
    }.getOrDefault(emptyList())
}

private fun savePanels(context: Context, panels: List<Panel>) {
    val array = JSONArray()
    panels.forEach { p ->
        array.put(JSONObject().apply {
            put("id", p.id); put("tool", canonical(p.tool).name)
            put("x", p.x); put("y", p.y)
            put("w", p.widthDp); put("h", p.heightDp)
            put("min", p.minimized); put("max", p.maximized); put("z", p.order)
            put("app", p.nativePackage); put("user", p.nativeUser); put("title", p.nativeTitle)
        })
    }
    context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE)
        .edit().putString("panels", array.toString()).putString("panel_units", "dp").apply()
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
    val panels = remember { mutableStateListOf<Panel>().apply {
        val saved = loadPanels(context)
        val firstLaunch = !context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE).contains("panels")
        addAll(if (firstLaunch) listOf(Panel(1, Tool.LIBRARY, 12f, 64f, maximized = configuration.screenWidthDp < 600)) else saved)
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
            if (event == Lifecycle.Event.ON_PAUSE) savePanels(context, panels.toList())
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
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "restore_epoch" || key == "layout_epoch") {
                panels.filter { it.native }.forEach { WorkspaceNative.close(it.windowKey) }
                panels.clear(); panels.addAll(loadPanels(context))
                nextId = (panels.maxOfOrNull { it.id } ?: 0) + 1
                topOrder = (panels.maxOfOrNull { it.order } ?: 0) + 1
            }
        }
        pref.registerOnSharedPreferenceChangeListener(listener)
        onDispose { pref.unregisterOnSharedPreferenceChangeListener(listener) }
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

    fun activate(requested: Tool) {
        val tool = canonical(requested)
        val old = panels.firstOrNull { it.tool == tool }
        if (old != null) {
            update(old.id) { it.copy(minimized = false, order = topOrder++) }
        } else {
            val stagger = (panels.size % 5) * 23f
            panels.add(Panel(nextId++, tool, 12f + stagger, 64f + stagger, maximized = configuration.screenWidthDp < 600 && appearance.maximizePhone, order = topOrder++))
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
        paneState.removeState("${panel.tool.name}:${panel.id}")
    }
    val nativeRequests by WorkspaceNativeRequests.requests.collectAsState()
    LaunchedEffect(nativeRequests) {
        nativeRequests.forEach { request -> request.target?.let { target ->
            val old = panels.firstOrNull { it.native && it.appPackage == target.pkg && (it.nativeUser < 0 || it.nativeUser == target.user) }
            if (old != null) {
                if (request.intent != null) WorkspaceNative.close(old.windowKey)
                update(old.id) { it.copy(minimized = false, nativePackage = target.pkg, nativeUser = target.user,
                    nativeTitle = target.title, nativeIntent = request.intent, order = topOrder++) }
            } else {
                panels.add(Panel(nextId++, Tool.NATIVE, 20f, 64f, maximized = configuration.screenWidthDp < 600 && appearance.maximizePhone,
                    order = topOrder++, nativePackage = target.pkg, nativeUser = target.user, nativeTitle = target.title, nativeIntent = request.intent))
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
    BoxWithConstraints(modifier.fillMaxSize().then(if (nativeBars) Modifier.safeDrawingPadding() else Modifier).imePadding().zIndex(10000f)) {
        val viewportWidth = maxWidth.value
        val viewportHeight = maxHeight.value
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val keyboardShown = WindowInsets.ime.getBottom(density) > 0
        LaunchedEffect(layoutRequest) {
            if (layoutRequest.isNotBlank()) {
                val visible = panels.filterNot { it.minimized }.sortedBy { it.order }
                visible.forEachIndexed { index, panel ->
                    val bounds = WorkspaceWindowGeometry.tile(index, visible.size, viewportWidth, viewportHeight, layoutRequest)
                    update(panel.id) { it.copy(x = bounds.x, y = bounds.y, widthDp = bounds.width, heightDp = bounds.height, maximized = false) }
                }
                layoutRequest = ""
            }
        }
        if (!expanded && commandBar) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Tab).padding(horizontal = 8.dp)) {
            WorkspaceCommandBar(onSearch = { queuedQuery = it; queryRequest++; activate(Tool.LIBRARY); onToggle() },
                onTerminal = { queuedCommand = it; commandRequest++; activate(Tool.TERMINAL); onToggle() },
                onTool = { activate(Tool.valueOf(it)); onToggle() })
        }
        // The always-visible grip gives touch users a reliable entry without overriding PFP's
        // Start/Select mappings, boot visuals, navigation, or its own categories.
        Box(
            Modifier.align(Alignment.CenterEnd).zIndex(1000000f)
                .clip(RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp))
                .background(Tab)
                .border(1.dp, Line, RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp))
                .semantics { contentDescription = if (expanded) "Close ArcDeck workspace" else "Open ArcDeck workspace" }
                .clickable { onSound(MenuSound.SELECT); onToggle() }
                .padding(horizontal = 11.dp, vertical = 17.dp)
        ) { Text(if (expanded) "‹" else "WS", color = Ice, fontWeight = FontWeight.Bold) }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(appearance.motionMs)) + scaleIn(tween(appearance.motionMs), initialScale = 0.975f),
            exit = fadeOut(tween(appearance.motionMs)) + scaleOut(tween(appearance.motionMs), targetScale = 0.975f)
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = appearance.dim))) {
                if (indexStatus.isNotBlank()) Text(indexStatus,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp).background(Tab).padding(6.dp),
                    color = Ice, fontSize = 11.sp, maxLines = 1)
                if (!keyboardShown) Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Tab).border(1.dp, Line, RoundedCornerShape(24.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text("⌂ PFP", Modifier.clickable { onOpenPfpFeature("original") }.padding(horizontal = 9.dp, vertical = 7.dp), color = Ice, fontSize = 12.sp)
                    WorkspaceScrollableRow(Modifier.weight(1f)) {
                    dockTools().filter { it.name !in appearance.hidden || it in setOf(Tool.PFP, Tool.CUSTOMIZE) }
                        .sortedBy { appearance.dockOrder.indexOf(it.name).takeIf { i -> i >= 0 } ?: (100 + it.ordinal) }.forEach { tool ->
                        Text(
                            "${tool.symbol} ${tool.title}",
                            Modifier.testTag("workspace-tool-${tool.name}").clip(RoundedCornerShape(14.dp))
                                .clickable { activate(tool) }
                                .padding(horizontal = if (appearance.compact) 8.dp else 12.dp, vertical = 12.dp),
                            color = Ice, fontSize = 12.sp
                        )
                    }
                    }
                    Text("▥", Modifier.clickable { layoutRequest = "side" }.padding(12.dp), color = Ice)
                    Text("×", Modifier.clickable { onSound(MenuSound.BACK); onClose() }
                        .padding(horizontal = 9.dp, vertical = 7.dp), color = Ice)
                }

                val active = panels.filterNot { it.minimized }.maxByOrNull { it.order }
                panels.sortedBy { it.order }.forEach { panel ->
                    if (!panel.minimized && ((active?.maximized != true && !keyboardShown) || active?.id == panel.id)) {
                        val effectiveMax = panel.maximized || keyboardShown
                        val widthDp = min(panel.widthDp, viewportWidth - 6f).coerceAtLeast(100f)
                        val heightDp = min(panel.heightDp, viewportHeight - 114f).coerceAtLeast(100f)
                        val actualWidth = with(density) { widthDp.dp.toPx() }
                        val actualHeight = with(density) { heightDp.dp.toPx() }
                        val top = with(density) { (if (keyboardShown) 0 else 62).dp.toPx() }
                        val bottom = with(density) { (if (keyboardShown) 0 else 52).dp.toPx() }
                        val x = if (effectiveMax) 0f else with(density) { panel.x.dp.toPx() }.coerceIn(0f, max(0f, screenWidthPx - actualWidth))
                        val y = if (effectiveMax) top else with(density) { panel.y.dp.toPx() }.coerceIn(top, max(top, screenHeightPx - actualHeight - bottom))
                        val drawWidth = if (effectiveMax) viewportWidth.dp else widthDp.dp
                        val drawHeight = if (effectiveMax) (viewportHeight - if (keyboardShown) 0f else 114f).coerceAtLeast(100f).dp else heightDp.dp

                        Column(
                            Modifier.zIndex(1f + panel.order)
                                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                                .width(drawWidth).height(drawHeight)
                                .clip(RoundedCornerShape(appearance.corners.dp))
                                .testTag("workspace-panel-${panel.tool.name}")
                                .pointerInput(panel.id) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        update(panel.id) { it.copy(order = topOrder++) }
                                    }
                                }
                                .background(Brush.verticalGradient(listOf(appearance.surface, Ink)))
                                .border(1.dp, Line, RoundedCornerShape(appearance.corners.dp))
                                .clickable { update(panel.id) { it.copy(order = topOrder++) } }
                        ) {
                            Row(
                                Modifier.fillMaxWidth().height(48.dp)
                                    .background(appearance.surface)
                                    .pointerInput(panel.id, effectiveMax) {
                                        if (!effectiveMax) detectDragGestures { change, delta ->
                                            change.consume()
                                            update(panel.id) { p -> p.copy(
                                                x = (p.x + with(density) { delta.x.toDp().value }).coerceIn(0f, max(0f, viewportWidth - widthDp)),
                                                y = (p.y + with(density) { delta.y.toDp().value }).coerceIn(62f, max(62f, viewportHeight - heightDp - 52f))
                                            ) }
                                        }
                                    }
                                    .padding(start = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(panel.tool.symbol + "  " + panel.title.uppercase(),
                                    Modifier.weight(1f), color = Ice, fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold)
                                FrameControl("⋮") { adjusting = panel.id }
                                FrameControl("—") { minimize(panel) }
                                FrameControl(if (panel.maximized) "▢" else "□") {
                                    update(panel.id) { it.copy(maximized = !it.maximized) }
                                }
                                FrameControl("×") {
                                    close(panel)
                                    onSound(MenuSound.BACK)
                                }
                            }
                            Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
                                paneState.SaveableStateProvider("${panel.tool.name}:${panel.id}") {
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
                                        onSpaceCreated = { activate(Tool.SPACES) }, initialQuery = queuedQuery, queryRequest = queryRequest,
                                    )
                                    Tool.FILES -> WorkspaceFilesPane(catalog, fileLocation, onOpenPfpGame, fileRequest, ::preview)
                                    Tool.APPS -> UnifiedLibraryPane(catalog, catalogRevision, catalogError, false,
                                        onOpenPfpGame, onSetPfpFavorite, { queuedCommand = it; commandRequest++; activate(Tool.TERMINAL) },
                                        { id -> Tool.entries.firstOrNull { it.name.equals(id, true) }?.let { activate(it) } },
                                        { uri -> fileLocation = uri; fileRequest++; activate(Tool.FILES) }, initialQuery = "type:app",
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
                                    Tool.PFP -> WorkspacePfpPane(pfpSections, onOpenPfpFeature)
                                    Tool.CUSTOMIZE -> WorkspaceAppearancePane(dockTools().map { it.name to it.title },
                                        { savePanels(context, panels.toList()) }, onOpenPfpFeature)
                                    Tool.TERMINAL -> WorkspaceTerminalPane(queuedCommand, commandRequest)
                                    Tool.NOTES -> WorkspaceNotesPane()
                                    Tool.CLIPBOARD -> WorkspaceClipboardPane()
                                    Tool.AUTOMATION -> WorkspaceAutomationPane(catalog)
                                    Tool.GAMING -> WorkspaceGameConnectionsPane(catalog, { activate(Tool.SPACES) },
                                        { uri -> fileLocation = uri; fileRequest++; activate(Tool.FILES) }, { onOpenPfpFeature("library") })
                                    Tool.NATIVE, Tool.CHATGPT, Tool.DISCORD, Tool.MEDIA -> if (launcherActive) WorkspaceNativePane(
                                        panel.windowKey, panel.appPackage, panel.nativeUser, panel.nativeIntent, focused = active?.id == panel.id,
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
                            if (!effectiveMax) {
                                Box(
                                    Modifier.align(Alignment.End).size(44.dp)
                                        .pointerInput(panel.id) {
                                            detectDragGestures { change, delta ->
                                                change.consume()
                                                update(panel.id) { p ->
                                                    p.copy(widthDp = (p.widthDp + with(density) { delta.x.toDp().value })
                                                        .coerceIn(160f, max(160f, viewportWidth - 12f)),
                                                        heightDp = (p.heightDp + with(density) { delta.y.toDp().value })
                                                        .coerceIn(100f, max(100f, viewportHeight - 114f)))
                                                }
                                            }
                                        }.clickable { }
                                ) { Text("◢", Modifier.align(Alignment.Center), color = Accent) }
                            }
                        }
                    }
                }

                if (!keyboardShown) WorkspaceScrollableRow(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)
                        .clip(RoundedCornerShape(16.dp)).background(Tab)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    panels.sortedBy { it.order }.forEach { panel ->
                        Text(
                            panel.tool.title,
                            Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                                update(panel.id) { it.copy(minimized = false, order = topOrder++) }
                            }.padding(horizontal = 14.dp, vertical = 12.dp),
                            color = if (panel.minimized) Color.LightGray else Accent,
                            fontSize = 12.sp
                        )
                    }
                }
                adjusting?.let { id -> panels.firstOrNull { it.id == id }?.let { selected ->
                    WorkspaceDialog({ adjusting = null }, { Text("Arrange ${selected.tool.title}") }, {
                        Column(Modifier.heightIn(max = (viewportHeight - 150f).coerceAtLeast(120f).dp).verticalScroll(rememberScrollState())) {
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                TextButton(onClick = { layoutRequest = "side"; adjusting = null }) { Text("Tile side by side") }
                                TextButton(onClick = { layoutRequest = "grid"; adjusting = null }) { Text("Tile grid") }
                                TextButton(onClick = { layoutRequest = "cascade"; adjusting = null }) { Text("Cascade") }
                            }
                            Text("Horizontal position")
                            androidx.compose.material3.Slider(selected.x.coerceIn(0f, max(0f, viewportWidth - selected.widthDp)), { value -> update(id) { it.copy(x = value, maximized = false) } }, valueRange = 0f..max(0f, viewportWidth - selected.widthDp))
                            Text("Vertical position")
                            androidx.compose.material3.Slider(selected.y.coerceIn(62f, max(62f, viewportHeight - selected.heightDp - 52f)), { value -> update(id) { it.copy(y = value, maximized = false) } }, valueRange = 62f..max(62f, viewportHeight - selected.heightDp - 52f))
                            Text("Window width")
                            androidx.compose.material3.Slider(selected.widthDp.coerceIn(160f, max(160f, viewportWidth - 6f)), { value -> update(id) { it.copy(widthDp = value, maximized = false) } }, valueRange = 160f..max(160f, viewportWidth - 6f))
                            Text("Window height")
                            androidx.compose.material3.Slider(selected.heightDp.coerceIn(100f, max(100f, viewportHeight - 114f)), { value -> update(id) { it.copy(heightDp = value, maximized = false) } }, valueRange = 100f..max(100f, viewportHeight - 114f))
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
        Text("ArcDeck Workspace 1.3 · PFP, app spaces and web windows share one library. Use Library → Collections for backup and storage roots.",
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
