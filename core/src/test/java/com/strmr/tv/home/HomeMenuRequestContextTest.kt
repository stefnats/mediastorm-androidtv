package com.strmr.tv.home

import com.strmr.tv.api.*
import com.strmr.tv.library.*
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

/** Actual menu adapter and history route port over loopback; not authenticated Android send. */
class HomeMenuRequestContextTest {
    private val target = DetailsTarget("tmdb:movie:7", "Synthetic", true)
    @Test fun `actual history path retains original profile and stale APIs reject before transport`() = runTest {
        val expected = ProfileDataContext.current
        val path = detailsMenuHistoryPath(expected)
        assertEquals(ApiClient.inContext(expected) { "${ApiClient.profilePath}/history/continue" }, path)
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        for (read in listOf<suspend () -> Unit>(
            { detailsMenuHistoryPath(expected) },
            { ApiClient.customLists(expected) },
            { ApiClient.customListItems("aside", expected) },
        )) assertTrue(runCatching { read() }.exceptionOrNull() is CancellationException)
    }
    @Test fun `actual menu adapter history constructor and transport deliver one original request`() = runBlocking {
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            val seen = CompletableDeferred<String>(); val executor = Executors.newSingleThreadExecutor(); val client = OkHttpClient()
            executor.submit { server.accept().use { peer ->
                val reader = peer.getInputStream().bufferedReader(); seen.complete(reader.readLine())
                while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
                peer.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n[]".toByteArray())
            } }
            val parent = Job(); val origin = TitleListOrigin(target, ProfileDataContext.current, parent); var result: DetailsMenuSnapshot? = null
            try {
                readDetailsMenuSnapshot(target, origin, object : DetailsMenuReads {
                    override suspend fun lists(expected: Long) = emptyList<CustomList>()
                    override suspend fun items(id: String, expected: Long): List<WatchlistItem> = error("No reserved list")
                    override suspend fun continuing(expected: Long): List<ContinueWatchingItem> {
                        val path = detailsMenuHistoryPath(expected)
                        val stamp = RequestContext({ ProfileDataContext.current }, { ProfileDataContext.invalidate() }).capture(expected)
                        ContextBoundTransport(stamp, false, { false }, { error("No replacement recovery") }).execute {
                            client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}$path").build()).await().also { assertEquals(200, it.code) }
                        }
                        return listOf(ContinueWatchingItem(target.titleId, target.name))
                    }
                }) { result = it }
                assertEquals("GET ${detailsMenuHistoryPath(origin.context)} HTTP/1.1", withTimeout(3000) { seen.await() })
                assertEquals(DetailsMenuSnapshot(inContinueWatching = true, loaded = true), result)
            } finally { parent.cancel(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS) }
        }
    }
    @Test fun `original producer cancels actual history Call before blocked sibling completes`() = runBlocking {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")); val arrived = CountDownLatch(1); val finish = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor { Thread(it, "synthetic-details-menu").apply { isDaemon = true } }; val client = OkHttpClient()
        executor.submit { try { server.accept().use { peer ->
            peer.soTimeout = 3000; val reader = peer.getInputStream().bufferedReader(); reader.readLine()
            while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
            arrived.countDown(); check(finish.await(3, TimeUnit.SECONDS)); peer.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n[]".toByteArray())
        } } catch (_: java.io.IOException) { } }
        val parent = Job(); val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val original = CoroutineScope(Dispatchers.IO + parent); val host = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val sibling = original.launch { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
        var call: okhttp3.Call? = null; var published = 0
        try {
            withTimeout(3000) { entered.await() }; val origin = TitleListOrigin(target, ProfileDataContext.current, parent)
            val task = host.launch {
                readDetailsMenuSnapshot(target, origin, object : DetailsMenuReads {
                    override suspend fun lists(expected: Long) = listOf(CustomList("aside", "Set Aside"))
                    override suspend fun items(id: String, expected: Long) = emptyList<WatchlistItem>()
                    override suspend fun continuing(expected: Long): List<ContinueWatchingItem> {
                        call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}${detailsMenuHistoryPath(expected)}").build())
                        call!!.await(); return emptyList()
                    }
                }) { published++ }
            }
            check(arrived.await(3, TimeUnit.SECONDS)); parent.cancel(); withTimeout(3000) { task.join() }
            assertTrue(call!!.isCanceled()); assertFalse(parent.isCompleted); assertEquals(0, published)
        } finally {
            finish.countDown(); release.complete(Unit); sibling.join(); parent.cancel(); host.cancel(); server.close()
            client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS)
        }
    }
}
