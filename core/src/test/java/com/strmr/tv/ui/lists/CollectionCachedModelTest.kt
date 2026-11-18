package com.strmr.tv.ui.lists

import com.strmr.tv.api.*
import com.strmr.tv.home.*
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionCachedModelTest {
    private fun entry(parent: Job) = BrowseList.CollectionCached.open(CollectionRef(77, "Collection"), ProfileDataContext.current, parent) { true }!!
    @Test fun `actual model pages Go rows with complete total and independent cache-only marker`() = runTest {
        val list = entry(backgroundScope.coroutineContext[Job]!!); val offsets = mutableListOf<Int>()
        val model = ListGridModel(list, emptyMap(), backgroundScope, readPage = { _, limit, offset, _, _, expected -> assertEquals(list.originalContext, expected); offsets += offset; CollectionFixture.page(if (offset == 0) "unfiltered" else "continuation", limit, offset) }, pageSize = 2, clockMillis = { CollectionFixture.now })
        model.reload(); model.loadPage()
        assertEquals(listOf(0, 2), offsets); assertEquals(701, model.total); assertEquals(4, model.cards.size)
        assertTrue(model.cards.all { it.cacheOnlyBrowseEntry && it.reloadStoredRating == null && it.titleSourceCoordinate() == null })
        assertEquals(77, model.collectionObservation!!.id)
    }
    @Test fun `actual entry rejects retired producer target and A B A before screen composition`() = runTest {
        val parent = Job(); assertNull(BrowseList.CollectionCached.open(CollectionRef(77, "C"), ProfileDataContext.current, parent) { false })
        parent.cancel(); assertNull(BrowseList.CollectionCached.open(CollectionRef(77, "C"), ProfileDataContext.current, parent) { true })
        val list = entry(backgroundScope.coroutineContext[Job]!!); ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); var calls = 0
        val model = ListGridModel(list, emptyMap(), backgroundScope, readPage = { _, _, _, _, _, _ -> calls++; CollectionFixture.page("unfiltered") }, clockMillis = { CollectionFixture.now })
        model.reload(); assertEquals(0, calls); assertTrue(model.cards.isEmpty()); assertEquals(list.originalContext, model.dataContext)
    }
    @Test fun `held equal-profile original-page entry cannot open after the page is covered`() = runTest {
        val ref = CollectionRef(77, "Collection")
        val owner = ProfileDataContext.current
        var originalPage = true
        var opens = 0
        val heldClick = {
            BrowseList.CollectionCached.open(ref, owner, backgroundScope.coroutineContext[Job]!!) { originalPage }
                ?.also { opens++ }
        }
        originalPage = false
        assertNull(heldClick())
        assertEquals(0, opens)
        originalPage = true
        assertNotNull(heldClick())
        assertEquals(1, opens)
        assertEquals(owner, ProfileDataContext.current)
    }
    @Test fun `cancelled grid producer hides old rows and rejects both new reads and masked late replies`() = runTest {
        val producer = Job()
        val gridScope = CoroutineScope(coroutineContext + producer)
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val model = ListGridModel(entry(backgroundScope.coroutineContext[Job]!!), emptyMap(), gridScope,
            readPage = { _, _, _, _, _, _ -> calls++; withContext(NonCancellable) { gate.await() }; CollectionFixture.page("unfiltered") },
            clockMillis = { CollectionFixture.now })
        val pending = async { runCatching { model.reload() }.exceptionOrNull() }
        runCurrent()
        producer.cancel()
        gate.complete(Unit)
        assertTrue(pending.await() is CancellationException)
        model.reload()
        model.loadPage()
        assertEquals(1, calls)
        assertTrue(model.cards.isEmpty())
        assertNull(model.total)
        assertFalse(model.isCurrent)
    }
    @Test fun `new options and masked late page cannot restore old collection rows`() = runTest {
        val list = entry(backgroundScope.coroutineContext[Job]!!); val gate = CompletableDeferred<Unit>(); var calls = 0
        val model = ListGridModel(list, emptyMap(), backgroundScope, readPage = { _, _, _, _, options, _ -> if (calls++ == 0) { withContext(NonCancellable) { gate.await() }; CollectionFixture.page("unfiltered") } else { assertTrue(options.includeUnknownOriginalLanguage); CollectionFixture.page("empty-filter") } }, clockMillis = { CollectionFixture.now })
        val old = async { runCatching { model.reload() }.exceptionOrNull() }; runCurrent()
        model.options = BrowseOptions(includeUnknownOriginalLanguage = true); model.reload(); gate.complete(Unit)
        assertTrue(old.await() is CancellationException); assertEquals(0, model.total); assertTrue(model.cards.isEmpty()); assertEquals(2, calls)
    }
    @Test fun `expiry after first page blocks paging before IO without automatic producer refresh`() = runTest {
        var now = CollectionFixture.now; var calls = 0
        val model = ListGridModel(entry(backgroundScope.coroutineContext[Job]!!), emptyMap(), backgroundScope, readPage = { _, _, _, _, _, _ -> calls++; CollectionFixture.page("unfiltered") }, pageSize = 2, clockMillis = { now })
        model.reload(); now = model.collectionObservation!!.expiresMillis; model.loadPage(); model.loadPage()
        assertEquals(1, calls); assertTrue(model.cards.isEmpty()); assertNull(model.total); assertNotNull(model.loadError)
    }
    @Test fun `changed pool observation unsupported malformed and 409 never fall back to normal collection`() = runTest {
        for (mode in 0..3) {
            var calls = 0
            val model = ListGridModel(entry(backgroundScope.coroutineContext[Job]!!), emptyMap(), backgroundScope, readPage = { list, _, _, _, _, _ ->
                assertTrue(list is BrowseList.CollectionCached)
                if (calls++ == 0) CollectionFixture.page("unfiltered") else when (mode) {
                    0 -> CollectionFixture.page("continuation", offset = 2).let { it.copy(collection = it.collection!!.copy(name = "Changed")) }
                    1 -> CollectionFixture.page("continuation", offset = 2).copy(originalLanguageFilterSupported = false)
                    2 -> decodeCachedCollectionPage("{}", 77, now = CollectionFixture.now)
                    else -> throw ApiError.Http(HttpFailure(409, CollectionFixture.wire("missing")))
                }
            }, pageSize = 2, clockMillis = { CollectionFixture.now })
            model.reload(); model.loadPage(); model.loadPage(); assertEquals(2, calls); assertTrue(model.cards.isEmpty()); assertNull(model.total); assertNotNull(model.loadError)
        }
    }
    @Test fun `random keeps exact server language options without client page filtering`() = runTest {
        var selected: BrowseOptions? = null; val model = ListGridModel(entry(backgroundScope.coroutineContext[Job]!!), emptyMap(), backgroundScope,
            readPage = { _, _, offset, _, options, _ -> selected = options; if (offset == 0) CollectionFixture.page("unfiltered") else CollectionFixture.page("late-french") },
            randomOffset = { 700 }, pageSize = 2, clockMillis = { CollectionFixture.now })
        model.options = BrowseOptions(originalLanguages = setOf("fra", "nob")); model.reload(); var link: HomeLink? = null
        model.pickRandom { link = it }; runCurrent()
        // This synthetic changed filtered total must fail closed, not open a mixed view.
        assertNull(link); assertEquals(setOf("fra", "nob"), selected!!.originalLanguages); assertNotNull(model.loadError)
    }
    @Test fun `valid multi-page reread consumes a fresh observed row set rather than prior UI rows`() = runTest {
        val model = ListGridModel(entry(backgroundScope.coroutineContext[Job]!!), emptyMap(), backgroundScope,
            readPage = { _, limit, offset, _, _, _ -> CollectionFixture.page(if (offset == 0) "unfiltered" else "continuation", limit, offset) }, pageSize = 2, clockMillis = { CollectionFixture.now })
        model.reload(); model.loadPage(); model.rereadLoaded()
        assertEquals(4, model.cards.size); assertEquals(701, model.total); assertNull(model.loadError)
    }
    @Test fun `normal random selects an unloaded server filtered row using identical options`() = runTest {
        val pool = CollectionFixture.page("random-pool"); val seen = mutableListOf<Pair<Int, BrowseOptions>>()
        val model = ListGridModel(entry(backgroundScope.coroutineContext[Job]!!), emptyMap(), backgroundScope,
            readPage = { _, limit, offset, _, options, _ -> assertEquals(1, limit); seen += offset to options; pool.copy(entries = listOf(pool.entries[offset])) },
            pageSize = 1, randomOffset = { 1 }, clockMillis = { CollectionFixture.now })
        model.options = BrowseOptions(sort = BrowseSort.NAME, ascending = true, originalLanguages = setOf("fra", "nob")); model.reload()
        var link: HomeLink? = null; model.pickRandom { link = it }; runCurrent()
        assertEquals((pool.entries.last() as BrowseEntry.TitleEntry).title.id, (link as HomeLink.Details).target.titleId)
        assertEquals(listOf(0, 1), seen.map { it.first }); assertTrue(seen.all { it.second.originalLanguages == setOf("fra", "nob") })
    }

}
