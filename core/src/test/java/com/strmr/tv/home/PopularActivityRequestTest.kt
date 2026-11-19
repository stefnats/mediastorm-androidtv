package com.strmr.tv.home

import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class PopularActivityRequestTest {
    @Test fun `real Home shelf and Lists entry explicitly choose cached popularity with view threshold`() {
        val shelf = ShelfConfig("popular-on-server", "Popular", activityWindowDays = 365, minimumProfiles = 3, maxItemsPerProfile = 4)
        val origin = (HomeSource.from(shelf, listOf(shelf), HomeSettings(listOf(shelf))) as HomeSource.List).list
        val saved = PopularActivityFixture.list(origin, mapOf("homeView" to "shows", "hideUnreleased" to "true", "hideWatched" to "true"))
        assertEquals(BrowseKind.SHOWS, saved.parameters.initialKind)
        val request = popularActivityRequest("profile/a", saved, 60, 650, BrowseOptions(sort = BrowseSort.NAME, ascending = true, originalLanguages = setOf("fr", "nob"), includeUnknownOriginalLanguage = true))
        assertEquals("/api/users/profile%2Fa/display-list", request.path)
        assertEquals(mapOf("source" to "popular-on-server-cached", "activityWindowDays" to "365", "minimumViews" to "3", "hideWatched" to "true", "sortBy" to "name", "sortDirection" to "asc", "originalLanguages" to "fra,nob", "includeUnknownOriginalLanguage" to "true", "limit" to "60", "offset" to "650"), request.query)
        val defaults = popularActivityRequest("profile", PopularActivityFixture.list(BrowseList.PopularOnServer), 0, 2_000_000, BrowseOptions())
        assertEquals(mapOf("source" to "popular-on-server-cached", "limit" to "0", "offset" to "2000000"), defaults.query)
        assertEquals("popular-on-server", origin.query["source"])
        assertEquals(mapOf("source" to "popular-on-server"), BrowseList.PopularOnServer.query)
    }
    @Test fun `threshold aliases agree and window boundaries do not adopt invalid source parameters`() {
        for ((key, value) in listOf("activityWindowDays" to "6", "activityWindowDays" to "366", "activityWindowDays" to "07", "minimumViews" to "0", "minimumViews" to "101", "minimumProfiles" to "01")) {
            assertNull(BrowseList.PopularOnServerCached.open(PopularActivityFixture.origin.copy(params = PopularActivityFixture.origin.params + (key to value)), emptyMap(), ProfileDataContext.current, Job(), { true }, { "synthetic-client" }))
        }
        val conflicting = PopularActivityFixture.origin.copy(params = PopularActivityFixture.origin.params + mapOf("minimumViews" to "2", "minimumProfiles" to "3"))
        assertNull(BrowseList.PopularOnServerCached.open(conflicting, emptyMap(), ProfileDataContext.current, Job(), { true }, { "synthetic-client" }))
        for (threshold in listOf(1, 100)) {
            val origin = PopularActivityFixture.origin.copy(params = PopularActivityFixture.origin.params + mapOf("minimumViews" to "$threshold", "minimumProfiles" to "$threshold", "activityWindowDays" to "7"))
            val query = popularActivityRequest("profile", PopularActivityFixture.list(origin), 1, 0, BrowseOptions()).query
            assertEquals("$threshold", query["minimumViews"]); assertFalse(query.containsKey("minimumProfiles"))
        }
    }
    @Test fun `unsupported options and owner extras cannot silently change a saved popular request`() {
        val list = PopularActivityFixture.list()
        for (options in listOf(BrowseOptions(watch = WatchFilter.WATCHED), BrowseOptions(genres = setOf("Drama")), BrowseOptions(sort = BrowseSort.YEAR), BrowseOptions(sort = BrowseSort.RATING), BrowseOptions(ratingSource = RatingSource.TMDB), BrowseOptions(letter = "AB"), BrowseOptions(originalLanguages = setOf("not-a-language")))) {
            assertTrue(runCatching { popularActivityRequest("profile", list, 1, 0, options) }.isFailure)
        }
        for (key in listOf("source", "profileId", "url", "homeView", "maxItemsPerProfile", "minimumProfiles", "includeUnwatchedCounts", "includeFacets", "hideUnreleased")) assertTrue(runCatching { popularActivityRequest("profile", list, 1, 0, BrowseOptions(), mapOf(key to "synthetic")) }.isFailure)
        assertTrue(runCatching { popularActivityRequest("profile", list, -1, 0, BrowseOptions()) }.isFailure)
        assertEquals("needle", popularActivityRequest("profile", list, 1, 0, BrowseOptions(), mapOf("titleFilter" to "needle")).query["titleFilter"])
    }
    @Test fun `source factory rejects retired page producer and profile ABA without adopting another source`() {
        val expected = ProfileDataContext.current; val producer = Job()
        assertNull(BrowseList.PopularOnServerCached.open(RecentActivityFixture.origin, emptyMap(), expected, producer, { true }, { "synthetic-client" }))
        assertNull(BrowseList.PopularOnServerCached.open(PopularActivityFixture.origin, emptyMap(), expected, producer, { false }, { "synthetic-client" }))
        producer.cancel()
        assertNull(BrowseList.PopularOnServerCached.open(PopularActivityFixture.origin, emptyMap(), expected, producer, { true }, { "synthetic-client" }))
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        assertNull(BrowseList.PopularOnServerCached.open(PopularActivityFixture.origin, emptyMap(), expected, Job(), { true }, { "synthetic-client" }))
    }
}
