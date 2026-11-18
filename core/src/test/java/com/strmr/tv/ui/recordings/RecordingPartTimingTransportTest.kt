package com.strmr.tv.ui.recordings

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.live.*
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class RecordingPartTimingTransportTest {
    @Test fun `actual query builders and bound transport send GET then zero-byte POST to synthetic peer`() = runBlocking {
        ServerSocket(0,2,InetAddress.getByName("127.0.0.1")).use { server ->
            val received=CompletableDeferred<List<Pair<String,Int>>>()
            val executor=Executors.newSingleThreadExecutor();val client=OkHttpClient.Builder().retryOnConnectionFailure(false).build()
            executor.submit {
                val rows=mutableListOf<Pair<String,Int>>()
                repeat(2) { count -> server.accept().use { peer ->
                    peer.soTimeout=3000;val reader=peer.getInputStream().bufferedReader();val first=reader.readLine();var length=0
                    while(true){val line=reader.readLine() ?: break;if(line.isEmpty())break;if(line.startsWith("Content-Length:",true))length=line.substringAfter(':').trim().toInt()}
                    rows+=first to length
                    val body=PartTimingFixture.bytes(if(count==0)"unknown" else "container-estimate")
                    peer.getOutputStream().apply {write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray());write(body);flush()}
                } }
                received.complete(rows)
            }
            try {
                val expected=ProfileDataContext.current;val stamp=RequestContext({ProfileDataContext.current},{ProfileDataContext.invalidate()}).capture(expected)
                for(inspect in listOf(false,true)) {
                    val original=recordingTimingRequest("recording-one",1,"profile-a",inspect)
                    val url="http://127.0.0.1:${server.localPort}${original.path}?profileId=profile-a"
                    val result=ContextBoundTransport(stamp,false,{false},{error("No recovery")},allowReplay=false).execute {
                        val request=Request.Builder().url(url).method(original.method,if(inspect)ByteArray(0).toRequestBody()else null).build()
                        client.newCall(request).await()
                    }
                    assertEquals(200,result.code);assertEquals(if(inspect)"container_estimate" else "unknown",decodeRecordingPartTiming(result.text,"recording-one",1).state)
                }
                val rows=withTimeout(3000){received.await()}
                assertEquals(listOf("GET /api/live/recordings/recording-one/parts/1/timing?profileId=profile-a HTTP/1.1","POST /api/live/recordings/recording-one/parts/1/timing/refresh?profileId=profile-a HTTP/1.1"),rows.map{it.first})
                assertEquals(listOf(0,0),rows.map{it.second})
            } finally {executor.shutdownNow();executor.awaitTermination(3,TimeUnit.SECONDS);client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
        }
    }
    @Test fun `lost response never invokes transport recovery or a second inspection POST`() = runBlocking {
        val expected=ProfileDataContext.current;var sends=0;var recovers=0
        val stamp=RequestContext({ProfileDataContext.current},{ProfileDataContext.invalidate()}).capture(expected)
        val error=IOException("synthetic lost response")
        val actual=runCatching {ContextBoundTransport(stamp,true,{recovers++;true},{},allowReplay=false).execute {sends++;throw error}}.exceptionOrNull()
        assertSame(error,actual);assertEquals(1,sends);assertEquals(0,recovers)
    }
    @Test fun `actual selection retirement cancels Call await while unrelated producer sibling remains blocked`() = runBlocking {
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { server ->
            val arrived=CountDownLatch(1);val response=CountDownLatch(1);val executor=Executors.newSingleThreadExecutor()
            executor.submit {
                try {server.accept().use {peer->peer.soTimeout=3000;val reader=peer.getInputStream().bufferedReader();reader.readLine()
                    while(true){val line=reader.readLine() ?: break;if(line.isEmpty())break}
                    peer.getOutputStream().apply {write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n".toByteArray());flush()}
                    arrived.countDown();response.await(3,TimeUnit.SECONDS);peer.getOutputStream().write("{}".toByteArray())
                }}catch(_:IOException){}
            }
            val parent=Job();val scope=CoroutineScope(Dispatchers.IO+parent)
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val sibling=scope.launch {withContext(NonCancellable){entered.complete(Unit);release.await()}}
            val client=OkHttpClient();val call=AtomicReference<okhttp3.Call>();val cancelled=CompletableDeferred<Unit>()
            val part=RecordingPart(1,"2026-11-16T01:00:00Z","2026-11-16T01:02:00Z",83472,"source_ended","recording:recording-one/parts/1")
            val recording=Recording("recording-one","failed",capture=RecordingCapture(1,"interrupted",true,0,listOf(part),emptyList()))
            val access=object:RecordingsAccess {
                override suspend fun recordings(context:Long)=listOf(recording)
                override suspend fun rules(context:Long)=emptyList<SeriesRule>()
                override suspend fun plan(context:Long):RecordingPlan?=null
                override suspend fun cancel(id:String,context:Long){}
                override suspend fun delete(id:String,context:Long){}
                override suspend fun deleteRule(id:String,context:Long){}
            }
            val state=RecordingsState(scope,access=access,log={_,_->},notify={},timingInspect={_,_,_->
                val actual=client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/timing").post(ByteArray(0).toRequestBody()).build());call.set(actual)
                try{actual.await()}finally{cancelled.complete(Unit)};PartTimingFixture.reply()
            })
            try {
                withTimeout(3000){entered.await()};state.load();state.readTiming(state.timingAction(recording,part)!!,true)
                check(arrived.await(3,TimeUnit.SECONDS));state.selectedId="another";parent.cancel()
                withTimeout(3000){cancelled.await()};assertTrue(call.get().isCanceled());assertFalse(parent.isCompleted)
                assertNull(state.timing(part));assertNull(state.timingBusy)
            } finally {response.countDown();release.complete(Unit);sibling.join();state.dispose();scope.cancel();executor.shutdownNow();executor.awaitTermination(3,TimeUnit.SECONDS);client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
        }
    }
}
