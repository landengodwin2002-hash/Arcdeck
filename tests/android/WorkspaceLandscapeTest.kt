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
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w840dp-h400dp-land", application = Application::class)
class WorkspaceLandscapeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var catalog: UnifiedCatalog
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("arcdeck_unified_v1.db"); context.getSharedPreferences("arcdeck_workspace", 0).edit().clear().commit()
        catalog = UnifiedCatalog(context)
    }
    @After fun close() { catalog.close() }
    @Test fun landscapeLibraryLeavesRoomForTheAppGridAndWindowSliders() {
        compose.setContent { XmbWorkspaceOverlay(true, {}, {}, {}, emptyList(), {}, { _, _ -> }, Modifier.fillMaxSize()) }
        compose.onNodeWithTag("workspace-tool-LIBRARY").performClick()
        compose.onNodeWithText("Views").assertIsDisplayed().performClick()
        compose.onNodeWithText("Apps", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Arrange window").performClick()
        compose.onNodeWithText("Window width").assertExists()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Toggle window size").performClick()
        compose.onNodeWithContentDescription("Toggle window size").assertExists()
        val directory = System.getProperty("arcdeck.screenshot.dir")
        if (directory != null) {
            val file = File(directory, "landscape-workspace.png"); file.parentFile!!.mkdirs()
            file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun discordUsesNativeSetupInsteadOfAWebsiteWhenShizukuIsUnavailable() {
        compose.setContent { MaterialTheme { WorkspaceNativePane("native:check", "com.discord") } }
        compose.onNodeWithText("Connect native windows").assertExists()
        compose.onNodeWithText("Open / install Shizuku").assertExists()
        compose.onNodeWithText("https://discord.com/app").assertDoesNotExist()
    }
    @Test fun pfpHasAnEditableCommandBarWithAndroidVoiceInput() {
        compose.setContent { MaterialTheme { WorkspaceCommandBar({}, {}, {}) } }
        compose.onNodeWithText("Search or paste a Termux command").performTextInput("printf 'hello'")
        compose.onNodeWithText("Run").performClick()
        compose.onNodeWithText("Run in Termux").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Voice").assertExists()
        compose.onNodeWithText("Papaya / GameHub").assertExists()
    }
}
