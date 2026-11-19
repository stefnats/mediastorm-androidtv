package com.strmr.tv.api

import com.strmr.tv.live.DerivedTestData
import com.strmr.tv.live.recordingDerivedRequest
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class RecordingDerivedTransportTest {
    @Test fun `actual operation guard remains separate from unchanged saved browse allowlist`() {
        for (prepare in listOf(false, true)) {
            val request = recordingDerivedRequest("synthetic-recording", "adult", prepare)
            val policy = if (prepare) ObservedResponsePolicy.DERIVED_PREPARE else ObservedResponsePolicy.DERIVED_READ
            requireObservedDerivedRequest(request.method, request.path, request.query, "adult", true, policy, true)
            assertTrue(runCatching { requireObservedBrowseRequest(request.method, request.path, "/api/users/adult", request.query, true) }.isFailure)
            for (changed in listOf(request.path + "/extra", request.path.replace("synthetic-recording", ".."), request.path.replace("synthetic-recording", "%2Fprivate"))) {
                assertTrue(runCatching { requireObservedDerivedRequest(request.method, changed, request.query, "adult", true, policy, true) }.isFailure)
            }
            assertTrue(runCatching { requireObservedDerivedRequest(request.method, request.path, request.query + ("path" to "private"), "adult", true, policy, true) }.isFailure)
            assertTrue(runCatching { requireObservedDerivedRequest(request.method, request.path, request.query, "other", true, policy, true) }.isFailure)
            assertTrue(runCatching { requireObservedDerivedRequest(request.method, request.path, request.query, "adult", true, policy, false) }.isFailure)
        }
        for (source in listOf("continue-watching-cached", "similar-cached", "tmdb-public-list-cached"))
            requireObservedBrowseRequest("GET", "/api/users/adult/display-list", "/api/users/adult", mapOf("source" to source), true)
    }
    @Test fun `prepare has an isolated long response clock bound and never widens existing browse clocks`() {
        val url = "https://example.invalid/api/live/recordings/synthetic-recording/derived/join?profileId=adult".toHttpUrl()
        val actual = NetworkResponseObservation(url, listOf("Thu, 19 Nov 2026 12:00:00 GMT"), 0, 200_000_000_000)
        assertTrue(runCatching { actual.clock(url) }.isFailure)
        val clock = actual.clock(url, ObservedResponsePolicy.DERIVED_PREPARE.maxRoundTripMillis)
        assertTrue(clock.upperMillis(200_000_000_000) > DerivedTestData.serverMillis + 200_000)
        assertTrue(runCatching { actual.clock(url, ObservedResponsePolicy.DERIVED_READ.maxRoundTripMillis) }.isFailure)
        assertTrue(runCatching { actual.clock(url.newBuilder().encodedPath("/other").build(), 330_000) }.isFailure)
    }
    @Test fun `actual bound empty POST and GET carry only original explicit profile on wire`() = runBlocking {
        for (prepare in listOf(false, true)) {
            val body = DerivedTestData.raw(DerivedTestData.source()).toByteArray()
            peer(body, "application/json", listOf("Thu, 19 Nov 2026 12:00:00 GMT")) { url, captured ->
                val profile = ApiClient.profileId
                val operation = recordingDerivedRequest("synthetic-recording", profile, prepare)
                val endpoint = url.newBuilder().encodedPath(operation.path).addQueryParameter("profileId", profile).build()
                val context = RequestContext({ 1L }, {}).capture(1)
                val client = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build().withoutAutomaticReplay()
                try {
                    val result = ContextBoundTransport(context, false, { error("No recovery") }, {}, allowReplay = false).execute {
                        client.newCall(Request.Builder().url(endpoint).method(operation.method, if (prepare) ByteArray(0).toRequestBody() else null).build())
                            .await(true, if (prepare) ObservedResponsePolicy.DERIVED_PREPARE else ObservedResponsePolicy.DERIVED_READ)
                    }
                    assertEquals(200, result.code); assertEquals("ready", com.strmr.tv.live.RecordingDerivedObservation.accept(DerivedTestData.source(), result.text, result.observedClock(endpoint)).descriptor.state)
                    assertEquals("${operation.method} ${operation.path}?profileId=$profile HTTP/1.1", captured.get().first)
                    assertEquals(0, captured.get().second)
                } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
            }
        }
    }
    @Test fun `real response body type size UTF8 and repeated Date failures cannot become observations`() = runBlocking {
        for (mode in listOf("type", "size", "utf8", "date")) {
            val payload = when (mode) { "size" -> ByteArray(65_537) { 'x'.code.toByte() }; "utf8" -> byteArrayOf(0xc3.toByte(), 0x28); else -> "{}".toByteArray() }
            val dates = if (mode == "date") listOf("Thu, 19 Nov 2026 12:00:00 GMT", "Thu, 19 Nov 2026 12:00:00 GMT") else listOf("Thu, 19 Nov 2026 12:00:00 GMT")
            peer(payload, if (mode == "type") "text/html" else "application/json", dates) { url, _ ->
                val client = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build().withoutAutomaticReplay()
                try {
                    val failed = runCatching {
                        val result = client.newCall(Request.Builder().url(url).build()).await(true, ObservedResponsePolicy.DERIVED_READ)
                        result.observedClock(url)
                    }.isFailure
                    assertTrue("Invalid $mode accepted", failed)
                } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
            }
        }
    }
    @Test fun `lost preparation transport never recovers or repeats an uncertain operation`() = runBlocking {
        var requests = 0; var recoveries = 0
        val context = RequestContext({ 1L }, {}).capture(1)
        val failure = IOException("Synthetic lost preparation")
        val actual = runCatching { ContextBoundTransport(context, true, { recoveries++; true }, {}, allowReplay = false).execute { requests++; throw failure } }.exceptionOrNull()
        assertSame(failure, actual); assertEquals(1, requests); assertEquals(0, recoveries)
    }

    private suspend fun peer(body: ByteArray, type: String, dates: List<String>, action: suspend (okhttp3.HttpUrl, AtomicReference<Pair<String, Int>>) -> Unit) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")); val captured = AtomicReference<Pair<String, Int>>()
        val worker = Thread {
            try { server.accept().use { socket ->
                socket.soTimeout = 3000; val reader = socket.getInputStream().bufferedReader(); val first = reader.readLine(); var length = 0
                while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt() }
                repeat(length) { reader.read() }; captured.set(first to length)
                val header = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" + dates.joinToString("") { "Date: $it\r\n" } + "Connection: close\r\n\r\n"
                socket.getOutputStream().apply { write(header.toByteArray()); write(body); flush() }
            } } catch (_: IOException) { }
        }.apply { isDaemon = true; start() }
        try { action("http://127.0.0.1:${server.localPort}/".toHttpUrl(), captured) }
        finally { server.close(); worker.join(3000); assertFalse("Synthetic response worker leaked", worker.isAlive) }
    }
}
