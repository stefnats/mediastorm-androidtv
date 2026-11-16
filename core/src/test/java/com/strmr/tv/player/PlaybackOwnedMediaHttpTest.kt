package com.strmr.tv.player

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.strmr.tv.library.ProfileDataContext
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

/** Real local HTTP crosses the same guard used by the Media3 data source. */
class PlaybackOwnedMediaHttpTest {
    private class Headers:LinkedHashMap<String,MutableList<String>>() {
        fun add(name:String,value:String) {getOrPut(name){mutableListOf()}.add(value)}
        fun getFirst(name:String)=entries.firstOrNull {it.key.equals(name,true)}?.value?.firstOrNull()
    }
    private class Exchange(val requestHeaders:Headers) {
        val responseHeaders=Headers();val responseBody=ByteArrayOutputStream();var status=200
        fun sendResponseHeaders(code:Int,@Suppress("UNUSED_PARAMETER")length:Long) {status=code}
        fun close() {}
    }
    private class Peer:AutoCloseable {
        private val socket=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        private val worker=Executors.newSingleThreadExecutor {r->Thread(r,"synthetic-vod-peer").apply {isDaemon=true}}
        private val handlers=mutableMapOf<String,(Exchange)->Unit>()
        val server get()=this
        val base get()="http://127.0.0.1:${socket.localPort}/".toHttpUrl()
        fun createContext(path:String,handler:(Exchange)->Unit) {handlers[path]=handler}
        fun start() {worker.submit {
            while(!socket.isClosed) {
                val connection=try {socket.accept()}catch(_:IOException){break}
                connection.use {peer->
                    peer.soTimeout=3000;val input=peer.getInputStream().bufferedReader();val first=input.readLine() ?: return@use
                    val headers=Headers()
                    while(true) {val line=input.readLine() ?: break;if(line.isEmpty())break;val split=line.indexOf(':');if(split>0)headers.add(line.take(split),line.drop(split+1).trim())}
                    val exchange=Exchange(headers);val path=first.split(' ')[1].substringBefore('?')
                    handlers[path]?.invoke(exchange) ?: exchange.sendResponseHeaders(404,-1)
                    val body=exchange.responseBody.toByteArray()
                    val prefix=buildString {
                        append("HTTP/1.1 ${exchange.status} Synthetic\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n")
                        exchange.responseHeaders.forEach {(name,values)->values.forEach {append("$name: $it\r\n")}};append("\r\n")
                    }
                    peer.getOutputStream().apply {write(prefix.toByteArray());write(body);flush()}
                }
            }
        }}
        override fun close() {socket.close();worker.shutdownNow();worker.awaitTermination(3,TimeUnit.SECONDS)}
    }
    private fun client(access:VodMediaAccess)=OkHttpClient.Builder().addInterceptor(access.interceptor).addNetworkInterceptor(access.requestInterceptor).build()
    private fun info(error:IOException)=LoadErrorHandlingPolicy.LoadErrorInfo::class.java.constructors.single().newInstance(null,null,error,1) as LoadErrorHandlingPolicy.LoadErrorInfo
    private fun barrier(latch:CountDownLatch){check(latch.await(4,TimeUnit.SECONDS))}
    @Test fun `queued stale session cannot open HTTP or invoke retry fallback under replacement epoch`() {
        Peer().use {peer->
            var received=0;peer.server.createContext("/api/video/stream"){received++;it.sendResponseHeaders(200,-1);it.close()};peer.start()
            val owner=PlaybackSessionContext.capture();val access=VodMediaAccess(peer.base,true,requireCurrent=owner::requireCurrent);val http=client(access)
            ProfileDataContext.invalidate();ProfileDataContext.invalidate()
            try {
                val failure=runCatching {http.newCall(Request.Builder().url(peer.base.resolve("api/video/stream")!!).build()).execute()}.exceptionOrNull()
                assertTrue(failure is IOException);assertEquals(0,received)
                assertEquals(C.TIME_UNSET,access.loadPolicy.getRetryDelayMsFor(info(failure as IOException)))
                assertNull(access.loadPolicy.getFallbackSelectionFor(LoadErrorHandlingPolicy.FallbackOptions(2,0,1,0),info(failure)))
            } finally {http.dispatcher.executorService.shutdownNow()}
        }
    }
    @Test fun `response that arrives after owner change cannot be handed to the old player`()=runBlocking {
        Peer().use {peer->
            val arrived=CountDownLatch(1);val release=CountDownLatch(1);var authorization:String?=null
            peer.server.createContext("/api/video/stream"){exchange->authorization=exchange.requestHeaders.getFirst("Authorization");arrived.countDown();barrier(release);exchange.sendResponseHeaders(200,3);exchange.responseBody.use {it.write("OLD".toByteArray())}}
            peer.start();val owner=PlaybackSessionContext.capture();val access=VodMediaAccess(peer.base,true,requireCurrent=owner::requireCurrent);val http=client(access)
            try {
                val result=async(Dispatchers.IO){runCatching {http.newCall(Request.Builder().url(peer.base.resolve("api/video/stream")!!).header("Authorization","Bearer synthetic-original").build()).execute().use {it.body!!.string()}}}
                withContext(Dispatchers.IO){barrier(arrived)};ProfileDataContext.invalidate();release.countDown()
                assertTrue(result.await().exceptionOrNull() is IOException);assertEquals("Bearer synthetic-original",authorization)
            } finally {release.countDown();http.dispatcher.executorService.shutdownNow()}
        }
    }
    @Test fun `redirect following owner change never reaches another origin with replacement credentials`() {
        Peer().use {external->Peer().use {backend->
            var received=0;external.server.createContext("/file"){received++;it.sendResponseHeaders(200,-1);it.close()};external.start()
            backend.server.createContext("/api/video/stream"){exchange->ProfileDataContext.invalidate();exchange.responseHeaders.add("Location",external.base.resolve("file").toString());exchange.sendResponseHeaders(302,-1);exchange.close()};backend.start()
            val owner=PlaybackSessionContext.capture();val access=VodMediaAccess(backend.base,true,requireCurrent=owner::requireCurrent);val http=client(access)
            try {assertTrue(runCatching {http.newCall(Request.Builder().url(backend.base.resolve("api/video/stream")!!).build()).execute()}.exceptionOrNull() is IOException);assertEquals(0,received)}finally {http.dispatcher.executorService.shutdownNow()}
        }}
    }
}
