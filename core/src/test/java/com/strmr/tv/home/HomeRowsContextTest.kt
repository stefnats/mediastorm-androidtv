package com.strmr.tv.home

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeRowsContextTest {
    private fun shelf(source: HomeSource) = PlannedShelf(ShelfConfig("synthetic", name = "Synthetic"), source, 20, false, true)
    private class Reads : HomeShelfReads {
        val calls = mutableListOf<Pair<String, Long>>()
        var listsReply: suspend () -> List<CustomList> = { listOf(CustomList("aside", "Set Aside")) }
        override suspend fun page(list: BrowseList, count: Int, extra: Map<String, String>, phase: ShelfPhase?, expected: Long): BrowsePage {
            calls += "page" to expected; return BrowsePage(emptyList(), 0)
        }
        override suspend fun lists(expected: Long): List<CustomList> { calls += "lists" to expected; return listsReply() }
        override suspend fun items(id: String, expected: Long): List<WatchlistItem> { assertEquals("aside", id); calls += "items" to expected; return emptyList() }
        override suspend fun dashboard(expected: Long): List<DashboardStream> { calls += "dashboard" to expected; return emptyList() }
        override suspend fun hub(links: List<HomeSource.HubLink>, cap: Int, expected: Long): List<HomeCard> { calls += "hub" to expected; return emptyList() }
        override suspend fun liveFavorites(cap: Int, expected: Long): List<HomeCard> { calls += "live" to expected; return emptyList() }
        override suspend fun library(id: String, count: Int, mediaType: String?, expected: Long): LibraryGroupPage { calls += "library" to expected; return LibraryGroupPage() }
    }
    private suspend fun canceled(read: suspend () -> Any?) { try { read(); fail("Retired row survived") } catch (_: CancellationException) { } }
    @Test fun `queued shared Continue and Calendar loads reject old epoch before demand`() = runTest {
        var reads = 0; val expected = ProfileDataContext.current
        val context = HomeContext(HomeSettings(emptyList()), listOf(shelf(HomeSource.ContinueWatching), shelf(HomeSource.Calendar)), backgroundScope,
            expected, { reads++; emptyList() }, { _, _ -> reads++; emptyList() })
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); runCurrent()
        canceled { context.continueWatching() }; canceled { context.calendar() }; assertEquals(0, reads)
    }
    @Test fun `actual shared context reads each feed once under original owner`() = runTest {
        var continueReads = 0; var calendarReads = 0; val expected = ProfileDataContext.current
        val context = HomeContext(HomeSettings(emptyList()), listOf(shelf(HomeSource.ContinueWatching), shelf(HomeSource.Upcoming), shelf(HomeSource.Calendar)), backgroundScope,
            expected, { assertEquals(expected, it); continueReads++; listOf(ContinueWatchingItem("synthetic", "Synthetic")) }, { cap, owner -> assertEquals(20, cap); assertEquals(expected, owner); calendarReads++; emptyList() })
        assertEquals(1, context.continueWatching().size); context.continueWatching(); context.calendar(); context.calendar()
        assertEquals(1, continueReads); assertEquals(1, calendarReads)
    }
    @Test fun `late shared Continue reply cannot become launcher-prefetched data`() = runTest {
        val reply = CompletableDeferred<Unit>(); val expected = ProfileDataContext.current
        val context = HomeContext(HomeSettings(emptyList()), listOf(shelf(HomeSource.ContinueWatching)), backgroundScope,
            expected, { withContext(NonCancellable) { reply.await() }; listOf(ContinueWatchingItem("old", "Old")) }, { _, _ -> emptyList() })
        runCurrent(); ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); reply.complete(Unit)
        canceled { context.loadedContinueWatching() }
    }
    @Test fun `optional shared feed preserves terminal denial and cancellation`() = runTest { supervisorScope {
        val denied = ApiError.Http(HttpFailure(403, "{}"))
        val context = HomeContext(HomeSettings(emptyList()), listOf(shelf(HomeSource.ContinueWatching)), this,
            ProfileDataContext.current, { throw denied }, { _, _ -> emptyList() })
        try { context.loadedContinueWatching(); fail("Denial swallowed") } catch (error: ApiError) { assertSame(denied, error) }
        val canceledContext = HomeContext(HomeSettings(emptyList()), listOf(shelf(HomeSource.ContinueWatching)), this,
            ProfileDataContext.current, { throw CancellationException("synthetic") }, { _, _ -> emptyList() })
        canceled { canceledContext.loadedContinueWatching() }
    } }
    @Test fun `actual PlannedShelf request families all forward original owner`() = runTest {
        val reads = Reads(); val expected = ProfileDataContext.current
        val context = HomeContext(HomeSettings(emptyList()), emptyList(), backgroundScope, expected, { emptyList() }, { _, _ -> emptyList() }, reads)
        val sources = listOf(HomeSource.Watchlist, HomeSource.SetAside, HomeSource.List(BrowseList.Watchlist, false), HomeSource.Dashboard,
            HomeSource.Library(LibraryRef("synthetic", "Synthetic")), HomeSource.LiveFavorites, HomeSource.Hub(emptyList()))
        sources.forEach { shelf(it).load(context) }
        assertEquals(listOf("page", "lists", "items", "page", "dashboard", "library", "live", "hub"), reads.calls.map { it.first })
        assertTrue(reads.calls.all { it.second == expected })
    }
    @Test fun `actual Set Aside shelf cannot start items after discovery retires`() = runTest {
        val reads = Reads(); val reply = CompletableDeferred<Unit>(); val expected = ProfileDataContext.current
        reads.listsReply = { withContext(NonCancellable) { reply.await() }; listOf(CustomList("aside", "Set Aside")) }
        val context = HomeContext(HomeSettings(emptyList()), emptyList(), backgroundScope, expected, { emptyList() }, { _, _ -> emptyList() }, reads)
        val loading = async { shelf(HomeSource.SetAside).load(context) }; runCurrent()
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); reply.complete(Unit)
        canceled { loading.await() }; assertEquals(listOf("lists"), reads.calls.map { it.first })
    }
    @Test fun `stale actual shelf skips every IO family before demand`() = runTest {
        val reads = Reads(); val expected = ProfileDataContext.current
        val context = HomeContext(HomeSettings(emptyList()), emptyList(), backgroundScope, expected, { emptyList() }, { _, _ -> emptyList() }, reads)
        ProfileDataContext.invalidate()
        listOf(HomeSource.Watchlist, HomeSource.SetAside, HomeSource.Dashboard, HomeSource.LiveFavorites).forEach { source -> canceled { shelf(source).load(context) } }
        assertTrue(reads.calls.isEmpty())
    }
    @Test fun `terminal Set Aside discovery never tries items or alternative shelf`() = runTest {
        val reads = Reads(); reads.listsReply = { throw ApiError.Http(HttpFailure(403, "{}")) }
        val context = HomeContext(HomeSettings(emptyList()), emptyList(), backgroundScope, ProfileDataContext.current, { emptyList() }, { _, _ -> emptyList() }, reads)
        try { shelf(HomeSource.SetAside).load(context); fail("Denied shelf succeeded") } catch (_: ApiError) { }
        assertEquals(listOf("lists"), reads.calls.map { it.first })
    }
    @Test fun `actual stale Home APIs stop before Android stores and transport`() = runTest {
        val expected = ProfileDataContext.current; ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        canceled { ApiClient.homeSettings(expectedContext = expected) }; canceled { ApiClient.homeRevision(expected) }
        canceled { ApiClient.homeCalendar(20, expected) }; canceled { ApiClient.dashboardStreams(expected) }
        canceled { ApiClient.favoriteChannelCards(20, expected) }
        assertEquals(0, ApiClient.http.dispatcher.runningCallsCount()); assertEquals(0, ApiClient.http.dispatcher.queuedCallsCount())
    }
}
