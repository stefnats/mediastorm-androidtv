package com.strmr.tv.library

import com.strmr.tv.api.*
import com.strmr.tv.home.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListsTabContextTest {
    @Before fun reset() { ListsTabStore.reset() }
    @After fun finish() { ListsTabStore.reset() }
    private class Reads : ListsTabReads {
        val stages = mutableListOf<String>(); val contexts = mutableListOf<Long>()
        var hook: suspend (String) -> Unit = {}
        private suspend fun called(name: String, expected: Long) { stages += name; contexts += expected; hook(name) }
        override suspend fun shelves(expected: Long): List<ShelfConfig> { called("shelves", expected); return listOf(ShelfConfig("services")) }
        override suspend fun continuing(expected: Long): List<ContinueWatchingItem> { called("continue", expected); return listOf(ContinueWatchingItem("tmdb:movie:7", "Synthetic")) }
        override suspend fun libraries(expected: Long): List<LibraryInfo> { called("libraries", expected); return listOf(LibraryInfo("local", "Local")) }
    }
    @Test fun `actual Store publishes all three current components and completes loading`() = runTest {
        val expected = ProfileDataContext.current; val access = Reads()
        ListsTabStore.refresh(expected, access)
        assertEquals(setOf(expected), access.contexts.toSet()); assertEquals(3, access.stages.size)
        assertEquals("services", ListsTabStore.shelves!!.single().id); assertEquals("local", ListsTabStore.libraries.single().id)
        assertEquals("tmdb:movie:7", ListsTabStore.continueWatching!!.single().seriesId); assertTrue(ListsTabStore.isLoaded); assertNull(ListsTabStore.loadError)
    }
    @Test fun `queued epoch A B A rejects all actual read ports`() = runTest {
        val expected = ProfileDataContext.current; val access = Reads()
        val old = async { runCatching { ListsTabStore.refresh(expected, access) }.exceptionOrNull() }
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        assertTrue(old.await() is CancellationException); assertTrue(access.stages.isEmpty()); assertFalse(ListsTabStore.isLoaded)
    }
    @Test fun `getter owner check hides old rows synchronously even without reset`() = runTest {
        ListsTabStore.refresh(ProfileDataContext.current, Reads()); ProfileDataContext.invalidate()
        assertNull(ListsTabStore.shelves); assertNull(ListsTabStore.continueWatching); assertTrue(ListsTabStore.libraries.isEmpty()); assertNull(ListsTabStore.loadError); assertFalse(ListsTabStore.isLoaded)
    }
    @Test fun `shared same-owner read performs three ports once and both callers finish`() = runTest {
        val expected = ProfileDataContext.current; val access = Reads(); val gate = CompletableDeferred<Unit>()
        access.hook = { gate.await() }
        val first = launch { ListsTabStore.refresh(expected, access) }; runCurrent()
        val second = launch { ListsTabStore.refresh(expected, access) }; runCurrent()
        assertEquals(3, access.stages.size); gate.complete(Unit); first.join(); second.join(); assertTrue(ListsTabStore.isLoaded)
    }
    @Test fun `reset cancels shared waiters and masked old reply cannot replace new snapshot`() = runTest {
        val expected = ProfileDataContext.current; val access = Reads(); val gate = CompletableDeferred<Unit>()
        access.hook = { withContext(NonCancellable) { gate.await() } }
        val first = launch { ListsTabStore.refresh(expected, access) }; runCurrent()
        val second = async { runCatching { ListsTabStore.refresh(expected, access) }.exceptionOrNull() }; runCurrent()
        ListsTabStore.reset(); ListsTabStore.refresh { ListsTabSnapshot(shelves = listOf(ShelfConfig("new"))) }
        gate.complete(Unit); first.join(); assertTrue(second.await() is CancellationException)
        assertEquals("new", ListsTabStore.shelves!!.single().id)
    }
    @Test fun `ordinary component failure retains prior rows and publishes visible completed error`() = runTest {
        val expected = ProfileDataContext.current; ListsTabStore.refresh(expected, Reads())
        val access = Reads(); access.hook = { if (it == "libraries") throw IOException("private diagnostic") }
        ListsTabStore.refresh(expected, access)
        assertEquals("local", ListsTabStore.libraries.single().id); assertNotNull(ListsTabStore.shelves); assertTrue(ListsTabStore.isLoaded)
        assertNotNull(ListsTabStore.loadError); assertFalse(ListsTabStore.loadError!!.contains("private"))
        ListsTabStore.refresh(expected, Reads()); assertNull(ListsTabStore.loadError)
    }
    @Test fun `terminal component denial retires rows and never becomes an empty success`() = runTest {
        val expected = ProfileDataContext.current; ListsTabStore.refresh(expected, Reads())
        val access = Reads(); access.hook = { if (it == "shelves") throw ApiError.Http(HttpFailure(403, "denied")) else awaitCancellation() }
        ListsTabStore.refresh(expected, access)
        assertNull(ListsTabStore.shelves); assertNull(ListsTabStore.continueWatching); assertTrue(ListsTabStore.libraries.isEmpty())
        assertTrue(ListsTabStore.isLoaded); assertNotNull(ListsTabStore.loadError)
    }
    @Test fun `canceled current read produces no error or completed-attempt success and permits retry`() = runTest {
        val access = Reads(); access.hook = { awaitCancellation() }; val expected = ProfileDataContext.current
        val task = launch { ListsTabStore.refresh(expected, access) }; runCurrent(); task.cancelAndJoin()
        assertFalse(ListsTabStore.isLoaded); assertNull(ListsTabStore.loadError)
        ListsTabStore.refresh(expected, Reads()); assertTrue(ListsTabStore.isLoaded)
    }
    @Test fun `new profile refresh need not wait for masked old work`() = runTest {
        val access = Reads(); val gate = CompletableDeferred<Unit>(); access.hook = { withContext(NonCancellable) { gate.await() } }
        val old = launch { ListsTabStore.refresh(ProfileDataContext.current, access) }; runCurrent()
        ProfileDataContext.invalidate(); ListsTabStore.refresh { ListsTabSnapshot(libraries = listOf(LibraryInfo("new", "New"))) }
        gate.complete(Unit); old.join(); assertEquals("new", ListsTabStore.libraries.single().id)
    }
}
