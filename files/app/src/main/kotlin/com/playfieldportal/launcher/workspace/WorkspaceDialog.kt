package com.playfieldportal.launcher.workspace

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** Explicit responsive sizing also avoids platform-default window-width oscillation in Android UI tests. */
@Composable
internal fun WorkspaceDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    AlertDialog(onDismissRequest = onDismissRequest, confirmButton = confirmButton,
        dismissButton = dismissButton, title = title, text = text,
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.94f),
        properties = DialogProperties(usePlatformDefaultWidth = false))
}
