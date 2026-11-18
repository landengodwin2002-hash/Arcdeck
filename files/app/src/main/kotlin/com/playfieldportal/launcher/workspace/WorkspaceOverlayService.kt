package com.playfieldportal.launcher.workspace

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** User-enabled, visible overlay. No accessibility service, screen capture or app-content scraping. */
class WorkspaceOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    private val savedState = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()
    private lateinit var manager: WindowManager
    private lateinit var windowContext: Context
    private var bubble: TextView? = null
    private var panel: ComposeView? = null
    private val preferences get() = getSharedPreferences("arcdeck_workspace", MODE_PRIVATE)
    override fun onCreate() {
        super.onCreate(); savedState.performAttach(); savedState.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        val display = getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val displayContext = createDisplayContext(display)
        windowContext = if (Build.VERSION.SDK_INT >= 30) displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else displayContext
        manager = windowContext.getSystemService(WindowManager::class.java)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP || !Settings.canDrawOverlays(this)) {
            preferences.edit().putBoolean("overlay_enabled", false).apply(); stopSelf(); return START_NOT_STICKY
        }
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Floating workspace", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 451, Intent(this, com.playfieldportal.launcher.MainActivity::class.java)
            .putExtra("arcdeck_open_workspace", true).putExtra("arcdeck_tool", "SPACES"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 452, Intent(this, WorkspaceOverlayService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_dialer)
            .setContentTitle("ArcDeck floating workspace").setContentText("Tap WS over any app to access your spaces")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(451, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(451, notification)
        runCatching { if (bubble == null) showBubble() }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun showBubble() {
        val params = WindowManager.LayoutParams(dp(54), dp(54), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences.getInt("overlay_x", resources.displayMetrics.widthPixels - dp(62)).coerceIn(0, (resources.displayMetrics.widthPixels - dp(54)).coerceAtLeast(0))
            y = preferences.getInt("overlay_y", dp(150)).coerceIn(0, (resources.displayMetrics.heightPixels - dp(80)).coerceAtLeast(0))
        }
        val view = TextView(windowContext).apply {
            text = "WS"; textSize = 16f; gravity = Gravity.CENTER; setTextColor(android.graphics.Color.rgb(202, 232, 255))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xF2152C4B.toInt()); cornerRadius = dp(27).toFloat(); setStroke(dp(1), 0xFF75BEFF.toInt()) }
            contentDescription = "ArcDeck floating workspace"
        }
        view.setOnClickListener { if (panel == null) showPanel() else hidePanel() }
        var downX = 0f; var downY = 0f; var originX = 0; var originY = 0
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; originX = params.x; originY = params.y; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (originX + event.rawX - downX).toInt().coerceIn(0, (resources.displayMetrics.widthPixels - dp(54)).coerceAtLeast(0))
                    params.y = (originY + event.rawY - downY).toInt().coerceIn(0, (resources.displayMetrics.heightPixels - dp(80)).coerceAtLeast(0))
                    runCatching { manager.updateViewLayout(view, params) }; true
                }
                MotionEvent.ACTION_UP -> {
                    preferences.edit().putInt("overlay_x", params.x).putInt("overlay_y", params.y).apply()
                    if (abs(event.rawX - downX) + abs(event.rawY - downY) < dp(10)) view.performClick()
                    true
                }
                else -> false
            }
        }
        manager.addView(view, params); bubble = view
    }
    private fun showPanel() {
        val width = (resources.displayMetrics.widthPixels - dp(20)).coerceAtMost(dp(560))
        val height = (resources.displayMetrics.heightPixels * 0.76f).toInt().coerceAtLeast(dp(200))
        val params = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.CENTER; softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        val view = ComposeView(windowContext).apply {
            setViewTreeLifecycleOwner(this@WorkspaceOverlayService)
            setViewTreeSavedStateRegistryOwner(this@WorkspaceOverlayService)
            setViewTreeViewModelStoreOwner(this@WorkspaceOverlayService)
            setContent { FloatingSpacePanel({ hidePanel() }, { stopSelf(); preferences.edit().putBoolean("overlay_enabled", false).apply() }) }
        }
        try { manager.addView(view, params); panel = view } catch (e: Exception) { view.disposeComposition(); throw e }
    }
    private fun hidePanel() { panel?.let { view -> view.disposeComposition(); runCatching { manager.removeView(view) } }; panel = null }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig); hidePanel()
        bubble?.let { runCatching { manager.removeView(it) } }; bubble = null
        runCatching { showBubble() }.onFailure { stopSelf() }
    }
    override fun onDestroy() {
        hidePanel(); bubble?.let { runCatching { manager.removeView(it) } }; bubble = null
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE); registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY); viewModelStore.clear()
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "arcdeck_floating_workspace"
        private const val STOP = "arcdeck.overlay.STOP"
        fun ensureEnabled(context: Context) {
            if (context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE).getBoolean("overlay_enabled", false)) {
                if (Settings.canDrawOverlays(context)) ContextCompat.startForegroundService(context, Intent(context, WorkspaceOverlayService::class.java))
                else stop(context)
            }
        }
        fun stop(context: Context) {
            context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE).edit().putBoolean("overlay_enabled", false).apply()
            context.stopService(Intent(context, WorkspaceOverlayService::class.java))
        }
    }
}

