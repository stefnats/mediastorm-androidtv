package com.strmr.tv.ui.recordings

import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.live.*
import com.strmr.tv.player.queuePart
import com.strmr.tv.player.queueRecording
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** The actual State.play callback consumed by existing TV and phone recording actions. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingPartQueueStateTest {
    private class Access(var items:List<Recording>):RecordingsAccess {
        override suspend fun recordings(context:Long)=items
        override suspend fun rules(context:Long)=emptyList<SeriesRule>()
        override suspend fun plan(context:Long):RecordingPlan?=null
        override suspend fun cancel(id:String,context:Long){}
        override suspend fun delete(id:String,context:Long){}
        override suspend fun deleteRule(id:String,context:Long){}
    }
    @Test fun `current default action uses captured owned multipart queue while individual part remains explicit`() = runTest {
        val r=queueRecording();val access=Access(listOf(r));val queued=mutableListOf<Pair<Recording,Long>>();val singles=mutableListOf<Int?>()
        val state=RecordingsState(backgroundScope,ProfileDataContext.current,access,log={_,_->},notify={},playQueue={item,expected->queued+=item to expected}){_,part,_->singles+=part?.index}
        state.load();state.play(r);assertEquals(listOf(r to state.context),queued);assertTrue(singles.isEmpty())
        state.partAction(r,r.capture!!.parts.first())!!.let(state::playPart);assertEquals(listOf(2),singles)
    }
    @Test fun `refresh with changed closed snapshot invalidates an old default callback without appending`() = runTest {
        val old=queueRecording();val access=Access(listOf(old));var queues=0
        val state=RecordingsState(backgroundScope,ProfileDataContext.current,access,log={_,_->},notify={},playQueue={_,_->queues++}){_,_,_->fail("Unexpected legacy action")}
        state.load();val updated=queueRecording(listOf(queuePart(2),queuePart(5),queuePart(8)));access.items=listOf(updated);state.load(quiet=true)
        state.play(old);assertEquals(0,queues);state.play(updated);assertEquals(1,queues)
    }
    @Test fun `closed page or A B A epoch cannot create queue from an old visible recording`() = runTest {
        for(dispose in listOf(false,true)) {
            val r=queueRecording();val access=Access(listOf(r));var queues=0
            val state=RecordingsState(backgroundScope,ProfileDataContext.current,access,log={_,_->},notify={},playQueue={_,_->queues++}){_,_,_->fail("Unexpected legacy action")}
            state.load();if(dispose)state.dispose()else{ProfileDataContext.invalidate();ProfileDataContext.invalidate()}
            state.play(r);assertEquals(0,queues)
        }
    }
    @Test fun `legacy recording without capture keeps deliberate first-file action`() = runTest {
        val r=Recording("legacy","completed",outputPath="legacy.ts");val access=Access(listOf(r));var singles=0
        val state=RecordingsState(backgroundScope,ProfileDataContext.current,access,log={_,_->},notify={},playQueue={_,_->fail("A legacy recording has no queue")}){item,part,expected->assertEquals(r,item);assertNull(part);assertEquals(ProfileDataContext.current,expected);singles++}
        state.load();state.play(r);assertEquals(1,singles)
    }
    @Test fun `unreadable sibling is not silently skipped by a queue`() = runTest {
        val r=queueRecording(listOf(queuePart(2),queuePart(5).copy(streamPath=null)));val access=Access(listOf(r));var singles=0
        val state=RecordingsState(backgroundScope,ProfileDataContext.current,access,log={_,_->},notify={},playQueue={_,_->fail("Unreadable sibling skipped")}){_,_,_->singles++}
        state.load();state.play(r);assertEquals("Play part 2",RecordingCaptureRules.playLabel(r));assertEquals(1,singles)
    }
    @Test fun `owned queue transport uses original part selector and does not invent continuous stream path`() {
        val r=queueRecording();val route=recordingStreamRoute(r,"Synthetic",profile="original-profile",part=r.capture!!.parts[1])
        assertTrue(route.contains("part=5"));assertTrue(route.contains("profileId=original-profile"));assertFalse(route.contains("concat"));assertFalse(route.contains("parts=2"))
    }
}
