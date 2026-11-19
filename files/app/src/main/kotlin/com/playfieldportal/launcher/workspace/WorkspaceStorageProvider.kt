package com.playfieldportal.launcher.workspace

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import org.json.JSONObject

internal data class PhoneStorageItem(val path: String, val name: String, val directory: Boolean, val size: Long, val modified: Long)
internal object WorkspaceStorage {
    fun allFiles(context: Context): Boolean = android.os.Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()
    fun allowed(context: Context) = allFiles(context) || WorkspaceNative.ready()
    fun roots(context: Context): List<String> {
        val main = Environment.getExternalStorageDirectory().absolutePath
        val volumes = if (android.os.Build.VERSION.SDK_INT >= 30) context.getSystemService(android.os.storage.StorageManager::class.java).storageVolumes.mapNotNull { it.directory?.absolutePath } else emptyList()
        return (listOf(main) + volumes).distinct().filter { it.startsWith("/storage/") || it == main }
    }
    fun validate(context: Context, path: String): String {
        require(path.length <= 4096 && path.startsWith('/') && !path.contains('\u0000'))
        val normalized = File(path).canonicalPath
        require(roots(context).any { root -> normalized == root || normalized.startsWith("${root.trimEnd('/')}/") }) { "Path is outside shared phone storage" }
        require(!normalized.endsWith("/Android/data") && !normalized.endsWith("/Android/obb") && !normalized.contains("/Android/obb/")) { "Connect a specific game app folder" }
        return normalized
    }
    private fun remote(context: Context, path: String) = WorkspaceNative.ready() && (!allFiles(context) || path.contains("/Android/data/"))
    fun stat(context: Context, path: String): PhoneStorageItem {
        val safe = validate(context, path)
        if (remote(context, safe)) return parse(WorkspaceNative.files(JSONObject().put("op", "stat").put("path", safe)))
        check(allFiles(context)) { "Allow phone storage or connect Shizuku" }
        val file = File(safe); check(file.exists()) { "File no longer exists" }
        return PhoneStorageItem(safe, file.name, file.isDirectory, if (file.isFile) file.length() else 0, file.lastModified())
    }
    fun list(context: Context, path: String): List<PhoneStorageItem> {
        val safe = validate(context, path)
        if (remote(context, safe)) {
            val out = mutableListOf<PhoneStorageItem>(); var offset = 0
            do {
                val page = WorkspaceNative.files(JSONObject().put("op", "list").put("path", safe).put("offset", offset))
                val files = page.getJSONArray("files"); for (i in 0 until files.length()) out.add(parse(files.getJSONObject(i)))
                val next = page.optInt("next", -1); require(next == -1 || next > offset)
                offset = next
            } while (offset >= 0)
            return out
        }
        check(allFiles(context)) { "Allow phone storage or connect Shizuku" }
        val files = File(safe).listFiles() ?: error("Cannot read directory")
        require(files.size <= 20000) { "Directory is too large; connect a smaller subfolder" }
        return files.filterNot { it.path.contains("/Android/data") || it.path.contains("/Android/obb") }.sortedBy { it.name }
            .map { PhoneStorageItem(it.path, it.name, it.isDirectory, if (it.isFile) it.length() else 0, it.lastModified()) }
    }
    fun open(context: Context, path: String): ParcelFileDescriptor {
        val safe = validate(context, path)
        if (remote(context, safe)) return WorkspaceNative.openFile(safe)
        check(allFiles(context)); return ParcelFileDescriptor.open(File(safe), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    private fun parse(o: JSONObject) = PhoneStorageItem(o.getString("path"), o.getString("name"), o.getBoolean("directory"), o.optLong("size"), o.optLong("modified"))
    fun mime(name: String, directory: Boolean): String = if (directory) DocumentsContract.Document.MIME_TYPE_DIR else MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    fun owns(context: Context, uri: Uri) = uri.authority == WorkspacePhoneStore.authority(context)
    fun hasGrant(context: Context, uri: Uri): Boolean = if (owns(context, uri)) allowed(context) || runCatching { DocumentsContract.getTreeDocumentId(uri).let { it == "auto" || it.startsWith("auto/") } }.getOrDefault(false)
        else context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
}

/** Read-only provider joining existing phone files and granted app folders into one virtual ROM root. */
class WorkspaceStorageProvider : DocumentsProvider() {
    private val app get() = requireNotNull(context)
    private val store by lazy { WorkspacePhoneStore(app) }
    private val defaults = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID,
        DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS)).apply {
        val values = mapOf<String, Any>(DocumentsContract.Root.COLUMN_ROOT_ID to "auto", DocumentsContract.Root.COLUMN_DOCUMENT_ID to "auto",
            DocumentsContract.Root.COLUMN_TITLE to "ArcDeck phone games", DocumentsContract.Root.COLUMN_FLAGS to DocumentsContract.Root.FLAG_LOCAL_ONLY)
        addRow(columnNames.map { values[it] }.toTypedArray())
    }
    private fun row(id: String): Map<String, Any> {
        if (id == "auto" || id.startsWith("auto/")) {
            require(id == "auto" || id == "auto/windows" || id == "auto/windows/import" || id.removePrefix("auto/") in store.platforms()) { "Unknown virtual console" }
            return mapOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID to id, DocumentsContract.Document.COLUMN_DISPLAY_NAME to if (id == "auto") "Phone games" else id.substringAfterLast('/'),
                DocumentsContract.Document.COLUMN_MIME_TYPE to DocumentsContract.Document.MIME_TYPE_DIR, DocumentsContract.Document.COLUMN_FLAGS to 0)
        }
        val entry = store.get(id)
        if (entry != null) return mapOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID to id, DocumentsContract.Document.COLUMN_DISPLAY_NAME to entry.name,
            DocumentsContract.Document.COLUMN_MIME_TYPE to WorkspaceStorage.mime(entry.name, false), DocumentsContract.Document.COLUMN_FLAGS to 0,
            DocumentsContract.Document.COLUMN_SIZE to entry.size, DocumentsContract.Document.COLUMN_LAST_MODIFIED to entry.modified)
        val raw = WorkspaceStorage.stat(app, id)
        return mapOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID to raw.path, DocumentsContract.Document.COLUMN_DISPLAY_NAME to raw.name,
            DocumentsContract.Document.COLUMN_MIME_TYPE to WorkspaceStorage.mime(raw.name, raw.directory), DocumentsContract.Document.COLUMN_FLAGS to 0,
            DocumentsContract.Document.COLUMN_SIZE to raw.size, DocumentsContract.Document.COLUMN_LAST_MODIFIED to raw.modified)
    }
    private fun cursor(projection: Array<out String>?, ids: List<String>): Cursor = MatrixCursor(projection ?: defaults).apply {
        ids.forEach { id -> val values = row(id); addRow(columnNames.map { values[it] }.toTypedArray()) }
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val identity = Binder.clearCallingIdentity()
        try { return cursor(projection, listOf(documentId)) } finally { Binder.restoreCallingIdentity(identity) }
    }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val identity = Binder.clearCallingIdentity()
        try {
            val ids = when {
                parentDocumentId == "auto" -> (store.platforms().map { "auto/$it" } + "auto/windows").distinct()
                parentDocumentId == "auto/windows" -> listOf("auto/windows/import")
                parentDocumentId == "auto/windows/import" -> store.files(kind = "PC").map { it.id }
                parentDocumentId.startsWith("auto/") -> store.files(parentDocumentId.removePrefix("auto/"), "ROM").map { it.id }
                else -> WorkspaceStorage.list(app, parentDocumentId).map { it.path }
            }
            require(ids.size <= 20000) { "Console exceeds one provider page; narrow the connected source" }
            return cursor(projection, ids)
        } finally { Binder.restoreCallingIdentity(identity) }
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (parentDocumentId == documentId) return true
        if (parentDocumentId == "auto") return documentId.startsWith("auto/") || store.get(documentId) != null
        if (parentDocumentId == "auto/windows") return documentId == "auto/windows/import" || store.get(documentId)?.kind == "PC"
        if (parentDocumentId == "auto/windows/import") return store.get(documentId)?.kind == "PC"
        if (parentDocumentId.startsWith("auto/")) return store.get(documentId)?.let { it.platform == parentDocumentId.removePrefix("auto/") && it.kind in setOf("ROM", "AUX") } == true
        return runCatching { val parent = WorkspaceStorage.validate(app, parentDocumentId); val child = WorkspaceStorage.validate(app, documentId); child.startsWith("${parent.trimEnd('/')}/") }.getOrDefault(false)
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Automatic game sources are read-only; originals stay in place")
        val identity = Binder.clearCallingIdentity()
        try {
            val entry = store.get(documentId)
            if (entry != null && Uri.parse(entry.uri).authority != WorkspacePhoneStore.authority(app)) return app.contentResolver.openFileDescriptor(Uri.parse(entry.uri), "r", signal) ?: throw FileNotFoundException(entry.name)
            return WorkspaceStorage.open(app, documentId)
        } finally { Binder.restoreCallingIdentity(identity) }
    }
}
