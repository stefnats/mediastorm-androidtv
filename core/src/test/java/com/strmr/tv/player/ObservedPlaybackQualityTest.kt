package com.strmr.tv.player

import com.strmr.tv.api.ApiJson
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.PlaybackResolution
import com.strmr.tv.api.PrequeueStatus
import com.strmr.tv.api.SourceResult
import com.strmr.tv.api.resolveManualSourceWithQuality
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class ObservedPlaybackQualityTest {
    private val observed = mapOf("resolution" to "720p", "advertisedResolution" to "2160p", "resolutionSource" to "file_probe")
    private val title = DetailsTarget("tmdb:movie:123", "Synthetic", true)
    private fun status() = PrequeueStatus("preparation", "ready", streamPath = "/fixture/movie.mkv",
        selectedResult = PrequeueStatus.SelectedResult("Synthetic.2160p", mapOf("resolution" to "2160p", "raw_title" to "Original.2160p")),
        resultAttributes = observed)

    @Test fun preparedSourceShowsObservedQualityBesideUnchangedProviderClaim() {
        val status = status()
        val resolved = resolvedPrequeueStream(status, null)
        assertEquals("720p", resolved.source!!.attributes["resolution"])
        assertEquals("2160p", status.selectedResult!!.attributes!!["resolution"])
        assertEquals("Original.2160p", status.releaseTitle)
        assertTrue(status.observedQualityLabel!!.contains("File quality: 720p"))
        assertTrue(status.observedQualityLabel!!.contains("Advertised quality: 2160p"))
        val session = PlayerSession(PlaybackRequest(title), "http://fixture/movie", null,
            releaseName = status.releaseTitle, sourceAttributes = resolved.source!!.attributes)
        val rows = streamInfoRows(session, null, null)
        assertTrue(rows.contains("File" to "Original.2160p"))
        assertTrue(rows.contains("File quality" to "720p (file probe)"))
        assertTrue(rows.contains("Advertised quality" to "2160p"))
    }

    @Test fun immediateAndQueuedManualResolveKeepQualityOfTheActualFinalFile() = runTest {
        val result = SourceResult.from(buildJsonObject {
            put("guid", "synthetic-source"); put("title", "Synthetic.2160p")
            put("attributes", buildJsonObject { put("resolution", "2160p") })
        }, 0)!!
        for (queued in listOf(false, true)) {
            var polls = 0
            val final = ApiJson.decodeFromString<PlaybackResolution>("""{"webdavPath":"/fixture/final.mkv","trackSelection":{"audio":{"streamIndex":2,"typeIndex":0,"language":"spa"}},"qualityAttributes":{"resolution":"720p","advertisedResolution":"2160p","resolutionSource":"file_probe"}}""")
            val resolved = resolveManualSourceWithQuality(result, title.titleId, null, "profile", "device", ProfileDataContext.current,
                post = { if (queued) PlaybackResolution(queueId = 7, qualityAttributes = mapOf("resolution" to "1080p")) else final },
                poll = { _, _ -> polls++; final }, pause = {})
            assertEquals(if (queued) 1 else 0, polls)
            assertEquals("/fixture/final.mkv", resolved.path)
            assertEquals(2, resolved.tracks!!.audio!!.streamIndex)
            assertEquals(observed, resolved.qualityAttributes)
            val switch = SourceSwitchRequest(PlaybackRequest(title), result, CarriedTracks(), resolved = resolved)
            assertEquals("720p", switch.resolved!!.qualityAttributes["resolution"])
            assertEquals("2160p", result.attributes["resolution"])
        }
    }

    @Test fun absentProbeDataNeverTurnsAReleaseNameIntoVerifiedQuality() {
        val unverified = status().copy(resultAttributes = null)
        assertNull(unverified.observedQualityLabel)
        val session = PlayerSession(PlaybackRequest(title), "http://fixture/movie", null,
            releaseName = unverified.releaseTitle, sourceAttributes = prequeueSourceHints(unverified).attributes)
        assertTrue(streamInfoRows(session, null, null).none { it.first == "File quality" || it.first == "Advertised quality" })
        assertNull(ApiJson.decodeFromString<PlaybackResolution>("""{"webdavPath":"old.mkv"}""").qualityAttributes)
    }

    @Test fun observedQualityRemainsSpecificToEachPreparedFile() {
        val first = status()
        val second = first.copy(prequeueId = "second", streamPath = "/fixture/other.mkv", resultAttributes = mapOf(
            "resolution" to "1080p", "advertisedResolution" to "2160p", "resolutionSource" to "file_probe"))
        assertEquals("720p", resolvedPrequeueStream(first, null).source!!.attributes["resolution"])
        assertEquals("1080p", resolvedPrequeueStream(second, null).source!!.attributes["resolution"])
        assertEquals("2160p", first.selectedResult!!.attributes!!["resolution"])
        assertEquals("2160p", second.selectedResult!!.attributes!!["resolution"])
    }
}
