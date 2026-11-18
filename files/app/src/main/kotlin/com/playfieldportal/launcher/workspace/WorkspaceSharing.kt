package com.playfieldportal.launcher.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal data class IncomingShare(val text: String, val subject: String, val uris: List<Uri>)
internal object WorkspaceSharing {
    const val MAX_FILE_BYTES = 512L * 1024 * 1024
    const val MAX_BATCH_BYTES = 1024L * 1024 * 1024
    @Suppress("DEPRECATION") fun parse(intent: Intent): IncomingShare {
        require(intent.action in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) { "Unsupported share action" }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: ""
        require(text.length <= 500_000) { "Shared text is too large" }
        val subject = (intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "Shared text").take(120)
        val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.toList() ?: emptyList()
            else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        val uris = (streams + (0 until (intent.clipData?.itemCount ?: 0)).mapNotNull { intent.clipData?.getItemAt(it)?.uri }).distinct()
        require(uris.size <= 32) { "Share up to 32 files at a time" }
        require(uris.all { it.scheme == "content" && !it.authority.isNullOrBlank() }) { "Only Android content files can be imported" }
        require(text.isNotBlank() || uris.isNotEmpty()) { "There is no shared text or file to save" }
        return IncomingShare(text, subject, uris)
    }
    fun describe(context: Context, uri: Uri): WorkspaceDocument {
        require(uri.scheme == "content") { "Only content files are supported" }
        var name = "Shared file"; var size = 0L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni).take(240)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si).coerceAtLeast(0)
            }
        }
        return WorkspaceDocument(uri, name, context.contentResolver.getType(uri) ?: "application/octet-stream", size, 0)
    }
    /** Copy temporary share grants into private, durable storage before indexing them. */
    suspend fun import(context: Context, catalog: UnifiedCatalog, space: AppSpace, payload: IncomingShare): Int {
        val batch = File(context.filesDir, "arcdeck_shared/${UUID.randomUUID()}")
        check(batch.mkdirs()) { "Could not prepare shared-file storage" }
        val entries = mutableListOf<CatalogEntry>(); var total = 0L
        fun record(file: File, name: String, mime: String) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file, name)
            entries += CatalogEntry("file:$uri", "FILE", name, mime, uri.toString(), "share")
        }
        try {
            if (payload.text.isNotBlank()) {
                val value = payload.text.trim(); val url = Uri.parse(value)
                if (value.length <= 2048 && value.none { it.isWhitespace() } && url.scheme in setOf("https", "http") && !url.host.isNullOrBlank()) {
                    entries += CatalogEntry("user:${UUID.randomUUID()}", "LINK",
                        payload.subject.takeIf { it.isNotBlank() && it != "Shared text" } ?: url.host!!,
                        "Shared from Android", value, "user")
                } else {
                    val file = File(batch, "shared-text.txt"); file.writeText(payload.text, Charsets.UTF_8)
                    total += file.length(); record(file, payload.subject.ifBlank { "Shared text" } + ".txt", "text/plain")
                }
            }
            for ((index, uri) in payload.uris.withIndex()) {
                currentCoroutineContext().ensureActive()
                val doc = describe(context, uri)
                require(doc.size <= MAX_FILE_BYTES) { "${doc.name} exceeds the 512 MB import limit; connect its folder instead" }
                val suffix = doc.name.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,12}")) }
                    ?: android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(doc.mime) ?: "bin"
                val file = File(batch, "file-$index.${suffix.lowercase(java.util.Locale.ROOT)}")
                var bytes = 0L
                (context.contentResolver.openInputStream(uri) ?: error("Cannot read ${doc.name}")).use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        bytes += count; total += count
                        require(bytes <= MAX_FILE_BYTES && total <= MAX_BATCH_BYTES) { "Shared files exceed the import limit" }
                        check(batch.usableSpace > 32L * 1024 * 1024) { "Storage is nearly full" }
                        output.write(buffer, 0, count)
                    }
                } }
                record(file, doc.name, doc.mime)
            }
            catalog.attachIncoming(WorkspaceSpaces.shared(context).collectionId(space, catalog), entries)
            if (batch.listFiles()?.isEmpty() == true) batch.delete()
            return entries.size
        } catch (e: Throwable) { batch.deleteRecursively(); throw e }
    }
}

/** A review screen keeps an inbound Android share tied to the space the user chooses. */
class WorkspaceShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val payload = runCatching { WorkspaceSharing.parse(intent) }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val store = remember { WorkspaceSpaces.shared(this) }; val catalog = remember { UnifiedCatalog.shared(this) }
                val revision by store.revision.collectAsState()
                val spaces = remember(revision) { store.all() }
                var chosen by remember { mutableStateOf(store.active()?.id) }
                var newName by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf(payload.exceptionOrNull()?.message ?: "") }
                val scope = rememberCoroutineScope()
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().imePadding().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Save to ArcDeck", style = MaterialTheme.typography.headlineSmall)
                        payload.getOrNull()?.let { data ->
                            Text("${data.uris.size} files${if (data.text.isNotBlank()) " and shared text" else ""}")
                            if (data.text.isNotBlank()) Text(data.text.take(600))
                        }
                        Text("Choose a space")
                        spaces.forEach { space -> FilterChip(chosen == space.id, { chosen = space.id }, label = { Text(space.name) }) }
                        OutlinedTextField(newName, { newName = it.take(60) }, label = { Text("Or create a new space") }, enabled = !busy)
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                        Button(enabled = !busy && payload.isSuccess && (chosen != null || newName.isNotBlank()), onClick = {
                            busy = true
                            scope.launch {
                                workspaceResult { withContext(Dispatchers.IO) {
                                    val space = if (newName.isNotBlank()) store.create(newName, "", catalog) else store.all().first { it.id == chosen }
                                    WorkspaceSharing.import(this@WorkspaceShareActivity, catalog, space, payload.getOrThrow()); store.select(space.id); space
                                } }.onSuccess { space -> WorkspaceRequests.open(this@WorkspaceShareActivity, space.id); finish() }
                                    .onFailure { error = it.message ?: "Could not save shared content" }
                                busy = false
                            }
                        }) { Text("Save to space") }
                        TextButton(enabled = !busy, onClick = { finish() }) { Text("Cancel") }
                    }
                }
            }
        }
    }
}
