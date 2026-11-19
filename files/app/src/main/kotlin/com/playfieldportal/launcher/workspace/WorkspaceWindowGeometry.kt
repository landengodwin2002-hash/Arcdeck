package com.playfieldportal.launcher.workspace

import kotlin.math.ceil
import kotlin.math.min

internal data class WorkspaceBounds(val x: Float, val y: Float, val width: Float, val height: Float)
internal object WorkspaceWindowGeometry {
    fun tile(index: Int, count: Int, width: Float, height: Float, layout: String): WorkspaceBounds {
        require(count > 0 && index in 0 until count && width > 0 && height > 0)
        val top = 62f; val available = (height - 118f).coerceAtLeast(80f)
        if (layout == "cascade") {
            val stagger = (index % 5) * 20f
            return WorkspaceBounds(stagger, top + min(stagger, available / 4), (width * 0.68f).coerceAtLeast(min(220f, width)), available * 0.75f)
        }
        val proposed = if (layout == "side") min(count, 3) else min(count, if (width >= 1100f) 3 else 2)
        val columns = min(proposed, (width / 220f).toInt().coerceAtLeast(1))
        val rows = ceil(count.toFloat() / columns).toInt()
        val w = width / columns; val h = available / rows
        return WorkspaceBounds((index % columns) * w + 3, top + (index / columns) * h,
            (w - 6).coerceAtLeast(80f), (h - 5).coerceAtLeast(70f))
    }
    fun canonical(tool: String): String = if (tool in setOf("SEARCH", "COLLECTIONS")) "LIBRARY" else tool
}
