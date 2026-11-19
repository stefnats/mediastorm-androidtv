package com.strmr.tv.player

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.withoutAutomaticReplay
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.live.DerivedTestData
import com.strmr.tv.live.recordingDerivedStreamUrl
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RecordingDerivedMediaAccessTest {
    private val headers = mapOf("Authorization" to "Bearer synthetic-recording-session", "X-Client-ID" to "synthetic-device", "X-Profile-Unlock" to "synthetic-recording-unlock")
    @Before fun profile() { ProfileDataContext.invalidate() }
    @After fun restore() { ProfileDataContext.invalidate() }
    private fun source() = RecordingDerivedPlayback.launch(PlaybackSessionContext.capture(), DerivedTestData.observation(), { true }, { 0 }).also { it.claim() }
    private fun endpoint(base: HttpUrl) = recordingDerivedStreamUrl(base, ApiClient.profileId, "synthetic-recording", "a".repeat(32))
    private fun info(error: IOException) = LoadErrorHandlingPolicy.LoadErrorInfo::class.java.constructors.single().newInstance(null, null, error, 1) as LoadErrorHandlingPolicy.LoadErrorInfo
    @Test fun `exact dedicated URL has only profile selector and never provider path or URL credentials`() {
        val base = "https://example.invalid/prefix/".toHttpUrl(); val url = endpoint(base)
        assertEquals("/prefix/api/live/recordings/synthetic-recording/derived/${"a".repeat(32)}/stream", url.encodedPath)
        assertEquals(setOf("profileId"), url.queryParameterNames); assertEquals(ApiClient.profileId, url.queryParameter("profileId"))
        assertTrue(runCatching { endpoint("https://synthetic-user:synthetic-password@example.invalid/".toHttpUrl()) }.isFailure)
    }
    @Test fun `real application interceptor rejects foreign origin selector and private tails before any receiver sees headers`() {
        val base = "https://example.invalid/prefix/".toHttpUrl(); val source = source(); val access = RecordingDerivedMediaAccess(base, source, headers)
        val received = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor(access.interceptor).addInterceptor { chain ->
            received.incrementAndGet(); Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .body("synthetic media".toResponseBody("video/x-matroska".toMediaType())).build()
        }.build().withoutAutomaticReplay()
        try {
            val exact = endpoint(base)
            val changed = listOf(exact.newBuilder().host("other.invalid").build(), exact.newBuilder().scheme("http").build(),
                exact.newBuilder().addQueryParameter("profileId", "other").build(), exact.newBuilder().addQueryParameter("token", "synthetic").build(),
                exact.newBuilder().encodedPath("/prefix/api/video/stream").build(), exact.newBuilder().encodedPath(exact.encodedPath + "/private").build())
            changed.forEach { url -> assertTrue(runCatching { client.newCall(Request.Builder().url(url).build()).execute().close() }.isFailure) }
            assertEquals(0, received.get())
        } finally { source.release(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }
    @Test fun `terminal denied changed busy or redirected raw source never retries or selects a fallback`() {
        for (code in listOf(401, 403, 404, 409, 413, 416, 429, 503, 302)) {
            val base = "https://example.invalid/".toHttpUrl(); val source = source(); val access = RecordingDerivedMediaAccess(base, source, headers); val received = AtomicInteger()
            val client = OkHttpClient.Builder().addInterceptor(access.interceptor).addInterceptor { chain ->
                received.incrementAndGet(); Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic")
                    .header("Location", "/api/video/stream?path=recording:other").body("Unavailable".toResponseBody("text/plain".toMediaType())).build()
            }.build().withoutAutomaticReplay()
            try {
                assertTrue(runCatching { client.newCall(Request.Builder().url(endpoint(base)).build()).execute().close() }.isFailure)
                assertEquals(1, received.get()); assertFalse(source.allowed())
                assertEquals(C.TIME_UNSET, access.loadPolicy.getRetryDelayMsFor(info(IOException("Synthetic failure"))))
                assertNull(access.loadPolicy.getFallbackSelectionFor(LoadErrorHandlingPolicy.FallbackOptions(2, 0, 1, 0), info(IOException("Synthetic failure"))))
            } finally { source.release(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
        }
    }
    @Test fun `actual dedicated ranged request keeps original headers out of URL and demux fallback`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val captured = AtomicReference<List<String>>()
            val worker = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 3000; val input = socket.getInputStream().bufferedReader(); val lines = mutableListOf<String>()
                    while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break; lines += line }; captured.set(lines)
                    socket.getOutputStream().write("HTTP/1.1 206 Partial Content\r\nContent-Type: video/x-matroska\r\nContent-Length: 6\r\nContent-Range: bytes 4-9/2048\r\nConnection: close\r\n\r\njoined".toByteArray())
                }
            }.apply { isDaemon = true; start() }
            val base = "http://127.0.0.1:${server.localPort}/prefix/".toHttpUrl(); val source = source(); val access = RecordingDerivedMediaAccess(base, source, headers)
            val client = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).addInterceptor(access.interceptor).addNetworkInterceptor(access.requestInterceptor).build().withoutAutomaticReplay()
            try {
                client.newCall(Request.Builder().url(endpoint(base)).header("Range", "bytes=4-9").build()).execute().use {
                    assertEquals(206, it.code); assertEquals("joined", it.body!!.string())
                }
                val lines = captured.get(); assertTrue(lines.first().contains("/derived/${"a".repeat(32)}/stream?profileId=${ApiClient.profileId}")); assertFalse(lines.first().contains("token"))
                headers.forEach { (key, value) -> assertTrue(lines.any { it.equals("$key: $value", true) }) }
                assertTrue(lines.any { it.equals("Range: bytes=4-9", true) })
            } finally { server.close(); worker.join(3000); source.release(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); assertFalse(worker.isAlive) }
        }
    }
    @Test fun `late actual socket body after original profile loss never copies bytes to caller buffer`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val release = CountDownLatch(1); val reading = CountDownLatch(1)
            val worker = Thread {
                try { server.accept().use { socket ->
                    socket.soTimeout = 3000; val input = socket.getInputStream().bufferedReader()
                    while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break }
                    socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: video/x-matroska\r\nContent-Length: 10\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
                    release.await(3, TimeUnit.SECONDS); socket.getOutputStream().write("late-bytes".toByteArray())
                } } catch (_: IOException) { }
            }.apply { isDaemon = true; start() }
            val base = "http://127.0.0.1:${server.localPort}/".toHttpUrl(); val source = source(); val access = RecordingDerivedMediaAccess(base, source, headers)
            val client = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) { reading.countDown() }
            }).addInterceptor(access.interceptor).addNetworkInterceptor(access.requestInterceptor).build().withoutAutomaticReplay()
            val response = client.newCall(Request.Builder().url(endpoint(base)).build()).execute()
            val sink = Buffer(); val failure = AtomicReference<Throwable>()
            val consumer = Thread { try { response.body!!.source().read(sink, 10) } catch (error: Throwable) { failure.set(error) } }.apply { start() }
            try {
                assertTrue(reading.await(3, TimeUnit.SECONDS)); ProfileDataContext.invalidate(); release.countDown(); consumer.join(3000)
                assertFalse(consumer.isAlive); assertTrue(failure.get() is IOException); assertEquals(0L, sink.size)
            } finally { release.countDown(); response.close(); server.close(); consumer.join(3000); worker.join(3000); source.release(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
        }
    }

    private fun bodyDataSource(body: ResponseBody, reads: AtomicInteger = AtomicInteger(), afterRead: (ByteArray) -> Unit = {}): DataSource = object : DataSource {
        private val stream = body.source().inputStream()
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long = body.contentLength()
        override fun getUri(): android.net.Uri? = null
        override fun close() = body.close()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            reads.incrementAndGet()
            val count = stream.read(buffer, offset, length)
            afterRead(buffer)
            return count
        }
    }

    @Test fun `actual consumer boundary rejects already buffered response bytes after profile expiry or release`() {
        for (retire in listOf("profile", "expiry", "release")) {
            ProfileDataContext.invalidate()
            var now = 0L
            val observation = DerivedTestData.observation()
            val source = RecordingDerivedPlayback.launch(PlaybackSessionContext.capture(), observation, { true }, { now }).also { it.claim() }
            val base = "https://example.invalid/".toHttpUrl()
            val access = RecordingDerivedMediaAccess(base, source, headers)
            val client = OkHttpClient.Builder().addInterceptor(access.interceptor).addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                    .body(ByteArray(16_384) { 73 }.toResponseBody("video/x-matroska".toMediaType())).build()
            }.build().withoutAutomaticReplay()
            val response = client.newCall(Request.Builder().url(endpoint(base)).build()).execute()
            val reads = AtomicInteger()
            val data = access.dataSourceFactory(DataSource.Factory { bodyDataSource(response.body!!, reads) }).createDataSource()
            try {
                val first = ByteArray(1)
                assertEquals(1, data.read(first, 0, 1)); assertEquals(73.toByte(), first[0])
                assertTrue("The real Okio response must still hold admitted bytes", response.body!!.source().buffer.size > 0)
                when (retire) {
                    "profile" -> ProfileDataContext.invalidate()
                    "expiry" -> now = requireNotNull(observation.deadlineNanos)
                    else -> source.release()
                }
                val caller = ByteArray(16) { 19 }
                assertTrue(runCatching { data.read(caller, 3, 5) }.exceptionOrNull() is IOException)
                assertArrayEquals(ByteArray(16) { 19 }, caller)
                assertEquals("No buffered upstream drain after retirement", 1, reads.get())
            } finally { data.close(); response.close(); source.release(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
        }
    }

    @Test fun `consumer boundary clears late staged bytes without touching original caller buffer`() {
        val source = source()
        val access = RecordingDerivedMediaAccess("https://example.invalid/".toHttpUrl(), source, headers)
        val staged = AtomicReference<ByteArray>()
        val body = ByteArray(8192) { 73 }.toResponseBody("video/x-matroska".toMediaType())
        val data = access.dataSourceFactory(DataSource.Factory {
            bodyDataSource(body, afterRead = { staged.set(it); ProfileDataContext.invalidate() })
        }).createDataSource()
        try {
            val caller = ByteArray(20) { 19 }
            assertTrue(runCatching { data.read(caller, 2, 12) }.exceptionOrNull() is IOException)
            assertArrayEquals(ByteArray(20) { 19 }, caller)
            assertNotNull(staged.get()); assertTrue(staged.get().all { it == 0.toByte() })
        } finally { data.close(); source.release() }
    }

    @Test fun `consumer boundary bounds staging preserves offsets zero length and terminal EOF`() {
        val source = source()
        val access = RecordingDerivedMediaAccess("https://example.invalid/".toHttpUrl(), source, headers)
        val reads = AtomicInteger(); var maxRead = 0
        val body = ByteArray(70_000) { 73 }.toResponseBody("video/x-matroska".toMediaType())
        val data = access.dataSourceFactory(DataSource.Factory {
            bodyDataSource(body, reads) { maxRead = maxOf(maxRead, it.size) }
        }).createDataSource()
        try {
            val caller = ByteArray(70_010) { 19 }; var total = 0
            assertEquals(0, data.read(caller, 4, 0)); assertEquals(0, reads.get())
            while (true) {
                val count = data.read(caller, 4 + total, 70_000 - total + 1)
                if (count < 0) break
                assertTrue(count in 1..32_768); total += count
            }
            assertEquals(70_000, total); assertTrue(maxRead <= 32_768)
            assertTrue(caller.take(4).all { it == 19.toByte() }); assertTrue(caller.drop(4).take(total).all { it == 73.toByte() })
            assertTrue(caller.drop(4 + total).all { it == 19.toByte() })
        } finally { data.close(); source.release() }
    }
}
