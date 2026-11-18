package com.strmr.tv.home

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Actual HomeStore with synthetic IO/platform ports; no Android preferences, profile activation or renderer. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeStoreContextTest {
    private class Access : HomeStoreAccess {
        val calls = mutableListOf<Pair<String, Long>>()
        var settingsReply: suspend () -> HomeSettings = { HomeSettings(listOf(ShelfConfig("watchlist", enabled = true))) }
        var revisionReply: suspend () -> String? = { "one" }
        var rowReply: suspend (PlannedShelf, HomeContext, ShelfPhase) -> HomeShelf? = { shelf, _, _ -> HomeShelf(shelf.id, "Synthetic", listOf(HomeCard.from(WatchlistItem("one"))), 20) }
        var continueReply: suspend (Long) -> List<ContinueWatchingItem> = { emptyList() }
        var hiddenReply: suspend () -> Unit = {}
        var asideReply: suspend () -> Unit = {}
        var publications = 0; var layouts = 0; var launches = 0
        val phases = mutableListOf<ShelfPhase>()
        override suspend fun settings(view: String, expected: Long): HomeSettings { calls += "settings" to expected; return settingsReply() }
        override suspend fun revision(expected: Long): String? { calls += "revision" to expected; return revisionReply() }
        override suspend fun hidden(expected: Long) { calls += "hidden" to expected; hiddenReply() }
        override suspend fun aside(expected: Long) { calls += "aside" to expected; asideReply() }
        override fun context(settings: HomeSettings, plan: List<PlannedShelf>, scope: CoroutineScope, expected: Long) =
            HomeContext(settings, plan, scope, expected, { continueReply(it) }, { _, _ -> emptyList() })
        override suspend fun row(shelf: PlannedShelf, context: HomeContext, phase: ShelfPhase): HomeShelf? {
            calls += "row" to context.dataContext; phases += phase; return rowReply(shelf, context, phase)
        }
        override fun profile(): Profile? = null
        override fun views(settings: HomeSettings) { publications++ }
        override fun layout(settings: HomeSettings, plan: List<PlannedShelf>) { layouts++ }
        override fun ordered(plan: List<PlannedShelf>) = plan
        override fun hiddenShelf(id: String) = false
        override fun layoutIdentity(): Any = Unit
        override fun log(warning: Boolean, message: String) { }
        override fun homeLoaded(items: List<ContinueWatchingItem>?, hideWatched: Boolean, expected: Long) { launches++ }
    }
    private suspend fun canceled(read: suspend () -> Unit) { try { read(); fail("Retired operation survived") } catch (_: CancellationException) { } }
    @Test fun `normal actual store publishes settings rows layout and guarded home handoff`() = runTest {
        val access = Access(); val expected = ProfileDataContext.current
        val store = HomeStore(backgroundScope, HomeViews.ALL, expected, access)
        store.load()
        assertNotNull(store.settings); assertEquals(1, store.slots.size); assertFalse(store.isLoading)
        assertEquals(1, access.publications); assertEquals(1, access.layouts); assertEquals(1, access.launches)
        runCurrent(); assertTrue(access.calls.all { it.second == expected })
    }
    @Test fun `queued old store cannot begin settings hidden aside or revision`() = runTest {
        val access = Access(); val store = HomeStore(backgroundScope, HomeViews.ALL, ProfileDataContext.current, access)
        val load = async { store.load() }
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        canceled { load.await() }; assertTrue(access.calls.isEmpty()); assertTrue(store.slots.isEmpty()); assertNull(store.settings)
    }
    @Test fun `late old settings cannot change global views badges layout or rows`() = runTest {
        val access = Access(); val reply = CompletableDeferred<HomeSettings>()
        access.settingsReply = { withContext(NonCancellable) { reply.await() } }
        val store = HomeStore(backgroundScope, HomeViews.ALL, ProfileDataContext.current, access)
        val load = async { store.load() }; runCurrent()
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); reply.complete(HomeSettings(emptyList()))
        canceled { load.await() }; assertEquals(0, access.publications); assertEquals(0, access.layouts); assertEquals(0, access.launches)
        assertTrue(store.slots.isEmpty()); assertNull(store.settings)
    }
    @Test fun `parent retirement cancels actual row child and forbids late publication`() = runTest {
        val parent = Job(); val scope = CoroutineScope(coroutineContext + parent); val access = Access()
        val entered = CompletableDeferred<Unit>(); val reply = CompletableDeferred<Unit>(); var cancelled = false
        access.rowReply = { shelf, _, _ ->
            entered.complete(Unit)
            try { reply.await(); HomeShelf(shelf.id, "Synthetic", emptyList(), 20) }
            finally { cancelled = true }
        }
        val store = HomeStore(scope, HomeViews.ALL, ProfileDataContext.current, access)
        val load = async { store.load() }; entered.await(); parent.cancel(); runCurrent()
        canceled { load.await() }; assertTrue(cancelled); assertTrue(store.slots.isEmpty()); assertEquals(0, access.launches)
    }
    @Test fun `calling effect cancellation cancels original store child even while store remains active`() = runTest {
        val access = Access(); val entered = CompletableDeferred<Unit>(); val hold = CompletableDeferred<Unit>(); var cancelled = false
        access.settingsReply = { entered.complete(Unit); try { hold.await(); HomeSettings(emptyList()) } finally { cancelled = true } }
        val store = HomeStore(backgroundScope, HomeViews.ALL, ProfileDataContext.current, access)
        val load = async { store.load() }; entered.await(); load.cancel(); runCurrent()
        canceled { load.await() }; assertTrue(cancelled); assertTrue(store.isCurrent); assertEquals(0, access.publications)
    }
    @Test fun `new load retires masked older load without old publication or cleanup of replacement`() = runTest {
        val access = Access(); val oldReply = CompletableDeferred<HomeSettings>(); var reads = 0
        access.settingsReply = { if (reads++ == 0) withContext(NonCancellable) { oldReply.await() } else HomeSettings(emptyList()) }
        val store = HomeStore(backgroundScope, HomeViews.ALL, ProfileDataContext.current, access)
        val old = async { store.load() }; runCurrent(); store.load()
        oldReply.complete(HomeSettings(listOf(ShelfConfig("watchlist", enabled = true))))
        canceled { old.await() }; assertTrue(store.slots.isEmpty()); assertEquals(1, access.publications); assertFalse(store.isLoading)
    }
    @Test fun `same profile epoch retirement clears observable settings and rows and new store can load`() = runTest {
        val access = Access(); val first = HomeStore(backgroundScope, HomeViews.ALL, ProfileDataContext.current, access)
        first.load(); assertNotNull(first.settings)
        ProfileDataContext.invalidate(); runCurrent()
        assertNull(first.settings); assertTrue(first.slots.isEmpty()); assertFalse(first.isCurrent)
        val replacement = HomeStore(backgroundScope, HomeViews.ALL, ProfileDataContext.current, access)
        replacement.load(); assertNotNull(replacement.settings); assertEquals(1, replacement.slots.size)
    }
    @Test fun `terminal settings denial ends load visibly without row fallback`() = runTest {
        val access = Access(); access.settingsReply = { throw ApiError.Http(HttpFailure(403, "{}")) }
        val store = HomeStore(backgroundScope, HomeViews.ALL, ProfileDataContext.current, access)
        store.load(); assertFalse(store.isLoading); assertNotNull(store.loadError); assertTrue(store.slots.isEmpty())
        assertFalse(access.calls.any { it.first == "row" }); assertEquals(0, access.publications)
    }
    @Test fun `legitimate complete pass remains alive after first rows return`() = runTest {
        val access = Access(); access.settingsReply = { HomeSettings(listOf(ShelfConfig("synthetic", enabled = true, type = "mdblist", listUrl = "synthetic-list"))) }
        val complete = CompletableDeferred<Unit>()
        access.rowReply = { shelf, _, phase ->
            if (phase == ShelfPhase.COMPLETE) complete.await()
            HomeShelf(shelf.id, "Synthetic", listOf(HomeCard.from(WatchlistItem(if (phase == ShelfPhase.CARDS) "first" else "complete"))), 20)
        }
        val store = HomeStore(backgroundScope, HomeViews.ALL, ProfileDataContext.current, access)
        store.load(); runCurrent(); assertEquals(listOf(ShelfPhase.CARDS, ShelfPhase.COMPLETE), access.phases)
        complete.complete(Unit); runCurrent()
        assertEquals("complete", (store.slots.single() as HomeSlot.Shelf).shelf.cards.single().target!!.titleId)
    }
    @Test fun `calendar retry is original-parent bound and cannot start after retirement`() = runTest {
        val access = Access(); access.settingsReply = { HomeSettings(listOf(ShelfConfig("calendar", enabled = true))) }
        access.rowReply = { _, _, _ -> throw HomeCalendarPending() }
        val store = HomeStore(backgroundScope, HomeViews.ALL, ProfileDataContext.current, access)
        store.load(); val before = access.phases.size
        ProfileDataContext.invalidate(); advanceTimeBy(10_001); runCurrent()
        assertEquals(before, access.phases.size); assertTrue(store.slots.isEmpty())
    }
    @Test fun `legitimate calendar retry survives first return and publishes under same epoch`() = runTest {
        val access = Access(); access.settingsReply = { HomeSettings(listOf(ShelfConfig("calendar", enabled = true))) }
        var attempts = 0
        access.rowReply = { shelf, _, _ ->
            if (attempts++ == 0) throw HomeCalendarPending()
            HomeShelf(shelf.id, "Synthetic", listOf(HomeCard.from(WatchlistItem("calendar-ready"))), 20)
        }
        val store = HomeStore(backgroundScope, HomeViews.ALL, ProfileDataContext.current, access)
        store.load(); assertEquals(1, attempts); assertTrue(store.slots.single() is HomeSlot.Loading)
        advanceTimeBy(10_001); runCurrent()
        assertEquals(2, attempts); assertEquals("calendar-ready", (store.slots.single() as HomeSlot.Shelf).shelf.cards.single().target!!.titleId)
        assertTrue(store.isCurrent)
    }
    @Test fun `strict history denial in playback-row reload ends visibly without escaping or launcher handoff`() = runTest {
        val access = Access(); access.settingsReply = { HomeSettings(listOf(ShelfConfig("continue-watching", enabled = true))) }
        val store = HomeStore(backgroundScope, HomeViews.ALL, ProfileDataContext.current, access)
        store.load(); assertEquals(1, access.launches)
        access.continueReply = { throw ApiError.Http(HttpFailure(403, "{}")) }
        store.reloadPlaybackRows()
        assertTrue(store.slots.isEmpty()); assertNotNull(store.loadError); assertEquals(1, access.launches)
    }

    @Test fun `known shared feed denial prevents starting COMPLETE stages`() = runTest {
        for (view in listOf(HomeViews.ALL, "movies")) {
            val access = Access()
            access.settingsReply = { HomeSettings(listOf(ShelfConfig("continue-watching", enabled = true),
                ShelfConfig("synthetic", enabled = true, type = "mdblist", listUrl = "synthetic-list")), view = view) }
            access.continueReply = { throw ApiError.Http(HttpFailure(403, "{}")) }
            val store = HomeStore(backgroundScope, view, ProfileDataContext.current, access)
            store.load(); runCurrent()
            assertFalse(access.phases.contains(ShelfPhase.COMPLETE)); assertEquals(0, access.launches)
            assertTrue(store.slots.isEmpty()); assertNotNull(store.loadError)
        }
    }

    @Test fun `replacement full generation cancels old cache-prefill children while owner stays current`() = runTest {
        val access = Access(); val blocked = CompletableDeferred<Unit>()
        var hiddenAttempts = 0; var asideAttempts = 0; var hiddenCanceled = false; var asideCanceled = false
        var oldFollowups = 0
        access.hiddenReply = { if (hiddenAttempts++ == 0) try { blocked.await(); oldFollowups++ } finally { hiddenCanceled = true } }
        access.asideReply = { if (asideAttempts++ == 0) try { blocked.await(); oldFollowups++ } finally { asideCanceled = true } }
        val store = HomeStore(backgroundScope, HomeViews.ALL, ProfileDataContext.current, access)
        store.load(); runCurrent(); assertFalse(hiddenCanceled); assertFalse(asideCanceled)
        store.load(); runCurrent()
        assertTrue(hiddenCanceled); assertTrue(asideCanceled)
        assertEquals(2, hiddenAttempts); assertEquals(2, asideAttempts); assertTrue(store.isCurrent)
        assertEquals(0, oldFollowups)
        blocked.complete(Unit); runCurrent(); assertEquals(0, oldFollowups)
    }

    @Test fun `partial terminal denial retires already accepted COMPLETE and no automatic retry revives it`() = runTest {
        val access = Access()
        access.settingsReply = { HomeSettings(listOf(ShelfConfig("continue-watching", enabled = true),
            ShelfConfig("synthetic", enabled = true, type = "mdblist", listUrl = "synthetic-list"))) }
        val release = CompletableDeferred<Unit>(); var completeStarted = false
        access.rowReply = { shelf, _, phase ->
            if (phase == ShelfPhase.COMPLETE && shelf.id == "synthetic") {
                completeStarted = true
                withContext(NonCancellable) { release.await() }
            }
            HomeShelf(shelf.id, "Synthetic", listOf(HomeCard.from(WatchlistItem("synthetic-card"))), 20)
        }
        val store = HomeStore(backgroundScope, HomeViews.ALL, ProfileDataContext.current, access)
        store.load(); runCurrent(); assertTrue(completeStarted)
        access.continueReply = { throw ApiError.Http(HttpFailure(403, "{}")) }
        store.reloadPlaybackRows(); assertTrue(store.slots.isEmpty()); assertNotNull(store.loadError)
        val callsAfterDenial = access.calls.size
        release.complete(Unit); runCurrent(); assertTrue(store.slots.isEmpty())
        store.reloadPlaybackRows(); store.reloadWatchlistRow(); store.refreshIfStale(); runCurrent()
        assertEquals(callsAfterDenial, access.calls.size)
        access.continueReply = { emptyList() }
        store.load(); runCurrent(); assertTrue(store.slots.any { it is HomeSlot.Shelf })
    }

}
