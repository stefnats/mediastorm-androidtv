package com.strmr.tv.ui.lists

import com.strmr.tv.api.*
import com.strmr.tv.home.*
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PopularActivityModelTest {
    @Test fun `complete popular pagination keeps exact totals beyond500 and real targets separate from row keys`() = runTest {
        val offsets = mutableListOf<Int>()
        val model = ListGridModel(PopularActivityFixture.list(), emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, pageSize = 601,
            readPage = { source, limit, offset, _, _, _ -> assertTrue(source is BrowseList.PopularOnServerCached); offsets += offset; decodePopularActivityPage(PopularActivityFixture.synthetic(701, offset, minOf(limit, 701 - offset)), limit, offset) })
        model.loadPage(); val oldKeys = model.cards.map { it.id }; model.loadPage()
        assertEquals(listOf(0, 601), offsets); assertEquals(701, model.fetched); assertEquals(701, model.total)
        assertEquals(701, model.cards.size); assertEquals(701, model.cards.map { it.id }.distinct().size)
        assertEquals(oldKeys, model.cards.take(601).map { it.id })
        assertEquals("tmdb:movie:701", model.cards.last().target!!.titleId)
        assertTrue(model.cards.all { it.id != it.target!!.titleId && it.rank == null && it.cacheOnlyBrowseEntry && it.titleSourceCoordinate() == null && it.reloadStoredRating == null })
        model.loadPage(); assertEquals(2, offsets.size)
    }
    @Test fun `genuine late language matches and continuation remain server filtered before total and page`() = runTest {
        val first = PopularActivityFixture.page("late-filtered"); val next = PopularActivityFixture.page("late-continuation")
        var calls = 0
        val model = ListGridModel(PopularActivityFixture.list(), emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, pageSize = 1,
            readPage = { source, limit, offset, _, options, _ -> assertTrue(source is BrowseList.PopularOnServerCached); assertEquals(1, limit); assertEquals(setOf("fra"), options.originalLanguages); assertEquals(calls, offset); calls++; if (offset == 0) first else next })
        model.options = BrowseOptions(originalLanguages = setOf("fra")); model.loadPage(); model.loadPage()
        assertEquals(2, model.total); assertEquals(2, model.cards.size)
        assertEquals(listOf("tmdb:movie:650", "tmdb:movie:653"), model.cards.map { it.target!!.titleId })
        assertTrue(model.originalLanguageFilterSupported)
    }
    @Test fun `same public primary text retains movie series identity and live page overlap without client regroup`() = runTest {
        val kinds = listOf("movie", "series", "movie")
        val model = ListGridModel(PopularActivityFixture.list(), emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, pageSize = 2,
            readPage = { _, limit, offset, _, _, _ -> decodePopularActivityPage(PopularActivityFixture.synthetic(3, offset, minOf(limit, 3 - offset), views = 4) { "tmdb:42" to kinds[it] }, limit, offset) })
        model.loadPage(); model.loadPage()
        assertEquals(3, model.fetched); assertEquals(3, model.cards.size); assertEquals(3, model.cards.map { it.id }.distinct().size)
        assertEquals(listOf(true, false, true), model.cards.map { it.target!!.isMovie })
        assertTrue(model.cards.all { it.target!!.titleId == "tmdb:42" && it.subtitle == "4 views" && it.rank == null })
    }
    @Test fun `live total changes stay exact and shrinking behind offset requires explicit reload`() = runTest {
        var calls = 0
        val model = ListGridModel(PopularActivityFixture.list(), emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, pageSize = 2,
            readPage = { _, limit, offset, _, _, _ -> val total = if (calls++ == 0) 10 else 3; decodePopularActivityPage(PopularActivityFixture.synthetic(total, offset, minOf(limit, maxOf(0, total - offset))), limit, offset) })
        model.loadPage(); model.loadPage(); assertEquals(3, model.total); assertEquals(3, model.cards.size)
        var shrinking = false
        val shrink = ListGridModel(PopularActivityFixture.list(), emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, pageSize = 2,
            readPage = { _, limit, offset, _, _, _ -> val total = if (shrinking) 1 else 10; decodePopularActivityPage(PopularActivityFixture.synthetic(total, offset, minOf(limit, maxOf(0, total - offset))), limit, offset) })
        shrink.loadPage(); shrinking = true; shrink.loadPage()
        assertNull(shrink.total); assertEquals(0, shrink.fetched); assertTrue(shrink.cards.isEmpty()); assertNotNull(shrink.loadError)
        shrink.loadPage(); assertTrue(shrink.cards.isEmpty())
        shrink.reload(); assertEquals(1, shrink.total); assertEquals(1, shrink.cards.size)
    }
    @Test fun `masked stale options and covered page replies cannot restore previous results`() = runTest {
        val gate = CompletableDeferred<Unit>(); var calls = 0
        val model = ListGridModel(PopularActivityFixture.list(), emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, pageSize = 3,
            readPage = { _, _, _, _, options, _ -> if (calls++ == 0) { withContext(NonCancellable) { gate.await() }; PopularActivityFixture.page("counts-default") } else { assertTrue(options.includeUnknownOriginalLanguage); PopularActivityFixture.page("empty-capability") } })
        val old = async { runCatching { model.loadPage() }.exceptionOrNull() }; runCurrent()
        model.options = BrowseOptions(includeUnknownOriginalLanguage = true); model.loadPage(); gate.complete(Unit)
        assertTrue(old.await() is CancellationException); assertEquals(0, model.total); assertTrue(model.cards.isEmpty()); assertTrue(model.originalLanguageFilterSupported)
        var selected = true; val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val covered = ListGridModel(PopularActivityFixture.list(), emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, pageSelected = { selected }, pageSize = 3,
            readPage = { _, _, _, _, _, _ -> withContext(NonCancellable) { entered.complete(Unit); release.await() }; PopularActivityFixture.page("counts-default") })
        val late = async { runCatching { covered.loadPage() }.exceptionOrNull() }; entered.await()
        selected = false; covered.pageSelectionChanged(false); selected = true; release.complete(Unit)
        assertTrue(late.await() is CancellationException); assertTrue(covered.cards.isEmpty()); assertNull(covered.total); assertNotNull(covered.loadError)
    }
    @Test fun `client producer and profile ABA retire protected popular rows and old API calls`() = runTest {
        val list = PopularActivityFixture.list(); var client = "synthetic-client"; var calls = 0
        val producer = Job(); val scope = CoroutineScope(coroutineContext + producer)
        val model = ListGridModel(list, emptyMap(), scope, clientIdentity = { client }, pageSize = 3,
            readPage = { _, _, _, _, _, _ -> calls++; PopularActivityFixture.page("counts-default") })
        model.loadPage(); client = "replacement-client"; model.loadPage()
        assertFalse(model.isCurrent); assertTrue(model.cards.isEmpty()); assertEquals(1, calls)
        producer.cancel()
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        val stale = ListGridModel(list, emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, readPage = { _, _, _, _, _, _ -> calls++; error("stale read") })
        stale.loadPage(); assertEquals(1, calls); assertFalse(stale.isCurrent)
        assertTrue(runCatching { ApiClient.browsePage(list, 1, expectedContext = list.originalContext) }.exceptionOrNull() is CancellationException)
    }
    @Test fun `unsupported denied or malformed results block without ordinary popularity fallback`() = runTest {
        for (error in listOf<Exception>(ManagementPageFailure(), ApiError.Http(HttpFailure(403, "{}")), ApiError.Http(HttpFailure(409, "{}")), ApiError.Http(HttpFailure(503, "{}")))) {
            var calls = 0
            val model = ListGridModel(PopularActivityFixture.list(), emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, readPage = { source, _, _, _, _, _ -> assertTrue(source is BrowseList.PopularOnServerCached); calls++; throw error })
            model.loadPage(); model.loadPage(); assertEquals(1, calls); assertTrue(model.cards.isEmpty()); assertNotNull(model.loadError)
        }
        val unsupported = ListGridModel(PopularActivityFixture.list(), emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, readPage = { _, _, _, _, _, _ -> BrowsePage(emptyList(), 0) })
        unsupported.loadPage(); assertNull(unsupported.total); assertNotNull(unsupported.loadError)
    }
    @Test fun `both normal entry routes open current cached views while keeping ordinary lists unchanged`() = runTest {
        for (origin in listOf(PopularActivityFixture.origin, BrowseList.PopularOnServer)) {
            var selected = true
            val normal = ListGridModel(origin, mapOf("homeView" to "shows", "hideUnreleased" to "true"), backgroundScope, clientIdentity = { "synthetic-client" })
            assertTrue(normal.canOpenSavedPopular); assertFalse(normal.canOpenSavedRecent)
            val list = requireNotNull(normal.openSavedPopular(backgroundScope.coroutineContext[Job]!!) { selected })
            val saved = ListGridModel(list, emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" })
            assertTrue(saved.isCurrent); assertEquals(BrowseKind.SHOWS, saved.options.kind)
            selected = false; assertNull(normal.openSavedPopular(backgroundScope.coroutineContext[Job]!!) { selected })
            assertEquals("popular-on-server", normal.list.query["source"])
        }
    }
    @Test fun `unloaded filtered random selection beyond500 opens actual title and retires late owner replies`() = runTest {
        val reads = mutableListOf<Int>(); val options = BrowseOptions(originalLanguages = setOf("fra", "nob"), includeUnknownOriginalLanguage = true)
        val model = ListGridModel(PopularActivityFixture.list(), emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, pageSize = 1, randomOffset = { 650 },
            readPage = { source, limit, offset, _, selected, _ -> assertTrue(source is BrowseList.PopularOnServerCached); assertEquals(options, selected); reads += offset; decodePopularActivityPage(PopularActivityFixture.synthetic(701, offset, 1) { (if (offset == 0) "tmdb:movie:1" else "tmdb:movie:987654") to "movie" }, limit, offset) })
        model.options = options; model.loadPage(); var link: HomeLink? = null; model.pickRandom { link = it }; runCurrent()
        assertEquals(listOf(0, 650), reads); assertEquals("tmdb:movie:987654", (link as HomeLink.Details).target.titleId)
        for (change in listOf("page", "client")) {
            var selected = true; var client = "synthetic-client"; var calls = 0
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val held = ListGridModel(PopularActivityFixture.list(), emptyMap(), backgroundScope, clientIdentity = { client }, pageSelected = { selected }, pageSize = 1, randomOffset = { 650 },
                readPage = { _, limit, offset, _, _, _ -> if (calls++ > 0) withContext(NonCancellable) { entered.complete(Unit); release.await() }; decodePopularActivityPage(PopularActivityFixture.synthetic(701, offset, 1), limit, offset) })
            held.loadPage(); var opened: HomeLink? = null; held.pickRandom { opened = it }; entered.await()
            if (change == "page") { selected = false; held.pageSelectionChanged(false); selected = true } else client = "replacement-client"
            release.complete(Unit); runCurrent(); assertNull(opened); assertEquals(2, calls); assertTrue(held.cards.isEmpty())
        }
    }
}
