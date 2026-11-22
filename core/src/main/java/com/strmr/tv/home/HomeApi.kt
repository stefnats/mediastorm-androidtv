package com.strmr.tv.home

import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.vodDenial
import com.strmr.tv.library.TitleListOrigin
import com.strmr.tv.library.WatchlistStore
import com.strmr.tv.ui.details.isTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ApiJson
import com.strmr.tv.api.vodOptionalRead
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import com.strmr.tv.api.libraryPage
import com.strmr.tv.api.ContinueWatchingItem
import com.strmr.tv.api.DisplayListPage
import com.strmr.tv.api.ListedTitle
import com.strmr.tv.api.LossyListSerializer
import com.strmr.tv.api.Title
import com.strmr.tv.app.Config
import com.strmr.tv.library.HiddenItem
import java.util.TimeZone
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable private data class DisplayFields(val hideWatched: Boolean? = null, val badgeVisibility: List<String>? = null)

@Serializable private data class UserHome(val homeShelves: HomeFields? = null, val display: DisplayFields? = null)

@Serializable private data class MetadataFields(val aiApiKey: String? = null, val geminiApiKey: String? = null)

@Serializable private data class ServerHome(
    val metadata: MetadataFields? = null,
    val homeShelves: HomeFields? = null,
    val display: DisplayFields? = null,
)

@Serializable private data class DeviceHome(val homeShelfFocusModel: String? = null)

@Serializable private data class Manifest(val revision: String? = null)

@Serializable private data class CalendarResponse(
    @Serializable(with = LossyListSerializer::class) val items: List<CalendarItem> = emptyList(),
    /** The server has no calendar for the profile yet and is building one: ask again shortly. */
    val pending: Boolean = false,
)

/** Home's calendar is still being built on the server (right after a restart); its rows keep loading and retry. */
class HomeCalendarPending : Exception("calendar is still building")

@Serializable private data class DashboardResponse(
    @Serializable(with = LossyListSerializer::class) val streams: List<DashboardStream> = emptyList(),
)

@Serializable private data class SurprisePick(val title: Title)

/**
 * The profile's `homeShelves` config resolved for a Home view (see [resolveHomeView]), layered
 * with the server's AI key presence and this device's focus position (set in the admin UI).
 */
suspend fun ApiClient.homeSettings(view: String = HomeViews.ALL, expectedContext: Long = ProfileDataContext.current): HomeSettings = coroutineScope {
    currentCoroutineContext().ensureActive()
    ProfileDataContext.requireCurrent(expectedContext)
    // The server's own settings only say whether an AI key is set (masked); a failure there
    // just hides the AI-backed Watch Something cards.
    val server = async { vodOptionalRead {
        send<ServerHome>("GET", "/api/settings", timeoutMs = 10_000, expectedContext = expectedContext)
    } }
    val device = async {
        vodOptionalRead {
            val query = inContext(expectedContext) { mapOf("userId" to profileId) }
            send<DeviceHome>("GET", "/api/clients/${segment(Config.clientId)}/settings", query,
                             promptOnLock = false, timeoutMs = 10_000, expectedContext = expectedContext)
        }
    }
    val path = inContext(expectedContext) { "$profilePath/settings" }
    val settings = send<UserHome>("GET", path, expectedContext = expectedContext)
    val serverHome = server.await()
    val deviceHome = device.await()
    currentCoroutineContext().ensureActive()
    ProfileDataContext.requireCurrent(expectedContext)
    val metadata = serverHome?.metadata
    val views = HomeViews.merge(serverHome?.homeShelves?.views, settings.homeShelves?.views)
    // A view that no longer exists is Home itself; the caller falls back to All.
    val shown = if (view in HomeViews.ids(views)) view else HomeViews.ALL
    val home = resolveHomeView(serverHome?.homeShelves, settings.homeShelves, shown)
    HomeSettings(
        shelves = (home.shelves ?: emptyList()).sortedBy { it.order ?: 0 },
        itemCap = home.itemCap?.takeIf { it > 0 } ?: 20,
        exploreFirst = home.exploreCardPosition != "end",
        hideWatched = settings.display?.hideWatched ?: false,
        badgeVisibility = settings.display?.badgeVisibility ?: serverHome?.display?.badgeVisibility,
        excludeUpcomingFromContinue = home.excludeUpcomingFromContinue ?: true,
        layout = ProfileHomeLayout.from(
            focusModel = deviceHome?.homeShelfFocusModel ?: home.homeShelfFocusModel,
            disableCardExpansion = home.disableTvLandscapeCardExpansion,
            shelfScale = home.homeShelfScale,
            heroScale = home.homeHeroScale,
            excludeUpcomingFromContinue = home.excludeUpcomingFromContinue,
        ),
        hasAI = listOf(metadata?.aiApiKey, metadata?.geminiApiKey).any { !it.isNullOrEmpty() },
        topShelfMode = TopShelfMode.from(home.tvTopShelfMode),
        topShelfSourceId = home.tvTopShelfSourceId,
        view = shown,
        filter = HomeViews.filter(shown, views),
        views = views,
    )
}

/**
 * A fingerprint of everything Home shows that the server tracks (settings, shelf config,
 * Continue Watching, watchlist, hidden titles); null when it can't be read.
 */
suspend fun ApiClient.homeRevision(expectedContext: Long = ProfileDataContext.current): String? = vodOptionalRead {
    val path = inContext(expectedContext) { "$profilePath/home/manifest" }
    send<Manifest>("GET", path, promptOnLock = false, timeoutMs = 15_000, expectedContext = expectedContext).revision
}

/**
 * Continue Watching, minus what the profile put aside. Titles hidden with
 * [hideFromContinueWatching] are dropped server-side; the "Set Aside" custom list is
 * filtered here, as the other clients do.
 */
