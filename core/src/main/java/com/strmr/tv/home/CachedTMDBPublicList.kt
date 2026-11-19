package com.strmr.tv.home

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ApiJson
import com.strmr.tv.api.ObservedResponseClock
import com.strmr.tv.api.Title
import com.strmr.tv.app.Config
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** The finite normal returned set, not all upstream results or a playback grant. */
data class CachedTMDBPublicListObservation(
    val listID: Long,
    val metadataLanguage: String,
    val observedAt: String,
    val expiresAt: String,
    val scope: String,
    val clock: ObservedResponseClock,
) {
    val observedMillis get() = java.time.OffsetDateTime.parse(observedAt).toInstant().toEpochMilli()
    val expiresMillis get() = java.time.OffsetDateTime.parse(expiresAt).toInstant().toEpochMilli()
    fun sameObservation(other: CachedTMDBPublicListObservation) = listID == other.listID &&
        metadataLanguage == other.metadataLanguage && observedAt == other.observedAt && expiresAt == other.expiresAt && scope == other.scope
}

internal data class TMDBPublicListSelection(val listID: Long, val initialKind: BrowseKind?)

/** Only the exact configured global TMDB operation is selectable; URLs and names are not identity. */
internal fun tmdbPublicListSelection(origin: BrowseList, extra: Map<String,String>, views: Map<String,HomeViewSettings> = HomeViewStore.views): TMDBPublicListSelection? {
    if (origin !is BrowseList.Remote || origin.params["source"] != "tmdb-list" || origin.params["sourceType"] != "public-list") return null
    val known = setOf("source", "sourceType", "sourceId", "name", "mediaType", "sort", "discoverQuery", "hideUnreleased")
    require(origin.params.keys.all { it in known })
    val id = origin.params["sourceId"]?.toLongOrNull()
    require(id != null && id > 0 && id.toString() == origin.params["sourceId"])
    require(origin.params["discoverQuery"].orEmpty().isBlank())
    require(extra.keys.all { it in setOf("homeView", "hideWatched", "hideUnreleased") })
    for (value in listOf(origin.params["hideUnreleased"], extra["hideUnreleased"], extra["hideWatched"])) require(value == null || value == "false")
    val kind = when (origin.params["mediaType"].orEmpty()) {
        "", "all" -> null
        "movie" -> BrowseKind.MOVIES
        "tv", "series" -> BrowseKind.SHOWS
        else -> throw IllegalArgumentException("Unsupported saved list kind")
    }
    val view = extra["homeView"] ?: HomeViews.ALL
    val filter = when (view) {
        HomeViews.ALL -> HomeMediaFilter.ALL
        HomeViews.MOVIES -> HomeMediaFilter.MOVIES
        HomeViews.SHOWS -> HomeMediaFilter.SHOWS
        else -> {
            require(HomeViews.isCustom(view))
            val definition = requireNotNull(views[view])
            require(definition.deleted != true)
            requireNotNull(HomeMediaFilter.from(definition.mediaFilter))
        }
    }
    val viewKind = when (filter) {
        HomeMediaFilter.ALL -> null
        HomeMediaFilter.MOVIES -> BrowseKind.MOVIES
        HomeMediaFilter.SHOWS -> BrowseKind.SHOWS
    }
    require(kind == null || viewKind == null || kind == viewKind)
    return TMDBPublicListSelection(id, kind ?: viewKind)
}

internal data class CachedTMDBPublicListRequest(val path: String, val query: Map<String, String>)
internal fun cachedTMDBPublicListRequest(profile: String, list: BrowseList.TMDBPublicListCached, limit: Int, offset: Int,
                                  options: BrowseOptions, extra: Map<String, String> = emptyMap()): CachedTMDBPublicListRequest {
    if (profile.isBlank() || limit < 0 || offset < 0 || extra.keys.any { it != "titleFilter" }) throw ManagementPageFailure()
    val selected = try { options.query(list) } catch (_: IllegalArgumentException) { throw ManagementPageFailure() }
    return CachedTMDBPublicListRequest("/api/users/${ApiClient.segment(profile)}/display-list",
        list.query + selected + extra + mapOf("limit" to limit.toString(), "offset" to offset.toString()))
}

internal suspend fun ApiClient.cachedTMDBPublicListPage(list: BrowseList.TMDBPublicListCached, limit: Int, offset: Int,
                                                extra: Map<String, String>, options: BrowseOptions, expected: Long): BrowsePage {
    fun check() {
        ProfileDataContext.requireCurrent(expected)
        if (expected != list.originalContext || Config.clientId != list.originalClient) throw CancellationException("Saved public-list owner changed")
    }
    val request = inContext(expected) { check(); cachedTMDBPublicListRequest(profileId, list, limit, offset, options, extra) }
    val response = sendObservedRaw(request.path, request.query, expected)
    currentCoroutineContext().ensureActive()
    inContext(expected) { check() }
    return decodeCachedTMDBPublicListPage(response.text, list, response.clock ?: throw ManagementPageFailure(), limit, offset)
}

