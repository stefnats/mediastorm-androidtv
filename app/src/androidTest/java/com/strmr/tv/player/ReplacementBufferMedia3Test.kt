package com.strmr.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.mediaHeaders
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run only on a disposable device with no account, using Root-generated synthetic AVC/AAC
 * MP4s (good >=60s; short <3s). Explicit arguments and SHA-256 pins are mandatory. These
 * tests never resolve providers, load a playlist, or reuse a stored account/profile.
 * Test APK compilation is a separate gate from actual decoder/device execution.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class ReplacementBufferMedia3Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private suspend fun <T> main(action: () -> T): T = withContext(Dispatchers.Main) { action() }
    private suspend fun waitFor(label: String, predicate: () -> Boolean) {
        withTimeout(45_000) { while (!main(predicate)) delay(25) }
    }
    private fun fixture(key: String): File {
        check(arguments.getString("syntheticFixtureOnly") == "true") { "Explicit disposable synthetic fixture opt-in required" }
        val name = requireNotNull(arguments.getString(key))
        check(Regex("mediastorm-synthetic-[a-z0-9-]+\\.mp4").matches(name))
        val cache = instrumentation.targetContext.cacheDir.canonicalFile
        val file = File(cache, name).canonicalFile
        check(file.parentFile == cache)
        check(file.isFile && file.length() in 1L..(8L * 1024 * 1024))
        val expected = requireNotNull(arguments.getString("${key}Sha256"))
        check(Regex("[0-9a-f]{64}").matches(expected))
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(8192); while (true) {
            val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n)
        } }
        check(digest.digest().joinToString("") { "%02x".format(it) } == expected)
        return file
    }
    private suspend fun withPlayback(mode: String = "good", body: suspend (Fixture) -> Unit) {
        val good = fixture("syntheticGoodMp4"); val short = fixture("syntheticShortMp4")
        check(ApiClient.session == null && ApiClient.remoteCredential == null && ApiClient.profileId == "default" &&
            ApiClient.mediaHeaders.keys.none { it.equals("Authorization", true) || it.contains("unlock", true) || it.contains("credential", true) }) {
            "A disposable target without stored account/profile credentials is required"
        }
        SyntheticServer(good, short, mode).use { server ->
            val oldOrigin = ApiClient.baseUrl
            main { ApiClient.configureServer(server.origin.toHttpUrl(), null, null) }
            val monitor = instrumentation.addMonitor(PlayerActivity::class.java.name, null, false)
            var activity: PlayerActivity? = null
            var test: Fixture? = null
            try {
                main {
                    val owner = PlaybackSessionContext.capture()
                    PlaybackCoordinator.playSession(PlayerSession(
                        PlaybackRequest(DetailsTarget("tt0000000", "Synthetic buffer canary", isMovie = true)),
                        "${server.origin}primary.mp4", null, streamPath = "/synthetic/primary.mp4",
                        prequeueId = "original", releaseName = "Synthetic AVC AAC", ownership = owner,
                    ))
                }
                activity = requireNotNull(monitor.waitForActivityWithTimeout(15_000) as? PlayerActivity)
                val opened = requireNotNull(activity)
                waitFor("actual primary decoder") { opened.currentController?.player?.playbackState == Player.STATE_READY }
                val controller = main { requireNotNull(opened.currentController) }
                assertTrue(main { controller.player.duration >= 60_000 })
                val flow = main { controller.openReleaseReports() }
                val running = Fixture(opened, controller, flow, server)
                test = running
                body(running)
            } finally {
                instrumentation.removeMonitor(monitor)
                val actual = test
                val buffer = main { actual?.flow?.bufferedPreparation as? ReplacementBuffer }
                main { actual?.flow?.close(); activity?.finish() }
                buffer?.let { withContext(Dispatchers.Main) { withTimeout(45_000) { it.closeAndJoin() } } }
                activity?.let { retiring -> waitFor("actual activity destroyed") { retiring.isDestroyed } }
                main { ApiClient.configureServer(oldOrigin, null, null) }
            }
        }
    }
    private inner class Fixture(val activity: PlayerActivity, val primary: PlayerController,
        val flow: ReleaseReports, val server: SyntheticServer) {
        suspend fun prepare(mode: ReleaseResume = ReleaseResume.BEGINNING): ReplacementBuffer {
            withContext(Dispatchers.Main) { flow.load(); flow.resume(mode); flow.submit(false, true) }
            waitFor("actual selected paused decoder and range") { !flow.state.value.busy }
            assertNotNull(main { flow.state.value.ready })
            return main { requireNotNull(flow.bufferedPreparation as? ReplacementBuffer) }
        }
    }
    @Test fun actualThreeSecondRangeAndSelectedPausedDecoder() = runBlocking {
        withPlayback { f ->
            val before = main { f.primary.player.currentPosition }
            val buffer = f.prepare()
            val observation = main { buffer.observe() }
            assertTrue(observation.audioSelected && observation.videoSelected && observation.paused)
            assertTrue(observation.audioDecoderInit > observation.audioDecoderReleased)
            assertTrue(observation.videoDecoderInit > observation.videoDecoderReleased)
            assertEquals(buffer.targetMs, observation.positionMs)
            assertTrue(observation.bufferedMs - observation.positionMs >= 3000)
            assertTrue(observation.durationMs - observation.positionMs >= 3000)
            assertTrue(observation.preloadThreadAlive && observation.playbackThreadAlive)
            assertSame(f.primary, main { f.activity.currentController })
            assertTrue(main { f.primary.player.playWhenReady && f.primary.player.currentPosition > before })
            assertTrue(f.server.candidateReads > 0)
        }
    }
    @Test fun actualFailedCandidateLeavesPrimaryRunning() = runBlocking {
        withPlayback("broken") { f ->
            withContext(Dispatchers.Main) { f.flow.load(); f.flow.submit(false, true) }
            waitFor("actual candidate failure") { !f.flow.state.value.busy }
            assertNull(main { f.flow.state.value.ready }); assertSame(f.primary, main { f.activity.currentController })
            assertSame(f.primary.session, main { PlaybackCoordinator.pending })
            assertTrue(main { f.primary.player.playWhenReady && f.primary.player.playerError == null })
            assertTrue(f.server.candidateReads > 0)
        }
    }
    @Test fun actualCancellationJoinsBodyAndPreloadLooper() = runBlocking {
        withPlayback { f ->
            val buffer = f.prepare()
            assertTrue(main { buffer.observe().playbackThreadAlive })
            main { f.flow.close() }
            withContext(Dispatchers.Main) { withTimeout(45_000) { buffer.closeAndJoin() } }
            val closed = main { buffer.observe() }
            assertTrue(closed.cleanupJoined); assertFalse(closed.preloadThreadAlive)
            assertFalse(closed.surfaceThreadAlive); assertFalse(closed.playbackThreadAlive); assertEquals(0, closed.originalWorkersAlive); assertEquals(0, closed.openBodies); assertEquals(0, closed.activeHttpCalls)
            assertSame(f.primary, main { f.activity.currentController })
            assertTrue(main { f.primary.player.playWhenReady })
        }
        withPlayback("held") { f ->
            withContext(Dispatchers.Main) { f.flow.load(); f.flow.submit(false, true) }
            waitFor("real HTTP request held before headers") { f.server.candidateOpen > 0 }
            main { f.flow.close() }
            waitFor("real held HTTP socket retired") { f.server.candidateOpen == 0 }
            waitFor("actual local source and both owned loopers joined") { ReplacementBuffer.ownedLeaseCount() == 0 }
            assertSame(f.primary, main { f.activity.currentController })
            // A fresh admitted candidate proves the old global charge was not returned
            // until its actual source/call/loader/shared-looper/surface cleanup joined.
            f.server.mode = "good"
            val next = main { f.primary.openReleaseReports() }
            try {
                withContext(Dispatchers.Main) { next.load(); next.resume(ReleaseResume.BEGINNING); next.submit(false, true) }
                waitFor("fresh source admitted after physical cleanup") { !next.state.value.busy }
                assertNotNull(main { next.state.value.ready })
                val fresh = main { requireNotNull(next.bufferedPreparation) }
                main { next.close() }; withContext(Dispatchers.Main) { fresh.closeAndJoin() }
            } finally { main { next.close() } }
        }
    }
    @Test fun actualSwapConsumesSameLoadedSourceAtFrozenTarget() = runBlocking {
        withPlayback { f ->
            main { f.primary.player.seekTo(10_000) }
            waitFor("actual primary seek") { f.primary.player.currentPosition >= 10_000 }
            val buffer = f.prepare(ReleaseResume.CURRENT)
            val frozen = buffer.targetMs
            val standbyPcmGeneration = main { buffer.observe().decodedPcmGeneration }
            assertTrue(frozen >= 10_000)
            main { f.flow.take() }
            waitFor("same activity committed actual source") { f.activity.currentController !== f.primary }
            val replacement = main { requireNotNull(f.activity.currentController) }
            assertSame(buffer, replacement.session.replacementBuffer)
            assertSame(replacement.session, main { PlaybackCoordinator.pending })
            assertTrue(main { replacement.player.playWhenReady })
            assertTrue(main { replacement.player.currentPosition in frozen..(frozen + 1500) })
            assertFalse(f.activity.isFinishing)
            assertEquals(0, replacement.session.request.audioRecoveryCount)
            waitFor("actual positive PCM frames after adoption") {
                val observed = buffer.observe()
                observed.decodedPcmGeneration > standbyPcmGeneration && observed.decodedPcmFrames > 0
            }
            // Cleanup the transferred primary's retained actual I/O lease too.
            main { f.activity.finish() }
            withContext(Dispatchers.Main) { withTimeout(45_000) { buffer.closeAndJoin() } }
            assertTrue(main { buffer.observe().cleanupJoined })
        }
    }
    @Test fun actualShortTailDoesNotPretendThreeSecondsReady() = runBlocking {
        withPlayback("short") { f ->
            withContext(Dispatchers.Main) { f.flow.load(); f.flow.resume(ReleaseResume.BEGINNING); f.flow.submit(false, true) }
            waitFor("actual short-tail conservative failure") { !f.flow.state.value.busy }
            assertNull(main { f.flow.state.value.ready }); assertSame(f.primary, main { f.activity.currentController })
            assertTrue(main { f.primary.player.playWhenReady }); assertTrue(f.server.candidateReads > 0)
        }
    }

    /** An actual bounded loopback HTTP fixture with range requests and joined owned sockets. */
    private class SyntheticServer(private val good: File, private val short: File, @Volatile var mode: String) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val origin = "http://127.0.0.1:${server.localPort}/"
        private val stopped = AtomicBoolean()
        private val sockets = Collections.synchronizedSet(linkedSetOf<Socket>())
        private val workers = Collections.synchronizedList(mutableListOf<Thread>())
        @Volatile var candidateReads = 0; private set
        @Volatile var candidateOpen = 0; private set
        private val accept = Thread({
            while (!stopped.get()) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                val admitted = synchronized(sockets) { if (stopped.get() || sockets.size >= 16) false else { sockets += socket; true } }
                if (!admitted) { socket.close(); continue }
                workers.removeAll { !it.isAlive }
                val worker = Thread({ try { socket.use(::serve) } catch (_: Exception) { }
                    finally { sockets -= socket } }, "synthetic-media-request")
                workers += worker; worker.start()
            }
        }, "synthetic-media-accept").also { it.start() }
        private fun serve(socket: Socket) {
            socket.soTimeout = 15_000
            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            val first = reader.readLine()?.split(' ') ?: return
            val method = first[0]; val url = (origin.dropLast(1) + first[1]).toHttpUrl()
            val headers = mutableMapOf<String, String>()
            while (true) { val line = reader.readLine() ?: return; if (line.isEmpty()) break
                val colon = line.indexOf(':'); if (colon > 0) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
            }
            var remaining = headers["content-length"]?.toIntOrNull() ?: 0
            check(remaining in 0..16_384)
            while (remaining > 0) { val read = reader.read(CharArray(minOf(remaining, 1024))); if (read < 0) return; remaining -= read }
            val path = url.encodedPath
            if (path == "/primary.mp4" || path == "/api/video/stream") {
                val candidate = path != "/primary.mp4"
                if (candidate) synchronized(this) { candidateReads++ }
                if (candidate && mode == "held") {
                    synchronized(this) { candidateOpen++ }
                    try { while (socket.getInputStream().read() >= 0) { } }
                    finally { synchronized(this) { candidateOpen-- } }
                    return
                }
                if (candidate && mode == "broken") { json(socket, "{}", 404); return }
                val file = if (candidate && mode == "short") short else good
                val range = headers["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
                val start = range?.groupValues?.get(1)?.toLong() ?: 0
                val end = minOf(range?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toLong() ?: (file.length() - 1), file.length() - 1)
                check(start >= 0 && start <= end && end < file.length())
                val output = socket.getOutputStream()
                val prefix = if (range != null) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-$end/${file.length()}\r\n" else "HTTP/1.1 200 OK\r\n"
                output.write((prefix + "Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: ${end - start + 1}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                RandomAccessFile(file, "r").use { input -> input.seek(start); var bytes = end - start + 1; val buffer = ByteArray(8192)
                    while (bytes > 0) { val n = input.read(buffer, 0, minOf(bytes, buffer.size.toLong()).toInt()); check(n > 0); output.write(buffer, 0, n); bytes -= n }
                }
                output.flush(); return
            }
            when {
                path.endsWith("/original/report") && method == "GET" -> json(socket, """{"sourceVersion":"synthetic-version","audioLanguage":"fra"}""")
                path.endsWith("/original/retry") && method == "POST" -> json(socket, """{"replacement":{"prequeueId":"candidate"}}""")
                path.endsWith("/prequeue/candidate") -> json(socket, """{"prequeueId":"candidate","status":"ready","streamPath":"/synthetic/candidate.mp4","duration":${if (mode == "short") 2 else 60}}""")
                else -> json(socket, "{}")
            }
        }
        private fun json(socket: Socket, body: String, status: Int = 200) {
            val data = body.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write("HTTP/1.1 $status Fixture\r\nContent-Type: application/json\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)); write(data); flush()
            }
        }
        override fun close() {
            stopped.set(true); server.close()
            accept.join(20_000); check(!accept.isAlive)
            synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
            val deadline = System.nanoTime() + 20_000_000_000L
            synchronized(workers) { workers.toList() }.forEach {
                it.join(((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)); check(!it.isAlive)
            }
        }
    }
}
