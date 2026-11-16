package com.strmr.tv.home

import com.strmr.tv.api.*
import com.strmr.tv.library.HiddenItem
import com.strmr.tv.library.HiddenItemsStore
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.ui.lists.ListGridModel
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Consumed child-read adapters and real list model, with simulated epoch retirement; no Android activation. */
@OptIn(ExperimentalCoroutinesApi::class)
class IndirectPagesContextTest {
    private val service = BrowseList.Service(StreamingService("synthetic", "Synthetic", lists = listOf(
        StreamingService.ServiceList(url = "synthetic-one"), StreamingService.ServiceList(url = "synthetic-two"),
    )))
    private val aside = CustomList("aside", "Set Aside")
    private fun watching(id: String) = ContinueWatchingItem(id, "Synthetic $id")
    private fun page(id: String, total: Int = 4) = BrowsePage(listOf(BrowseEntry.ItemEntry(WatchlistItem(id))), total)
    private val denied get() = ApiError.Http(HttpFailure(403, "{\"code\":\"kids_vod_access_denied\"}"))
    private suspend fun cancelled(read: suspend () -> Any?) {
        try { read(); fail("Retired read survived") } catch (_: CancellationException) { }
    }
    private suspend fun rejected(read: suspend () -> Any?) {
        try { read(); fail("Denial became optional") } catch (error: ApiError) { assertEquals(403, error.statusCode) }
    }