suspend fun ApiClient.continueWatching(expectedContext: Long = ProfileDataContext.current): List<ContinueWatchingItem> =
    readContinueWatching(
        expectedContext,
        rows = { expected ->
            val path = inContext(expected) { "$profilePath/history/continue" }
            sendList("GET", path, expectedContext = expected)
        },
        lists = { expected -> customLists(expected) },
        items = { id, expected -> customListItems(id, expected) },
    )

/** Every child and follow-up belongs to the caller, including optional Set Aside reads. */
internal suspend fun readContinueWatching(
    expectedContext: Long,
    rows: suspend (Long) -> List<ContinueWatchingItem>,
    lists: suspend (Long) -> List<CustomList>,
    items: suspend (String, Long) -> List<WatchlistItem>,
): List<ContinueWatchingItem> = coroutineScope {
    suspend fun checkOwner() {
        currentCoroutineContext().ensureActive()
        ProfileDataContext.requireCurrent(expectedContext)
    }
    checkOwner()
    val readingRows = async {
        checkOwner()
        rows(expectedContext).also { checkOwner() }
    }
    val readingAside = async {
        checkOwner()
        vodOptionalRead {
            val list = lists(expectedContext).also { checkOwner() }.firstOrNull { it.isSetAside }
            list?.let {
                checkOwner()
                items(it.id, expectedContext).also { checkOwner() }
            }.orEmpty()
        }.orEmpty().also { checkOwner() }
    }
    val watching = readingRows.await()
    val parked = readingAside.await()
    checkOwner()
    if (parked.isEmpty()) watching else watching.filterNot { item -> parked.any { it.matches(item) } }
}

/** Drops a title from Continue Watching until it is played again. */
suspend fun ApiClient.hideFromContinueWatching(seriesId: String, expectedContext: Long) {
    val path = inContext(expectedContext) { "$profilePath/history/continue/hide" }
    sendRaw("POST", path, body = buildJsonObject { put("seriesId", seriesId) }, expectedContext = expectedContext)
}

/** The titles [hideFromContinueWatching] dropped, newest first (Settings › Hidden Items). Name and art may be missing. */
suspend fun ApiClient.hiddenFromContinueWatching(expectedContext: Long = ProfileDataContext.current): List<HiddenItem> {
    val path = inContext(expectedContext) { "$profilePath/history/continue/hidden" }
    return sendList("GET", path, expectedContext = expectedContext)
}

/** Undoes [hideFromContinueWatching]: the title returns to Continue Watching if it still qualifies. */
suspend fun ApiClient.showInContinueWatching(seriesId: String, expectedContext: Long) {
    val path = inContext(expectedContext) { "$profilePath/history/continue/unhide" }
    sendRaw("POST", path, body = buildJsonObject { put("seriesId", seriesId) }, expectedContext = expectedContext)
}

/** The same title: its ID, or a shared provider ID. */
private fun WatchlistItem.matches(item: ContinueWatchingItem): Boolean {
    if (id == item.seriesId) return true
    val mine = externalIds ?: return false
    val theirs = item.externalIds ?: return false
    return listOf("tmdb", "imdb", "tvdb").any { key -> mine[key]?.takeIf { it.isNotEmpty() }?.let { it == theirs[key] } == true }
}

/**
 * The calendar window Home's calendar rows use: two weeks ahead and the recent past. Throws
 * [HomeCalendarPending] while the server is still building the profile's calendar.
 */
suspend fun ApiClient.homeCalendar(limit: Int, expectedContext: Long = ProfileDataContext.current): List<CalendarItem> {
    val path = inContext(expectedContext) { "$profilePath/calendar" }
    val response = send<CalendarResponse>(
        "GET", path,
        mapOf("days" to "14", "recentDays" to "90", "compact" to "1", "home" to "1", "limit" to limit.toString(),
              "tz" to TimeZone.getDefault().id),
        timeoutMs = 60_000, expectedContext = expectedContext,
    )
    currentCoroutineContext().ensureActive()
    ProfileDataContext.requireCurrent(expectedContext)
    if (response.pending && response.items.isEmpty()) throw HomeCalendarPending()
    return response.items
}

/** Streams playing on the server right now, for the Dashboard row. */
suspend fun ApiClient.dashboardStreams(expectedContext: Long = ProfileDataContext.current): List<DashboardStream> =
    send<DashboardResponse>("GET", "/api/dashboard/shelf", promptOnLock = false, timeoutMs = 15_000, expectedContext = expectedContext).streams

/** A random title for Watch Something's Surprise Me card (needs an AI key on the server), of the view's kind. */
suspend fun ApiClient.surprise(filter: HomeMediaFilter = HomeMediaFilter.ALL): Title = send<SurprisePick>(
    "GET", "/api/recommendations/surprise",
    mapOf("userId" to profileId) + if (filter == HomeMediaFilter.ALL) emptyMap() else mapOf("mediaType" to filter.mediaType),
    timeoutMs = 60_000,
).title

suspend fun ApiClient.customLists(expectedContext: Long = ProfileDataContext.current): List<CustomList> {
    val path = inContext(expectedContext) { "$profilePath/custom-lists" }
    return sendList("GET", path, expectedContext = expectedContext)
}

suspend fun ApiClient.customListItems(id: String, expectedContext: Long = ProfileDataContext.current): List<WatchlistItem> {
    val path = inContext(expectedContext) { "$profilePath/custom-lists/${segment(id)}/items" }
    return sendList("GET", path, expectedContext = expectedContext)
}

/**
 * One page of a list, as titles whatever the source's item shape. `extra` adds query
 * parameters (Home's `hideWatched` / `hideUnreleased`). With a `phase`, a remote list that
 * pages progressively reads only enough of its source to fill the page.
 */
