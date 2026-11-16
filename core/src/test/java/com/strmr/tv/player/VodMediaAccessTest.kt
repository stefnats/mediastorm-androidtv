package com.strmr.tv.player

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.strmr.tv.api.VodAccessDenial
import java.io.IOException
import java.net.ServerSocket
import java.net.InetAddress
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

/** Hermetic HTTP redirects exercise the real player interceptors; no provider or Android device. */
class VodMediaAccessTest {
    private val marker="X-Mediastorm-Local-Vod-Denial"
    private val privateHeaders=mapOf("Authorization" to "Bearer synthetic-backend","X-Profile-Unlock" to "synthetic-unlock","X-Remote-Access-Credential" to "synthetic-remote","X-Client-ID" to "synthetic-client")
    private class Headers:LinkedHashMap<String,MutableList<String>>() {
        fun add(name:String,value:String) {getOrPut(name){mutableListOf()}.add(value)}
        fun getFirst(name:String)=entries.firstOrNull {it.key.equals(name,true)}?.value?.firstOrNull()
    }
    private class Exchange(val requestHeaders:Headers) {
        val responseHeaders=Headers();val responseBody=ByteArrayOutputStream();var status=200
        fun sendResponseHeaders(code:Int,@Suppress("UNUSED_PARAMETER")length:Long) {status=code}
        fun close() {}
    }
    private class Server:AutoCloseable {
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
    private fun failure(code:Int,headers:Map<String,List<String>>):HttpDataSource.InvalidResponseCodeException {
        // This exception only stores DataSpec; a real Android Uri is not needed to exercise its headers/policy.
        val constructor=HttpDataSource.InvalidResponseCodeException::class.java.constructors.single {it.parameterCount==6}
        return constructor.newInstance(code,"Synthetic",null,headers,null,byteArrayOf()) as HttpDataSource.InvalidResponseCodeException
    }
    private fun info(error:IOException):LoadErrorHandlingPolicy.LoadErrorInfo =
        LoadErrorHandlingPolicy.LoadErrorInfo::class.java.constructors.single().newInstance(null,null,error,1) as LoadErrorHandlingPolicy.LoadErrorInfo

