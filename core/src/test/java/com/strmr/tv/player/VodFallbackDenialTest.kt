package com.strmr.tv.player

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.ui.details.SourceTracks
import com.strmr.tv.ui.details.TrackDefaults
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VodFallbackDenialTest {
    private fun denied()=ApiError.Http(HttpFailure(403,"""{"code":"kids_vod_classification_unknown","error":"private"}"""))
    @Test fun `automatic candidate resolution stops on first authorization rejection`() = runTest {
        val tried=mutableListOf<Int>();val missing=mutableListOf<Int>();var caught:Throwable?=null;val error=denied()
        try {resolveVodFallback(listOf(1,2,3),attempt={id->tried+=id;if(id==1)throw java.io.IOException("missing");if(id==2)throw error;"resolved"},unavailable={missing+=it})}catch(value:Throwable){caught=value}
        assertSame(error,caught);assertEquals(listOf(1,2),tried);assertEquals(listOf(1),missing)
    }
    @Test fun `a missing release still permits another candidate while cancellation stops immediately`() = runTest {
        assertEquals(2 to "ready",resolveVodFallback(listOf(1,2),attempt={if(it==1)throw ApiError.Http(HttpFailure(404,"missing"));"ready"}))
        val tried=mutableListOf<Int>()
        try {resolveVodFallback(listOf(1,2),attempt={tried+=it;throw CancellationException("closed")});fail()}catch(_:CancellationException){}
        assertEquals(listOf(1),tried)
    }
    @Test fun `denied track defaults cancel queued explicit probe before it reads a provider`() = runTest {
        var probes=0;var observed:VodAccessDenial?=null
        val result=SourceResult.from(buildJsonObject {put("title","Synthetic")},0)!!
        val tracks=SourceTracks(backgroundScope,ProfileDataContext.current,readProbe={_,_->probes++;TrackProbe.Tracks(emptyList(),emptyList())},
            readDefaults={throw denied()},log={},onProbeError={_,error->observed=error.vodDenial})
        tracks.open(result);runCurrent();assertEquals(0,probes);assertEquals(VodAccessDenial.CLASSIFICATION_UNKNOWN,observed);assertNull(tracks.openResult);assertNull(tracks.manualFor(result))
        tracks.open(result);runCurrent();assertEquals(0,probes);tracks.dispose()
    }
    @Test fun `up next settings denial cancels prefetch and any future advance`() = runTest {
        val title=DetailsTarget("tmdb:series:41","Synthetic",false)
        val request=PlaybackRequest(title,episode=PlaybackRequest.EpisodeInfo(1,1),following=listOf(PlaybackRequest.EpisodeInfo(1,2)),episodeOrderKnown=true)
        var prepares=0;var advances=0;var denial:VodAccessDenial?=null
        val next=UpNext(request,null,backgroundScope,{null},readSettings={throw denied()},creditsEnabled={false},prepareNext={prepares++},log={},onDenied={denial=it}) {advances++}
        runCurrent();next.prefetchIfNear(95_000,100_000);next.nextEpisode?.let(next::advance)
        assertEquals(VodAccessDenial.CLASSIFICATION_UNKNOWN,denial);assertEquals(0,prepares);assertEquals(0,advances);next.cancel()
    }
    @Test fun `queued up next settings cannot read a replacement profile before its observer runs`() = runTest {
        val request=PlaybackRequest(DetailsTarget("tmdb:series:41","Synthetic",false),episode=PlaybackRequest.EpisodeInfo(1,1),following=listOf(PlaybackRequest.EpisodeInfo(1,2)),episodeOrderKnown=true)
        var reads=0
        val next=UpNext(request,null,backgroundScope,{null},readSettings={reads++;PlaybackSettings()},creditsEnabled={false},prepareNext={},log={}) {}
        val old=ProfileDataContext.current;ProfileDataContext.invalidate();runCurrent();assertEquals(0,reads)
        try {ApiClient.playbackSettings(old);fail()}catch(_:CancellationException){}
        next.cancel()
    }

}
