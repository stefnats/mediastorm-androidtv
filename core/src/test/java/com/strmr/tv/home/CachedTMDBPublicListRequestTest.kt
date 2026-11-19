package com.strmr.tv.home

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class CachedTMDBPublicListRequestTest {
    @Test fun `actual configured Home and Connected List produce an explicit canonical saved-list request`() {
        val shelf = ShelfConfig("synthetic-shelf", "Synthetic list", type = "tmdb", tmdbSourceType = "public-list", tmdbSourceId = "77", tmdbMediaType = "all", sort = "title.asc")
        val normal = (HomeSource.from(shelf, listOf(shelf), HomeSettings(listOf(shelf))) as HomeSource.List).list
        assertEquals(shelf.browseList, normal)
        val list = requireNotNull(BrowseList.TMDBPublicListCached.open(normal, mapOf("homeView" to "movies"), ProfileDataContext.current, Job(), { true }, { "synthetic-client" }))
        assertEquals(BrowseKind.MOVIES, list.initialKind)
        val selected = cachedTMDBPublicListRequest("profile/a", list, 60, 650, BrowseOptions(sort = BrowseSort.NAME, ascending = true, kind = BrowseKind.SHOWS, originalLanguages = setOf("fr", "nob"), includeUnknownOriginalLanguage = true))
        assertEquals("/api/users/profile%2Fa/display-list", selected.path)
        assertEquals(mapOf("source" to "tmdb-public-list-cached", "listId" to "77", "limit" to "60", "offset" to "650", "sortBy" to "name", "sortDirection" to "asc", "filterMediaType" to "series", "originalLanguages" to "fra,nob", "includeUnknownOriginalLanguage" to "true"), selected.query)
        assertEquals("tmdb-list", normal.query["source"]); assertEquals("public-list", normal.query["sourceType"]); assertEquals("77", normal.query["sourceId"])
        val large = PublicListFixture.list(Long.MAX_VALUE)
        assertEquals(mapOf("source" to "tmdb-public-list-cached", "listId" to Long.MAX_VALUE.toString(), "limit" to "0", "offset" to "2000000"), cachedTMDBPublicListRequest("profile", large, 0, 2_000_000, BrowseOptions()).query)
    }
    @Test fun `private URL unrelated sources and unsupported original intent cannot become public-list identity`() {
        val base = PublicListFixture.origin()
        for (pair in listOf("source" to "mdblist", "source" to "trakt-list", "sourceType" to "discover", "sourceType" to "person", "sourceType" to "public_list", "sourceId" to "https://www.themoviedb.org/list/77", "sourceId" to "tmdb:77", "sourceId" to "0", "sourceId" to "01", "sourceId" to "+77", "sourceId" to " 77", "sourceId" to "9223372036854775808", "accountId" to "private", "url" to "mediastorm:tmdb:77", "listId" to "77", "discoverQuery" to "language=fr", "hideUnreleased" to "true", "mediaType" to "unexpected")) {
            assertNull("Rejected original selector ${pair.first}", BrowseList.TMDBPublicListCached.open(base.copy(params = base.params + pair), emptyMap(), ProfileDataContext.current, Job(), { true }, { "synthetic-client" }))
        }
        for (extra in listOf(mapOf("hideWatched" to "true"), mapOf("hideUnreleased" to "true"), mapOf("homeView" to "invalid"), mapOf("profileId" to "foreign"), mapOf("sourceId" to "88"))) assertNull(BrowseList.TMDBPublicListCached.open(base, extra, ProfileDataContext.current, Job(), { true }, { "synthetic-client" }))
        assertNull(BrowseList.TMDBPublicListCached.open(BrowseList.PopularOnServer, emptyMap(), ProfileDataContext.current, Job(), { true }, { "synthetic-client" }))
        val expected = ProfileDataContext.current; val producer = Job()
        assertNull(BrowseList.TMDBPublicListCached.open(base, emptyMap(), expected, producer, { false }, { "synthetic-client" }))
        producer.cancel(); assertNull(BrowseList.TMDBPublicListCached.open(base, emptyMap(), expected, producer, { true }, { "synthetic-client" }))
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        assertNull(BrowseList.TMDBPublicListCached.open(base, emptyMap(), expected, Job(), { true }, { "synthetic-client" }))
    }
    @Test fun `custom Home view kind must be currently resolved rather than broadening to all`() {
        val origin = PublicListFixture.origin()
        for ((raw, expected) in listOf("all" to null, "movies" to BrowseKind.MOVIES, "shows" to BrowseKind.SHOWS)) {
            val result = tmdbPublicListSelection(origin, mapOf("homeView" to "page-synthetic"), mapOf("page-synthetic" to HomeViewSettings(mediaFilter = raw)))
            assertNotNull(result); assertEquals(expected, result!!.initialKind)
        }
        for (views in listOf(emptyMap(), mapOf("page-synthetic" to HomeViewSettings(mediaFilter = "movies", deleted = true)), mapOf("page-synthetic" to HomeViewSettings()), mapOf("page-synthetic" to HomeViewSettings(mediaFilter = "unexpected")))) assertTrue(runCatching { tmdbPublicListSelection(origin, mapOf("homeView" to "page-synthetic"), views) }.isFailure)
        assertTrue(runCatching { tmdbPublicListSelection(PublicListFixture.origin(kind = BrowseKind.MOVIES), mapOf("homeView" to "shows")) }.isFailure)
    }
    @Test fun `unsupported request options cannot silently invoke a normal provider list`() {
        val list = PublicListFixture.list()
        for (options in listOf(BrowseOptions(watch = WatchFilter.WATCHED), BrowseOptions(genres = setOf("Drama")), BrowseOptions(sort = BrowseSort.YEAR), BrowseOptions(sort = BrowseSort.RATING), BrowseOptions(ratingSource = RatingSource.TMDB), BrowseOptions(letter = "AB"), BrowseOptions(originalLanguages = setOf("not-a-language")))) assertTrue(runCatching { cachedTMDBPublicListRequest("profile", list, 1, 0, options) }.isFailure)
        for (key in listOf("source", "sourceType", "sourceId", "listId", "mediaType", "profileId", "url", "homeView", "hideWatched", "hideUnreleased", "includeUnwatchedCounts", "includeFacets")) assertTrue(runCatching { cachedTMDBPublicListRequest("profile", list, 1, 0, BrowseOptions(), mapOf(key to "synthetic")) }.isFailure)
        assertTrue(runCatching { cachedTMDBPublicListRequest("profile", list, -1, 0, BrowseOptions()) }.isFailure)
        assertEquals("needle", cachedTMDBPublicListRequest("profile", list, 1, 0, BrowseOptions(), mapOf("titleFilter" to "needle")).query["titleFilter"])
    }
    @Test fun `actual observed sender guard admits only three explicit current-profile cached GET sources`() {
        val selected = cachedTMDBPublicListRequest("profile", PublicListFixture.list(), 1, 0, BrowseOptions())
        requireObservedBrowseRequest("GET", selected.path, "/api/users/profile", selected.query, true)
        for (source in listOf("continue-watching-cached", "similar-cached")) requireObservedBrowseRequest("GET", selected.path, "/api/users/profile", mapOf("source" to source), true)
        for (source in listOf("tmdb-list", "similar", "collection-cached", "recently-watched-cached", "popular-on-server-cached", "", "TMDB-Public-List-Cached")) assertTrue(runCatching { requireObservedBrowseRequest("GET", selected.path, "/api/users/profile", mapOf("source" to source), true) }.isFailure)
        for ((method, path, expected) in listOf(Triple("POST", selected.path, true), Triple("GET", "/api/users/foreign/display-list", true), Triple("GET", selected.path, false))) assertTrue(runCatching { requireObservedBrowseRequest(method, path, "/api/users/profile", selected.query, expected) }.isFailure)
    }
}
