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
class RecordingProgressActionsTest {
    @Before fun profile() { ProfileDataContext.invalidate() }
    @After fun restore() { ProfileDataContext.invalidate() }
    private class Derived : RecordingDerivedGateway {
        val calls = mutableListOf<Boolean>()
        var action: suspend (RecordingDerivedSource) -> RecordingDerivedObservation = { DerivedTestData.observation(it) }
        fun rejectAdditionalReads() { action = { error("Progress must not use the generic Derived transport") } }
        override suspend fun read(source: RecordingDerivedSource, prepare: Boolean, expected: Long): RecordingDerivedObservation {
            assertEquals(ProfileDataContext.current, expected); calls += prepare; return action(source)
        }
    }

    @Test fun actual_current_descriptor_then_snapshot_then_explicit_claim_only() = runTest {
        val row = DerivedTestData.recording(); val derived = Derived(); val progress = RecordingProgressTestGateway()
        var handoff: RecordingDerivedPlayback? = null
        val state = RecordingDerivedState(backgroundScope, ProfileDataContext.current, { row }, { ApiClient.profileId }, { true }, { true },
            derived, now = { 0 }, play = { handoff = it; true }, progressGateway = progress)
        try {
            assertFalse(state.progressAvailable(row)); assertTrue(progress.calls.isEmpty()); assertTrue(derived.calls.isEmpty())
            state.read(row, false); runCurrent(); derived.rejectAdditionalReads(); state.readProgress(row); runCurrent()
            assertEquals(listOf(false), derived.calls); assertEquals(listOf("snapshot"), progress.calls)
            assertTrue(state.canResume(row)); assertNull(handoff)
            state.playWithProgress(row, true); runCurrent()
            assertEquals(listOf(false), derived.calls)
            assertEquals(listOf("snapshot", "resume"), progress.calls)
            assertEquals(650L, requireNotNull(handoff).startPositionMs)
            assertFalse(state.progressAvailable(row))
        } finally { handoff?.release(); state.dispose(); runCurrent() }
    }

    @Test fun none_matching_conflict_and_end_offer_only_valid_actions() = runTest {
        for (mode in listOf("none", "matching", "conflict", "end")) {
            val row = DerivedTestData.recording(); val derived = Derived(); val progress = RecordingProgressTestGateway()
            progress.state = if (mode == "end") "matching" else mode
            progress.position = if (mode == "end") 2000 else 650
            var handoff: RecordingDerivedPlayback? = null
            val state = RecordingDerivedState(backgroundScope, ProfileDataContext.current, { row }, { ApiClient.profileId }, { true }, { true },
                derived, now = { 0 }, play = { handoff = it; true }, progressGateway = progress)
            try {
                state.read(row, false); runCurrent(); derived.rejectAdditionalReads(); state.readProgress(row); runCurrent()
                assertEquals(listOf(false), derived.calls)
                assertTrue(state.progressAvailable(row)); assertEquals(mode == "matching", state.canResume(row))
                if (mode != "matching") { state.playWithProgress(row, true); runCurrent(); assertEquals(listOf("snapshot"), progress.calls) }
                if (mode == "conflict") assertTrue(requireNotNull(state.progressMessage).contains("different artifact"))
                state.playWithProgress(row, false); runCurrent()
                assertEquals("from-start", progress.commands.single().action)
                assertEquals(0L, requireNotNull(handoff).startPositionMs); assertEquals(listOf(false), derived.calls)
            } finally { handoff?.release(); state.dispose(); runCurrent() }
        }
    }

    @Test fun choice_revalidates_same_descriptor_before_known_ack_handoff() = runTest {
        for (mode in listOf("held-ack", "changed-snapshot", "changed-post", "failed-ack")) {
            val row = DerivedTestData.recording(); val derived = Derived(); val progress = RecordingProgressTestGateway()
            val gate = CompletableDeferred<Unit>(); var handoff: RecordingDerivedPlayback? = null
            val state = RecordingDerivedState(backgroundScope, ProfileDataContext.current, { row }, { ApiClient.profileId }, { true }, { true },
                derived, now = { 0 }, play = { handoff = it; true }, progressGateway = progress)
            try {
                state.read(row, false); runCurrent(); derived.rejectAdditionalReads()
                // Controlled current-source outcomes from Progress GET/POST, not executed Go authority.
                if (mode == "changed-snapshot") progress.readAction = { original, expected ->
                    val changed = ProgressTestData.raw(original).replace("a".repeat(32), "b".repeat(32))
                    RecordingProgressSnapshotObservation.accept(original, changed, DerivedTestData.clock(), expected, 0)
                }
                state.readProgress(row); runCurrent()
                if (mode == "held-ack") progress.writeAction = { command -> gate.await(); progress.receipt(command) }
                if (mode == "changed-post") progress.writeAction = { throw com.strmr.tv.api.ApiError.Http(com.strmr.tv.api.HttpFailure(409, "Synthetic current source changed")) }
                if (mode == "failed-ack") progress.writeAction = { throw java.io.IOException("Synthetic uncertain commit") }
                state.playWithProgress(row, true); runCurrent(); assertNull(handoff)
                if (mode == "held-ack") { assertTrue(state.busy); gate.complete(Unit); runCurrent(); assertNotNull(handoff) }
                else assertFalse(state.progressAvailable(row))
                assertEquals(if (mode == "changed-snapshot") 0 else 1, progress.commands.size)
                assertEquals(listOf(false), derived.calls)
            } finally { gate.complete(Unit); handoff?.release(); state.dispose(); runCurrent() }
        }
    }

