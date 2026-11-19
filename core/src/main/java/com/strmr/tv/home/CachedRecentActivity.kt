package com.strmr.tv.home

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ApiJson
import com.strmr.tv.api.Title
import com.strmr.tv.app.Config
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal data class RecentActivitySelection(
    val window: Int? = null,
    val cap: Int? = null,
    val initialKind: BrowseKind? = null,
    val hideWatched: Boolean? = null,
)

internal fun BrowseList.isNormalRecentActivity() = this is BrowseList.Remote && params["source"] == "recently-watched"

/** Only a real normal shared-activity page can offer this explicit entry. */
internal fun recentActivitySelection(origin: BrowseList, extra: Map<String, String>): RecentActivitySelection? {
    if (!origin.isNormalRecentActivity()) return null
    val parameters = (origin as BrowseList.Remote).params.toMap()
    fun integer(key: String, range: IntRange): Int? = parameters[key]?.let { raw ->
        requireNotNull(raw.toIntOrNull()).also { require(it in range && it.toString() == raw) }
    }
    require(extra.keys.all { it == "homeView" || it == "hideWatched" || it == "hideUnreleased" })
    extra["hideUnreleased"]?.let { require(it == "true" || it == "false") }
    val view = extra["homeView"] ?: HomeViews.ALL
    require(view == HomeViews.ALL || view == HomeViews.MOVIES || view == HomeViews.SHOWS || HomeViews.isCustom(view))
    val kind = when (HomeViews.filter(view, HomeViewStore.views)) {
        HomeMediaFilter.MOVIES -> BrowseKind.MOVIES
        HomeMediaFilter.SHOWS -> BrowseKind.SHOWS
        HomeMediaFilter.ALL -> null
    }
    val hidden = extra["hideWatched"] ?: parameters["hideWatched"]
    require(hidden == null || hidden == "true" || hidden == "false")
    return RecentActivitySelection(integer("activityWindowDays", 1..90), integer("maxItemsPerProfile", 1..20), kind, hidden?.toBooleanStrict())
}

internal data class RecentActivityRequest(val path: String, val query: Map<String, String>)

internal fun recentActivityRequest(profile: String, list: BrowseList.RecentlyWatchedCached, limit: Int, offset: Int,
                                   options: BrowseOptions, extra: Map<String, String> = emptyMap()): RecentActivityRequest {
    if (profile.isBlank() || limit < 0 || offset < 0 || extra.keys.any { it != "titleFilter" }) throw ManagementPageFailure()
    val selected = try { options.query(list) } catch (_: IllegalArgumentException) { throw ManagementPageFailure() }
    val query = buildMap {
        putAll(list.query)
        list.parameters.window?.let { put("activityWindowDays", it.toString()) }
        list.parameters.cap?.let { put("maxItemsPerProfile", it.toString()) }
        list.parameters.hideWatched?.let { put("hideWatched", it.toString()) }
        putAll(selected); putAll(extra)
        put("limit", limit.toString()); put("offset", offset.toString())
    }
    return RecentActivityRequest("/api/users/${ApiClient.segment(profile)}/display-list", query)
}

internal suspend fun ApiClient.cachedRecentActivityPage(list: BrowseList.RecentlyWatchedCached, limit: Int, offset: Int,
                                                        extra: Map<String, String>, options: BrowseOptions, expected: Long): BrowsePage {
    fun check() {
        ProfileDataContext.requireCurrent(expected)
        if (expected != list.originalContext || Config.clientId != list.originalClient) throw CancellationException("Saved activity owner changed")
    }
    val request = inContext(expected) { check(); recentActivityRequest(profileId, list, limit, offset, options, extra) }
    val raw = sendRaw("GET", request.path, request.query, expectedContext = expected)
    currentCoroutineContext().ensureActive()
    inContext(expected) { check() }
    return decodeRecentActivityPage(raw, limit, offset)
}

