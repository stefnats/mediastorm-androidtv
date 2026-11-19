package com.strmr.tv.player

import com.strmr.tv.api.ApiClient
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.live.DerivedTestData
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RecordingDerivedPlaybackTest {
    @Before fun profile() { ProfileDataContext.invalidate() }
    @After fun restore() { ProfileDataContext.invalidate() }
    private fun newSource(now: () -> Long = { 0 }) = RecordingDerivedPlayback.launch(PlaybackSessionContext.capture(), DerivedTestData.observation(), { true }, now)
    @Test fun `actual pending source is one shot and adopted playback survives expected page coverage`() {
        var page = true; val original = PlaybackSessionContext.capture()
        val source = RecordingDerivedPlayback.launch(original, DerivedTestData.observation(), { page }, { 0 })
        val session = recordingDerivedSession(source, "https://example.invalid/api/live/recordings/synthetic-recording/derived/${"a".repeat(32)}/stream?profileId=adult")
        assertTrue(session.canOpenPlayer()); assertTrue(session.recordingDerivedReady()); source.claim(); page = false
        source.retirePending(); assertTrue(session.canOpenPlayer()); assertFalse(session.recordingDerivedReady())
        assertTrue(runCatching { source.claim() }.isFailure); source.release(); assertFalse(session.canOpenPlayer())
        assertSame(original, session.ownership)
    }
    @Test fun `replaced unclaimed pending source cannot enter actual decoder helper`() {
        val source = newSource(); val session = recordingDerivedSession(source, "synthetic")
        source.retirePending(); assertFalse(session.recordingDerivedReady()); assertFalse(session.canOpenPlayer())
    }
    @Test fun `exact original expiry and profile ABA stop paused or active ownership without renewal`() {
        var now = 0L; val source = newSource { now }; val deadline = requireNotNull(source.observation.deadlineNanos)
        val session = recordingDerivedSession(source, "synthetic"); source.claim()
        now = deadline - 1; assertTrue(session.playbackAllowed()); now = deadline; assertFalse(session.playbackAllowed())
        val next = newSource(); val pending = recordingDerivedSession(next, "synthetic")
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); assertFalse(pending.canOpenPlayer()); assertFalse(pending.recordingDerivedReady())
    }
    @Test fun `foreign current profile and original producer loss cannot mint a handoff`() {
        val observation = DerivedTestData.observation()
        assertTrue(runCatching { RecordingDerivedPlayback.launch(PlaybackSessionContext.capture(), observation, { false }, { 0 }) }.isFailure)
        val foreign = DerivedTestData.observation(DerivedTestData.source(DerivedTestData.recording(profile = ApiClient.profileId + "-other")))
        assertTrue(runCatching { RecordingDerivedPlayback.launch(PlaybackSessionContext.capture(), foreign, { true }, { 0 }) }.isFailure)
    }
    @Test fun `joined player source has no catalogue progress reports release recovery or part cursor`() {
        val source = newSource(); val session = recordingDerivedSession(source, "synthetic")
        assertFalse(session.reportsProgress); assertNull(session.prequeueId); assertNull(session.streamPath); assertNull(session.recordingPart)
        assertNull(session.growingRestart); assertNull(session.retuneLive); assertNull(session.progressSink)
        assertFalse(audioRecoveryEligible(session, true, true)); assertTrue(session.request.title.titleId.startsWith("recording-derived:"))
        assertTrue(runCatching { session.copy(reportsProgress = true) }.isFailure)
        assertTrue(runCatching { session.copy(prequeueId = "synthetic-other-release") }.isFailure)
        assertTrue(runCatching { session.copy(request = session.request.copy(start = com.strmr.tv.api.StartPoint.Seconds(12.0))) }.isFailure)
        assertTrue(runCatching { session.copy(ownership = PlaybackSessionContext.capture()) }.isFailure)
        source.release()
    }
}
