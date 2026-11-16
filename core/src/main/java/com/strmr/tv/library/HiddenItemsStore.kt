package com.strmr.tv.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ApiError
import com.strmr.tv.api.DetailsTarget
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.strmr.tv.app.AppLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** One `/users/{id}/hidden-items` entry: a title hidden from this profile. */
@Serializable
data class HiddenItem(
    val id: String,
    val mediaType: String = "movie",
    val name: String = "",
    val year: Int? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val externalIds: Map<String, String>? = null,
) {
    val isMovie: Boolean get() = mediaType == "movie"
}

/**
 * Titles hidden from this profile (the Expo app's HiddenItemsContext): read once, kept here so
 * Home rows, lists, search and the menus agree, and changed through Hide / Unhide. Main
 * thread only.
 */
object HiddenItemsStore {
    var items by mutableStateOf<List<HiddenItem>>(emptyList())
        private set
    var isLoaded by mutableStateOf(false)
        private set
    /** Bumped by every change, so screens showing titles can drop or restore them. */
    var revision by mutableIntStateOf(0)
        private set
    private val refreshes = ProfileRefresh()
    private var readSequence = 0L

    suspend fun refresh(expectedContext: Long = ProfileDataContext.current) {
        checkOwner(expectedContext)
        refresh { readItems(expectedContext) }
        checkOwner(expectedContext)
    }

    private suspend fun checkOwner(expectedContext: Long) {
        currentCoroutineContext().ensureActive()
        ProfileDataContext.requireCurrent(expectedContext)
    }

    private suspend fun readItems(expectedContext: Long): List<HiddenItem> {
        val path = ApiClient.inContext(expectedContext) { "${ApiClient.profilePath}/hidden-items" }
        return ApiClient.sendList("GET", path, promptOnLock = false, expectedContext = expectedContext)
    }

    /** A page consumes its own immutable response, never another refresh's singleton value. */
    internal suspend fun pageItems(
        expectedContext: Long,
        load: suspend () -> List<HiddenItem> = { readItems(expectedContext) },
    ): List<HiddenItem> {
        checkOwner(expectedContext)
        val sequence = ++readSequence
        val snapshot = load().toList()
        checkOwner(expectedContext)
        if (sequence == readSequence) {
            refreshes.reset()
            items = snapshot
            isLoaded = true
        }
        return snapshot
    }

    internal suspend fun refresh(
        onFailure: (Exception) -> Unit = { legacyBackgroundRefreshFailure() },
        load: suspend () -> List<HiddenItem>,
    ) {
        var sequence = 0L
        refreshes.run(
            load = { sequence = ++readSequence; load() },
            success = { if (sequence == readSequence) { items = it; isLoaded = true } },
            failure = onFailure,
        )
    }

    /** Existing background consumers keep their logging-only failure policy; pages never use it. */
    private fun legacyBackgroundRefreshFailure() {
        AppLog.w("Hidden", "Could not load hidden titles")
    }

    /** Loads once (the first screen that filters asks). */
    suspend fun ensureLoaded() { if (!isLoaded) refresh() }

    fun reset() {
        readSequence++
        refreshes.reset()
        items = emptyList()
        isLoaded = false
        revision++
    }

    fun isHidden(target: DetailsTarget): Boolean = items.any { matches(it, target) }

    /** Hidden by its ID or a shared provider ID of the same kind. */
    fun matches(item: HiddenItem, target: DetailsTarget): Boolean {
        if (item.isMovie != target.isMovie) return false
        if (item.id == target.titleId) return true
        val ids = item.externalIds ?: return false
        return listOf("tmdb" to target.tmdbId, "imdb" to target.imdbId, "tvdb" to target.tvdbId)
            .any { (key, value) -> !value.isNullOrEmpty() && ids[key] == value }
    }

    suspend fun hide(target: DetailsTarget) {
        val context = ProfileDataContext.current
        val body = buildJsonObject {
            put("id", target.titleId)
            put("mediaType", if (target.isMovie) "movie" else "series")
            put("name", target.name)
            target.year?.let { put("year", it) }
            target.posterUrl?.let { put("posterUrl", it) }
            target.backdropUrl?.let { put("backdropUrl", it) }
            val ids = listOfNotNull(target.tmdbId?.let { "tmdb" to it }, target.imdbId?.let { "imdb" to it }, target.tvdbId?.let { "tvdb" to it })
            if (ids.isNotEmpty()) putJsonObject("externalIds") { ids.forEach { (k, v) -> put(k, v) } }
        }
        val item = ApiClient.json.decodeFromString<HiddenItem>(ApiClient.sendRaw("POST", "${ApiClient.profilePath}/hidden-items", body = body))
        ProfileDataContext.requireCurrent(context)
        items = items.filterNot { matches(it, target) } + item
        revision++
        AppLog.i("Hidden", "hid ${target.titleId} (${target.name})")
    }

    /** Unhides every stored entry matching the title (its own media type and ID); a 404 counts as done. */
    suspend fun unhide(target: DetailsTarget) {
        val context = ProfileDataContext.current
        val stored = items.filter { matches(it, target) }.ifEmpty {
            listOf(HiddenItem(target.titleId, if (target.isMovie) "movie" else "series"))
        }
        for (item in stored) {
            ProfileDataContext.requireCurrent(context)
            try {
                ApiClient.sendRaw("DELETE", "${ApiClient.profilePath}/hidden-items/${ApiClient.segment(item.mediaType)}/${ApiClient.segment(item.id)}")
            } catch (e: ApiError) {
                if (e.statusCode != 404) throw e
            }
        }
        ProfileDataContext.requireCurrent(context)
        items = items.filterNot { matches(it, target) }
        revision++
        AppLog.i("Hidden", "unhid ${target.titleId} (${target.name})")
    }
}
