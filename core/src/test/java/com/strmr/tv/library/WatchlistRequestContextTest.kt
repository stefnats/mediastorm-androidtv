package com.strmr.tv.library

import com.strmr.tv.api.*
import com.strmr.tv.home.WatchlistItem
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

/** Actual request constructor/loopback/Call.await are separate from full Android authenticated send. */
class WatchlistRequestContextTest {
    private val target = DetailsTarget("catalog:one/7", "Synthetic", true, year = 2020, tmdbId = "7")
    private fun operation() = CustomListOperations(target = target).begin()!!
    @Test fun `actual raw body preserves server protocol and encoded original delete identity`() {
        val operation = operation(); val add = watchlistRequest(target, true, operation)
        assertEquals("POST", add.method); assertEquals("${ApiClient.profilePath}/watchlist", add.path); assertEquals(operation.owner.epoch, add.expected)
        val body = add.body!!; assertEquals(target.titleId, body.getValue("id").jsonPrimitive.content); assertEquals("movie", body.getValue("mediaType").jsonPrimitive.content)
        assertEquals(2020, body.getValue("year").jsonPrimitive.int); assertEquals("7", body.getValue("externalIds").jsonObject.getValue("tmdb").jsonPrimitive.content)
        assertEquals(setOf("id", "mediaType", "name", "year", "externalIds"), body.keys)
        val remove = watchlistRequest(target, false, operation)
        assertEquals("DELETE", remove.method); assertNull(remove.body); assertEquals("${ApiClient.profilePath}/watchlist/movie/${ApiClient.segment(target.titleId)}", remove.path)
    }
    @Test fun `actual stale Store APIs reject before HTTP or credential access`() = runBlocking {
        val operation = operation(); ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        assertTrue(runCatching { WatchlistStore.add(target, operation) }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { WatchlistStore.setSaved(target, false, operation) }.exceptionOrNull() is CancellationException)
        assertEquals(0, ApiClient.http.dispatcher.runningCallsCount()); assertEquals(0, ApiClient.http.dispatcher.queuedCallsCount())
    }
    @Test fun `actual original request path and body reach synthetic HTTP transport`() = runBlocking {
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor(); val seen = CompletableDeferred<Pair<String,String>>(); val client = OkHttpClient()
            executor.submit { server.accept().use { peer ->
                peer.soTimeout = 3000; val reader = peer.getInputStream().bufferedReader(); val line = reader.readLine(); var size = 0
                while (true) { val header = reader.readLine() ?: break; if (header.isEmpty()) break; if (header.startsWith("Content-Length:", true)) size = header.substringAfter(':').trim().toInt() }
                val body = CharArray(size); var read = 0; while (read < size) { val count = reader.read(body, read, size-read); check(count > 0); read += count }
                seen.complete(line to String(body)); peer.getOutputStream().apply { write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
            } }
            try {
                val operation = operation(); val request = watchlistRequest(target, true, operation)
                val scope = RequestContext({ ProfileDataContext.current }, { ProfileDataContext.invalidate() }).capture(operation.owner.epoch)
                ContextBoundTransport(scope, false, { false }, { error("No recovery") }).execute {
                    val body = request.body.toString().toRequestBody()
                    client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}${request.path}").post(body).build()).execute().use { assertEquals(204, it.code) }
                }
                val received = withTimeout(3000) { seen.await() }; assertEquals("POST ${request.path} HTTP/1.1", received.first); assertEquals(request.body, ApiJson.parseToJsonElement(received.second))
            } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS) }
        }
    }
    @Test fun `original producer cancellation cancels real Call await before blocked sibling completes`() = runBlocking {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")); val arrived = CountDownLatch(1); val finish = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor { Thread(it, "synthetic-watchlist").apply { isDaemon = true } }; val client = OkHttpClient()
        executor.submit { try { server.accept().use { peer ->
            peer.soTimeout = 3000; val reader = peer.getInputStream().bufferedReader(); reader.readLine()
            while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
            arrived.countDown(); check(finish.await(3, TimeUnit.SECONDS)); peer.getOutputStream().write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
        } } catch (_: java.io.IOException) { } }
        val producer = Job(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val original = CoroutineScope(Dispatchers.IO + producer)
        val sibling = original.launch { withContext(NonCancellable) { entered.complete(Unit); release.await() } }; val host = CoroutineScope(Dispatchers.IO + SupervisorJob())
        var call: okhttp3.Call? = null; var reads = 0; var published = 0
        val access = object : WatchlistAccess {
            override suspend fun write(target: DetailsTarget, saved: Boolean, operation: CustomListOperation) {
                val request = watchlistRequest(target, saved, operation)
                call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}${request.path}").build())
                call!!.await()
            }
            override suspend fun read(expectedContext: Long): List<WatchlistItem> { reads++; return emptyList() }
        }
        try {
            withTimeout(3000) { entered.await() }; val origin = TitleListOrigin(target, ProfileDataContext.current, producer)
            val action = TitleListAction(origin, host) { op -> WatchlistStore.setSavedOwned(target, true, op, access); op.publish { published++ } }
            val task = action.commit(action.accept()!!) { origin.dismiss {} }!!
            check(arrived.await(3, TimeUnit.SECONDS)); producer.cancel(); withTimeout(3000) { task.join() }
            assertTrue(call!!.isCanceled()); assertFalse(producer.isCompleted); assertEquals(0, reads); assertEquals(0, published)
        } finally {
            finish.countDown(); release.complete(Unit); sibling.join(); producer.cancel(); host.cancel(); server.close()
            client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS); WatchlistStore.reset()
        }
    }
    private fun String.toRequestBody() = okhttp3.RequestBody.create(null, this)
}
