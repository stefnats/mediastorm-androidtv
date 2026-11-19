package com.strmr.tv.home

import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class RecentActivityRequestTest {
    @Test fun `real Home recent metadata opens explicit saved source and drops normal producer flags`() {
        val origin = BrowseList.Remote("recently-watched", "Recently Watched", mapOf("source" to "recently-watched", "name" to "Recently Watched", "activityWindowDays" to "30", "maxItemsPerProfile" to "4", "minimumProfiles" to "2", "lite" to "true"))
        val list = RecentActivityFixture.list(origin, mapOf("homeView" to "movies", "hideUnreleased" to "true", "hideWatched" to "true"))
        assertEquals(BrowseKind.MOVIES, list.parameters.initialKind)
        val request = recentActivityRequest("profile/a", list, 60, 650, BrowseOptions(sort = BrowseSort.NAME, ascending = true, originalLanguages = setOf("fr", "nob"), includeUnknownOriginalLanguage = true))
        assertEquals("/api/users/profile%2Fa/display-list", request.path)
        assertEquals(mapOf("source" to "recently-watched-cached", "activityWindowDays" to "30", "maxItemsPerProfile" to "4", "hideWatched" to "true", "sortBy" to "name", "sortDirection" to "asc", "originalLanguages" to "fra,nob", "includeUnknownOriginalLanguage" to "true", "limit" to "60", "offset" to "650"), request.query)
        assertEquals("recently-watched", origin.params["source"])
    }
    @Test fun `canonical zero full view and offsets have no discovery or pool cap`() {
        val list = RecentActivityFixture.list()
        val request = recentActivityRequest("profile", list, 0, 2_000_000, BrowseOptions(includeUnknownOriginalLanguage = true))
        assertEquals("0", request.query["limit"]); assertEquals("2000000", request.query["offset"])
        assertFalse(request.query.containsKey("activityWindowDays")); assertFalse(request.query.containsKey("maxItemsPerProfile"))
        for (options in listOf(BrowseOptions(watch = WatchFilter.WATCHED), BrowseOptions(genres = setOf("Drama")), BrowseOptions(sort = BrowseSort.YEAR), BrowseOptions(sort = BrowseSort.RATING), BrowseOptions(ratingSource = RatingSource.TMDB), BrowseOptions(letter = "AB"), BrowseOptions(originalLanguages = setOf("not-a-language")))) {
            assertTrue(runCatching { recentActivityRequest("profile", list, 1, 0, options) }.isFailure)
        }
        for (key in listOf("source", "profileId", "url", "homeView", "includeUnwatchedCounts", "includeFacets", "hideUnreleased")) assertTrue(runCatching { recentActivityRequest("profile", list, 1, 0, BrowseOptions(), mapOf(key to "synthetic")) }.isFailure)
        assertTrue(runCatching { recentActivityRequest("profile", list, -1, 0, BrowseOptions()) }.isFailure)
    }
    @Test fun `actual entry factory rejects wrong source stale page producer and profile ABA`() {
        val expected = ProfileDataContext.current; val producer = Job()
        assertNull(BrowseList.RecentlyWatchedCached.open(BrowseList.PopularOnServer, emptyMap(), expected, producer, { true }, { "synthetic-client" }))
        assertNull(BrowseList.RecentlyWatchedCached.open(RecentActivityFixture.origin, emptyMap(), expected, producer, { false }, { "synthetic-client" }))
        producer.cancel()
        assertNull(BrowseList.RecentlyWatchedCached.open(RecentActivityFixture.origin, emptyMap(), expected, producer, { true }, { "synthetic-client" }))
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        assertNull(BrowseList.RecentlyWatchedCached.open(RecentActivityFixture.origin, emptyMap(), expected, Job(), { true }, { "synthetic-client" }))
    }
    @Test fun `invalid shelf values cannot become a different request and normal Home flags remain unchanged`() {
        for ((key, value) in listOf("activityWindowDays" to "91", "activityWindowDays" to "01", "maxItemsPerProfile" to "0", "maxItemsPerProfile" to "21")) {
            val normal = RecentActivityFixture.origin.copy(params = RecentActivityFixture.origin.params + (key to value))
            assertNull(BrowseList.RecentlyWatchedCached.open(normal, emptyMap(), ProfileDataContext.current, Job(), { true }, { "synthetic-client" }))
        }
        assertEquals(mapOf("source" to "popular-on-server"), BrowseList.PopularOnServer.query)
        assertFalse(BrowseList.PopularOnServer.originalLanguageEligible)
        assertEquals("recently-watched", browseQuery(RecentActivityFixture.origin, 60, 0)["source"])
    }
}
