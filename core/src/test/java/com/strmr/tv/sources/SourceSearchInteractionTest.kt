package com.strmr.tv.sources

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.player.ManualTrackChoice
import com.strmr.tv.ui.details.SourceTracks
import com.strmr.tv.ui.details.TrackDefaults
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises the actual shared Sources state and track controller together, as both UIs do. */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceSearchInteractionTest {
    private class Transport:SourceSearchTransport {
        var searches=0;var pages=0
        override suspend fun search(title:DetailsTarget,episode:Episode?,context:Long):SourceSearchReply {
            searches++;return SourceSearchReply.Supported(filterPage(expiry=filterAt.plusSeconds(1)))
        }
        override suspend fun page(snapshotId:String,filters:SourceFilters,cursor:String?,context:Long):SourceSearchPage {
            pages++;return filterPage(filters,snapshot=snapshotId,view="new-view")
        }
        override suspend fun health(token:String,context:Long)=SourceHealthSnapshot(true)
    }
    @Test fun `fresh loaded file plays once after old view expiry without changing raw resolve payload`() = runTest {
        val transport=Transport()
        val state=SourceSearchState(backgroundScope,filterTitle,null,ProfileDataContext.current,transport,{filterAt.plusMillis(testScheduler.currentTime)},{"synthetic-client"})
        state.reload();runCurrent();val row=state.rows.single();val original=row.result.raw.toString()
        val oldAction=state.selection(row)!!;val intent=state.beginManual(row)!!;var probes=0
        val tracks=SourceTracks(backgroundScope,state.context,readProbe={_,_->probes++;TrackProbe.Tracks(listOf(ProbedAudioTrack(4,"fra","aac")),emptyList())},
            readDefaults={TrackDefaults("fra",null,"off",emptyList())},log={},onProbeChanged=state::probeChanged,onProbeError=state::probeFailed)
        tracks.open(row.result);runCurrent();val loaded=tracks.probeState(row.result)
        tracks.choose(row.result,ManualTrackChoice(audio=4),loaded)
        assertTrue(state.metadataChanged);assertFalse(state.accepts(oldAction));assertTrue(state.acceptsManual(intent))
        advanceTimeBy(1001);runCurrent();assertTrue(state.expiredNow)
        var plays=0
        val play={tracks.play(row.result,loaded){source,choice->state.useManual(intent){owned->
            assertSame(source,owned);assertEquals(4,choice!!.choice.audio);plays++
        }}}
        if(state.acceptsManual(intent))play();if(state.acceptsManual(intent))play()
        assertEquals(1,plays);assertEquals(1,probes);assertEquals(1,transport.searches);assertEquals(0,transport.pages)
        assertEquals(original,row.result.raw.toString());assertEquals(row.result.raw,trackProbeRequestBody(row.result,"adult",false)["result"])
        tracks.dispose();state.dispose()
    }
    @Test fun `scoped probe access failure retires existing panel action and sanitizes server error`() = runTest {
        val transport=Transport();val state=SourceSearchState(backgroundScope,filterTitle,null,ProfileDataContext.current,transport,{filterAt},{"synthetic-client"})
        state.reload();runCurrent();val row=state.rows.single();val intent=state.beginManual(row)!!
        val tracks=SourceTracks(backgroundScope,state.context,readProbe={_,_->throw ApiError.Http(HttpFailure(409,"""{"code":"source_snapshot_context_changed","error":"private path"}"""))},
            readDefaults={TrackDefaults(null,null,"off",emptyList())},log={},onProbeChanged=state::probeChanged,onProbeError=state::probeFailed)
        tracks.open(row.result);runCurrent();assertTrue(state.requiresReload);assertFalse(state.acceptsManual(intent));assertFalse(state.error!!.contains("private"))
        var plays=0;if(state.acceptsManual(intent))tracks.play(row.result,tracks.probeState(row.result)){_,_->plays++}
        assertEquals(0,plays);assertEquals(1,transport.searches);assertEquals(0,transport.pages)
        tracks.dispose();state.dispose()
    }
}
