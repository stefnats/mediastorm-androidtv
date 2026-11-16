package com.strmr.tv.api

import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.home.BrowseEntry
import com.strmr.tv.home.BrowseOptions
import com.strmr.tv.home.BrowsePage
import com.strmr.tv.home.BrowseSort
import com.strmr.tv.home.ManagementPageFailure
import com.strmr.tv.home.originalLanguageQuery
import kotlinx.serialization.json.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

// The server's local, Plex and Jellyfin libraries. Every call names the profile: the server checks
// it against each library's access list and a kids profile's rating caps.

/** `GET /api/library/libraries`: the libraries this profile may browse. */
suspend fun ApiClient.libraries(): List<LibraryInfo> =
    sendList("GET", "/api/library/libraries", mapOf("profileId" to profileId))

/**
 * One page of a library's titles, sorted and filtered by the server. `cards` asks for the light
 * card shape (no files, no per-episode metadata), as the Expo rows and grid do.
 */
suspend fun ApiClient.libraryGroups(
    libraryId: String, limit: Int, offset: Int = 0, sort: String? = null, ascending: Boolean = false,
    letter: String? = null, mediaType: String? = null, search: String? = null, cards: Boolean = true, expectedContext: Long = ProfileDataContext.current,
): LibraryGroupPage {
    val query = libraryGroupsQuery(expectedContext, limit, offset, sort, ascending, letter, mediaType, search, cards)
    return send("GET", "/api/library/libraries/${segment(libraryId)}/groups", query, expectedContext = expectedContext)
}

/** Construct selectors while the same request owner still holds the API context lock. */
internal fun ApiClient.libraryGroupsQuery(
    expectedContext: Long, limit: Int, offset: Int, sort: String?, ascending: Boolean,
    letter: String?, mediaType: String?, search: String?, cards: Boolean,
): Map<String, String> = inContext(expectedContext) {
    mutableMapOf("limit" to limit.toString(), "offset" to offset.toString(), "userId" to profileId).apply {
        if (sort != null) {
            put("sort", sort)
            put("dir", if (ascending) "asc" else "desc")
        }
        letter?.let { put("alphabet", it) }
        mediaType?.takeIf { it != "all" }?.let { put("mediaType", it) }
        search?.takeIf { it.isNotEmpty() }?.let { put("query", it) }
        if (cards) put("include", "cards")
    }
}

/**
 * A grid page of a library (`BrowseList.Library`). Default and Date Added are newest first for
 * every source (the server's own default is A–Z for Plex and Jellyfin).
 */
suspend fun ApiClient.libraryPage(
    library: LibraryRef, options: BrowseOptions?, limit: Int, offset: Int,
    expectedContext: Long = ProfileDataContext.current,
): BrowsePage {
    currentCoroutineContext().ensureActive()
    ProfileDataContext.requireCurrent(expectedContext)
    val selected = options ?: BrowseOptions()
    val remote = library.sourceType?.trim()?.lowercase() in setOf("plex", "jellyfin")
    if (remote && selected.originalLanguageActive) throw ManagementPageFailure()
    var support = false
    val page = readLibraryPage(library, selected, limit, offset, expectedContext) { id, count, start, sort, ascending, letter, expected ->
        val query = inContext(expected) { libraryCardsQuery(profileId, count, start, sort, ascending, letter, selected) }
        val path = inContext(expected) { libraryGroupsPath(id) }
        val raw = sendRaw("GET", path, query, expectedContext = expected)
        decodeLibraryCardPage(raw, remote, selected.originalLanguageActive).also { support = it.supported }.page
    }
    if (selected.originalLanguageActive && !support) throw ManagementPageFailure()
    return page.copy(originalLanguageFilterSupported = support)
}

