package com.strmr.tv.home

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
    return decodeBrowsePage(raw, list)

}

/** Additive malformed capability never discards the containing legacy page. */
internal fun decodeBrowsePage(raw: String, list: BrowseList): BrowsePage {
    val root = ApiJson.parseToJsonElement(raw)
    val marker = ((root as? JsonObject)?.get("originalLanguageFilterSupported") as? JsonPrimitive)
    val supported = list.filters && marker?.isString == false && marker.booleanOrNull == true
    fun <T> page(value: DisplayListPage<T>, entry: (T) -> BrowseEntry) = BrowsePage(
        value.items.map(entry), maxOf(if (supported) value.total else value.sourceTotal ?: value.total, value.items.size), value.genres.orEmpty(), value.alphabetBuckets.orEmpty(), supported,
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
    (list.query + extra + options?.query(list).orEmpty()).toMutableMap().apply {
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
    BrowsePage(items.map { BrowseEntry.ItemEntry(it) }, total, letters = alphabet, originalLanguageFilterSupported = true)
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
