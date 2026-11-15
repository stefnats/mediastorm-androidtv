package com.strmr.tv.api

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class NonReplayingHttpTest {
    @Test fun `a remote creation transport failure does not reconnect and resend`() = runTest {
        val gate = RequestContext({ 1L }, {})
        var sent = 0; var recoveries = 0; var failures = 0
        val transport = ContextBoundTransport(gate.capture(), true, { recoveries++; true }, { failures++ }, allowReplay = false)
        try { transport.execute { sent++; throw java.net.SocketException("Synthetic lost creation response") }; fail() } catch (_: IOException) { }
        assertEquals(1, sent); assertEquals(0, recoveries); assertEquals(1, failures)
    }

    @Test fun `a real creation request does not follow a 307 redirect`() {
        withServer("HTTP/1.1 307 Temporary Redirect\r\nLocation: /second\r\nContent-Length: 0\r\nConnection: close\r\n\r\n") { port, calls ->
            val client = OkHttpClient.Builder().callTimeout(3, java.util.concurrent.TimeUnit.SECONDS).build().withoutAutomaticReplay()
            val request = Request.Builder().url("http://127.0.0.1:$port/create").post("{}".toRequestBody()).build()
            client.newCall(request).execute().use { assertEquals(307, it.code) }
            assertEquals(1, calls.get())
        }
    }

    @Test fun `a real lost response is attempted once`() {
        withServer(null) { port, calls ->
            val client = OkHttpClient.Builder().callTimeout(3, java.util.concurrent.TimeUnit.SECONDS).build().withoutAutomaticReplay()
            val request = Request.Builder().url("http://127.0.0.1:$port/create").post("{}".toRequestBody()).build()
            try { client.newCall(request).execute().close(); fail("Lost response unexpectedly succeeded") } catch (_: IOException) { }
            assertEquals(1, calls.get())
        }
    }

    private fun withServer(reply: String?, action: (Int, AtomicInteger) -> Unit) {
        val calls = AtomicInteger()
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            try {
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        calls.incrementAndGet()
                        val reader = socket.getInputStream().bufferedReader()
                        var contentLength = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true)) contentLength = line.substringAfter(':').trim().toInt()
                        }
                        repeat(contentLength) { reader.read() }
                        // A null reply models a lost response after the server
                        // could already have applied the mutation.
                        reply?.let { socket.getOutputStream().write(it.toByteArray()); socket.getOutputStream().flush() }
                    }
                }
            } catch (_: IOException) { }
        }.apply { isDaemon = true; start() }
        try { action(server.localPort, calls) } finally {
            server.close(); worker.join(1_000); assertFalse("Fixture server did not stop", worker.isAlive)
        }
    }
}
