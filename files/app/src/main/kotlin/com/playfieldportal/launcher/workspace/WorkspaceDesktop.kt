package com.playfieldportal.launcher.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.playfieldportal.core.domain.model.Game

internal data class DesktopInsets(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f)
internal object WorkspaceDesktopGeometry {
    fun insets(position: String, visible: Boolean, width: Float): DesktopInsets {
        if (!visible) return DesktopInsets()
        val side = minOf(152f, width * .32f)
        return when (position) {
            "Left" -> DesktopInsets(left = side)
            "Right" -> DesktopInsets(right = side)
            "Bottom" -> DesktopInsets(bottom = 56f)
            else -> DesktopInsets(top = 56f)
        }
    }
    fun tile(index: Int, count: Int, width: Float, height: Float, layout: String, inset: DesktopInsets): WorkspaceBounds {
        val w = (width - inset.left - inset.right).coerceAtLeast(100f)
        val h = (height - inset.top - inset.bottom).coerceAtLeast(100f)
        val columns = if (layout == "side") minOf(count, maxOf(1, (w / 260).toInt())) else minOf(count, if (w >= 1000) 3 else if (w >= 520) 2 else 1)
        if (layout == "cascade") {
            val delta = (index % 5) * 18f
            return WorkspaceBounds(inset.left + delta, inset.top + minOf(delta, h * .15f), maxOf(180f, w * .72f).coerceAtMost(w - delta), h * .78f)
        }
        val rows = (count + columns - 1) / columns
        return WorkspaceBounds(inset.left + (index % columns) * w / columns + 3, inset.top + (index / columns) * h / rows + 3,
            (w / columns - 6).coerceAtLeast(80f), (h / rows - 6).coerceAtLeast(80f))
    }
}

internal data class DesktopAction(val id: String, val title: String, val symbol: String)

@Composable internal fun WorkspaceMainMenu(actions: List<DesktopAction>, games: List<Game>, spaces: List<AppSpace>,
    onOpen: (String) -> Unit, onSelectSpace: (String) -> Unit, onGame: (Long) -> Unit,
    onSearch: (String) -> Unit, onTerminal: (String) -> Unit, pfpSections: List<Pair<String, String>>, onPfp: (String) -> Unit) {
    val style = LocalWorkspaceAppearance.current
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp).testTag("workspace-main-menu"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("ArcDeck", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("  Your apps, games and tools", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onOpen("CUSTOMIZE") }) { Text("Edit menu") }
        }
        WorkspaceCommandBar(onSearch, onTerminal, onOpen, showShortcuts = false)
        if (spaces.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(spaces, key = { it.id }) { space -> AssistChip(onClick = { onSelectSpace(space.id) }, label = { Text(space.name) }) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val showArt = maxWidth >= 760.dp
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyVerticalGrid(GridCells.Adaptive(if (style.compact) 118.dp else 150.dp), Modifier.weight(1f).fillMaxHeight().testTag("workspace-main-actions"),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(actions, key = { it.id }) { action ->
                    Column(Modifier.fillMaxWidth().heightIn(min = if (style.compact) 76.dp else 90.dp)
                        .clip(RoundedCornerShape(style.corners.dp)).background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, style.accent.copy(alpha = .22f), RoundedCornerShape(style.corners.dp))
                        .testTag("workspace-tool-${action.id}").clickable { onOpen(action.id) }.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(action.symbol, style = MaterialTheme.typography.titleLarge, color = style.accent)
                        Text(action.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (pfpSections.isNotEmpty()) item {
                    TextButton(onClick = { onPfp("category:settings") }) { Text("Themes, sounds and console settings") }
                }
            }
            BoxWithConstraints(Modifier.fillMaxHeight()) {
                // Artwork is a useful companion on wide screens; small displays keep the tool board's space.
                val recent = games.filterNot { it.isMissing }.sortedByDescending { it.lastPlayedAt ?: 0 }.take(1)
                if (showArt && recent.isNotEmpty() && actions.isNotEmpty()) {
                    val game = recent.first()
                    Column(Modifier.width(176.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AsyncImage(game.heroUri ?: game.artworkUri ?: game.boxArtUri ?: game.iconUri, null,
                            Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Crop)
                        Text(game.displayTitle, Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.titleSmall, maxLines = 2)
                        TextButton(onClick = { onGame(game.id) }, modifier = Modifier.fillMaxWidth()) { Text("Game details") }
                    }
                }
            }
        }
        }
    }
}

@Composable internal fun WorkspaceDesktopBar(position: String, modifier: Modifier, panels: List<Pair<Int, String>>, active: Int?,
    onMenu: () -> Unit, onFocus: (Int) -> Unit, onCommand: () -> Unit, onArrange: (String) -> Unit, onEdit: () -> Unit) {
    var arrange by remember { mutableStateOf(false) }
    val side = position in setOf("Left", "Right")
    val surface = Modifier.background(MaterialTheme.colorScheme.surface).testTag("workspace-dock")
    @Composable fun menuButton() { TextButton(onClick = onMenu, modifier = Modifier.heightIn(min = 48.dp).testTag("workspace-menu-button")) { Text("◈ Menu") } }
    @Composable fun commandButton() { TextButton(onClick = onCommand, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Search or run a command" }) { Text(">_ Command") } }
    @Composable fun arrangeButton() { Box {
        TextButton(onClick = { arrange = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Arrange") }
        DropdownMenu(arrange, { arrange = false }) {
            listOf("side" to "Side by side", "grid" to "Grid", "cascade" to "Cascade").forEach { (id, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { arrange = false; onArrange(id) })
            }
        }
    } }
    if (side) Column(modifier.then(surface).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        menuButton()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { panels.forEach { (id, title) ->
            TextButton(onClick = { onFocus(id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(title, color = if (id == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 2)
            }
        } }
        commandButton(); arrangeButton(); TextButton(onClick = onEdit) { Text("Customize") }
    } else Row(modifier.then(surface).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        menuButton()
        WorkspaceScrollableRow(Modifier.weight(1f)) { panels.forEach { (id, title) ->
            TextButton(onClick = { onFocus(id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(title, color = if (id == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1)
            }
        } }
        commandButton(); arrangeButton(); TextButton(onClick = onEdit) { Text("Edit") }
    }
}
