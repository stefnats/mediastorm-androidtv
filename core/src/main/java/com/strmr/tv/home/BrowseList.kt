package com.strmr.tv.home

import com.strmr.tv.api.LibraryEntry
import com.strmr.tv.api.LibraryRef
import com.strmr.tv.api.Title
import kotlinx.serialization.Serializable

enum class BrowseKind(val mediaType: String, val noun: String) {
    MOVIES("movie", "Movies"),
    SHOWS("series", "Shows"),
}

/** A list Home opens as a grid (explore cards, services, hubs, Ask AI), read through `display-list`. */
sealed class BrowseList {
    data class Custom(val id: String, val name: String) : BrowseList()
    data object Watchlist : BrowseList()
    data object ContinueWatching : BrowseList()
    /** Explicit saved-feed page; acceptance captures the old page before navigation hides it. */
    class ContinueWatchingCached private constructor(val originalContext: Long) : BrowseList() {
        private var originalPool: Triple<String,String,String>? = null
        private var deadline = Long.MAX_VALUE
        private var lastClock: com.strmr.tv.api.ObservedResponseClock? = null
        @Synchronized internal fun live(): Boolean = originalPool == null || System.nanoTime() < deadline
        /** The entry, including replacement page models, retains its first observation. */
        @Synchronized internal fun accept(observation: CachedWatchingObservation): Long {
            val tuple = Triple(observation.id, observation.observedAt, observation.expiresAt)
            val remaining = observation.clock.remainingMillis(observation.expiresMillis)
            if (!live() || originalPool?.let { it != tuple } == true || lastClock?.let { observation.clock.predates(it) } == true ||
                remaining <= 0 || remaining > 600_000L || observation.observedMillis > observation.clock.upperMillis() || observation.expiresMillis-observation.observedMillis > 600_000L) throw ManagementPageFailure()
            val boundedDeadline = minOf(deadline, observation.clock.deadlineNanos(observation.expiresMillis))
            if (System.nanoTime() >= boundedDeadline) throw ManagementPageFailure()
            deadline = boundedDeadline
            if (originalPool == null) originalPool = tuple
            lastClock = observation.clock
            return deadline
        }
        companion object {
            fun open(expected: Long, producer: kotlinx.coroutines.Job, selected: () -> Boolean): ContinueWatchingCached? = try {
                com.strmr.tv.api.ApiClient.inContext(expected) { if (!producer.isActive || !selected()) null else ContinueWatchingCached(expected) }
            } catch (_: kotlinx.coroutines.CancellationException) { null }
        }
    }
    /** Explicit stored shared activity; no retained pool or provider refresh. */
    class RecentlyWatchedCached private constructor(
        val originalContext: Long,
        internal val originalClient: String,
        internal val parameters: RecentActivitySelection,
    ) : BrowseList() {
        companion object {
            fun open(origin: BrowseList, query: Map<String, String>, expected: Long,
                     producer: kotlinx.coroutines.Job, selected: () -> Boolean,
                     clientIdentity: () -> String = { com.strmr.tv.app.Config.clientId }): RecentlyWatchedCached? = try {
                com.strmr.tv.api.ApiClient.inContext(expected) {
                    if (!producer.isActive || !selected()) null
                    else recentActivitySelection(origin, query)?.let {
                        RecentlyWatchedCached(expected, clientIdentity().also { id -> require(id.isNotBlank()) }, it)
                    }
                }
            } catch (_: kotlinx.coroutines.CancellationException) { null }
              catch (_: IllegalArgumentException) { null }
        }
    }
    /** Titles hidden from this profile ([com.strmr.tv.library.HiddenItemsStore]), where they can be unhidden. */
    data object Hidden : BrowseList()
    /** Titles the server keeps resolved until they are removed. */
    data object PermanentPrequeue : BrowseList()
    /** Complete visible saved Love ratings, separate from the existing enriched Loved view. */
    data object LovedStored : BrowseList()
    /** Explicit cache-only collection browse. The entry epoch is correlation, not permission. */
    class CollectionCached private constructor(val id: Int, val name: String, val originalContext: Long) : BrowseList() {
        companion object {
            fun open(ref: com.strmr.tv.api.CollectionRef, expected: Long, producer: kotlinx.coroutines.Job, selected: () -> Boolean): CollectionCached? = try {
                com.strmr.tv.api.ApiClient.inContext(expected) {
                    if (ref.id <= 0 || !producer.isActive || !selected()) null else CollectionCached(ref.id, ref.name, expected)
                }
            } catch (_: kotlinx.coroutines.CancellationException) { null }
        }
    }
    /** Movies and shows together when the kind is null. */
    data class TopTen(val kind: BrowseKind? = null) : BrowseList()
    data class Trending(val kind: BrowseKind) : BrowseList()
    data object PopularOnServer : BrowseList()
    /** The server's recommendations from the profile's watch history. */
    data object Personalized : BrowseList()
    data class Genre(val genreId: Int, val name: String, val kind: BrowseKind) : BrowseList()
    data class Decade(val decade: Int, val kind: BrowseKind) : BrowseList()
    /** A list configured as a home shelf (MDBList, Stremio, TMDB, Trakt…), with its query. */
    data class Remote(val id: String, val name: String, val params: Map<String, String>) : BrowseList()
    /** A streaming service's top lists; the grid shows the first (movies) list. */
    /** [part] indexes [parts]: All (every list, merged) first when the service has several. */
    data class Service(val service: StreamingService, val part: Int = 0) : BrowseList() {
        val parts: List<String>
            get() = service.lists.mapIndexed { i, l -> l.title ?: "List ${i + 1}" }.let { if (it.size > 1) listOf("All") + it else it }

