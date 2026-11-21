package com.strmr.tv.ui.recordings

import com.strmr.tv.api.ApiClient
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.live.*
import com.strmr.tv.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingHistoryActionsTest {
    @Before fun profile() { ProfileDataContext.invalidate() }
    @After fun restore() { ProfileDataContext.invalidate() }
    private class Derived : RecordingDerivedGateway {
        override suspend fun read(source: RecordingDerivedSource, prepare: Boolean, expected: Long) = DerivedTestData.observation(source)
    }
    private class Gateway : RecordingProgressGateway {
        val base = RecordingProgressTestGateway().also { it.state = "conflict" }
        var reads = 0; var state = "compatible"; var position = 650L
        var historyAction: (suspend (RecordingDerivedObservation, Long) -> RecordingProgressSnapshotObservation)? = null
        override suspend fun read(original: RecordingDerivedObservation, expectedContext: Long) = base.read(original, expectedContext)
        override suspend fun readHistory(original: RecordingDerivedObservation, expectedContext: Long): RecordingProgressSnapshotObservation {
            reads++; return historyAction?.invoke(original, expectedContext) ?: HistoryTestData.snapshot(original, expectedContext, state, position)
        }
        override suspend fun write(original: RecordingDerivedObservation, command: RecordingProgressCommand, expectedContext: Long) = base.write(original, command, expectedContext)
    }
    private fun state(scope: CoroutineScope, row: () -> Recording, gateway: Gateway, play: (RecordingDerivedPlayback) -> Boolean = { false }, now: () -> Long = { 0 }) =
        RecordingDerivedState(scope, ProfileDataContext.current, row, { ApiClient.profileId }, { true }, { true }, Derived(), now, play, gateway)
    @Test fun history_read_requires_explicit_conflict_choice_and_preserves_old_read() = runTest {
        val row = DerivedTestData.recording(); val gateway = Gateway(); val state = state(backgroundScope, { row }, gateway)
        try {
            state.checkEarlierProgress(row); runCurrent(); assertEquals(0, gateway.reads)
            state.read(row, false); runCurrent(); state.readProgress(row); runCurrent()
            assertEquals(listOf("snapshot"), gateway.base.calls); assertEquals(0, gateway.reads)
            assertTrue(state.canCheckEarlier(row)); assertFalse(state.canResumeEarlier(row)); assertFalse(state.canResume(row))
            state.checkEarlierProgress(row); runCurrent(); assertEquals(1, gateway.reads); assertTrue(state.canResumeEarlier(row)); assertTrue(gateway.base.commands.isEmpty())
        } finally { state.dispose(); runCurrent() }
    }
    @Test fun compatible_choice_claims_once_before_actual_player_handoff() = runTest {
        val row = DerivedTestData.recording(); val gateway = Gateway(); val gate = CompletableDeferred<Unit>(); var handoff: RecordingDerivedPlayback? = null
        gateway.base.writeAction = { command -> gate.await(); gateway.base.receipt(command) }
        val state = state(backgroundScope, { row }, gateway, { handoff = it; true })
        try {
            state.read(row, false); runCurrent(); state.readProgress(row); runCurrent(); state.checkEarlierProgress(row); runCurrent()
            state.playEarlier(row); runCurrent(); assertNull(handoff); assertEquals("resume-history", gateway.base.commands.single().action)
            state.playEarlier(row); runCurrent(); assertEquals(1, gateway.base.commands.size)
            gate.complete(Unit); runCurrent(); assertEquals(650L, requireNotNull(handoff).startPositionMs); assertFalse(state.progressAvailable(row))
        } finally { gate.complete(Unit); handoff?.release(); state.dispose(); runCurrent() }
    }
    @Test fun failed_check_preserves_working_from_start_choice() = runTest {
        val row = DerivedTestData.recording(); val gateway = Gateway(); gateway.historyAction = { _, _ -> throw java.io.IOException("Synthetic failed check") }
        var handoff: RecordingDerivedPlayback? = null; val state = state(backgroundScope, { row }, gateway, { handoff = it; true })
        try {
            state.read(row, false); runCurrent(); state.readProgress(row); runCurrent(); state.checkEarlierProgress(row); runCurrent()
            assertTrue(state.progressAvailable(row)); assertFalse(state.canResumeEarlier(row)); assertEquals(1, gateway.reads)
            state.playWithProgress(row, false); runCurrent(); assertEquals("from-start", gateway.base.commands.single().action); assertEquals(0L, requireNotNull(handoff).startPositionMs)
        } finally { handoff?.release(); state.dispose(); runCurrent() }
    }
    @Test fun weak_or_absent_history_never_offers_resume() = runTest {
        for (mode in listOf("absent", "unverifiable", "end")) {
            val row = DerivedTestData.recording(); val gateway = Gateway(); gateway.state = if (mode == "end") "compatible" else mode; gateway.position = if (mode == "end") 2000 else 650
            val state = state(backgroundScope, { row }, gateway)
            try {
                state.read(row, false); runCurrent(); state.readProgress(row); runCurrent(); state.checkEarlierProgress(row); runCurrent()
                assertTrue(state.progressAvailable(row)); assertFalse(state.canResumeEarlier(row)); state.playEarlier(row); runCurrent(); assertTrue(gateway.base.commands.isEmpty())
            } finally { state.dispose(); runCurrent() }
        }
    }
    @Test fun changed_owner_source_or_expired_candidate_never_launches() = runTest {
        for (mode in listOf("owner", "source", "expiry")) {
            var row = DerivedTestData.recording(); val gateway = Gateway(); var launches = 0; var now = 0L
            val state = state(backgroundScope, { row }, gateway, { launches++; true }, { now })
            try {
                state.read(row, false); runCurrent(); state.readProgress(row); runCurrent(); state.checkEarlierProgress(row); runCurrent()
                when (mode) { "owner" -> { row = row.copy(userId = "different-owner"); state.observeCurrent(row) }; "source" -> { row = row.copy(updatedAt = "2026-11-19T12:00:01Z"); state.observeCurrent(row) }; else -> now = 21_000_000_000 }
                state.playEarlier(row); runCurrent(); assertEquals(0, launches); assertTrue(gateway.base.commands.isEmpty())
            } finally { state.dispose(); runCurrent() }
        }
    }
    @Test fun cancelled_history_check_retains_resource_until_callback_finishes() = runTest {
        val row = DerivedTestData.recording(); val gateway = Gateway(); val gate = CompletableDeferred<Unit>(); val returned = CompletableDeferred<Unit>()
        gateway.historyAction = { original, context -> try { withContext(NonCancellable) { gate.await() }; HistoryTestData.snapshot(original, context) } finally { returned.complete(Unit) } }
        val state = state(backgroundScope, { row }, gateway)
        try {
            state.read(row, false); runCurrent(); state.readProgress(row); runCurrent(); state.checkEarlierProgress(row); runCurrent(); state.retire(); runCurrent()
            assertFalse(returned.isCompleted); assertEquals(1, RecordingProgressResource.charged()); gate.complete(Unit); runCurrent()
            assertTrue(returned.isCompleted); assertEquals(0, RecordingProgressResource.charged()); assertFalse(state.progressAvailable(row))
        } finally { gate.complete(Unit); state.dispose(); runCurrent() }
    }
    @Test fun history_ack_mismatch_or_failure_never_seeks_or_replays() = runTest {
        for (mode in listOf("failure", "different-position")) {
            val row = DerivedTestData.recording(); val gateway = Gateway(); var launches = 0
            gateway.base.writeAction = { command -> if (mode == "failure") throw java.io.IOException("Synthetic unknown ACK") else RecordingProgressWriteObservation.accept(command,
                ProgressTestData.replace(ProgressTestData.result(command), "result", "positionMs", kotlinx.serialization.json.JsonPrimitive(651)), ProgressTestData.clock(), 0) }
            val state = state(backgroundScope, { row }, gateway, { launches++; true })
            try {
                state.read(row, false); runCurrent(); state.readProgress(row); runCurrent(); state.checkEarlierProgress(row); runCurrent(); state.playEarlier(row); runCurrent(); state.playEarlier(row); runCurrent()
                assertEquals(0, launches); assertEquals(1, gateway.base.commands.size); assertFalse(state.progressAvailable(row))
            } finally { state.dispose(); runCurrent() }
        }
    }
    @Test fun historical_check_returning_matching_uses_only_ordinary_resume() = runTest {
        val row = DerivedTestData.recording(); val gateway = Gateway(); var handoff: RecordingDerivedPlayback? = null
        gateway.historyAction = { original, context ->
            var raw = ProgressTestData.raw(original, "matching", "8", "10", 800)
            raw = ProgressTestData.replace(raw, "progress", "historyState", kotlinx.serialization.json.JsonPrimitive("compatible"))
            raw = ProgressTestData.replace(raw, "progress", "historicalPositionMs", kotlinx.serialization.json.JsonPrimitive(650))
            raw = ProgressTestData.replace(raw, "progress", "historicalAtDerivedEnd", kotlinx.serialization.json.JsonPrimitive(false))
            RecordingProgressSnapshotObservation.acceptHistory(original, raw, ProgressTestData.clock(), context, 0)
        }
        val state = state(backgroundScope, { row }, gateway, { handoff = it; true })
        try {
            state.read(row, false); runCurrent(); state.readProgress(row); runCurrent()
            assertTrue(state.canCheckEarlier(row)); state.checkEarlierProgress(row); runCurrent()
            assertEquals(1, gateway.reads); assertTrue(state.canResume(row)); assertFalse(state.canResumeEarlier(row))
            assertTrue(requireNotNull(state.progressMessage).contains("Current saved position: 800"))
            state.playEarlier(row); runCurrent(); assertTrue(gateway.base.commands.isEmpty()); assertNull(handoff)
            state.playWithProgress(row, true); runCurrent(); state.playWithProgress(row, true); runCurrent()
            assertEquals(listOf("snapshot", "resume"), gateway.base.calls); assertEquals(1, gateway.base.commands.size)
            val command = gateway.base.commands.single()
            assertEquals("8", command.revision.wire); assertEquals("10", command.writerGeneration.wire)
            assertEquals(800L, requireNotNull(handoff).startPositionMs)
        } finally { handoff?.release(); state.dispose(); runCurrent() }
    }

}
