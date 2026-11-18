package com.playfieldportal.launcher.workspace

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun WorkspaceNotesPane() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE) }
    var text by remember { mutableStateOf(prefs.getString("notes", "") ?: "") }
    var pending by remember { mutableStateOf<String?>(null) }
    fun save() { pending?.let { prefs.edit().putString("notes", it).apply() }; pending = null }
    LaunchedEffect(text) { if (pending != null) { delay(500); save() } }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) save() }
        owner.lifecycle.addObserver(observer)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "restore_epoch") { pending = null; text = prefs.getString("notes", "") ?: "" }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { pending?.let { prefs.edit().putString("notes", it).apply() }; prefs.unregisterOnSharedPreferenceChangeListener(listener); owner.lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(text, { if (it.length <= 500000) { text = it; pending = it } }, label = { Text("Workspace notes") },
            modifier = Modifier.fillMaxWidth().weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (pending == null) "Saved · ${text.length} characters" else "Saving…", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { save() }) { Text("Save") }
        }
    }
}
