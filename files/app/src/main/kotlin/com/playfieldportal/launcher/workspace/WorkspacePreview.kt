package com.playfieldportal.launcher.workspace

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Reader

internal object WorkspacePreview {
    const val TEXT_LIMIT = 262144
    fun readText(reader: Reader): Pair<String, Boolean> {
        val out = StringBuilder(); val buffer = CharArray(8192)
        while (out.length <= TEXT_LIMIT) {
            val n = reader.read(buffer, 0, minOf(buffer.size, TEXT_LIMIT + 1 - out.length)); if (n < 0) break
            out.append(buffer, 0, n)
        }
        return out.take(TEXT_LIMIT).toString() to (out.length > TEXT_LIMIT)
    }
    fun isText(doc: WorkspaceDocument): Boolean = doc.mime.startsWith("text/") || doc.name.substringAfterLast('.', "").lowercase() in setOf("txt", "md", "json", "csv", "log", "xml", "yaml", "yml", "ini", "py", "kt", "java", "sh")
    fun pdf(context: Context, uri: Uri, page: Int): Pair<Bitmap, Int> {
        // Some providers return a non-seekable pipe; stage a bounded copy for PdfRenderer.
        val temp = File.createTempFile("arcdeck-preview-", ".pdf", context.cacheDir)
        try {
            (context.contentResolver.openInputStream(uri) ?: error("Cannot open PDF")).use { input -> temp.outputStream().use { output ->
                val buffer = ByteArray(65536); var total = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 64L * 1024 * 1024) { "PDF is too large for the built-in preview; use Open with" }; output.write(buffer, 0, n) }
            } }
            return PdfRenderer(ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                require(renderer.pageCount > 0)
                renderer.openPage(page.coerceIn(0, renderer.pageCount - 1)).use { p ->
                    val scale = minOf(1400f / p.width, 2000f / p.height, 2f)
                    val bitmap = Bitmap.createBitmap((p.width * scale).toInt().coerceAtLeast(1), (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE); p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap to renderer.pageCount
                }
            }
        } finally { temp.delete() }
    }
}

@Composable internal fun WorkspacePreviewPane(uri: String?) {
    val context = LocalContext.current
    var doc by remember(uri) { mutableStateOf<WorkspaceDocument?>(null) }; var text by remember(uri) { mutableStateOf("") }
    var truncated by remember(uri) { mutableStateOf(false) }; var error by remember(uri) { mutableStateOf("") }
    var page by remember(uri) { mutableIntStateOf(0) }; var count by remember(uri) { mutableIntStateOf(0) }
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }; var busy by remember(uri) { mutableStateOf(false) }
    var zoom by remember(uri) { mutableFloatStateOf(1f) }
    LaunchedEffect(uri) {
        if (uri == null) return@LaunchedEffect
        workspaceResult { withContext(Dispatchers.IO) { WorkspaceDocuments(context).stat(Uri.parse(uri)) } }
            .onSuccess { doc = it }.onFailure { error = it.message ?: "Cannot read this file" }
    }
    LaunchedEffect(doc, page) {
        val file = doc ?: return@LaunchedEffect; busy = true; error = ""
        workspaceResult { withContext(Dispatchers.IO) {
            if (WorkspacePreview.isText(file)) {
                val result = (context.contentResolver.openInputStream(file.uri) ?: error("Cannot open file")).bufferedReader().use(WorkspacePreview::readText)
                Triple(result.first, result.second, null as Pair<Bitmap, Int>?)
            } else if (file.mime == "application/pdf" || file.name.endsWith(".pdf", true)) Triple("", false, WorkspacePreview.pdf(context, file.uri, page))
            else Triple("", false, null)
        } }.onSuccess { result -> text = result.first; truncated = result.second; bitmap = result.third?.first; count = result.third?.second ?: 0 }
            .onFailure { error = it.message ?: "Preview unavailable; use Open with" }
        busy = false
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(doc?.name ?: "Select a file to preview", style = MaterialTheme.typography.titleMedium)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        doc?.let { file ->
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { runCatching { openWorkspaceDocument(context, file) }.onFailure { error = it.message ?: "No viewer available" } }) { Text("Open with…") }
                TextButton(onClick = { runCatching { WorkspaceApps.shareFiles(context, listOf(file)) }.onFailure { error = it.message ?: "Cannot share" } }) { Text("Share") }
                if (text.isNotBlank()) TextButton(onClick = { WorkspaceApps.copy(context, text) }) { Text("Copy text") }
            }
            if (file.mime.startsWith("image/") || bitmap != null) {
                Row {
                    TextButton(onClick = { zoom = (zoom / 1.25f).coerceAtLeast(1f) }) { Text("−") }
                    TextButton(onClick = { zoom = 1f }) { Text("${(zoom * 100).toInt()}%") }
                    TextButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(4f) }) { Text("+") }
                    if (count > 0) {
                        TextButton(enabled = page > 0 && !busy, onClick = { page--; zoom = 1f }) { Text("Previous") }
                        Text("${page + 1}/$count", Modifier.padding(top = 12.dp))
                        TextButton(enabled = page < count - 1 && !busy, onClick = { page++; zoom = 1f }) { Text("Next") }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().transformable(rememberTransformableState { scale, _, _ -> zoom = (zoom * scale).coerceIn(1f, 4f) })) {
                    val image = Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom)
                    if (bitmap != null) Image(bitmap!!.asImageBitmap(), file.name, image, contentScale = ContentScale.Fit)
                    else AsyncImage(file.uri, file.name, image, contentScale = ContentScale.Fit)
                }
            } else if (WorkspacePreview.isText(file)) {
                if (truncated) Text("Preview limited to the first 256K characters", style = MaterialTheme.typography.bodySmall)
                SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) { Text(text) }
            } else Text("${file.mime} · ${file.size / 1024} KB\nUse Open with to choose a compatible app.")
        }
    }
}