    @Test fun `queued merged children cannot capture replacement epoch`() = runTest {
        val expected = ProfileDataContext.current
        var demands = 0
        val reading = async(start = CoroutineStart.UNDISPATCHED) {
            ServicePageMerge().read(service, 2, 0, emptyMap(), null, expected) { _, _, _, _, _, _ -> demands++; page("never") }
        }
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        cancelled { reading.await() }
        assertEquals(0, demands)
    }
    @Test fun `all merge requests retain owner options query and original part indices`() = runTest {
        val expected = ProfileDataContext.current
        val calls = mutableListOf<Pair<Int, Int>>()
        val options = BrowseOptions(sort = BrowseSort.YEAR, ascending = true)
        val result = ServicePageMerge().read(service, 4, 0, mapOf("hideWatched" to "true"), options, expected) { part, count, offset, query, selected, owner ->
            assertEquals(expected, owner); assertEquals(options, selected); assertEquals("true", query["hideWatched"])
            calls += part.part to offset
            BrowsePage((0 until count).map { BrowseEntry.ItemEntry(WatchlistItem("${part.part}-$it")) }, 10)
        }
        assertEquals(listOf(1 to 0, 2 to 0), calls)
        assertEquals(listOf("1-0", "2-0", "1-1", "2-1"), result.entries.map { (it as BrowseEntry.ItemEntry).item.id })
        assertEquals(20, result.total)
    }
    @Test fun `late initial totals cannot trigger continuation under new epoch`() = runTest {
        val expected = ProfileDataContext.current
        val result = CompletableDeferred<Unit>(); val offsets = mutableListOf<Int>()
        val reading = async {
            ServicePageMerge().read(service, 2, 2, emptyMap(), null, expected) { part, _, offset, _, _, owner ->
                assertEquals(expected, owner); offsets += offset
                withContext(NonCancellable) { result.await() }; page("${part.part}")
            }
        }
        runCurrent(); assertEquals(listOf(0, 0), offsets)
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); result.complete(Unit)
        cancelled { reading.await() }; assertEquals(listOf(0, 0), offsets)
    }
    @Test fun `merge totals are reused within owner but rediscovered after epoch retirement`() = runTest {
        val merge = ServicePageMerge(); val offsets = mutableListOf<Int>()
        suspend fun read(expected: Long, offset: Int) = merge.read(service, 2, offset, emptyMap(), null, expected) { part, _, start, _, _, _ ->
            offsets += start; page("${part.part}-$start", 6)
        }
        read(ProfileDataContext.current, 0); offsets.clear()
        read(ProfileDataContext.current, 2); assertEquals(listOf(1, 1), offsets); offsets.clear()
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        read(ProfileDataContext.current, 2); assertEquals(listOf(0, 0, 1, 1), offsets)
    }
    @Test fun `late old merge cannot replace new owner totals`() = runTest {
        val merge = ServicePageMerge(); val oldOwner = ProfileDataContext.current; val release = CompletableDeferred<Unit>()
        val old = async { merge.read(service, 2, 0, emptyMap(), null, oldOwner) { _, _, _, _, _, _ ->
            withContext(NonCancellable) { release.await() }; page("old", 99)
        } }
        runCurrent(); ProfileDataContext.invalidate()
        merge.read(service, 2, 0, emptyMap(), null, ProfileDataContext.current) { part, _, _, _, _, _ -> page("new-${part.part}", 3) }
        release.complete(Unit); cancelled { old.await() }
        val offsets = mutableListOf<Int>()
        val result = merge.read(service, 2, 4, emptyMap(), null, ProfileDataContext.current) { part, _, start, _, _, _ ->
            offsets += start; page("current-${part.part}", 3)
        }
        assertEquals(6, result.total); assertEquals(listOf(2, 2), offsets)
    }
    @Test fun `terminal merge error is not converted into partial page`() = runTest {
        rejected { ServicePageMerge().read(service, 2, 0, emptyMap(), null, ProfileDataContext.current) { _, _, _, _, _, _ -> throw denied } }
    }
    @Test fun `canceled merge rejects masked late successful child`() = runTest {
        val release = CompletableDeferred<Unit>()
        val reading = async { ServicePageMerge().read(service, 2, 0, emptyMap(), null, ProfileDataContext.current) { _, _, _, _, _, _ ->
            withContext(NonCancellable) { release.await() }; page("late")
        } }
        runCurrent(); reading.cancel(); release.complete(Unit); cancelled { reading.await() }
    }
    @Test fun `queued continue children stop before any replacement-profile demand`() = runTest {
        val expected = ProfileDataContext.current; var demands = 0
        val reading = async(start = CoroutineStart.UNDISPATCHED) {
            readContinueWatching(expected, { demands++; listOf(watching("never")) }, { demands++; emptyList() }, { _, _ -> demands++; emptyList() })
        }
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); cancelled { reading.await() }
        assertEquals(0, demands)
    }
    @Test fun `continue rows discovery and reserved items use same original owner`() = runTest {
        val expected = ProfileDataContext.current; val owners = mutableListOf<Long>()
        val result = readContinueWatching(expected, { owners += it; listOf(watching("kept"), watching("parked")) },
            { owners += it; listOf(aside) }, { id, owner -> assertEquals(aside.id, id); owners += owner; listOf(WatchlistItem("parked")) })
        assertEquals(listOf("kept"), result.map { it.seriesId }); assertEquals(listOf(expected, expected, expected), owners)
    }
    @Test fun `retirement during aside discovery prevents item followup`() = runTest {
        val expected = ProfileDataContext.current; val release = CompletableDeferred<Unit>(); var followups = 0
        val reading = async {
            readContinueWatching(expected, { listOf(watching("original")) }, {
                withContext(NonCancellable) { release.await() }; listOf(aside)
            }, { _, _ -> followups++; emptyList() })
        }
        runCurrent(); ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); release.complete(Unit)
        cancelled { reading.await() }; assertEquals(0, followups)
    }
    @Test fun `retirement during aside items rejects old combined result`() = runTest {
        val expected = ProfileDataContext.current; val release = CompletableDeferred<Unit>()
        val reading = async { readContinueWatching(expected, { listOf(watching("original")) }, { listOf(aside) }, { _, _ ->
            withContext(NonCancellable) { release.await() }; emptyList()
        }) }
        runCurrent(); ProfileDataContext.invalidate(); release.complete(Unit); cancelled { reading.await() }
    }
    @Test fun `ordinary optional aside failure retains continue feed`() = runTest {
        val result = readContinueWatching(ProfileDataContext.current, { listOf(watching("kept")) }, { throw IOException("synthetic") }, { _, _ -> error("No items") })
        assertEquals("kept", result.single().seriesId)
    }
    @Test fun `terminal aside discovery cannot become empty optional list`() = runTest {
        rejected { readContinueWatching(ProfileDataContext.current, { listOf(watching("kept")) }, { throw denied }, { _, _ -> error("No followup") }) }
    }
    @Test fun `terminal aside item read cannot become empty optional list`() = runTest {
        rejected { readContinueWatching(ProfileDataContext.current, { emptyList() }, { listOf(aside) }, { _, _ -> throw denied }) }
    }
    @Test fun `canceled optional aside is never swallowed`() = runTest {
        cancelled { readContinueWatching(ProfileDataContext.current, { emptyList() }, { throw CancellationException("synthetic") }, { _, _ -> error("No items") }) }
    }

    private suspend fun TestScope.retiredModel(list: BrowseList) {
        val release = CompletableDeferred<Unit>()
        val merge = ServicePageMerge()
        val model = ListGridModel(list, emptyMap(), backgroundScope, readPage = { shown, limit, offset, extra, options, expected ->
            when (shown) {
                is BrowseList.Service -> merge.read(shown, limit, offset, extra, options, expected) { _, _, _, _, _, _ ->
                    withContext(NonCancellable) { release.await() }; page("old")
                }
                BrowseList.ContinueWatching -> {
                    val rows = readContinueWatching(expected, { withContext(NonCancellable) { release.await() }; listOf(watching("old")) }, { emptyList() }, { _, _ -> emptyList() })
                    BrowsePage(rows.map { BrowseEntry.WatchingEntry(it) }, rows.size)
                }
                BrowseList.Hidden -> {
                    val rows = HiddenItemsStore.pageItems(expected) { withContext(NonCancellable) { release.await() }; listOf(HiddenItem("old")) }
                    BrowsePage(rows.map { BrowseEntry.ItemEntry(WatchlistItem(it.id)) }, rows.size)
                }
                is BrowseList.Library -> readLibraryPage(shown.library, options, limit, offset, expected) { _, _, _, _, _, _, _ ->
                    withContext(NonCancellable) { release.await() }; LibraryGroupPage(emptyList(), 99)
                }
                else -> error("Unexpected list")
            }
        })
        val reading = async { model.reload() }; runCurrent()
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); release.complete(Unit)
        cancelled { reading.await() }; assertTrue(model.entries.isEmpty()); assertNull(model.total)
    }
    @Test fun `actual model plus merge adapter cannot publish old profile page`() = runTest { retiredModel(service) }
    @Test fun `actual model plus continue adapter cannot publish old profile page`() = runTest { retiredModel(BrowseList.ContinueWatching) }
    @Test fun `actual model plus hidden snapshot cannot publish old profile page`() = runTest { retiredModel(BrowseList.Hidden) }
    @Test fun `actual model plus library adapter cannot publish old profile page`() = runTest { retiredModel(BrowseList.Library(LibraryRef("synthetic", "Synthetic"))) }
}
