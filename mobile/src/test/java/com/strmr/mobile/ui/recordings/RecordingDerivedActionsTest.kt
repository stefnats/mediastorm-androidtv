package com.strmr.mobile.ui.recordings

import com.strmr.tv.live.*
import org.junit.Assert.*
import org.junit.Test

/** The actual shared eligibility/labels called by both platform detail views. */
class RecordingDerivedActionsTest {
    private fun recording(): Recording {
        val first = RecordingPart(1, "2026-11-19T10:00:00Z", "2026-11-19T10:00:01Z", 1000, "source_ended", "recording:synthetic/parts/1")
        val second = RecordingPart(2, "2026-11-19T10:00:06Z", "2026-11-19T10:00:07Z", 1000, "source_ended", "recording:synthetic/parts/2")
        return Recording("synthetic", "failed", capture = RecordingCapture(1, "interrupted", true, 0, listOf(first, second),
            listOf(RecordingGap("2026-11-19T10:00:01Z", "2026-11-19T10:00:06Z", "capacity"))), userId = "adult", updatedAt = "2026-11-19T12:00:00Z")
    }
    @Test fun `joined tools require known owned closed multi part source without changing ordinary part actions`() {
        val r = recording(); val capture = requireNotNull(r.capture); assertNotNull(RecordingDerivedSource.capture(r, "adult"))
        for (bad in listOf(r.copy(userId = null), r.copy(userId = "other"), r.copy(status = "running"),
            r.copy(capture = capture.copy(restored = true)), r.copy(capture = capture.copy(version = 0)))) {
            assertNull(RecordingDerivedSource.capture(bad, "adult"))
        }
        assertTrue(RecordingActions.of(r, null).any { it.kind == RecordingActionKind.PLAY })
        assertEquals(2, RecordingCaptureRules.queueParts(r)!!.size)
    }
    @Test fun `actual shared ready text is explicit about incomplete source omitted gaps and separate original parts`() {
        val value = RecordingDerivedDescriptor("synthetic", "ready", true, true, "failed", listOf(RecordingDerivedOffset(1, 0, 1000), RecordingDerivedOffset(2, 1000, 1000)), id = "a".repeat(32), durationMs = 2000, bytes = 2000)
        val text = value.label(); assertTrue(text.contains("incomplete")); assertTrue(text.contains("gaps were omitted"))
        assertTrue(text.contains("not restored")); assertTrue(text.contains("Original parts remain available")); assertTrue(text.contains("not guaranteed"))
        assertTrue(value.copy(state = "unknown", reason = "expired").label().contains("prepare explicitly"))
    }
}
