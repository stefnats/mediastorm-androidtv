package com.strmr.tv.api

import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class SpokenAudioTransportTest {
    private val reply = """{"status":"likely_match","requestedLanguage":"eng","detectedLanguage":"eng","confidence":0.92,"samples":2,"sourceVersion":"original-version","audioTrackIndex":2}"""
    private val input = SpokenAudioInput("original-version", 2, 42.5)

    private fun sender(peer: Peer, client: OkHttpClient, context: RequestContext.Scope) = SpokenAudioTransport(
        context, { peer.url }, { "original-profile" }, { "synthetic-bearer" }, { "synthetic-unlock" },
        { "synthetic-device" }, { "synthetic-remote" }, client)

    private fun client() = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
    private fun stop(client: OkHttpClient) { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }

    @Test fun exactOriginalTrackAndCredentialsUseOneBoundRequest() = runBlocking {
        Peer(reply).use { peer ->
            val client = client()
            try {
                val context = RequestContext({ 11L }, {}).capture(11L)
                val transport = sender(peer, client, context)
                val operation = SpokenAudioRequest("preparation", "original-profile", 11, input)
                val result = withTimeout(5_000) { transport.send(operation) }
                assertEquals("likely_match", result.status)
                val wire = requireNotNull(peer.wire.get())
                assertEquals("POST /proxy/api/playback/prequeue/preparation/audio-language?profileId=original-profile HTTP/1.1", wire.first)
                assertEquals("Bearer synthetic-bearer", wire.headers["authorization"])
                assertEquals("synthetic-unlock", wire.headers["x-profile-unlock"])
                assertEquals("synthetic-device", wire.headers["x-client-id"])
                assertEquals("synthetic-remote", wire.headers["x-remote-access-credential"])
                val body = ApiJson.parseToJsonElement(wire.body).jsonObject
                assertEquals(setOf("sourceVersion", "audioTrackIndex", "startOffset"), body.keys)
                assertEquals(2, body["audioTrackIndex"]!!.jsonPrimitive.int)
                assertEquals(42.5, body["startOffset"]!!.jsonPrimitive.double, 0.0)
                try { transport.send(operation); fail("One-shot request replayed") } catch (_: IllegalStateException) { }
                assertEquals(1, peer.requests.get())
            } finally { stop(client) }
        }
    }

    @Test fun authorizationAndRetryAfterResponsesAreNotRefreshedOrReplayed() = runBlocking {
        for (code in listOf(401, 408, 429, 503)) Peer("{}", code).use { peer ->
            val client = client()
            try {
                val transport = sender(peer, client, RequestContext({ 1L }, {}).capture(1))
                try {
                    withTimeout(5_000) { transport.send(SpokenAudioRequest("preparation", "original-profile", 1, input)) }
                    fail("Failed response accepted")
                } catch (error: ApiError.Http) { assertEquals(code, error.statusCode) }
                delay(50)
                assertEquals(1, peer.requests.get())
            } finally { stop(client) }
        }
    }

    @Test fun changingOriginalContextWhileBodyWaitsPreventsReplyAdoption() = runBlocking {
        val epoch = java.util.concurrent.atomic.AtomicLong(1)
        Peer(reply, hold = true).use { peer ->
            val client = client()
            try {
                val transport = sender(peer, client, RequestContext(epoch::get, {}).capture(1))
                val request = async(Dispatchers.IO) { transport.send(SpokenAudioRequest("preparation", "original-profile", 1, input)) }
                assertTrue(peer.headersSent.await(3, TimeUnit.SECONDS))
                epoch.incrementAndGet()
                peer.release.countDown()
                try { withTimeout(5_000) { request.await() }; fail("Stale profile reply adopted") }
                catch (_: CancellationException) { }
                assertEquals(1, peer.requests.get())
            } finally { stop(client) }
        }
    }

    @Test fun cancellationJoinsTheActualHttpCallbackWithoutRetry() = runBlocking {
        Peer(reply, hold = true).use { peer ->
            val client = client()
            try {
                val transport = sender(peer, client, RequestContext({ 1L }, {}).capture(1))
                val request = launch(Dispatchers.IO) { transport.send(SpokenAudioRequest("preparation", "original-profile", 1, input)) }
                assertTrue(peer.headersSent.await(3, TimeUnit.SECONDS))
                withTimeout(5_000) { request.cancelAndJoin() }
                assertTrue(request.isCancelled)
                assertEquals(1, peer.requests.get())
            } finally { stop(client) }
        }
    }

    @Test fun mismatchedSourceOrTrackAndMalformedObservationFailClosed() = runBlocking {
        for (raw in listOf(reply.replace("original-version", "replacement"), reply.replace("\"audioTrackIndex\":2", "\"audioTrackIndex\":3"),
            reply.replace("0.92", "2.0"), reply.replace("\"samples\":2", "\"samples\":9"), reply.replace("likely_match", "certain"))) {
            Peer(raw).use { peer ->
                val client = client()
                try {
                    val transport = sender(peer, client, RequestContext({ 1L }, {}).capture(1))
                    try { withTimeout(5_000) { transport.send(SpokenAudioRequest("preparation", "original-profile", 1, input)) }; fail("Malformed observation accepted") }
                    catch (_: IllegalArgumentException) { }
                    assertEquals(1, peer.requests.get())
                } finally { stop(client) }
            }
        }
    }

    @Test fun invalidFiniteBoundsAndPathCannotReachTheNetwork() = runBlocking {
        Peer(reply).use { peer ->
            val client = client()
            try {
                val transport = sender(peer, client, RequestContext({ 1L }, {}).capture(1))
                val invalid = listOf(
                    SpokenAudioRequest("wrong/path", "original-profile", 1, input),
                    SpokenAudioRequest("preparation", "original-profile", 1, input.copy(audioTrackIndex = -1)),
                    SpokenAudioRequest("preparation", "original-profile", 1, input.copy(startOffset = Double.NaN)),
                    SpokenAudioRequest("preparation", "original-profile", 1, input.copy(startOffset = Double.POSITIVE_INFINITY)),
                    SpokenAudioRequest("preparation", "original-profile", 1, input.copy(startOffset = -1.0)),
                )
                for (operation in invalid) {
                    try { transport.send(operation); fail("Invalid operation reached transport") }
                    catch (_: IllegalArgumentException) { }
                }
                assertEquals(0, peer.requests.get())
            } finally { stop(client) }
        }
    }

    private data class Wire(val first: String, val headers: Map<String, String>, val body: String)

    /** Only literal loopback synthetic HTTP; every socket and worker joins in close. */
    private class Peer(private val body: String, private val status: Int = 200, hold: Boolean = false) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val closed = AtomicBoolean()
        private val active = AtomicReference<Socket?>()
        val requests = AtomicInteger()
        val wire = AtomicReference<Wire?>()
        val headersSent = CountDownLatch(1)
        val release = CountDownLatch(if (hold) 1 else 0)
        val url: HttpUrl = "http://127.0.0.1:${server.localPort}/proxy".toHttpUrl()
        private val thread = Thread({
            while (!closed.get()) {
                try {
                    server.accept().use { socket ->
                        active.set(socket)
                        val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                        val first = input.readLine() ?: return@use
                        val headers = linkedMapOf<String, String>()
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        val chars = CharArray(headers["content-length"]?.toInt() ?: 0)
                        var at = 0
                        while (at < chars.size) { val count = input.read(chars, at, chars.size - at); check(count > 0); at += count }
                        wire.set(Wire(first, headers, String(chars))); requests.incrementAndGet()
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val output = socket.getOutputStream()
                        output.write(("HTTP/1.1 $status Synthetic\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nRetry-After: 0\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        output.flush(); headersSent.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        output.write(bytes); output.flush()
                    }
                } catch (_: Exception) { if (closed.get()) break }
                finally { active.set(null) }
            }
        }, "spoken-audio-loopback").apply { isDaemon = true; start() }
        override fun close() {
            closed.set(true); release.countDown()
            runCatching { active.get()?.close() }; server.close()
            thread.join(3_000); check(!thread.isAlive) { "Synthetic audio peer did not join" }
        }
    }
}
