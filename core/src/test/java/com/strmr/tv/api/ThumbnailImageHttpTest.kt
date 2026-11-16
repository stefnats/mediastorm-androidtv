package com.strmr.tv.api

import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.player.PlaybackSessionContext
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

/** Real synthetic HTTP peers observe the exact headers and query after each redirect hop. */
class ThumbnailImageHttpTest {
    private data class Seen(val url:String,val headers:Map<String,String>)
    private class Peer(private val reply:(Seen)->String):AutoCloseable {
        private val socket=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        private val worker=Executors.newSingleThreadExecutor {r->Thread(r,"synthetic-thumbnail").apply {isDaemon=true}}
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
    private val headers=mapOf("Authorization" to "Bearer synthetic-backend","X-Profile-Unlock" to "synthetic-unlock","X-Remote-Access-Credential" to "synthetic-remote","X-Client-ID" to "synthetic-client")
    private val image="proxy/api/video/thumbnails/image/key/frame.jpg"
    private val success="HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nIMG"
    private fun redirect(url:String)="HTTP/1.1 302 Found\r\nLocation: $url\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    private fun access(base:HttpUrl)=ThumbnailImageAccess(base,headers,PlaybackSessionContext.capture())
    private fun call(access:ThumbnailImageAccess,url:String,providerAuthorization:String?=null):String {
        val client=access.client(OkHttpClient())
        return try {client.newCall(Request.Builder().url(url).apply {providerAuthorization?.let {header("Authorization",it)}}.build()).execute().use {it.body!!.string()}}finally {client.dispatcher.executorService.shutdownNow()}
    }
    private fun noBackend(row:Seen){headers.forEach {(name,value)->assertNotEquals(value,row.headers[name.lowercase()])}}
    @Test fun `own protected image receives captured credentials profile and unchanged ordinary query`() {
        Peer {success}.use {peer->
            val access=access(peer.base.resolve("proxy/")!!);val model=access.image("/api/video/thumbnails/image/key/frame.jpg?view=a%2Bb&size=one&size=two")!!
            assertEquals("IMG",call(access,model.url));val seen=peer.seen.single();val query=peer.base.resolve(seen.url)!!
            assertEquals("/proxy/api/video/thumbnails/image/key/frame.jpg",query.encodedPath);assertEquals("a+b",query.queryParameter("view"));assertEquals(listOf("one","two"),query.queryParameterValues("size"));assertEquals(PlaybackSessionContext.capture().profileId,query.queryParameter("profileId"))
            headers.forEach {(name,value)->assertEquals(value,seen.headers[name.lowercase()])}
        }
    }
    @Test fun `external initial image preserves provider query and distinct provider authorization`() {
        Peer {success}.use {peer->
            val access=access("http://127.0.0.1:1/proxy/".toHttpUrl());val raw=peer.base.resolve("frame?token=synthetic-provider&profileId=provider&x=one&x=two")!!.toString()
            assertEquals(raw,access.image(raw)!!.url);call(access,raw,"Bearer synthetic-provider-auth")
            val seen=peer.seen.single();noBackend(seen);assertEquals("Bearer synthetic-provider-auth",seen.headers["authorization"]);assertEquals("/frame?token=synthetic-provider&profileId=provider&x=one&x=two",seen.url)
        }
    }
    @Test fun `backend redirect strips captured header values without rewriting provider query`() {
        Peer {success}.use {external->Peer {redirect(external.base.resolve("frame?token=synthetic-provider&profileId=provider")!!.toString())}.use {own->
            val access=access(own.base.resolve("proxy/")!!);call(access,access.image("/api/video/thumbnails/image/key/frame.jpg")!!.url)
            noBackend(external.seen.single());assertEquals("/frame?token=synthetic-provider&profileId=provider",external.seen.single().url)
        }}
    }
    @Test fun `same host with different port wrong path or scheme receives no backend credentials`() {
        Peer {success}.use {peer->
            val url=peer.base.resolve(image)!!.toString()
            call(access("http://127.0.0.1:1/proxy/".toHttpUrl()),url)
            call(access(peer.base.resolve("proxy/")!!),peer.base.resolve("public/frame?provider=one")!!.toString())
            call(access(peer.base.newBuilder().scheme("https").encodedPath("/proxy/").build()),url)
            assertEquals(3,peer.seen.size);peer.seen.forEach {noBackend(it);assertFalse(it.url.contains("profileId"))}
        }
    }
    @Test fun `unregistered malformed image subpaths do not receive backend credentials`() {
        Peer {success}.use {peer->
            val access=access(peer.base.resolve("proxy/")!!)
            for(path in listOf("proxy/api/video/thumbnails/image/","proxy/api/video/thumbnails/image/key/extra/frame.jpg","proxy/api/video/thumbnails/image/key/file%2Fextra.jpg"))call(access,peer.base.resolve(path)!!.toString())
            assertEquals(3,peer.seen.size);peer.seen.forEach(::noBackend)
        }
    }
    @Test fun `redirect with embedded exact backend secret is rejected before external HTTP`() {
        Peer {success}.use {external->Peer {redirect(external.base.resolve("frame?opaque=synthetic-remote")!!.toString())}.use {own->
            val access=access(own.base.resolve("proxy/")!!)
            assertTrue(runCatching {call(access,access.image("/api/video/thumbnails/image/key/frame.jpg")!!.url)}.exceptionOrNull() is IOException)
            assertTrue(external.seen.isEmpty())
        }}
    }
    @Test fun `foreign selector redirect is rejected instead of being rebound to current profile`() {
        Peer {seen->if(seen.url.startsWith("/start"))redirect("/proxy/api/video/thumbnails/image/key/frame.jpg?profileId=synthetic-foreign")else success}.use {peer->
            val access=access(peer.base.resolve("proxy/")!!)
            assertTrue(runCatching {call(access,peer.base.resolve("start")!!.toString())}.exceptionOrNull() is IOException);assertEquals(1,peer.seen.size)
        }
    }
    @Test fun `queued old image cannot send credentials or publish bytes after A B A`() {
        Peer {success}.use {peer->
            val access=access(peer.base.resolve("proxy/")!!);val model=access.image("/api/video/thumbnails/image/key/frame.jpg")!!
            ProfileDataContext.invalidate();ProfileDataContext.invalidate()
            assertTrue(runCatching {call(access,model.url)}.exceptionOrNull() is IOException);assertTrue(peer.seen.isEmpty())
        }
    }
    @Test fun `HTTP image reply after context loss is closed before returning a frame`() {
        Peer {ProfileDataContext.invalidate();success}.use {peer->
            val access=access(peer.base.resolve("proxy/")!!);val model=access.image("/api/video/thumbnails/image/key/frame.jpg")!!
            assertTrue(runCatching {call(access,model.url)}.exceptionOrNull() is IOException);assertEquals(1,peer.seen.size)
        }
    }
}
