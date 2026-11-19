package com.playfieldportal.launcher.workspace

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

internal enum class WorkspaceWebsite(val title: String, val home: String, val app: String) {
    CHATGPT("ChatGPT", "https://chatgpt.com/", "com.openai.chatgpt"),
    DISCORD("Discord", "https://discord.com/app", "com.discord"),
    MEDIA("Media", "https://www.youtube.com/", "com.google.android.youtube")
}

/** RAM-only history; hidden pages are destroyed, not hidden behind a running JS timer. */
internal class WorkspaceWebHistory {
    private val states = mutableMapOf<String, Bundle>()
    private val epochs = mutableMapOf<String, MutableState<Int>>()
    fun version(key: String): State<Int> = epochs.getOrPut(key) { mutableStateOf(0) }
    fun invalidate(key: String) { epochs.getOrPut(key) { mutableStateOf(0) }.value++ }
    fun take(key: String): Bundle? = states.remove(key)
    fun save(key: String, state: Bundle) { states[key] = state }
    fun remove(key: String) { states.remove(key) }
    fun clear() { states.clear() }
}

internal object WorkspaceWebPolicy {
    private val live = mutableMapOf<WebView, () -> Unit>()
    private val held = mutableSetOf<WebView>()
    private val released = java.util.WeakHashMap<WebView, Boolean>()
    fun register(web: WebView, suspend: () -> Unit) { live[web] = suspend }
    fun hold(web: WebView, value: Boolean) { if (value) held.add(web) else held.remove(web) }
    fun suspendAll() { live.toMap().filterKeys { it !in held }.values.forEach { it() } }
    fun allowed(raw: String): Boolean = runCatching {
        val uri = Uri.parse(raw)
        raw.length <= 4096 && uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && (uri.port == -1 || uri.port == 443)
    }.getOrDefault(false)
    @Suppress("DEPRECATION") fun configure(settings: WebSettings) {
        settings.javaScriptEnabled = true; settings.domStorageEnabled = true
        settings.allowFileAccess = false; settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false; settings.allowUniversalAccessFromFileURLs = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.mediaPlaybackRequiresUserGesture = true
        settings.javaScriptCanOpenWindowsAutomatically = false; settings.setSupportMultipleWindows(false)
        settings.useWideViewPort = true; settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true; settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.safeBrowsingEnabled = true
    }
    fun release(web: WebView, save: ((Bundle) -> Unit)? = null) {
        if (released.put(web, true) == true) return
        live.remove(web); held.remove(web)
        if (save != null) runCatching {
            val state = Bundle(); web.saveState(state)
            val parcel = android.os.Parcel.obtain()
            try { parcel.writeBundle(state); if (parcel.dataSize() <= 512_000) save(state) } finally { parcel.recycle() }
        }
        (web.parent as? ViewGroup)?.removeView(web)
        runCatching { web.stopLoading(); web.onPause(); web.webChromeClient = null }
        runCatching { web.destroy() }
    }
}

@Composable internal fun WorkspaceWebPane(site: WorkspaceWebsite, history: WorkspaceWebHistory) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Sign in on the site's own page. If it rejects an embedded browser, use Open app/browser.") }
    var progress by remember { mutableIntStateOf(0) }; var generation by remember { mutableIntStateOf(0) }
    var options by remember { mutableStateOf(false) }; var broken by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(100f) }; var desktop by remember { mutableStateOf(site == WorkspaceWebsite.DISCORD) }
    var address by remember { mutableStateOf(site.home) }
    var upload by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var uploadWeb by remember { mutableStateOf<WebView?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        upload?.onReceiveValue(uris.filter { it.scheme == "content" }.take(16).toTypedArray()); upload = null
        uploadWeb?.let { WorkspaceWebPolicy.hold(it, false) }; uploadWeb = null
    }
    val epoch by history.version(site.name)
    val web = remember(site, generation, epoch) { WebView(context).apply {
        WorkspaceWebPolicy.configure(settings)
        // Desktop layout is a normal display preference; it does not bypass a site's authentication rules.
        if (desktop) settings.userAgentString = settings.userAgentString.replace(" Mobile", "")
        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (WorkspaceWebPolicy.allowed(request.url.toString())) return false
                status = "This link requires an external app. Use Open app/browser."; return true
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { progress = 0 }
            override fun onPageFinished(view: WebView, url: String?) { progress = 100; if (url != null) address = url }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                status = "Android released this web renderer. Reload to resume."; broken = true; WorkspaceWebPolicy.release(view); return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, value: Int) { progress = value }
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                request.deny(); status = "Use the native app for microphone/camera calls. Keyboard voice typing works in text fields."
            }
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                upload?.onReceiveValue(null); upload = callback; uploadWeb = view; WorkspaceWebPolicy.hold(view, true)
                return try { picker.launch(params.acceptTypes.filter { it.contains('/') }.toTypedArray().ifEmpty { arrayOf("*/*") }); true }
                catch (_: Exception) { callback.onReceiveValue(null); upload = null; WorkspaceWebPolicy.hold(view, false); uploadWeb = null; false }
            }
        }
        val saved = history.take(site.name)
        if (saved == null || restoreState(saved) == null) loadUrl(site.home)
        WorkspaceWebPolicy.register(this) { WorkspaceWebPolicy.release(this) { history.save(site.name, it) }; history.invalidate(site.name) }
    } }
    DisposableEffect(web) { onDispose {
        upload?.onReceiveValue(null); upload = null
        WorkspaceWebPolicy.release(web) { history.save(site.name, it) }
    } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = { if (web.canGoBack()) web.goBack() }) { Text("Back") }
            Text(Uri.parse(address).host.orEmpty(), modifier = Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.labelSmall)
            Box {
            TextButton(onClick = { options = true }) { Text("⋮") }
            DropdownMenu(options, { options = false }) {
            TextButton(onClick = { if (broken) { broken = false; generation++ } else web.reload(); options = false }) { Text("Reload") }
            TextButton(onClick = { web.loadUrl(site.home) }) { Text("Home") }
            TextButton(onClick = { runCatching {
                context.startActivity(context.packageManager.getLaunchIntentForPackage(site.app) ?: Intent(Intent.ACTION_VIEW, Uri.parse(site.home)))
            }.onFailure { status = it.message ?: "No external app is available" } }) { Text("Open app/browser") }
            TextButton(onClick = { WorkspaceApps.copy(context, address); status = "Page address copied" }) { Text("Copy URL") }
            TextButton(onClick = { desktop = !desktop; history.remove(site.name); generation++ }) { Text(if (desktop) "Mobile layout" else "Desktop layout") }
            Text("Text ${zoom.toInt()}%", Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
            Slider(zoom, { zoom = it; web.settings.textZoom = it.toInt() }, Modifier.width(240.dp), valueRange = 70f..160f)
            Text(status, Modifier.width(260.dp).padding(8.dp), style = MaterialTheme.typography.labelSmall)
            }
            }
        }
        if (progress in 1..99) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!broken) AndroidView(factory = { web }, modifier = Modifier.weight(1f).fillMaxWidth())
        else Text(status, Modifier.weight(1f))
    }
}