suspend fun ApiClient.browsePage(
    list: BrowseList, limit: Int, offset: Int = 0, extra: Map<String, String> = emptyMap(), phase: ShelfPhase? = null,
    options: BrowseOptions? = null, expectedContext: Long = ProfileDataContext.current, hiddenLegacyMode: Boolean? = null,
): BrowsePage {
    currentCoroutineContext().ensureActive()
    ProfileDataContext.requireCurrent(expectedContext)
    if (list is BrowseList.Library) return libraryPage(list.library, options, limit, offset, expectedContext)
    if (list is BrowseList.Service && list.lists.size > 1) return mergedServicePage(list, limit, offset, extra, options, expectedContext)
    // Continue Watching's own shape: the whole feed, paged here.
    if (list == BrowseList.ContinueWatching) {
        // Opened from a Home view: only that view's kind, as its row shows.
        val filter = extra["homeView"]?.let { HomeViews.filter(it, HomeViewStore.views) } ?: HomeMediaFilter.ALL
        val all = continueWatching(expectedContext).filter { matchesHomeView(if (it.nextEpisode != null) "series" else "movie", filter) }
        currentCoroutineContext().ensureActive()
        ProfileDataContext.requireCurrent(expectedContext)
        return BrowsePage(all.drop(offset).take(limit).map { BrowseEntry.WatchingEntry(it) }, all.size)
    }
    if (list is BrowseList.RecentlyWatchedCached) return cachedRecentActivityPage(list, limit, offset, extra, options ?: BrowseOptions(), expectedContext)
    if (list is BrowseList.PopularOnServerCached) return cachedPopularActivityPage(list, limit, offset, extra, options ?: BrowseOptions(), expectedContext)
    if (list is BrowseList.SimilarCached) return cachedSimilarPage(list, limit, offset, extra, options ?: BrowseOptions(), expectedContext)
    if (list is BrowseList.TMDBPublicListCached) return cachedTMDBPublicListPage(list, limit, offset, extra, options ?: BrowseOptions(), expectedContext)
    if (list is BrowseList.ContinueWatchingCached) return cachedWatchingPage(list, limit, offset, extra, options ?: BrowseOptions(), expectedContext)
    if (list is BrowseList.CollectionCached) return cachedCollectionPage(list, limit, offset, extra, options ?: BrowseOptions(), expectedContext)
    if (list == BrowseList.LovedStored) return storedLovedPage(limit, offset, extra, options ?: BrowseOptions(), expectedContext)
    if (list == BrowseList.Hidden) return hiddenManagementPage(limit, offset, extra, options ?: BrowseOptions(), expectedContext, hiddenLegacyMode)
    val query = inContext(expectedContext) { browseQuery(list, limit, offset, extra, options, phase) }
    // Series counts the server hasn't cached yet are fetched in the background once asked for.
    val path = inContext(expectedContext) {
        if (com.strmr.tv.ui.settings.BadgeSettings.visibility.unwatchedCount) query["includeUnwatchedCounts"] = "true"
        browsePath(expectedContext)
    }
    val timeout = when {
        query["source"] == "custom-ai" -> 120_000L
        phase == ShelfPhase.COMPLETE && list.loadsProgressively -> 180_000L
        else -> 30_000L
    }
    val raw = sendRaw("GET", path, query, timeoutMs = timeout, expectedContext = expectedContext)
    currentCoroutineContext().ensureActive()
    ProfileDataContext.requireCurrent(expectedContext)
    return decodeBrowsePage(raw, list, extra)

}

