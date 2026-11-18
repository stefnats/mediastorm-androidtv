package com.strmr.tv.ui.recordings

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.live.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingPartTimingStateTest {
    private fun recording(id: String = "recording-one") = Recording(id,"failed",capture = RecordingCapture(1,"interrupted",true,0,
        listOf(RecordingPart(1,"2026-11-16T01:00:00Z","2026-11-16T01:02:00Z",83472,"source_ended","recording:$id/parts/1")),emptyList()))
    private class Access(var items: List<Recording>) : RecordingsAccess {
        override suspend fun recordings(context: Long) = items
        override suspend fun rules(context: Long) = emptyList<SeriesRule>()
        override suspend fun plan(context: Long): RecordingPlan? = null
        override suspend fun cancel(id: String,context: Long) {}
        override suspend fun delete(id: String,context: Long) {}
        override suspend fun deleteRule(id: String,context: Long) {}
    }
    @Test fun `opening polling and creating buttons never read or inspect timing automatically`() = runTest {
        val r = recording(); var reads = 0; var probes = 0
        val state = RecordingsState(backgroundScope,access = Access(listOf(r)),log={_,_->},notify={},
            timingRead={_,_,_->reads++;PartTimingFixture.reply("unknown")}, timingInspect={_,_,_->probes++;PartTimingFixture.reply()})
        state.load(); val action = state.timingAction(r,r.capture!!.parts.single())!!
        assertEquals(0,reads); assertEquals(0,probes)
        state.load(quiet=true); state.readTiming(action,false); runCurrent(); assertEquals(0,reads)
        state.readTiming(state.timingAction(r,r.capture.parts.single())!!,false); runCurrent()
        assertEquals(1,reads); assertEquals(0,probes); assertEquals("unknown",state.timing(r.capture.parts.single())!!.state)
        state.dispose()
    }
    @Test fun `explicit inspection uses exact original recording part and profile once with historical label`() = runTest {
        val r = recording(); var probes = 0; val expected = ProfileDataContext.current
        val state = RecordingsState(backgroundScope,expected,Access(listOf(r)),log={_,_->},notify={}, timingInspect={id,index,context->
            assertEquals(r.id,id); assertEquals(1,index); assertEquals(expected,context); probes++;PartTimingFixture.reply()
        })
        state.load(); val part=r.capture!!.parts.single(); state.readTiming(state.timingAction(r,part)!!,true); runCurrent()
        assertEquals(1,probes); assertEquals("Observed container estimate: 0:02",state.timing(part)!!.label()); assertNull(state.timingBusy)
        assertTrue(state.timing(part)!!.observationLabel()!!.contains("Stored until:")); state.dispose()
    }
    @Test fun `equal-valued replacement recording and part copies never become action receipts`() = runTest {
        val r = recording(); val state=RecordingsState(backgroundScope,access=Access(listOf(r)),log={_,_->},notify={})
        state.load(); val part=r.capture!!.parts.single()
        assertNull(state.timingAction(r.copy(),part)); assertNull(state.timingAction(r,part.copy()))
        assertNull(state.timingAction(r.copy(status="running"),part)); state.dispose()
    }
    @Test fun `held same-profile page retires old action and returning requires a fresh explicit action`() = runTest {
        val r=recording(); var page=true; var probes=0
        val state=RecordingsState(backgroundScope,access=Access(listOf(r)),log={_,_->},notify={},pageCurrent={page},timingInspect={_,_,_->probes++;PartTimingFixture.reply()})
        state.load(); val p=r.capture!!.parts.single(); val old=state.timingAction(r,p)!!
        page=false; state.readTiming(old,true); runCurrent(); assertEquals(0,probes)
        state.retireTiming(); page=true; state.readTiming(old,true); runCurrent(); assertEquals(0,probes)
        state.readTiming(state.timingAction(r,p)!!,true); runCurrent(); assertEquals(1,probes); state.dispose()
    }
    @Test fun `selection change cancels pending PIN child before any inspection and cannot target replacement`() = runTest {
        val a=recording(); val b=recording("other"); val pin=CompletableDeferred<Unit>(); var probes=0; var cancelled=false
        val state=RecordingsState(backgroundScope,access=Access(listOf(a,b)),log={_,_->},notify={},timingInspect={_,_,_->
            try {pin.await()}finally{cancelled=true};probes++;PartTimingFixture.reply()
        })
        state.load(); val old=state.timingAction(a,a.capture!!.parts.single())!!; state.readTiming(old,true); runCurrent()
        state.selectedId=b.id; runCurrent(); assertTrue(cancelled); pin.complete(Unit); runCurrent()
        assertEquals(0,probes); assertNull(state.timingBusy); assertNull(state.timing(b.capture!!.parts.single())); state.dispose()
    }
    @Test fun `normal original PIN wait completes the one selected explicit inspection`() = runTest {
        val r=recording(); val pin=CompletableDeferred<Unit>(); var probes=0
        val state=RecordingsState(backgroundScope,access=Access(listOf(r)),log={_,_->},notify={},timingInspect={_,_,context->
            assertEquals(ProfileDataContext.current,context);pin.await();probes++;PartTimingFixture.reply()
        })
        state.load(); val p=r.capture!!.parts.single(); state.readTiming(state.timingAction(r,p)!!,true); runCurrent()
        assertEquals(0,probes); pin.complete(Unit); runCurrent(); assertEquals(1,probes); assertNotNull(state.timing(p)); state.dispose()
    }
    @Test fun `masked late reply after replacement list or disposal cannot publish historical observation`() = runTest {
        for (dispose in listOf(false,true)) {
            val r=recording(); val gate=CompletableDeferred<Unit>(); val access=Access(listOf(r))
            val state=RecordingsState(backgroundScope,access=access,log={_,_->},notify={},timingInspect={_,_,_->withContext(NonCancellable){gate.await()};PartTimingFixture.reply()})
            state.load(); val p=r.capture!!.parts.single(); state.readTiming(state.timingAction(r,p)!!,true); runCurrent()
            if(dispose)state.dispose()else {access.items=listOf(recording());state.load(quiet=true)}
            gate.complete(Unit);runCurrent();assertNull(state.timing(p));assertNull(state.timingBusy)
        }
    }
    @Test fun `final unauthorized PIN or busy failure never retries POST automatically`() = runTest {
        for (failure in listOf(HttpFailure(401,"Unauthorized"),HttpFailure(403,"""{"code":"profile_locked"}"""),HttpFailure(429,"Busy"))) {
            val r=recording();var calls=0
            val state=RecordingsState(backgroundScope,access=Access(listOf(r)),log={_,_->},notify={},timingInspect={_,_,_->calls++;throw ApiError.Http(failure)})
            state.load();val p=r.capture!!.parts.single();state.readTiming(state.timingAction(r,p)!!,true);runCurrent()
            assertEquals(1,calls);assertNull(state.timing(p));assertNotNull(state.timingError(p));assertNull(state.timingBusy);state.dispose()
        }
    }
    @Test fun `A B A context retirement prevents all old timing actions`() = runTest {
        val r=recording();var probes=0
        val state=RecordingsState(backgroundScope,access=Access(listOf(r)),log={_,_->},notify={},timingInspect={_,_,_->probes++;PartTimingFixture.reply()})
        state.load();val old=state.timingAction(r,r.capture!!.parts.single())!!;ProfileDataContext.invalidate();ProfileDataContext.invalidate()
        state.readTiming(old,true);runCurrent();assertEquals(0,probes);assertNull(state.timingBusy);state.dispose()
    }
}
