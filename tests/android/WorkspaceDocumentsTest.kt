package com.playfieldportal.launcher.workspace

import android.app.Application
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** The real resolver, streams and document-contract calls against an opaque-ID provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WorkspaceDocumentsTest {
    private lateinit var context: Context
    private lateinit var provider: FixtureDocumentsProvider
    private lateinit var documents: WorkspaceDocuments
    private val root = Uri.parse("content://arcdeck.test.documents/tree/root/document/root")
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        provider = FixtureDocumentsProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "arcdeck.test.documents"; exported = true; grantUriPermissions = true; readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = "android.permission.MANAGE_DOCUMENTS" })
        provider.onCreate()
        ShadowContentResolver.registerProviderInternal("arcdeck.test.documents", provider)
        documents = WorkspaceDocuments(context)
    }
    @Test fun anUnavailableListingIsNotAnEmptyFolder() {
        assertTrue(documents.children(root).items.isEmpty())
        provider.failQuery = true
        assertThrows(IllegalStateException::class.java) { documents.children(root) }
    }
    @Test fun cancellingARefreshRetainsPreviouslyIndexedFilesAndTheirOrganization() {
        context.deleteDatabase("arcdeck_unified_v1.db")
        val catalog = UnifiedCatalog(context)
        try {
            context.contentResolver.takePersistableUriPermission(root, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            provider.createDocument("root", "text/plain", "Keep me.txt")
            assertTrue(catalog.scanRoot(root).complete)
            val entry = catalog.search("type:file").single(); catalog.rename(entry.key, "Personal name")
            val report = catalog.scanRoot(root, cancelled = { true })
            assertFalse(report.complete); assertEquals(0, report.indexed)
            val retained = catalog.get(entry.key)!!
            assertFalse(retained.missing); assertEquals("Personal name", retained.alias)
        } finally { catalog.close() }
    }
    @Test fun copyUsesStreamsWithoutOverwritingTheSource() = runBlocking {
        val id = provider.createDocument("root", "text/plain", "sample.txt")
        provider.nodes.getValue(id).file.writeText("sample content")
        val uri = DocumentsContract.buildDocumentUriUsingTree(root, id)
        documents.copy(documents.stat(uri), root, false) {}
        val children = documents.children(root).items
        assertEquals(2, children.size)
        val copy = children.first { it.uri != uri }
        assertEquals("sample content", context.contentResolver.openInputStream(copy.uri)!!.bufferedReader().use { it.readText() })
        assertTrue(provider.nodes.containsKey(id))
    }
    @Test fun aFailedCopyRollsBackItsNewDestinationAndRetainsTheSource() = runBlocking {
        val id = provider.createDocument("root", "text/plain", "source.txt")
        provider.failRead = id
        val source = documents.stat(DocumentsContract.buildDocumentUriUsingTree(root, id))
        val failed = runCatching { documents.copy(source, root, false) {} }
        assertTrue(failed.isFailure)
        assertEquals(listOf("source.txt"), documents.children(root).items.map { it.name })
    }
    @Test fun copyingIntoADescendantIsRejectedAcrossDifferentTreeGrants() = runBlocking {
        val folder = provider.createDocument("root", DocumentsContract.Document.MIME_TYPE_DIR, "Folder")
        val child = provider.createDocument(folder, DocumentsContract.Document.MIME_TYPE_DIR, "Child")
        val source = documents.stat(DocumentsContract.buildDocumentUriUsingTree(root, folder))
        val otherTree = DocumentsContract.buildTreeDocumentUri("arcdeck.test.documents", child)
        val destination = DocumentsContract.buildDocumentUriUsingTree(otherTree, child)
        assertTrue(runCatching { documents.copy(source, destination, false) {} }.isFailure)
        assertEquals(3, provider.nodes.size)
    }
    @Test fun moveDoesNotClaimSuccessIfTheSourceCannotBeRemoved() = runBlocking {
        val id = provider.createDocument("root", "text/plain", "source.txt")
        provider.nodes.getValue(id).file.writeText("keep me")
        provider.failDelete = id
        val source = documents.stat(DocumentsContract.buildDocumentUriUsingTree(root, id))
        val result = documents.copy(source, root, true) {}
        assertTrue(result.contains("source could not be removed"))
        assertTrue(provider.nodes.containsKey(id))
        assertEquals(2, documents.children(root).items.size)
    }
    @Test fun invalidNamesAreRejectedBeforeProviderMutation() {
        listOf("", "..", "a/b", "a\\b").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { documents.validateName(value) }
        }
        assertEquals(1, provider.nodes.size)
    }
    @Test fun movingATaggedFilePreservesItsOrganizationAtTheNewUri() = runBlocking {
        context.deleteDatabase("arcdeck_unified_v1.db")
        val catalog = UnifiedCatalog(context)
        try {
            val id = provider.createDocument("root", "text/plain", "organized.txt")
            provider.nodes.getValue(id).file.writeText("Move this")
            val folder = provider.createDocument("root", DocumentsContract.Document.MIME_TYPE_DIR, "Destination")
            val source = documents.stat(DocumentsContract.buildDocumentUriUsingTree(root, id))
            val target = DocumentsContract.buildDocumentUriUsingTree(root, folder)
            catalog.registerDocument(source, "saf:$root"); catalog.addTag("file:${source.uri}", "keep")
            val collection = catalog.createCollection("Organized"); catalog.addToCollection(collection, "file:${source.uri}")
            documents.copy(source, target, true, onMoved = { catalog.movedDocuments(it, documents) }) {}
            val moved = documents.children(target).items.single()
            assertNull(catalog.get("file:${source.uri}")); assertEquals(listOf("keep"), catalog.tags("file:${moved.uri}"))
            assertEquals("file:${moved.uri}", catalog.search("in:$collection").single().key)
        } finally { catalog.close() }
    }
}

