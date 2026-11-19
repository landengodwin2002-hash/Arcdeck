package com.playfieldportal.launcher.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A small reserved home strip: native app touches never collide with its return gesture. */
@Composable internal fun WorkspaceFullscreenControls(onPfp: () -> Unit, onWorkspace: () -> Unit,
    onWindowed: () -> Unit, onArrange: () -> Unit, onClose: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val threshold = with(LocalDensity.current) { 36.dp.toPx() }
    val home by rememberUpdatedState(onPfp)
    BackHandler(menu) { menu = false }
    Box(Modifier.fillMaxSize().zIndex(1_000_000f)) {
        if (menu) {
            Box(Modifier.fillMaxSize().clickable { menu = false })
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp, start = 10.dp, end = 10.dp)
                .widthIn(max = 420.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
                .padding(8.dp).testTag("fullscreen-controls")) {
                Text("App controls", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { menu = false; onPfp() }) { Text("Main menu") }
                    TextButton(onClick = { menu = false; onWorkspace() }) { Text("Workspace") }
                    TextButton(onClick = { menu = false; onWindowed() }) { Text("Windowed") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { menu = false; onArrange() }) { Text("Size / app options") }
                    TextButton(onClick = { menu = false; onClose() }) { Text("Close window") }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp).background(Color.Black), contentAlignment = Alignment.Center) {
            Box(Modifier.width(104.dp).fillMaxHeight().testTag("fullscreen-home-handle")
                .semantics { contentDescription = "Swipe up for ArcDeck; tap for app controls" }
                .pointerInput(Unit) { detectTapGestures(onTap = { menu = !menu }) }
                .pointerInput(threshold) {
                    var distance = 0f
                    detectVerticalDragGestures(onDragStart = { distance = 0f }, onVerticalDrag = { change, dy -> change.consume(); distance += dy },
                        onDragEnd = { if (distance < -threshold) { menu = false; home() } }, onDragCancel = { distance = 0f })
                }, contentAlignment = Alignment.Center) {
                Box(Modifier.width(44.dp).height(4.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = .8f)))
            }
        }
    }
}
