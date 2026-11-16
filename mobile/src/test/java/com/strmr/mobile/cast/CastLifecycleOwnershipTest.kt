package com.strmr.mobile.cast

import com.strmr.tv.api.await
import com.strmr.tv.library.ProfileDataContext
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import com.strmr.tv.player.PlaybackSessionContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Actual Cast ownership/progress/cleanup coordinator; synthetic receivers, no SDK or device. */
@OptIn(ExperimentalCoroutinesApi::class)
class CastLifecycleOwnershipTest {
    private class Receiver:CastTransport {
        override val device=CastDevice("synthetic-device","Synthetic",null,CastDevice.Kind.DLNA,null)
        override val canSeek=true
        var stops=0
        override suspend fun load(media:CastMedia){}
        override suspend fun play(){}
        override suspend fun pause(){}
        override suspend fun seek(seconds:Double){}
        override suspend fun setVolume(volume:Float){}
        override suspend fun status():CastPlaybackState?=null
        override suspend fun stop(){stops++}
    }
    private class StalledPeer:AutoCloseable {
        private val socket=ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))
        private val worker=Executors.newSingleThreadExecutor { r->Thread(r,"synthetic-progress-http").apply{isDaemon=true} }
        val arrived=CountDownLatch(1);val release=CountDownLatch(1)
        val url="http://127.0.0.1:${socket.localPort}/synthetic-progress"
        init {worker.submit {runCatching {socket.accept().use {peer->
            peer.soTimeout=3000;val reader=peer.getInputStream().bufferedReader();reader.readLine()
            while(true){val line=reader.readLine()?:break;if(line.isEmpty())break}
            arrived.countDown();check(release.await(3,TimeUnit.SECONDS))
            peer.getOutputStream().apply{write("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nsaved".toByteArray());flush()}
        }}}}
        override fun close(){release.countDown();socket.close();worker.shutdownNow();worker.awaitTermination(3,TimeUnit.SECONDS)}
    }
    private fun prepared(receiver:Receiver,id:String)=PreparedCast(receiver,CastMedia("http://127.0.0.1/synthetic","video/mp4","Synthetic",null,null,100.0,0.0),id,null)
    private suspend fun cancelled(action:suspend()->Unit){try{action();fail("Denied progress survived")}catch(_:CancellationException){}}
    @Test fun `normal retire preserves last closing progress while owner remains current`()=runTest {
        val ownership=CastOwnership({true});val ticket=ownership.begin(PlaybackSessionContext.capture(),"device");val release=CompletableDeferred<Unit>();var writes=0
        val job=async{withContext(NonCancellable){ownership.progress(ticket,true){release.await();writes++}}}
        runCurrent();ownership.retire(ticket);assertTrue(ownership.canReportClosing(ticket));release.complete(Unit);job.await()
        assertEquals(1,writes);assertTrue(ticket.progressJobs.isEmpty())
    }
    @Test fun `normal retire cancels nonclosing pending progress without blocking the closing receipt`()=runTest {
        val ownership=CastOwnership({true});val ticket=ownership.begin(PlaybackSessionContext.capture(),"device");var canceled=0;var writes=0
        val job=async{withContext(NonCancellable){ownership.progress(ticket,false){suspendCancellableCoroutine<Unit>{it.invokeOnCancellation{canceled++}};writes++}}}
        runCurrent();ownership.retire(ticket);cancelled{job.await()};assertEquals(1,canceled);assertEquals(0,writes);assertTrue(ownership.canReportClosing(ticket))
    }
    @Test fun `already denied ticket cannot start queued final progress`()=runTest {
        val ownership=CastOwnership({true});val ticket=ownership.begin(PlaybackSessionContext.capture(),"device");var writes=0
        val job=async{withContext(NonCancellable){ownership.progress(ticket,true){writes++}}}
        assertTrue(ownership.deny(ticket));runCurrent();assertNull(job.await());assertEquals(0,writes);assertFalse(ownership.canReportClosing(ticket))
    }
    @Test fun `denial cancels in flight closing transport even with NonCancellable parent`()=runTest {
        val ownership=CastOwnership({true});val ticket=ownership.begin(PlaybackSessionContext.capture(),"device");var canceled=0;var writes=0
        val job=async{withContext(NonCancellable){ownership.progress(ticket,true){suspendCancellableCoroutine<Unit>{it.invokeOnCancellation{canceled++}};writes++}}}
        runCurrent();assertTrue(ownership.deny(ticket));cancelled{job.await()};assertEquals(1,canceled);assertEquals(0,writes);assertTrue(ticket.progressJobs.isEmpty())
    }
    @Test fun `late old denial cannot retire or stop a replacement receiver`()=runTest {
        val ownership=CastOwnership({true});val context=PlaybackSessionContext.capture();val oldReceiver=Receiver();val newReceiver=Receiver()
        val old=ownership.begin(context,"synthetic-device");ownership.start(old,{prepared(oldReceiver,"old")}){}
        val replacement=ownership.begin(context,"synthetic-device");ownership.start(replacement,{prepared(newReceiver,"new")}){}
        assertFalse(ownership.deny(old));ownership.stopCaptured(old,oldReceiver)
        assertTrue(ownership.owns(replacement));assertEquals(0,oldReceiver.stops);assertEquals(0,newReceiver.stops)
    }
    @Test fun `known server cleanup never stops a replacement assigned the same opaque ID`()=runTest {
        val stops=mutableListOf<Pair<String,PlaybackSessionContext>>();val ownership=CastOwnership({true}){id,context->stops+=id to context};val context=PlaybackSessionContext.capture()
        val old=ownership.begin(context,"synthetic-device");ownership.start(old,{prepared(Receiver(),"shared")}){}
        val replacement=ownership.begin(context,"synthetic-device");ownership.start(replacement,{prepared(Receiver(),"shared")}){}
        ownership.stopOwnedServer(old,"shared");ownership.stopOwnedServer(old,"not-owned");assertTrue(stops.isEmpty())
        ownership.stopOwnedServer(replacement,"shared");assertEquals(listOf("shared"),stops.map{it.first});assertSame(context,stops.single().second)
    }
    @Test fun `context or adult provenance loss prevents queued final progress`()=runTest {
        for(changed in listOf(true,false)) {
            var eligible=true;val ownership=CastOwnership({eligible});val ticket=ownership.begin(PlaybackSessionContext.capture(),"device");var writes=0
            val job=async{ownership.progress(ticket,true){writes++}}
            if(changed){ProfileDataContext.invalidate();ProfileDataContext.invalidate()}else eligible=false
            runCurrent();assertNull(job.await());assertEquals(0,writes)
        }
    }
    @Test fun `denied ticket can stop only captured receiver and original known server ID`()=runTest {
        val stops=mutableListOf<Long>();val ownership=CastOwnership({true}){_,context->stops+=context.epoch};val context=PlaybackSessionContext.capture();val receiver=Receiver()
        val ticket=ownership.begin(context,"synthetic-device");ownership.start(ticket,{prepared(receiver,"owned")}){}
        assertTrue(ownership.deny(ticket));ownership.stopCaptured(ticket,receiver);ownership.stopOwnedServer(ticket,"owned")
        assertEquals(1,receiver.stops);assertEquals(listOf(context.epoch),stops)
        ProfileDataContext.invalidate();ownership.stopOwnedServer(ticket,"owned");assertEquals(1,stops.size)
    }
    @Test fun `normal closing receipt completes through actual Call await after retire`()=runTest {
        StalledPeer().use {peer->val ownership=CastOwnership({true});val ticket=ownership.begin(PlaybackSessionContext.capture(),"device");val client=OkHttpClient();val call=client.newCall(Request.Builder().url(peer.url).build());var published=0
            try{
                val job=async{withContext(NonCancellable){ownership.progress(ticket,true){call.await();published++}}}
                runCurrent();assertTrue(peer.arrived.await(3,TimeUnit.SECONDS));ownership.retire(ticket);assertFalse(call.isCanceled());peer.release.countDown();job.await()
                assertEquals(1,published);assertFalse(call.isCanceled())
            }finally{peer.release.countDown();client.dispatcher.executorService.shutdownNow()}
        }
    }
    @Test fun `known denial cancels real in flight Call await before publishing its receipt`()=runTest {
        StalledPeer().use {peer->val ownership=CastOwnership({true});val ticket=ownership.begin(PlaybackSessionContext.capture(),"device");val client=OkHttpClient();val call=client.newCall(Request.Builder().url(peer.url).build());var published=0
            try{
                val job=async{withContext(NonCancellable){ownership.progress(ticket,true){call.await();published++}}}
                runCurrent();assertTrue(peer.arrived.await(3,TimeUnit.SECONDS));assertTrue(ownership.deny(ticket));runCurrent();assertTrue(call.isCanceled());cancelled{job.await()};assertEquals(0,published)
            }finally{peer.release.countDown();client.dispatcher.executorService.shutdownNow()}
        }
    }

}