    @Test fun `own protected denial is marked locally bounded and never retried or failed over`() {
        Server().use {server->
            val body="""{"code":"kids_vod_classification_unknown","error":"synthetic-private-path"}"""
            server.server.createContext("/api/video/stream") {exchange->exchange.sendResponseHeaders(403,body.length.toLong());exchange.responseBody.use {it.write(body.toByteArray())}}
            server.start();val access=VodMediaAccess(server.base,true,privateHeaders)
            client(access).newCall(Request.Builder().url(server.base.resolve("api/video/stream")!!).build()).execute().use {response->
                assertEquals(body,response.body!!.string())
                val error=failure(response.code,response.headers.toMultimap())
                assertEquals(VodAccessDenial.CLASSIFICATION_UNKNOWN,access.denial(error))
                assertEquals(C.TIME_UNSET,access.loadPolicy.getRetryDelayMsFor(info(error)))
                assertNull(access.loadPolicy.getFallbackSelectionFor(LoadErrorHandlingPolicy.FallbackOptions(2,0,1,0),info(error)))
                assertFalse(access.denial(error)!!.message.contains("private"))
                assertNull(VodMediaAccess(server.base,true,privateHeaders).denial(error))
            }
        }
    }
    @Test fun `backend redirect to an external origin cannot carry backend credentials or forge a denial`() {
        Server().use {external->Server().use {backend->
            var received:Map<String,List<String>>?=null
            external.server.createContext("/api/video/stream") {exchange->
                received=exchange.requestHeaders.toMap();exchange.responseHeaders.add(marker,"forged:BLOCKED");exchange.responseHeaders.add(marker.lowercase(),"forged:BLOCKED")
                exchange.sendResponseHeaders(403,-1);exchange.close()
            }
            external.start()
            backend.server.createContext("/api/video/stream") {exchange->exchange.responseHeaders.add("Location",external.base.resolve("api/video/stream").toString());exchange.sendResponseHeaders(302,-1);exchange.close()}
            backend.start();val access=VodMediaAccess(backend.base,true,privateHeaders)
            val request=Request.Builder().url(backend.base.resolve("api/video/stream")!!).apply {privateHeaders.forEach { (k,v)->header(k,v)};header(marker,"outgoing-forged")}.build()
            client(access).newCall(request).execute().use {response->
                assertEquals(external.base.port,response.request.url.port);assertNull(response.header(marker));assertNull(access.denial(failure(403,response.headers.toMultimap())))
                for(name in privateHeaders.keys+marker)assertTrue(received!!.keys.none {it.equals(name,true)})
                val unmarked=failure(403,response.headers.toMultimap())
                assertTrue(access.loadPolicy.getRetryDelayMsFor(info(unmarked))>=0)
                assertNotNull(access.loadPolicy.getFallbackSelectionFor(LoadErrorHandlingPolicy.FallbackOptions(2,0,1,0),info(unmarked)))
            }
        }}
    }
    @Test fun `explicit external provider authorization survives while captured backend values do not`() {
        Server().use {external->
            var authorization:String?=null;var unlock:String?=null
            external.server.createContext("/file") {exchange->authorization=exchange.requestHeaders.getFirst("Authorization");unlock=exchange.requestHeaders.getFirst("X-Profile-Unlock");exchange.sendResponseHeaders(403,-1);exchange.close()}
            external.start();val access=VodMediaAccess("http://127.0.0.1:1/".toHttpUrl(),true,privateHeaders)
            val request=Request.Builder().url(external.base.resolve("file")!!).header("Authorization","Bearer synthetic-provider").header("X-Profile-Unlock",privateHeaders.getValue("X-Profile-Unlock")).build()
            client(access).newCall(request).execute().close()
            assertEquals("Bearer synthetic-provider",authorization);assertNull(unlock)
        }
    }
    @Test fun `only captured backend protected paths are recognized and profile lock keeps its distinct reason`() {
        Server().use {server->
            for(path in listOf("/prefix/api/video/stream","/not-protected"))server.server.createContext(path) {exchange->
                val body="""{"code":"profile_locked"}""";exchange.sendResponseHeaders(403,body.length.toLong());exchange.responseBody.use {it.write(body.toByteArray())}
            }
            server.start();val access=VodMediaAccess(server.base.resolve("prefix/")!!,true,privateHeaders)
            val c=client(access)
            c.newCall(Request.Builder().url(server.base.resolve("prefix/api/video/stream")!!).build()).execute().use {assertEquals(VodAccessDenial.PROFILE_LOCKED,access.denial(failure(403,it.headers.toMultimap())))}
            c.newCall(Request.Builder().url(server.base.resolve("not-protected")!!).build()).execute().use {assertNull(access.denial(failure(403,it.headers.toMultimap())))}
            assertNull(access.denial(IOException("403 is only text")))
        }
    }
    @Test fun `structured profile selection denial at 400 is terminal for VOD and catchup`() {
        Server().use {server->
            server.server.createContext("/api/video/stream") {exchange->val body="""{"code":"kids_vod_profile_required"}""";exchange.sendResponseHeaders(400,body.length.toLong());exchange.responseBody.use {it.write(body.toByteArray())}}
            server.start()
            for(enabled in listOf(true,false)) {
                val access=VodMediaAccess(server.base,enabled,privateHeaders)
                client(access).newCall(Request.Builder().url(server.base.resolve("api/video/stream")!!).build()).execute().use {response->
                    assertEquals(VodAccessDenial.PROFILE_REQUIRED,access.denial(failure(400,response.headers.toMultimap())))
                }
            }
        }
    }
    @Test fun `generated own HLS asset auth denial is terminal even for live or recording sessions`() {
        Server().use {server->
            server.server.createContext("/api/video/hls/owned/segment0.ts") {exchange->exchange.sendResponseHeaders(403,-1)}
            server.start();val access=VodMediaAccess(server.base,false,privateHeaders)
            client(access).newCall(Request.Builder().url(server.base.resolve("api/video/hls/owned/segment0.ts")!!).build()).execute().use {response->
                val error=failure(403,response.headers.toMultimap())
                assertEquals(VodAccessDenial.ACCESS_DENIED,access.denial(error));assertEquals(C.TIME_UNSET,access.loadPolicy.getRetryDelayMsFor(info(error)))
            }
        }
    }
    @Test fun `uncoded forwarded provider403 on raw live stream keeps legacy retry behavior`() {
        Server().use {server->
            server.server.createContext("/api/video/stream") {exchange->val body="external stream error: 403";exchange.sendResponseHeaders(403,body.length.toLong());exchange.responseBody.write(body.toByteArray())}
            server.start();val access=VodMediaAccess(server.base,false,privateHeaders)
            client(access).newCall(Request.Builder().url(server.base.resolve("api/video/stream")!!).build()).execute().use {response->
                val error=failure(403,response.headers.toMultimap());assertNull(access.denial(error));assertTrue(access.loadPolicy.getRetryDelayMsFor(info(error))>=0)
            }
        }
    }
    @Test fun `structured denial from external CDN cannot impersonate owned HLS authorization`() {
        Server().use {external->
            external.server.createContext("/api/video/hls/owned/segment0.ts") {exchange->val body="""{"code":"kids_vod_access_denied"}""";exchange.sendResponseHeaders(403,body.length.toLong());exchange.responseBody.write(body.toByteArray())}
            external.start();val access=VodMediaAccess("http://127.0.0.1:1/".toHttpUrl(),false,privateHeaders)
            client(access).newCall(Request.Builder().url(external.base.resolve("api/video/hls/owned/segment0.ts")!!).build()).execute().use {response->assertNull(access.denial(failure(403,response.headers.toMultimap())))}
        }
    }

}
