package com.strmr.tv.library

import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.home.WatchlistItem
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Existing accepted-menu and selection receipts consumed by the actual Watchlist UI. */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchlistMenuContextTest {
    private val target = DetailsTarget("tmdb:movie:7", "Synthetic", true)
    @Test fun `normal accepted menu dismiss retains selected add intention`() = runTest {
        WatchlistStore.reset(); val producer = Job(); val origin = TitleListOrigin(target, ProfileDataContext.current, producer)
        var writes = 0; var dismiss = 0
        val access = object : WatchlistAccess {
            override suspend fun write(target: DetailsTarget, saved: Boolean, operation: CustomListOperation) { assertTrue(saved); writes++ }
            override suspend fun read(expectedContext: Long) = listOf(WatchlistItem(target.titleId, "movie", "Synthetic"))
        }
        val action = TitleListAction(origin, backgroundScope) { WatchlistStore.setSavedOwned(target, true, it, access) }
        val task = action.commit(action.accept()!!) { origin.dismiss { dismiss++ } }!!
        task.join(); assertEquals(1, writes); assertEquals(1, dismiss); assertTrue(WatchlistStore.contains(target)); producer.cancel(); WatchlistStore.reset()
    }
    @Test fun `accepted normal dismiss allows only current original error publication`() = runTest {
        val producer = Job(); val origin = TitleListOrigin(target, ProfileDataContext.current, producer)
        var errors = 0; var dismissed = false
        val action = TitleListAction(origin, backgroundScope) { operation ->
            assertTrue(dismissed); assertFalse(origin.isCurrent)
            operation.publish { errors++ }
        }
        action.commit(action.accept()!!) { origin.dismiss { dismissed = true } }!!.join()
        assertEquals(1, errors); producer.cancel()
    }
    @Test fun `canceled hide and original page replacement cannot dispatch accepted old action`() = runTest {
        val producer = Job(); var selected = true; val origin = TitleListOrigin(target, ProfileDataContext.current, producer) { selected }
        var writes = 0; val action = TitleListAction(origin, backgroundScope) { writes++ }
        val click = action.accept()!!
        assertTrue(runCatching { commitTitleListClick({ throw CancellationException("synthetic") }, click::abandon) { action.commit(click) {} } }.exceptionOrNull() is CancellationException)
        val replacement = action.accept()!!; selected = false
        assertNull(action.commit(replacement) {}); assertEquals(0, writes); producer.cancel()
    }
    @Test fun `old queued button receipt cannot write after reference-unequal target replacement`() = runTest {
        var selected: DetailsTarget = target; val operations = CustomListOperations(target = target) { selected === target }
        val operation = operations.begin(key = "watchlist")!!; selected = target.copy()
        var writes = 0; val task = async { operations.run(operation) { writes++ } }
        assertTrue(runCatching { task.await() }.exceptionOrNull() is CancellationException); assertEquals(0, writes)
    }
    @Test fun `fresh visible selection generation works after cover while old busy publications remain retired`() = runTest {
        var selected = true; val old = CustomListOperations(target = target) { selected }; val operation = old.begin(key = "watchlist")!!
        selected = false; assertFalse(old.isCurrent); selected = true
        assertNull(old.begin(key = "watchlist")); var oldBusy = 0; assertFalse(operation.publish { oldBusy++ })
        val fresh = CustomListOperations(target = target) { selected }; val next = fresh.begin(key = "watchlist")!!
        var currentBusy = 0; fresh.run(next) { it.publish { currentBusy++ } }
        assertEquals(0, oldBusy); assertEquals(1, currentBusy)
    }
    @Test fun `original selection and producer before job prevent simulated A B A dispatch`() = runTest {
        val producer = Job(); val origin = TitleListOrigin(target, ProfileDataContext.current, producer)
        var writes = 0; val action = TitleListAction(origin, backgroundScope) { writes++ }; val click = action.accept()!!
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        assertNull(action.commit(click) {}); assertEquals(0, writes); producer.cancel()
    }
}
