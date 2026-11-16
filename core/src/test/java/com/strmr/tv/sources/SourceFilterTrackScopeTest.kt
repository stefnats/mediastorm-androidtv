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

@OptIn(ExperimentalCoroutinesApi::class)
class SourceFilterTrackScopeTest {
    private val raw=filterRow(0).result.raw
    private fun result(scope:String)=SourceResult.from(raw,0,scope)!!
    private fun probe(file:Char)=TrackProbe.Tracks(listOf(ProbedAudioTrack(1,"fra","aac")),emptyList(),MediaEvidence(1,"file_v1_"+file.toString().repeat(64),probed=ProbedMediaFacts("ffprobe")))
    private fun TestScope.tracks(read:suspend (SourceResult,Long)->TrackProbe)=SourceTracks(backgroundScope,ProfileDataContext.current,read,{TrackDefaults("fra",null,"off",emptyList())},log={})
    @Test fun `same raw source and index in a different snapshot cannot inherit probes or manual choices`() = runTest {
        var probes=0;val state=tracks {_,_->probes++;probe('a')};val first=result("snapshot-a");state.open(first);runCurrent();state.choose(first,ManualTrackChoice(audio=1));assertNotNull(state.manualFor(first));state.close()
        val second=result("snapshot-b");state.open(second);runCurrent();assertEquals(2,probes);assertNull(state.manualFor(second));assertFalse(state.hasChoice(second))
    }
    @Test fun `same snapshot raw source retains explicit choice across views when exact file agrees`() = runTest {
        var probes=0;val state=tracks {_,_->probes++;probe('a')};val first=result("snapshot-a");state.open(first);runCurrent();state.choose(first,ManualTrackChoice(audio=1));state.close()
        val nextView=result("snapshot-a");state.reconcile(nextView,"file_v1_"+"a".repeat(64));state.open(nextView);runCurrent();assertEquals(1,probes);assertEquals(1,state.manualFor(nextView)!!.choice.audio)
    }
    @Test fun `new exact file retires old stream positions without automatically probing`() = runTest {
        var probes=0;val state=tracks {_,_->probes++;probe('a')};val first=result("snapshot-a");state.open(first);runCurrent();val old=state.probeState(first);state.choose(first,ManualTrackChoice(audio=1));state.close()
        val next=result("snapshot-a");state.reconcile(next,"file_v1_"+"b".repeat(64));assertNull(state.manualFor(next));assertNull(state.probeState(next).probe);assertEquals(1,probes)
        var plays=0;state.play(first,old){_,_->plays++};assertEquals(0,plays)
    }
    @Test fun `pending late old-file reply cannot replace a newer associated file view`() = runTest {
        val gate=CompletableDeferred<Unit>();var probes=0;val state=tracks {_,_->probes++;withContext(NonCancellable){gate.await()};probe('a')};val first=result("snapshot-a")
        state.open(first);runCurrent();state.reconcile(first,"file_v1_"+"b".repeat(64));gate.complete(Unit);runCurrent();assertNull(state.probeState(first).probe);assertNull(state.openResult);assertEquals(1,probes)
    }
    @Test fun `a file hash with no explicit release association cannot retire another source tracks`() {
        val row=filterRow(0);val unbound=row.copy(mediaEvidence=MediaEvidence(1,"file_v1_"+"a".repeat(64),probed=ProbedMediaFacts("ffprobe")))
        assertNull(unbound.expectedFileVersion)
        assertEquals("file_v1_"+"a".repeat(64),unbound.copy(mediaEvidence=unbound.mediaEvidence!!.copy(candidateVersion=row.candidateVersion)).expectedFileVersion)
    }
    @Test fun `fresh explicit F2 is not rolled back by clicking the same frozen F1 view`() = runTest {
        var probes=0;val state=tracks {_,_->probes++;probe('b')};val row=result("snapshot-a")
        val f1="file_v1_"+"a".repeat(64)
        state.reconcile(row,f1,"view-a");state.open(row);runCurrent()
        state.choose(row,ManualTrackChoice(audio=1));val fresh=state.probeState(row)
        state.reconcile(row,f1,"view-a")
        assertSame(fresh,state.probeState(row));assertEquals(1,state.manualFor(row)!!.choice.audio);assertEquals(1,probes)
        var plays=0;state.play(row,fresh){_,manual->assertEquals(1,manual!!.choice.audio);plays++};assertEquals(1,plays)
        state.reconcile(row,f1,"view-b");assertNull(state.manualFor(row));assertNull(state.probeState(row).probe);assertEquals(1,probes)
    }

}
