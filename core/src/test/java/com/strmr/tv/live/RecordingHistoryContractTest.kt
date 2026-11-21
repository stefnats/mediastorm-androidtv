package com.strmr.tv.live

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal object HistoryTestData {
    fun raw(original: RecordingDerivedObservation, state: String = "compatible", position: Long = 650): String {
        var raw = ProgressTestData.replace(ProgressTestData.raw(original, "conflict", "7", "9"), "progress", "historyState", JsonPrimitive(state))
        if (state == "compatible") {
            raw = ProgressTestData.replace(raw, "progress", "historicalPositionMs", JsonPrimitive(position))
            raw = ProgressTestData.replace(raw, "progress", "historicalAtDerivedEnd", JsonPrimitive(position == original.descriptor.durationMs))
        }
        return raw
    }
    fun snapshot(original: RecordingDerivedObservation, context: Long = 1, state: String = "compatible", position: Long = 650,
        now: Long = 0) = RecordingProgressSnapshotObservation.acceptHistory(original, raw(original, state, position), ProgressTestData.clock(now), context, now)
}
class RecordingHistoryContractTest {
    @Test fun strict_history_states_and_legacy_parser_stay_separate() {
        val original = ProgressTestData.original()
        for (state in listOf("absent", "unverifiable", "compatible")) {
            val raw = HistoryTestData.raw(original, state)
            val value = RecordingProgressSnapshotObservation.acceptHistory(original, raw, ProgressTestData.clock(), 1, 0)
            assertEquals(state, value.data.historyState); assertEquals("conflict", value.data.positionState)
            assertEquals(if (state == "compatible") 650L else null, value.data.historicalPositionMs)
            assertTrue(runCatching { RecordingProgressSnapshotObservation.accept(original, raw, ProgressTestData.clock(), 1, 0) }.isFailure)
        }
        val legacy = ProgressTestData.raw(original, "conflict")
        assertNull(RecordingProgressSnapshotObservation.accept(original, legacy, ProgressTestData.clock(), 1, 0).data.historyState)
        assertTrue(runCatching { RecordingProgressSnapshotObservation.acceptHistory(original, legacy, ProgressTestData.clock(), 1, 0) }.isFailure)
    }
    @Test fun compatible_position_and_end_are_exact_and_bounded() {
        val original = ProgressTestData.original()
        for (position in listOf(0L, 1L, 1999L, 2000L)) {
            val data = HistoryTestData.snapshot(original, position = position).data
            assertEquals(position, data.historicalPositionMs); assertEquals(position == 2000L, data.historicalAtDerivedEnd)
        }
        for (position in listOf(-1L, 2001L, Long.MAX_VALUE)) {
            assertTrue(runCatching { HistoryTestData.snapshot(original, position = position) }.isFailure)
        }
        val raw = HistoryTestData.raw(original)
        for (bad in listOf(JsonPrimitive("650"), JsonPrimitive(650.0), JsonNull, JsonPrimitive(true))) {
            assertTrue(runCatching { RecordingProgressSnapshotObservation.acceptHistory(original, ProgressTestData.replace(raw, "progress", "historicalPositionMs", bad), ProgressTestData.clock(), 1, 0) }.isFailure)
        }
        assertTrue(runCatching { RecordingProgressSnapshotObservation.acceptHistory(original, ProgressTestData.replace(raw, "progress", "historicalAtDerivedEnd", JsonPrimitive(true)), ProgressTestData.clock(), 1, 0) }.isFailure)
    }
    @Test fun duplicate_unknown_null_and_counter_fields_are_denied() {
        val original = ProgressTestData.original(); val raw = HistoryTestData.raw(original)
        val bad = listOf(raw.replace("\"historyState\":\"compatible\"", "\"historyState\":\"compatible\",\"history\\u0053tate\":\"compatible\""),
            ProgressTestData.replace(raw, "progress", "historyState", JsonPrimitive("known")),
            ProgressTestData.replace(raw, "progress", "historyState", JsonNull),
            ProgressTestData.replace(raw, "progress", "historicalAtDerivedEnd", null),
            ProgressTestData.replace(raw, "progress", "compatible", JsonPrimitive(true)),
            ProgressTestData.replace(raw, "progress", "revision", JsonPrimitive("07")),
            ProgressTestData.replace(raw, "progress", "writerGeneration", JsonPrimitive(9)))
        bad.forEach { assertTrue(runCatching { RecordingProgressSnapshotObservation.acceptHistory(original, it, ProgressTestData.clock(), 1, 0) }.isFailure) }
        for (state in listOf("absent", "unverifiable")) assertTrue(runCatching {
            RecordingProgressSnapshotObservation.acceptHistory(original, ProgressTestData.replace(HistoryTestData.raw(original, state), "progress", "historicalPositionMs", JsonPrimitive(650)), ProgressTestData.clock(), 1, 0)
        }.isFailure)
    }
    @Test fun history_claim_is_explicit_zero_position_and_ack_must_match_candidate() {
        val original = ProgressTestData.original(); val snapshot = HistoryTestData.snapshot(original)
        val command = RecordingProgressCommand.resumeHistory(snapshot)
        assertEquals("resume-history", command.action); assertEquals(0L, command.positionMs); assertEquals(650L, command.expectedResultPositionMs)
        assertEquals(8, command.body().size); assertEquals("7", command.body().getValue("expectedRevision").jsonPrimitive.content)
        val valid = ProgressTestData.result(command)
        assertEquals(650L, RecordingProgressWriteObservation.accept(command, valid, ProgressTestData.clock(), 0).data.positionMs)
        assertTrue(runCatching { RecordingProgressWriteObservation.accept(command, ProgressTestData.replace(valid, "result", "positionMs", JsonPrimitive(651)), ProgressTestData.clock(), 0) }.isFailure)
        assertTrue(runCatching { RecordingProgressCommand.resume(snapshot) }.isFailure)
        for (state in listOf("absent", "unverifiable")) assertTrue(runCatching { RecordingProgressCommand.resumeHistory(HistoryTestData.snapshot(original, state = state)) }.isFailure)
    }
    @Test fun original_context_clock_and_deadline_bound_history_adoption() {
        val original = ProgressTestData.original(); val value = HistoryTestData.snapshot(original, context = 37)
        assertSame(original, value.original); assertEquals(37L, value.expectedContext)
        assertTrue(value.adoptable(0)); assertFalse(value.adoptable(21_000_000_000))
        val changed = DerivedTestData.observation(original.source, DerivedTestData.raw(original.source, id = "b".repeat(32)))
        assertTrue(runCatching { RecordingProgressSnapshotObservation.acceptHistory(changed, HistoryTestData.raw(original), ProgressTestData.clock(), 1, 0) }.isFailure)
        assertTrue(runCatching { RecordingProgressSnapshotObservation.acceptHistory(original, HistoryTestData.raw(original), ProgressTestData.clock(), 1, 21_000_000_000) }.isFailure)
    }
}
