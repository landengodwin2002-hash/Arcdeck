package com.playfieldportal.launcher.workspace

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun workspaceFlag(key: String, default: Boolean): Boolean {
    val context = LocalContext.current; val prefs = remember { context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE) }
    var value by remember(key) { mutableStateOf(prefs.getBoolean(key, default)) }
    DisposableEffect(prefs, key) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, changed -> if (changed == key || changed == "restore_epoch") value = prefs.getBoolean(key, default) }
        prefs.registerOnSharedPreferenceChangeListener(listener); onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}

@Composable internal fun WorkspaceAndroidControls(onSystemUiChanged: () -> Unit) {
    val context = LocalContext.current; val prefs = remember { context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE) }
    val enabled = workspaceFlag("overlay_enabled", false); val bars = workspaceFlag("system_bars", false); val indexing = workspaceFlag("auto_index", true)
    var status by remember { mutableStateOf("") }
    fun startOverlay() {
        prefs.edit().putBoolean("overlay_enabled", true).apply()
        runCatching { WorkspaceOverlayService.ensureEnabled(context) }.onFailure { prefs.edit().putBoolean("overlay_enabled", false).apply(); status = it.message ?: "Could not start floating workspace" }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startOverlay() }
    fun permissionThenStart() {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startOverlay()
    }
    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(context)) permissionThenStart() else status = "Allow ArcDeck to display over other apps, then enable the floating workspace."
    }
    Text("Android integration", style = MaterialTheme.typography.titleMedium)
    TextButton(onClick = {
        if (enabled) WorkspaceOverlayService.stop(context)
        else if (Settings.canDrawOverlays(context)) permissionThenStart()
        else runCatching { overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
            .onFailure { status = "Open Android Settings → Apps → Special app access → Display over other apps" }
    }) { Text(if (enabled) "Stop floating workspace" else "Enable floating workspace over other apps") }
    Text("A movable WS button opens your app spaces over Discord, Brave, ChatGPT and other apps. Use Android Share → Save to ArcDeck to bring content back into a space.", style = MaterialTheme.typography.bodySmall)
    Row { Switch(bars, { prefs.edit().putBoolean("system_bars", it).apply(); onSystemUiChanged() }); Text("Show Android status and navigation bars", Modifier.weight(1f).padding(10.dp)) }
    Row { Switch(indexing, { prefs.edit().putBoolean("auto_index", it).apply() }); Text("Refresh connected folders on return", Modifier.weight(1f).padding(10.dp)) }
    listOf("Internet quick panel" to Settings.Panel.ACTION_INTERNET_CONNECTIVITY,
        "Volume quick panel" to Settings.Panel.ACTION_VOLUME, "Display and brightness" to Settings.ACTION_DISPLAY_SETTINGS,
        "Battery settings" to Settings.ACTION_BATTERY_SAVER_SETTINGS, "Notification settings" to Settings.ACTION_APP_NOTIFICATION_SETTINGS).forEach { (title, action) ->
        TextButton(onClick = { runCatching {
            context.startActivity(Intent(action).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }.onFailure { status = "This device does not offer that panel. Open Android settings instead." } }) { Text(title) }
    }
    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
}

@Composable internal fun WorkspaceAppActions(packageName: String) {
    val context = LocalContext.current; var shortcuts by remember(packageName) { mutableStateOf<List<ShortcutInfo>>(emptyList()) }
    var status by remember(packageName) { mutableStateOf("") }
    LaunchedEffect(packageName) {
        workspaceResult { withContext(Dispatchers.IO) {
            val launcher = context.getSystemService(LauncherApps::class.java)
            if (!launcher.hasShortcutHostPermission()) emptyList() else launcher.getShortcuts(LauncherApps.ShortcutQuery()
                .setPackage(packageName).setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED), Process.myUserHandle()) ?: emptyList()
        } }.onSuccess { shortcuts = it.filter { s -> s.isEnabled }.take(20) }.onFailure { status = "App shortcuts require ArcDeck to be your default Home app." }
    }
    TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }.onFailure { status = it.message ?: "Cannot open app info" } }) { Text("Android app info and permissions") }
    TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))) }.onFailure { status = it.message ?: "Cannot open Android uninstall" } }) { Text("Uninstall through Android…") }
    shortcuts.forEach { shortcut -> TextButton(onClick = { runCatching {
        context.getSystemService(LauncherApps::class.java).startShortcut(packageName, shortcut.id, null, null, Process.myUserHandle())
    }.onFailure { status = it.message ?: "Shortcut unavailable" } }) { Text(shortcut.shortLabel?.toString() ?: shortcut.id) } }
    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
}

@Composable internal fun WorkspacePfpPane(sections: List<Pair<String, String>>, open: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Original PFP", style = MaterialTheme.typography.titleMedium)
        Text("The original launcher stays running beneath the workspace. These shortcuts return to its real screens; your workspace windows remain saved.")
        TextButton(onClick = { open("original") }) { Text("Return to original PFP") }
        sections.forEach { (id, title) -> TextButton(onClick = { open("category:$id") }) { Text(title) } }
        listOf("Library manager" to "library", "PFP notifications and jobs" to "notifications", "Player status" to "player",
            "Original icon editor" to "icons", "Original XMB layout editor" to "layout", "Original color picker" to "colors").forEach { (title, id) ->
            TextButton(onClick = { open(id) }) { Text(title) }
        }
    }
}
