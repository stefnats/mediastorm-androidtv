package com.strmr.tv.api

import com.strmr.tv.library.ProfileDataContext
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

/** Production query/context helpers over synthetic HTTP; not a successful Android ApiClient send. */
class PreparationContextApiTest {
    private data class Seen(val url:String,val headers:Map<String,String>)
    private class Peer(private val reply:(Seen)->String):AutoCloseable {
        private val socket=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        private val worker=Executors.newSingleThreadExecutor {r->Thread(r,"synthetic-preparation").apply {isDaemon=true}}
        val seen=CopyOnWriteArrayList<Seen>()
        val base="http://127.0.0.1:${socket.localPort}/".toHttpUrl()
        init {worker.submit {
            while(!socket.isClosed) {
                val connection=try {socket.accept()}catch(_:IOException){break}
                runCatching {connection.use {client->
                    client.soTimeout=1000;val input=client.getInputStream().bufferedReader();val first=input.readLine() ?: return@use
                    val headers=mutableMapOf<String,String>()
                    while(true){val line=input.readLine() ?: break;if(line.isEmpty())break;val split=line.indexOf(':');if(split>0)headers[line.take(split).lowercase()]=line.drop(split+1).trim()}
                    val row=Seen(first.split(' ')[1],headers);seen+=row
                    client.getOutputStream().apply {write(reply(row).toByteArray());flush()}
                }}
            }
        }}
        override fun close(){socket.close();worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS)}
    }


    private val success="HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"
    private suspend fun cancelled(action:suspend()->Unit) {try {action();fail("Old context accepted")}catch(_:CancellationException){}}
    private fun barrier(latch:CountDownLatch) {check(latch.await(3,TimeUnit.SECONDS)){"Synthetic peer timed out"}}
    @Test fun `actual scoped query includes captured profile and retains health token and wait`() {
        val expected=ProfileDataContext.current
        val query=ApiClient.preparationQuery(expected,mapOf("token" to "synthetic-health+a&b", "waitMs" to "20000"))
        assertEquals(mapOf("token" to "synthetic-health+a&b","waitMs" to "20000","profileId" to ApiClient.profileId),query)
        assertFalse(query.containsKey("profileUnlock"));assertFalse(query.containsKey("Authorization"))
    }
    @Test fun `production selector helper reaches real HTTP without changing opaque query values`() = runBlocking {
        Peer {success}.use {peer ->
            val expected=ProfileDataContext.current
            val gate=RequestContext({ProfileDataContext.current},{ProfileDataContext.invalidate()})
            val scope=gate.capture(expected);val query=ApiClient.preparationQuery(expected,mapOf("token" to "synthetic-health+a&b","waitMs" to "20000"))
            val url=peer.base.resolve("api/indexers/source-health")!!.newBuilder().apply {query.forEach{(name,value)->addQueryParameter(name,value)}}.build()
            val client=OkHttpClient()
            try {
                ContextBoundTransport(scope,false,{false},{fail("Unexpected transport failure")}).execute {
                    client.newCall(Request.Builder().url(url).build()).execute().use {it.body!!.string()}
                }
                val row=peer.seen.single();val actual=peer.base.resolve(row.url)!!
                assertEquals(ApiClient.profileId,actual.queryParameter("profileId"));assertEquals("synthetic-health+a&b",actual.queryParameter("token"));assertEquals("20000",actual.queryParameter("waitMs"));assertNull(row.headers["authorization"])
            } finally {client.dispatcher.executorService.shutdownNow()}
        }
    }
    @Test fun `actual ApiClient preparation APIs reject old caller before any HTTP or Android credential store read`() = runBlocking {
        Peer {success}.use {peer ->
            val previous=ApiClient.baseUrl
            try {
                ApiClient.baseUrl=peer.base
                val expected=ProfileDataContext.current;ProfileDataContext.invalidate();ProfileDataContext.invalidate()
                cancelled {ApiClient.prequeueStatus("synthetic-owned",expected)}
                cancelled {ApiClient.releaseReportOptions("synthetic-owned",expected)}
                cancelled {ApiClient.submitReleaseAction("synthetic-owned",true,buildJsonObject {},expected)}
                cancelled {ApiClient.submitReleaseAction("synthetic-owned",false,buildJsonObject {},expected)}
                cancelled {ApiClient.cancelReleaseAlternative("synthetic-owned",expected)}
                cancelled {ApiClient.undoReleaseReport("synthetic-report",expected)}
                cancelled {ApiClient.sourceHealth("synthetic-health",expected)}
                assertTrue(peer.seen.isEmpty());assertEquals(0,ApiClient.http.dispatcher.runningCallsCount());assertEquals(0,ApiClient.http.dispatcher.queuedCallsCount())
            } finally {ApiClient.baseUrl=previous}
        }
    }
    @Test fun `context changes after query construction cannot send old prepared HTTP under replacement`() = runBlocking {
        Peer {success}.use {peer ->
            val expected=ProfileDataContext.current;val gate=RequestContext({ProfileDataContext.current},{ProfileDataContext.invalidate()});val scope=gate.capture(expected)
            val query=ApiClient.preparationQuery(expected)
            val url=peer.base.resolve("api/playback/prequeue/synthetic-owned/report")!!.newBuilder().apply {query.forEach{(n,v)->addQueryParameter(n,v)}}.build()
            ProfileDataContext.invalidate();ProfileDataContext.invalidate()
            val client=OkHttpClient()
            try {
                cancelled {ContextBoundTransport(scope,false,{false},{fail("Stale transport recovery")}).execute {
                    client.newCall(Request.Builder().url(url).build()).execute().close()
                }}
                assertTrue(peer.seen.isEmpty())
            } finally {client.dispatcher.executorService.shutdownNow()}
        }
    }
    @Test fun `real HTTP completion after A B A cannot return owned status or start retry under B`() = runBlocking {
        val arrived=CountDownLatch(1);val release=CountDownLatch(1)
        Peer {arrived.countDown();barrier(release);success}.use {peer ->
            val expected=ProfileDataContext.current;val gate=RequestContext({ProfileDataContext.current},{ProfileDataContext.invalidate()});val scope=gate.capture(expected)
            val query=ApiClient.preparationQuery(expected)
            val url=peer.base.resolve("api/playback/prequeue/synthetic-owned")!!.newBuilder().apply {query.forEach{(n,v)->addQueryParameter(n,v)}}.build()
            val client=OkHttpClient()
            try {
                val request=async(Dispatchers.IO){ContextBoundTransport(scope,false,{fail("No retry");false},{fail("No recovery")}).execute {client.newCall(Request.Builder().url(url).build()).execute().use{it.body!!.string()}}}
                barrier(arrived);ProfileDataContext.invalidate();ProfileDataContext.invalidate();release.countDown()
                cancelled {request.await()};assertEquals(1,peer.seen.size)
                assertEquals(ApiClient.profileId,peer.base.resolve(peer.seen.single().url)!!.queryParameter("profileId"))
            } finally {release.countDown();client.dispatcher.executorService.shutdownNow()}
        }
    }
}
