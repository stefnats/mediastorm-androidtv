package com.strmr.tv.rooms

import com.strmr.tv.api.ApiError
import com.strmr.tv.api.ApiJson
import com.strmr.tv.api.RequestContext
import com.strmr.tv.api.HttpFailure
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** Real loopback HTTP requests, not a response supplied by an interceptor. Root runs them offline. */
class WatchRoomRequestTest {
    private fun withServer(code: Int = 200, body: String = ApiJson.encodeToString(WatchRoom.serializer(), RoomFixtures.room()),
        action: suspend (WatchRoomGateway, MutableList<String>, () -> Unit) -> Unit) {
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val requests = CopyOnWriteArrayList<String>()
        var epoch = 9L
        val contexts = RequestContext({ epoch }, { epoch++ })
        val worker = thread(isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val lines = mutableListOf<String>()
                    while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break; lines += line }
                    val size = lines.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                    repeat(size) { input.read() }
                    requests += lines.joinToString("\n")
                    val bytes = body.toByteArray()
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 " + code + " Synthetic\r\nContent-Length: " + bytes.size +
                            "\r\nConnection: close\r\nContent-Type: application/json\r\n\r\n").toByteArray())
                        write(bytes); flush()
                    }
                }
            } catch (_: java.net.SocketException) { }
        }
        try {
            val gateway = WatchRoomTransport(contexts.capture(9), ("http://127.0.0.1:" + server.localPort + "/base").toHttpUrl(),
                mapOf("Authorization" to "Bearer synthetic-original", "X-Client-ID" to "client-one", "X-Profile-Unlock" to "synthetic-pin"),
                "viewer", "client-one", OkHttpClient())
            runBlocking { action(gateway, requests) { epoch++ } }
        } finally { server.close(); worker.join(5000); check(!worker.isAlive) }
    }
    @Test fun fixedRoutesUseOriginalHeaders() = withServer { api, requests, _ ->
        api.join("room-one")
        assertEquals(1, requests.size)
        assertTrue(requests.single().startsWith("POST /base/api/users/viewer/watch-rooms/room-one/join "))
        assertTrue(requests.single().contains("Authorization: Bearer synthetic-original", true))
        assertTrue(requests.single().contains("X-Profile-Unlock: synthetic-pin", true))
    }
    @Test fun mutationsNeverRefreshPromptOrReplay() = withServer(401, """{"error":"expired"}""") { api, requests, _ ->
        assertEquals(401, (runCatching { api.join("room-one") }.exceptionOrNull() as ApiError).statusCode)
        assertEquals(1, requests.size)
    }
    @Test fun profileChangeRetiresPublication() = withServer { api, requests, change ->
        change()
        assertFalse(api.current())
        assertTrue(runCatching { api.join("room-one") }.isFailure)
        assertTrue(requests.isEmpty())
    }
    @Test fun acceptUsesRecipientProfile() = withServer { api, requests, _ ->
        api.accept("invite-one")
        assertTrue(requests.single().startsWith("POST /base/api/users/viewer/watch-room-account-invitations/invite-one/accept "))
    }
    @Test fun conflictReadsWithoutReplayingState() = withServer(409, """{"error":"changed"}""") { api, requests, _ ->
        val failure = runCatching { api.state("room-one", "playing", 4.0, 100.0, 9007199254740993L) }.exceptionOrNull()
        assertEquals(409, (failure as ApiError).statusCode)
        assertEquals(1, requests.size)
    }
    @Test fun unknownMutationHasNoAutomaticRetry() = withServer(503, """{"error":"unknown"}""") { api, requests, _ ->
        assertEquals(503, (runCatching { api.join("room-one") }.exceptionOrNull() as ApiError).statusCode)
        assertEquals(1, requests.size)
    }
}
