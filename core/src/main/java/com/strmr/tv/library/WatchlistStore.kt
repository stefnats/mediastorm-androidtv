package com.strmr.tv.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ApiError
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.vodDenial
import com.strmr.tv.home.WatchlistItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The profile's watchlist, shared by the Watchlist tab, long-press menus and the details
 * toggle so a change in one shows everywhere. Writes update the list optimistically, then
 * re-read the server's copy (canonical IDs, artwork, release badges). Main thread only.
 */
object WatchlistStore {
    private var storedItems by mutableStateOf<List<WatchlistItem>>(emptyList())
    private var itemsOwner by mutableStateOf<Long?>(null)
    private var loaded by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private data class Override(
        val target: DetailsTarget,
        val saved: Boolean,
        val operation: CustomListOperation?,
        val confirmed: Boolean,
        val job: kotlinx.coroutines.Job? = null,
        var active: Boolean = true,
    ) {
        fun current() = confirmed || active && job?.isActive != false && runCatching { operation?.check(); operation != null }.getOrDefault(false)
    }
    private val overrides = mutableStateMapOf<String, Override>()
    private class Writes(val owner: Long) {
        val mutex = kotlinx.coroutines.sync.Mutex()
        var revision = 0L
        var pending = 0
    }
    private var writes: Writes? = null
    private var readSequence = 0L
    private val refreshes = ProfileRefresh()
    private fun owned() = itemsOwner == ProfileDataContext.current
    val items: List<WatchlistItem> get() = if (!owned()) emptyList() else storedItems.filterNot { item ->
        overrides.values.any { !it.saved && it.current() && matches(item, it.target) }
    }
    val isLoaded: Boolean get() = owned() && loaded
    val loadError: String? get() = error.takeIf { owned() }
    private fun state(expected: Long): Writes {
        ProfileDataContext.requireCurrent(expected)
        return writes?.takeIf { it.owner == expected } ?: Writes(expected).also { writes = it }
    }
    private fun store(items: List<WatchlistItem>, expected: Long) {
        if (itemsOwner != expected) { overrides.clear(); loaded = false; error = null }
        storedItems = items.toList(); itemsOwner = expected
        // This endpoint omits hidden/unreleased titles. Presence may reconcile membership,
        // but absence never reverses a previously accepted positive write.
        for ((key, previous) in overrides.toMap()) {
            if (previous.confirmed) overrides[key] = previous.copy(saved = previous.saved || items.any { matches(it, previous.target) })
            else if (!previous.current()) overrides.remove(key)
        }
    }

    suspend fun refresh() {
        val expected = ProfileDataContext.current
        refresh { ServerWatchlistAccess.readLegacy(expected) }
    }

    /** Existing shell/TV/launcher background callers keep visible errors rather than uncaught failures.
     * Owned mutation follow-up reads never use this legacy adapter and remain strict. */
    internal suspend fun refresh(load: suspend () -> List<WatchlistItem>) {
        val expected = ProfileDataContext.current
        val state = state(expected)
        val revision = state.revision
        var sequence = 0L
        fun current() = expected == ProfileDataContext.current && sequence == readSequence && revision == state.revision && state.pending == 0
        refreshes.run(load = {
            sequence = ++readSequence
            load()
        }, success = {
            if (current()) { store(it, expected); loaded = true; error = null }
        }, failure = {
            if (current() && (!isLoaded || items.isEmpty())) {
                if (itemsOwner != expected) { storedItems = emptyList(); overrides.clear(); itemsOwner = expected; loaded = false }
                error = "Could not load your watchlist. Try again."
                loaded = true // Existing TV callers distinguish a finished attempt from success using loadError.
            }
        })
    }

    fun reset() {
        readSequence++; refreshes.reset(); writes = null
        storedItems = emptyList(); itemsOwner = null; loaded = false; error = null; overrides.clear()
    }

    fun contains(target: DetailsTarget, server: Boolean? = null): Boolean =
        overrides.values.lastOrNull { owned() && it.current() && overlaps(it.target, target) }?.saved ?: server ?: items.any { matches(it, target) }

    /** Desired state was selected synchronously by the original button/menu, never toggled again after waiting. */
    suspend fun setSaved(target: DetailsTarget, saved: Boolean, operation: CustomListOperation) =
        setSavedOwned(target, saved, operation, ServerWatchlistAccess)

