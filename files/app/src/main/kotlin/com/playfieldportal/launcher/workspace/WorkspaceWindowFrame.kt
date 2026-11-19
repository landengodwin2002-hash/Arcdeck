package com.playfieldportal.launcher.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun WorkspaceWindowFrame(title: String, symbol: String, fullscreen: Boolean, maximized: Boolean,
    modifier: Modifier, corners: Float = 12f, onArrange: () -> Unit, onMinimize: () -> Unit, onMaximize: () -> Unit,
    onFullscreen: () -> Unit, onClose: () -> Unit, onMove: (Offset) -> Unit, onResize: (Offset) -> Unit,
    content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.then(if (fullscreen) Modifier.background(colors.background.copy(alpha = 1f)) else Modifier
        .clip(RoundedCornerShape(corners.dp)).background(colors.surface).border(1.dp, colors.primary.copy(alpha = .4f), RoundedCornerShape(corners.dp)))) {
        val wide = maxWidth >= 480.dp
        Column(Modifier.fillMaxSize()) {
            if (!fullscreen) Row(Modifier.fillMaxWidth().height(48.dp).testTag("workspace-window-title")
                .background(colors.surface).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).fillMaxHeight()
                    .pointerInput(maximized) { if (!maximized) detectDragGestures { change, delta -> change.consume(); onMove(delta) } }, contentAlignment = Alignment.CenterStart) {
                    Text("$symbol  $title", color = colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                WindowButton("⋮", "Arrange window", onArrange)
                if (!maximized) Box(Modifier.size(44.dp)
                    .semantics { contentDescription = "Resize window" }
                    .pointerInput(Unit) { detectDragGestures { change, delta -> change.consume(); onResize(delta) } }, contentAlignment = Alignment.Center) {
                    Text("↘", color = colors.primary, fontSize = 20.sp)
                }
                if (wide) WindowButton("—", "Minimize window", onMinimize)
                WindowButton(if (maximized) "▢" else "□", "Toggle window size", onMaximize)
                if (wide) WindowButton("⛶", "Full screen window", onFullscreen)
                WindowButton("×", "Close window", onClose)
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(if (fullscreen) 0.dp else 6.dp).padding(bottom = if (fullscreen) 24.dp else 0.dp)) { content() }
        }
        // Edge drags supplement the large title-bar resize handle without covering app controls.
        if (!fullscreen && !maximized) {
            Box(Modifier.align(Alignment.CenterEnd).width(6.dp).fillMaxHeight().padding(top = 48.dp)
                .pointerInput(Unit) { detectDragGestures { change, delta -> change.consume(); onResize(Offset(delta.x, 0f)) } })
            Box(Modifier.align(Alignment.BottomCenter).height(6.dp).fillMaxWidth()
                .pointerInput(Unit) { detectDragGestures { change, delta -> change.consume(); onResize(Offset(0f, delta.y)) } })
        }
    }
}

@Composable private fun WindowButton(label: String, description: String, action: () -> Unit) {
    Box(Modifier.size(44.dp).semantics { contentDescription = description }.clickable(onClick = action), contentAlignment = Alignment.Center) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp)
    }
}
