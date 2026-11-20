package com.strmr.tv.api

import com.strmr.tv.live.*
import java.io.IOException
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
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class RecordingProgressTransportTest {
    @Test fun actual_fixed_get_post_wire_carries_original_header_only_profile() = runBlocking {
        for (write in listOf(false, true)) {
            val received = System.nanoTime(); val original = ProgressTestData.original(received)
            val snapshot = ProgressTestData.snapshot(original, clock = ProgressTestData.clock(received), now = received)
            val command = if (write) RecordingProgressCommand.fromStart(snapshot) else null
            val payload = if (command == null) ProgressTestData.raw(original) else ProgressTestData.result(command)
            val gate = CountDownLatch(1)
            Peer(payload.toByteArray(), bodyGate = gate).use { peer ->
                val client = client(); var currentUnlock = "synthetic-original-unlock"; val lookedUp = mutableListOf<String>()
                var pending: Deferred<ObservedRawResponse>? = null
                try {
                    val context = RequestContext({ 1L }, {}).capture(1)
                    val base = peer.url.newBuilder().encodedPath("/proxy/").addQueryParameter("old", "discarded").fragment("discarded").build()
                    val sender = transport(client, context, base, unlock = { lookedUp += it; currentUnlock })
                    val request = async(Dispatchers.IO) { sender.send(RecordingProgressRequest(original, 1, command)) }
                    pending = request
                    await(peer.headersSent)
                    currentUnlock = "synthetic-replacement-unlock"
                    gate.countDown()
                    val raw = withTimeout(5_000) { request.await() }
                    val wire = requireNotNull(peer.wire.get())
                    assertEquals("${if (write) "POST" else "GET"} /proxy/api/live/recordings/synthetic-recording/progress HTTP/1.1", wire.first)
                    assertEquals("Bearer synthetic-bearer", wire.headers["authorization"])
                    assertEquals("synthetic-client", wire.headers["x-client-id"])
                    assertEquals("synthetic-original-unlock", wire.headers["x-profile-unlock"])
                    assertEquals("synthetic-remote", wire.headers["x-remote-access-credential"])
                    assertEquals(listOf("adult"), lookedUp)
                    if (write) {
                        val body = ApiJson.parseToJsonElement(wire.body).toString()
                        assertEquals(requireNotNull(command).body().toString(), body)
                        assertFalse(body.contains("profileId")); assertFalse(body.contains("profileUnlock"))
                        assertTrue(RecordingProgressWriteObservation.accept(command, raw.text, requireNotNull(raw.clock)).adoptable())
                    } else {
                        assertEquals("", wire.body)
                        assertTrue(RecordingProgressSnapshotObservation.accept(original, raw.text, requireNotNull(raw.clock), 1).adoptable())
                    }
                    assertFalse(wire.first.contains("synthetic-original-unlock")); assertEquals(1, peer.requests.get())
                } finally { gate.countDown(); pending?.cancelAndJoin(); closeClient(client) }
            }
        }
    }
    @Test fun actual_prepared_command_is_attempted_once_without_auth_or_pin_replay() = runBlocking {
        val now = System.nanoTime(); val original = ProgressTestData.original(now)
        val command = RecordingProgressCommand.fromStart(ProgressTestData.snapshot(original, clock = ProgressTestData.clock(now), now = now))
        Peer(ProgressTestData.result(command).toByteArray()).use { peer ->
            val client = client()
            try {
                val sender = transport(client, RequestContext({ 1L }, {}).capture(1), peer.url)
                val operation = RecordingProgressRequest(original, 1, command)
                sender.send(operation)
                assertTrue(runCatching { sender.send(operation) }.isFailure)
                assertTrue(runCatching { sender.send(RecordingProgressRequest(original, 1, command)) }.isFailure)
                assertEquals(1, peer.requests.get())
            } finally { closeClient(client) }
        }
    }
    @Test fun invalid_selectors_and_wrong_original_context_issue_zero_requests() = runBlocking {
        val original = ProgressTestData.original(System.nanoTime())
        Peer("{}".toByteArray()).use { peer ->
            val client = client()
            try {
                for (id in listOf("..", "../private", "a?profileId=other", "a/extra", "%2fprivate")) {
                    assertNull(RecordingDerivedSource.capture(DerivedTestData.recording(id = id, profile = "adult"), "adult"))
                }
                assertTrue(runCatching { requireObservedDerivedRequest("GET", "/api/live/recordings/synthetic-recording/derived", mapOf("profileId" to "adult"), "adult", true, ObservedResponsePolicy.PROGRESS, true) }.isFailure)
                assertTrue(runCatching { requireObservedBrowseRequest("GET", "/api/live/recordings/synthetic-recording/progress", "/api/users/adult", emptyMap(), true) }.isFailure)
                assertTrue(runCatching { transport(client, RequestContext({ 2L }, {}).capture(2), peer.url).send(RecordingProgressRequest(original, 1)) }.isFailure)
                assertTrue(runCatching { transport(client, RequestContext({ 1L }, {}).capture(1), peer.url, profile = { "other" }).send(RecordingProgressRequest(original, 1)) }.isFailure)
                assertTrue(runCatching { transport(client, RequestContext({ 1L }, {}).capture(1), peer.url.newBuilder().username("synthetic-user").build()).send(RecordingProgressRequest(original, 1)) }.isFailure)
                assertTrue(runCatching { transport(client, RequestContext({ 1L }, {}).capture(1), peer.url, bearer = { null }).send(RecordingProgressRequest(original, 1)) }.isFailure)
                assertEquals(0, peer.requests.get())
            } finally { closeClient(client) }
        }
    }
    @Test fun redirects_send_no_second_origin_request_or_credentials() = runBlocking {
        val original = ProgressTestData.original(System.nanoTime())
        Peer("{}".toByteArray()).use { foreign ->
            for (code in listOf(301, 302, 307, 308)) {
                Peer(ByteArray(0), code = code, extra = "Location: ${foreign.url}\r\n").use { peer ->
                    val client = client()
                    try {
                        assertTrue(runCatching { transport(client, RequestContext({ 1L }, {}).capture(1), peer.url).send(RecordingProgressRequest(original, 1)) }.isFailure)
                        assertEquals(1, peer.requests.get()); assertEquals(0, foreign.requests.get()); assertNull(foreign.wire.get())
                    } finally { closeClient(client) }
                }
            }
        }
    }
    @Test fun lost_reply_and_http_errors_are_terminal_without_recovery() = runBlocking {
        val original = ProgressTestData.original(System.nanoTime())
        for (status in listOf(0, 401, 403, 409, 429, 503)) for (write in listOf(false, true)) {
            Peer("recording progress unavailable".toByteArray(), code = status, type = "text/plain",
                extra = if (status == 503) "Retry-After: 0\r\n" else "").use { peer ->
                val connects = AtomicInteger()
                val client = client(connects); val tokens = AtomicInteger()
                try {
                    val sender = transport(client, RequestContext({ 1L }, {}).capture(1), peer.url, bearer = { tokens.incrementAndGet(); "synthetic-bearer" })
                    val received = System.nanoTime()
                    val command = if (write) RecordingProgressCommand.fromStart(ProgressTestData.snapshot(original, clock = ProgressTestData.clock(received), now = received)) else null
                    val failure = runCatching { sender.send(RecordingProgressRequest(original, 1, command)) }.exceptionOrNull()
                    assertNotNull(failure)
                    if (status != 0) {
                        assertTrue("HTTP result lost its type", failure is ApiError.Http)
                        val http = (failure as ApiError.Http).response
                        assertEquals(status, http.status)
                        assertEquals("recording progress unavailable", http.body)
                        if (status == 503) assertEquals(0.0, requireNotNull(http.retryAfter), 0.0)
                    }
                    assertEquals(if (write) "POST" else "GET", requireNotNull(peer.wire.get()).first.substringBefore(' '))
                    assertEquals(1, peer.requests.get()); assertEquals(1, tokens.get())
                    assertEquals("SDK attempted another connection", 1, connects.get())
                    assertEquals("Peer accepted another connection", 1, peer.connections.get())
                } finally { closeClient(client) }
            }
        }
    }
    @Test fun actual_json_type_utf8_date_and_body_bounds_are_enforced() = runBlocking {
        val original = ProgressTestData.original(System.nanoTime())
        val invalidTypes = mapOf(
            "type" to "text/html", "charset" to "application/json; charset=utf-16",
            "unknown" to "application/json; charset=definitely-unknown",
            "empty" to "application/json; charset=\"\"",
            "duplicate-unknown" to "application/json; charset=utf-8; charset=definitely-unknown",
            "duplicate-utf16" to "application/json; charset=utf-8; charset=utf-16",
            "duplicate-same" to "application/json; charset=utf-8; CHARSET=UTF-8")
        for (mode in invalidTypes.keys + listOf("size", "utf8", "date", "missing-date")) {
            val payload = when (mode) { "size" -> ByteArray(65_537) { 'x'.code.toByte() }; "utf8" -> byteArrayOf(0xc3.toByte(), 0x28); else -> ProgressTestData.raw(original).toByteArray() }
            val dates = when (mode) { "date" -> listOf(date, date); "missing-date" -> emptyList(); else -> listOf(date) }
            Peer(payload, type = invalidTypes[mode] ?: "application/json", dates = dates).use { peer ->
                val client = client()
                try {
                    assertTrue("Invalid $mode accepted", runCatching { transport(client, RequestContext({ 1L }, {}).capture(1), peer.url).send(RecordingProgressRequest(original, 1)) }.isFailure)
                    assertEquals(1, peer.requests.get())
                } finally { closeClient(client) }
            }
        }
        for (type in listOf("application/json", "application/json; charset=UTF8", "application/json; charset=\"UTF-8\"",
            "application/json; note=\"x;charset=utf-16;y\"; charset=utf-8")) {
            Peer(ProgressTestData.raw(original).toByteArray(), type = type).use { peer ->
                val client = client()
                try {
                    val raw = transport(client, RequestContext({ 1L }, {}).capture(1), peer.url).send(RecordingProgressRequest(original, 1))
                    assertEquals(ProgressTestData.raw(original), raw.text)
                    assertEquals(1, peer.requests.get())
                } finally { closeClient(client) }
            }
        }
    }
    @Test fun held_reply_rejects_profile_account_server_aba_after_io() = runBlocking {
        for (kind in listOf("profile", "account", "server")) {
            val original = ProgressTestData.original(System.nanoTime()); val gate = CountDownLatch(1)
            Peer(ProgressTestData.raw(original).toByteArray(), bodyGate = gate).use { peer ->
                val client = client(); var epoch = 1L; var profile = "adult"
                val contexts = RequestContext({ epoch }, { epoch++ }); val scope = contexts.capture(1)
                try {
                    val result = async(Dispatchers.IO) { runCatching { transport(client, scope, peer.url, profile = { profile }).send(RecordingProgressRequest(original, 1)) } }
                    await(peer.headersSent)
                    if (kind == "profile") { contexts.changeProfile { profile = "other" }; contexts.changeProfile { profile = "adult" } }
                    else { contexts.changeAuthentication({ true }); contexts.changeAuthentication({ true }) }
                    gate.countDown()
                    assertTrue(withTimeout(5_000) { result.await() }.isFailure); assertEquals(1, peer.requests.get())
                } finally { gate.countDown(); closeClient(client) }
            }
        }
    }
    @Test fun cancel_joins_actual_response_callback_and_body_close() = runBlocking {
        for (status in listOf(200, 503)) {
            val original = ProgressTestData.original(System.nanoTime()); val bodyGate = CountDownLatch(1)
            val reading = CountDownLatch(1); val closing = CountDownLatch(1); val releaseClose = CountDownLatch(1)
            Peer(ProgressTestData.raw(original).toByteArray(), code = status,
                extra = if (status == 503) "Retry-After: 0\r\n" else "", bodyGate = bodyGate).use { peer ->
                val client = client().newBuilder().addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request()); val body = requireNotNull(response.body)
                    val observed = object : ForwardingSource(body.source()) {
                        override fun read(sink: Buffer, byteCount: Long): Long { reading.countDown(); return super.read(sink, byteCount) }
                        override fun close() {
                            closing.countDown()
                            try { check(releaseClose.await(5, TimeUnit.SECONDS)) } finally { super.close() }
                        }
                    }.buffer()
                    response.newBuilder().body(object : ResponseBody() {
                        override fun contentType(): MediaType? = body.contentType()
                        override fun contentLength(): Long = body.contentLength()
                        override fun source(): BufferedSource = observed
                    }).build()
                }.build()
                val published = AtomicBoolean()
                val job = launch(Dispatchers.IO) {
                    transport(client, RequestContext({ 1L }, {}).capture(1), peer.url).send(RecordingProgressRequest(original, 1))
                    published.set(true)
                }
                try {
                    await(reading); job.cancel(); await(closing)
                    assertFalse("Cancel returned before actual body Close", job.isCompleted); assertFalse(published.get())
                    releaseClose.countDown(); bodyGate.countDown()
                    withTimeout(5_000) { job.join() }; assertFalse(published.get())
                } finally { releaseClose.countDown(); bodyGate.countDown(); job.cancelAndJoin(); closeClient(client) }
            }
        }
    }
    @Test fun late_validuntil_and_cancel_after_decode_cannot_publish() = runBlocking {
        val original = ProgressTestData.original(System.nanoTime())
        val expired = ProgressTestData.replace(ProgressTestData.raw(original), "progress", "validUntil", kotlinx.serialization.json.JsonPrimitive(DerivedTestData.observed))
        Peer(expired.toByteArray()).use { peer ->
            val client = client()
            try {
                val contexts = RequestContext({ 1L }, {}); val scope = contexts.capture(1)
                val raw = transport(client, scope, peer.url).send(RecordingProgressRequest(original, 1))
                assertTrue(runCatching { recordingProgressReply(scope::check, { RecordingProgressSnapshotObservation.accept(original, raw.text, requireNotNull(raw.clock), 1) }, { it.adoptable() }) }.isFailure)
            } finally { closeClient(client) }
        }
        val contexts = RequestContext({ 1L }, {}); val scope = contexts.capture(1); val job = Job()
        val now = System.nanoTime(); val clock = ProgressTestData.clock(now)
        assertTrue(runCatching { recordingProgressReply(
            { scope.check(); job.ensureActive() },
            { ProgressTestData.snapshot(original, clock = clock, now = now).also { job.cancel() } },
            { it.adoptable() }) }.isFailure)
        assertTrue(runCatching { recordingProgressReply(
            scope::check,
            { ProgressTestData.snapshot(original, clock = clock, now = now).also { contexts.changeAuthentication({ true }) } },
            { it.adoptable() }) }.isFailure)
    }

    private fun transport(client: OkHttpClient, context: RequestContext.Scope, base: HttpUrl,
        profile: () -> String = { "adult" }, bearer: () -> String? = { "synthetic-bearer" },
        unlock: (String) -> String? = { "synthetic-unlock" }) = RecordingProgressTransport(context,
        base = { base }, activeProfile = profile, bearer = bearer, unlock = unlock,
        clientId = { "synthetic-client" }, remoteCredential = { "synthetic-remote" }, client = client)
    private fun client(connects: AtomicInteger? = null) = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).apply {
        if (connects != null) eventListener(object : EventListener() {
            override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: Proxy) { connects.incrementAndGet() }
        })
    }.build()
    private fun closeClient(client: OkHttpClient) {
        client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        if (!client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS)) {
            client.dispatcher.executorService.shutdownNow()
            assertTrue("Actual HTTP callbacks leaked", client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
    private fun await(latch: CountDownLatch) { assertTrue("Synthetic fixture barrier not reached", latch.await(5, TimeUnit.SECONDS)) }
    private data class Wire(val first: String, val headers: Map<String, String>, val body: String)
    private class Peer(private val payload: ByteArray, private val code: Int = 200,
        private val type: String = "application/json", private val dates: List<String> = listOf(date),
        private val extra: String = "", private val bodyGate: CountDownLatch = CountDownLatch(0)) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}/".toHttpUrl()
        val requests = AtomicInteger(); val wire = AtomicReference<Wire>(); val headersSent = CountDownLatch(1)
        val connections = AtomicInteger()
        private val connection = AtomicReference<Socket?>()
        private val worker = Thread {
            while (!listener.isClosed) try {
                listener.accept().use { socket ->
                    connections.incrementAndGet()
                    connection.set(socket); socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val first = requireNotNull(reader.readLine()); val headers = linkedMapOf<String, String>()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
                    val length = headers["content-length"]?.toInt() ?: 0; require(length in 0..4096)
                    val body = CharArray(length); var read = 0
                    while (read < length) { val n = reader.read(body, read, length - read); require(n > 0); read += n }
                    requests.incrementAndGet(); wire.set(Wire(first, headers, String(body)))
                    if (code != 0) {
                        val header = "HTTP/1.1 $code Synthetic\r\nContent-Type: $type\r\nContent-Length: ${payload.size}\r\n" + dates.joinToString("") { "Date: $it\r\n" } + extra + "Connection: close\r\n\r\n"
                        socket.getOutputStream().apply {
                            write(header.toByteArray(Charsets.US_ASCII)); flush(); headersSent.countDown()
                            check(bodyGate.await(5, TimeUnit.SECONDS)); write(payload); flush()
                        }
                    }
                }
            } catch (_: IOException) { /* Closing an owned peer unblocks accept/read/write. */ }
        }.apply { isDaemon = true; name = "recording-progress-synthetic-peer"; start() }
        override fun close() {
            bodyGate.countDown(); listener.close(); connection.get()?.close(); worker.join(5_000)
            assertFalse("Synthetic peer worker leaked", worker.isAlive)
        }
    }
    companion object { private const val date = "Thu, 19 Nov 2026 12:00:00 GMT" }
}
