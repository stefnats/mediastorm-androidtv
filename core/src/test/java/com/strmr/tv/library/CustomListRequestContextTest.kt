package com.strmr.tv.library

import com.strmr.tv.api.*
import com.strmr.tv.home.*
import com.strmr.tv.player.PlaybackSessionContext
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Actual request construction + synthetic HTTP; successful Android ApiClient.send is not exercised. */
class CustomListRequestContextTest {
    private class Peer(private val bodyGate: CountDownLatch? = null) : AutoCloseable {
        val arrived = CountDownLatch(1)
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "synthetic-custom-lists").apply { isDaemon = true } }
        val requests = CopyOnWriteArrayList<String>()
        val base = "http://127.0.0.1:${socket.localPort}/".toHttpUrl()
        init { worker.submit { while (!socket.isClosed) {
            val connection = try { socket.accept() } catch (_: IOException) { break }
            runCatching { connection.use { client ->
                client.soTimeout = 1000; val reader = client.getInputStream().bufferedReader()
                val first = reader.readLine() ?: return@use
                while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
                requests += first
                client.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n".toByteArray()); flush()
                    arrived.countDown()
                    bodyGate?.let { check(it.await(3, TimeUnit.SECONDS)) }
                    write("{}".toByteArray()); flush()
                }
            } }
        } } }
        override fun close() { socket.close(); worker.shutdownNow(); worker.awaitTermination(2, TimeUnit.SECONDS) }
    }
    @Before fun before() { ProfileDataContext.invalidate(); CustomListsStore.reset() }
    @After fun after() { ProfileDataContext.invalidate(); CustomListsStore.reset() }
    private suspend fun cancelled(action: suspend () -> Unit) { try { action(); fail("Old operation accepted") } catch (_: CancellationException) { } }
    @Test fun `actual owned constructor retains selected profile encoded list and body`() {
        val receipt = CustomListOperations().begin("one/a+b")!!; val body = buildJsonObject { put("name", "Synthetic \"new\" list") }
        val request = customListRequest("PATCH", "/${ApiClient.segment("one/a+b")}", body, receipt.owner, receipt)
        assertEquals("PATCH", request.method); assertEquals("/api/users/${ApiClient.profileId}/custom-lists/one%2Fa%2Bb", request.path)
        assertEquals(receipt.owner.epoch, request.expected); assertSame(body, request.body)
    }
    @Test fun `actual helper reaches synthetic HTTP with original path and method`() = runBlocking {
        Peer().use { peer ->
            val receipt = CustomListOperations().begin("one+a")!!
            val request = customListRequest("DELETE", "/${ApiClient.segment("one+a")}", null, receipt.owner, receipt)
            val client = OkHttpClient()
            try {
                checkedListStage(receipt.owner, receipt) {
                    client.newCall(Request.Builder().url(peer.base.resolve(request.path)!!).method(request.method, null).build()).await()
                }
                assertEquals("DELETE ${request.path} HTTP/1.1", peer.requests.single())
            } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
        }
    }
    @Test fun `actual read wrappers reject stale expected before Android stores or HTTP`() = runBlocking {
        Peer().use { peer ->
            val previous = ApiClient.baseUrl
            try {
                ApiClient.baseUrl = peer.base; val expected = ProfileDataContext.current; ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
                cancelled { ApiClient.customLists(expected) }; cancelled { ApiClient.customListItems("original", expected) }
                assertTrue(peer.requests.isEmpty()); assertEquals(0, ApiClient.http.dispatcher.runningCallsCount())
            } finally { ApiClient.baseUrl = previous }
        }
    }
    @Test fun `all actual Store write APIs reject old dialog before request and refresh`() = runBlocking {
        Peer().use { peer ->
            val previous = ApiClient.baseUrl
            try {
                ApiClient.baseUrl = peer.base
                val target = DetailsTarget("tmdb:movie:1", "Synthetic", true); val item = WatchlistItem(target.titleId)
                val operations = CustomListOperations(target = target); val create = operations.begin()!!; val row = operations.begin("one", item)!!
                ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
                cancelled { CustomListsStore.create("Synthetic", create) }; cancelled { CustomListsStore.createAndAdd("Synthetic", target, create) }
                cancelled { CustomListsStore.rename("one", "Synthetic", row) }; cancelled { CustomListsStore.delete("one", row) }
                cancelled { CustomListsStore.add(target, "one", row) }; cancelled { CustomListsStore.remove(item, "one", operation = row) }
                cancelled { CustomListsStore.refresh(operation = row) }; cancelled { CustomListsStore.memberships(target, operation = row) }
                assertTrue(peer.requests.isEmpty()); assertEquals(0, ApiClient.http.dispatcher.queuedCallsCount())
            } finally { ApiClient.baseUrl = previous }
        }
    }
    @Test fun `same epoch replacement receipt cannot construct request for old dialog`() = runBlocking {
        val operations = CustomListOperations(); val old = operations.begin("one")!!; operations.begin("one")
        cancelled { customListRequest("DELETE", "/one", null, old.owner, old) }
    }
    @Test fun `constructed original request cannot perform demand after A B A retirement`() = runBlocking {
        Peer().use { peer ->
            val receipt = CustomListOperations().begin("one")!!; val request = customListRequest("DELETE", "/one", null, receipt.owner, receipt)
            ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); var sends = 0
            cancelled { checkedListStage(receipt.owner, receipt) { sends++; request.path } }
            assertEquals(0, sends); assertTrue(peer.requests.isEmpty())
        }
    }
    @Test fun `retired owner cannot reconstruct a current profile mutation path`() = runBlocking {
        val owner = PlaybackSessionContext.capture(); ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        cancelled { customListRequest("POST", "", buildJsonObject { put("name", "Synthetic") }, owner, null) }
    }
    @Test fun `actual legacy expected argument also rejects old scope before write`() = runBlocking {
        val expected = ProfileDataContext.current; ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        cancelled { CustomListsStore.create("Synthetic", expectedContext = expected) }
        cancelled { CustomListsStore.rename("one", "Synthetic", expectedContext = expected) }
        cancelled { CustomListsStore.delete("one", expectedContext = expected) }
    }
    @Test fun `closing actual registered list operation cancels real Call await in flight`() = runBlocking {
        val release = CountDownLatch(1)
        Peer(release).use { peer ->
            val operations = CustomListOperations(); val receipt = operations.begin("one")!!
            val request = customListRequest("DELETE", "/one", null, receipt.owner, receipt)
            val client = OkHttpClient(); val call = client.newCall(Request.Builder().url(peer.base.resolve(request.path)!!).delete().build())
            var publications = 0
            try {
                val job = launch(Dispatchers.IO) { operations.run(receipt) { call.await(); it.publish { publications++ } } }
                check(peer.arrived.await(3, TimeUnit.SECONDS))
                operations.close(); job.join()
                assertTrue(call.isCanceled()); assertTrue(job.isCancelled); assertEquals(0, publications)
            } finally { release.countDown(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
        }
    }

}
