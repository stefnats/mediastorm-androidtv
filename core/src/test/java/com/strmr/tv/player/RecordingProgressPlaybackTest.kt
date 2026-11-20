package com.strmr.tv.player

import com.strmr.tv.api.ApiClient
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.live.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Synthetic DATA through the genuine strict factories; not an executed Go/PG authority. */
internal class RecordingProgressTestGateway(private val now: () -> Long = { 0 }) : RecordingProgressGateway {
    val calls = mutableListOf<String>()
    val commands = mutableListOf<RecordingProgressCommand>()
    var state = "matching"
    var position = 650L
    var readAction: (suspend (RecordingDerivedObservation, Long) -> RecordingProgressSnapshotObservation)? = null
    var writeAction: (suspend (RecordingProgressCommand) -> RecordingProgressWriteObservation)? = null
    fun snapshot(original: RecordingDerivedObservation, expected: Long = ProfileDataContext.current,
        revision: String = "0", generation: String = "0"): RecordingProgressSnapshotObservation {
        val raw = ProgressTestData.replace(ProgressTestData.raw(original, state, revision, generation, position), "progress", "validUntil",
            JsonPrimitive(Instant.ofEpochMilli(DerivedTestData.serverMillis + 20_000 + now() / 1_000_000).toString()))
        return RecordingProgressSnapshotObservation.accept(original, raw, clock(), expected, now())
    }
    fun receipt(command: RecordingProgressCommand): RecordingProgressWriteObservation {
        val raw = ProgressTestData.replace(ProgressTestData.result(command), "result", "validUntil",
            JsonPrimitive(Instant.ofEpochMilli(DerivedTestData.serverMillis + 20_000 + now() / 1_000_000).toString()))
        return RecordingProgressWriteObservation.accept(command, raw, clock(), now())
    }
    private fun clock() = DerivedTestData.clock(now(), DerivedTestData.serverMillis + 1001 + now() / 1_000_000)
    override suspend fun read(original: RecordingDerivedObservation, expectedContext: Long): RecordingProgressSnapshotObservation {
        calls += "snapshot"
        return readAction?.invoke(original, expectedContext) ?: snapshot(original, expectedContext)
    }
    override suspend fun write(original: RecordingDerivedObservation, command: RecordingProgressCommand, expectedContext: Long): RecordingProgressWriteObservation {
        assertSame(original, command.original); assertEquals(expectedContext, command.expectedContext)
        calls += command.action; commands += command
        return writeAction?.invoke(command) ?: receipt(command)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingProgressPlaybackTest {
    @Before fun profile() { ProfileDataContext.invalidate() }
    @After fun restore() { ProfileDataContext.invalidate() }

    private fun original() = DerivedTestData.observation()
    private fun launch(scope: CoroutineScope, value: RecordingDerivedObservation = original(),
        now: () -> Long = { 0 }): RecordingDerivedPlayback {
        val gateway = RecordingProgressTestGateway(now)
        val command = RecordingProgressCommand.resume(gateway.snapshot(value))
        val resource = requireNotNull(RecordingProgressResource.acquire(scope))
        return RecordingDerivedPlayback.launchProgress(PlaybackSessionContext.capture(), value,
            gateway.receipt(command), resource, { true }, now)
    }

    @Test fun actual_start_gate_uses_acknowledged_long_without_seconds_roundtrip() = runTest {
        val source = launch(backgroundScope)
        try {
            source.claim()
            val gate = RecordingProgressStart(source)
            val targets = mutableListOf<Long>()
            gate.install { targets += it }
            assertEquals(listOf(650L), targets)
            assertFalse(gate.started)
            assertTrue(gate.ready(true, 2000, 650))
            assertTrue(gate.started)
            assertTrue(runCatching { gate.install { error("Second media item") } }.isFailure)
            assertTrue(gate.ready(true, 2000, 651))
        } finally { source.release(); runCurrent() }
    }

    @Test fun nonzero_start_requires_seek_support_and_never_falls_back_to_zero() = runTest {
        for (mode in listOf("unseekable", "wrong-position", "short-timeline", "unknown-duration")) {
            val source = launch(backgroundScope)
            try {
                source.claim(); val gate = RecordingProgressStart(source); val targets = mutableListOf<Long>()
                gate.install { targets += it }
                when (mode) {
                    "unseekable" -> assertTrue(runCatching { gate.ready(false, 2000, 650) }.isFailure)
                    "wrong-position" -> assertTrue(runCatching { gate.ready(true, 2000, 0) }.isFailure)
                    "short-timeline" -> assertTrue(runCatching { gate.ready(true, 600, 650) }.isFailure)
                    else -> assertFalse(gate.ready(true, -1, 650))
                }
                assertFalse(gate.started); assertEquals(listOf(650L), targets)
            } finally { source.release(); runCurrent() }
        }
    }

    @Test fun initial_receipt_expiry_vs_adopted_playback_and_original_source_expiry() = runTest {
        var time = 0L
        val first = launch(backgroundScope, now = { time })
        try {
            time = requireNotNull(first.progressReceipt).deadlineNanos
            assertFalse(first.canStart())
            assertTrue(runCatching { first.claim() }.isFailure)
        } finally { first.release(); runCurrent() }
        time = 0
        val adopted = launch(backgroundScope, now = { time })
        try {
            adopted.claim(); time = requireNotNull(adopted.progressReceipt).deadlineNanos + 1
            assertTrue(adopted.allowed())
            time = requireNotNull(adopted.observation.deadlineNanos)
            assertFalse(adopted.allowed())
        } finally { adopted.release(); runCurrent() }
    }

    @Test fun legacy_raw_source_and_catalogue_progress_isolation_remain() = runTest {
        val source = launch(backgroundScope)
        val legacy = RecordingDerivedPlayback.launch(PlaybackSessionContext.capture(), original(), { true }, { 0 })
        try {
            val session = recordingDerivedSession(source, "synthetic")
            assertNull(session.request.start); assertFalse(session.reportsProgress)
            assertNull(session.progressSink); assertNull(session.streamPath); assertNull(session.prequeueId)
            assertFalse(audioRecoveryEligible(session, true, true))
            assertEquals(0L, legacy.startPositionMs); assertNull(legacy.progressReceipt)
            assertTrue(legacy.canStart())
            assertTrue(runCatching { session.copy(reportsProgress = true) }.isFailure)
        } finally { source.release(); legacy.release(); runCurrent() }
    }

    @Test fun pending_transfer_is_one_shot_and_expected_page_coverage_survives() = runTest {
        val parent = CoroutineScope(backgroundScope.coroutineContext + Job())
        val source = launch(parent)
        try {
            assertEquals(1, RecordingProgressResource.charged())
            source.claim(); parent.cancel(); runCurrent()
            assertTrue(source.allowed()); assertFalse(source.canStart())
            source.retirePending(); assertTrue(source.allowed())
            assertTrue(runCatching { source.claim() }.isFailure)
        } finally { source.release(); parent.cancel(); runCurrent() }
        assertEquals(0, RecordingProgressResource.charged())
    }

    @Test fun source_epoch_loss_and_regenerated_artifact_retire_original_handoff() = runTest {
        val source = launch(backgroundScope)
        try {
            ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); runCurrent(); advanceTimeBy(101); runCurrent()
            assertFalse(source.canStart()); assertFalse(source.allowed())
        } finally { source.release(); runCurrent() }
        val value = original(); val gateway = RecordingProgressTestGateway()
        val receipt = gateway.receipt(RecordingProgressCommand.resume(gateway.snapshot(value)))
        val changed = DerivedTestData.observation(value.source, DerivedTestData.raw(value.source, id = "b".repeat(32)))
        val resource = requireNotNull(RecordingProgressResource.acquire(backgroundScope))
        try {
            assertTrue(runCatching { RecordingDerivedPlayback.launchProgress(PlaybackSessionContext.capture(), changed, receipt, resource, { true }, { 0 }) }.isFailure)
        } finally { resource.close().join() }
    }
}
