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
    const val MAX_CHARS = 20_000_000

    fun readBounded(reader: Reader): String {
        val out = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            require(out.length + count <= MAX_CHARS) { "Backup exceeds 20 MB" }
            out.append(buffer, 0, count)
        }
        return out.toString()
    }

    fun export(context: Context, catalog: UnifiedCatalog): String {
        WorkspaceSpaces.shared(context).flush()
        val prefs = context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE)
        val preferences = JSONObject().put("notes", prefs.getString("notes", ""))
            .put("panels", prefs.getString("panels", "[]"))
            .put("panel_units", prefs.getString("panel_units", "px"))
            .put("workspace_key", prefs.getInt("workspace_key", android.view.KeyEvent.KEYCODE_F10))
            .put("landscape_prompt", prefs.getBoolean("landscape_prompt", false))
            .put("appearance", prefs.getString("appearance", "{}"))
            .put("profiles", prefs.getString("profiles", "[]"))
            .put("spaces", prefs.getString("spaces", "[]"))
            .put("game_connections", prefs.getString("game_connections", "[]"))
            .put("active_space", prefs.getString("active_space", ""))
            .put("system_bars", prefs.getBoolean("system_bars", false))
            .put("auto_index", prefs.getBoolean("auto_index", true))
        return JSONObject().put("format", "arcdeck-workspace").put("version", 2)
            .put("catalog", JSONObject(catalog.exportUserLayer())).put("preferences", preferences).toString(2)
            .also { require(it.length <= MAX_CHARS) { "Backup exceeds 20 MB" } }
    }

    fun restore(context: Context, catalog: UnifiedCatalog, raw: String) {
        require(raw.length <= MAX_CHARS) { "Backup exceeds 20 MB" }
        val root = JSONObject(raw)
        if (root.optString("format") != "arcdeck-workspace") { catalog.importUserLayer(raw); return }
        require(root.getInt("version") in 1..2) { "Unknown workspace backup version" }
        val prefs = root.optJSONObject("preferences") ?: JSONObject()
        val current = context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE)
        val notes = prefs.optString("notes")
        val panels = prefs.optString("panels", "[]")
        require(notes.length <= 500_000) { "Notes are too large" }
        validatePanels(panels)
        val appearance = prefs.optString("appearance", current.getString("appearance", "{}")); WorkspaceAppearance.parse(appearance)
        val profiles = prefs.optString("profiles", current.getString("profiles", "[]")); WorkspaceProfiles.validate(profiles)
        val spaces = prefs.optString("spaces", current.getString("spaces", "[]")); val decodedSpaces = WorkspaceSpaces.decode(spaces)
        val active = prefs.optString("active_space", current.getString("active_space", ""))
        val connections = prefs.optString("game_connections", current.getString("game_connections", "[]"))
        WorkspaceGameConnections.decode(connections)
        require(active.isBlank() || decodedSpaces.any { it.id == active }) { "Unknown active space" }
        val shortcut = prefs.optInt("workspace_key", android.view.KeyEvent.KEYCODE_F10)
        require(shortcut in setOf(android.view.KeyEvent.KEYCODE_F10, android.view.KeyEvent.KEYCODE_BUTTON_SELECT,
            android.view.KeyEvent.KEYCODE_BUTTON_START, android.view.KeyEvent.KEYCODE_BUTTON_THUMBL,
            android.view.KeyEvent.KEYCODE_BUTTON_THUMBR)) { "Invalid workspace shortcut" }
        val units = prefs.optString("panel_units", "px")
        require(units in setOf("px", "dp"))
        catalog.importUserLayer(root.getJSONObject("catalog").toString())
        check(context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE).edit()
            .putString("notes", notes).putString("panels", panels).putString("panel_units", units)
            .putString("appearance", appearance).putString("profiles", profiles).putString("spaces", spaces).putString("active_space", active)
            .putString("game_connections", connections)
            .putBoolean("system_bars", prefs.optBoolean("system_bars", current.getBoolean("system_bars", false)))
            .putBoolean("auto_index", prefs.optBoolean("auto_index", current.getBoolean("auto_index", true)))
            .putInt("workspace_key", shortcut)
            .putBoolean("landscape_prompt", prefs.optBoolean("landscape_prompt", false)).putLong("restore_epoch", System.nanoTime()).commit()) {
            "Organization restored; workspace preferences could not be saved"
        }
        WorkspaceSpaces.shared(context).reload()
    }

    fun validatePanels(panels: String) {
        val windows = org.json.JSONArray(panels)
        require(windows.length() <= 32) { "Too many saved windows" }
        val ids = mutableSetOf<Int>()
        val tools = setOf("SEARCH", "LIBRARY", "COLLECTIONS", "FILES", "APPS", "TERMINAL", "NOTES", "SYSTEM", "SPACES", "CUSTOMIZE", "PFP", "PREVIEW")
        for (i in 0 until windows.length()) {
            val window = windows.getJSONObject(i)
            require(window.getInt("id") > 0 && ids.add(window.getInt("id"))) { "Invalid window identity" }
            require(window.getString("tool") in tools) { "Unknown workspace tool" }
            for (field in listOf("x", "y", "w", "h")) {
                val value = window.optDouble(field, if (field == "w") 390.0 else if (field == "h") 305.0 else 0.0)
                require(value.isFinite() && value in -100000.0..100000.0) { "Invalid window geometry" }
            }
        }
    }
}
