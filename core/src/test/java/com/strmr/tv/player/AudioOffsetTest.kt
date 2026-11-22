package com.strmr.tv.player

import androidx.media3.exoplayer.audio.AudioSink
import com.strmr.tv.api.DetailsTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class AudioOffsetTest {
    @Test fun positiveOffsetReportsLaterPositionSoAudioIsHeardLater() {
        // Video shows the frame whose timestamp equals the clock. With +100 ms, when the audio
        // sample at 5 s plays the clock says 5.1 s: the picture is 100 ms ahead, audio is late.
        assertEquals(5_100_000L, AudioOffset.adjust(5_000_000L, 100))
        assertEquals(4_900_000L, AudioOffset.adjust(5_000_000L, -100))
    }

    @Test fun clampsToTwoSeconds() {
        assertEquals(2000, AudioOffset.clamp(5000))
        assertEquals(-2000, AudioOffset.clamp(-5000))
        assertEquals(7_000_000L, AudioOffset.adjust(5_000_000L, 9999))
    }

    @Test fun notSetPassesThroughAndNeverNegative() {
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, AudioOffset.adjust(AudioSink.CURRENT_POSITION_NOT_SET, 500))
        assertEquals(0L, AudioOffset.adjust(100_000L, -500))
        assertEquals(123L, AudioOffset.adjust(123L, 0))
    }

    @Test fun labels() {
        assertEquals("0 ms", AudioOffset.label(0))
        assertEquals("+150 ms", AudioOffset.label(150))
        assertEquals("−200 ms", AudioOffset.label(-200))
    }

    private class FakePrefs : AudioOffsetStore.Persistence {
        var text: String? = null
        override fun read() = text
        override fun write(value: String) { text = value }
    }

    private fun freshStore(): FakePrefs {
        val prefs = FakePrefs()
        AudioOffsetStore.persistence = prefs
        AudioOffsetStore.clearCache()
        return prefs
    }

    @After fun tearDown() {
        AudioOffsetStore.clearCache()
        AudioOffsetStore.persistence = null
    }

    @Test fun storeStepsClampsAndSinkFollowsLive() {
        freshStore()
        AudioOffsetStore.activate("live:1")
        val real = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, m, _ ->
            if (m.name == "getCurrentPositionUs") 10_000_000L else null
        } as AudioSink
        val sink = AudioOffsetSink(real) { AudioOffsetStore.currentMs }
        assertEquals(10_000_000L, sink.getCurrentPositionUs(false))
        AudioOffsetStore.step(AudioOffset.STEP_MS)
        AudioOffsetStore.step(AudioOffset.STEP_MS)
        assertEquals(100, AudioOffsetStore.currentMs)
        assertEquals(100, AudioOffsetStore.offsetMs)
        assertEquals(10_100_000L, sink.getCurrentPositionUs(false))
        AudioOffsetStore.set(-3000)
        assertEquals(-2000, AudioOffsetStore.currentMs)
        assertEquals(8_000_000L, sink.getCurrentPositionUs(false))
    }

    @Test fun keys() {
        val movie = PlaybackRequest(DetailsTarget("tmdb:movie:603", "The Matrix", isMovie = true))
        assertEquals("vod:tmdb:movie:603", AudioOffsetKeys.vod(movie))
        val ep = PlaybackRequest(DetailsTarget("tmdb:tv:1", "Show", isMovie = false), PlaybackRequest.EpisodeInfo(1, 2))
        assertEquals("vod:tmdb:tv:1:S01E02", AudioOffsetKeys.vod(ep))
        val ep2 = ep.copy(episode = PlaybackRequest.EpisodeInfo(1, 2, itemId = "srv-ep-9"))
        assertEquals("vod:srv-ep-9", AudioOffsetKeys.vod(ep2))
        // Another source of the same title (new URL) keeps the key.
        assertEquals(AudioOffsetKeys.of(PlayerSession(movie, "http://a/1", null)), AudioOffsetKeys.of(PlayerSession(movie, "http://b/2", null)))
        assertEquals("live:ch7", AudioOffsetKeys.live("ch7"))
        assertEquals("rec:r1", AudioOffsetKeys.recording("r1"))
        // Catch-up / recordings set their key explicitly and report no progress.
        val catchup = PlayerSession(PlaybackRequest(DetailsTarget("catchup:ch7:123", "P", isMovie = true)), "u", null,
                                    reportsProgress = false, audioOffsetKey = AudioOffsetKeys.live("ch7"))
        assertEquals("live:ch7", AudioOffsetKeys.of(catchup))
        assertNull(AudioOffsetKeys.of(PlayerSession(PlaybackRequest(DetailsTarget("trailer", "T", isMovie = true)), "u", null, reportsProgress = false)))
    }

    @Test fun savesPerKeyAndResetRemoves() {
        val prefs = freshStore()
        AudioOffsetStore.activate("vod:a")
        AudioOffsetStore.set(150)
        AudioOffsetStore.activate("vod:b")
        assertEquals(0, AudioOffsetStore.currentMs)
        AudioOffsetStore.set(-50)
        // A new process reads what was written.
        AudioOffsetStore.clearCache()
        AudioOffsetStore.activate("vod:a")
        assertEquals(150, AudioOffsetStore.currentMs)
        AudioOffsetStore.reset()
        assertEquals(0, AudioOffsetStore.currentMs)
        assertTrue(prefs.text!!.contains("vod:b"))
        assertFalse(prefs.text!!.contains("vod:a"))
    }

    @Test fun retuneToAnotherChannelLoadsItsValue() {
        freshStore()
        AudioOffsetStore.activate(AudioOffsetKeys.live("1"))
        AudioOffsetStore.set(200)
        AudioOffsetStore.activate(AudioOffsetKeys.live("2"))
        assertEquals(0, AudioOffsetStore.currentMs)
        AudioOffsetStore.set(-100)
        AudioOffsetStore.activate(AudioOffsetKeys.live("1"))
        assertEquals(200, AudioOffsetStore.currentMs)
    }

    @Test fun capDropsOldest() {
        val m = AudioOffsetMemory(cap = 3)
        m.put("a", 50); m.put("b", 100); m.put("c", 150)
        m.put("a", 75) // re-set moves a to newest
        m.put("d", 200)
        assertEquals(listOf("c", "a", "d"), m.entries.keys.toList())
        assertEquals(0, m["b"])
        m.put("c", 0)
        assertEquals(listOf("a", "d"), m.entries.keys.toList())
        assertEquals(AudioOffsetMemory.CAP, 500)
    }
}