class FixtureDocumentsProvider : DocumentsProvider() {
    data class Node(val id: String, val parent: String?, val name: String, val mime: String, val file: File)
    val nodes = linkedMapOf<String, Node>()
    var failQuery = false
    var failRead: String? = null
    var failDelete: String? = null
    private var nextId = 1
    private val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_FLAGS)
    override fun onCreate(): Boolean {
        val root = File(context!!.cacheDir, "test-documents").apply { mkdirs() }
        nodes.clear(); nodes["root"] = Node("root", null, "Root", DocumentsContract.Document.MIME_TYPE_DIR, root)
        return true
    }
    private fun cursor(projection: Array<out String>?, rows: List<Node>): Cursor {
        val cols = projection ?: columns
        return MatrixCursor(cols).apply {
            rows.forEach { node -> addRow(cols.map<String, Any?> { column -> when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> node.id
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> node.name
                DocumentsContract.Document.COLUMN_MIME_TYPE -> node.mime
                DocumentsContract.Document.COLUMN_SIZE -> node.file.length()
                DocumentsContract.Document.COLUMN_FLAGS -> DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_RENAME or
                    if (node.mime == DocumentsContract.Document.MIME_TYPE_DIR) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else 0
                else -> null
            } }.toTypedArray()) }
        }
    }
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(
        DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE,
        DocumentsContract.Root.COLUMN_FLAGS)).apply { addRow(arrayOf<Any>("root", "root", "Test", DocumentsContract.Root.FLAG_SUPPORTS_CREATE)) }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        cursor(projection, listOf(nodes[documentId] ?: throw FileNotFoundException(documentId)))
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        if (failQuery) throw FileNotFoundException("Storage disconnected")
        return cursor(projection, nodes.values.filter { it.parent == parentDocumentId })
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        var current: String? = documentId
        while (current != null) { if (current == parentDocumentId) return true; current = nodes[current]?.parent }
        return false
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (documentId == failRead && mode == "r") throw FileNotFoundException("Read failed")
        return ParcelFileDescriptor.open(nodes.getValue(documentId).file, ParcelFileDescriptor.parseMode(mode))
    }
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        check(nodes.containsKey(parentDocumentId))
        val id = "opaque-${nextId++}"
        val file = File(context!!.cacheDir, id)
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) file.mkdirs() else file.writeText("")
        nodes[id] = Node(id, parentDocumentId, displayName, mimeType, file)
        return id
    }
    override fun deleteDocument(documentId: String) {
        if (documentId == failDelete) throw FileNotFoundException("Read-only source")
        nodes.keys.filter { isChildDocument(documentId, it) }.toList().forEach { nodes.remove(it)?.file?.delete() }
    }
}
