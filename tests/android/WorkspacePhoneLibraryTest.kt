package com.playfieldportal.launcher.workspace

import android.app.Application
import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.domain.model.Platform
import java.io.FileNotFoundException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WorkspacePhoneLibraryTest {
    private lateinit var context: Context
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); context.deleteDatabase("arcdeck_phone_library.db") }
    private fun platform(id: String, vararg extensions: String) = Platform(id, id.uppercase(), id.uppercase(), null, 0L, romExtensions = extensions.toList())
    @Test fun ambiguousDiscImagesNeedAFolderHintWhileUniqueFormatsWorkAnywhere() {
        val classifier = PhoneGameClassifier(listOf(platform("ps2", ".iso", ".bin"), platform("psp", ".iso", ".cso"), platform("gba", ".gba")))
        assertNull(classifier.classify("/Download", "unknown.iso"))
        assertNull(classifier.classify("/Games", "unknown.iso"))
        assertEquals("psp", classifier.classify("/ROMs/PS2/PSP", "game.ISO"))
        assertEquals("ps2", classifier.classify("/PS2 Games", "game.iso"))
        assertEquals("gba", classifier.classify("/Download", "game.gba"))
        assertNull(classifier.classify("/PS2", "scph10000.bin"))
        assertTrue(classifier.skipFolder("Saves")); assertFalse(classifier.skipFolder("PSP"))
    }
    private fun doc(tree: Uri, id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
    private fun record(id: String, uri: String) = PhoneGameFile(id, uri, "game.gba", "gba", 10, 0)
    @Test fun completedScansPruneOnlySourcesActuallyVisitedAndFailedScansKeepOldEntries() {
        val a = Uri.parse("content://arcdeck.test.documents/tree/a"); val b = Uri.parse("content://arcdeck.test.documents/tree/b")
        WorkspacePhoneStore(context).use { store ->
            store.upsert(record("a", doc(a,"a/game")), 1); store.upsert(record("b", doc(b,"b/game")), 1)
            val partial = store.begin(listOf(doc(a,"a"))); store.finish(false, partial)
            assertNotNull(store.get("a")); assertNotNull(store.get("b"))
            val complete = store.begin(listOf(doc(a,"a"))); assertEquals(listOf("a"), store.stale(complete).map { it.first }); store.finish(true, complete)
            assertNull(store.get("a")); assertNotNull(store.get("b"))
        }
    }
    @Test fun pendingFoldersSurviveReopeningAndTheVisitedSetStopsCycles() {
        val tree = Uri.parse("content://arcdeck.test.documents/tree/root")
        WorkspacePhoneStore(context).use { it.begin(listOf(doc(tree,"root"))); it.enqueue(doc(tree,"child"), 1); it.visit("path:/roms") }
        WorkspacePhoneStore(context).use { store ->
            assertEquals(2, store.pending()); assertTrue(store.visited("path:/roms"))
            store.done(doc(tree,"root")); assertEquals(doc(tree,"child") to 1, store.next())
        }
    }
    @Test fun virtualConsoleReadsTheOriginalOpaqueDocumentAndNeverOffersWriteAccess() {
        val source = FixtureDocumentsProvider()
        source.attachInfo(context, ProviderInfo().apply { authority = "arcdeck.test.documents"; exported = true; grantUriPermissions = true; readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        source.onCreate(); ShadowContentResolver.registerProviderInternal("arcdeck.test.documents", source)
        val sourceId = source.createDocument("root", "application/octet-stream", "game.gba")
        source.nodes.getValue(sourceId).file.writeText("original ROM bytes")
        val tree = Uri.parse("content://arcdeck.test.documents/tree/root")
        val original = DocumentsContract.buildDocumentUriUsingTree(tree, sourceId)
        val id = WorkspacePhoneStore.sourceId(original)
        WorkspacePhoneStore(context).use { it.upsert(record(id, original.toString()), 1) }
        val provider = WorkspaceStorageProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = WorkspacePhoneStore.authority(context); exported = true; grantUriPermissions = true; readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        provider.onCreate(); ShadowContentResolver.registerProviderInternal(WorkspacePhoneStore.authority(context), provider)
        assertTrue(provider.isChildDocument("auto/gba", id)); assertFalse(provider.isChildDocument("auto/ps2", id))
        provider.queryChildDocuments("auto/gba", projection = null, sortOrder = null).use { assertEquals(1, it.count) }
        val uri = WorkspacePhoneStore.doc(context, id)
        assertEquals("original ROM bytes", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
        assertThrows(FileNotFoundException::class.java) { provider.openDocument(id, "w", null) }
        assertEquals("original ROM bytes", source.nodes.getValue(sourceId).file.readText())
        assertThrows(IllegalArgumentException::class.java) { WorkspaceStorage.validate(context, "/data/user/0/com.discord/files") }
        assertFalse(provider.isChildDocument("auto", "source:not-registered"))
    }
}
