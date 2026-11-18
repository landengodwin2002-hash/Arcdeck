package com.playfieldportal.launcher.workspace

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp", application = Application::class)
class WorkspaceComposeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var context: Context
    private lateinit var catalog: UnifiedCatalog
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("arcdeck_unified_v1.db")
        context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE).edit().clear().commit()
        catalog = UnifiedCatalog(context)
    }
    @After fun cleanup() { catalog.close() }
    @Test fun gameConnectionsCanBeOpenedAndClosedAtPhoneWidth() {
        compose.setContent { MaterialTheme { WorkspaceSpacePane(catalog, {}, {}, {}, {}) } }
        compose.onNodeWithText("Game connections").performClick()
        waitFor("Connect existing folder")
        compose.onNodeWithText("Connect existing folder").assertIsDisplayed()
        compose.onNodeWithText("Back to spaces").performClick()
        compose.onNodeWithText("New app space").assertExists()
    }
    private fun waitFor(text: String) = compose.waitUntil(15000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    private fun library() { compose.setContent { MaterialTheme {
        UnifiedLibraryPane(catalog, 0, null, false, {}, { _, _ -> }, {}, {}, {})
    } } }
    @Test fun searchAndShortcutEditingWorkAtPhoneWidth() {
        catalog.addCustom("LINK", "Test website", "https://example.com")
        library(); waitFor("Test website")
        compose.onNodeWithText("Search games, apps, files and tools").performTextInput("website")
        waitFor("Test website")
        compose.onNodeWithText("⋮").performClick()
        compose.onNodeWithText("Display alias").performTextInput("My website")
        compose.onNodeWithText("Save display alias").performClick()
        compose.onNodeWithText("Close").performClick()
        waitFor("My website")
        assertEquals("My website", catalog.search("website").single().alias)
    }
    @Test fun searchStateSurvivesClosingAndReopeningTheWorkspace() {
        val expanded = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { XmbWorkspaceOverlay(expanded.value, {}, {}, {}, emptyList(), {}, { _, _ -> }, Modifier.fillMaxSize()) }
        compose.onNodeWithText("Search games, apps, files and tools").performTextInput("kept query")
        compose.runOnIdle { expanded.value = false }
        compose.waitForIdle()
        compose.runOnIdle { expanded.value = true }
        compose.waitForIdle()
        compose.onNodeWithText("Search games, apps, files and tools").assertTextContains("kept query")
    }
    @Test fun notesSaveWhenTheirWindowClosesBeforeTheDebounce() {
        val shown = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { MaterialTheme { if (shown.value) WorkspaceNotesPane() } }
        compose.onNodeWithText("Workspace notes").performTextInput("Saved on close")
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        assertEquals("Saved on close", context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE).getString("notes", ""))
    }
    @Test fun everyWorkspaceToolCanBeReachedOnAPhone() {
        compose.setContent { XmbWorkspaceOverlay(true, {}, {}, {}, emptyList(), {}, { _, _ -> }, Modifier.fillMaxSize()) }
        compose.onNodeWithTag("workspace-tool-SYSTEM").performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
        val directory = System.getProperty("arcdeck.screenshot.dir")
        if (directory != null) {
            val file = File(directory, "workspace-phone.png"); file.parentFile!!.mkdirs()
            file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithTag("workspace-panel-SYSTEM").assertExists()
        compose.onNodeWithText("⌂  Change default home app").assertExists()
    }
    @Test fun anInstalledAppCanHaveItsOwnSpaceWithoutDuplicatingItsLibraryRecord() {
        catalog.syncSource("android", listOf(CatalogEntry("android:com.discord", "APP", "Discord", "Installed app", "com.discord", "android")))
        compose.setContent { MaterialTheme { WorkspaceSpacePane(catalog, {}, {}, {}, {}) } }
        compose.onNodeWithText("New app space").performClick(); waitFor("Discord")
        compose.onNodeWithText("Discord").performClick()
        compose.onNodeWithText("Save space").performClick(); waitFor("Connected app: com.discord")
        assertEquals("com.discord", WorkspaceSpaces.shared(context).active()!!.app)
        compose.onNodeWithText("Open app").assertExists()
        compose.onNodeWithText("Copy context").assertExists()
        val directory = System.getProperty("arcdeck.screenshot.dir")
        if (directory != null) {
            val file = File(directory, "app-space-phone.png"); file.parentFile!!.mkdirs()
            file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun originalPfpSectionsRemainReachableFromTheWorkspace() {
        var requested = ""
        compose.setContent { MaterialTheme { WorkspacePfpPane(listOf("music" to "Music", "videos" to "Videos", "photos" to "Photos", "social" to "Social")) { requested = it } } }
        compose.onNodeWithText("Music").performClick()
        assertEquals("category:music", requested)
        compose.onNodeWithText("Photos").assertExists()
        compose.onNodeWithText("Return to original PFP").performClick()
        assertEquals("original", requested)
    }
    @Test fun appSpaceNotesSaveWhenThePanelClosesImmediately() {
        val store = WorkspaceSpaces.shared(context); val space = store.create("Discord", "com.discord", catalog)
        val shown = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { MaterialTheme { if (shown.value) SpaceNotesEditor(space, store) } }
        compose.onNodeWithText("Discord notes").performTextInput("Keep across apps")
        compose.runOnIdle { shown.value = false }; compose.waitForIdle()
        assertEquals("Keep across apps", store.all().single().notes)
    }
    @Test fun savedLayoutsCanReuseWindowIdsForDifferentToolsWithoutLosingSearchState() {
        compose.setContent { XmbWorkspaceOverlay(true, {}, {}, {}, emptyList(), {}, { _, _ -> }, Modifier.fillMaxSize()) }
        compose.onNodeWithText("Search games, apps, files and tools").performTextInput("Kept across layouts")
        val prefs = context.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE)
        fun switch(tool: String, epoch: Long) = prefs.edit().putString("panels", "[{\"id\":1,\"tool\":\"$tool\",\"x\":0,\"y\":54,\"w\":360,\"h\":700,\"max\":true}]")
            .putString("panel_units", "dp").putLong("layout_epoch", epoch).commit()
        compose.runOnIdle { switch("FILES", 1) }; compose.waitForIdle()
        compose.onNodeWithText("Choose folder").assertExists()
        compose.runOnIdle { switch("SEARCH", 2) }; compose.waitForIdle()
        compose.onNodeWithText("Search games, apps, files and tools").assertTextContains("Kept across layouts")
    }
}
