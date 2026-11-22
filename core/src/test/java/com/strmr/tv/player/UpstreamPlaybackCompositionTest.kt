package com.strmr.tv.player

import androidx.media3.exoplayer.audio.AudioSink
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/** The display gate and per-stream sink share the fork's secondary-player lifecycle. */
class UpstreamPlaybackCompositionTest {
    private class Preferences : AudioOffsetStore.Persistence {
        var text: String? = null
        override fun read() = text
        override fun write(value: String) { text = value }
    }

    private fun resetStore() {
        AudioOffsetStore.persistence = Preferences()
        AudioOffsetStore.clearCache()
    }

    @After fun closeStore() {
        AudioOffsetStore.clearCache()
        AudioOffsetStore.persistence = null
    }

    private fun sink(key: String?): AudioSink {
        val delegate = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, _ ->
            if (method.name == "getCurrentPositionUs") 10_000_000L else null
        } as AudioSink
        return AudioOffsetSink(delegate) { AudioOffsetStore.value(key) }
    }

    @Test fun standbySinkCannotReplaceActiveOffsetSelection() {
        resetStore()
        AudioOffsetStore.activate("vod:secondary")
        AudioOffsetStore.set(-200)
        AudioOffsetStore.activate("vod:primary")
        AudioOffsetStore.set(150)
        val primary = sink("vod:primary")
        val secondary = sink("vod:secondary")
        assertEquals(10_150_000L, primary.getCurrentPositionUs(false))
        assertEquals(9_800_000L, secondary.getCurrentPositionUs(false))
        assertEquals("vod:primary", AudioOffsetStore.key)
        AudioOffsetStore.step(50)
        assertEquals(10_200_000L, primary.getCurrentPositionUs(false))
        assertEquals(9_800_000L, secondary.getCurrentPositionUs(false))
        AudioOffsetStore.activate("vod:secondary")
        AudioOffsetStore.step(50)
        assertEquals(9_850_000L, secondary.getCurrentPositionUs(false))
        assertEquals(10_200_000L, primary.getCurrentPositionUs(false))
    }

    @Test fun temporaryUnkeyedPlaybackOffsetIsNotSavedToAnotherStream() {
        resetStore()
        AudioOffsetStore.activate(null)
        AudioOffsetStore.set(100)
        assertEquals(10_100_000L, sink(null).getCurrentPositionUs(false))
        AudioOffsetStore.activate("live:one")
        assertEquals(10_000_000L, sink("live:one").getCurrentPositionUs(false))
    }

    @Test fun displayAndRecordingCanBecomeReadyInEitherOrder() {
        for (displayFirst in listOf(false, true)) {
            var recordingReady = false
            var starts = 0
            val barrier = PlaybackStartBarrier({ recordingReady }, { starts++ })
            if (displayFirst) barrier.releaseDisplay()
            assertEquals(0, starts)
            recordingReady = true
            barrier.startIfAllowed()
            if (!displayFirst) assertEquals(0, starts)
            barrier.releaseDisplay()
            barrier.startIfAllowed()
            assertEquals(1, starts)
        }
    }

    @Test fun delayedDisplayCallbackCannotRestartRetiredOrStandbyPlayback() {
        var current = true
        var standby = true
        var starts = 0
        val barrier = PlaybackStartBarrier({ current && !standby }, { starts++ })
        barrier.releaseDisplay()
        assertEquals(0, starts)
        current = false
        standby = false
        barrier.startIfAllowed()
        barrier.releaseDisplay()
        assertEquals(0, starts)
    }

    @Test fun promotionCanReleaseOneAlreadyPreparedStandbyStart() {
        var standby = true
        var starts = 0
        val barrier = PlaybackStartBarrier({ !standby }, { starts++ })
        barrier.releaseDisplay()
        assertEquals(0, starts)
        standby = false
        barrier.startIfAllowed()
        barrier.releaseDisplay()
        barrier.startIfAllowed()
        assertEquals(1, starts)
    }
}
