package com.playfieldportal.launcher.workspace

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.graphics.SurfaceTexture
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun WorkspaceNativeSetup() {
    val context = LocalContext.current
    val status by WorkspaceNative.status.collectAsState()
    Text(status, style = MaterialTheme.typography.bodySmall)
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        TextButton(onClick = { runCatching { WorkspaceNative.connect(context) } }) { Text("Connect native windows") }
        TextButton(onClick = { context.startActivity(context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))) }) { Text("Open / install Shizuku") }
    }
    Text("Start Shizuku with Android wireless debugging, then allow ArcDeck. Native windows use the installed app and its existing account; no app copies are made. Shizuku normally needs restarting after a phone reboot.", style = MaterialTheme.typography.bodySmall)
    if (WorkspaceNative.ready()) TextButton(onClick = { WorkspaceNative.disconnect() }) { Text("Disconnect native windows") }
}

@Composable internal fun WorkspaceNativePane(key: String, initialPackage: String, initialUser: Int = -1,
    launch: String? = null, focused: Boolean = false, showToolbar: Boolean = true,
    profileRequest: Int = 0, reloadRequest: Int = 0, appScale: Float = 1f, phoneIme: Boolean = true,
    onProfileRequestConsumed: () -> Unit = {}, onSelect: (NativeAppTarget) -> Unit = {}) {
    val context = LocalContext.current
    remember { WorkspaceNative.initialize(context); true }
    val status by WorkspaceNative.status.collectAsState()
    val state by WorkspaceNative.state(key).collectAsState()
    var target by remember(initialPackage, initialUser) { mutableStateOf<NativeAppTarget?>(null) }
    var picker by remember { mutableStateOf(false) }; var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(profileRequest) { if (profileRequest > 0) { picker = true; onProfileRequestConsumed() } }
    LaunchedEffect(initialPackage, initialUser, launch) {
        target = withContext(Dispatchers.IO) { if (initialPackage.isBlank()) null else WorkspaceNative.targets(context, initialPackage)
            .firstOrNull { initialUser < 0 || it.user == initialUser }?.let { selected ->
                if (launch == null) selected else selected.copy(component = requireNotNull(Intent.parseUri(launch, Intent.URI_INTENT_SCHEME).component).flattenToString()) } }
    }
    BackHandler(enabled = focused && WorkspaceNative.ready() && target != null) { WorkspaceNative.key(key, KeyEvent.KEYCODE_BACK) }
    Column(Modifier.fillMaxSize()) {
        if (!WorkspaceNative.ready()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                WorkspaceNativeSetup()
                target?.let { app -> TextButton(onClick = { runCatching { context.getSystemService(android.content.pm.LauncherApps::class.java)
                    .startMainActivity(android.content.ComponentName.unflattenFromString(app.component), android.os.UserHandle.getUserHandleForUid(app.user * 100000), null, null) } }) { Text("Open installed app normally") } }
            }
        } else if (target == null) {
            Text("Choose an installed app and Android profile for this window.")
            TextButton(onClick = { picker = true }) { Text("Pick installed app / profile") }
        } else {
            if (showToolbar) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { WorkspaceNative.key(key, KeyEvent.KEYCODE_BACK) }) { Text("Back") }
                TextButton(onClick = { picker = true }) { Text("App / profile") }
                TextButton(onClick = { WorkspaceNative.close(key); reload++ }) { Text("Reload app window") }
                TextButton(onClick = { target?.let { app -> context.getSystemService(android.content.pm.LauncherApps::class.java)
                    .startMainActivity(android.content.ComponentName.unflattenFromString(app.component), android.os.UserHandle.getUserHandleForUid(app.user * 100000), null, null) } }) { Text("Open normally") }
            }
            val selected = requireNotNull(target)
            androidx.compose.runtime.key(selected.key, launch, reload, reloadRequest, status) {
                NativeSurface(key, selected, launch, appScale, phoneIme, Modifier.weight(1f).fillMaxWidth())
            }
            if (!state.attached) Text(state.status, style = MaterialTheme.typography.labelSmall, maxLines = 3)
        }
    }
    if (picker) {
        var query by remember { mutableStateOf("") }; var apps by remember { mutableStateOf<List<NativeAppTarget>>(emptyList()) }
        LaunchedEffect(query) { apps = withContext(Dispatchers.IO) { WorkspaceNative.targets(context).filter { (it.title + " " + it.pkg).contains(query, true) } } }
        WorkspaceDialog({ picker = false }, { Text("Installed apps and profiles") }, {
            Column(Modifier.heightIn(max = 300.dp)) {
                OutlinedTextField(query, { query = it.take(100) }, label = { Text("Find an installed app") }, singleLine = true)
                LazyColumn { items(apps, key = { it.key }) { app -> TextButton(onClick = {
                    WorkspaceNative.close(key); target = app; onSelect(app); picker = false
                }) { Text("${app.title} · Android profile ${app.user}") } } }
            }
        }, { TextButton(onClick = { picker = false }) { Text("Done") } })
    }
}

@Composable private fun NativeSurface(window: String, target: NativeAppTarget, launch: String?, appScale: Float, phoneIme: Boolean, modifier: Modifier) {
    val context = LocalContext.current; val owner = LocalLifecycleOwner.current
    var view by remember { mutableStateOf<TextureView?>(null) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    fun attach(width: Int, height: Int) {
        val current = surface ?: return
        if (current.isValid && width > 0 && height > 0) {
            val size = runCatching { NativeRenderSize.fit(width, height, context.resources.displayMetrics.densityDpi, appScale) }.getOrNull() ?: return
            view?.surfaceTexture?.setDefaultBufferSize(size.width, size.height)
            WorkspaceNative.attach(window, target, current, size.width, size.height, size.density, launch, phoneIme)
        }
    }
    val latestAttach by rememberUpdatedState<(Int, Int) -> Unit>({ width, height -> attach(width, height) })
    LaunchedEffect(appScale, phoneIme) { view?.let { attach(it.width, it.height) } }
    DisposableEffect(owner, window) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) view?.let { latestAttach(it.width, it.height) }
            if (event == Lifecycle.Event.ON_STOP) WorkspaceNative.detach(window)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); WorkspaceNative.detach(window); surface?.release(); surface = null; view = null }
    }
    AndroidView(factory = { TextureView(context).apply {
        view = this; isFocusableInTouchMode = true
        isOpaque = true
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                surface?.release(); surface = Surface(texture); latestAttach(width, height)
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) { latestAttach(width, height) }
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                WorkspaceNative.detach(window); surface?.release(); surface = null; return true
            }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
        setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) { parent?.requestDisallowInterceptTouchEvent(true); requestFocus() }
            WorkspaceNative.touch(window, event, width, height)
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP || event.actionMasked == android.view.MotionEvent.ACTION_CANCEL) parent?.requestDisallowInterceptTouchEvent(false)
            true
        }
        setOnKeyListener { _, code, event ->
            if (event.action == KeyEvent.ACTION_UP && code in setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_TAB)) { WorkspaceNative.key(window, code); true } else false
        }
    } }, modifier = modifier)
}
