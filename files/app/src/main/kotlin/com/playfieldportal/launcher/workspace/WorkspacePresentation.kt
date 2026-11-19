package com.playfieldportal.launcher.workspace

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** MainActivity reads this only to apply the current in-app system-bar presentation. */
object WorkspacePresentation {
    private val updates = MutableStateFlow(false)
    val changes = updates.asStateFlow()
    val immersive: Boolean get() = updates.value
    internal fun setImmersive(value: Boolean) { updates.value = value }
}

internal data class NativeRenderSize(val width: Int, val height: Int, val density: Int) {
    companion object {
        /** Keep one aspect ratio for the display, buffer and input coordinates, including 4K/portrait. */
        fun fit(width: Int, height: Int, screenDensity: Int, appScale: Float = 1f): NativeRenderSize {
            require(width > 0 && height > 0 && appScale.isFinite() && appScale in 0.75f..1.25f)
            val scale = min(1.0, min(2560.0 / maxOf(width, height), sqrt(4_000_000.0 / (width.toDouble() * height))))
            val w = (width * scale).toInt(); val h = (height * scale).toInt()
            require(w >= 128 && h >= 128) { "Enlarge this window to use the installed app" }
            return NativeRenderSize(w, h, (screenDensity * scale * appScale).roundToInt().coerceIn(120, 640))
        }
    }
}
