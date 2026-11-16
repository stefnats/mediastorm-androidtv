package com.strmr.tv.player

import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.live.HlsUriPolicy
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

/** Real per-hop Media3 transport helper over loopback peers; no decoder/server lease grant. */
class HlsUriHttpTest {
    private data class Seen(val url:String,val headers:Map<String,String>)
    private class Peer(private val reply:(Seen)->String):AutoCloseable {
        private val socket=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        private val worker=Executors.newSingleThreadExecutor {r->Thread(r,"synthetic-hls-uri").apply {isDaemon=true}}
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


    private val headers=mapOf("Authorization" to "Bearer synthetic-backend","X-Profile-Unlock" to "synthetic-unlock","X-Remote-Access-Credential" to "synthetic-pairing","X-Client-ID" to "synthetic-client")
    private val success="HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nHLS"
    private fun redirect(url:String)="HTTP/1.1 302 Found\r\nLocation: $url\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    private fun access(base:HttpUrl,owner:PlaybackSessionContext=PlaybackSessionContext.capture())=VodMediaAccess(base,false,headers,context=owner.epoch,requireCurrent=owner::requireCurrent,profileId=owner.profileId)
    private fun call(access:VodMediaAccess,url:String,incoming:Map<String,String> = headers):String {
        val client=OkHttpClient.Builder().addInterceptor(access.interceptor).addNetworkInterceptor(access.requestInterceptor).build()
        return try{client.newCall(Request.Builder().url(url).apply{incoming.forEach{(k,v)->header(k,v)}}.build()).execute().use{it.body!!.string()}}
            finally{client.dispatcher.executorService.shutdownNow()}
    }
    private fun noBackend(row:Seen){headers.forEach{(k,v)->assertNotEquals(v,row.headers[k.lowercase()])}}
    @Test fun `initial playlist and relative segment each get original profile without inheriting query`() {
        Peer{success}.use{peer->
            val owner=PlaybackSessionContext.capture();val base=peer.base.resolve("proxy/")!!;val access=access(base,owner)
            val initial=HlsUriPolicy(base,owner.profileId).media("/video/hls/id/stream.m3u8?view=a%2Bb"){listOf("token" to "synthetic-backend")}.toHttpUrl()
            call(access,initial.toString())
            val child=initial.resolve("segment0.ts")!!;assertNull(child.queryParameter("profileId"));assertNull(child.queryParameter("token"))
            call(access,child.toString())
            assertEquals(2,peer.seen.size);peer.seen.forEach{assertEquals(owner.profileId,peer.base.resolve(it.url)!!.queryParameter("profileId"))}
            assertNull(peer.base.resolve(peer.seen.last().url)!!.queryParameter("token"));assertEquals(headers["Authorization"],peer.seen.last().headers["authorization"])
        }
    }
    @Test fun `variants init and subtitles retain exact unrelated query bytes`() {
        Peer{success}.use{peer->val owner=PlaybackSessionContext.capture();val access=access(peer.base.resolve("proxy/")!!,owner)
            for(file in listOf("master.m3u8","subtitle-2.m3u8","subtitles-2.vtt","init.mp4","segment1.m4s","captions.srt"))call(access,peer.base.resolve("proxy/api/video/hls/id/$file?x=a%2Bb&x=c")!!.toString())
            assertEquals(6,peer.seen.size);peer.seen.forEach{val url=peer.base.resolve(it.url)!!;assertEquals(owner.profileId,url.queryParameter("profileId"));assertTrue(url.encodedQuery!!.startsWith("x=a%2Bb&x=c&"))}
        }
    }
    @Test fun `conflicting case variants duplicate and null selectors never reach server`() {
        Peer{success}.use{peer->val owner=PlaybackSessionContext.capture();val access=access(peer.base.resolve("proxy/")!!,owner)
            for(query in listOf("profileId=foreign","PROFILEID=foreign","userId=foreign","profileId=","profileId","profileId=${owner.profileId}&profileId=foreign"))assertTrue(runCatching{call(access,peer.base.resolve("proxy/api/video/hls/id/stream.m3u8?$query")!!.toString())}.exceptionOrNull() is IOException)
            assertTrue(peer.seen.isEmpty())
            val query="profileId=${owner.profileId}&PROFILEID=${owner.profileId}&userId=${owner.profileId}&x=a%2Bb"
            call(access,peer.base.resolve("proxy/api/video/hls/id/stream.m3u8?$query")!!.toString());assertEquals(query,peer.base.resolve(peer.seen.single().url)!!.encodedQuery)
        }
    }
    @Test fun `external provider retains query and deliberate Authorization while backend fields are removed`() {
        Peer{success}.use{peer->
            val access=access("http://127.0.0.1:1/proxy/".toHttpUrl());val raw=peer.base.resolve("provider/stream.m3u8?token=synthetic-provider&profileId=provider&x=a%2Bb")!!.toString()
            call(access,raw,headers+("Authorization" to "Bearer synthetic-provider"))
            val row=peer.seen.single();noBackend(row);assertEquals("Bearer synthetic-provider",row.headers["authorization"]);assertEquals("/provider/stream.m3u8?token=synthetic-provider&profileId=provider&x=a%2Bb",row.url)
        }
    }
    @Test fun `backend redirect to provider gets no native profile credentials or rewritten query`() {
        Peer{success}.use{external->Peer{redirect(external.base.resolve("stream.m3u8?provider=one&x=a%2Bb")!!.toString())}.use{own->
            val owner=PlaybackSessionContext.capture();call(access(own.base.resolve("proxy/")!!,owner),own.base.resolve("proxy/api/video/hls/id/stream.m3u8")!!.toString())
            assertEquals(owner.profileId,own.base.resolve(own.seen.single().url)!!.queryParameter("profileId"));val row=external.seen.single();noBackend(row);assertEquals("/stream.m3u8?provider=one&x=a%2Bb",row.url)
        }}
    }
    @Test fun `wrong scheme port proxy and nonasset status routes get no injected profile`() {
        Peer{success}.use{peer->val base=peer.base.resolve("proxy/")!!
            call(access("http://127.0.0.1:1/proxy/".toHttpUrl()),peer.base.resolve("proxy/api/video/hls/id/stream.m3u8")!!.toString())
            call(access(base.newBuilder().scheme("https").build()),peer.base.resolve("proxy/api/video/hls/id/stream.m3u8")!!.toString())
            for(path in listOf("other/api/video/hls/id/stream.m3u8","proxy/api/video/hls/id/status","proxy/api/video/hls/id/cc-status","proxy/api/video/hls/start","proxy/api/video/hls/id%2Fother/stream.m3u8"))call(access(base),peer.base.resolve(path)!!.toString())
            assertEquals(7,peer.seen.size);peer.seen.forEach{assertNull(peer.base.resolve(it.url)!!.queryParameter("profileId"))}
        }
    }
    @Test fun `sibling proxy paths strip only known backend header values and preserve provider auth`() {
        Peer{success}.use{peer->
            call(access(peer.base.resolve("proxy/")!!),peer.base.resolve("sibling/stream.m3u8?x=a%2Bb")!!.toString(),headers+("Authorization" to "Bearer synthetic-provider"))
            val row=peer.seen.single();noBackend(row);assertEquals("Bearer synthetic-provider",row.headers["authorization"]);assertEquals("/sibling/stream.m3u8?x=a%2Bb",row.url)
        }
    }
    @Test fun `redirect to foreign owned profile is rejected before target demand`() {
        Peer{row->if(row.url.startsWith("/start"))redirect("/proxy/api/video/hls/id/stream.m3u8?USERID=foreign")else success}.use{peer->
            assertTrue(runCatching{call(access(peer.base.resolve("proxy/")!!),peer.base.resolve("start")!!.toString())}.exceptionOrNull() is IOException);assertEquals(1,peer.seen.size)
        }
    }
    @Test fun `old player epoch cannot send child request or return reply after simulated A B A`() {
        Peer{success}.use{peer->val owner=PlaybackSessionContext.capture();val access=access(peer.base.resolve("proxy/")!!,owner);ProfileDataContext.invalidate();ProfileDataContext.invalidate()
            assertTrue(runCatching{call(access,peer.base.resolve("proxy/api/video/hls/id/segment0.ts")!!.toString())}.exceptionOrNull() is IOException);assertTrue(peer.seen.isEmpty())
        }
        Peer{ProfileDataContext.invalidate();success}.use{peer->assertTrue(runCatching{call(access(peer.base.resolve("proxy/")!!),peer.base.resolve("proxy/api/video/hls/id/segment0.ts")!!.toString())}.exceptionOrNull() is IOException);assertEquals(1,peer.seen.size)}
    }
}
