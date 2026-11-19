package com.playfieldportal.launcher.workspace

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.AsyncImage
import com.playfieldportal.core.domain.model.Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class WorkspaceBrowseStyle(val mode: String = "Grid", val artwork: String = "Auto", val size: Float = 138f,
    val titleAbove: Boolean = false, val alignment: String = "Center") {
    fun json(): JSONObject = JSONObject().put("mode", mode).put("artwork", artwork).put("size", size.toDouble())
        .put("titleAbove", titleAbove).put("alignment", alignment)
    companion object {
        val modes = listOf("Carousel", "Grid", "List")
        val art = listOf("Auto", "Cover", "Icon", "Physical media", "3D box", "Banner")
        fun parse(j: JSONObject): WorkspaceBrowseStyle {
            val mode = j.optString("mode", "Grid"); require(mode in modes)
            val artwork = j.optString("artwork", "Auto"); require(artwork in art)
            val size = j.optDouble("size", 138.0); require(size.isFinite() && size in 84.0..240.0)
            val alignment = j.optString("alignment", "Center"); require(alignment in setOf("Top", "Center", "Bottom"))
            return WorkspaceBrowseStyle(mode, artwork, size.toFloat(), j.optBoolean("titleAbove"), alignment)
        }
    }
}

internal object WorkspaceBrowserPreferences {
    fun get(context: Context, key: String, gameView: Boolean): WorkspaceBrowseStyle {
        val root = JSONObject(context.getSharedPreferences("arcdeck_workspace", 0).getString("library_views", "{}"))
        return root.optJSONObject(key)?.let(WorkspaceBrowseStyle::parse) ?: WorkspaceBrowseStyle(mode = if (gameView) "Carousel" else "Grid")
    }
    fun save(context: Context, key: String, style: WorkspaceBrowseStyle) {
        val prefs = context.getSharedPreferences("arcdeck_workspace", 0)
        val root = JSONObject(prefs.getString("library_views", "{}")); root.put(key, style.json())
        validate(root.toString()); prefs.edit().putString("library_views", root.toString()).apply()
    }
    fun validate(raw: String) {
        require(raw.length <= 32000)
        val root = JSONObject(raw); require(root.length() <= 40)
        root.keys().forEach { key -> require(key.length <= 80); WorkspaceBrowseStyle.parse(root.getJSONObject(key)) }
    }
}

internal data class WorkspaceArt(val uri: String?, val ratio: Float, val fit: Boolean)
internal fun workspaceArt(entry: CatalogEntry, game: Game?, choice: String): WorkspaceArt {
    if (game == null) return WorkspaceArt(entry.artwork, if (entry.kind == "APP") 1f else 1.5f, entry.kind == "APP")
    val resolved = if (choice == "Auto") when {
        game.iconDisplayMode == "PHYSICAL_MEDIA" -> "Physical media"
        game.iconDisplayMode == "BOX_3D" -> "3D box"
        game.iconDisplayMode == "ICON0" -> "Icon"
        game.boxArtUri != null -> "Cover"
        else -> "Icon"
    } else choice
    return when (resolved) {
        "Cover" -> WorkspaceArt(game.boxArtUri ?: game.box3dUri ?: game.iconUri ?: entry.artwork, 2f / 3f, true)
        "Physical media" -> WorkspaceArt(game.physicalMediaUri ?: game.iconUri ?: entry.artwork, 1f, true)
        "3D box" -> WorkspaceArt(game.box3dUri ?: game.boxArtUri ?: game.iconUri ?: entry.artwork, .75f, true)
        "Banner" -> WorkspaceArt(game.heroUri ?: game.artworkUri ?: game.iconUri ?: entry.artwork, 2.2f, false)
        else -> WorkspaceArt(game.iconUri ?: game.boxArtUri ?: entry.artwork, 1.8f, true)
    }
}

@Composable internal fun WorkspaceEntryArtwork(entry: CatalogEntry, game: Game?, choice: String, modifier: Modifier) {
    val art = workspaceArt(entry, game, choice)
    val box = modifier.clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
    if (art.uri != null) AsyncImage(art.uri, null, box, contentScale = if (art.fit) ContentScale.Fit else ContentScale.Crop)
    else if (entry.kind == "APP") {
        val context = LocalContext.current
        val icon by produceState<android.graphics.Bitmap?>(null, entry.target) {
            value = withContext(Dispatchers.IO) { runCatching { context.packageManager.getApplicationIcon(entry.target).toBitmap(128, 128) }.getOrNull() }
        }
        if (icon != null) Image(icon!!.asImageBitmap(), null, box.padding(12.dp), contentScale = ContentScale.Fit)
        else Box(box, contentAlignment = Alignment.Center) { Text("▦", style = MaterialTheme.typography.headlineMedium) }
    } else Box(box, contentAlignment = Alignment.Center) {
        Text(when (entry.kind) { "GAME" -> "▶"; "FOLDER" -> "▣"; "COMMAND" -> ">_"; "TOOL" -> "⚙"; else -> "◇" }, style = MaterialTheme.typography.headlineMedium)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun WorkspaceLibraryGallery(entries: List<CatalogEntry>, games: Map<Long, Game>, style: WorkspaceBrowseStyle,
    modifier: Modifier, hasMore: Boolean, onMore: () -> Unit, onOpen: (CatalogEntry) -> Unit, onOptions: (CatalogEntry) -> Unit) {
    @Composable fun card(entry: CatalogEntry, artModifier: Modifier, cardModifier: Modifier) {
        val game = entry.target.toLongOrNull()?.let(games::get)
        Column(cardModifier.combinedClickable(onClick = { onOpen(entry) }, onLongClick = { onOptions(entry) }).padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            @Composable fun title() { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(entry.alias ?: entry.title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { onOptions(entry) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp)) { Text("⋮") }
            } }
            if (style.titleAbove) title()
            WorkspaceEntryArtwork(entry, game, style.artwork, artModifier)
            if (!style.titleAbove) title()
        }
    }
    if (style.mode == "Carousel") BoxWithConstraints(modifier) {
        val artHeight = (maxHeight - 74.dp).coerceIn(48.dp, 280.dp)
        LazyRow(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = when (style.alignment) {
            "Top" -> Alignment.Top; "Bottom" -> Alignment.Bottom; else -> Alignment.CenterVertically
        }) {
            items(entries, key = { it.key }) { entry ->
                val ratio = workspaceArt(entry, entry.target.toLongOrNull()?.let(games::get), style.artwork).ratio
                val width = minOf(style.size.dp * 1.6f, artHeight * ratio).coerceAtLeast(84.dp)
                card(entry, Modifier.fillMaxWidth().height(artHeight), Modifier.width(width))
            }
            if (hasMore) item { TextButton(onClick = onMore) { Text("Load more") } }
        }
    } else LazyVerticalGrid(GridCells.Adaptive(style.size.dp), modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(entries, key = { it.key }) { entry ->
            val ratio = workspaceArt(entry, entry.target.toLongOrNull()?.let(games::get), style.artwork).ratio
            card(entry, Modifier.fillMaxWidth().aspectRatio(ratio), Modifier.fillMaxWidth())
        }
        if (hasMore) item { TextButton(onClick = onMore) { Text("Load more") } }
    }
}