/** A whole page is validated before any rows or freshness observation are published. */
internal fun decodeCachedTMDBPublicListPage(raw: String, list: BrowseList.TMDBPublicListCached, clock: ObservedResponseClock, limit: Int, offset: Int): BrowsePage = try {
    if (raw.toByteArray(Charsets.UTF_8).size > 4 * 1024 * 1024) throw ManagementPageFailure()
    fun obj(value: JsonElement?, keys: Set<String>): JsonObject = (value as? JsonObject)?.also {
        if (it.keys.any { key -> key !in keys }) throw ManagementPageFailure()
    } ?: throw ManagementPageFailure()
    fun text(value: JsonElement?): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ManagementPageFailure()
    fun number(value: JsonElement?): Long = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw ManagementPageFailure()
    fun date(raw: String): Long {
        if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})").matches(raw)) throw ManagementPageFailure()
        return java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
    }
    val root = obj(ApiJson.parseToJsonElement(raw), setOf("source", "items", "total", "alphabetBuckets", "originalLanguageFilterSupported", "publicList"))
    if (text(root["source"]) != "tmdb-public-list-cached" || root["originalLanguageFilterSupported"] != JsonPrimitive(true)) throw ManagementPageFailure()
    val total = number(root["total"])
    val rows = root["items"] as? JsonArray ?: throw ManagementPageFailure()
    if (total !in 0L..4096L || limit < 0 || offset < 0) throw ManagementPageFailure()
    val remaining = maxOf(0L, total - offset.toLong())
    if (rows.size.toLong() != if (limit == 0) remaining else minOf(limit.toLong(), remaining)) throw ManagementPageFailure()
    val descriptor = obj(root["publicList"], setOf("listId", "metadataLanguage", "observedAt", "expiresAt", "scope"))
    val observation = CachedTMDBPublicListObservation(number(descriptor["listId"]), text(descriptor["metadataLanguage"]),
        text(descriptor["observedAt"]), text(descriptor["expiresAt"]), text(descriptor["scope"]), clock)
    if (observation.listID != list.listID || observation.metadataLanguage.isBlank() || observation.metadataLanguage.length > 32 || !Charsets.UTF_8.newEncoder().canEncode(observation.metadataLanguage) ||
        observation.metadataLanguage.any { it < ' ' || it == '\u007f' } || observation.scope != "returned-items") throw ManagementPageFailure()
    val observed = date(observation.observedAt); val expires = date(observation.expiresAt)
    if (expires <= observed || expires - observed > 86_400_000L || observed > clock.upperMillis() || clock.remainingMillis(expires) <= 0) throw ManagementPageFailure()
    clock.deadlineNanos(expires, 86_400_000L)
    val identities = mutableSetOf<Pair<String,Long>>()
    val entries = rows.map { value ->
        val title = obj(value, setOf("id", "name", "overview", "year", "language", "mediaType", "tmdbId"))
        val id = text(title["id"]); val name = text(title["name"]); val kind = text(title["mediaType"])
        if (kind !in setOf("movie", "series") || !Charsets.UTF_8.newEncoder().canEncode(name) || name.toByteArray(Charsets.UTF_8).size > 1024 || name.any { it < ' ' || it == '\u007f' }) throw ManagementPageFailure()
        val primary = Regex("tmdb:(movie|tv|series):([1-9][0-9]*)").matchEntire(id) ?: throw ManagementPageFailure()
        val primaryKind = if (primary.groupValues[1] == "movie") "movie" else "series"
        val scalar = primary.groupValues[2].toLongOrNull() ?: throw ManagementPageFailure()
        if (primaryKind != kind || number(title["tmdbId"]) != scalar || !identities.add(kind to scalar)) throw ManagementPageFailure()
        title["overview"]?.let { if (text(it).isNotEmpty()) throw ManagementPageFailure() }
        title["year"]?.let { if (number(it) != 0L) throw ManagementPageFailure() }
        title["language"]?.let { if (text(it).isNotEmpty()) throw ManagementPageFailure() }
        BrowseEntry.TitleEntry(Title(id, name, mediaType = kind, tmdbId = scalar))
    }
    val letters = root["alphabetBuckets"]?.let { value ->
        (value as? JsonArray ?: throw ManagementPageFailure()).map(::text).also { list ->
            if (list.distinct().size != list.size || list.any { it != "#" && !Regex("[A-Z]").matches(it) }) throw ManagementPageFailure()
        }
    }.orEmpty()
    BrowsePage(entries, total.toInt(), letters = letters, originalLanguageFilterSupported = true, publicList = observation)
} catch (_: Exception) { throw ManagementPageFailure() }
