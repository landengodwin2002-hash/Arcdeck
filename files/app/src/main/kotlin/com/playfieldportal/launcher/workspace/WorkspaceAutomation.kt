package com.playfieldportal.launcher.workspace

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.*
import android.content.*
import android.graphics.Path
import android.graphics.Rect
import android.os.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal data class WorkspaceControl(val serial: Long = 0, val action: String = "", val target: String = "")
internal object WorkspaceControls {
    private val state = MutableStateFlow<List<WorkspaceControl>>(emptyList())
    private var serial = 0L
    val requests = state.asStateFlow()
    @Synchronized fun post(action: String, target: String) { validate(action, target); require(state.value.size < 64) { "Window queue is full" }; state.value = state.value + WorkspaceControl(++serial, action, target) }
    @Synchronized fun acknowledge(ids: Set<Long>) { state.value = state.value.filterNot { it.serial in ids } }
    val tools = setOf("LIBRARY", "SEARCH", "APPS", "FILES", "SPACES", "TERMINAL", "NOTES", "SYSTEM", "PFP", "CUSTOMIZE", "CHATGPT", "DISCORD", "MEDIA", "CLIPBOARD", "GAMING", "AUTOMATION", "NATIVE", "AUTOLIB")
    fun validate(action: String, target: String) {
        require(action in setOf("open", "minimize", "close", "tile", "stop")) { "Unknown window action" }
        require(if (action == "tile") target in setOf("side", "grid", "cascade") else if (action == "stop") target == "all" else target in tools) { "Unknown window target" }
    }
}

internal object WorkspaceAutomation {
    const val PORT = 47831
    fun prefs(context: Context) = context.getSharedPreferences("arcdeck_automation", Context.MODE_PRIVATE)
    fun connected(context: Context) = WorkspaceAutomationService.running && prefs(context).getBoolean("enabled", false)
    fun token(context: Context): String {
        val prefs = prefs(context)
        return prefs.getString("token", null) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }.also { prefs.edit().putString("token", it).commit() }
    }
    fun allowed(context: Context, pkg: String): Boolean = pkg == context.packageName || pkg in prefs(context).getStringSet("apps", emptySet()).orEmpty()
    fun curl(context: Context): String = "curl --fail --silent --show-error -H 'Authorization: Bearer ${token(context)}' -H 'Content-Type: application/json' -d '{\"op\":\"window\",\"action\":\"open\",\"tool\":\"CHATGPT\"}' http://127.0.0.1:$PORT/action"
    fun dispatch(context: Context, body: JSONObject): JSONObject {
        check(connected(context)) { "Start the script connection in ArcDeck first" }
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        check(power.isInteractive && !keyguard.isKeyguardLocked) { "Unlock the phone before automating" }
        return when (body.getString("op")) {
            "window" -> {
                val action = body.getString("action"); val target = if (action == "tile") body.getString("layout") else if (action == "stop") "all" else body.getString("tool").uppercase(java.util.Locale.ROOT)
                WorkspaceControls.validate(action, target); WorkspaceControls.post(action, target)
                JSONObject().put("status", "queued")
            }
            "clipboard" -> {
                val text = body.getString("text"); require(text.length <= 30000) { "Text is limited to 30,000 characters" }
                WorkspaceApps.copy(context, text); JSONObject().put("status", "copied")
            }
            "app" -> {
                val pkg = body.getString("package"); require(allowed(context, pkg)) { "Allow this app in Automation first" }
                val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: error("App is not installed")
                (WorkspaceAccessibilityService.active ?: context).startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                JSONObject().put("status", "launch_requested")
            }
            "ui" -> {
                val service = WorkspaceAccessibilityService.active ?: error("Enable ArcDeck's optional accessibility control first")
                service.act(body); JSONObject().put("status", "action_requested")
            }
            else -> error("Unknown operation")
        }
    }
}

internal data class AutomationHttpRequest(val path: String, val json: JSONObject?)
internal object AutomationHttp {
    /** Bounded local HTTP subset: no browser origins, chunking, credentials in URLs or implicit trust. */
    fun read(input: java.io.InputStream, token: String): AutomationHttpRequest {
        fun line(): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) { val b = input.read(); require(b >= 0) { "Incomplete request" }; if (b == 10) break; require(bytes.size() < 8192); bytes.write(b) }
            return bytes.toString("US-ASCII").removeSuffix("\r")
        }
        val first = line().split(' '); require(first.size == 3 && first[2] == "HTTP/1.1")
        require(first[0] in setOf("POST", "GET") && first[1] in setOf("/action", "/status")) { "Unsupported endpoint" }
        val headers = linkedMapOf<String, String>()
        repeat(32) {
            val value = line()
            if (value.isEmpty()) {
                require("origin" !in headers && "transfer-encoding" !in headers) { "Browser and chunked requests are not supported" }
                val supplied = headers["authorization"].orEmpty().toByteArray(Charsets.US_ASCII)
                require(MessageDigest.isEqual(supplied, "Bearer $token".toByteArray(Charsets.US_ASCII))) { "Authentication failed" }
                if (first[0] == "GET") { require(first[1] == "/status"); return AutomationHttpRequest("/status", null) }
                require(first[1] == "/action" && headers["content-type"]?.substringBefore(';')?.trim() == "application/json")
                val size = headers["content-length"]?.toIntOrNull() ?: error("Content-Length is required")
                require(size in 1..32768) { "Request is too large" }
                val body = ByteArray(size); var offset = 0
                while (offset < size) { val count = input.read(body, offset, size - offset); require(count > 0) { "Incomplete body" }; offset += count }
                return AutomationHttpRequest("/action", JSONObject(body.toString(Charsets.UTF_8)))
            }
            val split = value.indexOf(':'); require(split > 0)
            val key = value.substring(0, split).lowercase(java.util.Locale.ROOT)
            require(key !in headers) { "Duplicate header" }; headers[key] = value.substring(split + 1).trim()
        }
        error("Too many headers")
    }
    fun reply(socket: Socket, status: Int, result: JSONObject) {
        val bytes = result.toString().toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write("HTTP/1.1 $status Result\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n".toByteArray(Charsets.US_ASCII)); write(bytes); flush()
        }
    }
}

