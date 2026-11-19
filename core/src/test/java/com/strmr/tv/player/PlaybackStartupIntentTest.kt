package com.strmr.tv.player

import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStartupIntentTest {
    private val target = DetailsTarget("tmdb:movie:42", "Synthetic title", true)
    private var nanos = 1_000_000L
    private fun advance(ms: Long) { nanos += ms * 1_000_000 }
    private fun store(capacity: Int = 128) = PlaybackStartupRecorder({ nanos }, capacity = capacity)
    private fun PlaybackStartupRecorder.click(entry: StartupEntry = StartupEntry.DETAILS_PLAY) = intent(PlaybackSessionContext.capture(), target, entry)
    private fun PlaybackStartupRecorder.adopt(value: PlaybackStartupIntent) = requireNotNull(adopt(value, target, value.owner.epoch, StartupInput.UNKNOWN))
    private fun session(value: PlaybackStartupAttempt) = value.attach(PlayerSession(PlaybackRequest(target), "synthetic", null, ownership = value.owner))

    @Test fun `original click includes preparation metadata library and player intervals`() {
        val store = store(); val click = store.click(); val original = click.owner
        advance(75); assertTrue(click.bind(target, original.epoch) { true })
        advance(8000); val adopted = store.adopt(click)
        assertSame(original, adopted.owner); advance(25)
        assertTrue(PlaybackStartupPlayer(session(adopted)) { true }.firstFrame())
        assertEquals(8100L, store.snapshot().single().elapsedMillis)
        assertEquals(StartupOrigin.USER_ACTION, store.records().single().origin)
    }
    @Test fun `pending click does not replace unrelated active player measurement`() {
        val store = store(); val active = store.begin(PlaybackSessionContext.capture()); val player = PlaybackStartupPlayer(session(active)) { true }
        val click = store.click(); assertEquals(2, store.inFlight()); click.cancel()
        assertTrue(player.firstFrame()); assertEquals(listOf(StartupOutcome.CANCELLED, StartupOutcome.RENDERED), store.snapshot().map { it.outcome })
    }
    @Test fun `replacement retires only previous pending receipt`() {
        val store = store(); val first = store.click(); val next = store.click(StartupEntry.MANUAL_SOURCE)
        assertEquals(StartupOutcome.SUPERSEDED, store.snapshot().single().outcome)
        assertNull(store.adopt(first, target, first.owner.epoch, StartupInput.UNKNOWN))
        store.adopt(next).finish(StartupOutcome.RENDERED)
        assertEquals(StartupEntry.MANUAL_SOURCE, store.records().last().entry)
    }
    @Test fun `expected page disposal after adoption does not cancel player`() {
        val store = store(); val click = store.click(); val producer = Job()
        assertTrue(click.bind(target, click.owner.epoch, producer) { true })
        val player = PlaybackStartupPlayer(session(store.adopt(click))) { true }
        producer.cancel(); click.cancel(); assertTrue(store.snapshot().isEmpty())
        assertTrue(player.firstFrame()); assertEquals(StartupOutcome.RENDERED, store.snapshot().single().outcome)
    }
    @Test fun `original pending producer cancellation is recorded before coordinator`() {
        val store = store(); val click = store.click(); val producer = Job()
        click.bind(target, click.owner.epoch, producer) { true }; advance(12); producer.cancel()
        assertEquals(StartupOutcome.CANCELLED, store.snapshot().single().outcome)
        assertEquals(12L, store.snapshot().single().elapsedMillis)
    }
    @Test fun `original bound page predicate is checked again at adoption`() {
        val store = store(); var current = true; val click = store.click()
        click.bind(target, click.owner.epoch) { current }; current = false
        assertNull(store.adopt(click, target, click.owner.epoch, StartupInput.UNKNOWN))
        assertEquals(StartupOutcome.CANCELLED, store.snapshot().single().outcome)
    }
    @Test fun `equal valued replacement target cannot receive original receipt`() {
        val store = store(); val click = store.click()
        assertNull(store.adopt(click, target.copy(), click.owner.epoch, StartupInput.UNKNOWN))
        assertEquals(StartupOutcome.CANCELLED, store.snapshot().single().outcome)
    }
    @Test fun `original same profile ABA cannot be rebound to current owner`() {
        val store = store(); val click = store.click(); ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        assertNull(store.adopt(click, target, ProfileDataContext.current, StartupInput.UNKNOWN))
        assertEquals(StartupOutcome.OWNER_LOST, store.snapshot().single().outcome)
    }
    @Test fun `binding is one shot and cannot replace original guard`() {
        val store = store(); val click = store.click(); click.bind(target, click.owner.epoch) { false }
        assertFalse(click.bind(target, click.owner.epoch) { true })
        assertNull(store.adopt(click, target, click.owner.epoch, StartupInput.UNKNOWN))
    }
    @Test fun `binding wrong target retires receipt without granting authority`() {
        val store = store(); val click = store.click()
        assertFalse(click.bind(target.copy(), click.owner.epoch) { true })
        assertEquals(StartupOutcome.CANCELLED, store.snapshot().single().outcome)
    }
    @Test fun `pending deadline uses original timestamp when scheduler is delayed`() {
        val store = store(); val click = store.click(); advance(120_000)
        assertNull(store.adopt(click, target, click.owner.epoch, StartupInput.UNKNOWN))
        assertEquals(StartupOutcome.TIMEOUT, store.snapshot().single().outcome)
    }
    @Test fun `actual pending timer expires without any coordinator or player callback`() = runTest {
        val store = PlaybackStartupRecorder({ nanos }, backgroundScope, timeoutMillis = 100)
        val click = store.click(); runCurrent(); advance(100); advanceTimeBy(100); runCurrent()
        assertEquals(StartupOutcome.TIMEOUT, store.snapshot().single().outcome)
        assertFalse(store.expirePending(click)); assertEquals(0, store.inFlight())
    }
    @Test fun `adopted deadline includes all time before coordinator`() {
        val store = store(); val click = store.click(); advance(119_950); val attempt = store.adopt(click)
        advance(50); PlaybackStartupPlayer(session(attempt)) { true }.firstFrame()
        assertEquals(StartupOutcome.TIMEOUT, store.snapshot().single().outcome)
    }
    @Test fun `failure not started and legacy choice are explicit completed outcomes`() {
        for (outcome in listOf(StartupOutcome.FAILED, StartupOutcome.NOT_STARTED, StartupOutcome.CHOICE_REQUIRED)) {
            val store = store(); val click = store.click(); advance(7); click.finish(outcome); click.cancel()
            assertEquals(outcome, store.snapshot().single().outcome); assertEquals(7L, store.snapshot().single().elapsedMillis)
        }
    }
    @Test fun `input is adopted observation not proof of cache and callback stays one shot`() {
        val store = store(); val click = store.click()
        val attempt = requireNotNull(store.adopt(click, target, click.owner.epoch, StartupInput.READY_PREPARATION_SUPPLIED))
        val player = PlaybackStartupPlayer(session(attempt)) { true }
        assertTrue(player.firstFrame()); assertFalse(player.firstFrame()); assertFalse(player.end(StartupOutcome.FAILED))
        assertEquals(StartupInput.READY_PREPARATION_SUPPLIED, store.snapshot().single().input)
    }
    @Test fun `coordinator fallback remains separately identified and ring stays bounded`() {
        val store = store(2); store.click().cancel(); store.click().failed(); store.begin(PlaybackSessionContext.capture()).finish(StartupOutcome.RENDERED)
        assertEquals(2, store.snapshot().size); assertEquals(StartupOrigin.COORDINATOR_ONLY, store.records().last().origin)
        assertEquals(StartupEntry.PROGRAMMATIC, store.records().last().entry)
    }
}