/** Additive malformed capability never discards the containing legacy page. */
internal fun decodeBrowsePage(raw: String, list: BrowseList, extra: Map<String, String> = emptyMap()): BrowsePage {
    val root = ApiJson.parseToJsonElement(raw)
    val marker = ((root as? JsonObject)?.get("originalLanguageFilterSupported") as? JsonPrimitive)
    val supported = list.filters && marker?.isString == false && marker.booleanOrNull == true
    val mediaMarker = ((root as? JsonObject)?.get("storedMediaFilterSupported") as? JsonPrimitive)
    val mediaSupport = list.storedMediaEligible(extra) && mediaMarker?.isString == false && mediaMarker.booleanOrNull == true
    val mediaBasis = ((root as? JsonObject)?.get("storedMediaFilterBasis") as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun <T> page(value: DisplayListPage<T>, entry: (T) -> BrowseEntry) = BrowsePage(
        value.items.map(entry), maxOf(if (supported || mediaSupport) value.total else value.sourceTotal ?: value.total, value.items.size), value.genres.orEmpty(), value.alphabetBuckets.orEmpty(), supported,
        storedMediaFilterSupported = mediaSupport, storedMediaFilterBasis = mediaBasis,
    )
    return when {
        list == BrowseList.PermanentPrequeue -> page(ApiJson.decodeFromJsonElement<DisplayListPage<JsonElement>>(root)) { row ->
            // Losing a wire row would move the next offset backwards. Reject a malformed page.
            if ((row as? JsonObject)?.get("title") is JsonObject) BrowseEntry.TitleEntry(ApiJson.decodeFromJsonElement<ListedTitle>(row).title)
            else BrowseEntry.ItemEntry(ApiJson.decodeFromJsonElement<WatchlistItem>(row))
        }
        list.itemShaped -> page(ApiJson.decodeFromJsonElement<DisplayListPage<WatchlistItem>>(root)) { BrowseEntry.ItemEntry(it) }
        list.titleShaped -> page(ApiJson.decodeFromJsonElement<DisplayListPage<Title>>(root)) { BrowseEntry.TitleEntry(it) }
        else -> page(ApiJson.decodeFromJsonElement<DisplayListPage<ListedTitle>>(root)) { BrowseEntry.TitleEntry(it.title) }
    }
}

/** Actual display-list query builder shared with portable request-contract tests. */
internal fun browseQuery(list: BrowseList, limit: Int, offset: Int, extra: Map<String, String> = emptyMap(), options: BrowseOptions? = null, phase: ShelfPhase? = null): MutableMap<String, String> =
    (list.query + extra + options?.query(list, extra).orEmpty()).toMutableMap().apply {
        put("limit", limit.toString()); put("offset", offset.toString())
        if (phase != null && list.loadsProgressively) putAll(phase.query)
    }

/** The path in production requests is captured under the original epoch before any await. */
internal fun ApiClient.browsePath(expectedContext: Long): String = inContext(expectedContext) { "$profilePath/display-list" }


/** A fixed contract failure blocks the management page; malformed rows never become a partial page. */
class ManagementPageFailure : Exception("This server could not apply this management view. Clear the filters or reload the list.")

internal fun hiddenManagementPath(profile: String): String = "/api/users/${ApiClient.segment(profile)}/display-list"

internal fun hiddenManagementQuery(limit: Int, offset: Int, extra: Map<String, String>, options: BrowseOptions): Map<String, String> {
    if (extra.keys.any { it != "titleFilter" }) throw ManagementPageFailure()
    val selected = try { options.query(BrowseList.Hidden) } catch (_: IllegalArgumentException) { throw ManagementPageFailure() }
    return mapOf("source" to "hidden", "limit" to limit.toString(), "offset" to offset.toString()) + extra + selected
}

private suspend fun ApiClient.hiddenManagementPage(
    limit: Int, offset: Int, extra: Map<String, String>, options: BrowseOptions, expected: Long, legacy: Boolean?,
): BrowsePage = readHiddenManagementPage(limit, offset, extra, options, expected, legacy,
    readPage = { query, owner ->
        val path = inContext(owner) { hiddenManagementPath(profileId) }
        sendRaw("GET", path, query, expectedContext = owner)
    },
    readLegacy = { owner ->
        val path = inContext(owner) { "$profilePath/hidden-items" }
        sendRaw("GET", path, promptOnLock = false, expectedContext = owner)
    },
)

/** Negotiation/fallback is private to the original model, never persisted or sent as a permission field. */
internal suspend fun readHiddenManagementPage(
    limit: Int, offset: Int, extra: Map<String, String>, options: BrowseOptions, expected: Long, legacy: Boolean?,
    readPage: suspend (Map<String, String>, Long) -> String,
    readLegacy: suspend (Long) -> String,
): BrowsePage {
    suspend fun checkOwner() {
        currentCoroutineContext().ensureActive()
        ProfileDataContext.requireCurrent(expected)
    }
    checkOwner()
    val query = hiddenManagementQuery(limit, offset, extra, options)
    val defaultRequest = query.keys == setOf("source", "limit", "offset")
    suspend fun legacyPage(): BrowsePage {
        if (!defaultRequest) throw ManagementPageFailure()
        checkOwner()
        val raw = readLegacy(expected)
        checkOwner()
        val rows = decodeHiddenRows(ApiJson.parseToJsonElement(raw)).reversed()
        val page = BrowsePage(rows.drop(offset).let { if (limit > 0) it.take(limit) else it }.map { BrowseEntry.ItemEntry(it) }, rows.size)
        checkOwner()
        return page
    }
    if (legacy == true) return legacyPage()
    val raw = try { readPage(query, expected) }
    catch (error: Exception) {
        checkOwner()
        val unsupported = (error as? com.strmr.tv.api.ApiError.Http)?.response?.let {
            it.status == 400 && it.code == null && it.body.trim() == "unsupported display list source"
        } == true
        if (legacy == null && defaultRequest && unsupported) return legacyPage()
        throw error
    }
    checkOwner()
    val page = decodeHiddenManagementPage(raw)
    checkOwner()
    return page
}

internal fun decodeHiddenManagementPage(raw: String): BrowsePage = try {
    val root = ApiJson.parseToJsonElement(raw) as? JsonObject ?: throw ManagementPageFailure()
    val source = root["source"] as? JsonPrimitive
    val support = root["originalLanguageFilterSupported"] as? JsonPrimitive
    if (source?.isString != true || source.content != "hidden" || support?.isString != false || support.booleanOrNull != true) throw ManagementPageFailure()
    val total = (root["total"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 0 } ?: throw ManagementPageFailure()
    val items = decodeHiddenRows(root["items"])
    if (total < items.size) throw ManagementPageFailure()
    val alphabet = root["alphabetBuckets"]?.takeUnless { it == JsonNull }?.let { value ->
        (value as? JsonArray ?: throw ManagementPageFailure()).map { (it as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ManagementPageFailure() }
    }.orEmpty()
    BrowsePage(items.map { BrowseEntry.ItemEntry(it) }, total, letters = alphabet, originalLanguageFilterSupported = true).withStoredMediaCapability(root)
} catch (_: Exception) { throw ManagementPageFailure() }

private fun decodeHiddenRows(value: JsonElement?): List<WatchlistItem> {
    val rows = value as? JsonArray ?: throw ManagementPageFailure()
    return rows.map { entry ->
        val row = entry as? JsonObject ?: throw ManagementPageFailure()
        fun required(key: String): String = (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ManagementPageFailure()
        fun optional(key: String): String? = row[key]?.takeUnless { it == JsonNull }?.let { (it as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ManagementPageFailure() }
        val id = required("id").takeIf { it.isNotBlank() } ?: throw ManagementPageFailure()
        val kind = required("mediaType").takeIf { it == "movie" || it == "series" } ?: throw ManagementPageFailure()
        val hiddenAt = required("hiddenAt")
        if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?(?:[Zz]|[+-][0-9]{2}:[0-9]{2})").matches(hiddenAt)) throw ManagementPageFailure()
        try { java.time.OffsetDateTime.parse(hiddenAt) } catch (_: Exception) { throw ManagementPageFailure() }
        val year = row["year"]?.takeUnless { it == JsonNull }?.let { (it as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw ManagementPageFailure() }
        val ids = row["externalIds"]?.takeUnless { it == JsonNull }?.let { external ->
            (external as? JsonObject ?: throw ManagementPageFailure()).mapValues { (_, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ManagementPageFailure() }
        }
        WatchlistItem(id, kind, optional("name").orEmpty(), year = year, posterUrl = optional("posterUrl"), backdropUrl = optional("backdropUrl"), externalIds = ids)
    }
}

/** Normal complete-list request; neither a capability probe nor a rating permission grant. */
internal data class StoredLovedRequest(val path: String, val query: Map<String, String>, val headers: Map<String, String>)
internal fun storedLovedRequest(profile: String, client: String, limit: Int, offset: Int, options: BrowseOptions, extra: Map<String, String> = emptyMap()): StoredLovedRequest {
    require(profile.isNotBlank() && client.isNotBlank() && client.length <= 128) { "A profile and client are required" }
    if (limit < 0 || offset < 0 || extra.keys.any { it != "titleFilter" } || options.ratingSource != RatingSource.IMDB) throw ManagementPageFailure()
    val selected = try { options.query(BrowseList.LovedStored) } catch (_: IllegalArgumentException) { throw ManagementPageFailure() }
    return StoredLovedRequest("/api/users/${ApiClient.segment(profile)}/display-list",
        mapOf("source" to "loved-stored", "limit" to limit.toString(), "offset" to offset.toString()) + extra + selected,
        mapOf("X-Client-ID" to client))
}
private suspend fun ApiClient.storedLovedPage(limit: Int, offset: Int, extra: Map<String, String>, options: BrowseOptions, expected: Long): BrowsePage {
    val request = inContext(expected) { storedLovedRequest(profileId, Config.clientId, limit, offset, options, extra) }
    val raw = sendRaw("GET", request.path, request.query, headers = request.headers, expectedContext = expected)
    currentCoroutineContext().ensureActive(); ProfileDataContext.requireCurrent(expected)
    return decodeStoredLovedPage(raw, limit, offset)
}

/** Every row is checked, with no defaulted/lossy Title list and no promotion of enrichment fields. */
internal fun decodeStoredLovedPage(raw: String, limit: Int? = null, offset: Int = 0): BrowsePage = try {
    val root = ApiJson.parseToJsonElement(raw) as? JsonObject ?: throw ManagementPageFailure()
    fun text(value: JsonElement?): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ManagementPageFailure()
    fun integer(value: JsonElement?): Int = (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw ManagementPageFailure()
    if (text(root["source"]) != "loved-stored") throw ManagementPageFailure()
    val marker = root["originalLanguageFilterSupported"] as? JsonPrimitive
    if (marker?.isString != false || marker.booleanOrNull != true) throw ManagementPageFailure()
    val total = integer(root["total"])
    val rows = root["items"] as? JsonArray ?: throw ManagementPageFailure()
    if (total < 0 || offset < 0 || rows.isNotEmpty() && offset.toLong() + rows.size > total) throw ManagementPageFailure()
    if (limit != null) {
        val remaining = maxOf(0, total - offset)
        val expectedRows = if (limit == 0) remaining else minOf(limit, remaining)
        if (rows.size != expectedRows) throw ManagementPageFailure()
    }
    val entries = rows.mapIndexed { index, entry ->
        val row = entry as? JsonObject ?: throw ManagementPageFailure()
        val rank = integer(row["rank"])
        if (rank != offset + index + 1 || rank > total) throw ManagementPageFailure()
        val updated = text(row["updatedAt"])
        if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?(?:[Zz]|[+-][0-9]{2}:[0-9]{2})").matches(updated)) throw ManagementPageFailure()
        java.time.OffsetDateTime.parse(updated)
        val title = row["title"] as? JsonObject ?: throw ManagementPageFailure()
        val id = text(title["id"]).takeIf { it.isNotBlank() && it.length <= 128 } ?: throw ManagementPageFailure()
        val kind = text(title["mediaType"]).takeIf { it == "movie" || it == "series" } ?: throw ManagementPageFailure()
        val name = text(title["name"])
        if (text(title["personalRating"]) != "love") throw ManagementPageFailure()
        fun optionalText(key: String) = title[key]?.takeUnless { it == JsonNull }?.let(::text)
        val year = title["year"]?.takeUnless { it == JsonNull }?.let(::integer)
        fun provider(key: String): Long? = title[key]?.takeUnless { it == JsonNull }?.let { value ->
            val number = (value as? JsonPrimitive)?.takeIf { !it.isString } ?: throw ManagementPageFailure()
            number.content.toLongOrNull() ?: throw ManagementPageFailure()
        }
        // Only saved bare presentation/identity is consumed. No source, artwork or watched claims.
        BrowseEntry.TitleEntry(Title(id, name, year = year, mediaType = kind, personalRating = "love",
            tmdbId = provider("tmdbId"), tvdbId = provider("tvdbId"), imdbId = optionalText("imdbId"), certification = optionalText("certification")), StoredLoveRow(rank, updated))
    }
    val alphabet = root["alphabetBuckets"]?.takeUnless { it == JsonNull }?.let { value ->
        (value as? JsonArray ?: throw ManagementPageFailure()).map(::text)
    }.orEmpty()
    BrowsePage(entries, total, letters = alphabet, originalLanguageFilterSupported = true).withStoredMediaCapability(root)
} catch (_: Exception) { throw ManagementPageFailure() }

/** Local menu-only membership. A failed read is not a successful empty membership snapshot. */
data class DetailsMenuSnapshot(
    val inSetAside: Boolean = false,
    val inContinueWatching: Boolean = false,
    val loaded: Boolean = false,
    val notice: String? = null,
)

internal interface DetailsMenuReads {
    suspend fun lists(expected: Long): List<CustomList>
    suspend fun items(id: String, expected: Long): List<WatchlistItem>
    suspend fun continuing(expected: Long): List<ContinueWatchingItem>
}

internal fun detailsMenuHistoryPath(expected: Long): String = ApiClient.inContext(expected) {
    "${ApiClient.profilePath}/history/continue"
}

/** These menu-local reads reject a malformed whole response rather than using legacy lossy lists. */
internal class RawDetailsMenuReads(
    private val get: suspend (String, Long) -> String = { path, expected -> ApiClient.sendRaw("GET", path, expectedContext = expected) },
) : DetailsMenuReads {
    private suspend fun raw(expected: Long, path: () -> String): String {
        currentCoroutineContext().ensureActive()
        val originalPath = ApiClient.inContext(expected, path)
        val value = get(originalPath, expected)
        currentCoroutineContext().ensureActive()
        ProfileDataContext.requireCurrent(expected)
        return value
    }
    override suspend fun lists(expected: Long) = menuRows(raw(expected) { "${ApiClient.profilePath}/custom-lists" }).map { row ->
        CustomList(menuText(row, "id", nonblank = true), menuText(row, "name"))
    }
    override suspend fun items(id: String, expected: Long) = menuRows(raw(expected) { "${ApiClient.profilePath}/custom-lists/${ApiClient.segment(id)}/items" }).map { row ->
        val kind = menuText(row, "mediaType")
        require(kind == "movie" || kind == "series") { "Invalid saved title kind" }
        WatchlistItem(menuText(row, "id", nonblank = true), kind, menuText(row, "name"), externalIds = menuAliases(row))
    }
    // Consume the already-read Set Aside snapshot; never run continueWatching's second list cycle.
    override suspend fun continuing(expected: Long) = menuRows(raw(expected) { detailsMenuHistoryPath(expected) }, allowEmptyHistory = true).map { row ->
        ContinueWatchingItem(menuText(row, "seriesId", nonblank = true), menuText(row, "seriesTitle"), externalIds = menuAliases(row))
    }
}

private fun menuRows(raw: String, allowEmptyHistory: Boolean = false): List<JsonObject> {
    val parsed = ApiJson.parseToJsonElement(raw)
    // The history service's empty result is a nil Go slice; list and item services emit [].
    if (allowEmptyHistory && parsed == JsonNull) return emptyList()
    return (parsed as? JsonArray ?: error("Invalid title actions response")).map {
        it as? JsonObject ?: error("Invalid title actions row")
    }
}
private fun menuText(row: JsonObject, key: String, nonblank: Boolean = false): String {
    val value = (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid title actions field")
    require(!nonblank || value.isNotBlank()) { "Invalid title actions identity" }
    return value
}
private fun menuAliases(row: JsonObject): Map<String, String>? = row["externalIds"]?.takeUnless { it == JsonNull }?.let { value ->
    (value as? JsonObject ?: error("Invalid title aliases")).mapValues { (_, id) ->
        (id as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid title alias")
    }
}

/** The two actual More menus retain their original target, route, producer and epoch. */
suspend fun loadDetailsMenuSnapshot(target: DetailsTarget, origin: TitleListOrigin, publish: (DetailsMenuSnapshot) -> Unit) =
    readDetailsMenuSnapshot(target, origin, RawDetailsMenuReads(), publish)

internal suspend fun readDetailsMenuSnapshot(
    target: DetailsTarget,
    origin: TitleListOrigin,
    reads: DetailsMenuReads,
    publish: (DetailsMenuSnapshot) -> Unit,
): Unit = coroutineScope {
    suspend fun checkSelection() {
        currentCoroutineContext().ensureActive()
        ProfileDataContext.requireCurrent(origin.context)
        if (origin.target !== target || !origin.isCurrent) throw CancellationException("Details menu changed")
    }
    checkSelection()
    // A real producer child receives cancellation immediately, even while a sibling delays completion.
    val pending = async(origin.parent, start = CoroutineStart.LAZY) {
        try {
            checkSelection()
            val lists = reads.lists(origin.context).also { checkSelection() }
            val candidates = lists.filter { it.isSetAside }
            check(candidates.size <= 1) { "Set Aside list is ambiguous" }
            val parked = candidates.singleOrNull()?.let { list ->
                checkSelection()
                reads.items(list.id, origin.context).also { checkSelection() }.toList()
            }.orEmpty()
            checkSelection()
            val rows = reads.continuing(origin.context).also { checkSelection() }
            val visible = rows.filterNot { item -> parked.any { it.matches(item) } }
            val result = DetailsMenuSnapshot(
                inSetAside = parked.any { WatchlistStore.matches(it, target) },
                inContinueWatching = visible.any { it.isTitle(target) },
                loaded = true,
            )
            checkSelection()
            publish(result)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            checkSelection()
            publish(DetailsMenuSnapshot(notice = error.vodDenial?.message
                ?: "Unable to load title actions. Close and reopen this menu."))
        }
    }
    try { pending.start(); pending.await() } finally { pending.cancel() }
}


internal data class CachedCollectionRequest(val path: String, val query: Map<String, String>)
internal fun cachedCollectionRequest(profile: String, list: BrowseList.CollectionCached, limit: Int, offset: Int, options: BrowseOptions, extra: Map<String, String> = emptyMap()): CachedCollectionRequest {
    require(profile.isNotBlank())
    if (limit < 0 || offset < 0 || options.ratingSource != RatingSource.IMDB || extra.keys.any { it != "titleFilter" }) throw ManagementPageFailure()
    val query = try { options.query(list) } catch (_: IllegalArgumentException) { throw ManagementPageFailure() }
    return CachedCollectionRequest("/api/users/${ApiClient.segment(profile)}/display-list", list.query + mapOf("limit" to limit.toString(), "offset" to offset.toString()) + query + extra)
}
private suspend fun ApiClient.cachedCollectionPage(list: BrowseList.CollectionCached, limit: Int, offset: Int, extra: Map<String, String>, options: BrowseOptions, expected: Long): BrowsePage {
    if (expected != list.originalContext) throw kotlinx.coroutines.CancellationException("Collection entry changed")
    val request = inContext(expected) { cachedCollectionRequest(profileId, list, limit, offset, options, extra) }
    val raw = sendRaw("GET", request.path, request.query, expectedContext = expected)
    currentCoroutineContext().ensureActive(); ProfileDataContext.requireCurrent(expected)
    return decodeCachedCollectionPage(raw, list.id, limit, offset)
}
internal fun decodeCachedCollectionPage(raw: String, expectedID: Int, limit: Int? = null, offset: Int = 0, now: Long = System.currentTimeMillis()): BrowsePage = try {
    val root = ApiJson.parseToJsonElement(raw) as? JsonObject ?: throw ManagementPageFailure()
    fun text(value: JsonElement?) = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ManagementPageFailure()
    fun integer(value: JsonElement?) = (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw ManagementPageFailure()
    if (text(root["source"]) != "collection-cached") throw ManagementPageFailure()
    val support = root["originalLanguageFilterSupported"] as? JsonPrimitive
    if (support?.isString != false || support.booleanOrNull != true) throw ManagementPageFailure()
    val total = integer(root["total"]); val rows = root["items"] as? JsonArray ?: throw ManagementPageFailure()
    if (total < 0 || offset < 0 || rows.isNotEmpty() && offset.toLong() + rows.size > total) throw ManagementPageFailure()
    if (limit != null && rows.size != (if (limit == 0) maxOf(0, total - offset) else minOf(limit, maxOf(0, total - offset)))) throw ManagementPageFailure()
    val meta = root["collection"] as? JsonObject ?: throw ManagementPageFailure()
    val observed = CachedCollectionObservation(integer(meta["id"]), text(meta["name"]), text(meta["metadataLanguage"]), text(meta["observedAt"]), text(meta["expiresAt"]))
    if (observed.id != expectedID || expectedID <= 0 || observed.metadataLanguage.isBlank() || observed.metadataLanguage.length > 64) throw ManagementPageFailure()
    val time = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?(?:[Zz]|[+-][0-9]{2}:[0-9]{2})")
    if (!time.matches(observed.observedAt) || !time.matches(observed.expiresAt) || observed.observedMillis > now || observed.expiresMillis <= now || observed.expiresMillis <= observed.observedMillis || observed.expiresMillis - observed.observedMillis > 86_400_000L) throw ManagementPageFailure()
    val entries = rows.map { value ->
        val row = value as? JsonObject ?: throw ManagementPageFailure()
        val id = text(row["id"]); val name = text(row["name"]); val kind = text(row["mediaType"])
        val tmdb = Regex("tmdb:movie:([1-9][0-9]*)").matchEntire(id)?.groupValues?.get(1)?.toLongOrNull()
        if (tmdb == null || kind != "movie") throw ManagementPageFailure()
        row["tmdbId"]?.takeUnless { it == JsonNull }?.let {
            if (integer(it).toLong() != tmdb) throw ManagementPageFailure()
        }
        // Saved bare titles do not acquire artwork, source or watch-state claims.
        val year = row["year"]?.takeUnless { it == JsonNull }?.let(::integer)
        BrowseEntry.TitleEntry(Title(id, name, year = year, mediaType = kind))
    }
    val alphabet = root["alphabetBuckets"]?.takeUnless { it == JsonNull }?.let { (it as? JsonArray ?: throw ManagementPageFailure()).map(::text) }.orEmpty()
    BrowsePage(entries, total, letters = alphabet, originalLanguageFilterSupported = true, collection = observed).withStoredMediaCapability(root)
} catch (_: Exception) { throw ManagementPageFailure() }

internal data class CachedWatchingRequest(val path: String, val query: Map<String,String>)
internal fun cachedWatchingRequest(profile: String, list: BrowseList.ContinueWatchingCached, limit: Int, offset: Int, options: BrowseOptions, extra: Map<String,String> = emptyMap()): CachedWatchingRequest {
    if (profile.isBlank() || limit !in 0..4096 || offset !in 0..1_000_000 || extra.keys.any { it != "titleFilter" }) throw ManagementPageFailure()
    val selected = try { options.query(list) } catch (_: IllegalArgumentException) { throw ManagementPageFailure() }
    return CachedWatchingRequest("/api/users/${ApiClient.segment(profile)}/display-list", list.query + selected + extra + mapOf("limit" to limit.toString(), "offset" to offset.toString()))
}
private suspend fun ApiClient.cachedWatchingPage(list: BrowseList.ContinueWatchingCached, limit: Int, offset: Int, extra: Map<String,String>, options: BrowseOptions, expected: Long): BrowsePage {
    if (expected != list.originalContext) throw CancellationException("Saved feed entry changed")
    if (!list.live()) throw ManagementPageFailure()
    val request = inContext(expected) { cachedWatchingRequest(profileId, list, limit, offset, options, extra) }
    val response = sendObservedRaw(request.path, request.query, expected)
    currentCoroutineContext().ensureActive(); ProfileDataContext.requireCurrent(expected)
    return decodeCachedWatchingPage(response.text, response.clock ?: throw ManagementPageFailure(), limit, offset).also { list.accept(it.watching ?: throw ManagementPageFailure()) }
}

/** Strict whitelisted rows; malformed input loses the whole page, never its offsets. */
internal fun decodeCachedWatchingPage(raw: String, clock: com.strmr.tv.api.ObservedResponseClock, limit: Int? = null, offset: Int = 0): BrowsePage = try {
    if (raw.toByteArray(Charsets.UTF_8).size > 4 * 1024 * 1024) throw ManagementPageFailure()
    fun obj(value: JsonElement?, keys: Set<String>): JsonObject = (value as? JsonObject)?.also { if (it.keys.any { key -> key !in keys }) throw ManagementPageFailure() } ?: throw ManagementPageFailure()
    fun text(value: JsonElement?): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ManagementPageFailure()
    fun optional(row: JsonObject, key: String): String? = row[key]?.takeUnless { it == JsonNull }?.let(::text)
    fun integer(value: JsonElement?): Int = (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw ManagementPageFailure()
    fun number(value: JsonElement?): Double = (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: throw ManagementPageFailure()
    fun bool(value: JsonElement?): Boolean = (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw ManagementPageFailure()
    fun date(raw: String): Long {
        if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})").matches(raw)) throw ManagementPageFailure()
        return java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
    }
    fun token(value: String): String = value.also { if (!Regex("[a-zA-Z0-9][a-zA-Z0-9:._-]{0,511}").matches(it)) throw ManagementPageFailure() }
    fun episode(value: JsonElement?): CachedWatchingEpisode? {
        if (value == null || value == JsonNull) return null
        val row = obj(value, setOf("seasonNumber","episodeNumber","absoluteEpisodeNumber","episodeId","itemId","tvdbId","title","airDate","airDateTimeUTC","numbering"))
        val season = integer(row["seasonNumber"]); val episode = integer(row["episodeNumber"])
        if (season !in 0..1_000_000 || episode !in 1..1_000_000) throw ManagementPageFailure()
        val absolute = row["absoluteEpisodeNumber"]?.let(::integer)?.also { if (it !in 1..1_000_000) throw ManagementPageFailure() }
        val numbering = row["numbering"]?.takeUnless { it == JsonNull }?.let {
            val fields = obj(it, setOf("seriesId","ordering"))
            CachedWatchingNumbering(token(text(fields["seriesId"])), optional(fields,"ordering")?.let(::token))
        }
        optional(row,"airDate")?.let { java.time.LocalDate.parse(it) }
        optional(row,"airDateTimeUTC")?.let(::date)
        return CachedWatchingEpisode(season, episode, absolute, optional(row,"episodeId")?.let(::token), optional(row,"itemId")?.let(::token), optional(row,"tvdbId")?.let(::token), optional(row,"title"), optional(row,"airDate"), optional(row,"airDateTimeUTC"), numbering)
    }
    val root = obj(ApiJson.parseToJsonElement(raw), setOf("source","items","total","alphabetBuckets","originalLanguageFilterSupported","storedMediaFilterSupported","storedMediaFilterBasis","pool"))
    if (text(root["source"]) != "continue-watching-cached" || !bool(root["originalLanguageFilterSupported"])) throw ManagementPageFailure()
    val pool = obj(root["pool"], setOf("id","observedAt","expiresAt","complete"))
    val id = text(pool["id"]); val observed = text(pool["observedAt"]); val expires = text(pool["expiresAt"])
    val at = date(observed); val until = date(expires); val upper = clock.upperMillis()
    if (!Regex("[0-9a-f]{32}").matches(id) || !bool(pool["complete"]) || at > upper || until <= upper || until <= at || until - at > 600_000L) throw ManagementPageFailure()
    val observation = CachedWatchingObservation(id, observed, expires, clock)
    val total = integer(root["total"]); val items = root["items"] as? JsonArray ?: throw ManagementPageFailure()
    if (total !in 0..4096 || items.size > 4096 || offset < 0 || items.size.toLong()+offset > maxOf(total, offset).toLong()) throw ManagementPageFailure()
    if (limit != null && (limit !in 0..4096 || items.size != if (limit == 0) maxOf(0,total-offset) else minOf(limit,maxOf(0,total-offset)))) throw ManagementPageFailure()
    val seen = mutableSetOf<String>()
    val entries = items.map { value ->
        val row = obj(value, setOf("seriesId","seriesTitle","mediaType","year","externalIds","updatedAt","nextEpisode","lastWatched","resume","percentWatched","resumePercent"))
        val work = text(row["seriesId"]); val kind = text(row["mediaType"])
        if (kind !in setOf("movie","series") || !Regex("(?:tmdb:(?:movie|tv|series):[1-9][0-9]*|tvdb:(?:(?:movie|series):)?[1-9][0-9]*|imdb:(?:movie|series):tt[0-9]+)").matches(work) || !seen.add("$kind|$work")) throw ManagementPageFailure()
        if ((work.startsWith("tmdb:movie:") || work.startsWith("tvdb:movie:") || work.startsWith("imdb:movie:")) && kind != "movie" || (work.startsWith("tmdb:tv:") || work.contains(":series:")) && kind != "series") throw ManagementPageFailure()
        val aliases = row["externalIds"]?.takeUnless { it == JsonNull }?.let { obj(it,setOf("tmdb","tvdb","imdb")).mapValues { (key,v) -> text(v).also { if (!(if (key == "imdb") Regex("tt[0-9]+") else Regex("[1-9][0-9]*")).matches(it)) throw ManagementPageFailure() } } }.orEmpty()
        val updated = text(row["updatedAt"]); date(updated)
        val year = row["year"]?.let(::integer)?.also { if (it !in 0..9999) throw ManagementPageFailure() }
        val next = episode(row["nextEpisode"]); val last = episode(row["lastWatched"])
        if (kind == "movie" && (next != null || last != null)) throw ManagementPageFailure()
        val resume = row["resume"]?.takeUnless { it == JsonNull }?.let {
            val fields = obj(it,setOf("position","duration","percent","eligible"))
            val position=number(fields["position"]);val duration=number(fields["duration"]);val percent=number(fields["percent"])
            if (percent > 100.0 || duration > 0 && position > duration) throw ManagementPageFailure()
            com.strmr.tv.api.ResumeState(position,duration,percent,bool(fields["eligible"]))
        }
        fun percent(key:String) = row[key]?.let(::number)?.also { if (it > 100.0) throw ManagementPageFailure() }
        BrowseEntry.CachedWatchingEntry(CachedWatchingItem(work,text(row["seriesTitle"]),kind,year,aliases.toMap(),updated,next,last,resume,percent("percentWatched"),percent("resumePercent")))
    }
    val alphabet = root["alphabetBuckets"]?.let { (it as? JsonArray ?: throw ManagementPageFailure()).map(::text) }.orEmpty()
    BrowsePage(entries,total,letters=alphabet,originalLanguageFilterSupported=true,watching=observation).withStoredMediaCapability(root)
} catch (_: Exception) { throw ManagementPageFailure() }
