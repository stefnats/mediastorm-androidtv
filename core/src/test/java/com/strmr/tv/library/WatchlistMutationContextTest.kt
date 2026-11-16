package com.strmr.tv.library

import com.strmr.tv.api.*
import com.strmr.tv.home.WatchlistItem
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** Actual Store/receipt/IO-port paths, not a second mutation implementation. */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchlistMutationContextTest {
    private val target = DetailsTarget("tmdb:movie:7", "Synthetic", true, tmdbId = "7")
    private fun item(id: String = target.titleId) = WatchlistItem(id, "movie", "Synthetic", externalIds = mapOf("tmdb" to "7"))
    private fun operation(t: DetailsTarget = target) = CustomListOperations(target = t).begin()!!
    private class Access : WatchlistAccess {
        var rows = emptyList<WatchlistItem>()
        var writing: suspend (Boolean) -> Unit = {}
        var reading: suspend () -> List<WatchlistItem> = { rows }
        val writes = mutableListOf<Pair<String, Boolean>>()
        var reads = 0
        override suspend fun write(target: DetailsTarget, saved: Boolean, operation: CustomListOperation) { writes += target.titleId to saved; writing(saved) }
        override suspend fun read(expectedContext: Long): List<WatchlistItem> { reads++; return reading() }
    }
    private suspend fun canceled(action: suspend () -> Unit) { assertTrue(runCatching { action() }.exceptionOrNull() is CancellationException) }
    @Before fun reset() = WatchlistStore.reset()
    @After fun after() = WatchlistStore.reset()

    @Test fun `positive add publishes confirmed original desired state and fetched row`() = runTest {
        val access = Access().apply { rows = listOf(item()) }
        WatchlistStore.setSavedOwned(target, true, operation(), access)
        assertEquals(listOf(target.titleId to true), access.writes); assertEquals(1, access.reads)
        assertEquals(listOf(item()), WatchlistStore.items); assertTrue(WatchlistStore.contains(target, false)); assertTrue(WatchlistStore.isLoaded); assertNull(WatchlistStore.loadError)
    }
    @Test fun `visible desired add is not reinterpreted when queued behind another same-owner add`() = runTest {
        val access = Access(); val gate = CompletableDeferred<Unit>(); access.rows = listOf(item())
        access.writing = { if (access.writes.size == 1) gate.await() }
        val first = async { WatchlistStore.setSavedOwned(target, true, operation(), access) }; runCurrent()
        val second = async { WatchlistStore.setSavedOwned(target, true, operation(), access) }; runCurrent()
        assertEquals(1, access.writes.size); gate.complete(Unit); first.await(); second.await()
        assertEquals(listOf(true, true), access.writes.map { it.second }); assertTrue(WatchlistStore.contains(target))
    }
    @Test fun `same-owner pending mutation prevents stale refresh publishing rows or error`() = runTest {
        WatchlistStore.refresh { listOf(item()) }
        val access = Access(); val gate = CompletableDeferred<Unit>(); access.writing = { gate.await() }
        val mutation = async { WatchlistStore.setSavedOwned(target, false, operation(), access) }; runCurrent()
        assertTrue(WatchlistStore.items.isEmpty())
        WatchlistStore.refresh { listOf(item("stale")) }; assertTrue(WatchlistStore.items.isEmpty())
        gate.complete(Unit); mutation.await(); assertTrue(WatchlistStore.items.isEmpty()); assertFalse(WatchlistStore.contains(target, true))
    }
    @Test fun `refresh begun before successful mutation cannot restore stale row afterwards`() = runTest {
        val reply = CompletableDeferred<List<WatchlistItem>>()
        val old = async { WatchlistStore.refresh { withContext(NonCancellable) { reply.await() } } }; runCurrent()
        WatchlistStore.setSavedOwned(target, true, operation(), Access().apply { rows = listOf(item()) })
        reply.complete(emptyList()); old.await()
        assertEquals(listOf(item()), WatchlistStore.items); assertTrue(WatchlistStore.contains(target))
    }
    @Test fun `failed old mutation finishes before later write and cannot rollback later success`() = runTest {
        WatchlistStore.refresh { listOf(item()) }
        val access = Access(); val fail = CompletableDeferred<Unit>(); access.writing = { if (access.writes.size == 1) { fail.await(); throw IOException("synthetic") } }
        val first = async { runCatching { WatchlistStore.setSavedOwned(target, false, operation(), access) } }; runCurrent()
        val other = target.copy(titleId = "tmdb:movie:8", tmdbId = "8")
        access.rows = listOf(item(), WatchlistItem(other.titleId, "movie", "Other"))
        val second = async { WatchlistStore.setSavedOwned(other, true, operation(other), access) }; runCurrent()
        fail.complete(Unit); assertTrue(first.await().isFailure); second.await()
        assertEquals(2, WatchlistStore.items.size); assertTrue(WatchlistStore.contains(other)); assertTrue(WatchlistStore.contains(target))
    }
    @Test fun `alias remove followed by overlapping add replaces old confirmed suppression`() = runTest {
        val alias = target.copy(titleId = "imdb:tt0000007", imdbId = "tt0000007")
        WatchlistStore.refresh { listOf(item()) }
        WatchlistStore.setSavedOwned(target, false, operation(), Access())
        assertFalse(WatchlistStore.contains(alias, true))
        val row = item(alias.titleId)
        WatchlistStore.setSavedOwned(alias, true, operation(alias), Access().apply { rows = listOf(row) })
        assertEquals(listOf(row), WatchlistStore.items); assertTrue(WatchlistStore.contains(target, false)); assertTrue(WatchlistStore.contains(alias, false))
    }
    @Test fun `404 delete is successful but ordinary and terminal write failures never start readback`() = runTest {
        val access = Access().apply { writing = { throw ApiError.Http(HttpFailure(404, "Missing")) } }
        WatchlistStore.setSavedOwned(target, false, operation(), access); assertEquals(1, access.reads); assertFalse(WatchlistStore.contains(target, true))
        for (status in listOf(401, 403, 500)) {
            val fail = Access().apply { writing = { throw ApiError.Http(HttpFailure(status, "Synthetic")) } }
            assertNotNull(runCatching { WatchlistStore.setSavedOwned(target, true, operation(), fail) }.exceptionOrNull()); assertEquals(0, fail.reads)
        }
    }
    @Test fun `strict owned readback terminal error is propagated with no confirmed success`() = runTest {
        val failure = ApiError.Http(HttpFailure(403, """{"code":"kids_vod_access_denied"}"""))
        val access = Access().apply { reading = { throw failure } }
        assertSame(failure, runCatching { WatchlistStore.setSavedOwned(target, true, operation(), access) }.exceptionOrNull())
        assertFalse(WatchlistStore.contains(target, false)); assertFalse(WatchlistStore.isLoaded)
    }
    @Test fun `malformed visible readback preserves accepted intent but never publishes partial rows`() = runTest {
        val good = """{"id":"tmdb:movie:7","mediaType":"movie","name":"Synthetic"}"""
        for (raw in listOf("{}", """{"source":"watchlist","items":null,"total":0}""", """{"source":"watchlist","items":[$good,{}],"total":2}""", """{"source":"watchlist","items":[$good],"total":"1"}""")) {
            val access = Access().apply { reading = { decodeOwnedWatchlist(raw) } }
            assertNotNull(runCatching { WatchlistStore.setSavedOwned(target, true, operation(), access) }.exceptionOrNull())
            assertTrue(WatchlistStore.items.isEmpty()); assertTrue(WatchlistStore.contains(target, false)); assertFalse(WatchlistStore.isLoaded)
        }
    }
    @Test fun `late original write after simulated A B A neither reads nor rolls back replacement owner`() = runTest {
        val access = Access(); val reply = CompletableDeferred<Unit>(); access.writing = { withContext(NonCancellable) { reply.await() } }
        val old = async { WatchlistStore.setSavedOwned(target, false, operation(), access) }; runCurrent()
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        WatchlistStore.refresh { listOf(WatchlistItem("new-owner", "movie", "New")) }
        reply.complete(Unit); canceled { old.await() }
        assertEquals(0, access.reads); assertEquals("new-owner", WatchlistStore.items.single().id)
    }
    @Test fun `queued old receipt and different target instance perform zero writes`() = runTest {
        val op = operation(); val access = Access()
        assertNotNull(runCatching { WatchlistStore.setSavedOwned(target.copy(), true, op, access) }.exceptionOrNull())
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        canceled { WatchlistStore.setSavedOwned(target, true, op, access) }; assertTrue(access.writes.isEmpty())
    }
    @Test fun `canceled masked read cannot publish saved state`() = runTest {
        val reply = CompletableDeferred<List<WatchlistItem>>(); val access = Access().apply { reading = { withContext(NonCancellable) { reply.await() } } }
        val task = async { WatchlistStore.setSavedOwned(target, true, operation(), access) }; runCurrent(); task.cancel(); reply.complete(listOf(item())); canceled { task.await() }
        assertTrue(WatchlistStore.items.isEmpty()); assertFalse(WatchlistStore.contains(target, false))
    }
    @Test fun `legacy background denial ends attempted loading with static error and keeps existing rows`() = runTest {
        WatchlistStore.refresh { throw ApiError.Http(HttpFailure(403, "private diagnostic")) }
        assertTrue(WatchlistStore.isLoaded); assertNotNull(WatchlistStore.loadError); assertTrue(WatchlistStore.items.isEmpty())
        WatchlistStore.refresh { listOf(item()) }; WatchlistStore.refresh { throw IOException("synthetic") }
        assertEquals(listOf(item()), WatchlistStore.items); assertNull(WatchlistStore.loadError)
    }
    @Test fun `fresh background read after mutation can reconcile a later remote membership change`() = runTest {
        WatchlistStore.setSavedOwned(target, false, operation(), Access())
        assertFalse(WatchlistStore.contains(target, true))
        WatchlistStore.refresh { listOf(item()) }
        assertEquals(listOf(item()), WatchlistStore.items); assertTrue(WatchlistStore.contains(target, false))
    }
    @Test fun `accepted add remains saved when display-list legitimately suppresses its row`() = runTest {
        WatchlistStore.setSavedOwned(target, true, operation(), Access())
        assertTrue(WatchlistStore.contains(target, false)); assertTrue(WatchlistStore.isLoaded)
        assertTrue(WatchlistStore.items.isEmpty()); assertNull(WatchlistStore.loadError)
    }
    @Test fun `later successful empty visible projection does not erase accepted positive membership`() = runTest {
        WatchlistStore.setSavedOwned(target, true, operation(), Access().apply { rows = listOf(item()) })
        WatchlistStore.refresh { emptyList() }
        assertTrue(WatchlistStore.contains(target, false)); assertTrue(WatchlistStore.items.isEmpty()); assertNull(WatchlistStore.loadError)
    }
    @Test fun `first mutation of new owner does not inherit previous loaded or error flags`() = runTest {
        WatchlistStore.refresh { throw IOException("synthetic old owner") }
        assertTrue(WatchlistStore.isLoaded); assertNotNull(WatchlistStore.loadError)
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        val access = Access().apply { writing = { throw ApiError.Http(HttpFailure(403, "Synthetic")) } }
        assertNotNull(runCatching { WatchlistStore.setSavedOwned(target, true, operation(), access) }.exceptionOrNull())
        assertFalse(WatchlistStore.isLoaded); assertNull(WatchlistStore.loadError); assertTrue(WatchlistStore.items.isEmpty())
    }
    @Test fun `accepted write then ordinary refresh failure keeps intent and old rows with honest error`() = runTest {
        val unrelated = WatchlistItem("other", "movie", "Preserved")
        WatchlistStore.refresh { listOf(unrelated) }
        val access = Access().apply { reading = { throw IOException("synthetic private transport error") } }
        val error = runCatching { WatchlistStore.setSavedOwned(target, true, operation(), access) }.exceptionOrNull()
        assertTrue(error is WatchlistRefreshFailure); assertTrue(WatchlistStore.contains(target, false))
        assertEquals(listOf(unrelated), WatchlistStore.items); assertTrue(WatchlistStore.isLoaded)
        assertEquals("Change saved, but the watchlist could not be refreshed. Try refreshing it again.", watchlistFailureMessage(error as Exception))
    }
    @Test fun `strict decoder retains valid row and rejects malformed identity metadata and dropped rows`() {
        val valid = """{"source":"watchlist","items":[{"id":"tmdb:movie:7","mediaType":"movie","name":"Synthetic","year":2020,"externalIds":{"tmdb":"7"}}],"total":1}"""
        assertEquals(listOf(item().copy(year = 2020)), decodeOwnedWatchlist(valid))
        for (raw in listOf(valid.replace("\"movie\"", "7"), valid.replace("\"Synthetic\"", "true"), valid.replace("2020", "\"2020\""), valid.replace("\"7\"", "7"), valid.replace("\"total\":1", "\"total\":0"))) assertTrue(runCatching { decodeOwnedWatchlist(raw) }.isFailure)
    }
}