    suspend fun add(target: DetailsTarget, operation: CustomListOperation) = setSaved(target, true, operation)

    internal suspend fun setSavedOwned(target: DetailsTarget, saved: Boolean, operation: CustomListOperation, access: WatchlistAccess) {
        operation.requireTarget(target)
        val state = state(operation.owner.epoch)
        state.mutex.withLock {
            operation.requireTarget(target)
            state.revision++; state.pending++; readSequence++; refreshes.reset()
            val previous = overrides.filterValues { owned() && overlaps(it.target, target) }
            if (!owned()) store(emptyList(), operation.owner.epoch)
            val job = currentCoroutineContext()[kotlinx.coroutines.Job]
            val optimistic = Override(target, saved, operation, false, job)
            var accepted = false
            operation.publish {
                previous.keys.forEach(overrides::remove)
                overrides[target.titleId] = optimistic
            }
            try {
                checkedListStage(operation.owner, operation) {
                    try { access.write(target, saved, operation) }
                    catch (error: ApiError) { if (saved || error.statusCode != 404) throw error }
                }
                accepted = true
                val fresh = checkedListStage(operation.owner, operation) { access.read(operation.owner.epoch) }
                operation.publish {
                    store(fresh, operation.owner.epoch)
                    overrides[target.titleId] = Override(target, saved, null, true)
                    loaded = true; error = null
                }
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                operation.check()
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                if (accepted) {
                    if (failure.vodDenial != null) throw failure
                    operation.publish { overrides[target.titleId] = Override(target, saved, null, true) }
                    throw WatchlistRefreshFailure()
                }
                operation.publish { overrides.remove(target.titleId); overrides.putAll(previous) }
                throw failure
            } finally {
                optimistic.active = false
                state.pending--; state.revision++
                if (writes === state && operation.owner.isCurrent()) { readSequence++; refreshes.reset() }
            }
        }
    }

    private fun overlaps(first: DetailsTarget, second: DetailsTarget): Boolean = first.isMovie == second.isMovie &&
        (first.titleId == second.titleId || listOf(first.tmdbId to second.tmdbId, first.imdbId to second.imdbId, first.tvdbId to second.tvdbId)
            .any { (a, b) -> !a.isNullOrEmpty() && a == b })

    /** The body of `POST /watchlist` and of adding to a custom list; the server rejects unknown fields. */
    fun upsert(t: DetailsTarget) = buildJsonObject {
        put("id", t.titleId)
        put("mediaType", if (t.isMovie) "movie" else "series")
        put("name", t.name)
        t.overview?.let { put("overview", it) }
        t.year?.let { put("year", it) }
        t.posterUrl?.let { put("posterUrl", it) }
        t.textPosterUrl?.let { put("textPosterUrl", it) }
        t.backdropUrl?.let { put("backdropUrl", it) }
        val ids = listOfNotNull(t.tmdbId?.let { "tmdb" to it }, t.imdbId?.let { "imdb" to it }, t.tvdbId?.let { "tvdb" to it })
        if (ids.isNotEmpty()) putJsonObject("externalIds") { ids.forEach { (k, v) -> put(k, v) } }
    }

    /** Fallback match for servers without `inWatchlist`: the same ID, or a shared provider ID of the same kind. */
    fun matches(item: WatchlistItem, target: DetailsTarget): Boolean {
        if (item.isMovie != target.isMovie) return false
        if (item.id == target.titleId) return true
        val ids = item.externalIds ?: return false
        return listOf("tmdb" to target.tmdbId, "imdb" to target.imdbId, "tvdb" to target.tvdbId)
            .any { (key, value) -> !value.isNullOrEmpty() && ids[key] == value }
    }

}

