package com.strmr.tv.home

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.rooms.RoomFixtures
import com.strmr.tv.rooms.WatchRoom
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WatchRoomShelfTest {
    private class Reads : HomeShelfReads {
        var rooms: suspend (Long) -> List<WatchRoom> = { listOf(RoomFixtures.room()) }
        override suspend fun rooms(expected: Long) = rooms.invoke(expected)
        override suspend fun page(list: BrowseList, count: Int, extra: Map<String, String>, phase: ShelfPhase?, expected: Long) = BrowsePage(emptyList(), 0)
        override suspend fun lists(expected: Long) = emptyList<CustomList>()
        override suspend fun items(id: String, expected: Long) = emptyList<WatchlistItem>()
        override suspend fun dashboard(expected: Long) = emptyList<DashboardStream>()
        override suspend fun hub(links: List<HomeSource.HubLink>, cap: Int, expected: Long) = emptyList<HomeCard>()
        override suspend fun liveFavorites(cap: Int, expected: Long) = emptyList<HomeCard>()
        override suspend fun library(id: String, count: Int, mediaType: String?, expected: Long) = LibraryGroupPage()
    }
    @Test fun configuredShelfIsUsable() = runTest {
        val config = ShelfConfig("watch-rooms", enabled = true)
        val settings = HomeSettings(listOf(config))
        assertEquals(HomeSource.WatchRooms, HomeSource.from(config, settings.shelves, settings))
        val shelf = PlannedShelf(config, HomeSource.WatchRooms, 20, false, true)
        val reads = Reads()
        val context = HomeContext(settings, listOf(shelf), backgroundScope, ProfileDataContext.current,
            { emptyList() }, { _, _ -> emptyList() }, reads)
        val row = requireNotNull(shelf.load(context))
        assertEquals(HomeLink.Action(HomeAction.WATCH_ROOMS), row.cards.single().link)
        assertFalse(row.cards.single().title.contains("Synthetic movie"))
    }
    @Test fun lateShelfRowsRetireWithProfile() = runTest {
        val hold = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val reads = Reads().apply { rooms = { entered.complete(Unit); withContext(NonCancellable) { hold.await() }; listOf(RoomFixtures.room()) } }
        val config = ShelfConfig("watch-rooms")
        val shelf = PlannedShelf(config, HomeSource.WatchRooms, 20, false, true)
        val context = HomeContext(HomeSettings(listOf(config)), listOf(shelf), backgroundScope, ProfileDataContext.current,
            { emptyList() }, { _, _ -> emptyList() }, reads)
        val loading = async { shelf.load(context) }
        entered.await(); ProfileDataContext.invalidate(); hold.complete(Unit)
        assertTrue(runCatching { loading.await() }.exceptionOrNull() is CancellationException)
    }
}
