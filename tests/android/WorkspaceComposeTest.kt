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
        compose.onNodeWithText("⌂  Change default home app").assertExists()
        val directory = System.getProperty("arcdeck.screenshot.dir")
        if (directory != null) {
            val file = File(directory, "workspace-phone.png"); file.parentFile!!.mkdirs()
            file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
