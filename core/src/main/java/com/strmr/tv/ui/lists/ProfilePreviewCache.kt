package com.strmr.tv.ui.lists

import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.library.optionalProfileRead
import com.strmr.tv.api.vodOptionalRead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Shared by main-thread list tiles and background Home hub loads. */
internal class ProfilePreviewCache(
    private val context: () -> Long = { ProfileDataContext.current },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val capacity: Int = 128,
    private val maxAgeMs: Long = 300_000,
) {
    private data class Key(val context: Long, val list: String, val version: Int?, val revision: Long)
    private data class Entry(val art: TileArt, val created: Long)
    private val lock = Any()
    private val items = LinkedHashMap<Key, Entry>(16, 0.75f, true)
    private var generation = 0L

    init { require(capacity > 0 && maxAgeMs > 0) }

    fun reset() = synchronized(lock) { generation++; items.clear() }

    suspend fun art(list: String, version: Int?, revision: Long = 0, expectedContext: Long? = null, load: suspend () -> TileArt?): TileArt {
        val captured = expectedContext ?: context()
        currentCoroutineContext().ensureActive()
        if (expectedContext != null && context() != captured) throw CancellationException("Preview owner changed")
        val key = Key(captured, list, version, revision)
        val stamp = synchronized(lock) {
            if (context() != captured) return TileArt()
            items.entries.removeAll { it.key.context != captured || now() - it.value.created >= maxAgeMs }
            items[key]?.let { return it.art }
            generation
        }
        val loaded = if (expectedContext != null) vodOptionalRead(load) else optionalProfileRead(load)
        currentCoroutineContext().ensureActive()
        if (expectedContext != null && context() != captured) throw CancellationException("Preview owner changed")
        if (loaded == null) return TileArt()
        return synchronized(lock) {
            if (context() != captured || generation != stamp) return TileArt()
            items[key] = Entry(loaded, now())
            while (items.size > capacity) items.remove(items.keys.first())
            loaded
        }
    }
}
