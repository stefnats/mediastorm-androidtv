package com.strmr.mobile.downloads

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

/** Real loopback redirect peers exercise captured backend credentials without an external provider. */
class OwnedDownloadHttpTest {
    private data class Seen(val path:String,val headers:Map<String,String>)
    private class Peer(private val reply:(Seen)->String):AutoCloseable {
        private val socket=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        private val worker=Executors.newSingleThreadExecutor {r->Thread(r,"synthetic-owned-download").apply {isDaemon=true}}
        val seen=CopyOnWriteArrayList<Seen>()
        val base="http://127.0.0.1:${socket.localPort}"
        init {worker.submit {
            while(!socket.isClosed) {
                val connection=try {socket.accept()}catch(_:IOException){break}
                runCatching {connection.use {client->
                    client.soTimeout=1000
                    val input=client.getInputStream().bufferedReader();val first=input.readLine() ?: return@use
                    val headers=mutableMapOf<String,String>()
                    while(true) {val line=input.readLine() ?: break;if(line.isEmpty())break;val split=line.indexOf(':');if(split>0)headers[line.take(split).lowercase()]=line.drop(split+1).trim()}
                    val request=Seen(first.split(' ')[1],headers);seen+=request
                    client.getOutputStream().apply {write(reply(request).toByteArray());flush()}
                }}
            }
        }}
        override fun close() {socket.close();worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS)}
    }
    private val success="HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nNEW"
    @Test fun `backend redirect cannot send captured account pairing or PIN credentials to another origin`() {
        Peer {success}.use {external->
            Peer {"HTTP/1.1 302 Found\r\nLocation: ${external.base}/file?profileUnlock=synthetic-unlock\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"}.use {backend->
                val values=mapOf("Authorization" to "Bearer synthetic-account-a","X-Remote-Access-Credential" to "synthetic-pairing-a","X-Profile-Unlock" to "synthetic-unlock","X-Client-ID" to "synthetic-device-a")
                val request=Request.Builder().url(backend.base+"/api/video/stream?profileUnlock=synthetic-unlock").apply {values.forEach {(k,v)->header(k,v)}}.build()
                val client=OwnedDownloadHttp(request,backend.base.toHttpUrl(),values){}.client(OkHttpClient())
                try {client.newCall(request).execute().use {assertEquals("NEW",it.body!!.string())}}finally {client.dispatcher.executorService.shutdownNow()}
                assertEquals("synthetic-pairing-a",backend.seen.single().headers["x-remote-access-credential"])
                val received=external.seen.single();assertFalse(received.path.contains("profileUnlock"));values.keys.forEach {assertNull(received.headers[it.lowercase()])}
            }
        }
    }
    @Test fun `direct provider-intended authorization is retained rather than treated as backend credentials`() {
        Peer {success}.use {provider->
            val request=Request.Builder().url(provider.base+"/file").header("Authorization","Bearer synthetic-provider").build()
            val client=OwnedDownloadHttp(request,"http://127.0.0.1:1/".toHttpUrl(),mapOf("Authorization" to "Bearer synthetic-backend")){}.client(OkHttpClient())
            try {client.newCall(request).execute().close()}finally {client.dispatcher.executorService.shutdownNow()}
            assertEquals("Bearer synthetic-provider",provider.seen.single().headers["authorization"])
        }
    }
    @Test fun `queued old owner request cannot borrow replacement context headers or reach server`() {
        Peer {success}.use {backend->
            var epoch=1;val captured=epoch
            val request=Request.Builder().url(backend.base+"/api/video/stream").header("Authorization","Bearer synthetic-a").build()
            val client=OwnedDownloadHttp(request,backend.base.toHttpUrl(),mapOf("Authorization" to "Bearer synthetic-a")){if(epoch!=captured)throw CancellationException("Synthetic context changed")}.client(OkHttpClient())
            epoch=2
            try {assertTrue(runCatching {client.newCall(request).execute()}.exceptionOrNull() is CancellationException);assertTrue(backend.seen.isEmpty())}finally {client.dispatcher.executorService.shutdownNow()}
        }
    }
}
