package com.strmr.tv.ui.recordings

import com.strmr.tv.live.*
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingPartsStateTest {
    private val p=RecordingPart(1,"2026-11-16T01:00:00Z","2026-11-16T01:02:00Z",1024,"source_ended","recording:r1/parts/1")
    private fun recording(id:String="r1")=Recording(id,"failed",outputPath="part1.ts",capture=RecordingCapture(1,"interrupted",true,0,listOf(p.copy(streamPath="recording:$id/parts/1")),emptyList()))
    private class Access:RecordingsAccess {
        var items=listOf<Recording>()
        var read:suspend ()->List<Recording> = {items}
        val calls=mutableListOf<Pair<String,Long>>()
        var deleting:suspend ()->Unit={}
        override suspend fun recordings(context:Long):List<Recording> {calls+="recordings" to context;return read()}
        override suspend fun rules(context:Long):List<SeriesRule> {calls+="rules" to context;return emptyList()}
        override suspend fun plan(context:Long):RecordingPlan? {calls+="plan" to context;return null}
        override suspend fun cancel(id:String,context:Long) {calls+="cancel" to context}
        override suspend fun delete(id:String,context:Long) {calls+="delete" to context;deleting()}
        override suspend fun deleteRule(id:String,context:Long) {calls+="deleteRule" to context}
    }
    @Test fun `current owned closed part is deliberately played with captured profile`() = runTest {
        val access=Access().apply {items=listOf(recording())};val plays=mutableListOf<Triple<Recording,RecordingPart?,Long>>()
        val state=RecordingsState(backgroundScope,ProfileDataContext.current,access,log={_,_->},notify={}) {r,part,context->plays+=Triple(r,part,context)}
        state.load();val action=state.partAction(state.selected!!,p)!!;assertTrue(plays.isEmpty());state.playPart(action)
        assertEquals(Triple(recording(),p,state.context),plays.single());assertEquals(listOf("recordings","rules","plan"),access.calls.map {it.first})
    }
    @Test fun `active unknown foreign and unselected parts do not create actions`() = runTest {
        val access=Access().apply {items=listOf(recording(),recording("other"))};val state=RecordingsState(backgroundScope,ProfileDataContext.current,access,log={_,_->},notify={}){_,_,_->fail("Unexpected playback")};state.load()
        assertNull(state.partAction(recording(),p.copy(endedAt=null)));assertNull(state.partAction(recording("other"),recording("other").capture!!.parts.single()));assertNull(state.partAction(recording("foreign"),p))
    }
    @Test fun `refresh invalidates captured button generation without automatically starting a part`() = runTest {
        val access=Access().apply {items=listOf(recording())};var plays=0;val state=RecordingsState(backgroundScope,ProfileDataContext.current,access,log={_,_->},notify={}){_,_,_->plays++}
        state.load();val old=state.partAction(recording(),p)!!;state.load(quiet=true);state.playPart(old);assertEquals(0,plays)
        state.playPart(state.partAction(recording(),p)!!);assertEquals(1,plays)
    }
    @Test fun `selected recording change rejects a previously loaded detail action`() = runTest {
        val access=Access().apply {items=listOf(recording(),recording("other"))};var plays=0;val state=RecordingsState(backgroundScope,ProfileDataContext.current,access,log={_,_->},notify={}){_,_,_->plays++};state.load()
        val old=state.partAction(recording(),p)!!;state.selectedId="other";state.playPart(old);assertEquals(0,plays)
    }
    @Test fun `disposed page and profile round trip reject all old buttons and reads`() = runTest {
        val access=Access().apply {items=listOf(recording())};var plays=0;val state=RecordingsState(backgroundScope,ProfileDataContext.current,access,log={_,_->},notify={}){_,_,_->plays++};state.load();val action=state.partAction(recording(),p)!!
        state.dispose();state.playPart(action);state.play(recording());assertEquals(0,plays);assertTrue(state.recordings.isEmpty())
        try {state.load();fail("Closed page loaded")}catch(_:CancellationException){}
        assertEquals(3,access.calls.size)
    }
    @Test fun `old profile never reads or publishes into replacement even after masked response`() = runTest {
        val gate=CompletableDeferred<Unit>();val access=Access().apply {read={withContext(NonCancellable){gate.await()};listOf(recording())}}
        val state=RecordingsState(backgroundScope,ProfileDataContext.current,access,log={_,_->},notify={}){_,_,_->fail("Unexpected playback")};val job=launch {state.load()};runCurrent();ProfileDataContext.invalidate();gate.complete(Unit);job.join()
        assertTrue(state.recordings.isEmpty());assertEquals(listOf("recordings"),access.calls.map {it.first})
        ProfileDataContext.invalidate();state.refresh();runCurrent();assertEquals(1,access.calls.size)
    }
    @Test fun `late older list cannot replace newer capture parts or reset current selection`() = runTest {
        val gate=CompletableDeferred<Unit>();var reads=0;val newer=recording().copy(capture=recording().capture!!.copy(parts=listOf(p,p.copy(index=2,streamPath="recording:r1/parts/2"))));val access=Access().apply {read={reads++;if(reads==1){gate.await();listOf(recording())}else listOf(newer)}}
        val state=RecordingsState(backgroundScope,ProfileDataContext.current,access,log={_,_->},notify={}){_,_,_->};val old=launch {state.load()};runCurrent();state.selectedId="r1";state.load();gate.complete(Unit);old.join()
        assertEquals(2,state.selected!!.capture!!.parts.size);assertEquals("r1",state.selectedId);assertFalse(state.loading);assertFalse(state.refreshing)
    }
    @Test fun `profile switch rejects both legacy and explicit part actions`() = runTest {
        val access=Access().apply {items=listOf(recording())};var plays=0;val state=RecordingsState(backgroundScope,ProfileDataContext.current,access,log={_,_->},notify={}){_,_,_->plays++};state.load();val old=state.partAction(recording(),p)!!
        ProfileDataContext.invalidate();state.playPart(old);state.play(recording());state.cancel(recording());state.delete(recording());runCurrent();assertEquals(0,plays);assertEquals(3,access.calls.size)
    }
    @Test fun `failed refresh retains prior parts and offers a safe fixed error`() = runTest {
        val access=Access().apply {items=listOf(recording())};val state=RecordingsState(backgroundScope,ProfileDataContext.current,access,log={_,_->},notify={}){_,_,_->};state.load();access.read={error("https://example.invalid/private")};state.load(quiet=true)
        assertEquals(listOf(recording()),state.recordings);assertNotNull(state.error);assertFalse(state.error!!.contains("private"))
    }
    @Test fun `a cancelled masked list response cannot publish even in same profile`() = runTest {
        val gate=CompletableDeferred<Unit>();val access=Access().apply {read={withContext(NonCancellable){gate.await()};listOf(recording())}};val state=RecordingsState(backgroundScope,ProfileDataContext.current,access,log={_,_->},notify={}){_,_,_->}
        val job=launch {state.load()};runCurrent();job.cancel();gate.complete(Unit);job.join();assertTrue(state.recordings.isEmpty())
    }

    @Test fun `identical quiet refresh observably invalidates and recreates the enabled part button`() = runTest {
        val access=Access().apply {items=listOf(recording())};var plays=0;val state=RecordingsState(backgroundScope,ProfileDataContext.current,access,log={_,_->},notify={}){_,_,_->plays++};state.load()
        val observer=androidx.compose.runtime.snapshots.SnapshotStateObserver {it()};observer.start()
        val scope=Any();var notifications=0;var action:RecordingsState.PartAction?=null
        observer.observeReads(scope,{_:Any->notifications++}) {action=state.partAction(recording(),p)}
        val old=action!!;androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications();notifications=0
        state.load(quiet=true);androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        assertTrue("The rendered button must observe its generation change",notifications>0)
        state.playPart(old);assertEquals(0,plays)
        observer.observeReads(scope,{_:Any->notifications++}) {action=state.partAction(recording(),p)}
        state.playPart(action!!);assertEquals(1,plays);observer.stop();observer.clear()
    }

    @Test fun `background rows and new parts preserve current recording selection and canonical part order`() = runTest {
        val access=Access().apply {items=listOf(recording())};val state=RecordingsState(backgroundScope,ProfileDataContext.current,access,log={_,_->},notify={}){_,_,_->};state.load();state.selectedId="r1"
        val expanded=recording().copy(capture=recording().capture!!.copy(parts=listOf(p,p.copy(index=2,streamPath="recording:r1/parts/2"))))
        access.items=listOf(recording("newer"),expanded);state.load(quiet=true)
        assertEquals("r1",state.selected!!.id);assertEquals(listOf(1,2),state.selected!!.capture!!.parts.map {it.index});assertEquals("r1",state.selectedId)
    }

    @Test fun `delete navigation waits for confirmed mutation before closing its page`() = runTest {
        val gate=CompletableDeferred<Unit>();val access=Access().apply {items=listOf(recording());deleting={gate.await();items=emptyList()}}
        val state=RecordingsState(backgroundScope,ProfileDataContext.current,access,log={_,_->},notify={}){_,_,_->};state.load();var closed=0
        state.delete(recording()) {closed++;state.dispose()};runCurrent();assertEquals(0,closed);assertTrue(state.isCurrent)
        gate.complete(Unit);runCurrent();assertEquals(1,closed);assertFalse(state.isCurrent);assertEquals(1,access.calls.count {it.first=="delete"})
    }
    @Test fun `failed deletion retains detail and never reports successful navigation`() = runTest {
        val access=Access().apply {items=listOf(recording());deleting={error("private provider diagnostics")}};val notices=mutableListOf<String>()
        val state=RecordingsState(backgroundScope,ProfileDataContext.current,access,log={_,_->},notify=notices::add){_,_,_->};state.load();var closed=0
        state.delete(recording()) {closed++};runCurrent();assertEquals(0,closed);assertEquals(listOf(recording()),state.recordings);assertTrue(state.isCurrent);assertNull(state.busyId);assertEquals(listOf("Failed to delete recording."),notices)
    }
}
