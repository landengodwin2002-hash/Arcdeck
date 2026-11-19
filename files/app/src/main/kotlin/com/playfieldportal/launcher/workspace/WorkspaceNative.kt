package com.playfieldportal.launcher.workspace

import android.content.*
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.*
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.Keep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

internal data class NativeAppTarget(val pkg: String, val component: String, val user: Int, val title: String) {
    val key get() = "$user:$pkg"
}
internal data class NativeWindowState(val display: Int = -1, val status: String = "Connect Shizuku to use the installed app", val attached: Boolean = false)

/** One private privileged service, direct native surfaces, and at most four app displays. No app cloning. */
internal object WorkspaceNative {
    private lateinit var app: Context
    private val work = Executors.newSingleThreadExecutor { task -> Thread(task, "ArcDeck-native-windows").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val connectionState = MutableStateFlow("Native windows are disconnected")
    val status = connectionState.asStateFlow()
    private val states = mutableMapOf<String, MutableStateFlow<NativeWindowState>>()
    private data class Slot(val target: NativeAppTarget, @Volatile var display: Int = -1, @Volatile var surface: Surface? = null,
        var width: Int = 0, var height: Int = 0, var density: Int = 240, @Volatile var generation: Long = 0, var intent: String? = null)
    private val slots = mutableMapOf<String, Slot>()
    @Volatile private var binder: IBinder? = null
    private var args: Shizuku.UserServiceArgs? = null
    private var initialized = false
    private var binding = false
    private val received = Shizuku.OnBinderReceivedListener { main.post { if (initialized && app.getSharedPreferences("arcdeck_workspace", 0).getBoolean("native_autoconnect", false) && authorized()) connect(app) else refresh() } }
    private val dead = Shizuku.OnBinderDeadListener { main.post { disconnected("Shizuku stopped. Start it again to resume native windows.") } }
    private val permission = Shizuku.OnRequestPermissionResultListener { request, granted ->
        if (request == 814) main.post { if (granted == PackageManager.PERMISSION_GRANTED) connect(app) else connectionState.value = "Shizuku access was not granted" }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service; binding = false
            runCatching { service?.linkToDeath({ main.post { disconnected("Native window service stopped; reconnect Shizuku") } }, 0) }
            connectionState.value = "Native app windows connected"
            app.getSharedPreferences("arcdeck_workspace", 0).edit().putBoolean("native_ready", service != null).apply()
            synchronized(slots) { slots.values.forEach { it.display = -1 } }
            states.forEach { (_, state) -> state.value = NativeWindowState(status = "Ready · reopen or reload the native window") }
            WorkspaceAutoLibrary.kickoff(app, true)
        }
        override fun onServiceDisconnected(name: ComponentName?) { disconnected("Native window service disconnected") }
    }
    fun initialize(context: Context) {
        if (initialized) return
        app = context.applicationContext; initialized = true
        app.getSharedPreferences("arcdeck_workspace", 0).edit().putBoolean("native_ready", false).apply()
        Shizuku.addBinderReceivedListenerSticky(received); Shizuku.addBinderDeadListener(dead); Shizuku.addRequestPermissionResultListener(permission)
        refresh()
        if (app.getSharedPreferences("arcdeck_workspace", 0).getBoolean("native_autoconnect", false) && authorized()) connect(app)
    }
    fun installed(context: Context) = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api") != null
    fun authorized(): Boolean = runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    fun ready(): Boolean = binder?.pingBinder() == true && authorized()
    fun refresh() {
        connectionState.value = when {
            ready() -> "Native app windows connected"
            !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> "Start Shizuku on this phone to enable native app windows"
            !authorized() -> "Allow ArcDeck in Shizuku to connect installed apps"
            else -> "Shizuku is available · tap Connect native windows"
        }
    }
    fun connect(context: Context) {
        initialize(context)
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) { refresh(); return }
        if (!authorized()) { Shizuku.requestPermission(814); return }
        if (ready() || binding) return
        app.getSharedPreferences("arcdeck_workspace", 0).edit().putBoolean("native_autoconnect", true).apply()
        args = Shizuku.UserServiceArgs(ComponentName(app.packageName, ArcDeckNativeService::class.java.name))
            .tag("arcdeck-native-windows").daemon(false).processNameSuffix("native_windows").debuggable(false).version(14000)
        connectionState.value = "Connecting native window service…"
        binding = true
        try { Shizuku.bindUserService(requireNotNull(args), connection) }
        catch (e: Exception) { binding = false; disconnected(e.message ?: "Cannot connect native windows") }
    }
    private fun disconnected(message: String) {
        binder = null; binding = false; connectionState.value = message
        if (initialized) app.getSharedPreferences("arcdeck_workspace", 0).edit().putBoolean("native_ready", false).apply()
        synchronized(slots) { slots.values.forEach { it.display = -1 } }
        states.forEach { (_, state) -> state.value = NativeWindowState(status = message) }
    }
    fun disconnect() {
        if (!initialized) return
        app.getSharedPreferences("arcdeck_workspace", 0).edit().putBoolean("native_autoconnect", false).apply()
        args?.let { runCatching { Shizuku.unbindUserService(it, connection, true) } }; args = null
        synchronized(slots) { slots.clear() }; disconnected("Native windows disconnected")
    }
    fun state(key: String) = states.getOrPut(key) { MutableStateFlow(NativeWindowState(status = status.value)) }.asStateFlow()
    fun targets(context: Context, pkg: String? = null): List<NativeAppTarget> {
        val launcher = context.getSystemService(LauncherApps::class.java)
        return launcher.profiles.flatMap { user -> runCatching {
            launcher.getActivityList(pkg, user).map { activity -> NativeAppTarget(activity.componentName.packageName,
                activity.componentName.flattenToString(), user.hashCode(), activity.label.toString()) }
        }.getOrDefault(emptyList()) }.distinctBy { it.key }.sortedBy { it.title.lowercase(java.util.Locale.ROOT) }
    }
    fun defaultTarget(context: Context, pkg: String): NativeAppTarget? = targets(context, pkg).let { list ->
        list.firstOrNull { it.user == android.os.Process.myUserHandle().hashCode() } ?: list.firstOrNull()
    }
    fun attach(key: String, target: NativeAppTarget, surface: Surface, width: Int, height: Int, density: Int, launch: String? = null) {
        if (!initialized) return
        val slot = synchronized(slots) {
            slots.getOrPut(key) { Slot(target) }.also {
                it.surface = surface; it.width = width.coerceIn(128, 2560); it.height = height.coerceIn(128, 1560)
                it.density = density.coerceIn(120, 480); it.intent = launch ?: it.intent; it.generation++
            }
        }
        val epoch = slot.generation
        work.execute {
            if (!ready()) { update(key, NativeWindowState(status = status.value)); return@execute }
            if (slot.generation != epoch || slot.surface?.isValid != true) return@execute
            try {
                val request = JSONObject().put("op", if (slot.display < 0) "create" else "resize")
                    .put("package", target.pkg).put("component", target.component).put("user", target.user)
                    .put("width", slot.width).put("height", slot.height).put("density", slot.density)
                if (slot.display >= 0) request.put("display", slot.display)
                slot.intent?.let { request.put("intent", it) }
                val result = NativeProtocol.call(requireNotNull(binder), request, surface)
                slot.display = result.getInt("display")
                if (slot.generation != epoch || slot.surface == null) {
                    NativeProtocol.call(requireNotNull(binder), JSONObject().put("op", "pause").put("display", slot.display))
                } else { update(key, NativeWindowState(slot.display, "Installed app · Android profile ${target.user}", true)); WorkspaceNativeGameLauncher.complete(slot.intent) }
            } catch (e: Exception) { val error = e.cause?.message ?: e.message ?: "Native app window failed"; WorkspaceNativeGameLauncher.complete(slot.intent, error); update(key, NativeWindowState(status = error)) }
        }
    }
    private fun update(key: String, state: NativeWindowState) { main.post { states.getOrPut(key) { MutableStateFlow(state) }.value = state } }
    fun detach(key: String) {
        val slot = synchronized(slots) { slots[key]?.also { it.generation++; it.surface = null } } ?: return
        work.execute { if (ready() && slot.display > 0) runCatching { NativeProtocol.call(requireNotNull(binder), JSONObject().put("op", "pause").put("display", slot.display)) }
            update(key, NativeWindowState(slot.display, "Native app window suspended", false)) }
    }
    fun suspendAll() { synchronized(slots) { slots.keys.toList() }.forEach(::detach) }
    fun close(key: String) {
        val slot = synchronized(slots) { slots.remove(key)?.also { it.generation++; it.surface = null } } ?: return
        work.execute { if (ready() && slot.display > 0) runCatching { NativeProtocol.call(requireNotNull(binder), JSONObject().put("op", "close").put("display", slot.display)) }; update(key, NativeWindowState(status = "Native window closed")) }
    }
    fun touch(key: String, event: MotionEvent, viewWidth: Int, viewHeight: Int) {
        val slot = synchronized(slots) { slots[key] } ?: return
        if (!ready() || slot.display <= 0 || viewWidth < 1 || viewHeight < 1) return
        val copy = MotionEvent.obtainNoHistory(event)
        try { copy.transform(android.graphics.Matrix().apply { setScale(slot.width.toFloat() / viewWidth, slot.height.toFloat() / viewHeight) })
            runCatching { NativeProtocol.touch(requireNotNull(binder), slot.display, copy) }
        } finally { copy.recycle() }
    }
    fun key(key: String, code: Int) {
        val id = synchronized(slots) { slots[key]?.display } ?: return
        work.execute { runCatching { NativeProtocol.call(requireNotNull(binder), JSONObject().put("op", "key").put("display", id).put("key", code)) } }
    }
    fun files(request: JSONObject): JSONObject {
        check(ready()) { "Connect Shizuku to read this installed game app's external files" }
        return NativeProtocol.call(requireNotNull(binder), request)
    }
    fun openFile(path: String): ParcelFileDescriptor { check(ready()); return NativeProtocol.openFile(requireNotNull(binder), path) }
}

internal data class NativeOpenRequest(val serial: Long = 0, val target: NativeAppTarget? = null, val intent: String? = null)
internal object WorkspaceNativeRequests {
    private val changes = MutableStateFlow<List<NativeOpenRequest>>(emptyList())
    private val serial = java.util.concurrent.atomic.AtomicLong()
    val requests = changes.asStateFlow()
    @Synchronized fun open(context: Context, pkg: String, user: Int? = null, intent: String? = null, component: String? = null) {
        val target = WorkspaceNative.targets(context, pkg).firstOrNull { user == null || it.user == user }
            ?: error("This app is not available in an accessible Android profile")
        require(changes.value.size < 32) { "Wait for pending app windows to open" }
        changes.value = changes.value + NativeOpenRequest(serial.incrementAndGet(), if (component == null) target else target.copy(component = NativeProtocol.component(component, pkg).flattenToString()), intent)
    }
    @Synchronized fun acknowledge(serials: Set<Long>) { changes.value = changes.value.filterNot { it.serial in serials } }
}