@Composable private fun FloatingSpacePanel(close: () -> Unit, stop: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { WorkspaceSpaces.shared(context) }; val catalog = remember { UnifiedCatalog.shared(context) }
    val revision by store.revision.collectAsState(); val catalogRevision by catalog.revision.collectAsState()
    val spaces = remember(revision) { store.all() }; val space = remember(revision) { store.active() }
    val appearance = rememberWorkspaceAppearance(); val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<CatalogEntry>>(emptyList()) }; var query by remember(space?.id) { mutableStateOf("") }
    var showingNotes by remember(space?.id) { mutableStateOf(false) }
    val keyboardVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    var status by remember { mutableStateOf("") }; var preview by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(space?.id, revision, catalogRevision, query) {
        workspaceResult { withContext(Dispatchers.IO) { space?.let { store.items(it, catalog, query) } ?: emptyList() } }
            .onSuccess { entries = it }.onFailure { status = it.message ?: "Could not open space" }
    }
    fun act(block: suspend () -> Unit) { scope.launch { workspaceResult { block() }.onFailure { status = it.message ?: "Action failed" } } }
    MaterialTheme(colorScheme = darkColorScheme(primary = appearance.accent, surface = appearance.surface, background = appearance.ink)) {
        Surface(Modifier.fillMaxSize().clip(RoundedCornerShape(appearance.corners.dp)).onPreviewKeyEvent {
            if (it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK && it.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP) { if (preview != null) preview = null else close(); true } else false
        }, color = appearance.surface) {
            Column(Modifier.padding(12.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Text(space?.name ?: "ArcDeck spaces", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = close) { Text("Minimize") }
                }
                if (preview != null) {
                    TextButton(onClick = { preview = null }) { Text("Back to space") }
                    Box(Modifier.weight(1f)) { WorkspacePreviewPane(preview) }
                } else {
                    if (!keyboardVisible) Row(Modifier.horizontalScroll(rememberScrollState())) {
                        spaces.forEach { item -> FilterChip(space?.id == item.id, { store.select(item.id) }, label = { Text(item.name) }) }
                    }
                    if (space == null) Text("Create an app space in ArcDeck, then use this panel over Discord, Brave, ChatGPT or any other app.")
                    else {
                        if (!keyboardVisible) Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(enabled = space.app.isNotBlank(), onClick = { act { WorkspaceApps.launch(context, space); close() } }) { Text("Open app") }
                            TextButton(onClick = { act { WorkspaceApps.copy(context, spaceContext(space.copy(notes = store.noteText(space)), entries)); status = "Copied notes and links" } }) { Text("Copy context") }
                            TextButton(onClick = { act { WorkspaceApps.shareText(context, spaceContext(space.copy(notes = store.noteText(space)), entries), space.app); close() } }) { Text("Share to app") }
                        }
                        Row {
                            FilterChip(!showingNotes, { showingNotes = false }, label = { Text("Items") })
                            FilterChip(showingNotes, { showingNotes = true }, label = { Text("Notes") })
                        }
                        if (showingNotes) SpaceNotesEditor(space, store, Modifier.weight(1f))
                        else {
                        OutlinedTextField(query, { query = it.take(512) }, label = { Text("Find in this space") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        LazyColumn(Modifier.weight(1f)) {
                            items(entries, key = { it.key }) { entry -> Row {
                                Text(entry.alias ?: entry.title, Modifier.weight(1f).clickable { act {
                                    require(!entry.missing) { "Reconnect this source in ArcDeck" }
                                    when (entry.kind) {
                                        "FILE" -> preview = entry.target
                                        "LINK" -> { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(entry.target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); close() }
                                        "APP" -> { val app = context.packageManager.getLaunchIntentForPackage(entry.target) ?: error("App not installed"); context.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); close() }
                                        "COMMAND" -> { WorkspaceApps.copy(context, entry.target); WorkspaceRequests.open(context, space.id, "TERMINAL"); close() }
                                        else -> { WorkspaceRequests.open(context, space.id); close() }
                                    }
                                } }.padding(10.dp))
                                if (entry.kind == "FILE" && !entry.missing) TextButton(onClick = { act {
                                    val doc = withContext(Dispatchers.IO) { WorkspaceDocuments(context).stat(android.net.Uri.parse(entry.target)) }
                                    WorkspaceApps.shareFiles(context, listOf(doc), space.app); close()
                                } }) { Text("Share") }
                            } }
                        }
                        }
                    }
                }
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { WorkspaceRequests.open(context, space?.id); close() }) { Text("Full workspace") }
                    TextButton(onClick = stop) { Text("Stop floating workspace") }
                }
            }
        }
    }
}