    @Test fun late_ack_profile_source_page_and_artifact_replacement_never_launch() = runTest {
        for (mode in listOf("profile", "source", "page", "dispose")) {
            var row = DerivedTestData.recording(); var page = true; val gate = CompletableDeferred<Unit>(); val derived = Derived()
            val progress = RecordingProgressTestGateway(); var launches = 0
            progress.writeAction = { command -> withContext(NonCancellable) { gate.await() }; progress.receipt(command) }
            val state = RecordingDerivedState(backgroundScope, ProfileDataContext.current, { row }, { ApiClient.profileId }, { true }, { page },
                derived, now = { 0 }, play = { launches++; true }, progressGateway = progress)
            try {
                state.read(row, false); runCurrent(); derived.rejectAdditionalReads(); state.readProgress(row); runCurrent(); state.playWithProgress(row, true); runCurrent()
                assertEquals(1, progress.commands.size)
                when (mode) {
                    "profile" -> { ProfileDataContext.invalidate(); ProfileDataContext.invalidate() }
                    "source" -> { val old = row; row = row.copy(updatedAt = "2026-11-19T12:00:01Z"); state.observeCurrent(row); row = old }
                    "page" -> { page = false; state.retire(); page = true }
                    else -> state.dispose()
                }
                runCurrent(); assertEquals(1, RecordingProgressResource.charged())
                gate.complete(Unit); runCurrent(); assertEquals(0, launches); assertFalse(state.progressAvailable(row)); assertEquals(listOf(false), derived.calls)
            } finally { gate.complete(Unit); state.dispose(); runCurrent() }
        }
    }

    @Test fun old_response_deadline_requires_explicit_new_read_without_zero_defaults() = runTest {
        val row = DerivedTestData.recording(); val derived = Derived(); val progress = RecordingProgressTestGateway { testScheduler.currentTime * 1_000_000 }
        progress.readAction = { original, context -> progress.snapshot(original, context, "7", "9") }
        var handoff: RecordingDerivedPlayback? = null
        val state = RecordingDerivedState(backgroundScope, ProfileDataContext.current, { row }, { ApiClient.profileId }, { true }, { true },
            derived, now = { testScheduler.currentTime * 1_000_000 }, play = { handoff = it; true }, progressGateway = progress)
        try {
            state.read(row, false); runCurrent(); derived.rejectAdditionalReads(); state.readProgress(row); runCurrent()
            advanceTimeBy(21_000); runCurrent(); assertFalse(state.progressAvailable(row))
            state.playWithProgress(row, false); runCurrent(); assertTrue(progress.commands.isEmpty())
            state.readProgress(row); runCurrent(); state.playWithProgress(row, false); runCurrent()
            assertEquals(7L, progress.commands.single().revision.value); assertEquals(9L, progress.commands.single().writerGeneration.value)
            assertNotNull(handoff); assertEquals(listOf(false), derived.calls)
        } finally { handoff?.release(); state.dispose(); runCurrent() }
    }

    @Test fun resource_admission_and_ui_retirement_wait_actual_callback_cleanup() = runTest {
        val row = DerivedTestData.recording(); val gate = CompletableDeferred<Unit>(); val returned = CompletableDeferred<Unit>(); val derived = Derived()
        val progress = RecordingProgressTestGateway()
        progress.writeAction = { command ->
            try { withContext(NonCancellable) { gate.await() }; progress.receipt(command) }
            finally { returned.complete(Unit) }
        }
        val state = RecordingDerivedState(backgroundScope, ProfileDataContext.current, { row }, { ApiClient.profileId }, { true }, { true },
            derived, now = { 0 }, play = { error("Retired producer") }, progressGateway = progress)
        var second: RecordingProgressResource? = null
        try {
            state.read(row, false); runCurrent(); derived.rejectAdditionalReads(); state.readProgress(row); runCurrent(); state.playWithProgress(row, true); runCurrent()
            second = requireNotNull(RecordingProgressResource.acquire(backgroundScope))
            assertNull(RecordingProgressResource.acquire(backgroundScope)); state.retire(); runCurrent()
            assertFalse(returned.isCompleted); assertEquals(2, RecordingProgressResource.charged())
            gate.complete(Unit); runCurrent(); assertTrue(returned.isCompleted); assertEquals(1, RecordingProgressResource.charged()); assertEquals(listOf(false), derived.calls)
        } finally { gate.complete(Unit); state.dispose(); second?.close(); runCurrent() }
        assertEquals(0, RecordingProgressResource.charged())
    }
}
