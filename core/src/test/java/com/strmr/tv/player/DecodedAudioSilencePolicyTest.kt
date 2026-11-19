package com.strmr.tv.player

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class DecodedAudioSilencePolicyTest {
    private val group = Any()
    private fun playing(now: Long) = DecodedAudioPlayback(now, now + 10_000, 600_000,
        true, true, false, true, group, 0, true, 1f, 1f)
    private fun zeros(now: Long) = DecodedAudioSnapshot(1, 8_000, now * 8, now * 8, 0, now)

    @Test fun needsSettleThenFortyFiveSecondsOfBothNewPcmAndCurrentProgressOnlyOnce() {
        val policy = DecodedAudioSilencePolicy()
        for (now in 0L..49_000L step 1_000) assertFalse(policy.observe(zeros(now), playing(now)))
        assertTrue(policy.observe(zeros(50_000), playing(50_000)))
        for (now in 51_000L..110_000L step 1_000) assertFalse(policy.observe(zeros(now), playing(now)))
    }

    @Test fun prebufferedFramesOrStaleInputAloneCannotSatisfyTheWindow() {
        for (stale in listOf(false, true)) {
            val policy = DecodedAudioSilencePolicy()
            for (now in 0L..90_000L step 1_000) {
                val sample = zeros(now).copy(frames = 960_000, zeroFrames = 960_000, lastBufferMs = if (stale) 0 else now)
                assertFalse(policy.observe(sample, playing(now)))
            }
        }
    }

    @Test fun ineligibleMutedPausedBufferingUnknownDurationDisabledOrNonstandardPlaybackNeverOffers() {
        val variants: List<(DecodedAudioPlayback) -> DecodedAudioPlayback> = listOf(
            { it.copy(eligible = false) }, { it.copy(playing = false) }, { it.copy(buffering = true) },
            { it.copy(videoSelected = false) }, { it.copy(audioGroup = null) }, { it.copy(audioIndex = -1) },
            { it.copy(audioEnabled = false) }, { it.copy(volume = 0f) }, { it.copy(volume = Float.NaN) },
            { it.copy(speed = 1.5f) }, { it.copy(durationMs = 0) }, { it.copy(durationMs = it.positionMs + 59_999) },
        )
        for (change in variants) {
            val policy = DecodedAudioSilencePolicy()
            for (now in 0L..90_000L step 1_000) assertFalse(policy.observe(zeros(now), change(playing(now))))
        }
    }

    @Test fun allTrackFormatContentAndClockReplacementsRestartTheOriginalObservation() {
        data class EqualTrack(val value: Int)
        val original = EqualTrack(1)
        val replacement = EqualTrack(1)
        for (change in listOf("group", "index", "generation", "content", "seek", "pause", "clock")) {
            val policy = DecodedAudioSilencePolicy()
            for (now in 0L..39_000L step 1_000) assertFalse(policy.observe(zeros(now), playing(now).copy(audioGroup = original)))
            val sample = zeros(40_000).copy(generation = if (change == "generation") 2 else 1, breaks = if (change == "content") 1 else 0)
            val value = playing(40_000).copy(audioGroup = if (change == "group") replacement else original,
                audioIndex = if (change == "index") 1 else 0, positionMs = if (change == "seek") 200_000 else 50_000,
                playing = change != "pause", nowMs = if (change == "clock") 1 else 40_000)
            assertFalse(policy.observe(sample, value))
            for (now in 41_000L..50_000L step 1_000) assertFalse(policy.observe(zeros(now), playing(now).copy(audioGroup = original)))
        }
    }

    @Test fun resetAndStalledVideoRequireAnEntireNewProgressInterval() {
        val policy = DecodedAudioSilencePolicy()
        for (now in 0L..39_000L step 1_000) assertFalse(policy.observe(zeros(now), playing(now)))
        policy.reset()
        for (now in 40_000L..89_000L step 1_000) assertFalse(policy.observe(zeros(now), playing(now)))
        assertTrue(policy.observe(zeros(90_000), playing(90_000)))
        val stalled = DecodedAudioSilencePolicy()
        for (now in 0L..90_000L step 1_000) assertFalse(stalled.observe(zeros(now), playing(now).copy(positionMs = 10_000)))
    }

    @Test fun actualMeterFramesAndCurrentClockReachTheBoundaryTogether() {
        val meter = DecodedAudioObservation().apply { configure(8_000, 2, ObservedPcmEncoding.PCM16) }
        val policy = DecodedAudioSilencePolicy()
        val second = ByteBuffer.allocate(8_000 * 4)
        for (now in 0L..50_000L step 1_000) {
            meter.consumed(second, 0, second.limit(), meter.token(), now)
            assertEquals(now == 50_000L, policy.observe(meter.snapshot(), playing(now)))
        }
    }

    @Test fun oneRealNonzeroFrameRestartsBothDecodedAndActiveObservationWindows() {
        val meter = DecodedAudioObservation().apply { configure(8_000, 1, ObservedPcmEncoding.PCM16) }
        val policy = DecodedAudioSilencePolicy()
        val second = ByteBuffer.allocate(8_000 * 2)
        for (now in 0L..90_000L step 1_000) {
            second.put(0, (if (now == 40_000L) 1 else 0).toByte())
            meter.consumed(second, 0, second.limit(), meter.token(), now)
            assertEquals(now == 90_000L, policy.observe(meter.snapshot(), playing(now)))
        }
    }
}
