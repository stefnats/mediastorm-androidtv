package com.strmr.tv.library

import com.strmr.tv.api.ApiError
import com.strmr.tv.api.HttpFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** The actual page reader consumes its response, independent of Unit-sharing legacy refresh. */
@OptIn(ExperimentalCoroutinesApi::class)
class HiddenPageContextTest {
    @Before fun setUp() { HiddenItemsStore.reset() }
    @After fun tearDown() { HiddenItemsStore.reset() }
    private suspend fun cancelled(read: suspend () -> Any?) {
        try { read(); fail("Retired read survived") } catch (_: CancellationException) { }
    }
    @Test fun `stale page stops before load and keeps current singleton unchanged`() = runTest {
        val expected = ProfileDataContext.current; var reads = 0
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        HiddenItemsStore.pageItems(ProfileDataContext.current) { listOf(HiddenItem("new")) }
        cancelled { HiddenItemsStore.pageItems(expected) { reads++; listOf(HiddenItem("old")) } }
        assertEquals(0, reads); assertEquals("new", HiddenItemsStore.items.single().id)
    }
    @Test fun `late old epoch never returns or publishes another profile snapshot`() = runTest {
        val expected = ProfileDataContext.current; val release = CompletableDeferred<Unit>()
        val old = async { HiddenItemsStore.pageItems(expected) {
            withContext(NonCancellable) { release.await() }; listOf(HiddenItem("old"))
        } }
        runCurrent(); ProfileDataContext.invalidate(); HiddenItemsStore.reset()
        HiddenItemsStore.pageItems(ProfileDataContext.current) { listOf(HiddenItem("new")) }
        release.complete(Unit); cancelled { old.await() }
        assertEquals("new", HiddenItemsStore.items.single().id)
    }
    @Test fun `page returns its own immutable snapshot even after a newer page finishes`() = runTest {
        val expected = ProfileDataContext.current; val release = CompletableDeferred<Unit>()
        val old = async { HiddenItemsStore.pageItems(expected) { release.await(); listOf(HiddenItem("first")) } }
        runCurrent(); val newer = HiddenItemsStore.pageItems(expected) { listOf(HiddenItem("second")) }
        release.complete(Unit)
        assertEquals("first", old.await().single().id); assertEquals("second", newer.single().id)
        assertEquals("second", HiddenItemsStore.items.single().id)
    }
    @Test fun `dedicated page denial is never replaced with cached items or shared refresh success`() = runTest {
        HiddenItemsStore.pageItems(ProfileDataContext.current) { listOf(HiddenItem("cached")) }
        try {
            HiddenItemsStore.pageItems(ProfileDataContext.current) { throw ApiError.Http(HttpFailure(403, "{}")) }
            fail("Denial became cached success")
        } catch (error: ApiError) { assertEquals(403, error.statusCode) }
        assertEquals("cached", HiddenItemsStore.items.single().id)
    }
    @Test fun `same terminal denial fails page while legacy background publishes no new success`() = runTest {
        val denied = ApiError.Http(HttpFailure(403, "{}"))
        var logged = 0
        HiddenItemsStore.refresh(onFailure = { assertSame(denied, it); logged++ }) { throw denied }
        assertEquals(1, logged)
        assertTrue(HiddenItemsStore.items.isEmpty()); assertFalse(HiddenItemsStore.isLoaded)
        try {
            HiddenItemsStore.pageItems(ProfileDataContext.current) { throw denied }
            fail("Page denial became optional")
        } catch (error: ApiError) { assertSame(denied, error) }
        assertTrue(HiddenItemsStore.items.isEmpty()); assertFalse(HiddenItemsStore.isLoaded)
        HiddenItemsStore.pageItems(ProfileDataContext.current) { listOf(HiddenItem("existing")) }
        HiddenItemsStore.refresh(onFailure = { assertSame(denied, it); logged++ }) { throw denied }
        assertEquals(2, logged)
        assertEquals("existing", HiddenItemsStore.items.single().id)
    }
    @Test fun `legacy background refresh still propagates cancellation without publication`() = runTest {
        cancelled { HiddenItemsStore.refresh { throw CancellationException("synthetic") } }
        assertTrue(HiddenItemsStore.items.isEmpty()); assertFalse(HiddenItemsStore.isLoaded)
    }
    @Test fun `concurrent page callers each receive their own failure or result`() = runTest {
        supervisorScope {
            val release = CompletableDeferred<Unit>()
            val denied = async { HiddenItemsStore.pageItems(ProfileDataContext.current) {
                release.await(); throw ApiError.Http(HttpFailure(403, "{}"))
            } }
            runCurrent()
            val accepted = HiddenItemsStore.pageItems(ProfileDataContext.current) { listOf(HiddenItem("current")) }
            release.complete(Unit)
            try { denied.await(); fail("Shared failure hidden") } catch (error: ApiError) { assertEquals(403, error.statusCode) }
            assertEquals("current", accepted.single().id); assertEquals("current", HiddenItemsStore.items.single().id)
        }
    }
    @Test fun `successful page invalidates older Unit shared refresh publication`() = runTest {
        val release = CompletableDeferred<Unit>()
        val refresh = launch { HiddenItemsStore.refresh { release.await(); listOf(HiddenItem("older-refresh")) } }
        runCurrent(); HiddenItemsStore.pageItems(ProfileDataContext.current) { listOf(HiddenItem("page")) }
        release.complete(Unit); refresh.join()
        assertEquals("page", HiddenItemsStore.items.single().id)
    }
    @Test fun `older page cannot overwrite a later completed refresh`() = runTest {
        val release = CompletableDeferred<Unit>()
        val page = async { HiddenItemsStore.pageItems(ProfileDataContext.current) { release.await(); listOf(HiddenItem("page")) } }
        runCurrent(); HiddenItemsStore.refresh { listOf(HiddenItem("refresh")) }
        release.complete(Unit); assertEquals("page", page.await().single().id)
        assertEquals("refresh", HiddenItemsStore.items.single().id)
    }
    @Test fun `older page cannot retire a later in-flight shared refresh`() = runTest {
        val pageReply = CompletableDeferred<Unit>(); val refreshReply = CompletableDeferred<Unit>()
        val page = async { HiddenItemsStore.pageItems(ProfileDataContext.current) { pageReply.await(); listOf(HiddenItem("page")) } }
        runCurrent()
        val refresh = launch { HiddenItemsStore.refresh { refreshReply.await(); listOf(HiddenItem("refresh")) } }
        runCurrent(); pageReply.complete(Unit); assertEquals("page", page.await().single().id)
        refreshReply.complete(Unit); refresh.join()
        assertEquals("refresh", HiddenItemsStore.items.single().id)
    }
    @Test fun `cancellation rejects even masked page reply and leaves snapshot unchanged`() = runTest {
        val release = CompletableDeferred<Unit>()
        val read = async { HiddenItemsStore.pageItems(ProfileDataContext.current) {
            withContext(NonCancellable) { release.await() }; listOf(HiddenItem("late"))
        } }
        runCurrent(); read.cancel(); release.complete(Unit); cancelled { read.await() }
        assertTrue(HiddenItemsStore.items.isEmpty()); assertFalse(HiddenItemsStore.isLoaded)
    }
}