internal interface WatchlistAccess {
    suspend fun write(target: DetailsTarget, saved: Boolean, operation: CustomListOperation)
    suspend fun read(expectedContext: Long): List<WatchlistItem>
}
internal data class WatchlistRequest(val method: String, val path: String, val body: JsonObject?, val expected: Long)
internal fun watchlistRequest(target: DetailsTarget, saved: Boolean, operation: CustomListOperation): WatchlistRequest = operation.owner.use {
    operation.requireTarget(target)
    val suffix = if (saved) "" else "/${if (target.isMovie) "movie" else "series"}/${ApiClient.segment(target.titleId)}"
    WatchlistRequest(if (saved) "POST" else "DELETE", "${ApiClient.profilePath}/watchlist$suffix", if (saved) WatchlistStore.upsert(target) else null, operation.owner.epoch)
}
private object ServerWatchlistAccess : WatchlistAccess {
    override suspend fun write(target: DetailsTarget, saved: Boolean, operation: CustomListOperation) {
        val request = watchlistRequest(target, saved, operation)
        ApiClient.sendRaw(request.method, request.path, body = request.body, expectedContext = request.expected)
    }
    override suspend fun read(expectedContext: Long): List<WatchlistItem> = decodeOwnedWatchlist(readRaw(expectedContext))
    suspend fun readLegacy(expectedContext: Long): List<WatchlistItem> =
        ApiClient.json.decodeFromString<com.strmr.tv.api.DisplayListPage<WatchlistItem>>(readRaw(expectedContext)).items
    private suspend fun readRaw(expectedContext: Long): String {
        val path = ApiClient.inContext(expectedContext) { "${ApiClient.profilePath}/display-list" }
        val raw = ApiClient.sendRaw("GET", path, mapOf("source" to "watchlist"), expectedContext = expectedContext)
        currentCoroutineContext().ensureActive(); ProfileDataContext.requireCurrent(expectedContext)
        return raw
    }
}

/** A confirmed write never adopts a lossy or default-empty response as a successful readback. */
internal fun decodeOwnedWatchlist(raw: String): List<WatchlistItem> {
    val root = ApiClient.json.parseToJsonElement(raw) as? JsonObject ?: error("Invalid watchlist response")
    require((root["source"] as? JsonPrimitive)?.takeIf { it.isString }?.content == "watchlist") { "Invalid watchlist source" }
    val rows = root["items"] as? JsonArray ?: error("Invalid watchlist rows")
    val total = (root["total"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    require(total != null && total == rows.size) { "Invalid watchlist total" }
    return rows.map { entry ->
        val row = entry as? JsonObject ?: error("Invalid watchlist row")
        for (key in listOf("id", "mediaType")) {
            val value = row[key] as? JsonPrimitive
            require(value?.isString == true && value.content.isNotBlank()) { "Invalid watchlist identity" }
        }
        require(row.getValue("mediaType").jsonPrimitive.content in setOf("movie", "series")) { "Invalid watchlist kind" }
        for (key in listOf("name", "overview", "posterUrl", "textPosterUrl", "backdropUrl", "textBackdropUrl", "logoUrl", "watchState", "status", "releaseDate", "availability", "cardSubtitle", "addedAt")) row[key]?.takeUnless { it == JsonNull }?.let {
            require((it as? JsonPrimitive)?.isString == true) { "Invalid watchlist metadata" }
        }
        row["forceTitleOverlay"]?.takeUnless { it == JsonNull }?.let {
            require((it as? JsonPrimitive)?.takeIf { value -> !value.isString }?.booleanOrNull != null) { "Invalid watchlist display flag" }
        }
        row["backdropUrls"]?.takeUnless { it == JsonNull }?.let {
            require(it is JsonArray && it.all { value -> (value as? JsonPrimitive)?.isString == true }) { "Invalid watchlist artwork" }
        }
        for (key in listOf("year", "unwatchedCount")) row[key]?.takeUnless { it == JsonNull }?.let {
            require((it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.intOrNull != null) { "Invalid watchlist year" }
        }
        row["externalIds"]?.takeUnless { it == JsonNull }?.let { value ->
            require(value is JsonObject && value.values.all { (it as? JsonPrimitive)?.isString == true }) { "Invalid watchlist IDs" }
        }
        ApiClient.json.decodeFromJsonElement<WatchlistItem>(row)
    }
}

/** The original write was accepted; only its visible-list refresh failed. No server exception text escapes. */
class WatchlistRefreshFailure : Exception("Change saved, but the watchlist could not be refreshed. Try refreshing it again.")
fun watchlistFailureMessage(error: Exception): String =
    if (error is WatchlistRefreshFailure) requireNotNull(error.message) else "Could not update your watchlist. Try again."
