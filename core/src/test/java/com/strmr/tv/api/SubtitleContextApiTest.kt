package com.strmr.tv.api

import com.strmr.tv.library.ProfileDataContext
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

/** Actual stale APIs; successful loopback uses production query/context helpers, not Android send. */
class SubtitleContextApiTest {
    private data class Seen(val url:String,val headers:Map<String,String>)
    private class Peer(private val reply:(Seen)->String):AutoCloseable {
        private val socket=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        private val worker=Executors.newSingleThreadExecutor {r->Thread(r,"synthetic-subtitles").apply {isDaemon=true}}
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
    private suspend fun cancelled(action:suspend()->Unit){try{action();fail("Stale request accepted")}catch(_:CancellationException){}}
    @Test fun `all actual subtitle APIs reject stale owner before Android credentials or HTTP`()=runBlocking {
        Peer{success}.use{peer->val previous=ApiClient.baseUrl
            try{
                ApiClient.baseUrl=peer.base;val expected=ProfileDataContext.current;ProfileDataContext.invalidate();ProfileDataContext.invalidate()
                val query=SubtitleQuery("tt0000041","Synthetic",null,2020,null,null);val result=SubtitleSearchResult("synthetic-id","synthetic-provider")
                cancelled{ApiClient.searchSubtitles(query,"fra",expected)};cancelled{ApiClient.downloadSubtitle(result,query,"fra",expected)}
                cancelled{ApiClient.startSubtitleExtract("synthetic-file",1,0.0,expected)};cancelled{ApiClient.translateSubtitle("/api/video/subtitles/serve?sessionId=synthetic","fra","eng",expected)}
                cancelled{ApiClient.subtitleServerSettings(expected)}
                assertTrue(peer.seen.isEmpty());assertEquals(0,ApiClient.http.dispatcher.runningCallsCount());assertEquals(0,ApiClient.http.dispatcher.queuedCallsCount())
            }finally{ApiClient.baseUrl=previous}
        }
    }
    @Test fun `actual translation query keeps source and language and both selectors agree over real HTTP`()=runBlocking {
        Peer{success}.use{peer->
            val expected=ProfileDataContext.current;val query=ApiClient.subtitleTranslationQuery("/api/video/subtitles/serve?sessionId=a+b&part=one","fra","eng",expected)
            assertEquals(query["profileId"],query["userId"])
            val gate=RequestContext({ProfileDataContext.current},{ProfileDataContext.invalidate()});val context=gate.capture(expected)
            val url=peer.base.resolve("api/subtitles/translate")!!.newBuilder().apply{query.forEach{(k,v)->addQueryParameter(k,v)}}.build();val client=OkHttpClient()
            try{ContextBoundTransport(context,false,{false},{fail("Unexpected recovery")}).execute{client.newCall(Request.Builder().url(url).build()).execute().use{it.body!!.string()}}
                val actual=peer.base.resolve(peer.seen.single().url)!!
                assertEquals(ApiClient.profileId,actual.queryParameter("profileId"));assertEquals(actual.queryParameter("profileId"),actual.queryParameter("userId"))
                assertEquals("/api/video/subtitles/serve?sessionId=a+b&part=one",actual.queryParameter("sourceUrl"));assertEquals("fra",actual.queryParameter("targetLanguage"));assertEquals("eng",actual.queryParameter("sourceLanguage"))
            }finally{client.dispatcher.executorService.shutdownNow()}
        }
    }
}
