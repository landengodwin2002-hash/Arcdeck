package com.playfieldportal.launcher.workspace

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

object WorkspaceInputFocus { @Volatile var editing = false }

@Composable fun PfpWorkspaceHost(content: @Composable () -> Unit) {
    val bar = workspaceFlag("command_bar", true)
    Box(Modifier.fillMaxSize().padding(top = if (bar) 112.dp else 0.dp)) { content() }
}

@Composable internal fun WorkspaceCommandBar(onSearch: (String) -> Unit, onTerminal: (String) -> Unit, onTool: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("arcdeck_terminal", 0) }
    var command by remember { mutableStateOf(prefs.getString("bar_draft", "") ?: "") }
    var review by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    val latest by rememberUpdatedState(command)
    DisposableEffect(Unit) { onDispose { WorkspaceInputFocus.editing = false; prefs.edit().putString("bar_draft", latest).apply() } }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        status = if (it) "Permission granted. Review and run your command." else "Allow the Termux command permission in Android app settings."
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(command, { command = it.take(32768) }, placeholder = { Text("Search or paste a Termux command") },
                modifier = Modifier.weight(1f).onFocusChanged { WorkspaceInputFocus.editing = it.isFocused }, singleLine = true)
            WorkspaceVoiceButton { command = (command + " " + it).take(32768) }
            TextButton(enabled = command.isNotBlank(), onClick = { onSearch(command) }) { Text("Find") }
            TextButton(enabled = command.isNotBlank(), onClick = { review = command }) { Text("Run") }
            TextButton(onClick = { onTool("TERMINAL") }) { Text(">_") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            listOf("Library" to "LIBRARY", "ChatGPT" to "CHATGPT", "Discord" to "DISCORD", "Media" to "MEDIA",
                "App spaces" to "SPACES", "Papaya / GameHub" to "GAMING", "Android" to "SYSTEM").forEach { (label, tool) ->
                TextButton(onClick = { onTool(tool) }) { Text(label) }
            }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.labelSmall)
    }
    review?.let { value -> WorkspaceDialog({ review = null }, { Text("Run in Termux") }, {
        Column(Modifier.heightIn(max = 150.dp)) { Text(value.take(4000)); Text("Output appears in the launcher terminal. Managed scripts pause when the launcher is left.") }
    }, { TextButton(onClick = {
        review = null
        if (context.checkSelfPermission(TermuxBridge.PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(TermuxBridge.PERMISSION)
        else runCatching { TermuxBridge.run(context, value, false) }.onSuccess { status = "Script started"; onTerminal("") }
            .onFailure { status = it.message ?: "Could not start script" }
    }) { Text("Run script") } }, { TextButton(onClick = { review = null }) { Text("Cancel") } }) }
}
