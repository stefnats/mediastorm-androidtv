package com.strmr.tv.api

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ArtworkLearningApiTest {
    private val state = """{"profileId":"profile one","supported":true,"enabled":false,"minimumSamples":3,"maxSamples":128,"posterSamples":2,"backdropSamples":0}"""
    private data class Seen(val method: String, val path: String, val body: String, val headers: Map<String, String>)
    private class Peer(private val response: (Seen) -> String) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "synthetic-artwork-learning").apply { isDaemon = true } }
        val base = "http://127.0.0.1:${socket.localPort}/proxy/".toHttpUrl()
        val seen = CopyOnWriteArrayList<Seen>()
        init { worker.submit {
            while (!socket.isClosed) {
                val connection = try { socket.accept() } catch (_: IOException) { break }
                runCatching { connection.use { client ->
                    client.soTimeout = 1000
                    val input = client.getInputStream().bufferedReader()
                    val first = input.readLine()?.split(' ') ?: return@use
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        val split = line.indexOf(':')
                        if (split > 0) headers[line.take(split).lowercase()] = line.drop(split + 1).trim()
                    }
                    val body = CharArray(headers["content-length"]?.toIntOrNull() ?: 0)
                    var offset = 0
                    while (offset < body.size) { val count = input.read(body, offset, body.size - offset); if (count < 0) break; offset += count }
                    val row = Seen(first[0], first[1], String(body), headers); seen += row
                    client.getOutputStream().apply { write(response(row).toByteArray()); flush() }
                } }
            }
        } }
        override fun close() { socket.close(); worker.shutdownNow(); worker.awaitTermination(2, TimeUnit.SECONDS) }
    }
    private fun response(status: Int, body: String = state, extra: String = "") =
        "HTTP/1.1 $status Synthetic\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n${extra}Connection: close\r\n\r\n$body"
    private fun transport(peer: Peer, context: RequestContext = RequestContext({ 1L }, {}), profile: String = "profile one") =
        ArtworkLearningTransport(context.capture(1L), peer.base, profile,
            mapOf("Authorization" to "Bearer synthetic", "X-Client-ID" to "synthetic-client", "X-Profile-Unlock" to "synthetic-pin"), OkHttpClient())

    @Test fun strictReplyRequiresProfileCapabilityBoundsAndActualJSONTypes() {
        assertEquals(2, artworkLearningState(state, "profile one").posterSamples)
        val invalid = listOf(state.replace("profile one", "another"), state.replace("true", "false"),
            state.replace("\"enabled\":false", "\"enabled\":\"false\""), state.replace("\"minimumSamples\":3", "\"minimumSamples\":\"3\""),
            state.replace("\"minimumSamples\":3", "\"minimumSamples\":2"), state.replace("\"maxSamples\":128", "\"maxSamples\":129"),
            state.replace("\"posterSamples\":2", "\"posterSamples\":129"), state.replace("\"backdropSamples\":0", "\"backdropSamples\":-1"),
            state.replace("\"posterSamples\":2", "\"posterSamples\":80").replace("\"backdropSamples\":0", "\"backdropSamples\":80"),
            state.replace("\"profileId\":\"profile one\",", ""))
        invalid.forEach { assertTrue(runCatching { artworkLearningState(it, "profile one") }.isFailure) }
    }
    @Test fun commandsContainExactlyOneExplicitField() {
        assertEquals("{\"enabled\":false}", artworkLearningBody(false, false).toString())
        assertEquals("{\"enabled\":true}", artworkLearningBody(true, false).toString())
        assertEquals("{\"reset\":true}", artworkLearningBody(null, true).toString())
        assertTrue(runCatching { artworkLearningBody(null, false) }.isFailure)
        assertTrue(runCatching { artworkLearningBody(true, true) }.isFailure)
    }
    @Test fun realHTTPUsesCapturedProfilePathAndSingleConfirmedMutation() = runTest {
        Peer { row -> response(200, if (row.method == "PUT") state.replace("\"enabled\":false", "\"enabled\":true") else state) }.use { peer ->
            val gateway = transport(peer)
            assertFalse(gateway.read()!!.enabled)
            assertTrue(gateway.write(true, false).enabled)
            assertEquals(listOf("GET", "PUT"), peer.seen.map { it.method })
            assertTrue(peer.seen.all { it.path == "/proxy/api/users/profile%20one/artwork-learning" })
            assertEquals("{\"enabled\":true}", peer.seen.last().body)
            assertEquals("Bearer synthetic", peer.seen.last().headers["authorization"])
            assertEquals("synthetic-pin", peer.seen.last().headers["x-profile-unlock"])
        }
    }
    @Test fun onlyOptionalRead404IsUnavailableAndNoFailedWriteReplays() = runTest {
        for (status in listOf(401, 403, 404, 408, 503, 307)) Peer { response(status, "{}", "Retry-After: 0\r\nLocation: /again\r\n") }.use { peer ->
            val gateway = transport(peer)
            if (status == 404) assertNull(gateway.read())
            else assertTrue(runCatching { gateway.read() }.exceptionOrNull() is ApiError.Http)
            assertEquals(1, peer.seen.size)
            assertTrue(runCatching { gateway.write(true, false) }.exceptionOrNull() is ApiError.Http)
            assertEquals(2, peer.seen.size)
        }
    }
    @Test fun resetRequiresDisabledEmptyCountersAndEnableRequiresExactAcknowledgement() = runTest {
        Peer { response(200) }.use { peer ->
            val gateway = transport(peer)
            assertTrue(runCatching { gateway.write(true, false) }.isFailure)
            assertTrue(runCatching { gateway.write(null, true) }.isFailure)
            assertEquals(2, peer.seen.size)
        }
        Peer { response(200, state.replace("\"posterSamples\":2", "\"posterSamples\":0")) }.use { peer ->
            assertFalse(transport(peer).write(null, true).enabled)
            assertEquals("{\"reset\":true}", peer.seen.single().body)
        }
    }
    @Test fun staleProfileCannotSendOrAdoptAReply() = runTest {
        var epoch = 1L
        val context = RequestContext({ epoch }, { epoch++ })
        Peer { response(200) }.use { peer ->
            val gateway = transport(peer, context)
            context.changeProfile { }
            assertTrue(runCatching { gateway.read() }.exceptionOrNull() is CancellationException)
            assertTrue(peer.seen.isEmpty())
        }
        epoch = 1L
        Peer { context.changeProfile { }; response(200) }.use { peer ->
            assertTrue(runCatching { transport(peer, context).read() }.exceptionOrNull() is CancellationException)
            assertEquals(1, peer.seen.size)
        }
    }
}
