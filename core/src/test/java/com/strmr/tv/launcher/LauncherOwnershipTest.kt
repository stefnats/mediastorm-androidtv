package com.strmr.tv.launcher

import com.strmr.tv.api.ContinueWatchingItem
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/** Actual coordinator consumed by LauncherSync; provider IO is a synthetic suspendable port. */
@OptIn(ExperimentalCoroutinesApi::class)
class LauncherOwnershipTest {
    private fun snapshot(id: String = "tmdb:movie:7") = LauncherSnapshot.capture(ProfileDataContext.current, listOf(ContinueWatchingItem(id, id)), false)
    private fun payload(input: LauncherSnapshot) = input.payload(input.prefetched.orEmpty(), emptyList())
    private class Provider : LauncherProviderPort {
        val events = mutableListOf<String>(); var value: LauncherPayload? = null
        var writeHook: suspend (LauncherPayload) -> Unit = {}
        var clearHook: suspend () -> Unit = {}
        override suspend fun write(payload: LauncherPayload) { events += "write:${payload.continueWatching.firstOrNull()?.id}"; writeHook(payload); value = payload }
        override suspend fun clear() { events += "clear"; clearHook(); value = null }
    }
    @Test fun `fresh request debounces once and loads captured input before provider write`() = runTest {
        val provider = Provider(); var reads = 0
        val actor = LauncherOwnership(backgroundScope, StandardTestDispatcher(testScheduler), provider, { reads++; payload(it) }, debounceMs = 100)
        val request = actor.request(snapshot())!!; runCurrent(); assertEquals(0, reads)
        advanceTimeBy(100); runCurrent(); request.join(); assertEquals(1, reads); assertNotNull(provider.value)
    }
    @Test fun `queued original epoch A B A starts no read or provider effect`() = runTest {
        val provider = Provider(); var reads = 0
        val actor = LauncherOwnership(backgroundScope, StandardTestDispatcher(testScheduler), provider, { reads++; payload(it) }, debounceMs = 100)
        actor.request(snapshot()); ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); advanceTimeBy(100); runCurrent()
        assertEquals(0, reads); assertTrue(provider.events.isEmpty())
    }
    @Test fun `late masked old read cannot write after current replacement`() = runTest {
        val provider = Provider(); val gate = CompletableDeferred<Unit>(); var calls = 0
        val actor = LauncherOwnership(backgroundScope, StandardTestDispatcher(testScheduler), provider, { input -> if (calls++ == 0) withContext(NonCancellable) { gate.await() }; payload(input) }, debounceMs = 0)
        actor.request(snapshot("tmdb:movie:1")); runCurrent(); ProfileDataContext.invalidate()
        actor.request(snapshot("tmdb:movie:2")); runCurrent(); gate.complete(Unit); runCurrent()
        assertEquals(listOf("write:tmdb:movie:2"), provider.events)
    }
    @Test fun `old write finishes and cleans its resource before new owner provider cycle`() = runTest {
        val provider = Provider(); val gate = CompletableDeferred<Unit>(); var first = true
        provider.writeHook = { if (first) { first = false; gate.await(); provider.events += "old-finished" } }
        val actor = LauncherOwnership(backgroundScope, StandardTestDispatcher(testScheduler), provider, { payload(it) }, debounceMs = 0)
        actor.request(snapshot("tmdb:movie:1")); runCurrent(); actor.clear(); ProfileDataContext.invalidate()
        actor.request(snapshot("tmdb:movie:2")); runCurrent(); assertEquals(listOf("write:tmdb:movie:1"), provider.events)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf("write:tmdb:movie:1", "old-finished", "clear", "write:tmdb:movie:2"), provider.events)
        assertEquals("tmdb:movie:2", provider.value!!.continueWatching.single().id)
    }
    @Test fun `blocked clear serializes before a replacement write`() = runTest {
        val provider = Provider(); val gate = CompletableDeferred<Unit>()
        provider.clearHook = { gate.await() }
        val actor = LauncherOwnership(backgroundScope, StandardTestDispatcher(testScheduler), provider, { payload(it) }, debounceMs = 0)
        actor.clear(); runCurrent(); actor.request(snapshot()); runCurrent(); assertEquals(listOf("clear"), provider.events)
        gate.complete(Unit); runCurrent(); assertEquals(listOf("clear", "write:tmdb:movie:7"), provider.events)
    }
    private class Reordered : CoroutineDispatcher() {
        val queued = mutableListOf<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued += block }
        fun last() { queued.removeAt(queued.lastIndex).run() }
    }
    @Test fun `old queued initial clear cannot delete an already published replacement`() = runTest {
        val dispatcher = Reordered(); val parent = Job(); val scope = CoroutineScope(parent + dispatcher); val provider = Provider()
        val actor = LauncherOwnership(scope, Dispatchers.Unconfined, provider, { payload(it) }, debounceMs = 0)
        val oldClear = actor.clear(); val write = actor.request(snapshot())!!
        var limit = 20
        while (!write.isCompleted && limit-- > 0) dispatcher.last()
        assertTrue(write.isCompleted); assertNotNull(provider.value)
        while (dispatcher.queued.isNotEmpty()) dispatcher.last()
        assertTrue(oldClear.isCompleted); assertEquals(listOf("write:tmdb:movie:7"), provider.events); parent.cancel()
    }
    @Test fun `failed provider write clears its partial resource and never caches success`() = runTest {
        val provider = Provider(); provider.writeHook = { throw java.io.IOException("private diagnostic") }
        val outcomes = mutableListOf<LauncherOutcome>()
        val actor = LauncherOwnership(backgroundScope, StandardTestDispatcher(testScheduler), provider, { payload(it) }, outcomes::add, debounceMs = 0)
        actor.request(snapshot()); runCurrent(); assertEquals(listOf("write:tmdb:movie:7", "clear"), provider.events)
        assertFalse(outcomes.contains(LauncherOutcome.WRITTEN)); assertTrue(outcomes.contains(LauncherOutcome.FAILED))
        provider.writeHook = {}; actor.request(snapshot()); runCurrent(); assertNotNull(provider.value)
    }
    @Test fun `delayed stale observer leaves a current replacement request alive`() = runTest {
        val provider = Provider(); var reads = 0
        val actor = LauncherOwnership(backgroundScope, StandardTestDispatcher(testScheduler), provider, { reads++; payload(it) }, debounceMs = 100)
        actor.request(snapshot()); ProfileDataContext.invalidate(); actor.request(snapshot("tmdb:movie:2")); actor.retireStale()
        advanceTimeBy(100); runCurrent(); assertEquals(1, reads); assertEquals("tmdb:movie:2", provider.value!!.continueWatching.single().id)
    }
    @Test fun `clear after local request retirement remains executable`() = runTest {
        val provider = Provider()
        val actor = LauncherOwnership(backgroundScope, StandardTestDispatcher(testScheduler), provider, { payload(it) }, debounceMs = 0)
        actor.request(snapshot()); runCurrent(); ProfileDataContext.invalidate(); actor.retireStale(); runCurrent()
        assertEquals(listOf("write:tmdb:movie:7", "clear"), provider.events); assertNull(provider.value)
    }
    @Test fun `same owner equal payload avoids redundant write but normal clear remains available`() = runTest {
        val provider = Provider(); val input = snapshot()
        // Stable time keeps the payload's fallback engagement unchanged for this exact snapshot.
        val stable = payload(input)
        val actor = LauncherOwnership(backgroundScope, StandardTestDispatcher(testScheduler), provider, { stable }, debounceMs = 0)
        actor.request(input); runCurrent(); actor.request(input); runCurrent(); assertEquals(1, provider.events.size)
        actor.clear(); runCurrent(); assertEquals(listOf("write:tmdb:movie:7", "clear"), provider.events)
    }
    @Test fun `failed partial write is cleaned and not cached as a successful payload`() = runTest {
        val provider = Provider(); var first = true
        provider.writeHook = { if (first) { first = false; throw java.io.IOException("private provider diagnostic") } }
        val stable = payload(snapshot())
        val actor = LauncherOwnership(backgroundScope, StandardTestDispatcher(testScheduler), provider, { stable }, debounceMs = 0)
        actor.request(snapshot()); runCurrent(); assertNull(provider.value)
        actor.request(snapshot()); runCurrent()
        assertEquals(listOf("write:tmdb:movie:7", "clear", "write:tmdb:movie:7"), provider.events)
        assertNotNull(provider.value)
    }
    @Test fun `known terminal read clears only assigned rows without automatic retry or write`() = runTest {
        val provider = Provider(); var reads = 0
        val actor = LauncherOwnership(backgroundScope, StandardTestDispatcher(testScheduler), provider, {
            if (reads++ > 0) throw com.strmr.tv.api.ApiError.Http(com.strmr.tv.api.HttpFailure(403, "denied"))
            payload(it)
        }, debounceMs = 0)
        actor.request(snapshot()); runCurrent(); actor.request(snapshot()); runCurrent()
        assertEquals(2, reads); assertEquals(listOf("write:tmdb:movie:7", "clear"), provider.events); assertNull(provider.value)
    }
    @Test fun `parent cancellation while writing does not confirm success and still retires its own rows`() = runTest {
        val parent = SupervisorJob(); val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)
        val provider = Provider(); val gate = CompletableDeferred<Unit>(); val outcomes = mutableListOf<LauncherOutcome>()
        provider.writeHook = { gate.await() }
        val actor = LauncherOwnership(scope, StandardTestDispatcher(testScheduler), provider, { payload(it) }, outcomes::add, debounceMs = 0)
        val job = actor.request(snapshot())!!; runCurrent(); parent.cancel(); gate.complete(Unit); runCurrent(); job.join()
        assertEquals(listOf("write:tmdb:movie:7", "clear"), provider.events); assertFalse(outcomes.contains(LauncherOutcome.WRITTEN)); assertNull(provider.value)
    }

    @Test fun `late clear queued before replacement cannot erase already written B resource`() = runTest {
        val dispatcher = Reordered(); val parent = Job(backgroundScope.coroutineContext[Job]); val provider = Provider()
        val actor = LauncherOwnership(CoroutineScope(dispatcher + parent), Dispatchers.Unconfined, provider, { payload(it) }, debounceMs = 0)
        actor.clear()
        val replacement = actor.request(snapshot("tmdb:movie:2"))!!
        repeat(10) { if (!replacement.isCompleted) dispatcher.queued.removeAt(dispatcher.queued.lastIndex).run() }
        assertTrue(replacement.isCompleted); assertNotNull(provider.value)
        while (dispatcher.queued.isNotEmpty()) dispatcher.queued.removeAt(dispatcher.queued.lastIndex).run()
        assertEquals(listOf("write:tmdb:movie:2"), provider.events)
        assertEquals("tmdb:movie:2", provider.value!!.continueWatching.single().id); parent.cancel()
    }

    @Test fun `actual stale reservation binds observed A even if B revision is read after replacement`() = runTest {
        val provider = Provider()
        val actor = LauncherOwnership(backgroundScope, StandardTestDispatcher(testScheduler), provider, { payload(it) }, debounceMs = 0)
        actor.request(snapshot("tmdb:movie:1")); runCurrent()
        ProfileDataContext.invalidate()
        val oldObserver = actor.reserveStaleClear()!!
        actor.request(snapshot("tmdb:movie:2")); runCurrent()
        assertEquals("tmdb:movie:2", provider.value!!.continueWatching.single().id)
        // This is the same reservation invoked by retireStale, delayed across the provider write.
        oldObserver(); runCurrent()
        assertEquals(listOf("write:tmdb:movie:1", "write:tmdb:movie:2"), provider.events)
        assertEquals("tmdb:movie:2", provider.value!!.continueWatching.single().id)
    }

}
