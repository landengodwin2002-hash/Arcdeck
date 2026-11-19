package com.playfieldportal.launcher.workspace

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable internal fun WorkspaceScrollableRow(modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start, content: @Composable RowScope.() -> Unit) {
    val state = rememberScrollState(); val scope = rememberCoroutineScope()
    Row(modifier, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        TextButton(enabled = state.value > 0, onClick = { scope.launch { state.animateScrollBy(-240f) } }, modifier = Modifier.width(40.dp)) { Text("‹") }
        Row(Modifier.weight(1f).horizontalScroll(state).pointerInput(state) {
            awaitPointerEventScope { while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Scroll) {
                    val delta = event.changes.sumOf { (it.scrollDelta.x + it.scrollDelta.y).toDouble() }.toFloat()
                    state.dispatchRawDelta(delta * 40f); event.changes.forEach { it.consume() }
                }
            } }
        }, horizontalArrangement = horizontalArrangement, content = content)
        TextButton(enabled = state.value < state.maxValue, onClick = { scope.launch { state.animateScrollBy(240f) } }, modifier = Modifier.width(40.dp)) { Text("›") }
    }
}
