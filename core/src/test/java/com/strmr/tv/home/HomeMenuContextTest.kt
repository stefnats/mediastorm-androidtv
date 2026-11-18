package com.strmr.tv.home

import com.strmr.tv.api.*
import com.strmr.tv.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Actual adapter used by both More menus, with original receipt and synthetic IO ports. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeMenuContextTest {
    private val target = DetailsTarget("tmdb:movie:7", "Synthetic", true, imdbId = "tt7")
    private class Reads : DetailsMenuReads {
        val stages = mutableListOf<String>()
        val epochs = mutableListOf<Long>()
        var listRows = listOf(CustomList("aside", "Set Aside"))
        var parked = emptyList<WatchlistItem>()
        var rows = listOf(ContinueWatchingItem("tmdb:movie:7", "Synthetic"))
        var hook: suspend (String) -> Unit = {}
        private suspend fun stage(name: String, expected: Long) { stages += name; epochs += expected; hook(name) }
        override suspend fun lists(expected: Long): List<CustomList> { stage("lists", expected); return listRows }
        override suspend fun items(id: String, expected: Long): List<WatchlistItem> { assertEquals("aside", id); stage("items", expected); return parked }
        override suspend fun continuing(expected: Long): List<ContinueWatchingItem> { stage("continue", expected); return rows }
    }
    @Test fun `normal menu uses at most three original reads and no global aside publication`() = runTest {
        SetAsideStore.reset(); val global = SetAsideStore.items
        val parent = Job(backgroundScope.coroutineContext[Job]); val origin = TitleListOrigin(target, ProfileDataContext.current, parent)
        val reads = Reads(); val published = mutableListOf<DetailsMenuSnapshot>()
        readDetailsMenuSnapshot(target, origin, reads, published::add)
        assertEquals(listOf("lists", "items", "continue"), reads.stages); assertEquals(setOf(origin.context), reads.epochs.toSet())
        assertEquals(DetailsMenuSnapshot(inContinueWatching = true, loaded = true), published.single())
        assertEquals(global, SetAsideStore.items)
    }
    @Test fun `same alias snapshot offers Remove Aside and suppresses Continue actions`() = runTest {
        val parent = Job(backgroundScope.coroutineContext[Job]); val origin = TitleListOrigin(target, ProfileDataContext.current, parent)
        val reads = Reads(); reads.parked = listOf(WatchlistItem("tmdb:movie:99", externalIds = mapOf("imdb" to "tt7")))
        reads.rows = listOf(ContinueWatchingItem("tmdb:movie:77", "Alias", externalIds = mapOf("imdb" to "tt7")))
        var snapshot: DetailsMenuSnapshot? = null
        readDetailsMenuSnapshot(target, origin, reads) { snapshot = it }
        assertEquals(DetailsMenuSnapshot(inSetAside = true, loaded = true), snapshot)
        assertTrue(SetAsideStore.items.isEmpty())
    }
    @Test fun `absent reserved list skips items and retains valid empty success`() = runTest {
        val parent = Job(backgroundScope.coroutineContext[Job]); val origin = TitleListOrigin(target, ProfileDataContext.current, parent)
        val reads = Reads(); reads.listRows = emptyList(); reads.rows = emptyList()
        var snapshot: DetailsMenuSnapshot? = null
        readDetailsMenuSnapshot(target, origin, reads) { snapshot = it }
        assertEquals(listOf("lists", "continue"), reads.stages); assertEquals(DetailsMenuSnapshot(loaded = true), snapshot)
    }
    @Test fun `ordinary and terminal stage errors publish fixed notice with zero later IO`() = runTest {
        for (stage in listOf("lists", "items", "continue")) for (error in listOf(java.io.IOException("private diagnostic"), ApiError.Http(HttpFailure(403, "forbidden")))) {
            val parent = Job(backgroundScope.coroutineContext[Job]); val origin = TitleListOrigin(target, ProfileDataContext.current, parent)
            val reads = Reads(); reads.hook = { if (it == stage) throw error }; var result: DetailsMenuSnapshot? = null
            readDetailsMenuSnapshot(target, origin, reads) { result = it }
            assertFalse(result!!.loaded); assertNotNull(result!!.notice); assertFalse(result!!.notice!!.contains("private"))
            assertEquals(listOf("lists", "items", "continue").takeWhile { it != stage } + stage, reads.stages)
        }
    }
    @Test fun `ambiguous reserved lists stop before items or history instead of inventing membership`() = runTest {
        val parent = Job(backgroundScope.coroutineContext[Job]); val origin = TitleListOrigin(target, ProfileDataContext.current, parent)
        val reads = Reads(); reads.listRows = listOf(CustomList("a", "Set Aside"), CustomList("b", "Set Aside")); var result: DetailsMenuSnapshot? = null
        readDetailsMenuSnapshot(target, origin, reads) { result = it }
        assertEquals(listOf("lists"), reads.stages); assertFalse(result!!.loaded); assertNotNull(result!!.notice)
    }
    @Test fun `queued stale target route and retired epoch start no request`() = runTest {
        for (mode in 0..2) {
            val parent = Job(backgroundScope.coroutineContext[Job]); val origin = TitleListOrigin(target, ProfileDataContext.current, parent) { mode != 1 }
            if (mode == 2) { ProfileDataContext.invalidate(); ProfileDataContext.invalidate() }
            val reads = Reads()
            val actual = if (mode == 0) target.copy() else target
            assertTrue(runCatching { readDetailsMenuSnapshot(actual, origin, reads) { error("No publish") } }.exceptionOrNull() is CancellationException)
            assertTrue(reads.stages.isEmpty())
        }
    }
    @Test fun `late original lists across A B A cannot start items or history`() = runTest {
        val parent = Job(backgroundScope.coroutineContext[Job]); val origin = TitleListOrigin(target, ProfileDataContext.current, parent)
        val gate = CompletableDeferred<Unit>(); val reads = Reads(); reads.hook = { if (it == "lists") withContext(NonCancellable) { gate.await() } }
        var published = 0
        val pending = async { runCatching { readDetailsMenuSnapshot(target, origin, reads) { published++ } }.exceptionOrNull() }; runCurrent()
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); gate.complete(Unit)
        assertTrue(pending.await() is CancellationException); assertEquals(listOf("lists"), reads.stages); assertEquals(0, published)
    }
    @Test fun `route replacement and normal dismissal retire late rows and errors`() = runTest {
        for (error in listOf(false, true)) {
            val parent = Job(backgroundScope.coroutineContext[Job]); var selected = true
            val origin = TitleListOrigin(target, ProfileDataContext.current, parent) { selected }
            val reads = Reads(); val gate = CompletableDeferred<Unit>(); reads.hook = { if (it == "continue") withContext(NonCancellable) { gate.await(); if (error) throw java.io.IOException("late") } }
            var published = 0
            val pending = async { runCatching { readDetailsMenuSnapshot(target, origin, reads) { published++ } }.exceptionOrNull() }; runCurrent()
            selected = false; origin.close(); gate.complete(Unit)
            assertTrue(pending.await() is CancellationException); assertEquals(0, published)
        }
    }
    @Test fun `cancellation stays cancellation and never becomes a visible successful empty state`() = runTest {
        val parent = Job(backgroundScope.coroutineContext[Job]); val origin = TitleListOrigin(target, ProfileDataContext.current, parent)
        val reads = Reads(); val failure = CancellationException("Canceled"); reads.hook = { throw failure }
        val error = runCatching { readDetailsMenuSnapshot(target, origin, reads) { fail("No publish") } }.exceptionOrNull()
        assertTrue(error is CancellationException); assertEquals(listOf("lists"), reads.stages)
    }
    @Test fun `caller cancellation also cancels producer child awaiting original read`() = runTest {
        val parent = Job(backgroundScope.coroutineContext[Job]); val origin = TitleListOrigin(target, ProfileDataContext.current, parent)
        val reads = Reads(); var canceled = false
        reads.hook = { try { awaitCancellation() } finally { canceled = true } }
        val pending = launch { readDetailsMenuSnapshot(target, origin, reads) { fail("No publish") } }; runCurrent()
        pending.cancelAndJoin(); assertTrue(canceled); assertTrue(parent.isActive); assertEquals(listOf("lists"), reads.stages)
    }
    @Test fun `actual raw menu decoder accepts complete valid arrays and aliases without global publication`() = runTest {
        val parent = Job(backgroundScope.coroutineContext[Job]); val origin = TitleListOrigin(target, ProfileDataContext.current, parent)
        val paths = mutableListOf<String>(); val reads = RawDetailsMenuReads { path, expected ->
            assertEquals(origin.context, expected); paths += path
            when {
                path.endsWith("/items") -> """[{"id":"tmdb:movie:99","mediaType":"movie","name":"Alias","externalIds":{"imdb":"tt7"}}]"""
                path.endsWith("/custom-lists") -> """[{"id":"aside","name":"Set Aside","itemCount":1}]"""
                else -> """[{"seriesId":"tmdb:movie:77","seriesTitle":"Alias","externalIds":{"imdb":"tt7"}}]"""
            }
        }
        var result: DetailsMenuSnapshot? = null
        readDetailsMenuSnapshot(target, origin, reads) { result = it }
        assertEquals(3, paths.size); assertEquals(DetailsMenuSnapshot(inSetAside = true, loaded = true), result)
        assertTrue(SetAsideStore.items.isEmpty())
    }
    @Test fun `actual malformed raw lists or items stop before history with no lossy success`() = runTest {
        val validList = """[{"id":"aside","name":"Set Aside"}]"""
        for (stage in listOf("lists", "items")) for (bad in listOf("null", "{}", "[null]", "[{},{\"id\":\"good\",\"name\":\"Other\"}]", "[{\"id\":4,\"name\":\"Set Aside\"}]", "[{\"id\":\"aside\"}]")) {
            val parent = Job(backgroundScope.coroutineContext[Job]); val origin = TitleListOrigin(target, ProfileDataContext.current, parent)
            val paths = mutableListOf<String>(); var result: DetailsMenuSnapshot? = null
            val reads = RawDetailsMenuReads { path, _ ->
                paths += path
                if (stage == "lists" || path.endsWith("/items")) bad else validList
            }
            readDetailsMenuSnapshot(target, origin, reads) { result = it }
            assertEquals(if (stage == "lists") 1 else 2, paths.size); assertFalse(result!!.loaded); assertNotNull(result!!.notice)
            assertTrue(paths.none { it.endsWith("/history/continue") })
        }
    }
    @Test fun `actual malformed raw history or aliases reject whole result without loaded publication`() = runTest {
        for (bad in listOf("{}", "[null]", "[{}]", "[{\"seriesId\":\"tmdb:movie:7\",\"seriesTitle\":7}]", "[{\"seriesId\":\"tmdb:movie:7\",\"seriesTitle\":\"Title\",\"externalIds\":{\"imdb\":7}}]")) {
            val parent = Job(backgroundScope.coroutineContext[Job]); val origin = TitleListOrigin(target, ProfileDataContext.current, parent)
            var calls = 0; var result: DetailsMenuSnapshot? = null
            val reads = RawDetailsMenuReads { path, _ -> calls++; if (path.endsWith("/custom-lists")) "[]" else bad }
            readDetailsMenuSnapshot(target, origin, reads) { result = it }
            assertEquals(2, calls); assertFalse(result!!.loaded); assertNotNull(result!!.notice)
        }
    }

    @Test fun `actual Go history whole null remains valid empty only for history stage`() = runTest {
        val parent = Job(backgroundScope.coroutineContext[Job]); val origin = TitleListOrigin(target, ProfileDataContext.current, parent)
        val reads = RawDetailsMenuReads { path, _ -> if (path.endsWith("/history/continue")) "null" else "[]" }
        var result: DetailsMenuSnapshot? = null
        readDetailsMenuSnapshot(target, origin, reads) { result = it }
        assertEquals(DetailsMenuSnapshot(loaded = true), result)
    }

}