        /** The lists [part] shows: all of them for All, else the one. */
        val lists: List<StreamingService.ServiceList>
            get() = when {
                service.lists.size <= 1 -> service.lists
                part == 0 -> service.lists
                else -> listOfNotNull(service.lists.getOrNull(part - 1))
            }
    }
    /** "Because You Watched": titles like a Continue Watching title, with its backdrop for the tile. */
    data class Similar(val tmdbId: String, val kind: BrowseKind, val seed: String, val backdrop: String?) : BrowseList()
    /** A local, Plex or Jellyfin library, paged from `/api/library` ([com.strmr.tv.api.libraryPage]). */
    data class Library(val library: LibraryRef) : BrowseList()

    val title: String
        get() = when (this) {
            is Custom -> name
            Watchlist -> "Watchlist"
            ContinueWatching -> "Continue Watching"
            is ContinueWatchingCached -> "Saved Continue Watching"
            is RecentlyWatchedCached -> "Saved Recently Watched"
            Hidden -> "Hidden"
            PermanentPrequeue -> "Permanent Prequeue"
            LovedStored -> "Saved Love Ratings"
            is CollectionCached -> name
            is TopTen -> when (kind) { null -> "Top 10 Today"; BrowseKind.MOVIES -> "Top 10 Movies"; BrowseKind.SHOWS -> "Top 10 Shows" }
            is Trending -> if (kind == BrowseKind.MOVIES) "Trending Movies" else "Trending Shows"
            PopularOnServer -> "Popular on This Server"
            Personalized -> "Recommended for You"
            is Genre -> name
            is Decade -> "${decade}s ${kind.noun}"
            is Remote -> name
            is Service -> service.name + (lists.singleOrNull()?.takeIf { service.lists.size > 1 }?.title?.let { " · $it" } ?: "")
            is Similar -> "Because You Watched $seed"
            is Library -> library.name
        }

    /**
     * MDBList-backed lists, whose cold pages the server returns in about a second when it skips
     * artwork analysis (`lite`, `deferArtwork`), as the Expo app asks for them. The grid shows
     * that page, then fills its art (text posters) from the full one.
     */
    val quickPageQuery: Map<String, String>?
        get() = when {
            this is Service -> quickQuery
            this is Remote && params["source"]?.lowercase() == "mdblist" -> quickQuery
            else -> null
        }

    /** Remote lists the server can page without reading the whole source first. */
    val loadsProgressively: Boolean
        get() = when (this) {
            is Remote -> params["source"]?.lowercase() in progressiveSources
            is Service -> true
            else -> false
        }

