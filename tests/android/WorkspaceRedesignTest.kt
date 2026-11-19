package com.playfieldportal.launcher.workspace

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.domain.model.Game
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w840dp-h400dp-land", application = Application::class)
class WorkspaceRedesignTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("arcdeck_workspace", 0).edit().clear().commit()
    }
    private fun desktop() { compose.setContent { XmbWorkspaceOverlay(true, {}, {}, {}, emptyList(), {}, { _, _ -> }, Modifier.fillMaxSize()) } }

    @Test fun theMainMenuLeavesDirectAccessToAppsGamesFilesAndCommands() {
        desktop()
        listOf("GAMES", "APPS", "FILES", "TERMINAL").forEach { tool ->
            compose.onNodeWithTag("workspace-main-actions").performScrollToNode(hasTestTag("workspace-tool-$tool"))
            compose.onNodeWithTag("workspace-tool-$tool").assertIsDisplayed()
        }
        compose.onNodeWithText("Search or paste a Termux command").assertExists()
    }
    @Test fun resizingThenFullscreenReturnsToTheSameWindowAndDraft() {
        val panels = """[{"id":1,"tool":"NOTES","x":12,"y":58,"w":330,"h":260,"instance":"notes-test"}]"""
        context.getSharedPreferences("arcdeck_workspace", 0).edit().putString("panels", panels).putString("panel_units", "dp").commit()
        desktop()
        compose.onNodeWithTag("workspace-main-actions").performScrollToNode(hasTestTag("workspace-tool-NOTES"))
        compose.onNodeWithTag("workspace-tool-NOTES").performClick()
        compose.onNodeWithText("Workspace notes").performTextInput("Keep this draft")
        val before = compose.onNodeWithTag("workspace-panel-NOTES").fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithContentDescription("Resize window").performTouchInput { swipe(center, center + Offset(80f, 0f), 350) }
        compose.waitForIdle()
        val resized = compose.onNodeWithTag("workspace-panel-NOTES").fetchSemanticsNode().boundsInRoot.width
        assertTrue("A real resize gesture must enlarge the frame", resized > before)
        compose.onNodeWithContentDescription("Arrange window").performClick()
        compose.onNodeWithText("Full screen").performClick()
        compose.onNodeWithTag("workspace-dock").assertDoesNotExist()
        compose.onNodeWithTag("fullscreen-home-handle").performClick()
        compose.onNodeWithText("Windowed").performClick()
        compose.onNodeWithTag("workspace-dock").assertExists()
        compose.onNodeWithText("Workspace notes").assertTextContains("Keep this draft")
        assertEquals(resized, compose.onNodeWithTag("workspace-panel-NOTES").fetchSemanticsNode().boundsInRoot.width, 2f)
    }
    @Test fun gameCoverAndPhysicalMediaKeepDifferentShapesAndAssets() {
        val entry = CatalogEntry("game:9", "GAME", "Game", "psp", "9", "pfp")
        val game = Game(id = 9, title = "Game", platformId = "psp", boxArtUri = "cover.png", physicalMediaUri = "disc.png", heroUri = "banner.png")
        val cover = workspaceArt(entry, game, "Cover")
        val media = workspaceArt(entry, game, "Physical media")
        assertEquals("cover.png", cover.uri); assertEquals("disc.png", media.uri)
        assertTrue(cover.ratio < 1); assertEquals(1f, media.ratio, .001f)
        assertEquals("banner.png", workspaceArt(entry, game, "Banner").uri)
    }
    @Test fun renderBudgetPreservesPortraitAndUltraWideAspectRatios() {
        for ((w, h) in listOf(1080 to 2400, 3840 to 2160, 8000 to 2000)) {
            val size = NativeRenderSize.fit(w, h, 420)
            NativeProtocol.dimensions(size.width, size.height, size.density)
            assertEquals(w.toDouble() / h, size.width.toDouble() / size.height, .01)
        }
    }
    @Test fun dockPlacementAndSavedArtSettingsRoundTripWithoutLosingOtherPreferences() {
        val original = WorkspaceAppearance(palette = "Violet", dockPosition = "Right", nativeFullscreen = true)
        val parsed = WorkspaceAppearance.parse(original.json())
        assertEquals("Right", parsed.dockPosition); assertTrue(parsed.nativeFullscreen); assertEquals("Violet", parsed.palette)
        val style = WorkspaceBrowseStyle("Carousel", "Physical media", 180f, true, "Bottom")
        WorkspaceBrowserPreferences.save(context, "Games", style)
        assertEquals(style, WorkspaceBrowserPreferences.get(context, "Games", true))
        assertThrows(IllegalArgumentException::class.java) { WorkspaceBrowseStyle.parse(JSONObject("{\"size\":999999}")) }
    }
    @Test fun spaceArrangementsAndArtworkOptionsSurviveACompleteBackupRestore() {
        val catalog = UnifiedCatalog(context)
        try {
            val store = WorkspaceSpaces.shared(context)
            val first = store.create("Work", "", catalog)
            val second = store.create("Games", "", catalog)
            val one = """[{"id":1,"tool":"TERMINAL","x":4,"y":60,"w":400,"h":250}]"""
            val two = """[{"id":1,"tool":"GAMES","x":50,"y":60,"w":600,"h":300}]"""
            WorkspaceSessions.save(context, first.id, one); WorkspaceSessions.save(context, second.id, two)
            WorkspaceBrowserPreferences.save(context, "Games", WorkspaceBrowseStyle("Carousel", "Cover"))
            val exported = WorkspaceBackup.export(context, catalog)
            context.getSharedPreferences("arcdeck_workspace", 0).edit().clear().commit()
            WorkspaceBackup.restore(context, catalog, exported)
            assertEquals(one, WorkspaceSessions.read(context, first.id)); assertEquals(two, WorkspaceSessions.read(context, second.id))
            assertEquals("Cover", WorkspaceBrowserPreferences.get(context, "Games", true).artwork)
        } finally { catalog.close() }
    }
    @Test fun platformFilteringDoesNotMistakeAGameTitleForItsPlatform() {
        val catalog = UnifiedCatalog(context)
        try {
            catalog.syncPfpGames(listOf(Game(id = 91, title = "Real PSP game", platformId = "psp"), Game(id = 92, title = "PSP guide game", platformId = "ps2")))
            assertEquals(listOf("91"), catalog.search("type:game platform:psp").map { it.target })
            assertTrue(UnifiedQuery.parse("platform:bad/value").invalid.isNotEmpty())
        } finally { catalog.close() }
    }
    @Test fun theCatalogStillShowsItsListInsideAShortLandscapeWindow() {
        compose.setContent { MaterialTheme { Box(Modifier.size(620.dp, 245.dp)) { WorkspacePapayaPane({}) } } }
        compose.waitUntil(15000) { compose.onAllNodesWithText("Android").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Android").assertIsDisplayed()
        compose.onNodeWithText("Platforms").performClick()
        compose.onNodeWithText("Emulators").performClick()
        compose.onNodeWithText("Find platform or emulator").assertIsDisplayed()
    }
    @Test fun importedRecipesPassTheRealLauncherValidator() {
        val raw = context.assets.open("emulator_kb/emulators.json").bufferedReader().use { it.readText() }
        val decoded = com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDecoder.decode(raw)
        assertTrue(decoded is com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDecode.Decoded)
        val document = (decoded as com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDecode.Decoded).document
        val checked = com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbValidator.validate(document,
            com.playfieldportal.feature.launcher.kb.KnownPlatformIds.ALL, context.packageName)
        assertTrue(checked.refusedEmulators.toString(), checked.refusedEmulators.isEmpty())
        assertEquals(6, checked.emulators.count { it.id.startsWith("papaya_") })
    }
    @Test fun paneLocationsAndQueriesAreValidatedBeforeRestore() {
        WorkspaceBackup.validatePanels("""[{"id":1,"tool":"FILES","location":"content://example/tree/games"},{"id":2,"tool":"GAMES","query":"type:game platform:psp"}]""")
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceBackup.validatePanels("""[{"id":1,"tool":"FILES","location":"file:///private/data"}]""")
        }
    }
    @Test fun pinnedPapayaCatalogIncludesTheRealDefinitionsAndCompatibleAdditions() {
        val data = PapayaCatalog.load(context)
        assertEquals(52, data.platforms.size); assertEquals(433, data.emulators.size); assertEquals(14, data.streaming.size)
        assertEquals(6, data.additions)
        assertTrue(data.emulators.any { it.app == "gamehub.lite" && it.core == "steam" })
        assertTrue(data.emulators.any { it.app == "gamehub.lite" && it.core == "local" })
    }
}
