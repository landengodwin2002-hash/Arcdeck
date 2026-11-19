package com.playfieldportal.launcher.workspace

import android.content.Intent
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp

@Composable internal fun WorkspaceVoiceButton(onText: (String) -> Unit) {
    val context = LocalContext.current; var error by remember { mutableStateOf("") }
    val recognize = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.take(50000)?.let(onText)
    }
    TextButton(onClick = { runCatching { recognize.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to ArcDeck")) }.onFailure { error = "Enable a speech recognition app or use your keyboard microphone." } }) { Text("Voice") }
    if (error.isNotBlank()) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }) { Text(error) }
}

@Composable internal fun WorkspaceClipboardPane() {
    val context = LocalContext.current; val keyboard = LocalSoftwareKeyboardController.current
    val prefs = remember { context.getSharedPreferences("arcdeck_workspace", 0) }
    var text by remember { mutableStateOf(prefs.getString("clipboard_draft", "") ?: "") }
    var status by remember { mutableStateOf("") }
    val latest by rememberUpdatedState(text)
    DisposableEffect(Unit) { onDispose { prefs.edit().putString("clipboard_draft", latest.take(50000)).apply() } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Clipboard pad", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { WorkspaceApps.copy(context, text); status = "Copied. Paste into the focused ChatGPT, Discord or native app field." }) { Text("Copy") }
            TextButton(onClick = {
                val clip = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip
                text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.take(50000) ?: ""
            }) { Text("Paste here") }
            WorkspaceVoiceButton { text = (text + if (text.isBlank()) "" else "\n") + it }
            TextButton(onClick = { keyboard?.show() }) { Text("Keyboard") }
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }) { Text("Keyboard settings") }
            TextButton(onClick = { text = "" }) { Text("Clear") }
        }
        OutlinedTextField(text, { text = it.take(50000) }, label = { Text("Text to move between apps") }, modifier = Modifier.fillMaxWidth().weight(1f))
        Text(status.ifBlank { "Uses your Android keyboard and its voice typing. Clipboard contents are read only when you tap Paste here." }, style = MaterialTheme.typography.bodySmall)
    }
}