    /** How the source's `display-list` items are shaped: list items, or ranked titles. */
    val itemShaped: Boolean get() = this is Custom || this == Watchlist || this == PermanentPrequeue

    /** Sources whose items are bare titles (not ranked entries). */
    val titleShaped: Boolean get() = this is Similar

    /** `display-list` query parameters, without paging. */
    val query: Map<String, String>
        get() = when (this) {
            is Custom -> mapOf("source" to "custom-list", "listId" to id)
            Watchlist -> mapOf("source" to "watchlist")
            ContinueWatching -> mapOf("source" to "continue-watching")
            is ContinueWatchingCached -> mapOf("source" to "continue-watching-cached")
            is RecentlyWatchedCached -> mapOf("source" to "recently-watched-cached")
            Hidden -> mapOf("source" to "hidden")
            PermanentPrequeue -> mapOf("source" to "permanent-prequeue")
            LovedStored -> mapOf("source" to "loved-stored")
            is CollectionCached -> mapOf("source" to "collection-cached", "collectionId" to id.toString())
            is TopTen -> mapOf("source" to "top-ten") + (kind?.let { mapOf("mediaType" to it.mediaType) } ?: emptyMap())
            is Trending -> mapOf("source" to "trending", "mediaType" to kind.mediaType)
            PopularOnServer -> mapOf("source" to "popular-on-server")
            Personalized -> mapOf("source" to "personalized")
            is Genre -> mapOf("source" to "genre", "genreId" to genreId.toString(), "mediaType" to kind.mediaType)
            is Decade -> mapOf("source" to "decade", "decade" to decade.toString(), "mediaType" to kind.mediaType)
            is Remote -> params
            is Service -> mapOf("source" to "mdblist", "name" to service.name, "url" to (lists.singleOrNull()?.url ?: ""))
            is Similar -> mapOf("source" to "similar", "tmdbId" to tmdbId, "mediaType" to kind.mediaType)
            is Library -> emptyMap()
        }

    companion object {
        private val quickQuery = mapOf("lite" to "true", "deferArtwork" to "true")
        private val progressiveSources = setOf(
            "mdblist", "trakt-list", "stremio", "simkl-list", "letterboxd-list", "publicmetadb-list", "tmdb-list",
        )

        /**
         * The server's AI picks for a description (`display-list?source=custom-ai&q=`), cached
         * server-side per description, so paging doesn't ask again.
         */
        fun askAI(prompt: String): BrowseList {
            val name = "Ask AI: $prompt"
            return Remote("custom-ai", name, mapOf("source" to "custom-ai", "q" to prompt, "name" to name))
        }
    }
}

/**
 * How much of a progressive list the server prepares before answering, as the Expo home asks
 * for it: the first cards fast, then the same page with full metadata and the exact total.
 */
enum class ShelfPhase(val value: String) {
    CARDS("cards"),
    COMPLETE("complete");

    val query: Map<String, String>
        get() = mapOf(
            "shelfPhase" to value, "lite" to "true", "deferArtwork" to (this == CARDS).toString(),
            "artworkLimit" to if (this == CARDS) "20" else "50",
        )
}

/** A watchlist / custom list entry (`display-list?source=watchlist`). */
@Serializable
data class WatchlistItem(
    val id: String,
    val mediaType: String = "movie",
    val name: String = "",
    val overview: String? = null,
    val year: Int? = null,
    val posterUrl: String? = null,
    val textPosterUrl: String? = null,
    val backdropUrl: String? = null,
    /** The backdrop with its title text, and ranked alternates: the launcher's 16:9 art. */
    val textBackdropUrl: String? = null,
    val backdropUrls: List<String>? = null,
    val logoUrl: String? = null,
    val externalIds: Map<String, String>? = null,
    /** none | partial | complete */
    val watchState: String? = null,
    /** Series: released episodes not yet watched (when the server knows the episode total). */
    val unwatchedCount: Int? = null,
    val status: String? = null,
    val releaseDate: String? = null,
    val availability: String? = null,
    val cardSubtitle: String? = null,
    val forceTitleOverlay: Boolean = false,
    val sourceAvailability: com.strmr.tv.api.SourceAvailability? = null,
) {
    val isMovie: Boolean get() = mediaType == "movie"
}

