package com.strmr.tv.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.PrequeueStatus
import com.strmr.tv.api.SelectedTrack
import com.strmr.tv.api.TrackSelection
import org.junit.Assert.*
import org.junit.Test

class ObservedCodecHdrRowsTest {
    private val title = DetailsTarget("tmdb:movie:1", "Synthetic", true)
    private val attributes = mapOf("codec" to "h264", "codecSource" to "file_probe", "advertisedCodec" to "HEVC",
        "audio" to "aac", "audioSource" to "file_probe", "audioStreamIndex" to "3", "advertisedAudio" to "TrueHD Atmos",
        "hdr" to "HDR10", "hdrSource" to "file_probe", "advertisedHDR" to "DV", "hasDV" to "false", "advertisedHasDV" to "true")

    @Test fun fileAndClaimComparisonDoesNotDependOnResolutionProbe() {
        val status = PrequeueStatus("preparation", "ready", streamPath = "/fixture/video.mkv", resultAttributes = attributes,
            selectedResult = PrequeueStatus.SelectedResult("Synthetic.HEVC.DV.TrueHD", mapOf("codec" to "HEVC", "hdr" to "DV")))
        val resolved = resolvedPrequeueStream(status, null)
        val session = PlayerSession(PlaybackRequest(title), "http://fixture/video", null, sourceAttributes = resolved.source!!.attributes)
        val rows = streamInfoRows(session, null, null)
        assertTrue(rows.contains("File video codec" to "H.264 (file probe)"))
        assertTrue(rows.contains("Advertised video codec" to "HEVC"))
        assertTrue(rows.contains("File audio (prepared track)" to "AAC · container stream 3 (file probe)"))
        assertTrue(rows.contains("Advertised audio" to "TrueHD Atmos"))
        assertTrue(rows.contains("File dynamic range" to "HDR10 (file probe)"))
        assertTrue(rows.contains("Advertised dynamic range" to "DV"))
        assertTrue(rows.contains("File Dolby Vision" to "No (file probe)"))
        assertTrue(rows.contains("Advertised Dolby Vision" to "true"))
        assertEquals("HEVC", status.selectedResult!!.attributes!!["codec"])
        assertEquals("DV", status.selectedResult!!.attributes!!["hdr"])
    }

    @Test fun unknownFactsAndUnselectedAggregateAudioDoNotBecomeFileClaims() {
        assertTrue(observedQualityRows(mapOf("codec" to "HEVC", "audio" to "TrueHD", "hdr" to "DV", "hasDV" to "true")).isEmpty())
        for (index in listOf(null, "-1", "unknown")) {
            val fields = mutableMapOf("codecSource" to "file_probe", "codec" to "unknown", "audioSource" to "file_probe",
                "audio" to "aac", "advertisedAudio" to "TrueHD", "hdrSource" to "file_probe", "hasDV" to "false")
            index?.let { fields["audioStreamIndex"] = it }
            assertTrue(observedQualityRows(fields).isEmpty())
        }
        assertTrue(observedQualityRows(mapOf("hdrSource" to "file_probe", "hdr" to "unknown", "hasDV" to "false")).isEmpty())
    }

    @Test fun explicitSdrAndDolbyVisionAreShownOnlyAsActualObservedValues() {
        val sdr = observedQualityRows(mapOf("hdrSource" to "file_probe", "hdr" to "SDR", "hasDV" to "false", "advertisedHDR" to "HDR10"))
        assertTrue(sdr.contains("File dynamic range" to "SDR (file probe)"))
        assertTrue(sdr.contains("Advertised dynamic range" to "HDR10"))
        val dv = observedQualityRows(mapOf("hdrSource" to "file_probe", "hdr" to "DV", "hasDV" to "true"))
        assertTrue(dv.contains("File dynamic range" to "Dolby Vision (file probe)"))
        assertTrue(dv.contains("File Dolby Vision" to "Yes (file probe)"))
        assertTrue(observedQualityRows(mapOf("hasDV" to "false")).none { it.second.contains("SDR") })
    }

    @Test fun decoderRowsRemainSeparateFromTheContainerTrackPreparedEarlier() {
        val video = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setWidth(1920).setHeight(1080).build()
        val audio = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_TRUEHD).setChannelCount(8).build()
        val session = PlayerSession(PlaybackRequest(title), "http://fixture/video",
            TrackSelection(audio = SelectedTrack(8, 1)), sourceAttributes = attributes)
        val rows = streamInfoRows(session, video, audio)
        assertTrue(rows.first { it.first == "Video" }.second.contains("HEVC"))
        assertTrue(rows.first { it.first == "Audio" }.second.contains("TrueHD"))
        assertTrue(rows.contains("File video codec" to "H.264 (file probe)"))
        assertTrue(rows.contains("File audio (prepared track)" to "AAC · container stream 3 (file probe)"))
        assertEquals(8, session.tracks!!.audio!!.streamIndex)
    }

    @Test fun compactDetailsCaptionStaysBoundedWhileInfoShowsFullComparison() {
        val status = PrequeueStatus("preparation", "ready", resultAttributes = attributes + mapOf(
            "resolution" to "720p", "resolutionSource" to "file_probe", "advertisedResolution" to "2160p"))
        assertTrue(status.observedQualityLabel!!.contains("720p"))
        assertTrue(status.observedQualityLabel!!.contains("2160p"))
        assertFalse(status.observedQualityLabel!!.contains("video codec"))
        assertFalse(status.observedQualityLabel!!.contains("prepared track"))
        assertTrue(observedQualityRows(status.resultAttributes!!).any { it.first == "File video codec" })
    }
}
