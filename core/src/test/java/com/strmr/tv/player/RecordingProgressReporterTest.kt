package com.strmr.tv.player

import com.strmr.tv.api.*
import com.strmr.tv.app.DeferredResumeGate
import com.strmr.tv.app.ResumeBinding
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.live.*
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
import kotlinx.coroutines.test.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingProgressReporterTest {
    @Before fun profile() { ProfileDataContext.invalidate() }
    @After fun restore() { ProfileDataContext.invalidate() }
    private class Fixture(scope: CoroutineScope, now: () -> Long) {
        val resource = requireNotNull(RecordingProgressResource.acquire(scope)).also { it.transfer() }
        val gateway = RecordingProgressTestGateway(now)
        val original = DerivedTestData.observation()
        val initial = gateway.receipt(RecordingProgressCommand.fromStart(gateway.snapshot(original)))
        val owner = PlaybackSessionContext.capture()
        var position = 100L
        var playing = false
        var failures = 0
        val reporter = RecordingProgressReporter(resource, initial, gateway,
            { owner.isCurrent() && original.ready(now()) }, { position }, { playing }, now, { failures++ })
    }

    @Test fun one_inflight_and_latest_sample_keep_ack_cas_order() = runTest {
        val f = Fixture(backgroundScope) { 0 }; val gate = CompletableDeferred<Unit>()
        f.gateway.writeAction = { command -> if (f.gateway.commands.size == 1) gate.await(); f.gateway.receipt(command) }
        try {
            f.reporter.start(); f.reporter.offer(100); runCurrent()
            f.reporter.offer(200); f.reporter.offer(300); runCurrent()
            assertEquals(1, f.gateway.commands.size); assertEquals(1L, f.gateway.commands.single().revision.value)
            gate.complete(Unit); runCurrent()
            assertEquals(listOf(100L, 300L), f.gateway.commands.map { it.positionMs })
            assertEquals(listOf(1L, 2L), f.gateway.commands.map { it.revision.value })
            assertEquals(listOf(1L, 1L), f.gateway.commands.map { it.writerGeneration.value })
            assertEquals(0, f.failures)
        } finally { gate.complete(Unit); f.reporter.close(null) {}.join() }
    }

    @Test fun two_client_generation_conflict_retires_old_reporter_without_adoption() = runTest {
        val f = Fixture(backgroundScope) { 0 }
        var serverRevision = 1L; var serverGeneration = 1L
        f.gateway.writeAction = { command ->
            if (command.revision.value != serverRevision || command.writerGeneration.value != serverGeneration)
                throw ApiError.Http(HttpFailure(409, "Synthetic current CAS conflict"))
            f.gateway.receipt(command).also { serverRevision = it.data.revision.value }
        }
        try {
            f.reporter.start(); f.reporter.offer(321); runCurrent()
            // Stateful second-client DATA model through the same strict factories, not PG proof.
            f.gateway.position = 321
            val secondSnapshot = f.gateway.snapshot(f.original, revision = serverRevision.toString(), generation = serverGeneration.toString())
            val second = f.gateway.receipt(RecordingProgressCommand.resume(secondSnapshot))
            serverRevision = second.data.revision.value; serverGeneration = second.data.writerGeneration.value
            f.reporter.offer(654); runCurrent(); f.reporter.offer(987); runCurrent()
            assertEquals(listOf(321L, 654L), f.gateway.commands.map { it.positionMs })
            assertEquals(1, f.failures); assertFalse(f.gateway.calls.contains("snapshot"))
            assertEquals(1L, f.gateway.commands.last().writerGeneration.value)
            assertEquals(2L, serverGeneration); assertEquals(0, RecordingProgressResource.charged())
        } finally { f.reporter.close(null) {}.join() }
    }

    @Test fun unknown_loss_auth_and_expired_reply_are_terminal_without_replay() = runTest {
        for (mode in listOf("unknown", "403", "409", "503", "expired")) {
            val f = Fixture(backgroundScope) { 0 }
            f.gateway.writeAction = { command ->
                when (mode) {
                    "unknown" -> throw java.io.IOException("Synthetic lost acknowledgement")
                    "expired" -> RecordingProgressWriteObservation.accept(command, ProgressTestData.result(command), DerivedTestData.clock(), 30_000_000_000L)
                    else -> throw ApiError.Http(HttpFailure(mode.toInt(), "Synthetic terminal reply"))
                }
            }
            try {
                f.reporter.start(); f.reporter.offer(111); runCurrent(); f.reporter.offer(222); runCurrent()
                assertEquals(1, f.gateway.commands.size); assertEquals(1, f.failures)
                assertEquals(listOf("touch"), f.gateway.calls); assertEquals(0, RecordingProgressResource.charged())
            } finally { f.reporter.close(999) {}.join() }
        }
    }

    @Test fun pause_seek_end_and_close_use_integer_measured_timeline_positions() = runTest {
        val f = Fixture(backgroundScope) { testScheduler.currentTime * 1_000_000 }
        try {
            f.reporter.offer(999); runCurrent(); assertTrue(f.gateway.commands.isEmpty())
            f.reporter.start()
            for (position in listOf(1001L, 17L, 2000L)) { f.reporter.offer(position); runCurrent() }
            var closed = false
            f.reporter.close(1999) { closed = true }.join()
            assertTrue(closed)
            assertEquals(listOf(1001L, 17L, 2000L, 1999L), f.gateway.commands.map { it.positionMs })
            assertTrue(f.gateway.commands.all { it.action == "touch" && it.identity.durationMs == 2000L })
            assertEquals(listOf(1L, 2L, 3L, 4L), f.gateway.commands.map { it.revision.value })
        } finally { f.reporter.close(null) {}.join() }
        assertEquals(0, RecordingProgressResource.charged())
    }

    @Test fun final_sample_and_parallel_close_wait_real_body_close_before_refund() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val received = System.nanoTime()
        val original = DerivedTestData.observation(clock = DerivedTestData.clock(received))
        val expected = ProfileDataContext.current
        val snapshot = RecordingProgressSnapshotObservation.accept(original, ProgressTestData.raw(original), DerivedTestData.clock(received), expected, received)
        val claim = RecordingProgressCommand.fromStart(snapshot)
        val initial = RecordingProgressWriteObservation.accept(claim, ProgressTestData.result(claim), DerivedTestData.clock(received), received)
        val command = RecordingProgressCommand.touch(initial, 1001)
        val closing = CountDownLatch(1); val release = CountDownLatch(1); val after = AtomicBoolean(); val failures = AtomicInteger()
        val resource = requireNotNull(RecordingProgressResource.acquire(parent)).also { it.transfer() }
        val peer = ReplyPeer(ProgressTestData.result(command))
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request()); val body = requireNotNull(response.body)
            val input = object : ForwardingSource(body.source()) {
                override fun close() {
                    closing.countDown()
                    try { check(release.await(10, TimeUnit.SECONDS)) } finally { super.close() }
                }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source(): BufferedSource = input
            }).build()
        }.build()
        val transport = RecordingProgressTransport(RequestContext({ expected }, {}).capture(expected), { peer.url },
            { original.source.profile }, { "synthetic-bearer" }, { "synthetic-unlock" }, { "synthetic-client" }, { null }, client)
        val gateway = object : RecordingProgressGateway {
            override suspend fun read(original: RecordingDerivedObservation, expectedContext: Long): RecordingProgressSnapshotObservation = error("No reopening")
            override suspend fun write(original: RecordingDerivedObservation, command: RecordingProgressCommand, expectedContext: Long): RecordingProgressWriteObservation {
                val raw = transport.send(RecordingProgressRequest(original, expectedContext, command))
                return RecordingProgressWriteObservation.accept(command, raw.text, requireNotNull(raw.clock))
            }
        }
        val reporter = RecordingProgressReporter(resource, initial, gateway, { original.ready() }, { 1001 }, { false }, failed = { failures.incrementAndGet() })
        var finish: Job? = null
        try {
            reporter.start(); val done = reporter.close(1001) { after.set(true) }; finish = done
            assertTrue(withContext(Dispatchers.IO) { closing.await(5, TimeUnit.SECONDS) })
            assertSame(done, reporter.close(0) { error("Second cleanup callback") })
            assertFalse(done.isCompleted); assertFalse(after.get()); assertEquals(1, RecordingProgressResource.charged())
            parent.cancel() // The old Controller/UI scope may expire while actual body Close is pending.
            assertFalse(parent.isActive); assertFalse(done.isCompleted); assertEquals(1, RecordingProgressResource.charged())
            assertEquals(1, peer.requests.get())
            release.countDown(); withTimeout(5_000) { done.join() }
            assertTrue(after.get()); assertEquals(0, RecordingProgressResource.charged()); assertEquals(1, peer.requests.get())
            assertEquals(0, failures.get()); assertTrue(requireNotNull(peer.body.get()).contains("\"positionMs\":1001"))
        } finally {
            release.countDown(); finish?.join(); resource.close().join(); parent.cancel()
            peer.close(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            assertTrue(client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun background_pin_obligation_does_not_interrupt_actor_but_owner_loss_does() = runTest {
        val f = Fixture(backgroundScope) { testScheduler.currentTime * 1_000_000 }
        val gate = DeferredResumeGate { testScheduler.currentTime }
        val binding = ResumeBinding(Any(), ProfileDataContext.current, Any(), "synthetic-client", Any())
        val playerPage = Any(); val browsing = Any()
        try {
            gate.enter(binding, playerPage, true, true, false, false, 2)
            gate.backgrounded(binding); f.playing = true; f.reporter.start()
            advanceTimeBy(300_001); runCurrent()
            assertNull(gate.enter(binding, playerPage, true, true, false, false, 2))
            assertTrue(gate.blocked.value); assertTrue(f.gateway.commands.size >= 30); assertEquals(0, f.failures)
            assertNotNull(gate.enter(binding, browsing, false, true, false, false, 2))
            val before = f.gateway.commands.size
            ProfileDataContext.invalidate(); advanceTimeBy(251); runCurrent()
            f.reporter.offer(111); runCurrent()
            assertEquals(before, f.gateway.commands.size); assertEquals(0, RecordingProgressResource.charged())
        } finally { f.reporter.close(null) {}.join() }
    }

    private class ReplyPeer(private val json: String) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}/".toHttpUrl()
        val requests = AtomicInteger()
        val body = AtomicReference<String?>()
        private val socket = AtomicReference<Socket?>()
        private val failure = AtomicReference<Throwable?>()
        private val worker = Thread {
            try {
                listener.accept().use { connection ->
                    socket.set(connection); connection.soTimeout = 5000
                    val input = connection.getInputStream().bufferedReader(Charsets.US_ASCII)
                    check(input.readLine()?.startsWith("POST /api/live/recordings/") == true)
                    var length = 0
                    while (true) {
                        val line = requireNotNull(input.readLine()); if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    require(length in 1..4096); val body = CharArray(length); var read = 0
                    while (read < length) { val count = input.read(body, read, length - read); check(count > 0); read += count }
                    this@ReplyPeer.body.set(String(body)); requests.incrementAndGet()
                    val bytes = json.toByteArray()
                    val header = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nDate: Thu, 19 Nov 2026 12:00:00 GMT\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    connection.getOutputStream().apply { write(header.toByteArray()); write(bytes); flush() }
                }
            } catch (error: Throwable) { if (!listener.isClosed) failure.set(error) }
        }.apply { isDaemon = true; name = "recording-progress-final-synthetic-peer"; start() }
        override fun close() {
            listener.close(); socket.get()?.close(); worker.join(5000)
            assertFalse(worker.isAlive); failure.get()?.let { throw AssertionError("Owned peer failed", it) }
        }
    }
}