internal fun libraryGroupsPath(id: String): String = "/api/library/libraries/${ApiClient.segment(id)}/groups"
internal fun libraryCardsQuery(
    profile: String, limit: Int, offset: Int, sort: String, ascending: Boolean, letter: String?, options: BrowseOptions,
): Map<String, String> = buildMap {
    put("userId", profile)
    put("limit", limit.toString())
    put("offset", offset.toString())
    put("sort", sort)
    put("dir", if (ascending) "asc" else "desc")
    put("include", "cards")
    letter?.let { put("alphabet", it) }
    putAll(options.originalLanguageQuery())
}
internal data class LibraryCardPage(val page: LibraryGroupPage, val supported: Boolean, val rawGroups: JsonArray?)
internal fun decodeLibraryCardPage(raw: String, remote: Boolean = false, requireSupport: Boolean = false): LibraryCardPage {
    var strict = requireSupport
    try {
        val root = ApiJson.parseToJsonElement(raw) as? JsonObject ?: throw ManagementPageFailure()
        val marker = root["originalLanguageFilterSupported"] as? JsonPrimitive
        val supported = !remote && marker?.isString == false && marker.booleanOrNull == true
        if (requireSupport && !supported) throw ManagementPageFailure()
        strict = strict || supported
        if (supported) {
            root["alphabetBuckets"]?.takeUnless { it == JsonNull }?.let { buckets ->
                if (buckets !is JsonArray || buckets.any { (it as? JsonPrimitive)?.isString != true }) throw ManagementPageFailure()
            }
            val total = (root["total"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            val groups = root["groups"] as? JsonArray
            if (total == null || total < 0 || groups == null || total < groups.size) throw ManagementPageFailure()
            for (entry in groups) {
                val row = entry as? JsonObject ?: throw ManagementPageFailure()
                for (key in listOf("id", "title", "groupType", "libraryType")) {
                    val value = row[key] as? JsonPrimitive
                    if (value?.isString != true || key == "id" && value.content.isBlank()) throw ManagementPageFailure()
                }
                for (key in listOf("groupType", "libraryType", "overview", "imdbId", "matchStatus")) row[key]?.takeUnless { it == JsonNull }?.let {
                    if ((it as? JsonPrimitive)?.isString != true) throw ManagementPageFailure()
                }
                val versions = (row["itemCount"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                if (versions == null || versions < 0) throw ManagementPageFailure()
                for (key in listOf("year", "tmdbId", "tvdbId", "itemCount", "missingCount", "totalSizeBytes")) row[key]?.takeUnless { it == JsonNull }?.let {
                    val value = (it as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw ManagementPageFailure()
                    if (key in listOf("itemCount", "missingCount", "totalSizeBytes") && value < 0) throw ManagementPageFailure()
                }
                for (key in listOf("confidenceMin", "confidenceMax")) row[key]?.takeUnless { it == JsonNull }?.let {
                    val value = (it as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: throw ManagementPageFailure()
                    if (!value.isFinite()) throw ManagementPageFailure()
                }
                for (key in listOf("poster", "textPoster", "backdrop", "textBackdrop")) row[key]?.takeUnless { it == JsonNull }?.let {
                    val image = it as? JsonObject ?: throw ManagementPageFailure()
                    if ((image["url"] as? JsonPrimitive)?.isString != true) throw ManagementPageFailure()
                }
                for (key in listOf("items", "seasons")) row[key]?.takeUnless { it == JsonNull }?.let {
                    if ((it as? JsonArray)?.isNotEmpty() != false) throw ManagementPageFailure()
                }
            }
        }
        return LibraryCardPage(ApiJson.decodeFromJsonElement<LibraryGroupPage>(root), supported, root["groups"] as? JsonArray)
    } catch (error: Exception) {
        if (strict) throw ManagementPageFailure()
        throw error
    }
}

internal suspend fun readLibraryPage(
    library: LibraryRef, options: BrowseOptions?, limit: Int, offset: Int, expectedContext: Long,
    readGroups: suspend (String, Int, Int, String, Boolean, String?, Long) -> LibraryGroupPage,
): BrowsePage {
    currentCoroutineContext().ensureActive()
    ProfileDataContext.requireCurrent(expectedContext)
    val (sort, ascending) = when (options?.sort) {
        BrowseSort.NAME -> "name" to options.ascending
        BrowseSort.YEAR -> "year" to options.ascending
        BrowseSort.ADDED -> "created" to options.ascending
        else -> "created" to false
    }
    val letter = options?.letter?.takeIf { options.sort == BrowseSort.NAME }
    val page = readGroups(library.id, limit, offset, sort, ascending, letter, expectedContext)
    currentCoroutineContext().ensureActive()
    ProfileDataContext.requireCurrent(expectedContext)
    return BrowsePage(page.groups.map { BrowseEntry.LibraryItemEntry(LibraryEntry(library, it)) }, page.total,
                      letters = page.alphabetBuckets.orEmpty())
}

/** A title's copies in the profile's libraries: by its catalog ids, else its title and year. */
suspend fun ApiClient.libraryMatches(target: DetailsTarget,expectedContext:Long=ProfileDataContext.current): List<LibraryMatch> {
    val profile=inContext(expectedContext) {profileId}
    val q = mutableMapOf("mediaType" to if (target.isMovie) "movie" else "series", "title" to target.name, "profileId" to profile)
    if (target.hasCatalogIds) q["titleId"] = target.titleId
    target.tmdbId?.let { q["tmdbId"] = it }
    target.imdbId?.let { q["imdbId"] = it }
    target.tvdbId?.let { q["tvdbId"] = it }
    target.year?.let { q["year"] = it.toString() }
    return sendList("GET", "/api/library/matches", q, timeoutMs = 8_000,expectedContext=expectedContext)
}

/**
 * One library file's stream path. The server first checks a local file can be played (422 when
 * it can't). `profileName` attributes the stream on the admin dashboard.
 */
suspend fun ApiClient.libraryPlayback(itemId: String, expectedContext: Long = ProfileDataContext.current): LibraryPlaybackInfo {
    val query = inContext(expectedContext) {
        mutableMapOf("profileId" to profileId).apply {
            profileName?.takeIf { it.isNotEmpty() }?.let { put("profileName", it) }
        }
    }
    return send("GET", "/api/library/items/${segment(itemId)}/playback", query, timeoutMs = 60_000, expectedContext = expectedContext)
}

/**
 * A card's group with its files (rows and grids carry none). The API has no call for one group,
 * so this searches the library for the title and keeps only an exact id match.
 */
suspend fun ApiClient.libraryGroup(entry: LibraryEntry): LibraryGroup {
    if (entry.group.hasFiles) return entry.group
    val page = libraryGroups(entry.library.id, limit = 200, search = entry.group.title, cards = false)
    return page.groups.firstOrNull { it.id == entry.group.id } ?: throw ApiError.NotReady("This title is no longer in the library.")
}

/** The server's resume decision for one progress record; null when there is none. */
suspend fun ApiClient.resumeState(mediaType: String, itemId: String, expectedContext:Long=ProfileDataContext.current): ResumeState? {
    val path=inContext(expectedContext) {"$profilePath/history/progress/${segment(mediaType)}/${segment(itemId)}"}
    return vodOptionalRead {send<ProgressResume>("GET",path,promptOnLock=false,expectedContext=expectedContext).resume}
}

@kotlinx.serialization.Serializable
data class ProgressResume(val resume: ResumeState? = null)
