package com.strmr.tv.api

import com.strmr.tv.library.ProfileDataContext
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

/** Successful HTTP uses production query/transport helpers; actual APIs are tested stale-before-IO. */
class SkipContextApiTest {
    private data class Seen(val url:String,val headers:Map<String,String>)
    private class Peer(private val reply:(Seen)->String):AutoCloseable {
        private val socket=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        private val worker=Executors.newSingleThreadExecutor {r->Thread(r,"synthetic-segments").apply {isDaemon=true}}
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
    @Test fun `actual segment and metadata APIs reject old context before Android credentials or HTTP`()=runBlocking {
        Peer {success}.use {peer->val previous=ApiClient.baseUrl
            try {
                ApiClient.baseUrl=peer.base;val expected=ProfileDataContext.current;ProfileDataContext.invalidate();ProfileDataContext.invalidate()
                try {ApiClient.skipSegments(buildJsonObject {},expected);fail("Stale segments accepted")}catch(_:CancellationException){}
                try {ApiClient.videoChapters("synthetic-file",expected);fail("Stale metadata accepted")}catch(_:CancellationException){}
                assertTrue(peer.seen.isEmpty());assertEquals(0,ApiClient.http.dispatcher.runningCallsCount());assertEquals(0,ApiClient.http.dispatcher.queuedCallsCount())
            }finally{ApiClient.baseUrl=previous}
        }
    }
    @Test fun `existing scoped query preserves exact path and sends captured selector over loopback HTTP`()=runBlocking {
        Peer {success}.use {peer->
            val expected=ProfileDataContext.current;val gate=RequestContext({ProfileDataContext.current},{ProfileDataContext.invalidate()});val scope=gate.capture(expected)
            val query=ApiClient.preparationQuery(expected,mapOf("path" to "synthetic-file+part&name"))
            val url=peer.base.resolve("api/video/metadata")!!.newBuilder().apply{query.forEach{(k,v)->addQueryParameter(k,v)}}.build();val client=OkHttpClient()
            try {
                ContextBoundTransport(scope,false,{false},{fail("Unexpected failure")}).execute {client.newCall(Request.Builder().url(url).build()).execute().use{it.body!!.string()}}
                val actual=peer.base.resolve(peer.seen.single().url)!!;assertEquals(ApiClient.profileId,actual.queryParameter("profileId"));assertEquals("synthetic-file+part&name",actual.queryParameter("path"))
            }finally{client.dispatcher.executorService.shutdownNow()}
        }
    }
}
