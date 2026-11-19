package com.playfieldportal.launcher.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal object WorkspaceIndexing {
    private val running = AtomicBoolean(false)
    private val cancellation = AtomicLong(0)
    fun cancel() { cancellation.incrementAndGet() }
    private var last = 0L
    private val progress = MutableStateFlow("")
    val status = progress.asStateFlow()
    suspend fun refresh(catalog: UnifiedCatalog, force: Boolean = false) {
        if (!running.compareAndSet(false, true)) return
        try {
            if (!force && System.currentTimeMillis() - last < 300_000) return
            val scanEpoch = cancellation.get()
            withContext(Dispatchers.IO) {
                val scanContext = currentCoroutineContext()
                val roots = catalog.roots()
                for ((i, root) in roots.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    if (cancellation.get() != scanEpoch) break
                    progress.value = "Updating folder ${i + 1}/${roots.size}: ${root.label}"
                    catalog.scanRoot(android.net.Uri.parse(root.uri), cancelled = { !scanContext.isActive || cancellation.get() != scanEpoch })
                }
                if (cancellation.get() == scanEpoch) last = System.currentTimeMillis()
            }
        } finally { progress.value = ""; running.set(false) }
    }
}
