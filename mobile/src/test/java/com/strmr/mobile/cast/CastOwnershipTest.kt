package com.strmr.mobile.cast

import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.player.PlaybackRequest
import com.strmr.tv.player.PlaybackSessionContext
import com.strmr.tv.player.PlayerSession
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CastOwnershipTest {
    private class Receiver(id:String="synthetic-device"):CastTransport {
        override val device=CastDevice(id,"Synthetic",null,CastDevice.Kind.DLNA,null)
        override val canSeek=true
        var loads=0;var stops=0;var commands=0
        var load:suspend ()->Unit={};var state:suspend ()->CastPlaybackState?={null}
        override suspend fun load(media:CastMedia){loads++;load()}
        override suspend fun play(){commands++}
        override suspend fun pause(){commands++}
        override suspend fun seek(seconds:Double){commands++}
        override suspend fun setVolume(volume:Float){commands++}
        override suspend fun status()=state()
        override suspend fun stop(){stops++}
    }
    private val media=CastMedia("http://127.0.0.1/synthetic","video/mp4","Synthetic",null,null,100.0,0.0)
    private fun prepared(receiver:Receiver,id:String?="synthetic-hls")=PreparedCast(receiver,media,id,null)
    private suspend fun cancelled(action:suspend ()->Unit){try {action();fail("Stale Cast effect accepted")}catch(_:CancellationException){}}
    @Test fun `queued preparation cannot start after original context changes`()=runTest {
        val authority=CastOwnership({true});val context=PlaybackSessionContext.capture();val receiver=Receiver();val ticket=authority.begin(context,receiver.device.id);var prepares=0
        ProfileDataContext.invalidate()
        cancelled {authority.start(ticket,{prepares++;prepared(receiver)}) {fail("Late publish")}}
        assertEquals(0,prepares);assertEquals(0,receiver.loads)
    }
    @Test fun `late preparation after A B A cannot LOAD publish or clean server under replacement owner`()=runTest {
        var serverStops=0;val authority=CastOwnership({true}) {_,_->serverStops++};val context=PlaybackSessionContext.capture();val receiver=Receiver();val ticket=authority.begin(context,receiver.device.id)
        val ready=CompletableDeferred<PreparedCast>();var published=0
        val start=async {authority.start(ticket,{ready.await()}) {published++}}
        runCurrent();ProfileDataContext.invalidate();ProfileDataContext.invalidate();ready.complete(prepared(receiver));cancelled {start.await()}
        assertEquals(0,receiver.loads);assertEquals(0,published);assertEquals(0,serverStops);assertEquals(1,receiver.stops)
    }
    @Test fun `canceled LOAD must settle before replacement LOAD and late cleanup cannot stop replacement`()=runTest {
        val authority=CastOwnership({true});val context=PlaybackSessionContext.capture();val first=Receiver();val second=Receiver();val release=CompletableDeferred<Unit>();first.load={release.await()}
        val a=authority.begin(context,first.device.id);var acceptedA=0;var acceptedB=0
        val old=async {authority.start(a,{prepared(first)}) {acceptedA++}};runCurrent();assertEquals(1,first.loads)
        old.cancel();val b=authority.begin(context,second.device.id)
        val next=async {authority.start(b,{prepared(second)}) {acceptedB++}};runCurrent();assertEquals(0,second.loads)
        release.complete(Unit);cancelled {old.await()};next.await()
        assertEquals(0,acceptedA);assertEquals(1,acceptedB);assertEquals(0,first.stops);assertEquals(0,second.stops)
        authority.stopCaptured(a,first);assertEquals(0,first.stops);assertTrue(authority.owns(b))
    }
    @Test fun `context loss during LOAD stops only captured receiver after reply and never publishes`()=runTest {
        var serverStops=0;val authority=CastOwnership({true}) {_,_->serverStops++};val context=PlaybackSessionContext.capture();val receiver=Receiver();val release=CompletableDeferred<Unit>();receiver.load={release.await()}
        val ticket=authority.begin(context,receiver.device.id);var accepted=0
        val start=async {authority.start(ticket,{prepared(receiver)}) {accepted++}};runCurrent();ProfileDataContext.invalidate();release.complete(Unit)
        cancelled {start.await()};assertEquals(0,accepted);assertEquals(1,receiver.stops);assertEquals(0,serverStops)
    }
    @Test fun `late status cannot update replacement and commands reject retired ticket before receiver IO`()=runTest {
        val authority=CastOwnership({true});val context=PlaybackSessionContext.capture();val receiver=Receiver();val a=authority.begin(context,receiver.device.id)
        authority.start(a,{prepared(receiver)}){};val release=CompletableDeferred<CastPlaybackState?>();receiver.state={release.await()};var applied=0
        val old=async {authority.command(a,receiver){receiver.status()};applied++};runCurrent();val b=authority.begin(context,receiver.device.id);release.complete(CastPlaybackState());cancelled {old.await()}
        cancelled {authority.command(a,receiver){receiver.play()}};assertEquals(0,receiver.commands);assertEquals(0,applied);assertTrue(authority.owns(b))
    }
    @Test fun `current canceled preparation releases only its own HLS session`()=runTest {
        val stopped=mutableListOf<Pair<String,PlaybackSessionContext>>();val authority=CastOwnership({true}) {id,context->stopped+=id to context};val context=PlaybackSessionContext.capture();val receiver=Receiver();val ticket=authority.begin(context,receiver.device.id)
        receiver.load={throw IllegalStateException("Synthetic load failure")}
        assertTrue(runCatching {authority.start(ticket,{prepared(receiver)}){}}.isFailure)
        assertEquals(listOf("synthetic-hls"),stopped.map {it.first});assertSame(context,stopped.single().second);assertEquals(1,receiver.stops)
    }
    @Test fun `unknown or restricted adult provenance cannot begin any Cast work`() {
        val authority=CastOwnership({false});val context=PlaybackSessionContext.capture()
        assertFalse(authority.allowed(context));assertTrue(runCatching {authority.begin(context,"synthetic-device")}.isFailure)
    }
    @Test fun `queued heartbeat never reaches a replacement profiles history transport`()=runTest {
        val authority=CastOwnership({true});val context=PlaybackSessionContext.capture();val ticket=authority.begin(context,"synthetic-device");var writes=0
        val old=async {authority.server(ticket){writes++;it.profileId}}
        ProfileDataContext.invalidate();ProfileDataContext.invalidate();cancelled {old.await()};assertEquals(0,writes)
    }
    @Test fun `late heartbeat reply cannot publish into a replacement Cast generation`()=runTest {
        val authority=CastOwnership({true});val context=PlaybackSessionContext.capture();val oldTicket=authority.begin(context,"synthetic-device");val reply=CompletableDeferred<Unit>();var applied=0
        val old=async {authority.server(oldTicket){reply.await()};applied++};runCurrent()
        val replacement=authority.begin(context,"synthetic-device");reply.complete(Unit);cancelled {old.await()}
        assertEquals(0,applied);assertTrue(authority.owns(replacement))
    }
    @Test fun `cleanup of another receiver never stops the replacement receiver`()=runTest {
        val authority=CastOwnership({true});val context=PlaybackSessionContext.capture();val first=Receiver("synthetic-a");val next=Receiver("synthetic-b")
        val a=authority.begin(context,first.device.id);authority.start(a,{prepared(first)}){}
        val b=authority.begin(context,next.device.id);authority.start(b,{prepared(next)}){}
        authority.stopCaptured(a,first);assertEquals(1,first.stops);assertEquals(0,next.stops);assertTrue(authority.owns(b))
    }
    @Test fun `old rendered controls cannot target a new cast even in the same profile epoch`() {
        val authority=CastOwnership({true});val context=PlaybackSessionContext.capture()
        val first=authority.begin(context,"synthetic-device");val callbackGeneration=first.generation
        assertTrue(authority.accepts(callbackGeneration));val next=authority.begin(context,"synthetic-device")
        assertFalse(authority.accepts(callbackGeneration));assertTrue(authority.accepts(next.generation));assertFalse(authority.accepts(null))
    }
    @Test fun `local files and unbound sessions never expose a cast handoff`() {
        val request=PlaybackRequest(DetailsTarget("tmdb:movie:1","Synthetic",true))
        val local=PlayerSession(request,"file:///synthetic/file.mkv",null,streamPath="/synthetic/file.mkv",localFile=true,localFileAccess={true},ownership=PlaybackSessionContext.capture())
        assertFalse(local.castable());assertFalse(local.copy(localFile=false,ownership=null).castable())
    }
}
