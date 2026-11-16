package com.strmr.tv.api

import com.strmr.tv.library.CustomListOperation
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Existing provider bulk scopes; specials and all-except-season stay unscoped. */
enum class WatchedScope(val value: String) { SHOW("show"), SEASON("season") }

/** Private client correlation, not content or mutation authority. Inputs are frozen before confirmation/job. */
class WatchedSelection internal constructor(
    val target: DetailsTarget,
    val watched: Boolean,
    val operation: CustomListOperation,
    val episodes: List<Episode>,
    val cardEpisode: CardEpisode?,
    internal val bulk: WatchedRequest,
    internal val progress: WatchedRequest?,
    private val selected: () -> Boolean,
) {
    fun check() {
        operation.requireTarget(target)
        if (!selected()) throw kotlinx.coroutines.CancellationException("Watched selection changed")
    }
    fun publish(action: () -> Unit): Boolean {
        if (!selected()) return false
        return operation.publish(action)
    }
}

/** Snapshot inputs can be carried through a menu or confirmation before an operation is accepted. */
class WatchedIntent internal constructor(
    val target: DetailsTarget, val watched: Boolean, private val context: Long,
    private val selected: () -> Boolean, private val build: (CustomListOperation) -> WatchedSelection,
) {
    fun bind(operation: CustomListOperation): WatchedSelection? {
        if (context != ProfileDataContext.current || !selected()) return null
        operation.requireTarget(target)
        if (operation.owner.epoch != context) return null
        return build(operation)
    }
}
fun movieWatchedIntent(target: DetailsTarget, watched: Boolean, context: Long, selected: () -> Boolean = { true }) =
    WatchedIntent(target, watched, context, selected) { selectMovieWatched(target, watched, it, selected) }
fun episodesWatchedIntent(target: DetailsTarget, episodes: List<Episode>, watched: Boolean, scope: WatchedScope?, context: Long, selected: () -> Boolean = { true }): WatchedIntent {
    val snapshot = java.util.Collections.unmodifiableList(episodes.toList())
    return WatchedIntent(target, watched, context, selected) { selectEpisodesWatched(target, snapshot, watched, scope, it, selected) }
}
fun cardEpisodeWatchedIntent(target: DetailsTarget, episode: CardEpisode, watched: Boolean, context: Long) =
    WatchedIntent(target, watched, context, { true }) { selectCardEpisodeWatched(target, episode, watched, it) }

/** A confirmation consumes its exact original selection once; normal accepted dismissal does not cancel it. */
class WatchedConfirmation(val selection: WatchedSelection, val close: Boolean = true) {
    private var chosen = false
    private var closed = false
    fun confirm(): WatchedSelection? {
        if (closed || chosen || runCatching { selection.check() }.isFailure) return null
        chosen = true
        return selection
    }
    fun cancel() { if (!chosen) { closed = true; selection.operation.cancel() } }
}

internal data class WatchedRequest(val method: String, val path: String, val query: Map<String, String>, val body: JsonArray?, val expected: Long, val progressBody: JsonObject? = null)

fun selectMovieWatched(movie: DetailsTarget, watched: Boolean, operation: CustomListOperation, selected: () -> Boolean = { true }): WatchedSelection {
    operation.requireTarget(movie)
    val body = buildJsonArray { add(buildJsonObject {
        watchedBase(movie, watched); put("mediaType", "movie"); put("itemId", movie.titleId)
        movie.year?.let { put("year", it) }
    }) }
    return selection(movie, watched, operation, emptyList(), null, body, null, !watched, selected)
}

fun selectEpisodesWatched(series: DetailsTarget, episodes: List<Episode>, watched: Boolean, scope: WatchedScope?, operation: CustomListOperation, selected: () -> Boolean = { true }): WatchedSelection {
    val snapshot = java.util.Collections.unmodifiableList(episodes.toList())
    val body = buildJsonArray { snapshot.forEach { ep -> add(episodeUpdate(series, watched, ep.itemId, ep.name, ep.seasonNumber, ep.episodeNumber)) } }
    return selection(series, watched, operation, snapshot, null, body, scope, false, selected)
}

fun selectCardEpisodeWatched(series: DetailsTarget, episode: CardEpisode, watched: Boolean, operation: CustomListOperation): WatchedSelection =
    selection(series, watched, operation, emptyList(), episode,
        buildJsonArray { add(episodeUpdate(series, watched, episode.itemId, episode.name, episode.seasonNumber, episode.episodeNumber)) }, null, false) { true }

private fun selection(target: DetailsTarget, watched: Boolean, operation: CustomListOperation, episodes: List<Episode>, card: CardEpisode?, body: JsonArray, scope: WatchedScope?, clearMovie: Boolean, selected: () -> Boolean): WatchedSelection = operation.owner.use {
    operation.requireTarget(target)
    val path = ApiClient.profilePath
    WatchedSelection(target, watched, operation, episodes, card,
        WatchedRequest("POST", "$path/history/watched/bulk", scope?.let { mapOf("scope" to it.value) }.orEmpty(), body, operation.owner.epoch),
        if (clearMovie) WatchedRequest("POST", "$path/history/progress/delete", emptyMap(), null, operation.owner.epoch,
            buildJsonObject { put("mediaType", "movie"); put("itemId", target.titleId) }) else null, selected)
}

