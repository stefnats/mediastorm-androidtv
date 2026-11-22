package com.strmr.tv.api

import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.player.FrameRates
import com.strmr.tv.player.prequeueSourceHints
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpstreamManualFrameRateTest {
    private val row = SourceResult.from(buildJsonObject { put("title", "Synthetic"); put("guid", "synthetic") }, 0)!!

    @Test fun finalQueuedFileKeepsQualityTracksAndFrameRateTogether() = runTest {
        val expected = ProfileDataContext.current
        var polls = 0
        val resolved = resolveManualSourceWithQuality(row, "tmdb:movie:41", 12.0, "original-profile", "original-client", expected,
            post = { body ->
                assertEquals("original-profile", body["profileId"]!!.jsonPrimitive.content)
                PlaybackResolution(queueId = 7, qualityAttributes = mapOf("resolution" to "2160p"), frameRate = "60/1")
            },
            poll = { _, query ->
                polls++
                assertEquals("original-profile", query["profileId"])
                assertEquals("original-client", query["clientId"])
                PlaybackResolution(webdavPath = "synthetic/final.mkv", trackSelection = TrackSelection(audio = SelectedTrack(3, 0)),
                    qualityAttributes = mapOf("resolution" to "720p", "resolutionSource" to "file_probe"), frameRate = "24000/1001")
            }, pause = {})
        assertEquals(1, polls)
        assertEquals("720p", resolved.qualityAttributes["resolution"])
        assertEquals(3, resolved.tracks!!.audio!!.streamIndex)
        assertEquals(24000f / 1001f, FrameRates.parse(resolved.frameRate)!!, 0.0001f)
    }

    @Test fun finalReplyCannotAdoptFrameRateAfterOriginalProfileRetires() = runTest {
        val expected = ProfileDataContext.current
        val reply = CompletableDeferred<Unit>()
        var adopted = false
        val job = backgroundScope.launch {
            resolveManualSourceWithQuality(row, "tmdb:movie:41", null, "original", "device", expected,
                post = { PlaybackResolution(queueId = 7) },
                poll = { _, _ ->
                    withContext(NonCancellable) { reply.await() }
                    PlaybackResolution(webdavPath = "synthetic/stale.mkv", frameRate = "25/1")
                }, pause = {})
            adopted = true
        }
        runCurrent()
        ProfileDataContext.invalidate()
        reply.complete(Unit)
        runCurrent()
        assertTrue(job.isCancelled)
        assertFalse(adopted)
    }

    @Test fun prequeueProbeIsAdditiveAndDoesNotReplaceObservedQuality() {
        val status = PrequeueStatus("original-preparation", "ready", frameRate = "30000/1001",
            resultAttributes = mapOf("resolution" to "720p", "resolutionSource" to "file_probe"))
        val hints = prequeueSourceHints(status)
        assertEquals(30000f / 1001f, hints.frameRate!!, 0.0001f)
        assertEquals("720p", hints.attributes["resolution"])
        assertNull(prequeueSourceHints(status.copy(frameRate = "0/0")).frameRate)
    }
}
