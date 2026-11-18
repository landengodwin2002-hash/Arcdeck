package com.playfieldportal.launcher.workspace

import android.content.Context
import java.io.Reader
import org.json.JSONObject
import kotlinx.coroutines.CancellationException

internal suspend fun <T> workspaceResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) { throw e }
catch (e: Exception) { Result.failure(e) }

internal object WorkspaceBackup {
    const val MAX_CHARS = 5_000_000

    fun readBounded(reader: Reader): String {
        val out = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            require(out.length + count <= MAX_CHARS) { "Backup exceeds 5 MB" }
            out.append(buffer, 0, count)
        }
        return out.toString()
    }

    fun export(context: Context, catalog: UnifiedCatalog): String {
        val prefs = context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE)
        val preferences = JSONObject().put("notes", prefs.getString("notes", ""))
            .put("panels", prefs.getString("panels", "[]"))
            .put("panel_units", prefs.getString("panel_units", "px"))
            .put("workspace_key", prefs.getInt("workspace_key", android.view.KeyEvent.KEYCODE_F10))
            .put("landscape_prompt", prefs.getBoolean("landscape_prompt", false))
        return JSONObject().put("format", "arcdeck-workspace").put("version", 1)
            .put("catalog", JSONObject(catalog.exportUserLayer())).put("preferences", preferences).toString(2)
            .also { require(it.length <= MAX_CHARS) { "Backup exceeds 5 MB" } }
    }

    fun restore(context: Context, catalog: UnifiedCatalog, raw: String) {
        require(raw.length <= MAX_CHARS) { "Backup exceeds 5 MB" }
        val root = JSONObject(raw)
        if (root.optString("format") != "arcdeck-workspace") { catalog.importUserLayer(raw); return }
        require(root.getInt("version") == 1) { "Unknown workspace backup version" }
        val prefs = root.optJSONObject("preferences") ?: JSONObject()
        val notes = prefs.optString("notes")
        val panels = prefs.optString("panels", "[]")
        require(notes.length <= 500_000) { "Notes are too large" }
        require(org.json.JSONArray(panels).length() <= 32) { "Too many saved windows" }
        val units = prefs.optString("panel_units", "px")
        require(units in setOf("px", "dp"))
        catalog.importUserLayer(root.getJSONObject("catalog").toString())
        check(context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE).edit()
            .putString("notes", notes).putString("panels", panels).putString("panel_units", units)
            .putInt("workspace_key", prefs.optInt("workspace_key", android.view.KeyEvent.KEYCODE_F10))
            .putBoolean("landscape_prompt", prefs.optBoolean("landscape_prompt", false)).putLong("restore_epoch", System.currentTimeMillis()).commit()) {
            "Organization restored; workspace preferences could not be saved"
        }
    }
}
