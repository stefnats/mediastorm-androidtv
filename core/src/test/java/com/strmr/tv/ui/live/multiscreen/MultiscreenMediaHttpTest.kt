package com.strmr.tv.ui.live.multiscreen

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.player.PlaybackSessionContext
import com.strmr.tv.player.VodMediaAccess
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

/** Actual tile guard plus installed shared interceptors over loopback; no Media3 decoder. */
class MultiscreenMediaHttpTest {
    private data class Seen(val path: String, val headers: Map<String, String>)
    private class Peer(private val respond: (Seen) -> String): AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "synthetic-tile-http").apply { isDaemon = true } }
        val base = "http://127.0.0.1:${socket.localPort}/".toHttpUrl()
        val seen = CopyOnWriteArrayList<Seen>()
        init { worker.submit {
            while (!socket.isClosed) {
                val peer = try { socket.accept() } catch (_: IOException) { break }
                runCatching { peer.use {
                    peer.soTimeout = 1500; val input = peer.getInputStream().bufferedReader(); val first = input.readLine() ?: return@use
                    val headers = mutableMapOf<String, String>()
                    while (true) { val row = input.readLine() ?: break; if (row.isEmpty()) break; val at = row.indexOf(':'); if (at > 0) headers[row.take(at).lowercase()] = row.drop(at + 1).trim() }
                    val seen = Seen(first.split(' ')[1], headers); this.seen += seen
                    peer.getOutputStream().apply { write(respond(seen).toByteArray()); flush() }
                } }
            }
        } }
        override fun close() { socket.close(); worker.shutdownNow(); worker.awaitTermination(2, TimeUnit.SECONDS) }
    }
    private val backendHeaders = mapOf("Authorization" to "Bearer synthetic-backend", "X-Profile-Unlock" to "synthetic-unlock", "X-Remote-Access-Credential" to "synthetic-pair")
    private fun response(status: Int = 200, body: String = "HLS") = "HTTP/1.1 $status Synthetic\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
    private fun client(access: VodMediaAccess) = OkHttpClient.Builder().addInterceptor(access.interceptor).addNetworkInterceptor(access.requestInterceptor).build()
    private fun error(status: Int, headers: Map<String, List<String>>) = HttpDataSource.InvalidResponseCodeException::class.java.constructors.single { it.parameterCount == 6 }
        .newInstance(status, "Synthetic", null, headers, null, byteArrayOf()) as HttpDataSource.InvalidResponseCodeException
    private fun info(error: IOException) = LoadErrorHandlingPolicy.LoadErrorInfo::class.java.constructors.single().newInstance(null, null, error, 1) as LoadErrorHandlingPolicy.LoadErrorInfo
    @Test fun `actual tile transport gives relative HLS child original profile without query inheritance`() {
        Peer { response() }.use { peer ->
            val owner = PlaybackSessionContext.capture(); val life = TilePlaybackLifetime(owner) { true }; val base = peer.base.resolve("proxy/")!!
            val access = VodMediaAccess(base, false, backendHeaders, context = owner.epoch, requireCurrent = life::check, profileId = owner.profileId); val c = client(access)
            try {
                val initial = base.resolve("api/video/hls/owned/stream.m3u8?x=a%2Bb")!!
                c.newCall(Request.Builder().url(initial).header("Authorization", backendHeaders.getValue("Authorization")).build()).execute().close()
                c.newCall(Request.Builder().url(initial.resolve("segment0.ts")!!).build()).execute().close()
                assertEquals(2, peer.seen.size); peer.seen.forEach { assertEquals(owner.profileId, peer.base.resolve(it.path)!!.queryParameter("profileId")) }
                assertTrue(peer.seen.first().path.contains("x=a%2Bb")); assertFalse(peer.seen.last().path.contains("x="))
            } finally { c.dispatcher.executorService.shutdownNow() }
        }
    }
    @Test fun `own HLS denial stops load policy and tile retry before any followup effect`() {
        Peer { response(403, "{}") }.use { peer ->
            val owner = PlaybackSessionContext.capture(); val life = TilePlaybackLifetime(owner) { true }
            val access = VodMediaAccess(peer.base, false, backendHeaders, context = owner.epoch, requireCurrent = life::check, profileId = owner.profileId); val c = client(access); var retries = 0; var denials = 0
            try { c.newCall(Request.Builder().url(peer.base.resolve("api/video/hls/owned/segment0.ts")!!).build()).execute().use { reply ->
                val error = error(reply.code, reply.headers.toMultimap()); val reason = requireNotNull(access.denial(error))
                assertEquals(C.TIME_UNSET, access.loadPolicy.getRetryDelayMsFor(info(error)))
                life.deny(reason) { denials++ }; assertFalse(life.use { retries++ }); assertEquals(0, retries); assertEquals(1, denials)
            } } finally { c.dispatcher.executorService.shutdownNow() }
        }
    }
    @Test fun `external provider denial retains query provider credentials and ordinary retry`() {
        Peer { response(403) }.use { peer ->
            val owner = PlaybackSessionContext.capture(); val life = TilePlaybackLifetime(owner) { true }
            val access = VodMediaAccess("http://127.0.0.1:1/proxy/".toHttpUrl(), false, backendHeaders, context = owner.epoch, requireCurrent = life::check, profileId = owner.profileId); val c = client(access); var retries = 0
            try {
                val request = Request.Builder().url(peer.base.resolve("provider.m3u8?token=synthetic-provider&x=a%2Bb")!!).header("Authorization", "Bearer synthetic-provider").header("X-Profile-Unlock", backendHeaders.getValue("X-Profile-Unlock")).build()
                c.newCall(request).execute().use { reply ->
                    val error = error(reply.code, reply.headers.toMultimap()); assertNull(access.denial(error)); assertTrue(access.loadPolicy.getRetryDelayMsFor(info(error)) >= 0); life.use { retries++ }
                }
                assertEquals(1, retries); assertEquals("Bearer synthetic-provider", peer.seen.single().headers["authorization"]); assertNull(peer.seen.single().headers["x-profile-unlock"])
                assertEquals("/provider.m3u8?token=synthetic-provider&x=a%2Bb", peer.seen.single().path)
            } finally { c.dispatcher.executorService.shutdownNow() }
        }
    }
    @Test fun `replaced tile cannot perform request or publish even when profile is unchanged`() {
        var current = true
        Peer { current = false; response() }.use { peer ->
            val owner = PlaybackSessionContext.capture(); val life = TilePlaybackLifetime(owner) { current }
            val access = VodMediaAccess(peer.base, false, backendHeaders, context = owner.epoch, requireCurrent = life::check, profileId = owner.profileId); val c = client(access)
            try {
                assertTrue(runCatching { c.newCall(Request.Builder().url(peer.base.resolve("api/video/hls/owned/segment0.ts")!!).build()).execute().close() }.exceptionOrNull() is IOException)
                assertEquals(1, peer.seen.size); assertFalse(life.use { fail("No publication") })
                assertTrue(runCatching { c.newCall(Request.Builder().url(peer.base.resolve("api/video/hls/owned/segment1.ts")!!).build()).execute().close() }.exceptionOrNull() is IOException); assertEquals(1, peer.seen.size)
            } finally { c.dispatcher.executorService.shutdownNow() }
        }
    }
    @Test fun `original player epoch cannot demand tile bytes after simulated A B A`() {
        Peer { response() }.use { peer ->
            val owner = PlaybackSessionContext.capture(); val life = TilePlaybackLifetime(owner) { true }
            val access = VodMediaAccess(peer.base, false, backendHeaders, context = owner.epoch, requireCurrent = life::check, profileId = owner.profileId); val c = client(access)
            ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
            try { assertTrue(runCatching { c.newCall(Request.Builder().url(peer.base.resolve("api/video/hls/owned/segment0.ts")!!).build()).execute().close() }.exceptionOrNull() is IOException); assertTrue(peer.seen.isEmpty()) }
            finally { c.dispatcher.executorService.shutdownNow() }
        }
    }
}
