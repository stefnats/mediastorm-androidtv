package com.strmr.tv.home

import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.SourceAvailability
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.player.*
import java.time.Instant
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class HeroStartupTimingTest {
    private val now = Instant.parse("2026-11-19T18:00:00Z")
    private fun missing() = SourceAvailability("not_found", now.minusSeconds(60).toString(), now.plusSeconds(60).toString(), complete = true, reason = "reported_candidates")
    private inner class Fixture {
        var nanos = 0L
        val recorder = PlaybackStartupRecorder({ nanos })
        val target = DetailsTarget("tmdb:movie:42", "Synthetic", true)
        val card = HomeCard("synthetic", HomeLink.Details(target), "Synthetic", "")
        val producer = Job()
        var selected = true
        var source: SourceAvailability? = null
        var prepares = 0
        var opens = 0
        var receipt: PlaybackStartupIntent? = null
        var duringPrepare: () -> Unit = {}
        val actions = HeroActions(card, ProfileDataContext.current, producer, { selected }, { card }, { source }, true,
            prepare = { prepares++; assertEquals(1, recorder.inFlight()); nanos += 50_000_000; duringPrepare() },
            discard = { _, _ -> }, open = { _, _, _ -> opens++ }, now = { now },
            openPlayback = { value, intent -> assertSame(target, value); selected = false; receipt = intent },
            startPlayback = { owner, value -> recorder.intent(owner, value, StartupEntry.HERO_PRIMARY) })
    }
    @Test fun `production hero captures before prewarm and transfers original intent despite expected Home coverage`() {
        val f = Fixture(); assertTrue(f.actions.primary()); val receipt = requireNotNull(f.receipt)
        assertFalse(f.selected); assertEquals(1, f.prepares); assertEquals(0, f.opens)
        assertTrue(receipt.bind(f.target, receipt.owner.epoch) { true })
        f.nanos += 70_000_000
        val attempt = requireNotNull(f.recorder.adopt(receipt, f.target, receipt.owner.epoch, StartupInput.UNKNOWN))
        assertSame(receipt.owner, attempt.owner); attempt.finish(StartupOutcome.RENDERED)
        assertEquals(120L, f.recorder.snapshot().single().elapsedMillis)
    }
    @Test fun `confirmed missing and presented missing primary create no playback intent`() {
        val actual = Fixture(); actual.source = missing(); assertTrue(actual.actions.primary())
        assertNull(actual.receipt); assertEquals(0, actual.prepares); assertEquals(0, actual.recorder.inFlight())
        val presented = Fixture(); assertTrue(presented.actions.primary(presentedMissing = true))
        assertNull(presented.receipt); assertEquals(0, presented.recorder.inFlight())
    }
    @Test fun `Info and mobile style Details actions never create a play receipt`() {
        for (tv in listOf(true, false)) {
            val f = Fixture(); var prepares = 0
            val details = HeroActions(f.card, ProfileDataContext.current, f.producer, { true }, { f.card }, { null }, tv,
                prepare = { prepares++ }, discard = { _, _ -> }, open = { _, autoplay, _ -> assertFalse(autoplay) },
                openPlayback = { _, _ -> fail() }, startPlayback = { _, _ -> error("Details must not create a receipt") })
            assertTrue(details.details()); assertEquals(if (tv) 1 else 0, prepares)
            assertTrue(f.recorder.snapshot().isEmpty())
        }
    }
    @Test fun `instrumentation capture failure preserves ordinary accepted hero playback`() {
        val f = Fixture(); var opened = false
        val actions = HeroActions(f.card, ProfileDataContext.current, f.producer, { true }, { f.card }, { null }, false,
            prepare = { }, discard = { _, _ -> }, open = { _, autoplay, _ -> opened = autoplay },
            openPlayback = { _, _ -> fail() }, startPlayback = { _, _ -> error("Synthetic measurement unavailable") })
        assertTrue(actions.primary()); assertTrue(opened)
    }
    @Test fun `MISSING arriving during actual prewarm retires intent and opens Details`() {
        val f = Fixture(); f.duringPrepare = { f.source = missing() }
        assertTrue(f.actions.primary()); assertNull(f.receipt); assertEquals(1, f.opens)
        assertEquals(StartupOutcome.CANCELLED, f.recorder.snapshot().single().outcome)
    }
    @Test fun `changed original producer during prewarm retires intent without navigation`() {
        val f = Fixture(); f.duringPrepare = { f.producer.cancel() }
        assertFalse(f.actions.primary()); assertNull(f.receipt); assertEquals(0, f.opens)
        assertEquals(StartupOutcome.CANCELLED, f.recorder.snapshot().single().outcome)
    }
    @Test fun `prepare exception preserves original exception and records failed start`() {
        val f = Fixture(); val original = IllegalStateException("Synthetic preparation failure")
        f.duringPrepare = { throw original }
        try { f.actions.primary(); fail() } catch (caught: IllegalStateException) { assertSame(original, caught) }
        assertEquals(StartupOutcome.FAILED, f.recorder.snapshot().single().outcome)
    }
}
