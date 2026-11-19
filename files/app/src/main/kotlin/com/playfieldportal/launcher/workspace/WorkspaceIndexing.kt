package com.playfieldportal.launcher.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal object WorkspaceIndexing {
    private val running = AtomicBoolean(false)
    private var last = 0L
    private val progress = MutableStateFlow("")
    val status = progress.asStateFlow()
    suspend fun refresh(catalog: UnifiedCatalog, force: Boolean = false) {
        if (!running.compareAndSet(false, true)) return
        try {
            if (!force && System.currentTimeMillis() - last < 300_000) return
            withContext(Dispatchers.IO) {
                val scanContext = currentCoroutineContext()
                val roots = catalog.roots()
                for ((i, root) in roots.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    progress.value = "Updating folder ${i + 1}/${roots.size}: ${root.label}"
                    catalog.scanRoot(android.net.Uri.parse(root.uri), cancelled = { !scanContext.isActive })
                }
                last = System.currentTimeMillis()
            }
        } finally { progress.value = ""; running.set(false) }
    }
}
