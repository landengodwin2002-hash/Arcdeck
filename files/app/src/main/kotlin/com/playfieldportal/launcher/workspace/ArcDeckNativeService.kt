package com.playfieldportal.launcher.workspace

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.Keep
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Runs as the Shizuku-authorized shell user. It is reached only through its private Binder. */
@Keep
class ArcDeckNativeService(context: Context) : Binder() {
    private val ownerUid = context.applicationInfo.uid
    private val ownerPackage = context.packageName
    private val lock = Any()
    private data class Window(val pkg: String, val user: Int, val display: VirtualDisplay, var surface: Surface?, var phoneIme: Boolean)
    private val windows = mutableMapOf<Int, Window>()
    private val gamePackages = mutableSetOf<String>()
    private val directoryPages = linkedMapOf<String, Pair<Long, List<java.io.File>>>()
    private fun storageFile(raw: String): java.io.File {
        require(raw.length <= 4096)
        val file = java.io.File(raw).canonicalFile; val path = file.path
        val ownRoot = "/storage/emulated/${ownerUid / 100000}"
        val external = java.io.File("/storage").listFiles().orEmpty().filter { it.name.matches(Regex("[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}")) }.map { it.canonicalPath }
        require((listOf(ownRoot) + external).any { path == it || path.startsWith("$it/") }) { "Only this user's shared game storage is exposed" }
        if (path.contains("/Android/data/")) {
            val tail = path.substringAfter("/Android/data/"); val pkg = tail.substringBefore('/')
            require(pkg in gamePackages && (tail == "$pkg/files" || tail.startsWith("$pkg/files/"))) { "This app data directory is not a connected game source" }
        }
        require(!path.endsWith("/Android/data") && !path.endsWith("/Android/obb") && !path.contains("/Android/obb/")) { "Only connected game folders are exposed under Android" }
        return file
    }
    private fun fileJson(file: java.io.File) = JSONObject().put("path", file.path).put("name", file.name)
        .put("directory", file.isDirectory).put("size", if (file.isFile) file.length() else 0).put("modified", file.lastModified())
    private val shell: Context by lazy {
        val cls = Class.forName("android.app.ActivityThread")
        val thread = cls.getMethod("currentActivityThread").invoke(null) ?: cls.getMethod("systemMain").invoke(null)
        (cls.getMethod("getSystemContext").invoke(thread) as Context).createPackageContext("com.android.shell", 0)
    }
    private val displays get() = shell.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val input by lazy {
        val cls = runCatching { Class.forName("android.hardware.input.InputManagerGlobal") }.getOrElse { Class.forName("android.hardware.input.InputManager") }
        cls.getMethod("getInstance").invoke(null)
    }
    private fun flag(name: String) = runCatching { DisplayManager::class.java.getField(name).getInt(null) }.getOrDefault(0)
    private fun command(vararg arguments: String): String {
        val process = ProcessBuilder(arguments.toList()).redirectErrorStream(true).start()
        // am/wm output is bounded by the fixed command shape; read concurrently so a full pipe cannot deadlock waitFor.
        val bytes = java.io.ByteArrayOutputStream()
        val reader = Thread { runCatching { process.inputStream.use { stream -> val block = ByteArray(2048); while (true) { val n = stream.read(block); if (n < 0) break; if (bytes.size() < 24000) bytes.write(block, 0, minOf(n, 24000 - bytes.size())) } } } }.apply { isDaemon = true; start() }
        if (!process.waitFor(8, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Android did not finish the native app request") }
        reader.join(1000)
        val result = bytes.toString("UTF-8")
        check(process.exitValue() == 0 && !result.contains("Error:", true) && !result.contains("Exception", true)) { result.take(1500).ifBlank { "Android rejected the native app request" } }
        return result
    }
    private fun ime(id: Int, phone: Boolean) = runCatching {
        val sm = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "window") as IBinder
        val wm = Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, sm)
        // FALLBACK_DISPLAY=1 places the user's keyboard on the physical phone; LOCAL=0 is optional.
        if (android.os.Build.VERSION.SDK_INT >= 31) wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(wm, id, if (phone) 1 else 0)
        else wm.javaClass.getMethod("setShouldShowIme", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).invoke(wm, id, !phone)
    }
    private fun inject(event: InputEvent, id: Int) {
        require(id > 0 && windows.containsKey(id)) { "Input cannot fall back to the phone's main display" }
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, id)
        check(input.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType).invoke(input, event, 0) == true) { "Android rejected display-scoped input" }
    }
    @Suppress("DEPRECATION") private fun close(id: Int) {
        val window = windows.remove(id) ?: return
        // Finish only tasks in the display owned by this window; never force-stop a package as part of normal close.
        runCatching {
            val am = shell.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
            am.getRunningTasks(100).filter { displayOf(it) == id }.forEach { task ->
                runCatching { service.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType).invoke(service, task.taskId) }
            }
        }
        runCatching { window.display.release() }; runCatching { window.surface?.release() }
    }
    private fun displayOf(task: ActivityManager.RunningTaskInfo): Int = task.javaClass.getField("displayId").getInt(task)
    @Suppress("DEPRECATION") private fun verifyLaunch(id: Int, pkg: String) {
        val am = shell.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        repeat(24) {
            if (am.getRunningTasks(100).any { task -> displayOf(task) == id &&
                    (task.baseActivity?.packageName == pkg || task.topActivity?.packageName == pkg) }) return
            Thread.sleep(100)
        }
        error("This app did not open on its ArcDeck display. Android or the app may block secondary displays; use Open normally if needed.")
    }
    private fun create(request: JSONObject, surface: Surface?): JSONObject {
        require(surface?.isValid == true) { "The app window has no live surface" }
        require(windows.size < NativeProtocol.MAX_WINDOWS) { "Close a native window before opening more than four" }
        val pkg = NativeProtocol.windowPackage(request.getString("package"), ownerPackage); val component = NativeProtocol.component(request.getString("component"), pkg)
        val user = NativeProtocol.user(request.getInt("user")); val width = request.getInt("width"); val height = request.getInt("height"); val dpi = request.getInt("density")
        NativeProtocol.dimensions(width, height, dpi)
        val base = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        val touch = flag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH"); val focus = flag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS"); val trusted = flag("VIRTUAL_DISPLAY_FLAG_TRUSTED")
        val destroy = flag("VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL")
        var display: VirtualDisplay? = null; var failure: Throwable? = null
        for (flags in listOf(base or touch or focus or trusted or destroy, base or touch or trusted or destroy, base or touch or destroy).distinct()) {
            try { display = displays.createVirtualDisplay("ArcDeck-${windows.size + 1}", width, height, dpi, surface, flags); if (display != null) break }
            catch (e: Throwable) { failure = e }
        }
        val virtual = display ?: throw IllegalStateException("Native display is unsupported on this Android build", failure)
        val id = virtual.display.displayId
        if (id <= 0) { virtual.release(); error("Android returned its main display instead of a native window") }
        val phoneIme = request.optBoolean("phone_ime", true)
        windows[id] = Window(pkg, user, virtual, surface, phoneIme)
        try {
            ime(id, phoneIme)
            val launch = request.optString("intent").takeIf { it.isNotBlank() }
            val args = mutableListOf("/system/bin/am", "start", "--user", "$user", "--display", "$id", "-f", "0x18000000")
            if (launch == null) args.addAll(listOf("-n", component.flattenToString()))
            else {
                require(launch.length <= 48000)
                val intent = Intent.parseUri(launch, Intent.URI_INTENT_SCHEME)
                require(intent.component == component && intent.selector == null) { "Launch target does not match the native app" }
                intent.flags = intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION).inv()
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                args.add(intent.toUri(Intent.URI_INTENT_SCHEME))
            }
            val output = command(*args.toTypedArray())
            verifyLaunch(id, pkg)
            return JSONObject().put("display", id).put("status", "connected").put("message", output.take(1000))
        } catch (e: Throwable) { close(id); throw e }
    }
    private fun dispatch(request: JSONObject, surface: Surface?): JSONObject = synchronized(lock) {
        when (request.getString("op")) {
            "create" -> create(request, surface)
            "resize" -> {
                val id = request.getInt("display"); val window = windows[id] ?: error("Native display is closed")
                val w = request.getInt("width"); val h = request.getInt("height"); val density = request.getInt("density")
                NativeProtocol.dimensions(w, h, density); require(surface?.isValid == true)
                val phoneIme = request.optBoolean("phone_ime", true)
                if (window.phoneIme != phoneIme) { ime(id, phoneIme); window.phoneIme = phoneIme }
                window.display.resize(w, h, density); window.display.surface = surface
                if (window.surface !== surface) window.surface?.release(); window.surface = surface
                JSONObject().put("display", id).put("status", "connected")
            }
            "pause" -> { val window = windows[request.getInt("display")]; window?.display?.surface = null; window?.surface?.release(); if (window != null) window.surface = null; JSONObject().put("status", "suspended") }
            "close" -> { close(request.getInt("display")); JSONObject().put("status", "closed") }
            "key" -> {
                val id = request.getInt("display"); val key = request.getInt("key")
                require(key in setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_TAB))
                val now = android.os.SystemClock.uptimeMillis()
                inject(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0), id); inject(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0), id)
                JSONObject().put("status", "input_requested")
            }
            "close_all" -> { windows.keys.toList().forEach(::close); JSONObject().put("status", "closed") }
            "status" -> JSONObject().put("windows", windows.size).put("uid", android.os.Process.myUid()).put("max", NativeProtocol.MAX_WINDOWS)
            "configure_games" -> {
                val packages = request.getJSONArray("packages"); require(packages.length() <= 200)
                gamePackages.clear(); directoryPages.clear(); for (i in 0 until packages.length()) gamePackages.add(NativeProtocol.packageName(packages.getString(i)))
                JSONObject().put("status", "connected")
            }
            "stat" -> { val file = storageFile(request.getString("path")); check(file.exists()) { "File no longer exists" }; fileJson(file) }
            "list" -> {
                val folder = storageFile(request.getString("path")); require(folder.isDirectory)
                val offset = request.optInt("offset", 0); require(offset in 0..20000)
                val now = android.os.SystemClock.elapsedRealtime()
                val allowed = if (offset == 0) {
                    val children = folder.listFiles() ?: error("Cannot read directory")
                    require(children.size <= 20000) { "Directory exceeds the provider limit; connect a smaller subfolder" }
                    val entries = children.sortedBy { it.name }.filter { runCatching { storageFile(it.path) }.isSuccess }
                    if (directoryPages.size >= 3) directoryPages.remove(directoryPages.keys.first())
                    directoryPages[folder.path] = now to entries; entries
                } else directoryPages[folder.path]?.takeIf { now - it.first < 60000 }?.second ?: error("Folder listing expired; retry this source")
                val out = org.json.JSONArray(); allowed.drop(offset).take(64).forEach { out.put(fileJson(it)) }
                if (offset + out.length() >= allowed.size) directoryPages.remove(folder.path)
                JSONObject().put("files", out).put("next", if (offset + out.length() < allowed.size) offset + out.length() else -1)
            }
            else -> error("Unsupported native operation")
        }
    }
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        val caller = getCallingUid()
        if (code == NativeProtocol.DESTROY && caller in setOf(ownerUid, 0, 2000)) {
            synchronized(lock) { windows.keys.toList().forEach(::close) }; reply?.writeNoException(); kotlin.system.exitProcess(0)
        }
        if (code == INTERFACE_TRANSACTION) { reply?.writeString(NativeProtocol.DESCRIPTOR); return true }
        if (code != NativeProtocol.CALL && code != NativeProtocol.TOUCH && code != NativeProtocol.OPEN_FILE) return super.onTransact(code, data, reply, flags)
        check(caller == ownerUid) { "This native Binder belongs to a different app" }
        data.enforceInterface(NativeProtocol.DESCRIPTOR)
        if (code == NativeProtocol.OPEN_FILE) {
            try {
                val file = synchronized(lock) { storageFile(requireNotNull(data.readString())) }
                require(file.isFile)
                val fd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                reply?.writeNoException(); fd.writeToParcel(requireNotNull(reply), android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
            } catch (e: Exception) { reply?.writeException(e) }
            return true
        }
        if (code == NativeProtocol.TOUCH) {
            val id = data.readInt(); val event = MotionEvent.CREATOR.createFromParcel(data)
            try { synchronized(lock) { runCatching { inject(event, id) } } } finally { event.recycle() }
            return true
        }
        val raw = requireNotNull(data.readString()); require(raw.length <= 64000)
        val surface = if (data.readInt() != 0) Surface.CREATOR.createFromParcel(data) else null
        val result = try { dispatch(JSONObject(raw), surface) }
        catch (e: Throwable) { surface?.release(); JSONObject().put("error", e.message ?: e.javaClass.simpleName) }
        reply?.writeNoException(); reply?.writeString(result.toString()); return true
    }
}
