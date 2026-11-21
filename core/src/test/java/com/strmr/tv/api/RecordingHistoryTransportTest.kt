package com.strmr.tv.api

import com.strmr.tv.live.*
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class RecordingHistoryTransportTest {
    private class Peer(private val payload: String, private val code: Int = 200,
        val gate: CountDownLatch = CountDownLatch(0)) : AutoCloseable {
        private val listener = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}/".toHttpUrl()
        val first = AtomicReference<String>(); val headers = AtomicReference<Map<String,String>>()
        val body = AtomicReference<String>(); val requests = AtomicInteger(); val headersSent = CountDownLatch(1)
        private val socket = AtomicReference<Socket?>()
        private val worker = Thread {
            while (!listener.isClosed) try {
                listener.accept().use { active ->
                    socket.set(active); active.soTimeout = 5000
                    val reader = active.getInputStream().bufferedReader(Charsets.US_ASCII)
                    first.set(requireNotNull(reader.readLine())); val values = linkedMapOf<String,String>()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; values[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
                    headers.set(values); val length = values["content-length"]?.toInt() ?: 0; require(length in 0..4096)
                    val chars = CharArray(length); var total = 0
                    while (total < length) { val read = reader.read(chars, total, length-total); require(read > 0); total += read }
                    body.set(String(chars)); requests.incrementAndGet()
                    active.getOutputStream().apply {
                        val bytes = payload.toByteArray(Charsets.UTF_8)
                        write("HTTP/1.1 $code Synthetic\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nDate: Thu, 19 Nov 2026 12:00:00 GMT\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush(); headersSent.countDown()
                        check(gate.await(5,TimeUnit.SECONDS)); write(bytes); flush()
                    }
                }
            } catch (_: IOException) { /* Closing the owned listener/socket joins actual work. */ }
        }.apply { isDaemon = true; name = "synthetic-recording-history-peer"; start() }
        override fun close() { gate.countDown(); listener.close(); socket.get()?.close(); worker.join(5000); assertFalse(worker.isAlive) }
    }
    private fun sender(client: OkHttpClient, peer: Peer, unlock: (String)->String? = { "synthetic-unlock" }) = RecordingProgressTransport(
        RequestContext({ 1L }, {}).capture(1), { peer.url.newBuilder().encodedPath("/proxy/").addQueryParameter("old", "discard").build() },
        { "adult" }, { "synthetic-bearer" }, unlock, { "synthetic-client" }, { null }, client)
    private fun client() = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
    private fun close(client: OkHttpClient) { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); assertTrue(client.dispatcher.executorService.awaitTermination(5,TimeUnit.SECONDS)) }
    private fun await(latch: CountDownLatch) { assertTrue(latch.await(5,TimeUnit.SECONDS)) }
    @Test fun actual_history_get_uses_only_canonical_opt_in_query() = runBlocking {
        val now = System.nanoTime(); val original = ProgressTestData.original(now)
        Peer(HistoryTestData.raw(original)).use { peer ->
            val client = client(); val lookedUp = mutableListOf<String>()
            try {
                val raw = sender(client,peer) { lookedUp += it; "synthetic-unlock" }.send(RecordingProgressRequest.history(original,1))
                assertEquals("GET /proxy/api/live/recordings/synthetic-recording/progress?includeHistory=true HTTP/1.1",peer.first.get())
                assertEquals("",peer.body.get()); assertEquals("Bearer synthetic-bearer",peer.headers.get()["authorization"])
                assertEquals("synthetic-unlock",peer.headers.get()["x-profile-unlock"]); assertEquals(listOf("adult"),lookedUp)
                assertEquals("compatible",RecordingProgressSnapshotObservation.acceptHistory(original,raw.text,requireNotNull(raw.clock),1).data.historyState)
                assertEquals(1,peer.requests.get())
            } finally { close(client) }
        }
    }
    @Test fun default_get_and_post_never_add_history_query() = runBlocking {
        for (write in listOf(false,true)) {
            val now = System.nanoTime(); val original = ProgressTestData.original(now)
            val command = if(write) RecordingProgressCommand.fromStart(ProgressTestData.snapshot(original,clock=ProgressTestData.clock(now),now=now)) else null
            Peer(if(command==null) ProgressTestData.raw(original) else ProgressTestData.result(command)).use { peer ->
                val client=client()
                try { sender(client,peer).send(RecordingProgressRequest(original,1,command)); assertEquals("${if(write) "POST" else "GET"} /proxy/api/live/recordings/synthetic-recording/progress HTTP/1.1",peer.first.get()); assertFalse(peer.body.get().contains("includeHistory")); assertEquals(1,peer.requests.get()) }
                finally { close(client) }
            }
        }
    }
    @Test fun history_http_failure_has_no_second_attempt() = runBlocking {
        for(code in listOf(403,408,503)) Peer("{}",code).use { peer ->
            val client=client(); val original=ProgressTestData.original(System.nanoTime()); val operation=RecordingProgressRequest.history(original,1); val sender=sender(client,peer)
            try { assertTrue(runCatching { sender.send(operation) }.isFailure); assertTrue(runCatching { sender.send(operation) }.isFailure); assertEquals(1,peer.requests.get()) }
            finally { close(client) }
        }
    }
    @Test fun cancel_history_transport_joins_actual_response_cleanup() = runBlocking {
        val original=ProgressTestData.original(System.nanoTime()); val gate=CountDownLatch(1); val finished=CountDownLatch(1)
        Peer(HistoryTestData.raw(original),gate=gate).use { peer ->
            val client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).eventListener(object: EventListener(){
                override fun callEnd(call: Call){finished.countDown()}
                override fun callFailed(call: Call,ioe: IOException){finished.countDown()}
            }).build()
            var pending: Job?=null
            try { val attempt=launch(Dispatchers.IO){sender(client,peer).send(RecordingProgressRequest.history(original,1))};pending=attempt;await(peer.headersSent);attempt.cancelAndJoin();await(finished);assertEquals(0,client.dispatcher.runningCallsCount());assertEquals(1,peer.requests.get()) }
            finally { gate.countDown();pending?.cancelAndJoin();close(client) }
        }
    }
}
