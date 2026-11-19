package com.playfieldportal.launcher.workspace

import android.content.Context
import org.json.JSONObject

/** Per-space arrangements are references and geometry; app data remains owned by the installed app. */
internal object WorkspaceSessions {
    private const val KEY = "space_sessions"
    fun active(context: Context): String = context.getSharedPreferences("arcdeck_workspace", 0).getString("active_space", "").orEmpty().ifBlank { "desktop" }
    fun save(context: Context, id: String, panels: String) {
        WorkspaceBackup.validatePanels(panels)
        val prefs = context.getSharedPreferences("arcdeck_workspace", 0)
        val all = JSONObject(prefs.getString(KEY, "{}"))
        val valid = WorkspaceSpaces.shared(context).all().map { it.id }.toSet() + "desktop"
        all.keys().asSequence().toList().filterNot { it in valid }.forEach(all::remove)
        if (id !in valid) return
        all.put(id, panels); validate(all.toString())
        prefs.edit().putString(KEY, all.toString()).apply()
    }
    fun read(context: Context, id: String): String? {
        val all = JSONObject(context.getSharedPreferences("arcdeck_workspace", 0).getString(KEY, "{}"))
        return if (all.has(id)) all.getString(id).also(WorkspaceBackup::validatePanels) else null
    }
    fun validate(raw: String) {
        require(raw.length <= 8_000_000) { "Workspace sessions are too large" }
        val all = JSONObject(raw); require(all.length() <= 201) { "Too many workspace sessions" }
        all.keys().forEach { key ->
            require(key.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid space identity" }
            WorkspaceBackup.validatePanels(all.getString(key))
        }
    }
}