/** Complete row validation precedes publication; repeated title IDs are valid activity rows. */
internal fun decodeRecentActivityPage(raw: String, limit: Int, offset: Int): BrowsePage = try {
    fun obj(value: JsonElement?, allowed: Set<String>): JsonObject = (value as? JsonObject)?.also {
        if (it.keys.any { key -> key !in allowed }) throw ManagementPageFailure()
    } ?: throw ManagementPageFailure()
    fun text(value: JsonElement?): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ManagementPageFailure()
    fun number(value: JsonElement?): Long = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw ManagementPageFailure()
    fun boolean(value: JsonElement?): Boolean = (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw ManagementPageFailure()
    val root = obj(ApiJson.parseToJsonElement(raw), setOf("source", "items", "total", "alphabetBuckets", "originalLanguageFilterSupported"))
    if (text(root["source"]) != "recently-watched-cached" || !boolean(root["originalLanguageFilterSupported"])) throw ManagementPageFailure()
    val total = number(root["total"])
    val rows = root["items"] as? JsonArray ?: throw ManagementPageFailure()
    if (total !in 0..Int.MAX_VALUE.toLong() || limit < 0 || offset < 0) throw ManagementPageFailure()
    val remaining = maxOf(0L, total - offset.toLong())
    if (rows.size.toLong() != if (limit == 0) remaining else minOf(limit.toLong(), remaining)) throw ManagementPageFailure()
    val entries = rows.mapIndexed { index, value ->
        val row = obj(value, setOf("rank", "title"))
        if (number(row["rank"]) != offset.toLong() + index + 1L) throw ManagementPageFailure()
        BrowseEntry.TitleEntry(decodeCachedActivityTitle(row["title"]))
    }
    val letters = root["alphabetBuckets"]?.let { value ->
        (value as? JsonArray ?: throw ManagementPageFailure()).map(::text).also { list ->
            if (list.distinct().size != list.size || list.any { it != "#" && !Regex("[A-Z]").matches(it) }) throw ManagementPageFailure()
        }
    }.orEmpty()
    BrowsePage(entries, total.toInt(), letters = letters, originalLanguageFilterSupported = true)
} catch (_: Exception) { throw ManagementPageFailure() }

/** The two activity APIs expose only primary-bound stored title fields. */
private fun decodeCachedActivityTitle(value: JsonElement?): Title {
    fun obj(value: JsonElement?, allowed: Set<String>): JsonObject = (value as? JsonObject)?.also {
        if (it.keys.any { key -> key !in allowed }) throw ManagementPageFailure()
    } ?: throw ManagementPageFailure()
    fun text(value: JsonElement?): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ManagementPageFailure()
    fun number(value: JsonElement?): Long = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw ManagementPageFailure()
    fun boolean(value: JsonElement?): Boolean = (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw ManagementPageFailure()
    val title = obj(value, setOf("id", "name", "overview", "year", "language", "mediaType", "tmdbId", "tvdbId", "imdbId", "cardSubtitle", "forceTitleOverlay"))
    val id = text(title["id"]); val name = text(title["name"]); val kind = text(title["mediaType"])
    if (kind != "movie" && kind != "series") throw ManagementPageFailure()
    val typed = Regex("(tmdb|tvdb):(movie|tv|series):([1-9][0-9]*)").matchEntire(id)
    val short = Regex("(tmdb|tvdb):([1-9][0-9]*)").matchEntire(id)
    val imdb = Regex("(?:imdb:)?(tt[0-9]{1,14})").matchEntire(id)
    val provider: String; val numeric: Long?; val imdbValue: String?
    when {
        typed != null -> {
            val actual = typed.groupValues[2].let { if (it == "tv") "series" else it }
            if (actual != kind) throw ManagementPageFailure()
            provider = typed.groupValues[1]; numeric = typed.groupValues[3].toLongOrNull() ?: throw ManagementPageFailure(); imdbValue = null
        }
        short != null -> { provider = short.groupValues[1]; numeric = short.groupValues[2].toLongOrNull() ?: throw ManagementPageFailure(); imdbValue = null }
        imdb != null -> { provider = "imdb"; numeric = null; imdbValue = imdb.groupValues[1] }
        else -> throw ManagementPageFailure()
    }
    fun scalar(key: String, expectedProvider: String): Long? = title[key]?.let {
        if (provider != expectedProvider || number(it) != numeric) throw ManagementPageFailure()
        number(it)
    }
    val tmdb = scalar("tmdbId", "tmdb"); val tvdb = scalar("tvdbId", "tvdb")
    title["imdbId"]?.let { if (provider != "imdb" || text(it) != imdbValue) throw ManagementPageFailure() }
    title["overview"]?.let { if (text(it).isNotEmpty()) throw ManagementPageFailure() }
    title["year"]?.let { if (number(it) != 0L) throw ManagementPageFailure() }
    title["language"]?.let { if (text(it).isNotEmpty()) throw ManagementPageFailure() }
    if (!boolean(title["forceTitleOverlay"])) throw ManagementPageFailure()
    val subtitle = title["cardSubtitle"]?.let(::text)
    return Title(id, name, mediaType = kind, tmdbId = tmdb, tvdbId = tvdb, imdbId = imdbValue,
        cardSubtitle = subtitle, forceTitleOverlay = true)
}

internal data class PopularActivitySelection(
    val window: Int? = null,
    val minimumViews: Int? = null,
    val initialKind: BrowseKind? = null,
    val hideWatched: Boolean? = null,
)

internal fun BrowseList.isNormalPopularActivity() = this == BrowseList.PopularOnServer ||
    this is BrowseList.Remote && params["source"] == "popular-on-server"

/** A shelf's legacy minimumProfiles means completed-item views on this source. */
internal fun popularActivitySelection(origin: BrowseList, extra: Map<String, String>): PopularActivitySelection? {
    if (!origin.isNormalPopularActivity()) return null
    val parameters = (origin as? BrowseList.Remote)?.params.orEmpty().toMap()
    fun integer(key: String, range: IntRange): Int? = parameters[key]?.let { raw ->
        requireNotNull(raw.toIntOrNull()).also { require(it in range && it.toString() == raw) }
    }
    val views = integer("minimumViews", 1..100)
    val alias = integer("minimumProfiles", 1..100)
    require(views == null || alias == null || views == alias)
    require(extra.keys.all { it == "homeView" || it == "hideWatched" || it == "hideUnreleased" })
    extra["hideUnreleased"]?.let { require(it == "true" || it == "false") }
    val view = extra["homeView"] ?: HomeViews.ALL
    require(view == HomeViews.ALL || view == HomeViews.MOVIES || view == HomeViews.SHOWS || HomeViews.isCustom(view))
    val kind = when (HomeViews.filter(view, HomeViewStore.views)) {
        HomeMediaFilter.MOVIES -> BrowseKind.MOVIES
        HomeMediaFilter.SHOWS -> BrowseKind.SHOWS
        HomeMediaFilter.ALL -> null
    }
    val hidden = extra["hideWatched"] ?: parameters["hideWatched"]
    require(hidden == null || hidden == "true" || hidden == "false")
    return PopularActivitySelection(integer("activityWindowDays", 7..365), views ?: alias, kind, hidden?.toBooleanStrict())
}

internal fun popularActivityRequest(profile: String, list: BrowseList.PopularOnServerCached, limit: Int, offset: Int,
                                   options: BrowseOptions, extra: Map<String, String> = emptyMap()): RecentActivityRequest {
    if (profile.isBlank() || limit < 0 || offset < 0 || extra.keys.any { it != "titleFilter" }) throw ManagementPageFailure()
    val selected = try { options.query(list) } catch (_: IllegalArgumentException) { throw ManagementPageFailure() }
    val query = buildMap {
        putAll(list.query)
        list.parameters.window?.let { put("activityWindowDays", it.toString()) }
        list.parameters.minimumViews?.let { put("minimumViews", it.toString()) }
        list.parameters.hideWatched?.let { put("hideWatched", it.toString()) }
        putAll(selected); putAll(extra)
        put("limit", limit.toString()); put("offset", offset.toString())
    }
    return RecentActivityRequest("/api/users/${ApiClient.segment(profile)}/display-list", query)
}

internal suspend fun ApiClient.cachedPopularActivityPage(list: BrowseList.PopularOnServerCached, limit: Int, offset: Int,
                                                        extra: Map<String, String>, options: BrowseOptions, expected: Long): BrowsePage {
    fun check() {
        ProfileDataContext.requireCurrent(expected)
        if (expected != list.originalContext || Config.clientId != list.originalClient) throw CancellationException("Saved popularity owner changed")
    }
    val request = inContext(expected) { check(); popularActivityRequest(profileId, list, limit, offset, options, extra) }
    val raw = sendRaw("GET", request.path, request.query, expectedContext = expected)
    currentCoroutineContext().ensureActive()
    inContext(expected) { check() }
    return decodePopularActivityPage(raw, limit, offset)
}

/** Rank is completed-item views; position and contributor counts are not encoded. */
internal fun decodePopularActivityPage(raw: String, limit: Int, offset: Int): BrowsePage = try {
    fun obj(value: JsonElement?, allowed: Set<String>): JsonObject = (value as? JsonObject)?.also {
        if (it.keys.any { key -> key !in allowed }) throw ManagementPageFailure()
    } ?: throw ManagementPageFailure()
    fun text(value: JsonElement?): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ManagementPageFailure()
    fun number(value: JsonElement?): Long = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw ManagementPageFailure()
    val root = obj(ApiJson.parseToJsonElement(raw), setOf("source", "items", "total", "alphabetBuckets", "originalLanguageFilterSupported"))
    if (text(root["source"]) != "popular-on-server-cached" || root["originalLanguageFilterSupported"] != JsonPrimitive(true)) throw ManagementPageFailure()
    val total = number(root["total"])
    val rows = root["items"] as? JsonArray ?: throw ManagementPageFailure()
    if (total !in 0..Int.MAX_VALUE.toLong() || limit < 0 || offset < 0) throw ManagementPageFailure()
    val remaining = maxOf(0L, total - offset.toLong())
    if (rows.size.toLong() != if (limit == 0) remaining else minOf(limit.toLong(), remaining)) throw ManagementPageFailure()
    val entries = rows.map { value ->
        val row = obj(value, setOf("rank", "title"))
        val views = number(row["rank"])
        if (views <= 0L) throw ManagementPageFailure()
        val title = decodeCachedActivityTitle(row["title"])
        if (title.cardSubtitle != "$views ${if (views == 1L) "view" else "views"}") throw ManagementPageFailure()
        BrowseEntry.TitleEntry(title)
    }
    val letters = root["alphabetBuckets"]?.let { value ->
        (value as? JsonArray ?: throw ManagementPageFailure()).map(::text).also { list ->
            if (list.distinct().size != list.size || list.any { it != "#" && !Regex("[A-Z]").matches(it) }) throw ManagementPageFailure()
        }
    }.orEmpty()
    BrowsePage(entries, total.toInt(), letters = letters, originalLanguageFilterSupported = true)
} catch (_: Exception) { throw ManagementPageFailure() }