internal interface WatchedAccess { suspend fun send(request: WatchedRequest) }
private class WatchedOwner(val epoch: Long) { val mutex = Mutex() }
private var watchedOwner: WatchedOwner? = null

/** Every actual writer consumes this same compound gate, including the progress-delete follow-up. */
internal suspend fun performWatched(selection: WatchedSelection, access: WatchedAccess, start: () -> Unit = {}, success: () -> Unit = {}, failure: () -> Unit = {}) {
    currentCoroutineContext().ensureActive(); selection.check()
    val gate = selection.operation.owner.use {
        watchedOwner?.takeIf { it.epoch == selection.operation.owner.epoch }
            ?: WatchedOwner(selection.operation.owner.epoch).also { watchedOwner = it }
    }
    gate.mutex.withLock {
        currentCoroutineContext().ensureActive(); selection.check()
        if (selection.bulk.body.isNullOrEmpty()) return@withLock
        if (!selection.publish(start)) throw kotlinx.coroutines.CancellationException("Watched selection changed")
        currentCoroutineContext().ensureActive(); selection.check()
        try {
            if (selection.bulk.body?.isNotEmpty() == true) {
                access.send(selection.bulk)
                currentCoroutineContext().ensureActive(); selection.check()
                selection.progress?.let { request ->
                    try { access.send(request) }
                    catch (error: ApiError) {
                        currentCoroutineContext().ensureActive(); selection.check()
                        if (error.vodDenial != null || error.statusCode != 404 || error.code != null) throw error
                    }
                    currentCoroutineContext().ensureActive(); selection.check()
                }
            }
            selection.publish(success)
        } catch (error: kotlinx.coroutines.CancellationException) { throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive(); selection.check()
            selection.publish(failure)
            throw error
        }
    }
}

internal object ServerWatchedAccess : WatchedAccess {
    override suspend fun send(request: WatchedRequest) {
        ApiClient.sendRaw(request.method, request.path, request.query, body = request.body ?: request.progressBody, expectedContext = request.expected)
    }
}
suspend fun ApiClient.setWatched(selection: WatchedSelection) = performWatched(selection, ServerWatchedAccess)

private fun episodeUpdate(series: DetailsTarget, watched: Boolean, itemId: String?, name: String?, season: Int, episode: Int) = buildJsonObject {
    watchedBase(series, watched); put("mediaType", "episode")
    itemId?.let { put("itemId", it) }; name?.let { put("name", it) }
    put("seriesId", series.titleId); put("seriesName", series.name)
    put("seasonNumber", season); put("episodeNumber", episode)
}
private fun JsonObjectBuilder.watchedBase(title: DetailsTarget, watched: Boolean) {
    put("watched", watched); put("name", title.name)
    val ids = listOfNotNull(title.tmdbId?.let { "tmdb" to it }, title.imdbId?.let { "imdb" to it }, title.tvdbId?.let { "tvdb" to it })
    if (ids.isNotEmpty()) putJsonObject("externalIds") { ids.forEach { (key, value) -> put(key, value) } }
}

internal suspend fun readWatched(expected: Long, read: suspend () -> String): Boolean {
    currentCoroutineContext().ensureActive(); ProfileDataContext.requireCurrent(expected)
    val raw = try { read() } catch (error: ApiError) {
        currentCoroutineContext().ensureActive(); ProfileDataContext.requireCurrent(expected)
        if (error.vodDenial == null && error.statusCode == 404 && error.code == null) return false else throw error
    }
    currentCoroutineContext().ensureActive(); ProfileDataContext.requireCurrent(expected)
    val row = ApiJson.parseToJsonElement(raw) as? JsonObject ?: error("Invalid watched response")
    return (row["watched"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: error("Invalid watched state")
}
suspend fun ApiClient.isMovieWatched(movie: DetailsTarget, expectedContext: Long): Boolean = readWatched(expectedContext) {
    val path = inContext(expectedContext) { "$profilePath/history/watched/movie/${segment(movie.titleId)}" }
    sendRaw("GET", path, promptOnLock = false, timeoutMs = 10_000, expectedContext = expectedContext)
}
suspend fun ApiClient.isEpisodeWatched(episode: CardEpisode, series: DetailsTarget, expectedContext: Long): Boolean = readWatched(expectedContext) {
    val id = episode.itemId ?: "${series.titleId}:s%02de%02d".format(episode.seasonNumber, episode.episodeNumber)
    val path = inContext(expectedContext) { "$profilePath/history/watched/episode/${segment(id)}" }
    sendRaw("GET", path, promptOnLock = false, timeoutMs = 10_000, expectedContext = expectedContext)
}
