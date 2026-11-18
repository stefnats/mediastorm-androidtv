package com.strmr.tv.ui.lists

import com.strmr.tv.api.Profile
import com.strmr.tv.api.ContinueWatchingItem
import com.strmr.tv.home.BrowseList
import com.strmr.tv.home.CustomList
import com.strmr.tv.library.CustomListsStore
import com.strmr.tv.library.ListsTabStore
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.library.WatchlistStore
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Existing profile datasets, presented as stable navigation choices. */
object PersonalArea {
    val loved = BrowseList.Remote("personal-loved", "Loved Titles", mapOf("source" to "loved"))

    // The new aggregate does not widen child access through older list APIs.
    fun available(profile: Profile?) = profile != null && profile.isKidsProfile != true

    fun tiles(continueWatching: List<ContinueWatchingItem>?, watchlistCount: Int?, preferenceRevision: Long): List<TileSpec> = listOf(
        TileSpec(BrowseList.ContinueWatching, count = continueWatching?.size,
            art = continueWatching?.take(4)?.let { first -> TileArt(first.mapNotNull { it.textPosterUrl ?: it.posterUrl }, first.mapNotNull { it.backdropUrl }) }),
        TileSpec(BrowseList.Watchlist, title = "My List", count = watchlistCount),
        TileSpec(loved, previewRevision = preferenceRevision),
        // Explicit entry only: no automatic preview request or rating-receipt renewal.
        TileSpec(BrowseList.LovedStored, subtitle = "Complete collection · Original-language filters", art = TileArt()),
        // Recommendations run only after selection, never to decorate this entry.
        TileSpec(BrowseList.Personalized, title = "For You", art = TileArt()),
    )

    fun ownLists(lists: List<CustomList>?): List<TileSpec> = lists.orEmpty()
        .filterNot { it.isSetAside }.distinctBy { it.id }
        .map { TileSpec(BrowseList.Custom(it.id, it.name), count = it.itemCount) }
}

/** Profile-local reads only; existing stores share and isolate their requests. */
suspend fun refreshPersonalArea(context: Long = ProfileDataContext.current) {
    ProfileDataContext.requireCurrent(context)
    coroutineScope {
        launch { ProfileDataContext.requireCurrent(context); WatchlistStore.refresh(expectedContext = context) }
        launch { ProfileDataContext.requireCurrent(context); CustomListsStore.refresh(expectedContext = context) }
        launch { ProfileDataContext.requireCurrent(context); ListsTabStore.refresh(context) }
    }
    ProfileDataContext.requireCurrent(context)
}

/** The real personal/list refresh button lifetime, before deferred effects or clicks. */
class ProfileListRefresh(val context: Long, private val producer: kotlinx.coroutines.Job) {
    private var closed = false
    private var generation = 0L
    private var job: kotlinx.coroutines.Job? = null
    private var busy by androidx.compose.runtime.mutableStateOf(false)
    private var failure by androidx.compose.runtime.mutableStateOf(false)
    val isCurrent get() = !closed && producer.isActive && context == ProfileDataContext.current
    val refreshing get() = isCurrent && busy
    val failed get() = isCurrent && failure
    fun close() { closed = true; generation++; job?.cancel(); job = null }
    suspend fun refresh(read: suspend (Long) -> Unit): Unit = kotlinx.coroutines.coroutineScope {
        if (!isCurrent || busy) return@coroutineScope
        val ticket = ++generation
        fun current() = isCurrent && ticket == generation
        busy = true
        val pending = async(producer, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!current()) throw kotlinx.coroutines.CancellationException("List refresh changed")
                read(context)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (current()) failure = false
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { if (current()) failure = true }
            finally { if (current()) busy = false }
        }
        job = pending
        try { pending.start(); pending.await() } finally {
            pending.cancel()
            if (job === pending) { job = null; if (current()) busy = false }
        }
    }
}
