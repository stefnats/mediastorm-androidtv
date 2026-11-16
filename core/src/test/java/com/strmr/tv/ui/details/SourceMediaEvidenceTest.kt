package com.strmr.tv.ui.details

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.player.ManualTrackChoice
import com.strmr.tv.ui.runInMediaContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourceMediaEvidenceTest {
    private fun source(id: String, index: Int = 0) = SourceResult.from(buildJsonObject {put("title","Synthetic $id");put("guid",id);put("serviceType","usenet")},index)!!
    private fun probe(version: Char) = TrackProbe.Tracks(listOf(ProbedAudioTrack(1,"fra","aac")),emptyList(),
        MediaEvidence(1,"file_v1_"+version.toString().repeat(64),probed=ProbedMediaFacts("ffprobe","2026-11-16T11:59:00Z","2026-11-16T12:01:00Z")))
    private inner class Fixture(val test: TestScope) {
        val expected = ProfileDataContext.current
        val requests = mutableListOf<Pair<SourceResult,Long>>()
        var defaults = 0
        var load: suspend (SourceResult,Long) -> TrackProbe = { _, _ -> probe('a') }
        val state=SourceTracks(test.backgroundScope,expected,
            { result,context -> requests += result to context;load(result,context) },
            { context -> assertEquals(expected,context);defaults++;TrackDefaults("fra","spa","on",emptyList()) },{})
    }
    @Test fun `opening display surfaces and reading unknown metadata never probes`() = runTest {
        val f=Fixture(this);val source=source("first")
        assertNull(f.state.openResult)
        assertNull(f.state.probeState(source).probe)
        assertNull(f.state.manualFor(source));assertFalse(f.state.hasChoice(source));runCurrent()
        assertTrue(f.requests.isEmpty());assertEquals(0,f.defaults)
        f.state.dispose()
    }
    @Test fun `only explicit open starts the existing request once and preserves returned exact version`() = runTest {
        val f=Fixture(this);val source=source("first")
        f.state.open(source);runCurrent()
        val actual=f.state.probeState(source).probe as TrackProbe.Tracks
        assertEquals("file_v1_"+"a".repeat(64),actual.mediaEvidence!!.sourceVersion)
        assertEquals(listOf(source to f.expected),f.requests);assertEquals(1,f.defaults)
        f.state.close();f.state.open(source);runCurrent();assertEquals(1,f.requests.size)
        f.state.dispose()
    }
    @Test fun `same search index from another result cannot reuse facts or track choice`() = runTest {
        val f=Fixture(this);val first=source("first");val second=source("second")
        f.load={ result,_ -> probe(if(result.id=="first") 'a' else 'b') }
        f.state.open(first);runCurrent();f.state.choose(first,ManualTrackChoice(audio=1))
        assertTrue(f.state.hasChoice(first))
        assertFalse(f.state.hasChoice(second));assertNull(f.state.probeState(second).probe)
        f.state.open(second);runCurrent()
        assertEquals("file_v1_"+"b".repeat(64),(f.state.probeState(second).probe as TrackProbe.Tracks).mediaEvidence!!.sourceVersion)
        assertFalse(f.state.hasChoice(second));assertEquals(2,f.requests.size)
        f.state.dispose()
    }
    @Test fun `older retry result cannot overwrite current exact version or retain obsolete choice`() = runTest {
        val f=Fixture(this);val source=source("first");val old=CompletableDeferred<TrackProbe>();var attempt=0
        f.load={ _,_->if(++attempt==1)withContext(NonCancellable){old.await()}else probe('b') }
        f.state.open(source);runCurrent();f.state.retry(source);runCurrent()
        f.state.choose(source,ManualTrackChoice(audio=1));old.complete(probe('a'));runCurrent()
        assertEquals("file_v1_"+"b".repeat(64),(f.state.probeState(source).probe as TrackProbe.Tracks).mediaEvidence!!.sourceVersion)
        assertEquals(2,f.requests.size);assertTrue(f.state.hasChoice(source));f.state.dispose()
    }
    @Test fun `explicit retry retires prior file track choice even before new probe returns`() = runTest {
        val f=Fixture(this);val source=source("first");val next=CompletableDeferred<TrackProbe>();var attempt=0
        f.load={_,_->if(++attempt==1)probe('a')else next.await()}
        f.state.open(source);runCurrent();f.state.choose(source,ManualTrackChoice(audio=1));assertTrue(f.state.hasChoice(source))
        f.state.retry(source);assertFalse(f.state.hasChoice(source));assertNull(f.state.manualFor(source))
        next.complete(probe('b'));runCurrent();assertFalse(f.state.hasChoice(source));f.state.dispose()
    }
    @Test fun `stale loaded panel choice retry and Play callbacks cannot act on replacement file response`() = runTest {
        for (legacy in listOf(false,true)) {
            val f=Fixture(this);val source=source("same-candidate");var attempt=0
            f.load={_,_->
                val result=if(++attempt==1)probe('a')else probe('b').copy(audio=listOf(ProbedAudioTrack(2,"spa","aac")))
                if(legacy) result.copy(mediaEvidence=null) else result
            }
            f.state.open(source);runCurrent();val old=f.state.probeState(source)
            f.state.choose(source,ManualTrackChoice(audio=1),old)
            f.state.retry(source,old);runCurrent();val latest=f.state.probeState(source)
            f.state.choose(source,ManualTrackChoice(audio=1),old)
            assertFalse(f.state.hasChoice(source));assertNull(f.state.manualFor(source))
            f.state.retry(source,old);runCurrent();assertEquals(2,f.requests.size)
            var plays=0
            f.state.play(source,old){_,_->plays++}
            assertEquals(0,plays);assertSame(source,f.state.openResult)
            f.state.choose(source,ManualTrackChoice(audio=2),latest)
            f.state.play(source,latest){_,manual->plays++;assertEquals(2,manual!!.choice.audio)}
            assertEquals(1,plays);assertNull(f.state.openResult);f.state.dispose()
        }
    }
    @Test fun `even identical file hash and track data after retry retire older displayed callback`() = runTest {
        val f=Fixture(this);val source=source("same-file")
        f.state.open(source);runCurrent();val old=f.state.probeState(source)
        f.state.retry(source,old);runCurrent();val latest=f.state.probeState(source)
        assertNotSame(old,latest)
        var plays=0;f.state.play(source,old){_,_->plays++};assertEquals(0,plays)
        f.state.play(source,latest){_,_->plays++};assertEquals(1,plays);f.state.dispose()
    }
    @Test fun `closed panel and late masked response do not republish old facts`() = runTest {
        val f=Fixture(this);val source=source("first");val late=CompletableDeferred<TrackProbe>();var attempt=0
        f.load={_,_->if(++attempt==1)withContext(NonCancellable){late.await()}else probe('b')}
        f.state.open(source);runCurrent();f.state.close();late.complete(probe('a'));runCurrent()
        assertNull(f.state.openResult);assertNull(f.state.probeState(source).probe)
        f.state.open(source);runCurrent()
        assertEquals("file_v1_"+"b".repeat(64),(f.state.probeState(source).probe as TrackProbe.Tracks).mediaEvidence!!.sourceVersion)
        f.state.dispose()
    }
    @Test fun `profile or server replacement clears facts and blocks old page actions`() = runTest {
        val f=Fixture(this);val source=source("first")
        f.state.open(source);runCurrent();ProfileDataContext.invalidate();runCurrent()
        assertNull(f.state.openResult);assertNull(f.state.manualFor(source));assertFalse(f.state.hasChoice(source))
        assertFalse((f.state.probeState(source).probe as? TrackProbe.Tracks)!=null)
        f.state.open(source);f.state.retry(source);runCurrent();assertEquals(1,f.requests.size)
        f.state.dispose()
    }
    @Test fun `context replacement before scheduled coroutines does not request replacement profile defaults or probe`() = runTest {
        val f=Fixture(this);f.state.open(source("first"));ProfileDataContext.invalidate();runCurrent()
        assertEquals(0,f.defaults);assertTrue(f.requests.isEmpty());f.state.dispose()
    }
    @Test fun `page disposal rejects late probe and later retry without starting work`() = runTest {
        val f=Fixture(this);val source=source("first");val late=CompletableDeferred<TrackProbe>()
        f.load={_,_->withContext(NonCancellable){late.await()}}
        f.state.open(source);runCurrent();f.state.dispose();late.complete(probe('a'));runCurrent()
        assertNull(f.state.openResult);assertNull(f.state.manualFor(source));f.state.retry(source);f.state.open(source);runCurrent()
        assertEquals(1,f.requests.size)
    }
    @Test fun `transport error does not expose arbitrary provider paths as new display text`() = runTest {
        val f=Fixture(this);val source=source("first")
        f.load={_,_->throw java.io.IOException("https://private.invalid/synthetic")}
        f.state.open(source);runCurrent()
        assertEquals("Track metadata could not be loaded. Try again.",(f.state.probeState(source).probe as TrackProbe.Failed).reason)
        f.state.dispose()
    }
    @Test fun `loaded manual choices remain available when panel closes for Play`() = runTest {
        val f=Fixture(this);val source=source("first")
        f.state.open(source);runCurrent();f.state.choose(source,ManualTrackChoice(audio=1));f.state.close()
        assertEquals(1,f.state.manualFor(source)!!.choice.audio);f.state.dispose()
    }
    @Test fun `real old-context source and settings APIs reject before any request IO`() = runTest {
        val expected=ProfileDataContext.current;ProfileDataContext.invalidate()
        suspend fun cancelled(block:suspend()->Unit){try{block();fail()}catch(_:CancellationException){}}
        cancelled {ApiClient.probeTracks(source("first"),expected)}
        cancelled {ApiClient.searchSources("Synthetic",DetailsTarget("tmdb:movie:41","Synthetic",true),expectedContext=expected)}
        cancelled {ApiClient.sourceHealth("synthetic-token",expected)}
        cancelled {ApiClient.playbackSettings(expected)}
    }
    @Test fun `late source button callbacks never apply to replacement profile`() {
        val expected=ProfileDataContext.current;var actions=0
        runInMediaContext(expected){actions++};assertEquals(1,actions)
        ProfileDataContext.invalidate();runInMediaContext(expected){actions++};assertEquals(1,actions)
    }
}
