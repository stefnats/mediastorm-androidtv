package com.strmr.tv.home

import com.strmr.tv.api.*
import com.strmr.tv.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Tests the same original-owner negotiation adapter consumed by HomeApi.Hidden, without Android IO. */
@OptIn(ExperimentalCoroutinesApi::class)
class HiddenManagementReadTest {
    private val current get() = ProfileDataContext.current
    private fun wire() = requireNotNull(javaClass.getResourceAsStream("/management-language-go/hidden/hidden-selected.json")).bufferedReader().use { it.readText() }
    private fun unsupported() = ApiError.Http(HttpFailure(400, "unsupported display list source\n"))
    private fun legacy() = """[{"id":"old-1","mediaType":"movie","name":"First","hiddenAt":"2026-11-16T00:00:00Z"},{"id":"old-2","mediaType":"series","name":"Second","hiddenAt":"2026-11-16T01:00:00+01:00"}]"""
    private suspend fun read(
        options: BrowseOptions = BrowseOptions(), expected: Long = current, mode: Boolean? = null,
        page: suspend (Map<String, String>, Long) -> String = { _, _ -> wire() },
        old: suspend (Long) -> String = { error("No fallback expected") },
    ) = readHiddenManagementPage(1, 0, emptyMap(), options, expected, mode, page, old)

    @Test fun `only exact default unsupported400 may use independent full legacy array`() = runTest {
        var first = 0; var old = 0
        val page = read(page = { _, _ -> first++; throw unsupported() }, old = { old++; legacy() })
        assertEquals(1, first); assertEquals(1, old)
        assertEquals("old-2", (page.entries.single() as BrowseEntry.ItemEntry).item.id)
        assertEquals(2, page.total); assertFalse(page.originalLanguageFilterSupported)
    }
    @Test fun `already negotiated legacy mode pages raw source directly without probe GET`() = runTest {
        var old = 0
        val page = read(mode = true, page = { _, _ -> error("No repeated negotiation") }, old = { old++; legacy() })
        assertEquals(1, old); assertEquals(2, page.total)
    }
    @Test fun `active or sorted request and previously supported page never downgrade`() = runTest {
        for ((options, mode) in listOf(BrowseOptions(originalLanguages = setOf("fra")) to null, BrowseOptions(sort = BrowseSort.NAME) to null, BrowseOptions() to false)) {
            var old = 0
            assertSame(null, runCatching { read(options, mode = mode, page = { _, _ -> throw unsupported() }, old = { old++; legacy() }) }.getOrNull())
            assertEquals(0, old)
        }
    }
    @Test fun `auth PIN server network and near matching400 never invoke legacy fallback`() = runTest {
        val errors = listOf(
            ApiError.Http(HttpFailure(401, "unauthorized")), ApiError.Http(HttpFailure(403, "forbidden")),
            ApiError.Http(HttpFailure(403, """{"code":"profile_locked"}""")), ApiError.Http(HttpFailure(503, "metadata source is unavailable")),
            ApiError.Http(HttpFailure(400, "unsupported source")), ApiError.Http(HttpFailure(400, """{"error":"unsupported display list source"}""")),
            java.io.IOException("synthetic transport failure"),
        )
        for (error in errors) {
            var old = 0
            assertSame(error, runCatching { read(page = { _, _ -> throw error }, old = { old++; legacy() }) }.exceptionOrNull())
            assertEquals(0, old)
        }
    }
    @Test fun `malformed success wrong source false marker or invalid row never use legacy`() = runTest {
        for (raw in listOf("{", "{}", wire().replace("\"hidden\"", "\"watchlist\""), wire().replace("Supported\":true", "Supported\":false"), wire().replace("hiddenAt", "missingTime"))) {
            var old = 0
            assertTrue(runCatching { read(page = { _, _ -> raw }, old = { old++; legacy() }) }.exceptionOrNull() is ManagementPageFailure)
            assertEquals(0, old)
        }
    }
    @Test fun `management and legacy array reads never publish a filtered global suppression store`() = runTest {
        HiddenItemsStore.reset()
        HiddenItemsStore.pageItems(current) { listOf(HiddenItem("global-only", name = "Preserved")) }
        val items = HiddenItemsStore.items; val revision = HiddenItemsStore.revision; val loaded = HiddenItemsStore.isLoaded
        read()
        read(page = { _, _ -> throw unsupported() }, old = { legacy() })
        assertEquals(items, HiddenItemsStore.items); assertEquals(revision, HiddenItemsStore.revision); assertEquals(loaded, HiddenItemsStore.isLoaded)
        HiddenItemsStore.reset()
    }
    @Test fun `queued original request after simulated A B A does zero reads`() = runTest {
        val owner = current; var reads = 0
        val job = async { read(expected = owner, page = { _, _ -> reads++; wire() }) }
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); runCurrent()
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException); assertEquals(0, reads)
    }
    @Test fun `late retired response cannot return data or start fallback`() = runTest {
        val owner = current; val pending = CompletableDeferred<String>(); var old = 0
        val job = async { read(expected = owner, page = { _, _ -> withContext(NonCancellable) { pending.await() } }, old = { old++; legacy() }) }
        runCurrent(); ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); pending.complete(wire())
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException); assertEquals(0, old)
    }
    @Test fun `cancelled masked legacy response cannot return a successful management page`() = runTest {
        val pending = CompletableDeferred<String>()
        val job = async { read(mode = true, old = { withContext(NonCancellable) { pending.await() } }) }
        runCurrent(); job.cancel(); pending.complete(legacy())
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
    }
}
