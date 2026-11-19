package com.strmr.tv.home

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.ui.lists.seeds
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class CachedSimilarRequestTest {
    @Test fun `actual Because You Watched seed explicitly opens original saved source and canonical filters`() {
        val origin = seeds(listOf(ContinueWatchingItem("tmdb:movie:77", "Synthetic seed", externalIds = mapOf("tmdb" to "77")))).single()
        val list = requireNotNull(BrowseList.SimilarCached.open(origin, ProfileDataContext.current, Job(), { true }, { "synthetic-client" }))
        val selected = cachedSimilarRequest("profile/a", list, 60, 650, BrowseOptions(sort = BrowseSort.NAME, ascending = true, kind = BrowseKind.SHOWS, originalLanguages = setOf("fr", "nob"), includeUnknownOriginalLanguage = true))
        assertEquals("/api/users/profile%2Fa/display-list", selected.path)
        assertEquals(mapOf("source" to "similar-cached", "tmdbId" to "77", "mediaType" to "movie", "limit" to "60", "offset" to "650", "sortBy" to "name", "sortDirection" to "asc", "filterMediaType" to "series", "originalLanguages" to "fra,nob", "includeUnknownOriginalLanguage" to "true"), selected.query)
        assertEquals("similar", origin.query["source"])
        assertEquals(mapOf("source" to "similar-cached", "tmdbId" to "77", "mediaType" to "movie", "limit" to "0", "offset" to "2000000"), cachedSimilarRequest("profile", list, 0, 2_000_000, BrowseOptions()).query)
        val large = SimilarFixture.list(Long.MAX_VALUE, BrowseKind.SHOWS)
        assertEquals(Long.MAX_VALUE.toString(), cachedSimilarRequest("profile", large, 1, 0, BrowseOptions()).query["tmdbId"])
        assertEquals("series", large.query["mediaType"])
    }
    @Test fun `invalid seed page client producer and ABA cannot manufacture a different saved view`() {
        for (id in listOf("0", "-1", "01", "+1", " 77", "9223372036854775808", "tmdb:77")) assertNull(BrowseList.SimilarCached.open(SimilarFixture.origin(id), ProfileDataContext.current, Job(), { true }, { "synthetic-client" }))
        val expected = ProfileDataContext.current; val producer = Job()
        assertNull(BrowseList.SimilarCached.open(BrowseList.PopularOnServer, expected, producer, { true }, { "synthetic-client" }))
        assertNull(BrowseList.SimilarCached.open(SimilarFixture.origin(), expected, producer, { false }, { "synthetic-client" }))
        assertNull(BrowseList.SimilarCached.open(SimilarFixture.origin(), expected, producer, { true }, { "" }))
        producer.cancel(); assertNull(BrowseList.SimilarCached.open(SimilarFixture.origin(), expected, producer, { true }, { "synthetic-client" }))
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        assertNull(BrowseList.SimilarCached.open(SimilarFixture.origin(), expected, Job(), { true }, { "synthetic-client" }))
    }
    @Test fun `unsupported metadata and owner parameters fail instead of falling back to normal Similar`() {
        val list = SimilarFixture.list()
        for (options in listOf(BrowseOptions(watch = WatchFilter.WATCHED), BrowseOptions(genres = setOf("Drama")), BrowseOptions(sort = BrowseSort.YEAR), BrowseOptions(sort = BrowseSort.RATING), BrowseOptions(ratingSource = RatingSource.TMDB), BrowseOptions(letter = "AB"), BrowseOptions(originalLanguages = setOf("not-a-language")))) assertTrue(runCatching { cachedSimilarRequest("profile", list, 1, 0, options) }.isFailure)
        for (key in listOf("source", "tmdbId", "mediaType", "profileId", "url", "homeView", "hideWatched", "hideUnreleased", "includeUnwatchedCounts", "includeFacets")) assertTrue(runCatching { cachedSimilarRequest("profile", list, 1, 0, BrowseOptions(), mapOf(key to "synthetic")) }.isFailure)
        assertTrue(runCatching { cachedSimilarRequest("profile", list, -1, 0, BrowseOptions()) }.isFailure)
        assertEquals("needle", cachedSimilarRequest("profile", list, 1, 0, BrowseOptions(), mapOf("titleFilter" to "needle")).query["titleFilter"])
    }
    @Test fun `actual observed sender guard permits only explicit original profile cached GET sources`() {
        for (source in listOf("continue-watching-cached", "similar-cached")) requireObservedBrowseRequest("GET", "/api/users/profile/display-list", "/api/users/profile", mapOf("source" to source), true)
        for (source in listOf("similar", "collection-cached", "recently-watched-cached", "popular-on-server-cached", "", "Similar-Cached")) assertTrue(runCatching { requireObservedBrowseRequest("GET", "/api/users/profile/display-list", "/api/users/profile", mapOf("source" to source), true) }.isFailure)
        for ((method, path, expected) in listOf(Triple("POST", "/api/users/profile/display-list", true), Triple("GET", "/api/users/foreign/display-list", true), Triple("GET", "/api/users/profile/display-list", false))) assertTrue(runCatching { requireObservedBrowseRequest(method, path, "/api/users/profile", mapOf("source" to "similar-cached"), expected) }.isFailure)
    }
}
