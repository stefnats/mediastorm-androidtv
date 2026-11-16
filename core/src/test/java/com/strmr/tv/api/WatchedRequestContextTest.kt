package com.strmr.tv.api

import com.strmr.tv.library.*
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

/** Production request helpers/Call.await over loopback; no Android auth/send or backend service claim. */
class WatchedRequestContextTest {
    private val movie = DetailsTarget("catalog:movie/7", "Synthetic", true, tmdbId = "7")
    private fun selection() = selectMovieWatched(movie, false, CustomListOperations(target = movie).begin()!!)
    @Test fun `actual stale bulk and initial read wrappers stop before HTTP or credential stores`() = runBlocking {
        val selection = selection(); val owner = ProfileDataContext.current; val changed = TitleActions.changes
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        for (action in listOf<suspend () -> Unit>(
            { ApiClient.setWatched(selection) }, { ApiClient.isMovieWatched(movie, owner) },
            { ApiClient.isEpisodeWatched(CardEpisode(1, 2, "Synthetic", "history"), movie, owner) },
            { TitleActions.setWatched(selection) },
        )) assertTrue(runCatching { action() }.exceptionOrNull() is CancellationException)
        assertEquals(changed, TitleActions.changes); assertEquals(0, ApiClient.http.dispatcher.runningCallsCount()); assertEquals(0, ApiClient.http.dispatcher.queuedCallsCount())
    }
    @Test fun `both actual compound request bodies and original profile paths reach synthetic HTTP`() = runBlocking {
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            val seen = CompletableDeferred<List<Pair<String,String>>>(); val executor = Executors.newSingleThreadExecutor(); val client = OkHttpClient()
            executor.submit {
                val rows = mutableListOf<Pair<String,String>>()
                repeat(2) { server.accept().use { peer ->
                    peer.soTimeout = 3000; val reader = peer.getInputStream().bufferedReader(); val line = reader.readLine(); var size = 0
                    while (true) { val h = reader.readLine() ?: break; if (h.isEmpty()) break; if (h.startsWith("Content-Length:", true)) size = h.substringAfter(':').trim().toInt() }
                    val body = CharArray(size); var n = 0; while (n < size) { val c = reader.read(body, n, size - n); check(c > 0); n += c }
                    rows += line to String(body); peer.getOutputStream().apply { write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
                } }; seen.complete(rows)
            }
            try {
                val selected = selection(); val context = RequestContext({ ProfileDataContext.current }, { ProfileDataContext.invalidate() }).capture(selected.operation.owner.epoch)
                performWatched(selected, object : WatchedAccess {
                    override suspend fun send(request: WatchedRequest) {
                        ContextBoundTransport(context, false, { false }, { error("No recovery") }).execute {
                            client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}${request.path}").method(request.method, (request.body ?: request.progressBody).toString().toRequestBody()).build()).await().also { assertEquals(204, it.code) }
                        }
                    }
                })
                val received = withTimeout(3000) { seen.await() }; assertEquals(2, received.size)
                assertEquals("POST ${selected.bulk.path} HTTP/1.1", received[0].first); assertEquals(selected.bulk.body, ApiJson.parseToJsonElement(received[0].second))
                assertEquals("POST ${selected.progress!!.path} HTTP/1.1", received[1].first); assertEquals(selected.progress.progressBody, ApiJson.parseToJsonElement(received[1].second))
            } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS) }
        }
    }
    @Test fun `original producer cancels actual Call await before blocked sibling and before progress followup`() = runBlocking {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")); val arrived = CountDownLatch(1); val finish = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor { Thread(it, "synthetic-watched").apply { isDaemon = true } }; val client = OkHttpClient()
        executor.submit { try { server.accept().use { peer ->
            peer.soTimeout = 3000; val reader = peer.getInputStream().bufferedReader(); reader.readLine()
            while (true) { val h = reader.readLine() ?: break; if (h.isEmpty()) break }
            arrived.countDown(); check(finish.await(3, TimeUnit.SECONDS)); peer.getOutputStream().write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
        } } catch (_: java.io.IOException) { } }
        val parent = Job(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val original = CoroutineScope(Dispatchers.IO + parent)
        val sibling = original.launch { withContext(NonCancellable) { entered.complete(Unit); release.await() } }; val host = CoroutineScope(Dispatchers.IO + SupervisorJob())
        var call: okhttp3.Call? = null; var requests = 0; var success = 0
        try {
            withTimeout(3000) { entered.await() }; val origin = TitleListOrigin(movie, ProfileDataContext.current, parent)
            val intent = movieWatchedIntent(movie, false, origin.context)
            val action = TitleListAction(origin, host) { op -> performWatched(intent.bind(op)!!, object : WatchedAccess {
                override suspend fun send(request: WatchedRequest) {
                    requests++; call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}${request.path}").post((request.body ?: request.progressBody).toString().toRequestBody()).build()); call!!.await()
                }
            }, success = { success++ }) }
            val task = action.commit(action.accept()!!) { origin.dismiss {} }!!
            check(arrived.await(3, TimeUnit.SECONDS)); parent.cancel(); withTimeout(3000) { task.join() }
            assertTrue(call!!.isCanceled()); assertFalse(parent.isCompleted); assertEquals(1, requests); assertEquals(0, success)
        } finally { finish.countDown(); release.complete(Unit); sibling.join(); parent.cancel(); host.cancel(); server.close(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS) }
    }
}
