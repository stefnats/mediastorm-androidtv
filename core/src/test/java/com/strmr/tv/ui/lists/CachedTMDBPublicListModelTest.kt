package com.strmr.tv.ui.lists

import com.strmr.tv.api.*
import com.strmr.tv.home.*
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CachedTMDBPublicListModelTest {
    @Test fun `complete finite pool beyond500 and genuine late language continuation preserve exact targets and totals`() = runTest {
        val offsets = mutableListOf<Int>(); val source = PublicListFixture.list()
        val model = ListGridModel(source, emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, pageSize = 601,
            readPage = { list, limit, offset, _, _, _ -> offsets += offset; decodeCachedTMDBPublicListPage(PublicListFixture.synthetic(701, offset, minOf(limit, 701 - offset)), list as BrowseList.TMDBPublicListCached, PublicListFixture.clock(), limit, offset) })
        model.loadPage(); val old = model.cards.map { it.id }; model.loadPage()
        assertEquals(listOf(0, 601), offsets); assertEquals(701, model.total); assertEquals(701, model.fetched); assertEquals(701, model.cards.size)
        assertEquals(701, model.cards.map { it.id }.distinct().size); assertEquals(old, model.cards.take(601).map { it.id })
        assertTrue(model.cards.all { it.id != it.target!!.titleId && it.cacheOnlyBrowseEntry && it.titleSourceCoordinate() == null && it.rank == null && it.reloadStoredRating == null })
        assertEquals("tmdb:movie:701", model.cards.last().target!!.titleId)
        var count = 0
        val real = ListGridModel(PublicListFixture.list(), emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, pageSize = 1,
            readPage = { list, limit, offset, _, options, _ -> assertTrue(list is BrowseList.TMDBPublicListCached); assertEquals(setOf("fra"), options.originalLanguages); assertEquals(1, limit); assertEquals(count++, offset); PublicListFixture.page(if (offset == 0) "late-filtered" else "late-continuation") })
        real.options = BrowseOptions(originalLanguages = setOf("fra")); real.loadPage(); real.loadPage()
        assertEquals(2, real.total); assertEquals(listOf("tmdb:tv:650", "tmdb:movie:653"), real.cards.map { it.target!!.titleId })
    }
    @Test fun `source descriptor changes while appending clear rows and explicit reload can adopt current observation`() = runTest {
        var replacement = false; var calls = 0
        val list = PublicListFixture.list()
        val model = ListGridModel(list, emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, pageSize = 2,
            readPage = { _, limit, offset, _, _, _ -> calls++; val delta = if (replacement) 1000L else 0L; decodeCachedTMDBPublicListPage(PublicListFixture.synthetic(3, offset, minOf(limit, 3 - offset), observed = PublicListFixture.date - 300_000L + delta, expires = PublicListFixture.date + 21_600_000L + delta), list, PublicListFixture.clock(), limit, offset) })
        model.loadPage(); replacement = true; model.loadPage()
        assertTrue(model.cards.isEmpty()); assertNull(model.total); assertNotNull(model.loadError); model.loadPage(); assertEquals(2, calls)
        model.reload(); assertEquals(3, calls); assertEquals(3, model.total); assertEquals(2, model.cards.size)
    }
    @Test fun `current language and hidden membership may change totals without inventing global pool immutability`() = runTest {
        var calls = 0; val list = PublicListFixture.list()
        val model = ListGridModel(list, emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, pageSize = 2,
            readPage = { _, limit, offset, _, _, _ -> val total = if (calls++ == 0) 10 else 3; decodeCachedTMDBPublicListPage(PublicListFixture.synthetic(total, offset, minOf(limit, maxOf(0, total - offset))), list, PublicListFixture.clock(), limit, offset) })
        model.loadPage(); model.loadPage(); assertEquals(3, model.total); assertEquals(3, model.cards.size)
        var shrink = false
        val shrinking = ListGridModel(PublicListFixture.list(), emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, pageSize = 2,
            readPage = { source, limit, offset, _, _, _ -> val total = if (shrink) 1 else 10; decodeCachedTMDBPublicListPage(PublicListFixture.synthetic(total, offset, minOf(limit, maxOf(0, total - offset))), source as BrowseList.TMDBPublicListCached, PublicListFixture.clock(), limit, offset) })
        shrinking.loadPage(); shrink = true; shrinking.loadPage(); assertTrue(shrinking.cards.isEmpty()); assertNull(shrinking.total); assertNotNull(shrinking.loadError)
    }
    @Test fun `same descriptor does not deduplicate live overlap across pages or alter real series actions`() = runTest {
        val list = PublicListFixture.list(kind = BrowseKind.SHOWS)
        val model = ListGridModel(list, emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, pageSize = 1,
            readPage = { _, limit, offset, _, _, _ -> decodeCachedTMDBPublicListPage(PublicListFixture.synthetic(2, offset, 1, kind = "series") { "tmdb:series:42" }, list, PublicListFixture.clock(), limit, offset) })
        model.loadPage(); model.loadPage()
        assertEquals(2, model.cards.size); assertEquals(2, model.cards.map { it.id }.distinct().size)
        assertTrue(model.cards.all { it.target!!.titleId == "tmdb:series:42" && !it.target!!.isMovie })
    }
    @Test fun `expiry hides cards and blocks passive near-end or random reads until explicit cache reload`() = runTest {
        val list = PublicListFixture.list(); var nanos = System.nanoTime(); var fresh = false; var calls = 0
        val model = ListGridModel(list, emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, pageSize = 1, monotonicNanos = { nanos },
            readPage = { _, limit, offset, _, _, _ -> calls++; val delta = if (fresh) 1000L else 0L; decodeCachedTMDBPublicListPage(PublicListFixture.synthetic(2, offset, 1, observed = PublicListFixture.date - 300_000L + delta, expires = PublicListFixture.date + if (fresh) 11_000L else 1602L), list, PublicListFixture.clock(PublicListFixture.date + delta), limit, offset) })
        model.loadPage(); assertEquals(1, model.cards.size)
        nanos += 1_000_000_000L; assertTrue(model.cards.isEmpty()); assertNull(model.total)
        advanceTimeBy(1001); runCurrent()
        model.loadPage(); var opened: HomeLink? = null; model.pickRandom { opened = it }; runCurrent()
        assertEquals(1, calls); assertNull(opened); assertTrue(model.cards.isEmpty()); assertNotNull(model.loadError)
        fresh = true; model.reload(); assertEquals(2, calls); assertEquals(1, model.cards.size)
    }
    @Test fun `masked previous options and covered page replies cannot replace current empty supported result`() = runTest {
        val release = CompletableDeferred<Unit>(); var calls = 0
        val list = PublicListFixture.list()
        val model = ListGridModel(list, emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, pageSize = 5,
            readPage = { _, _, _, _, options, _ -> if (calls++ == 0) { withContext(NonCancellable) { release.await() }; PublicListFixture.page("mixed-unfiltered") } else { assertTrue(options.includeUnknownOriginalLanguage); PublicListFixture.page("empty-capability") } })
        val old = async { runCatching { model.loadPage() }.exceptionOrNull() }; runCurrent()
        model.options = BrowseOptions(includeUnknownOriginalLanguage = true); model.loadPage(); release.complete(Unit)
        assertTrue(old.await() is CancellationException); assertEquals(0, model.total); assertTrue(model.originalLanguageFilterSupported)
        var selected = true; val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val covered = ListGridModel(PublicListFixture.list(), emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, pageSelected = { selected }, pageSize = 5,
            readPage = { _, _, _, _, _, _ -> withContext(NonCancellable) { entered.complete(Unit); gate.await() }; PublicListFixture.page("mixed-unfiltered") })
        val late = async { runCatching { covered.loadPage() }.exceptionOrNull() }; entered.await()
        selected = false; covered.pageSelectionChanged(false); selected = true; gate.complete(Unit)
        assertTrue(late.await() is CancellationException); assertTrue(covered.cards.isEmpty()); assertNull(covered.total)
    }
    @Test fun `original client producer and profile ABA retire current rows before any stale API request`() = runTest {
        val list = PublicListFixture.list(); var client = "synthetic-client"; var calls = 0
        val producer = Job(); val scope = CoroutineScope(coroutineContext + producer)
        val model = ListGridModel(list, emptyMap(), scope, clientIdentity = { client }, pageSize = 5,
            readPage = { _, _, _, _, _, _ -> calls++; PublicListFixture.page("mixed-unfiltered") })
        model.loadPage(); client = "replacement-client"; model.loadPage(); assertFalse(model.isCurrent); assertTrue(model.cards.isEmpty()); assertEquals(1, calls)
        producer.cancel(); ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        val stale = ListGridModel(list, emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, readPage = { _, _, _, _, _, _ -> error("stale read") })
        stale.loadPage(); assertFalse(stale.isCurrent)
        assertTrue(runCatching { ApiClient.browsePage(list, 1, expectedContext = list.originalContext) }.exceptionOrNull() is CancellationException)
    }
    @Test fun `denied malformed and unsupported cached replies never fall back to normal list generation`() = runTest {
        for (error in listOf<Exception>(ManagementPageFailure(), ApiError.Http(HttpFailure(403, "{}")), ApiError.Http(HttpFailure(409, "{}")), ApiError.Http(HttpFailure(503, "{}")))) {
            var calls = 0
            val model = ListGridModel(PublicListFixture.list(), emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, readPage = { source, _, _, _, _, _ -> assertTrue(source is BrowseList.TMDBPublicListCached); calls++; throw error })
            model.loadPage(); model.loadPage(); assertEquals(1, calls); assertTrue(model.cards.isEmpty()); assertNotNull(model.loadError)
        }
        val unsupported = ListGridModel(PublicListFixture.list(), emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, readPage = { _, _, _, _, _, _ -> BrowsePage(emptyList(), 0) })
        unsupported.loadPage(); assertNull(unsupported.total); assertNotNull(unsupported.loadError)
        val normal = ListGridModel(PublicListFixture.origin(), mapOf("homeView" to "movies", "hideUnreleased" to "false"), backgroundScope, clientIdentity = { "synthetic-client" })
        assertTrue(normal.canOpenSavedPublicList); val saved = requireNotNull(normal.openSavedPublicList(backgroundScope.coroutineContext[Job]!!) { true })
        assertTrue(ListGridModel(saved, emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }).isCurrent)
        assertEquals("tmdb-list", normal.list.query["source"]); assertNull(normal.openSavedPublicList(backgroundScope.coroutineContext[Job]!!) { false })
    }
    @Test fun `unloaded filtered random target is real and held replies cannot navigate after page client or expiry retires`() = runTest {
        val options = BrowseOptions(originalLanguages = setOf("fra", "nob"), includeUnknownOriginalLanguage = true); val reads = mutableListOf<Int>(); val list = PublicListFixture.list()
        val model = ListGridModel(list, emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" }, pageSize = 1, randomOffset = { 650 },
            readPage = { _, limit, offset, _, selected, _ -> assertEquals(options, selected); reads += offset; decodeCachedTMDBPublicListPage(PublicListFixture.synthetic(701, offset, 1) { if (offset == 0) "tmdb:movie:1" else "tmdb:movie:987654" }, list, PublicListFixture.clock(), limit, offset) })
        model.options = options; model.loadPage(); var link: HomeLink? = null; model.pickRandom { link = it }; runCurrent()
        assertEquals(listOf(0, 650), reads); assertEquals("tmdb:movie:987654", (link as HomeLink.Details).target.titleId)
        for (change in listOf("page", "client", "expiry")) {
            var selected = true; var client = "synthetic-client"; var nanos = System.nanoTime(); var calls = 0
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val source = PublicListFixture.list()
            val held = ListGridModel(source, emptyMap(), backgroundScope, clientIdentity = { client }, pageSelected = { selected }, monotonicNanos = { nanos }, pageSize = 1, randomOffset = { 650 },
                readPage = { _, limit, offset, _, _, _ -> if (calls++ > 0) withContext(NonCancellable) { entered.complete(Unit); release.await() }; decodeCachedTMDBPublicListPage(PublicListFixture.synthetic(701, offset, 1, expires = PublicListFixture.date + 1602L), source, PublicListFixture.clock(), limit, offset) })
            held.loadPage(); var opened: HomeLink? = null; held.pickRandom { opened = it }; entered.await()
            if (change == "page") { selected = false; held.pageSelectionChanged(false); selected = true } else if (change == "client") client = "replacement-client" else nanos += 1_000_000_000L
            release.complete(Unit); runCurrent(); assertNull(opened); assertEquals(2, calls); assertTrue(held.cards.isEmpty())
        }
    }
    @Test fun `explicit saved view visibly starts default ordering without changing effective normal year or rating sort`() = runTest {
        for (sort in listOf(BrowseSort.YEAR, BrowseSort.RATING)) {
            val normal = ListGridModel(PublicListFixture.origin(), emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" })
            val original = BrowseOptions(sort = sort, ascending = true)
            normal.options = original
            assertEquals(sort.value, browseQuery(normal.list, 1, 0, options = normal.options)["sortBy"])
            assertTrue(normal.canOpenSavedPublicList)
            val list = requireNotNull(normal.openSavedPublicList(backgroundScope.coroutineContext[Job]!!) { true })
            val saved = ListGridModel(list, emptyMap(), backgroundScope, clientIdentity = { "synthetic-client" })
            assertEquals(BrowseSort.DEFAULT, saved.options.sort)
            val query = cachedTMDBPublicListRequest("profile", list, 1, 0, saved.options).query
            assertFalse(query.containsKey("sortBy")); assertEquals("tmdb-public-list-cached", query["source"])
            assertEquals(original, normal.options); assertEquals("tmdb-list", normal.list.query["source"])
        }
    }

}
