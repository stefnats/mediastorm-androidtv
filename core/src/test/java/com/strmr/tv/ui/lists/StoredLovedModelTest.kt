package com.strmr.tv.ui.lists

import com.strmr.tv.api.*
import com.strmr.tv.home.*
import com.strmr.tv.library.*
import com.strmr.tv.ratings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Real shared model/card callback/accepted-action adapters, no Android/Compose execution. */
@OptIn(ExperimentalCoroutinesApi::class)
class StoredLovedModelTest {
    private fun page(id: String = "tmdb:movie:2", rank: Int = 1, total: Int = 1, kind: String = "movie") = BrowsePage(
        listOf(BrowseEntry.TitleEntry(Title(id, "Synthetic", mediaType = kind, personalRating = "love"), StoredLoveRow(rank, "2026-11-16T00:00:00Z"))), total, originalLanguageFilterSupported = true)
    private class Gateway : TitleRatingGateway {
        var current = true
        var reads = 0
        var writes = 0
        var hook: suspend () -> Unit = {}
        var reply = "love"
        override fun isCurrent() = current
        override suspend fun read(target: DetailsTarget): TitleRatingReply { reads++; hook(); return TitleRatingReply(reply) }
        override suspend fun write(target: DetailsTarget, rating: PersonalRating): TitleRatingReply { writes++; return TitleRatingReply(rating.wire) }
    }
    private suspend fun rate(model: ListGridModel, card: HomeCard = model.cards.single(), target: DetailsTarget = card.target!!): Unit {
        val operations = CustomListOperations(model.dataContext, target)
        val op = operations.begin()!!
        try { operations.run(op) { card.reloadStoredRating!!(target, it) } } finally { operations.close() }
    }
    @Test fun `actual Go filtered pages total and random use same original options and original global rank`() = runTest {
        val seen = mutableListOf<Triple<Int, Int, BrowseOptions>>()
        fun wire(name: String) = requireNotNull(javaClass.getResourceAsStream("/loved-stored-go/loved-stored-$name.json")).bufferedReader().use { it.readText() }
        val model = ListGridModel(BrowseList.LovedStored, emptyMap(), backgroundScope, readPage = { _, limit, offset, _, options, _ ->
            seen += Triple(limit, offset, options); decodeStoredLovedPage(wire(if (offset == 0) "selected" else "continuation"), limit, offset)
        }, clientIdentity = { "client-a" }, pageSize = 1, randomOffset = { 1 })
        model.options = BrowseOptions(sort = BrowseSort.NAME, ascending = true, originalLanguages = setOf("fre", "nob"))
        model.reload(); assertEquals(2, model.total); assertEquals(1, model.cards.size)
        assertNotNull(model.cards.single().reloadStoredRating); assertNull(model.cards.single().titleSourceCoordinate())
        var picked: HomeLink? = null; model.pickRandom { picked = it }; runCurrent()
        assertEquals("tmdb:movie:3", (picked as HomeLink.Details).target.titleId)
        model.loadPage(); assertEquals(2, model.cards.size); assertEquals(listOf(0, 1, 1), seen.map { it.second })
        assertTrue(seen.all { it.third.originalLanguages == setOf("fra", "nob") })
    }
    @Test fun `exact row renewal and authoritative read occur once with no second initial editor read`() = runTest {
        val requests = mutableListOf<Pair<Int, Int>>(); val g = Gateway(); var editor: TitleRatingEditor? = null
        val model = ListGridModel(BrowseList.LovedStored, emptyMap(), backgroundScope, readPage = { _, limit, offset, _, _, _ -> requests += limit to offset; page() }, clientIdentity = { "client-a" }, ratingGateway = { g }, openRating = { editor = it })
        model.reload(); val card = model.cards.single(); rate(model, card)
        assertEquals(listOf(60 to 0, 1 to 0), requests); assertEquals(1, g.reads); assertNotNull(editor)
        editor!!.load(); assertEquals(1, g.reads); assertEquals(PersonalRating.LOVE, editor!!.state.value.rating); assertEquals(0, g.writes)
        assertNull(card.titleSourceCoordinate())
        assertNotNull(card.copy(reloadStoredRating = null).titleSourceCoordinate())
    }
    @Test fun `ordinary read failure and capability downgrade never fall back to legacy loved`() = runTest {
        for (failure in listOf<Exception>(ApiError.Http(HttpFailure(400, "unsupported display list source")), ApiError.Http(HttpFailure(403, "denied")), ApiError.Http(HttpFailure(503, "unavailable")), ManagementPageFailure())) {
            var calls = 0
            val model = ListGridModel(BrowseList.LovedStored, emptyMap(), backgroundScope, readPage = { list, _, _, _, _, _ -> assertEquals(BrowseList.LovedStored, list); calls++; throw failure }, clientIdentity = { "a" })
            model.reload(); model.loadPage(); assertEquals(1, calls); assertTrue(model.cards.isEmpty()); assertNull(model.total); assertNotNull(model.loadError)
        }
        var calls = 0
        val model = ListGridModel(BrowseList.LovedStored, emptyMap(), backgroundScope, readPage = { _, _, _, _, _, _ -> if (calls++ == 0) page(total = 2) else page(rank = 2, total = 2).copy(originalLanguageFilterSupported = false) }, clientIdentity = { "a" }, pageSize = 1)
        model.reload(); model.loadPage(); assertTrue(model.cards.isEmpty()); assertNull(model.total); assertNotNull(model.loadError)
    }
    @Test fun `shifted rank row or different kind never opens or reads rating`() = runTest {
        for (replacement in listOf(page(id = "tmdb:movie:3"), page(kind = "series"))) {
            var calls = 0; var opens = 0; val g = Gateway()
            val model = ListGridModel(BrowseList.LovedStored, emptyMap(), backgroundScope, readPage = { _, _, _, _, _, _ -> if (calls++ == 0) page() else replacement }, clientIdentity = { "a" }, ratingGateway = { g }, openRating = { opens++ })
            model.reload(); assertTrue(runCatching { rate(model) }.exceptionOrNull() is StoredRatingReloadRequired)
            assertEquals(0, g.reads); assertEquals(0, opens); assertEquals(0, g.writes)
        }
    }
    @Test fun `false stale or denied gateway and invalid loaded value cannot open editor`() = runTest {
        for (mode in 0..3) {
            var opens = 0; val g = Gateway(); if (mode == 0) g.current = false
            if (mode == 1) g.hook = { throw ApiError.Http(HttpFailure(403, "denied")) }
            if (mode == 2) g.hook = { g.current = false }
            if (mode == 3) g.reply = "invalid"
            val model = ListGridModel(BrowseList.LovedStored, emptyMap(), backgroundScope, readPage = { _, _, _, _, _, _ -> page() }, clientIdentity = { "a" }, ratingGateway = { g }, openRating = { opens++ })
            model.reload(); assertTrue(runCatching { rate(model) }.isFailure); assertEquals(0, opens); assertEquals(0, g.writes)
        }
    }
    @Test fun `copied target client replacement and retired options reject before renewal demand`() = runTest {
        var client = "a"; var calls = 0
        val model = ListGridModel(BrowseList.LovedStored, emptyMap(), backgroundScope, readPage = { _, _, _, _, _, _ -> calls++; page() }, clientIdentity = { client })
        model.reload(); val card = model.cards.single()
        assertTrue(runCatching { rate(model, card, card.target!!.copy()) }.isFailure); assertEquals(1, calls)
        model.options = BrowseOptions(originalLanguages = setOf("fra"))
        assertTrue(runCatching { rate(model, card) }.isFailure); assertEquals(1, calls)
        client = "b"; assertTrue(model.cards.isEmpty()); assertNull(model.total)
        assertTrue(runCatching { rate(model, card) }.isFailure); assertEquals(1, calls)
    }
    @Test fun `queued epoch retirement and late masked row reply cannot open new owner editor`() = runTest {
        var calls = 0; var opens = 0; val gate = CompletableDeferred<Unit>(); val g = Gateway()
        val model = ListGridModel(BrowseList.LovedStored, emptyMap(), backgroundScope, readPage = { _, _, _, _, _, _ -> calls++; if (calls > 1) withContext(NonCancellable) { gate.await() }; page() }, clientIdentity = { "a" }, ratingGateway = { g }, openRating = { opens++ })
        model.reload(); val card = model.cards.single()
        val old = async { runCatching { rate(model, card) }.exceptionOrNull() }; runCurrent()
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); gate.complete(Unit)
        assertTrue(old.await() is CancellationException); assertEquals(0, g.reads); assertEquals(0, opens); assertTrue(model.cards.isEmpty())
        assertTrue(runCatching { rate(model, card) }.isFailure); assertEquals(2, calls)
    }
    @Test fun `rating change immediately retires old cards counts and callbacks then reloads same options`() = runTest {
        var calls = 0; val gate = CompletableDeferred<Unit>()
        val model = ListGridModel(BrowseList.LovedStored, emptyMap(), backgroundScope, readPage = { _, _, _, _, options, _ -> assertEquals(setOf("fra"), options.originalLanguages); calls++; if (calls > 1) gate.await(); page() }, clientIdentity = { "a" })
        model.options = BrowseOptions(originalLanguages = setOf("fra")); model.reload(); val oldCard = model.cards.single()
        backgroundScope.launch { model.followStoredRatingChanges() }; runCurrent()
        TitleRatingChanges.saved(); assertTrue(model.cards.isEmpty()); assertNull(model.total)
        assertTrue(runCatching { rate(model, oldCard) }.isFailure)
        runCurrent(); assertEquals(2, calls); gate.complete(Unit); runCurrent(); assertEquals(1, model.cards.size); assertEquals(1, model.total)
    }
    @Test fun `accepted rate normal dismiss survives but producer cancellation blocks further stages`() = runTest {
        var opens = 0; var calls = 0; val gate = CompletableDeferred<Unit>(); val parent = Job()
        val model = ListGridModel(BrowseList.LovedStored, emptyMap(), backgroundScope, readPage = { _, _, _, _, _, _ -> calls++; if (calls > 1) gate.await(); page() }, clientIdentity = { "a" }, openRating = { opens++ })
        model.reload(); val card = model.cards.single(); val selection = TitleMenuSelection(card, model.dataContext, parent)
        val action = TitleListAction(selection.origin, backgroundScope) { card.reloadStoredRating!!(card.target!!, it) }
        val click = action.accept()!!; val job = action.commit(click) { selection.close() }!!; runCurrent(); assertEquals(2, calls)
        val siblingGate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val sibling = backgroundScope.launch(parent) { withContext(NonCancellable) { entered.complete(Unit); siblingGate.await() } }; runCurrent(); entered.await()
        parent.cancel(); assertFalse(parent.isCompleted); runCurrent(); gate.complete(Unit); job.join()
        assertEquals(0, opens); siblingGate.complete(Unit); sibling.join()
    }
    @Test fun `stored rows beyond 2000 still renew an evicted receipt only on explicit Rate`() = runTest {
        var receipt = false; var deliveries = 0; var opened: TitleRatingEditor? = null
        val g = object : TitleRatingGateway {
            override fun isCurrent() = true
            override suspend fun read(target: DetailsTarget): TitleRatingReply {
                if (!receipt) throw ApiError.Http(HttpFailure(409, "Synthetic evicted receipt"))
                return TitleRatingReply("love")
            }
            override suspend fun write(target: DetailsTarget, rating: PersonalRating) = TitleRatingReply(rating.wire)
        }
        val model = ListGridModel(BrowseList.LovedStored, emptyMap(), backgroundScope, readPage = { _, limit, offset, _, _, _ ->
            deliveries++
            if (limit == 1) { receipt = true; page(rank = offset + 1, total = 2001) }
            else BrowsePage((1..2001).map { i -> BrowseEntry.TitleEntry(Title("tmdb:movie:${i + 1}", "Saved $i", mediaType = "movie", personalRating = "love"), StoredLoveRow(i, "2000-01-01T00:00:00Z")) }, 2001, originalLanguageFilterSupported = true)
        }, clientIdentity = { "a" }, ratingGateway = { g }, openRating = { opened = it }, pageSize = 2001)
        model.reload(); assertEquals(2001, model.total); assertEquals(2001, model.cards.size)
        assertEquals(1, deliveries); assertFalse(receipt)
        // No local age/rank/capability grants permission; explicit row delivery precedes the authority read.
        rate(model, model.cards.first()); assertEquals(2, deliveries); assertTrue(receipt); assertNotNull(opened)
    }
    @Test fun `new options during masked gateway read retire editor publication`() = runTest {
        val g = Gateway(); val gate = CompletableDeferred<Unit>(); g.hook = { withContext(NonCancellable) { gate.await() } }
        var opened = 0
        val model = ListGridModel(BrowseList.LovedStored, emptyMap(), backgroundScope, readPage = { _, _, _, _, _, _ -> page() }, clientIdentity = { "a" }, ratingGateway = { g }, openRating = { opened++ })
        model.reload(); val old = async { runCatching { rate(model) }.exceptionOrNull() }; runCurrent()
        assertEquals(1, g.reads); model.options = BrowseOptions(includeUnknownOriginalLanguage = true); gate.complete(Unit)
        assertTrue(old.await() is CancellationException); assertEquals(0, opened); assertEquals(0, g.writes)
    }
    @Test fun `accepted menu dismissal keeps exact renewed rating action alive`() = runTest {
        val parent = Job(); val g = Gateway(); var opened: TitleRatingEditor? = null
        val model = ListGridModel(BrowseList.LovedStored, emptyMap(), backgroundScope, readPage = { _, _, _, _, _, _ -> page() }, clientIdentity = { "a" }, ratingGateway = { g }, openRating = { opened = it })
        model.reload(); val card = model.cards.single(); val selection = TitleMenuSelection(card, model.dataContext, parent)
        val action = TitleListAction(selection.origin, backgroundScope) { card.reloadStoredRating!!(card.target!!, it) }
        action.commit(action.accept()!!) { selection.close() }!!.join()
        assertFalse(selection.isCurrent); assertNotNull(opened); opened!!.load(); assertEquals(1, g.reads); parent.cancel()
    }

    @Test fun `opened editor cannot write after original options or row generation changes`() = runTest {
        for (reload in listOf(false, true)) {
            val g = Gateway(); var editor: TitleRatingEditor? = null
            val model = ListGridModel(BrowseList.LovedStored, emptyMap(), backgroundScope, readPage = { _, _, _, _, _, _ -> page() }, clientIdentity = { "a" }, ratingGateway = { g }, openRating = { editor = it })
            model.reload(); rate(model); editor!!.load()
            if (reload) model.reload() else model.options = BrowseOptions(originalLanguages = setOf("fra"))
            assertFalse(editor!!.isCurrent())
            assertTrue(runCatching { editor!!.save(PersonalRating.LIKE) }.exceptionOrNull() is CancellationException)
            assertEquals(0, g.writes)
        }
    }

}
