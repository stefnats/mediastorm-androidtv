package com.strmr.tv.ui.lists

import com.strmr.tv.api.*
import com.strmr.tv.home.*
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecentActivityModelTest {
    @Test fun `actual model consumes beyond500 complete rows and keeps duplicate keys separate from title actions`() = runTest {
        val offsets = mutableListOf<Int>()
        val model = ListGridModel(RecentActivityFixture.list(), emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, pageSize = 601,
            readPage = { _, limit, offset, _, _, _ -> offsets += offset; decodeRecentActivityPage(RecentActivityFixture.synthetic(701, offset, minOf(limit, 701 - offset)), limit, offset) })
        model.loadPage(); val oldKeys = model.cards.map { it.id }; model.loadPage()
        assertEquals(listOf(0, 601), offsets); assertEquals(701, model.fetched); assertEquals(701, model.total)
        assertEquals(701, model.cards.size); assertEquals(701, model.cards.map { it.id }.distinct().size)
        assertEquals(oldKeys, model.cards.take(601).map { it.id })
        assertTrue(model.cards.all { it.target?.titleId == "tmdb:movie:42" && it.id != it.target?.titleId && it.cacheOnlyBrowseEntry && it.titleSourceCoordinate() == null && it.reloadStoredRating == null })
        model.loadPage(); assertEquals(2, offsets.size)
    }
    @Test fun `genuine rows repeating across server page boundaries are not deduplicated`() = runTest {
        val first = RecentActivityFixture.page("repeated-all")
        val next = RecentActivityFixture.page("repeated-continuation")
        val model = ListGridModel(RecentActivityFixture.list(), emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, pageSize = 2,
            readPage = { _, limit, offset, _, _, _ -> if (offset == 0) first.copy(entries = first.entries.take(limit)) else next })
        model.loadPage(); model.loadPage()
        assertTrue(model.entries.size >= 3)
        assertEquals(model.entries.size, model.cards.size)
        assertEquals(model.cards.size, model.cards.map { it.id }.distinct().size)
        assertEquals(3, model.cards.count { it.target?.titleId == "tmdb:movie:42" })
    }
    @Test fun `changed server total remains exact and a shrink behind the loaded offset clears rows`() = runTest {
        var calls = 0
        val model = ListGridModel(RecentActivityFixture.list(), emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, pageSize = 2,
            readPage = { _, limit, offset, _, _, _ -> val total = if (calls++ == 0) 10 else 3; decodeRecentActivityPage(RecentActivityFixture.synthetic(total, offset, minOf(limit, maxOf(0, total - offset))), limit, offset) })
        model.loadPage(); model.loadPage(); assertEquals(3, model.total); assertEquals(3, model.cards.size)
        var shrinking = false
        val shrink = ListGridModel(RecentActivityFixture.list(), emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, pageSize = 2,
            readPage = { _, limit, offset, _, _, _ -> val total = if (shrinking) 1 else 10; decodeRecentActivityPage(RecentActivityFixture.synthetic(total, offset, if (shrinking) 0 else 2), limit, offset) })
        shrink.loadPage(); shrinking = true; shrink.loadPage()
        assertNull(shrink.total); assertEquals(0, shrink.fetched); assertTrue(shrink.cards.isEmpty()); assertNotNull(shrink.loadError)
        shrink.loadPage(); assertTrue(shrink.cards.isEmpty())
    }
    @Test fun `masked late options reply cannot restore an old page and empty supported page keeps controls`() = runTest {
        val gate = CompletableDeferred<Unit>(); var calls = 0
        val model = ListGridModel(RecentActivityFixture.list(), emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" },
            readPage = { _, _, _, _, options, _ ->
                if (calls++ == 0) { withContext(NonCancellable) { gate.await() }; RecentActivityFixture.page("unfiltered") }
                else { assertTrue(options.includeUnknownOriginalLanguage); RecentActivityFixture.page("empty-capability") }
            }, pageSize = 3)
        val old = async { runCatching { model.loadPage() }.exceptionOrNull() }; runCurrent()
        model.options = BrowseOptions(includeUnknownOriginalLanguage = true); model.loadPage(); gate.complete(Unit)
        assertTrue(old.await() is CancellationException); assertEquals(0, model.total); assertTrue(model.cards.isEmpty())
        assertTrue(model.originalLanguageFilterSupported); assertEquals(2, calls)
    }
    @Test fun `page cover and return retire old work even under equal profile epoch`() = runTest {
        var selected = true; val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val model = ListGridModel(RecentActivityFixture.list(), emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, pageSelected = { selected }, pageSize = 3,
            readPage = { _, _, _, _, _, _ -> withContext(NonCancellable) { entered.complete(Unit); release.await() }; RecentActivityFixture.page("unfiltered") })
        val old = async { runCatching { model.loadPage() }.exceptionOrNull() }; entered.await()
        selected = false; model.pageSelectionChanged(false); selected = true; release.complete(Unit)
        assertTrue(old.await() is CancellationException); assertTrue(model.cards.isEmpty()); assertNull(model.total); assertNotNull(model.loadError)
    }
    @Test fun `old client producer or profile ABA cannot read or publish protected rows`() = runTest {
        val list = RecentActivityFixture.list(); var client = "synthetic-client"; var calls = 0
        val producer = Job(); val scope = CoroutineScope(coroutineContext + producer)
        val model = ListGridModel(list, emptyMap(), scope, clientIdentity = { client }, pageSize = 3,
            readPage = { _, _, _, _, _, _ -> calls++; RecentActivityFixture.page("unfiltered") })
        model.loadPage(); client = "replacement-client"; model.loadPage()
        assertFalse(model.isCurrent); assertTrue(model.cards.isEmpty()); assertEquals(1, calls)
        producer.cancel()
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        val stale = ListGridModel(list, emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, readPage = { _, _, _, _, _, _ -> calls++; error("stale read") })
        stale.loadPage(); assertEquals(1, calls); assertFalse(stale.isCurrent)
        assertTrue(runCatching { ApiClient.browsePage(list, 1, expectedContext = list.originalContext) }.exceptionOrNull() is CancellationException)
    }
    @Test fun `malformed unsupported and denied page blocks without ordinary source fallback`() = runTest {
        for (error in listOf<Exception>(ManagementPageFailure(), ApiError.Http(HttpFailure(403, "{\"code\":\"kids_recent_watches_unavailable\"}")), ApiError.Http(HttpFailure(409, "{\"code\":\"recent_watches_snapshot_unavailable\"}")), ApiError.Http(HttpFailure(503, "{}")))) {
            var calls = 0
            val model = ListGridModel(RecentActivityFixture.list(), emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, readPage = { list, _, _, _, _, _ -> assertTrue(list is BrowseList.RecentlyWatchedCached); calls++; throw error })
            model.loadPage(); model.loadPage(); assertEquals(1, calls); assertTrue(model.cards.isEmpty()); assertNotNull(model.loadError)
        }
        val unsupported = ListGridModel(RecentActivityFixture.list(), emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, readPage = { _, _, _, _, _, _ -> BrowsePage(emptyList(), 0) })
        unsupported.loadPage(); assertNull(unsupported.total); assertNotNull(unsupported.loadError)
    }
    @Test fun `normal grid selection provides actual reachable saved entry and captured kind`() = runTest {
        var selected = true
        val normal = ListGridModel(RecentActivityFixture.origin, mapOf("homeView" to "movies", "hideUnreleased" to "true"), backgroundScope, clientIdentity = { "synthetic-client" })
        assertTrue(normal.canOpenSavedRecent)
        val list = requireNotNull(normal.openSavedRecent(backgroundScope.coroutineContext[Job]!!) { selected })
        val saved = ListGridModel(list, emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" })
        assertEquals(BrowseKind.MOVIES, saved.options.kind)
        selected = false
        assertNull(normal.openSavedRecent(backgroundScope.coroutineContext[Job]!!) { selected })
    }
    @Test fun `unloaded random row beyond500 retains options and real title identity`() = runTest {
        val reads = mutableListOf<Pair<Int, BrowseOptions>>()
        val model = ListGridModel(RecentActivityFixture.list(), emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, pageSize = 1, randomOffset = { 650 },
            readPage = { _, limit, offset, _, options, _ ->
                reads += offset to options
                val id = if (offset == 0) "tmdb:movie:1" else "tmdb:movie:987654"
                decodeRecentActivityPage(RecentActivityFixture.synthetic(701, offset, 1, id), limit, offset)
            })
        val options = BrowseOptions(originalLanguages = setOf("fra", "nob"), includeUnknownOriginalLanguage = true)
        model.options = options; model.loadPage()
        var link: HomeLink? = null; model.pickRandom { link = it }; runCurrent()
        assertEquals(listOf(0, 650), reads.map { it.first }); assertTrue(reads.all { it.second == options })
        assertEquals("tmdb:movie:987654", (link as HomeLink.Details).target.titleId)
    }
    @Test fun `held random reply cannot navigate after original page or client retires`() = runTest {
        for (change in listOf("page", "client")) {
            var selected = true; var client = "synthetic-client"; var calls = 0
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val model = ListGridModel(RecentActivityFixture.list(), emptyMap(), backgroundScope, clientIdentity = { client }, pageSelected = { selected }, pageSize = 1, randomOffset = { 650 },
                readPage = { _, limit, offset, _, _, _ ->
                    if (calls++ > 0) withContext(NonCancellable) { entered.complete(Unit); release.await() }
                    decodeRecentActivityPage(RecentActivityFixture.synthetic(701, offset, 1), limit, offset)
                })
            model.loadPage(); var link: HomeLink? = null; model.pickRandom { link = it }; entered.await()
            if (change == "page") { selected = false; model.pageSelectionChanged(false); selected = true } else client = "replacement-client"
            release.complete(Unit); runCurrent()
            assertNull(link); assertEquals(2, calls); assertTrue(model.cards.isEmpty())
        }
    }

}
