package com.playfieldportal.launcher.workspace

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class TerminalRun(val id: String, val command: String, val status: String,
    val output: String = "", val exitCode: Int? = null)

internal object TermuxBridge {
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private fun prefs(context: Context) = context.getSharedPreferences("arcdeck_terminal", Context.MODE_PRIVATE)

    @Synchronized
    fun history(context: Context): List<TerminalRun> = runCatching {
        val rows = JSONArray(prefs(context).getString("history", "[]"))
        (0 until rows.length()).take(20).map { i -> rows.getJSONObject(i).let {
            TerminalRun(it.getString("id"), it.getString("command"), it.getString("status"),
                it.optString("output"), if (it.isNull("exit")) null else it.getInt("exit"))
        } }
    }.getOrDefault(emptyList())

    private fun save(context: Context, runs: List<TerminalRun>) {
        val rows = JSONArray()
        runs.take(20).forEach { rows.put(JSONObject().put("id", it.id).put("command", it.command)
            .put("status", it.status).put("output", it.output.takeLast(100000))
            .put("exit", it.exitCode ?: JSONObject.NULL)) }
        prefs(context).edit().putString("history", rows.toString()).apply()
    }

    @Synchronized
    fun clear(context: Context) = prefs(context).edit().remove("history").apply()

    @Synchronized
    fun acceptResult(context: Context, intent: Intent) {
        val id = intent.data?.lastPathSegment ?: return
        val rows = history(context)
        if (rows.none { it.id == id && it.status == "Running" }) return
        val result = intent.getBundleExtra("result") ?: return
        val error = result.getInt("err", -1)
        val output = listOf(result.getString("stdout", ""), result.getString("stderr", ""),
            result.getString("errmsg", "")).filter { it.isNotBlank() }.joinToString("\n").takeLast(100000)
        val exit = if (result.containsKey("exitCode")) result.getInt("exitCode") else null
        save(context, rows.map { if (it.id == id) it.copy(status = if (error == -1 && exit == 0) "Completed" else "Failed",
            output = output, exitCode = exit) else it })
    }

    @Synchronized
    fun run(context: Context, command: String, interactive: Boolean): String {
        require(command.isNotBlank() && command.length <= 2048) { "Enter a command up to 2048 characters" }
        check(context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED) { "Grant the Termux command permission first" }
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo("com.termux", 0)
        val id = UUID.randomUUID().toString()
        val callback = Intent(context, WorkspaceTermuxReceiver::class.java)
            .setAction("${context.packageName}.TERMUX_RESULT").setData(Uri.parse("arcdeck://termux/$id"))
        val flags = PendingIntent.FLAG_ONE_SHOT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(context, 0, callback, flags)
        val intent = Intent("com.termux.RUN_COMMAND").setClassName("com.termux", "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", !interactive)
            .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)
            .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "ArcDeck command")
        save(context, listOf(TerminalRun(id, command, "Running")) + history(context))
        try { checkNotNull(context.startService(intent)) { "Termux command service is unavailable" } }
        catch (e: Exception) {
            pending.cancel()
            save(context, history(context).map { if (it.id == id) it.copy(status = "Failed", output = e.message ?: "Could not start Termux") else it })
            throw e
        }
        return id
    }
}

/** Explicit, non-exported receiver; only the one-shot capability given to Termux can deliver results. */
class WorkspaceTermuxReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { TermuxBridge.acceptResult(context, intent) }
}

@Composable
internal fun WorkspaceTerminalPane(initialCommand: String?, requestId: Int = 0) {
    val context = LocalContext.current
    val draft = remember { context.getSharedPreferences("arcdeck_terminal", Context.MODE_PRIVATE) }
    var command by remember { mutableStateOf(draft.getString("draft", "") ?: "") }
    val latestCommand by rememberUpdatedState(command)
    DisposableEffect(Unit) { onDispose { draft.edit().putString("draft", latestCommand).apply() } }
    var interactive by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var runs by remember { mutableStateOf(TermuxBridge.history(context)) }
    var selected by remember { mutableStateOf<TerminalRun?>(runs.firstOrNull()) }
    LaunchedEffect(initialCommand, requestId) { if (initialCommand != null) command = initialCommand }
    DisposableEffect(context) {
        val pref = context.getSharedPreferences("arcdeck_terminal", Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            runs = TermuxBridge.history(context)
            selected = runs.find { it.id == selected?.id } ?: runs.firstOrNull()
        }
        pref.registerOnSharedPreferenceChangeListener(listener)
        onDispose { pref.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        status = if (it) "Permission granted. Review your command to run it."
            else "Permission was not granted. Enable it in ArcDeck app permissions."
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(command, { if (it.length <= 2048) command = it }, label = { Text("Bash command") },
            modifier = Modifier.fillMaxWidth(), maxLines = 3, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Button(enabled = command.isNotBlank(), onClick = { review = command }) { Text("Review & run") }
            TextButton(onClick = { permission.launch(TermuxBridge.PERMISSION) }) { Text("Grant permission") }
            TextButton(onClick = { runCatching {
                context.startActivity(context.packageManager.getLaunchIntentForPackage("com.termux") ?: error("Install Termux first"))
            }.onFailure { status = it.message ?: "Termux unavailable" } }) { Text("Open Termux") }
            TextButton(onClick = { TermuxBridge.clear(context); selected = null }) { Text("Clear history") }
        }
        Row {
            Checkbox(checked = interactive, onCheckedChange = { interactive = it })
            Text("Interactive session in Termux", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        Text("Enable allow-external-apps=true in ~/.termux/termux.properties. Captured output appears when a command exits.",
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            runs.forEach { run -> TextButton(onClick = { selected = run }) { Text("${run.status}: ${run.command.take(20)}") } }
        }
        val active = selected?.let { chosen -> runs.find { it.id == chosen.id } ?: chosen }
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize()) {
                item { Text(active?.let { "$ ${it.command}\n${it.status}${it.exitCode?.let { code -> " · exit $code" } ?: ""}\n\n${it.output}" }
                    ?: "Run a command to capture its output here.", fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    review?.let { reviewed ->
        val reviewedInteractive = interactive
        AlertDialog(onDismissRequest = { review = null }, title = { Text("Run this command in Termux?") },
            text = { Column { Text(reviewed, fontFamily = FontFamily.Monospace)
                Text(if (reviewedInteractive) "Opens an interactive Termux session." else "Runs in the background and returns output to this window.") } },
            confirmButton = { TextButton(onClick = {
                review = null
                runCatching { TermuxBridge.run(context, reviewed, reviewedInteractive) }
                    .onSuccess { status = "Command started"; runs = TermuxBridge.history(context); selected = runs.firstOrNull() }
                    .onFailure { status = it.message ?: "Could not start command" }
            }) { Text("Run") } }, dismissButton = { TextButton(onClick = { review = null }) { Text("Cancel") } })
    }
}
