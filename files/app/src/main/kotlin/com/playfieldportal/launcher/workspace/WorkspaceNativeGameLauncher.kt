package com.playfieldportal.launcher.workspace

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** Optional app-layer bridge. The launcher module has no dependency on this application module. */
@Keep
object WorkspaceNativeGameLauncher {
    private val pending = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
    @Keep @JvmStatic fun open(context: Context, source: Intent): CompletableFuture<Boolean>? {
        if (!WorkspaceNative.ready() || !context.getSharedPreferences("arcdeck_workspace", 0).getBoolean("native_game_windows", true)) return null
        val intent = Intent(source)
        // Intent URI transport supports scalar extras. Keep specialized parcelable/array launches on Android's original path.
        if (intent.extras?.keySet()?.any { key -> @Suppress("DEPRECATION") val value = intent.extras?.get(key)
                value != null && value !is String && value !is Boolean && value !is Byte && value !is Char && value !is Double && value !is Float && value !is Int && value !is Long && value !is Short } == true) return null
        val component = intent.component ?: intent.resolveActivity(context.packageManager) ?: return null
        if (component.packageName == context.packageName) return null
        val target = WorkspaceNative.defaultTarget(context, component.packageName) ?: return null
        // The original app grants its own ROM URIs. The privileged process never grants another app's data.
        val uris = buildList {
            intent.data?.takeIf { it.scheme == "content" }?.let(::add)
            intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.takeIf { it.scheme == "content" }?.let(::add) }
        }.distinct()
        uris.forEach { uri -> context.grantUriPermission(component.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        if (uris.any { WorkspaceStorage.owns(context, it) }) context.grantUriPermission(component.packageName,
            WorkspacePhoneStore.tree(context), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        intent.component = component
        intent.putExtra("dev.arcdeck.window_token", System.nanoTime())
        val serialized = intent.toUri(Intent.URI_INTENT_SCHEME)
        require(serialized.length <= 48000) { "Native game launch is too large" }
        val result = CompletableFuture<Boolean>(); pending[serialized] = result
        Handler(Looper.getMainLooper()).postDelayed({
            pending.remove(serialized)?.completeExceptionally(IllegalStateException("The native game window did not become ready. Reconnect Shizuku or open the app normally."))
        }, 25000)
        try {
            WorkspaceNativeRequests.open(context, component.packageName, target.user, serialized, component.flattenToString())
            WorkspaceRequests.open(context, tool = "NATIVE_PENDING")
        } catch (e: Exception) { pending.remove(serialized); result.completeExceptionally(e) }
        return result
    }
    internal fun complete(intent: String?, error: String? = null) {
        val result = intent?.let { pending.remove(it) } ?: return
        if (error == null) result.complete(true) else result.completeExceptionally(IllegalStateException(error))
    }
}
