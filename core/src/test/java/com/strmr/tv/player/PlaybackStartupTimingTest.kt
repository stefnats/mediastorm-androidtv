package com.strmr.tv.player

import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.PrequeueStatus
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Actual production recorder and player/input adapters; no Android render callback is executed. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStartupTimingTest {
    private var nanos = 1_000_000_000L
    private fun advance(millis: Long) { nanos += millis * 1_000_000 }
    private fun recorder(capacity: Int = 128, timeout: Long = 120_000, scope: CoroutineScope? = null) =
        PlaybackStartupRecorder({ nanos }, scope, capacity, timeout)
    private fun session(owner: PlaybackSessionContext) = PlayerSession(
        PlaybackRequest(DetailsTarget("tmdb:movie:42", "Synthetic private title", true)),
        "https://example.invalid/synthetic?token=synthetic-private", null, ownership = owner,
    )
    @Test fun `actual first frame adapter includes resolve and player wait from original entry timestamp`() = runTest {
        val store = recorder(); val owner = PlaybackSessionContext.capture(); val entered = nanos
        advance(25); val attempt = store.begin(owner, startedNanos = entered); val reply = CompletableDeferred<Unit>()
        val preparing = async { reply.await(); attempt.attach(session(owner)) }; advance(120); reply.complete(Unit)
        val player = PlaybackStartupPlayer(preparing.await()) { true }; assertTrue(store.snapshot().isEmpty())
        advance(55); assertTrue(player.firstFrame()); assertEquals(200, store.snapshot().single().elapsedMillis)
        assertEquals(StartupOutcome.RENDERED, store.snapshot().single().outcome)
    }
    @Test fun `ready or playing without first frame does not finish a sample`() {
        val store = recorder(); val owner = PlaybackSessionContext.capture(); val attempt = store.begin(owner)
        PlaybackStartupPlayer(attempt.attach(session(owner))) { true }; advance(1000)
        assertTrue(store.snapshot().isEmpty())
    }
    @Test fun `repeated frame failure and release callbacks complete exactly once`() {
        val store = recorder(); val owner = PlaybackSessionContext.capture(); val player = PlaybackStartupPlayer(store.begin(owner).attach(session(owner))) { true }
        assertTrue(player.firstFrame()); assertFalse(player.firstFrame()); assertFalse(player.end(StartupOutcome.FAILED)); assertFalse(player.end(StartupOutcome.RELEASED))
        assertEquals(1, store.snapshot().size); assertEquals(StartupOutcome.RENDERED, store.snapshot().single().outcome)
    }
    @Test fun `terminal failure before frame cannot later become a successful startup`() {
        val store = recorder(); val owner = PlaybackSessionContext.capture(); val player = PlaybackStartupPlayer(store.begin(owner).attach(session(owner))) { true }
        assertTrue(player.end(StartupOutcome.FAILED)); assertFalse(player.firstFrame()); assertEquals(StartupOutcome.FAILED, store.snapshot().single().outcome)
    }
    @Test fun `release before frame is recorded separately from a failure`() {
        val store = recorder(); val owner = PlaybackSessionContext.capture(); val player = PlaybackStartupPlayer(store.begin(owner).attach(session(owner))) { true }
        advance(75); assertTrue(player.end(StartupOutcome.RELEASED)); assertFalse(player.firstFrame())
        assertEquals(StartupSample(StartupOutcome.RELEASED, 75, StartupInput.UNKNOWN), store.snapshot().single())
    }
    @Test fun `canceled prepare cannot later complete through a delayed pending session`() = runTest {
        val store = recorder(); val owner = PlaybackSessionContext.capture(); val attempt = store.begin(owner); val reply = CompletableDeferred<Unit>()
        val preparing = async { withContext(NonCancellable) { reply.await() }; attempt.attach(session(owner)) }
        runCurrent(); attempt.finish(StartupOutcome.CANCELLED); reply.complete(Unit)
        val player = PlaybackStartupPlayer(preparing.await()) { true }; assertFalse(player.firstFrame())
        assertEquals(listOf(StartupOutcome.CANCELLED), store.snapshot().map { it.outcome })
    }
    @Test fun `new attempt cancels old one and late old controller frame cannot complete replacement`() {
        val store = recorder(); val owner = PlaybackSessionContext.capture(); val old = PlaybackStartupPlayer(store.begin(owner).attach(session(owner))) { true }
        advance(10); val next = PlaybackStartupPlayer(store.begin(owner).attach(session(owner))) { true }
        assertFalse(old.firstFrame()); assertEquals(listOf(StartupOutcome.CANCELLED), store.snapshot().map { it.outcome })
        advance(20); assertTrue(next.firstFrame()); assertEquals(listOf(StartupOutcome.CANCELLED, StartupOutcome.RENDERED), store.snapshot().map { it.outcome }); assertEquals(20, store.snapshot().last().elapsedMillis)
    }
    @Test fun `one original attempt can be claimed by only one player adapter`() {
        val store = recorder(); val owner = PlaybackSessionContext.capture(); val ready = store.begin(owner).attach(session(owner))
        val first = PlaybackStartupPlayer(ready) { true }; val duplicate = PlaybackStartupPlayer(ready.copy()) { true }
        assertFalse(duplicate.firstFrame()); assertTrue(first.firstFrame()); assertEquals(1, store.snapshot().size)
    }
    @Test fun `attempt cannot be rebound onto a copied session under another owner object`() {
        val store = recorder(); val owner = PlaybackSessionContext.capture(); val attempt = store.begin(owner); val original = attempt.attach(session(owner)); val other = PlaybackSessionContext.capture()
        val copied = original.copy(ownership = other); assertFalse(PlaybackStartupPlayer(copied) { true }.firstFrame())
        assertTrue(PlaybackStartupPlayer(original) { true }.firstFrame()); assertEquals(1, store.snapshot().size)
    }
    @Test fun `attempt does not attach to a foreign session identity even at matching current epoch`() {
        val store = recorder(); val owner = PlaybackSessionContext.capture(); val attempt = store.begin(owner)
        assertNull(attempt.attach(session(PlaybackSessionContext.capture())).startupTiming)
        attempt.finish(StartupOutcome.CANCELLED)
    }
    @Test fun `owner loss overrides late rendered frame after simulated A B A`() {
        val store = recorder(); val owner = PlaybackSessionContext.capture(); val player = PlaybackStartupPlayer(store.begin(owner).attach(session(owner))) { owner.isCurrent() }
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); player.firstFrame()
        assertEquals(StartupOutcome.OWNER_LOST, store.snapshot().single().outcome)
    }
    @Test fun `stale owner cannot start a live timing attempt or claim a player`() {
        val store = recorder(); val owner = PlaybackSessionContext.capture(); ProfileDataContext.invalidate()
        val attempt = store.begin(owner); assertFalse(PlaybackStartupPlayer(attempt.attach(session(owner))) { true }.firstFrame())
        assertEquals(StartupOutcome.OWNER_LOST, store.snapshot().single().outcome)
    }
    @Test fun `missing optional attempt on legacy session produces no invented startup sample`() {
        val store = recorder(); val player = PlaybackStartupPlayer(session(PlaybackSessionContext.capture())) { true }
        assertFalse(player.firstFrame()); assertFalse(player.end(StartupOutcome.RELEASED)); assertTrue(store.snapshot().isEmpty())
    }
    @Test fun `deadline is checked at frame even if timer callback was delayed`() {
        val store = recorder(timeout = 100); val owner = PlaybackSessionContext.capture(); val attempt = store.begin(owner); val player = PlaybackStartupPlayer(attempt.attach(session(owner))) { true }
        advance(101); player.firstFrame(); assertEquals(StartupOutcome.TIMEOUT, store.snapshot().single().outcome); assertFalse(store.expire(attempt))
    }
    @Test fun `actual optional scheduler expires one attempt without any player callback`() = runTest {
        val store = recorder(timeout = 100, scope = backgroundScope); val owner = PlaybackSessionContext.capture(); store.begin(owner)
        runCurrent(); advance(100); advanceTimeBy(100); runCurrent()
        assertEquals(StartupOutcome.TIMEOUT, store.snapshot().single().outcome)
    }
    @Test fun `successful frame cancels timeout job and cannot emit later timeout`() = runTest {
        val store = recorder(timeout = 100, scope = backgroundScope); val owner = PlaybackSessionContext.capture(); val attempt = store.begin(owner)
        val player = PlaybackStartupPlayer(attempt.attach(session(owner))) { true }; runCurrent(); advance(5); assertTrue(player.firstFrame()); assertNull(attempt.timeout)
        advance(1000); advanceTimeBy(1000); runCurrent(); assertEquals(listOf(StartupOutcome.RENDERED), store.snapshot().map { it.outcome })
    }
    @Test fun `retained anonymous sample history stays bounded and snapshots cannot mutate recorder`() {
        val store = recorder(capacity = 2); val owner = PlaybackSessionContext.capture()
        repeat(4) { val attempt = store.begin(owner); advance(1); attempt.finish(if (it == 3) StartupOutcome.RELEASED else StartupOutcome.FAILED) }
        val samples = store.snapshot(); assertEquals(2, samples.size); (samples as MutableList).clear(); assertEquals(2, store.snapshot().size)
        assertEquals(setOf("outcome", "elapsedMillis", "input"), StartupSample::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet())
        assertFalse(store.snapshot().toString().contains("private")); assertFalse(store.snapshot().toString().contains("http"))
    }
    @Test fun `input labels reflect supplied preparation facts instead of inferred warm cold speed`() {
        assertEquals(StartupInput.UNKNOWN, startupInput(null, false))
        assertEquals(StartupInput.RESOLVED_INPUT_SUPPLIED, startupInput(null, true))
        assertEquals(StartupInput.UNKNOWN, startupInput(PrequeueStatus("synthetic", "pending"), false))
        assertEquals(StartupInput.UNKNOWN, startupInput(PrequeueStatus("synthetic", "ready"), false))
        assertEquals(StartupInput.READY_PREPARATION_SUPPLIED, startupInput(PrequeueStatus("synthetic", "ready", streamPath = "synthetic"), false))
    }
    @Test fun `cast handoff ends local observation without claiming receiver rendered a frame`() {
        val store = recorder(); val owner = PlaybackSessionContext.capture(); val attempt = store.begin(owner)
        attempt.finish(StartupOutcome.EXTERNAL_HANDOFF); assertFalse(PlaybackStartupPlayer(attempt.attach(session(owner))) { true }.firstFrame())
        assertEquals(StartupOutcome.EXTERNAL_HANDOFF, store.snapshot().single().outcome)
    }
    @Test fun `early scheduler wakeup reschedules remaining monotonic deadline`() = runTest {
        val store = recorder(timeout = 100, scope = backgroundScope); store.begin(PlaybackSessionContext.capture())
        runCurrent(); advance(99); advanceTimeBy(100); runCurrent(); assertTrue(store.snapshot().isEmpty())
        advance(1); advanceTimeBy(1); runCurrent(); assertEquals(StartupOutcome.TIMEOUT, store.snapshot().single().outcome)
    }

}
