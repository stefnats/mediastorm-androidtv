package com.strmr.tv.ui.recordings

import com.strmr.tv.api.ApiClient
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.live.*
import com.strmr.tv.player.RecordingDerivedPlayback
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingDerivedStateTest {
    @Before fun profile() { ProfileDataContext.invalidate() }
    @After fun restore() { ProfileDataContext.invalidate() }
    private class Gateway : RecordingDerivedGateway {
        val calls = mutableListOf<Boolean>()
        var response: suspend (RecordingDerivedSource, Boolean) -> RecordingDerivedObservation = { source, _ -> DerivedTestData.observation(source) }
        override suspend fun read(source: RecordingDerivedSource, prepare: Boolean, expected: Long): RecordingDerivedObservation {
            assertEquals(ProfileDataContext.current, expected); calls += prepare; return response(source, prepare)
        }
    }
    @Test fun `production tools never prepare read or select merely from composition or equal polling`() = runTest {
        var row = DerivedTestData.recording(); val gateway = Gateway(); var launches = 0
        val state = RecordingDerivedState(backgroundScope, ProfileDataContext.current, { row }, { ApiClient.profileId }, { true }, { true }, gateway,
            now = { testScheduler.currentTime * 1_000_000 }, play = { launches++; true })
        assertTrue(state.eligible(row)); assertFalse(state.ready(row)); assertNull(state.label(row)); state.observeCurrent(row.copy())
        assertTrue(gateway.calls.isEmpty()); assertEquals(0, launches)
        state.read(row, true); runCurrent(); assertEquals(listOf(true), gateway.calls); assertTrue(state.ready(row)); assertEquals(0, launches)
        row = row.copy(); state.observeCurrent(row); assertTrue(state.ready(row)); assertEquals(listOf(true), gateway.calls)
        state.dispose()
    }
    @Test fun `only explicit play does fresh same artifact GET and transfers from original owner without automatic prepare`() = runTest {
        val row = DerivedTestData.recording(); val gateway = Gateway(); var handed: RecordingDerivedPlayback? = null
        val state = RecordingDerivedState(backgroundScope, ProfileDataContext.current, { row }, { ApiClient.profileId }, { true }, { true }, gateway,
            now = { testScheduler.currentTime * 1_000_000 }, play = { handed = it; true })
        state.read(row, false); runCurrent(); assertTrue(state.ready(row)); state.playFromStart(row); runCurrent()
        assertEquals(listOf(false, false), gateway.calls); assertNotNull(handed); assertFalse(state.ready(row))
        state.retire(); assertTrue(handed!!.canStart()); handed!!.release(); state.dispose()
    }
    @Test fun `one busy original job survives unchanged poll but a changed recording cancels only that child`() = runTest {
        var row = DerivedTestData.recording(); val gateway = Gateway(); val gate = CompletableDeferred<Unit>(); var cancelled = false
        gateway.response = { source, _ -> try { gate.await(); DerivedTestData.observation(source) } finally { cancelled = true } }
        val siblingGate = CompletableDeferred<Unit>(); val sibling = backgroundScope.launch { siblingGate.await() }
        val state = RecordingDerivedState(backgroundScope, ProfileDataContext.current, { row }, { ApiClient.profileId }, { true }, { true }, gateway,
            now = { 0 }, play = { true })
        state.read(row, true); runCurrent(); state.read(row, true); assertEquals(listOf(true), gateway.calls)
        row = row.copy(); state.observeCurrent(row); runCurrent(); assertFalse(cancelled); assertTrue(state.busy)
        row = row.copy(updatedAt = "2026-11-19T12:00:01Z"); state.observeCurrent(row); runCurrent()
        assertTrue(cancelled); assertFalse(state.busy); assertFalse(sibling.isCompleted); assertFalse(state.ready(row))
        siblingGate.complete(Unit); state.dispose()
    }
    @Test fun `masked late reply cannot attach to replacement producer selection ABA or disposed page`() = runTest {
        for (mode in listOf("producer", "source", "dispose", "page", "profile")) {
            var row = DerivedTestData.recording(); var page = true; val gateway = Gateway(); val gate = CompletableDeferred<Unit>()
            gateway.response = { source, _ -> withContext(NonCancellable) { gate.await() }; DerivedTestData.observation(source) }
            val state = RecordingDerivedState(backgroundScope, ProfileDataContext.current, { row }, { ApiClient.profileId }, { true }, { page }, gateway,
                now = { 0 }, play = { error("No late player") })
            state.read(row, true); runCurrent()
            when (mode) {
                "producer" -> { state.retire(); row = row.copy() }
                "source" -> { val original = row; row = row.copy(updatedAt = "2026-11-19T12:00:01Z"); state.observeCurrent(row); row = original; state.observeCurrent(row) }
                "dispose" -> state.dispose()
                "page" -> { page = false; state.retire(); page = true }
                "profile" -> { ProfileDataContext.invalidate(); ProfileDataContext.invalidate() }
            }
            gate.complete(Unit); runCurrent(); assertFalse(state.ready(row)); assertNull(state.label(row)); state.dispose()
        }
    }
    @Test fun `changed unknown expired or failed fresh play status never launches a replacement`() = runTest {
        for (mode in listOf("artifact", "unknown", "error", "expired")) {
            val row = DerivedTestData.recording(); val gateway = Gateway(); var plays = 0; var now = 0L
            val state = RecordingDerivedState(backgroundScope, ProfileDataContext.current, { row }, { ApiClient.profileId }, { true }, { true }, gateway,
                now = { now }, play = { plays++; true })
            state.read(row, false); runCurrent()
            gateway.response = { source, _ -> when (mode) {
                "artifact" -> DerivedTestData.observation(source, DerivedTestData.raw(source, id = "b".repeat(32)))
                "unknown" -> DerivedTestData.observation(source, DerivedTestData.raw(source, "source_changed"))
                "error" -> error("Synthetic denied status")
                else -> { now = requireNotNull(DerivedTestData.observation(source).deadlineNanos); DerivedTestData.observation(source) }
            } }
            state.playFromStart(row); runCurrent(); assertEquals(0, plays); assertFalse(state.ready(row)); assertNotNull(state.label(row)); state.dispose()
        }
    }
    @Test fun `original ready expiry clears buttons without polling or device wall clock renewal`() = runTest {
        val row = DerivedTestData.recording(); val gateway = Gateway()
        val state = RecordingDerivedState(backgroundScope, ProfileDataContext.current, { row }, { ApiClient.profileId }, { true }, { true }, gateway,
            now = { testScheduler.currentTime * 1_000_000 }, play = { true })
        state.read(row, false); runCurrent(); val boundary = requireNotNull(DerivedTestData.observation().deadlineNanos)
        advanceTimeBy(boundary / 1_000_000L + 1); runCurrent()
        assertFalse(state.ready(row)); assertTrue(state.label(row)!!.contains("expired")); assertEquals(listOf(false), gateway.calls); state.dispose()
    }
    @Test fun `source and profile unknown adult flags disable actions without reading or guessing permission`() = runTest {
        val row = DerivedTestData.recording(); val gateway = Gateway(); var adult = false
        val state = RecordingDerivedState(backgroundScope, ProfileDataContext.current, { row }, { ApiClient.profileId }, { adult }, { true }, gateway, now = { 0 }, play = { true })
        state.read(row, true); assertFalse(state.eligible(row)); assertTrue(gateway.calls.isEmpty()); adult = true
        assertTrue(state.eligible(row)); state.dispose()
    }
}
