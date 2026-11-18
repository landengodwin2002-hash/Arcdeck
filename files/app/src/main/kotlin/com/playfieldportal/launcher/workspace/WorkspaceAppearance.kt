package com.playfieldportal.launcher.workspace

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

internal data class WorkspaceAppearance(val palette: String = "Ice", val opacity: Float = 0.95f, val dim: Float = 0.4f,
    val corners: Float = 15f, val font: Float = 1f, val compact: Boolean = false, val maximizePhone: Boolean = true,
    val dockOrder: List<String> = emptyList(), val hidden: Set<String> = emptySet(), val customAccent: String = "") {
    val accent get() = if (customAccent.matches(Regex("#[A-Fa-f0-9]{6}"))) Color(0xFF000000 or customAccent.drop(1).toLong(16))
        else Color(when (palette) { "Violet" -> 0xFFCAA6FF; "Forest" -> 0xFF91DEB4; "Sunset" -> 0xFFFFBF8A; "Graphite" -> 0xFFD0D7E2; else -> 0xFF75BEFF })
    val ink get() = Color(when (palette) { "Violet" -> 0xFF241A3E; "Forest" -> 0xFF102D2A; "Sunset" -> 0xFF392325; "Graphite" -> 0xFF1F232B; else -> 0xFF11203D }).copy(alpha = opacity)
    val surface get() = Color(when (palette) { "Violet" -> 0xFF35284F; "Forest" -> 0xFF1B423B; "Sunset" -> 0xFF4F3334; "Graphite" -> 0xFF303640; else -> 0xFF1E3B60 }).copy(alpha = opacity)
    fun json(): String = JSONObject().put("palette", palette).put("opacity", (opacity * 1000).roundToInt() / 1000.0).put("dim", (dim * 1000).roundToInt() / 1000.0)
        .put("corners", (corners * 1000).roundToInt() / 1000.0).put("font", (font * 1000).roundToInt() / 1000.0).put("compact", compact).put("maximizePhone", maximizePhone)
        .put("dockOrder", JSONArray(dockOrder)).put("hidden", JSONArray(hidden.toList())).put("customAccent", customAccent).toString()
    companion object {
        val palettes = listOf("Ice", "Violet", "Forest", "Sunset", "Graphite")
        fun parse(raw: String): WorkspaceAppearance {
            require(raw.length <= 8000); val j = JSONObject(raw)
            fun number(key: String, default: Double, range: ClosedFloatingPointRange<Double>): Float {
                val v = j.optDouble(key, default); require(v.isFinite() && v in range) { "Invalid $key" }; return v.toFloat()
            }
            fun names(key: String): List<String> {
                val a = j.optJSONArray(key) ?: JSONArray(); require(a.length() <= 24)
                return (0 until a.length()).map { a.getString(it).also { n -> require(n.matches(Regex("[A-Z_]{1,24}"))) } }.distinct()
            }
            val palette = j.optString("palette", "Ice"); require(palette in palettes)
            val custom = j.optString("customAccent"); require(custom.isEmpty() || custom.matches(Regex("#[A-Fa-f0-9]{6}")))
            return WorkspaceAppearance(palette, number("opacity", 0.95, 0.65..1.0), number("dim", 0.4, 0.0..0.8),
                number("corners", 15.0, 0.0..28.0), number("font", 1.0, 0.85..1.35), j.optBoolean("compact"),
                j.optBoolean("maximizePhone", true), names("dockOrder"), names("hidden").filterNot { it in setOf("PFP", "CUSTOMIZE") }.toSet(), custom)
        }
    }
}
internal val LocalWorkspaceAppearance = staticCompositionLocalOf { WorkspaceAppearance() }

@Composable internal fun rememberWorkspaceAppearance(): WorkspaceAppearance {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE) }
    var style by remember { mutableStateOf(runCatching { WorkspaceAppearance.parse(prefs.getString("appearance", "{}")!!) }.getOrDefault(WorkspaceAppearance())) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in setOf("appearance", "restore_epoch")) style = runCatching { WorkspaceAppearance.parse(prefs.getString("appearance", "{}")!!) }.getOrDefault(WorkspaceAppearance())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return style
}

internal object WorkspaceProfiles {
    fun validate(raw: String) {
        require(raw.length <= 300_000); val a = JSONArray(raw); require(a.length() <= 20)
        val names = mutableSetOf<String>()
        for (i in 0 until a.length()) {
            val p = a.getJSONObject(i); val name = p.getString("name")
            require(name.isNotBlank() && name.length <= 60 && names.add(name.lowercase()))
            WorkspaceBackup.validatePanels(p.getString("panels")); WorkspaceAppearance.parse(p.getString("appearance"))
        }
    }
    fun save(context: Context, name: String) {
        require(name.trim().length in 1..60)
        val prefs = context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE)
        val old = JSONArray(prefs.getString("profiles", "[]")); val list = JSONArray()
        for (i in 0 until old.length()) if (!old.getJSONObject(i).getString("name").equals(name.trim(), true)) list.put(old.getJSONObject(i))
        list.put(JSONObject().put("name", name.trim()).put("panels", prefs.getString("panels", "[]"))
            .put("appearance", prefs.getString("appearance", "{}")))
        validate(list.toString()); prefs.edit().putString("profiles", list.toString()).apply()
    }
    fun load(context: Context, index: Int) {
        val prefs = context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE)
        val p = JSONArray(prefs.getString("profiles", "[]")).getJSONObject(index)
        WorkspaceBackup.validatePanels(p.getString("panels")); WorkspaceAppearance.parse(p.getString("appearance"))
        prefs.edit().putString("panels", p.getString("panels")).putString("panel_units", "dp")
            .putString("appearance", p.getString("appearance")).putLong("layout_epoch", System.nanoTime()).apply()
    }
    fun apply(context: Context, name: String): Boolean {
        if (name.isBlank()) return false
        val profiles = JSONArray(context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE).getString("profiles", "[]"))
        for (i in 0 until profiles.length()) if (profiles.getJSONObject(i).getString("name").equals(name, true)) { load(context, i); return true }
        return false
    }
    fun delete(context: Context, index: Int) {
        val prefs = context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE)
        val old = JSONArray(prefs.getString("profiles", "[]")); old.remove(index)
        prefs.edit().putString("profiles", old.toString()).apply()
    }
}

