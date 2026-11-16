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

/** Actual shared factory and real local HTTP peers, without Coil/device/provider calls. */
class ArtworkImageHttpTest {
    private data class Seen(val url:String,val headers:Map<String,String>)
    private class Peer(private val reply:(Seen)->String):AutoCloseable {
        private val socket=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        private val worker=Executors.newSingleThreadExecutor {r->Thread(r,"synthetic-artwork").apply {isDaemon=true}}
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

    private val headers = mapOf("Authorization" to "Bearer synthetic-backend", "X-Profile-Unlock" to "synthetic-unlock", "X-Remote-Access-Credential" to "synthetic-remote", "X-Client-ID" to "synthetic-client")
    private val success = "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nIMG"
    private fun redirect(url: String) = "HTTP/1.1 302 Found\r\nLocation: $url\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    private fun access(base: HttpUrl): ArtworkImageAccess {
        val owner = PlaybackSessionContext.capture()
        return ArtworkImageAccess(base, headers, owner.profileId, owner::isCurrent)
    }
    private fun call(access: ArtworkImageAccess, url: String, incoming: Map<String, String> = emptyMap(), customize: (OkHttpClient.Builder) -> Unit = {}): String {
        val base = OkHttpClient.Builder().apply(customize).build()
        val client = ArtworkImageAccess.client(base) { access }
        return try {
            client.newCall(Request.Builder().url(url).apply { incoming.forEach { (name,value) -> header(name,value) } }.build()).execute().use { it.body!!.string() }
        } finally { client.dispatcher.executorService.shutdownNow() }
    }
    private fun noBackend(row: Seen) { headers.forEach { (name,value) -> assertNotEquals(value, row.headers[name.lowercase()]) } }
    @Test fun `protected artwork sends account PIN transport and captured profile without query corruption`() {
        Peer { success }.use { peer ->
            call(access(peer.base.resolve("proxy/")!!), peer.base.resolve("proxy/api/library/items/id/artwork/poster?x=a%2Bb&x=c")!!.toString())
            val row=peer.seen.single(); headers.forEach { (name,value) -> assertEquals(value,row.headers[name.lowercase()]) }
            val url=peer.base.resolve(row.url)!!; assertEquals(listOf("a+b","c"),url.queryParameterValues("x")); assertEquals(PlaybackSessionContext.capture().profileId,url.queryParameter("profileId"))
        }
    }
    @Test fun `public icon branding static proxy and sports receive transport only`() {
        Peer { success }.use { peer ->
            val access=access(peer.base.resolve("proxy/")!!)
            for (path in listOf("users/other/icon?v=one", "branding/images/logo", "static/rating_icons/r.png", "images/proxy?url=https%3A%2F%2Fprovider.invalid%2Fx%3Fa%3Db", "images/gif-first-frame?url=https%3A%2F%2Fprovider.invalid%2Fx", "sports/logo?team=one")) call(access,peer.base.resolve("proxy/api/$path")!!.toString())
            assertEquals(6,peer.seen.size)
            peer.seen.forEach { row ->
                assertEquals(headers["X-Client-ID"],row.headers["x-client-id"]); assertEquals(headers["X-Remote-Access-Credential"],row.headers["x-remote-access-credential"])
                assertNull(row.headers["authorization"]); assertNull(row.headers["x-profile-unlock"]); assertFalse(row.url.contains("profileId"))
            }
        }
    }
    @Test fun `same host wrong scheme port proxy or nonimage route gets no injected fields`() {
        Peer { success }.use { peer ->
            val url=peer.base.resolve("proxy/api/users/id/icon")!!.toString()
            call(access("http://127.0.0.1:1/proxy/".toHttpUrl()),url)
            call(access(peer.base.newBuilder().scheme("https").encodedPath("/proxy/").build()),url)
            val access=access(peer.base.resolve("proxy/")!!)
            for(path in listOf("api/users/id/icon","proxy/api/branding","proxy/api/images/warm","proxy/api/admin/status","proxy/api/static/app.js","proxy/api/library/items/id%2Fextra/artwork/poster"))call(access,peer.base.resolve(path)!!.toString())
            peer.seen.forEach(::noBackend)
        }
    }
    @Test fun `protected redirect outside strips only helper injected fields and preserves provider query`() {
        Peer { success }.use { external -> Peer { redirect(external.base.resolve("frame?token=synthetic-provider&x=a%2Bb&x=c")!!.toString()) }.use { own ->
            call(access(own.base.resolve("proxy/")!!),own.base.resolve("proxy/api/library/items/id/artwork/poster")!!.toString())
            noBackend(external.seen.single()); assertEquals("/frame?token=synthetic-provider&x=a%2Bb&x=c",external.seen.single().url)
        } }
    }
    @Test fun `protected redirect to public drops account and PIN but preserves remote pairing`() {
        Peer { row -> if(row.url.contains("library/items")) redirect("/proxy/api/users/other/icon") else success }.use { peer ->
            call(access(peer.base.resolve("proxy/")!!),peer.base.resolve("proxy/api/library/items/id/artwork/poster")!!.toString())
            val row=peer.seen.last(); assertNull(row.headers["authorization"]); assertNull(row.headers["x-profile-unlock"])
            assertEquals(headers["X-Remote-Access-Credential"],row.headers["x-remote-access-credential"]); assertEquals(headers["X-Client-ID"],row.headers["x-client-id"])
        }
    }
    @Test fun `initial external never escalates to native account or transport on redirect into own artwork`() {
        Peer { row -> if(row.url.startsWith("/start")) redirect("/proxy/api/library/items/id/artwork/poster") else success }.use { peer ->
            call(access(peer.base.resolve("proxy/")!!),peer.base.resolve("start")!!.toString(),mapOf("Authorization" to "Bearer synthetic-provider"))
            peer.seen.forEach(::noBackend); assertEquals("Bearer synthetic-provider",peer.seen.last().headers["authorization"])
        }
    }
    @Test fun `initial public redirect to protected cannot acquire account or PIN`() {
        Peer { row -> if(row.url.contains("users/other"))redirect("/proxy/api/library/items/id/artwork/poster")else success }.use { peer ->
            call(access(peer.base.resolve("proxy/")!!),peer.base.resolve("proxy/api/users/other/icon")!!.toString())
            val row=peer.seen.last(); assertNull(row.headers["authorization"]); assertNull(row.headers["x-profile-unlock"]); assertEquals(headers["X-Remote-Access-Credential"],row.headers["x-remote-access-credential"])
        }
    }
    @Test fun `unknown incoming fields are not misclassified as helper injection even when equal backend values`() {
        Peer { success }.use { peer ->
            call(access("http://127.0.0.1:1/".toHttpUrl()),peer.base.resolve("image?token=synthetic-provider")!!.toString(),headers)
            headers.forEach { (name,value) -> assertEquals(value,peer.seen.single().headers[name.lowercase()]) }
        }
    }
    @Test fun `deliberate provider headers are preserved when protected request redirects outside`() {
        Peer { success }.use { external -> Peer { redirect(external.base.resolve("image")!!.toString()) }.use { own ->
            call(access(own.base.resolve("proxy/")!!),own.base.resolve("proxy/api/library/items/id/artwork/poster")!!.toString(),mapOf("X-Remote-Access-Credential" to "synthetic-provider-credential", "X-Profile-Unlock" to "synthetic-provider-unlock"))
            val row=external.seen.single(); assertEquals("synthetic-provider-credential",row.headers["x-remote-access-credential"]); assertEquals("synthetic-provider-unlock",row.headers["x-profile-unlock"]); assertNull(row.headers["x-client-id"])
        } }
    }
    @Test fun `later interceptor provider replacement is retained instead of removed by header name`() {
        Peer { success }.use { external -> Peer { redirect(external.base.resolve("image")!!.toString()) }.use { own ->
            call(access(own.base.resolve("proxy/")!!),own.base.resolve("proxy/api/library/items/id/artwork/poster")!!.toString(),customize={ builder ->
                builder.addNetworkInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("X-Remote-Access-Credential","synthetic-provider-override").build()) }
            })
            assertEquals("synthetic-provider-override",external.seen.single().headers["x-remote-access-credential"])
        } }
    }
    @Test fun `redirected captured secret query is rejected before external HTTP with static error`() {
        Peer { success }.use { external -> Peer { redirect(external.base.resolve("frame?opaque=synthetic-remote")!!.toString()) }.use { own ->
            val error=runCatching {call(access(own.base.resolve("proxy/")!!),own.base.resolve("proxy/api/library/items/id/artwork/poster")!!.toString())}.exceptionOrNull()
            assertTrue(error is IOException); assertFalse(error.toString().contains("synthetic-remote")); assertTrue(external.seen.isEmpty())
        } }
    }
    @Test fun `foreign protected selector rejects at initial request and redirect before target demand`() {
        Peer { row -> if(row.url.startsWith("/start"))redirect("/proxy/api/library/items/id/artwork/poster?PROFILEID=foreign")else success }.use { peer ->
            val access=access(peer.base.resolve("proxy/")!!)
            assertTrue(runCatching {call(access,peer.base.resolve("proxy/api/library/items/id/artwork/poster?profileId=foreign")!!.toString())}.exceptionOrNull() is IOException)
            assertTrue(peer.seen.isEmpty())
            assertTrue(runCatching {call(access,peer.base.resolve("start")!!.toString())}.exceptionOrNull() is IOException); assertEquals(1,peer.seen.size)
        }
    }
    @Test fun `capture cancellation is a safe HTTP failure before demand`() {
        Peer { success }.use { peer ->
            val original = PlaybackSessionContext.capture()
            ProfileDataContext.invalidate()
            val client = ArtworkImageAccess.client(OkHttpClient()) {
                original.requireCurrent()
                error("Stale capture was accepted")
            }
            try {
                val failure = runCatching { client.newCall(Request.Builder().url(peer.base.resolve("image")!!).build()).execute() }.exceptionOrNull()
                assertTrue(failure is IOException); assertEquals("Artwork context changed", failure?.message); assertTrue(peer.seen.isEmpty())
            } finally { client.dispatcher.executorService.shutdownNow() }
        }
    }
    @Test fun `captured A request cannot send or adopt after A B A`() {
        Peer { success }.use { peer ->
            val access=access(peer.base.resolve("proxy/")!!); ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
            assertTrue(runCatching {call(access,peer.base.resolve("proxy/api/library/items/id/artwork/poster")!!.toString())}.exceptionOrNull() is IOException); assertTrue(peer.seen.isEmpty())
        }
        Peer { ProfileDataContext.invalidate(); success }.use { peer ->
            assertTrue(runCatching {call(access(peer.base.resolve("proxy/")!!),peer.base.resolve("proxy/api/users/other/icon")!!.toString())}.exceptionOrNull() is IOException); assertEquals(1,peer.seen.size)
        }
    }
}
