package com.strmr.tv.player

import com.strmr.tv.api.ApiJson
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.live.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

internal fun queuePart(index:Int)=RecordingPart(index,"2026-11-16T01:00:00Z","2026-11-16T01:02:00Z",4096,"capture_failed","recording:queue/parts/$index")
internal fun queueRecording(parts:List<RecordingPart> = listOf(queuePart(2),queuePart(5)))=Recording("queue","failed",title="Synthetic",capture=RecordingCapture(1,"interrupted",true,1,parts,listOf(RecordingGap("2026-11-16T01:02:00Z","2026-11-16T01:03:00Z","capacity"))))

/** Actual queue/PlayerSession/claim/hand-off adapters; no Activity or decoder runtime is claimed. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingPartQueueTest {
    @Before fun before(){ProfileDataContext.invalidate();RecordingPartQueues.replace(null)}
    @After fun after(){RecordingPartQueues.replace(null);ProfileDataContext.invalidate()}
    private fun setup(recording:Recording=queueRecording(),sink:(RecordingPartCursor)->Unit={}):Pair<RecordingPartQueue,RecordingPartPlayback> {
        val queue=RecordingPartQueue(PlaybackSessionContext.capture(),RecordingPartSnapshot.capture(recording)!!,sink)
        RecordingPartQueues.replace(queue);return queue to queue.start()!!
    }
    private fun session(run:RecordingPartPlayback)=recordingPartSession(run.queue.snapshot.recording,run.part,"https://synthetic.invalid/no-request",run.owner,run)
    @Test fun `selected snapshot sorts original indices without closing or appending active file`() {
        val raw=mutableListOf(queuePart(5),queuePart(2),queuePart(9).copy(endedAt=null,endReason=null))
        val snapshot=RecordingPartSnapshot.capture(queueRecording(raw))!!
        raw.add(queuePart(11));assertEquals(listOf(2,5),snapshot.parts.map {it.index});assertEquals(listOf(2,5),snapshot.recording.capture!!.parts.map {it.index})
    }
    @Test fun `unreadable positive closed part rejects whole queue rather than skipping to later file`() {
        for(part in listOf(queuePart(2).copy(streamPath=null),queuePart(2).copy(streamPath="recording:foreign/parts/2"),queuePart(2).copy(endReason="unrecognized"))) {
            assertNull(RecordingPartSnapshot.capture(queueRecording(listOf(part,queuePart(5)))))
        }
    }
    @Test fun `empty attempts do not renumber a selected positive closed snapshot`() {
        val snapshot=RecordingPartSnapshot.capture(queueRecording(listOf(queuePart(1).copy(bytes=0,streamPath=null),queuePart(2),queuePart(5))))!!
        assertEquals(listOf(2,5),snapshot.parts.map {it.index})
    }
    @Test fun `duplicate original indices and unsupported capture cannot form queue`() {
        assertNull(RecordingPartSnapshot.capture(queueRecording(listOf(queuePart(2),queuePart(2)))))
        assertNull(RecordingPartSnapshot.capture(queueRecording().copy(capture=RecordingCapture())))
        assertNotNull(RecordingPartSnapshot.capture(queueRecording(listOf(queuePart(2)))))
    }
    @Test fun `natural end waits for old decoder release before private handoff can be consumed`() {
        val (_,run)=setup();run.claim();assertTrue(run.ended(12000,false));val next=run.next.value!!
        assertNull(next.take());assertTrue(run.canPlay());run.release(12000)
        val replacement=next.take()!!;assertEquals(5,replacement.part.index);assertEquals(0L,replacement.startPositionMs);assertNull(next.take())
        replacement.claim();assertFalse(run.canPlay())
    }
    @Test fun `actual session guard between ENDED and release preserves normal next receipt`() {
        val (_,run)=setup();val session=session(run);run.claim();run.ended(12000,false);val next=run.next.value!!
        assertTrue(session.playbackAllowed());assertTrue(session.canOpenPlayer())
        run.release(12000);assertNotNull(next.take());assertFalse(session.recordingPartReady())
    }
    @Test fun `controller claim is one shot and a finished run cannot construct another controller`() {
        val (queue,run)=setup();run.claim();assertNull(queue.start())
        try{run.claim();fail("Duplicate controller claim")}catch(_:IllegalStateException){}
        run.ended(10,false)
        try{run.claim();fail("Ended controller claim")}catch(_:IllegalStateException){}
    }
    @Test fun `back or ordinary release cancels rest and keeps local measured cursor only`() {
        val saved=mutableListOf<RecordingPartCursor>();val (_,run)=setup(sink=saved::add)
        run.tick(1234);run.release(1800);assertFalse(run.canPlay());assertFalse(run.ended(2000,false));assertNull(run.next.value)
        assertEquals(1800L,saved.last().localPositionMs);assertFalse(saved.last().ended)
    }
    @Test fun `an error end cancels without writing completion or offering next`() {
        val saved=mutableListOf<RecordingPartCursor>();val (_,run)=setup(sink=saved::add)
        assertFalse(run.ended(12000,true));assertNull(run.next.value);assertTrue(saved.isEmpty());assertFalse(run.canPlay())
    }
    @Test fun `explicit denial cancels a previously offered receipt and future cursor writes`() {
        val saved=mutableListOf<RecordingPartCursor>();val (_,run)=setup(sink=saved::add)
        run.ended(12000,false);val next=run.next.value!!;val count=saved.size
        run.cancel();run.release(13000);run.tick(14000);assertNull(next.take());assertEquals(count,saved.size)
    }
    @Test fun `new playback retires old callback and cannot be cleaned up by old release`() {
        val (_,old)=setup();old.ended(100,false);val next=old.next.value!!
        val (_,fresh)=setup();old.release(100);old.cancel();assertNull(next.take());assertTrue(fresh.canPlay());fresh.tick(10)
    }
    @Test fun `A B A epoch retirement rejects next and delayed cursor publication`() {
        val saved=mutableListOf<RecordingPartCursor>();val (_,run)=setup(sink=saved::add)
        run.ended(12000,false);val next=run.next.value!!;val count=saved.size
        ProfileDataContext.invalidate();ProfileDataContext.invalidate();run.release(12000);run.tick(13000)
        assertNull(next.take());assertFalse(run.canPlay());assertEquals(count,saved.size)
    }
    @Test fun `last selected part completes without a whole recording timeline or additional file`() {
        val saved=mutableListOf<RecordingPartCursor>();val (_,run)=setup(sink=saved::add)
        run.ended(12,false);val second=run.next.value!!;run.release(12);val last=second.take()!!
        assertTrue(last.ended(34,false));assertNull(last.next.value);last.release(34)
        assertEquals(listOf(2,5),saved.map {it.partIndex});assertTrue(saved.all {it.ended});assertFalse(last.canPlay())
    }
    @Test fun `actual part session resumes local position with no history scrobble prequeue or Up Next data`() {
        val snapshot=RecordingPartSnapshot.capture(queueRecording())!!
        val queue=RecordingPartQueue(PlaybackSessionContext.capture(),snapshot);RecordingPartQueues.replace(queue)
        val cursor=RecordingPartCursor("queue",5,9876,snapshot.closedSignature(5)!!,false)
        val run=queue.start(cursor)!!;val session=session(run)
        assertEquals(9.876,session.request.startPosition!!,0.0001);assertTrue(session.request.title.name.endsWith("Part 5"))
        assertFalse(session.reportsProgress);assertNull(session.progressSink);assertNull(session.prequeueId);assertNull(session.growingRestart);assertNull(session.liveSessionId);assertTrue(session.request.following.isEmpty());assertNull(session.request.nextEpisode)
    }
    @Test fun `an ended saved part resumes next original index at local zero`() {
        val snapshot=RecordingPartSnapshot.capture(queueRecording())!!
        val queue=RecordingPartQueue(PlaybackSessionContext.capture(),snapshot);RecordingPartQueues.replace(queue)
        val run=queue.start(RecordingPartCursor("queue",2,12000,snapshot.closedSignature(2)!!,true))!!
        assertEquals(5,run.part.index);assertEquals(0L,run.startPositionMs)
    }
    @Test fun `newly closed sibling preserves selected part resume in a fresh snapshot without autoappend`() {
        val (queue,_)=setup();val cursor=RecordingPartCursor("queue",5,12000,queue.snapshot.closedSignature(5)!!,false)
        val updated=queueRecording(listOf(queuePart(2),queuePart(5),queuePart(8)))
        val snapshot=RecordingPartSnapshot.capture(updated)!!;assertTrue(snapshot.accepts(cursor));assertEquals(listOf(2,5),queue.snapshot.parts.map{it.index})
        val fresh=RecordingPartQueue(queue.owner,snapshot);RecordingPartQueues.replace(fresh)
        val resumed=fresh.start(cursor)!!;assertEquals(5,resumed.part.index);assertEquals(12000L,resumed.startPositionMs)
    }
    @Test fun `deferred stale end cannot prepare replacement after original release and newer playback`() = runTest {
        val (_,run)=setup();val deferred=CompletableDeferred<Unit>();var starts=0
        val reply=launch{withContext(NonCancellable){deferred.await()};if(run.ended(10,false)){run.release(10);if(run.next.value?.take()!=null)starts++}}
        runCurrent();run.release(5);setup();deferred.complete(Unit);reply.join();assertEquals(0,starts)
    }
    @Test fun `capture gap labels are explicit wall clock observations with source reasons`() {
        val gap=queueRecording().capture!!.gaps.single();val text=RecordingCaptureRules.gapLabel(gap)
        assertTrue(text.contains("wall clock"));assertTrue(text.contains("Waiting for capacity"));assertFalse(text.contains("playable duration"))
    }
    @Test fun `actual existing Go multipart reply creates same closed queue and restored reply cannot`() {
        fun recording(name:String):Recording {
            val stream=javaClass.getResourceAsStream("/recording-capture-go/$name") ?: error("Missing actual Go fixture")
            return ApiJson.decodeFromString(stream.use{it.readBytes().decodeToString()})
        }
        val names=javaClass.getResourceAsStream("/recording-capture-go/manifest.json")!!.use {ApiJson.parseToJsonElement(it.readBytes().decodeToString())}.toString()
        // Find actual producer names without inventing a response format or making network calls.
        val directory=ApiJson.parseToJsonElement(names) as kotlinx.serialization.json.JsonObject
        val files=directory["files"]!!.jsonObject.filterValues {it.jsonPrimitive.content!="RecordingList"}.keys
        val decoded=files.map(::recording)
        val multipart=decoded.first {it.id=="multipart"};val restored=decoded.first {it.id=="restored"}
        assertEquals(listOf(1,2),RecordingPartSnapshot.capture(multipart)!!.parts.map {it.index});assertNull(RecordingPartSnapshot.capture(restored))
    }
}
