package com.playfieldportal.launcher.workspace

import android.content.Intent
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

@Composable internal fun WorkspaceAutomationPane(catalog: UnifiedCatalog) {
    val context = LocalContext.current; val prefs = remember { WorkspaceAutomation.prefs(context) }
    var enabled by remember { mutableStateOf(WorkspaceAutomation.connected(context)) }
    var error by remember { mutableStateOf("") }; var filter by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<CatalogEntry>>(emptyList()) }
    var allowed by remember { mutableStateOf(prefs.getStringSet("apps", emptySet()).orEmpty().toSet()) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> enabled = WorkspaceAutomation.connected(context); if (key == "last_error") error = prefs.getString("last_error", "") ?: "" }
        prefs.registerOnSharedPreferenceChangeListener(listener); onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(filter) { apps = withContext(Dispatchers.IO) { catalog.search("type:app", 500).filter { (it.title + " " + it.target).contains(filter, true) } } }
    fun start() { runCatching { context.startForegroundService(Intent(context, WorkspaceAutomationService::class.java)) }.onFailure { error = it.message ?: "Could not start script connection" } }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Termux → ArcDeck → apps", style = MaterialTheme.typography.titleMedium)
        Text("Start a local script connection when needed. It stops when the screen turns off. Nothing reads app messages or sends actions until your script requests them.")
        Button(onClick = {
            if (enabled) { prefs.edit().putBoolean("enabled", false).apply(); context.stopService(Intent(context, WorkspaceAutomationService::class.java)) }
            else if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            else start()
        }) { Text(if (enabled) "Stop script connection" else "Start script connection") }
        TextButton(onClick = { WorkspaceApps.copy(context, WorkspaceAutomation.curl(context)); error = "Connection example copied. Treat its token as private; paste it into your Termux script." }) { Text("Copy Termux connection example") }
        TextButton(onClick = { prefs.edit().putBoolean("enabled", false).remove("token").apply(); context.stopService(Intent(context, WorkspaceAutomationService::class.java)); error = "Old connection token revoked. Copy a new example before restarting." }) { Text("Revoke connection token") }
        Text("Window actions work inside ArcDeck. UI tap/swipe/paste needs the optional Android accessibility service and an allowed target app in front. Paste replaces the focused non-password field; it does not press Send.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Optional Android accessibility control") }
        OutlinedTextField(filter, { filter = it.take(100) }, label = { Text("Find apps to allow") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        apps.forEach { app -> Row(Modifier.fillMaxWidth()) {
            Checkbox(app.target in allowed, { checked -> allowed = if (checked) allowed + app.target else allowed - app.target; prefs.edit().putStringSet("apps", allowed).apply() })
            Text(app.title, Modifier.padding(top = 12.dp))
        } }
        if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall)
    }
}