class WorkspaceAutomationService : Service() {
    private var server: ServerSocket? = null
    private val main = Handler(Looper.getMainLooper())
    private val screenOff = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) { stopSelf() } }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        WorkspaceAutomation.prefs(this).edit().putBoolean("enabled", false).putString("last_error", "").apply()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("arcdeck_scripts", "ArcDeck script connection", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, WorkspaceAutomationService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(317, Notification.Builder(this, "arcdeck_scripts").setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("ArcDeck script connection is active").setContentText("Local Termux controls · tap Stop when finished")
            .setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build())
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF)) }
        try {
            server = ServerSocket(WorkspaceAutomation.PORT, 4, InetAddress.getByName("127.0.0.1"))
            running = true
            WorkspaceAutomation.prefs(this).edit().putBoolean("enabled", true).apply()
            Thread({
                while (server?.isClosed == false) {
                    val socket = try { server?.accept() ?: break } catch (_: Exception) { break }
                    socket.use { client ->
                        client.soTimeout = 3000
                        runCatching {
                            val request = AutomationHttp.read(client.getInputStream(), WorkspaceAutomation.token(this))
                            if (request.path == "/status") AutomationHttp.reply(client, 200, JSONObject().put("status", if (WorkspaceAutomation.connected(this)) "active" else "stopping").put("accessibility", WorkspaceAccessibilityService.active != null))
                            else {
                                val latch = CountDownLatch(1); val pending = AtomicBoolean(true); var result = JSONObject(); var code = 200
                                main.post {
                                    if (pending.compareAndSet(true, false)) try { result = WorkspaceAutomation.dispatch(this, requireNotNull(request.json)) }
                                    catch (e: Exception) { code = 409; result = JSONObject().put("error", e.message ?: "Action failed") }
                                    latch.countDown()
                                }
                                if (!latch.await(3, TimeUnit.SECONDS)) { pending.set(false); code = 503; result = JSONObject().put("error", "Launcher is busy; no queued action will be retried automatically") }
                                AutomationHttp.reply(client, code, result)
                            }
                        }.onFailure { runCatching { AutomationHttp.reply(client, 400, JSONObject().put("error", it.message ?: "Invalid request")) } }
                    }
                }
            }, "ArcDeck-local-script-connection").apply { isDaemon = true; start() }
        } catch (e: Exception) { WorkspaceAutomation.prefs(this).edit().putString("last_error", e.message ?: "Could not start script connection").apply(); stopSelf() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { if (intent?.action == "STOP") stopSelf(); return START_NOT_STICKY }
    override fun onDestroy() {
        running = false; runCatching { server?.close() }; server = null
        WorkspaceAutomation.prefs(this).edit().putBoolean("enabled", false).apply()
        if (getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE).getBoolean("pause_scripts", true)) TermuxBridge.pauseManaged(this)
        runCatching { unregisterReceiver(screenOff) }; super.onDestroy()
    }
    companion object { @Volatile internal var running = false }
}

class WorkspaceAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { active = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() { if (active === this) active = null; super.onDestroy() }
    internal fun act(body: JSONObject) {
        val target = body.getString("package")
        require(WorkspaceAutomation.allowed(this, target)) { "This app is not allowed" }
        val root = rootInActiveWindow ?: error("No active app window")
        require(root.packageName?.toString() == target) { "The allowed target app must be in front" }
        when (body.getString("action")) {
            "paste" -> {
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: error("Focus an editable field first")
                require(focused.isEditable && !focused.isPassword) { "A non-password editable field is required" }
                val text = body.getString("text"); require(text.length <= 30000)
                check(focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })) { "This app does not support setting the focused field" }
            }
            "tap", "swipe" -> {
                val bounds = Rect(); root.getBoundsInScreen(bounds)
                fun point(x: String, y: String): Pair<Float, Float> {
                    val a = body.getDouble(x).toFloat(); val b = body.getDouble(y).toFloat()
                    require(a.isFinite() && b.isFinite() && bounds.contains(a.toInt(), b.toInt())) { "Gesture is outside the target app" }; return a to b
                }
                val start = point("x", "y"); val path = Path().apply { moveTo(start.first, start.second) }
                val duration = if (body.getString("action") == "swipe") { val end = point("toX", "toY"); path.lineTo(end.first, end.second); 300L } else 70L
                check(dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(), null, null)) { "Gesture was rejected" }
            }
            "back" -> check(performGlobalAction(GLOBAL_ACTION_BACK)) { "Back was rejected" }
            else -> error("Unsupported UI action")
        }
    }
    companion object { @Volatile internal var active: WorkspaceAccessibilityService? = null }
}