@Composable internal fun WorkspaceAppearancePane(tools: List<Pair<String, String>>, flushLayout: () -> Unit, onPfp: (String) -> Unit) {
    val context = LocalContext.current; val prefs = remember { context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE) }
    val style = rememberWorkspaceAppearance()
    var profileName by remember { mutableStateOf("") }; var revision by remember { mutableIntStateOf(0) }; var error by remember { mutableStateOf("") }
    var customAccent by remember(style.customAccent) { mutableStateOf(style.customAccent) }
    fun change(value: WorkspaceAppearance) { prefs.edit().putString("appearance", value.json()).apply() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Make this workspace yours", style = MaterialTheme.typography.titleMedium)
        WorkspaceAppearance.palettes.forEach { palette -> FilterChip(style.palette == palette, { change(style.copy(palette = palette, customAccent = "")) }, label = { Text(palette) }) }
        OutlinedTextField(customAccent, { value -> customAccent = value.take(7); if (value.isEmpty() || value.matches(Regex("#[A-Fa-f0-9]{6}"))) change(style.copy(customAccent = value)) },
            label = { Text("Custom accent #RRGGBB") }, singleLine = true)
        Text("Window opacity"); Slider(style.opacity, { change(style.copy(opacity = it)) }, valueRange = 0.65f..1f)
        Text("Background dim"); Slider(style.dim, { change(style.copy(dim = it)) }, valueRange = 0f.. 0.8f)
        Text("Corner roundness"); Slider(style.corners, { change(style.copy(corners = it)) }, valueRange = 0f..28f)
        Text("Text size"); Slider(style.font, { change(style.copy(font = it)) }, valueRange = 0.85f..1.35f)
        Row { Checkbox(style.compact, { change(style.copy(compact = it)) }); Text("Compact dock and controls", Modifier.padding(top = 12.dp)) }
        Row { Checkbox(style.maximizePhone, { change(style.copy(maximizePhone = it)) }); Text("Maximize new phone windows", Modifier.padding(top = 12.dp)) }
        Text("Dock order and visibility", style = MaterialTheme.typography.titleSmall)
        val ordered = tools.sortedBy { (style.dockOrder.indexOf(it.first).takeIf { n -> n >= 0 } ?: (100 + tools.indexOf(it))) }
        ordered.forEachIndexed { index, (id, label) -> Row(Modifier.fillMaxWidth()) {
            Checkbox(id !in style.hidden, { show -> change(style.copy(hidden = if (show) style.hidden - id else style.hidden + id)) }, enabled = id !in setOf("PFP", "CUSTOMIZE"))
            Text(label, Modifier.weight(1f).padding(top = 12.dp))
            TextButton(enabled = index > 0, onClick = { val ids = ordered.map { it.first }.toMutableList(); java.util.Collections.swap(ids, index, index - 1); change(style.copy(dockOrder = ids)) }) { Text("↑") }
            TextButton(enabled = index < ordered.lastIndex, onClick = { val ids = ordered.map { it.first }.toMutableList(); java.util.Collections.swap(ids, index, index + 1); change(style.copy(dockOrder = ids)) }) { Text("↓") }
        } }
        Text("Saved workspace layouts", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(profileName, { profileName = it.take(60) }, label = { Text("Layout name") }, singleLine = true)
        TextButton(enabled = profileName.isNotBlank(), onClick = { runCatching { flushLayout(); WorkspaceProfiles.save(context, profileName); revision++; profileName = "" }.onFailure { error = it.message ?: "Could not save layout" } }) { Text("Save current layout") }
        val profiles = remember(revision) { JSONArray(prefs.getString("profiles", "[]")) }
        for (i in 0 until profiles.length()) Row {
            TextButton(onClick = { WorkspaceProfiles.load(context, i) }) { Text(profiles.getJSONObject(i).getString("name")) }
            TextButton(onClick = { WorkspaceProfiles.delete(context, i); revision++ }) { Text("Remove") }
        }
        TextButton(onClick = { change(WorkspaceAppearance()) }) { Text("Reset workspace appearance") }
        TextButton(onClick = { onPfp("category:settings") }) { Text("Original PFP themes and settings") }
        TextButton(onClick = { onPfp("icons") }) { Text("Original PFP icon editor") }
        TextButton(onClick = { onPfp("layout") }) { Text("Original PFP XMB layout editor") }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}
