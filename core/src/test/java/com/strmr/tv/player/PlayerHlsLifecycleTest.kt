package com.strmr.tv.player

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Actual lifecycle used by recording, catchup and live retune; effects are injected, not a decoder. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerHlsLifecycleTest {
    private data class Resource(val id:String)
    private inner class Fixture {
        val owner=PlaybackSessionContext.capture();val stopped=mutableListOf<Pair<String,Long>>();val denied=mutableListOf<VodAccessDenial>();var player=true
        val life=PlayerHlsLifecycle(owner,{player},"tmdb:movie:41",{denied+=it},{id,epoch->stopped+=id to epoch})
    }
    private suspend fun cancelled(action:suspend()->Unit){try{action();fail("Retired operation survived")}catch(_:CancellationException){}}
    @Test fun `successful request prepare adoption and known cleanup retain original owner`()=runTest {
        val f=Fixture();f.life.remember("initial");val old=f.life.receipt("initial");val ticket=f.life.begin()!!;var adopted=0
        assertEquals(f.owner.epoch,f.life.read(ticket){it})
        val resource=f.life.prepare<Resource>(ticket,{Resource("new")},{it.id});f.life.use(ticket){adopted++}
        f.life.stop(old);assertEquals(listOf("initial"),f.stopped.map{it.first});assertTrue(f.stopped.all{it.second==f.owner.epoch});assertEquals("new",resource.id);assertEquals(1,adopted)
    }
    @Test fun `closed same profile instance rejects queued reads prepares and effects`()=runTest {
        val f=Fixture();val ticket=f.life.begin()!!;var calls=0
        f.life.close();cancelled{f.life.read(ticket){calls++}};cancelled{f.life.prepare<Resource>(ticket,{calls++;Resource("old")},{it.id})};cancelled{f.life.use(ticket){calls++}}
        assertEquals(0,calls);assertNull(f.life.begin())
    }
    @Test fun `old epoch after simulated A B A cannot demand or clean with replacement context`()=runTest {
        val f=Fixture();f.life.remember("old");val stop=f.life.receipt("old");val ticket=f.life.begin()!!;var calls=0
        ProfileDataContext.invalidate();ProfileDataContext.invalidate()
        cancelled{f.life.read(ticket){calls++}};f.life.stop(stop)
        assertEquals(0,calls);assertTrue(f.stopped.isEmpty())
    }
    @Test fun `late returned start after release cleans that known original ID without adoption`()=runTest {
        val f=Fixture();val ticket=f.life.begin()!!;val reply=CompletableDeferred<Resource>();var adopted=0
        val work=async{f.life.prepare<Resource>(ticket,{withContext(NonCancellable){reply.await()}},{it.id});f.life.use(ticket){adopted++}}
        runCurrent();f.life.close();reply.complete(Resource("returned"));cancelled{work.await()}
        assertEquals(listOf("returned"),f.stopped.map{it.first});assertEquals(f.owner.epoch,f.stopped.single().second);assertEquals(0,adopted)
    }
    @Test fun `late returned start after owner change relies on server expiry not new credentials`()=runTest {
        val f=Fixture();val ticket=f.life.begin()!!;val reply=CompletableDeferred<Resource>()
        val work=async{f.life.prepare<Resource>(ticket,{withContext(NonCancellable){reply.await()}},{it.id})}
        runCurrent();ProfileDataContext.invalidate();reply.complete(Resource("unknown-under-new-owner"));cancelled{work.await()};assertTrue(f.stopped.isEmpty())
    }
    @Test fun `late older start cannot stop a replacement already assigned the same ID`()=runTest {
        val f=Fixture();val old=f.life.begin()!!;val reply=CompletableDeferred<Resource>()
        val work=async{f.life.prepare<Resource>(old,{withContext(NonCancellable){reply.await()}},{it.id})};runCurrent()
        val current=f.life.begin()!!;f.life.prepare<Resource>(current,{Resource("shared-id")},{it.id})
        reply.complete(Resource("shared-id"));cancelled{work.await()}
        assertTrue(f.stopped.isEmpty());assertTrue(f.life.current(current))
    }
    @Test fun `queued old cleanup cannot stop newly adopted reuse and detached handover stays protected`()=runTest {
        val f=Fixture();f.life.remember("shared");val old=f.life.receipt("shared");val current=f.life.begin()!!
        f.life.prepare<Resource>(current,{Resource("shared")},{it.id});f.life.stop(old);assertTrue(f.stopped.isEmpty())
        val stop=f.life.receipt("shared");f.life.detach("shared");f.life.close();f.life.stop(stop);assertTrue(f.stopped.isEmpty());assertNull(f.life.receipt("not-known"))
    }
    @Test fun `current terminal denial retires all subsequent reads and source fallback effects`()=runTest {
        val f=Fixture();val ticket=f.life.begin()!!;var calls=0;var fallback=0
        cancelled{f.life.read(ticket){calls++;throw ApiError.Http(HttpFailure(403,"{\"code\":\"kids_vod_access_denied\"}"))}}
        cancelled{f.life.read{calls++}};cancelled{f.life.use{fallback++}}
        assertEquals(1,calls);assertEquals(0,fallback);assertEquals(listOf(VodAccessDenial.BLOCKED),f.denied);assertNull(f.life.begin())
    }
    @Test fun `late superseded denial cannot retire a fresh retune or archive request`()=runTest {
        val f=Fixture();val old=f.life.begin()!!;val release=CompletableDeferred<Unit>()
        val work=async{f.life.read(old){withContext(NonCancellable){release.await()};throw ApiError.Http(HttpFailure(403,"{}"))}}
        runCurrent();val current=f.life.begin()!!;release.complete(Unit);cancelled{work.await()}
        assertTrue(f.denied.isEmpty());assertEquals("current",f.life.read(current){"current"})
    }
    @Test fun `canceled same owner start obtains returned ID for cleanup and never adopts`()=runTest {
        val f=Fixture();val ticket=f.life.begin()!!;val reply=CompletableDeferred<Resource>();var adopted=0
        val work=async{f.life.prepare<Resource>(ticket,{reply.await()},{it.id});adopted++};runCurrent();work.cancel();reply.complete(Resource("returned-on-cancel"));cancelled{work.await()}
        assertEquals(listOf("returned-on-cancel"),f.stopped.map{it.first});assertEquals(0,adopted)
    }
    @Test fun `ordinary provider failure does not retire compatible retry or fallback`()=runTest {
        val f=Fixture();val ticket=f.life.begin()!!
        try{f.life.read(ticket){throw IOException("Synthetic unavailable")};fail()}catch(_:IOException){}
        assertTrue(f.life.current(ticket));assertEquals(42,f.life.read(ticket){42});assertTrue(f.denied.isEmpty())
    }
    @Test fun `player lifetime loss prevents effects even when profile epoch remains current`()=runTest {
        val f=Fixture();val ticket=f.life.begin()!!;val release=CompletableDeferred<Unit>();var published=0
        val work=async{f.life.read(ticket){withContext(NonCancellable){release.await()};"old"};published++};runCurrent();f.player=false;release.complete(Unit);cancelled{work.await()}
        assertEquals(0,published);assertNull(f.life.begin())
    }
    @Test fun `superseded schedule result is rejected while fresh operation remains usable`()=runTest {
        val f=Fixture();val old=f.life.ticket();val release=CompletableDeferred<Unit>();var published=0
        val work=async{f.life.read(old){withContext(NonCancellable){release.await()};"old guide"};published++};runCurrent();val fresh=f.life.begin()!!;release.complete(Unit);cancelled{work.await()}
        assertEquals(0,published);assertEquals("new guide",f.life.read(fresh){"new guide"})
    }
}