/** A list entry: discovery sources send titles, custom lists send list items. */
sealed interface BrowseEntry {
    data class TitleEntry(val title: Title, val storedLove: StoredLoveRow? = null) : BrowseEntry
    data class ItemEntry(val item: WatchlistItem) : BrowseEntry
    data class CachedWatchingEntry(val item: CachedWatchingItem) : BrowseEntry
    data class WatchingEntry(val item: com.strmr.tv.api.ContinueWatchingItem) : BrowseEntry
    data class LibraryItemEntry(val entry: LibraryEntry) : BrowseEntry
}

/** Saved presentation/ordinal bookkeeping only; never a client edit or content grant. */
data class StoredLoveRow(val rank: Int, val updatedAt: String)

/** A page of a list, with the genre facets and first letters present when the source filters server-side. */
data class BrowsePage(val entries: List<BrowseEntry>, val total: Int, val genres: List<String> = emptyList(), val letters: List<String> = emptyList(),
    /** Explicit support for complete stored-list filtering before server pagination. */
    val originalLanguageFilterSupported: Boolean = false,
    val collection: CachedCollectionObservation? = null,
    val watching: CachedWatchingObservation? = null,
) {
    /**
     * The list's size once [fetched] items have been read, this page's included. Servers return
     * short pages mid-list (TMDB genre and decade pages are capped at 50; hidden, kids-rated and
     * unreleased titles are dropped after the read), so a page shorter than asked for doesn't end
     * the list: only an empty page, or reaching the total, does.
     */
    fun listSize(fetched: Int): Int = if (entries.isEmpty()) fetched else maxOf(total, fetched)
}

/** A custom list (`GET /api/users/{id}/custom-lists`). */
@Serializable
data class CustomList(val id: String, val name: String, val itemCount: Int? = null) {
    /** The reserved "Set Aside" list: titles parked out of Continue Watching. */
    val isSetAside: Boolean get() = name.trim().equals("Set Aside", ignoreCase = true)
}

/** Provider collection-pool observation only; neither an immutable language view nor a grant. */
data class CachedCollectionObservation(val id: Int, val name: String, val metadataLanguage: String, val observedAt: String, val expiresAt: String) {
    val observedMillis: Long get() = java.time.OffsetDateTime.parse(observedAt).toInstant().toEpochMilli()
    val expiresMillis: Long get() = java.time.OffsetDateTime.parse(expiresAt).toInstant().toEpochMilli()
}

/** Exact saved history coordinates; none of these fields is a mutation receipt. */
data class CachedWatchingNumbering(val seriesId: String, val ordering: String?)
data class CachedWatchingEpisode(val seasonNumber: Int, val episodeNumber: Int, val absoluteEpisodeNumber: Int?, val episodeId: String?, val itemId: String?, val tvdbId: String?, val title: String?, val airDate: String?, val airDateTimeUTC: String?, val numbering: CachedWatchingNumbering?)
data class CachedWatchingItem(val seriesId: String, val seriesTitle: String, val mediaType: String, val year: Int?, val externalIds: Map<String,String>, val updatedAt: String, val nextEpisode: CachedWatchingEpisode?, val lastWatched: CachedWatchingEpisode?, val resume: com.strmr.tv.api.ResumeState?, val percentWatched: Double?, val resumePercent: Double?)
data class CachedWatchingObservation(val id: String, val observedAt: String, val expiresAt: String, val clock: com.strmr.tv.api.ObservedResponseClock) {
    val observedMillis get() = java.time.OffsetDateTime.parse(observedAt).toInstant().toEpochMilli()
    val expiresMillis get() = java.time.OffsetDateTime.parse(expiresAt).toInstant().toEpochMilli()
    fun samePool(other: CachedWatchingObservation) = id == other.id && observedAt == other.observedAt && expiresAt == other.expiresAt
}
