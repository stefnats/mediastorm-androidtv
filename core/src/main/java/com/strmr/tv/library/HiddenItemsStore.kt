package com.strmr.tv.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ApiError
import com.strmr.tv.api.DetailsTarget
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
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
    private var storedItems by mutableStateOf<List<HiddenItem>>(emptyList())
    private var itemsOwner by mutableStateOf<Long?>(null)
    val items: List<HiddenItem> get() = storedItems.takeIf { itemsOwner == ProfileDataContext.current }.orEmpty()
    private var loaded by mutableStateOf(false)
    val isLoaded: Boolean get() = loaded && itemsOwner == ProfileDataContext.current
    /** Bumped by every change, so screens showing titles can drop or restore them. */
    var revision by mutableIntStateOf(0)
        private set
    private val refreshes = ProfileRefresh()
    private var readSequence = 0L
    private class Writes(val owner: Long) { val mutex = Mutex(); var pending = 0 }
    private var writes: Writes? = null
    private fun writing() = writes?.let { it.owner == ProfileDataContext.current && it.pending > 0 } == true
    private fun store(items: List<HiddenItem>, owner: Long) {
        storedItems = items.toList(); itemsOwner = owner
    }

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

    /** Only complete unfiltered reads enter the global store; filtered management pages stay separate. */
    internal suspend fun pageItems(
        expectedContext: Long,
        load: suspend () -> List<HiddenItem> = { readItems(expectedContext) },
    ): List<HiddenItem> {
        checkOwner(expectedContext)
        val sequence = ++readSequence
        val snapshot = load().toList()
        checkOwner(expectedContext)
        if (sequence == readSequence && !writing()) {
            refreshes.reset()
            store(snapshot, expectedContext)
            loaded = true
        }
        return snapshot
    }

    internal suspend fun refresh(
        onFailure: (Exception) -> Unit = { legacyBackgroundRefreshFailure() },
        load: suspend () -> List<HiddenItem>,
    ) {
        var sequence = 0L
        val expected = ProfileDataContext.current
        refreshes.run(
            load = { sequence = ++readSequence; load() },
            success = { if (sequence == readSequence && !writing()) { store(it, expected); loaded = true } },
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
        storedItems = emptyList(); itemsOwner = null
        loaded = false; writes = null
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

    private fun mutex(operation: CustomListOperation): Writes = operation.owner.use {
        operation.check()
        writes?.takeIf { it.owner == operation.owner.epoch } ?: Writes(operation.owner.epoch).also { writes = it }
    }

    private suspend fun mutate(target: DetailsTarget, operation: CustomListOperation, action: suspend () -> Unit) {
        operation.requireTarget(target)
        val state = mutex(operation)
        state.mutex.withLock {
            operation.requireTarget(target)
            state.pending++; readSequence++; refreshes.reset()
            try { checkedListStage(operation.owner, operation, action) }
            finally {
                state.pending--
                if (writes === state && operation.owner.isCurrent()) operation.owner.use {
                    readSequence++; refreshes.reset()
                }
            }
        }
    }

    suspend fun hide(target: DetailsTarget, operation: CustomListOperation) = hideOwned(target, operation, ServerHiddenWrites)
    internal suspend fun hideOwned(target: DetailsTarget, operation: CustomListOperation, access: HiddenWrites) = mutate(target, operation) {
        val item = checkedListStage(operation.owner, operation) { access.hide(target, operation) }
        check(matches(item, target)) { "Unexpected hidden title" }
        operation.publish {
            store(items.filterNot { matches(it, target) } + item, operation.owner.epoch)
            revision++
        }
    }

    /** Alias deletes consume the original full-store snapshot; every stage keeps its original owner. */
    suspend fun unhide(target: DetailsTarget, operation: CustomListOperation) = unhideOwned(target, operation, ServerHiddenWrites)
    internal suspend fun unhideOwned(target: DetailsTarget, operation: CustomListOperation, access: HiddenWrites) = mutate(target, operation) {
        val snapshot = items.filter { matches(it, target) }.ifEmpty {
            listOf(HiddenItem(target.titleId, if (target.isMovie) "movie" else "series"))
        }.toList()
        for (item in snapshot) checkedListStage(operation.owner, operation) {
            try { access.delete(item, operation) }
            catch (error: ApiError) { if (error.statusCode != 404) throw error }
        }
        operation.publish {
            store(items.filterNot { matches(it, target) }, operation.owner.epoch)
            revision++
        }
    }
}

internal interface HiddenWrites {
    suspend fun hide(target: DetailsTarget, operation: CustomListOperation): HiddenItem
    suspend fun delete(item: HiddenItem, operation: CustomListOperation)
}

internal data class HiddenRequest(val method: String, val path: String, val body: JsonObject?, val expected: Long)
internal fun hiddenRequest(operation: CustomListOperation, item: HiddenItem? = null, body: JsonObject? = null): HiddenRequest = operation.owner.use {
    operation.check()
    val suffix = item?.let { "/${ApiClient.segment(it.mediaType)}/${ApiClient.segment(it.id)}" }.orEmpty()
    HiddenRequest(if (item == null) "POST" else "DELETE", "${ApiClient.profilePath}/hidden-items$suffix", body, operation.owner.epoch)
}
private object ServerHiddenWrites : HiddenWrites {
    override suspend fun hide(target: DetailsTarget, operation: CustomListOperation): HiddenItem {
        operation.requireTarget(target)
        val body = buildJsonObject {
            put("id", target.titleId)
            put("mediaType", if (target.isMovie) "movie" else "series")
            put("name", target.name)
            target.year?.let { put("year", it) }
            target.posterUrl?.let { put("posterUrl", it) }
            target.backdropUrl?.let { put("backdropUrl", it) }
            val ids = listOfNotNull(target.tmdbId?.let { "tmdb" to it }, target.imdbId?.let { "imdb" to it }, target.tvdbId?.let { "tvdb" to it })
            if (ids.isNotEmpty()) putJsonObject("externalIds") { ids.forEach { (key, value) -> put(key, value) } }
        }
        val request = hiddenRequest(operation, body = body)
        return ApiClient.send(request.method, request.path, body = request.body, expectedContext = request.expected)
    }
    override suspend fun delete(item: HiddenItem, operation: CustomListOperation) {
        val request = hiddenRequest(operation, item)
        ApiClient.sendRaw(request.method, request.path, expectedContext = request.expected)
    }
}
