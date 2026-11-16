package com.strmr.tv.library

import com.strmr.tv.api.*
import com.strmr.tv.home.hideFromContinueWatching
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

/** Real helper/loopback/Call.await and actual stale API boundaries are separate from Android send. */
class HideRequestContextTest {
    private val target = DetailsTarget("tmdb:movie:17", "Synthetic", true)
    private fun operation() = CustomListOperations(target = target).begin()!!
    private suspend fun canceled(read: suspend () -> Unit) { try { read(); fail("Old operation accepted") } catch (_: CancellationException) { } }
    @Test fun `actual stale Hide Unhide and Continue actions stop before credentials or HTTP`() = runBlocking {
        val op = operation(); val previous = TitleActions.changes
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        canceled { TitleActions.hide(target, op) }; canceled { TitleActions.unhide(target, op) }
        canceled { TitleActions.hideFromContinueWatching(target, op) }
        canceled { ApiClient.hideFromContinueWatching(target.titleId, op.owner.epoch) }
        assertEquals(previous, TitleActions.changes)
        assertEquals(0, ApiClient.http.dispatcher.runningCallsCount()); assertEquals(0, ApiClient.http.dispatcher.queuedCallsCount())
    }
    @Test fun `actual request builder retains original profile body and encoded alias`() {
        val op = operation(); val body = buildJsonObject { put("id", target.titleId); put("mediaType", "movie") }
        val hide = hiddenRequest(op, body = body)
        assertEquals("POST", hide.method); assertSame(body, hide.body); assertEquals(op.owner.epoch, hide.expected)
        assertEquals("${ApiClient.profilePath}/hidden-items", hide.path)
        val delete = hiddenRequest(op, HiddenItem("catalog:one/extra", "movie"))
        assertEquals("DELETE", delete.method); assertNull(delete.body)
        assertEquals("${ApiClient.profilePath}/hidden-items/movie/${ApiClient.segment("catalog:one/extra")}", delete.path)
    }
    @Test fun `production request path reaches synthetic HTTP without account credentials`() = runBlocking {
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor(); val seen = CompletableDeferred<String>()
            executor.submit { server.accept().use { peer ->
                peer.soTimeout = 3000; val reader = peer.getInputStream().bufferedReader(); seen.complete(reader.readLine())
                while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
                peer.getOutputStream().apply { write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
            } }
            val client = OkHttpClient()
            try {
                val op = operation(); val request = hiddenRequest(op, HiddenItem("alias:17", "movie"))
                val owner = RequestContext({ ProfileDataContext.current }, { ProfileDataContext.invalidate() }).capture(op.owner.epoch)
                ContextBoundTransport(owner, false, { false }, { error("No recovery") }).execute {
                    client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}${request.path}").delete().build()).execute().use { assertEquals(204, it.code) }
                }
                assertEquals("DELETE ${request.path} HTTP/1.1", withTimeout(3000) { seen.await() })
            } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS) }
        }
    }
    @Test fun `actual original-producer retirement cancels in-flight Hide port Call await before blocked sibling completes`() = runBlocking {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val arrived = CountDownLatch(1); val response = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "synthetic-hide").apply { isDaemon = true } }
        executor.submit {
            try { server.accept().use { peer ->
                peer.soTimeout = 3000; val reader = peer.getInputStream().bufferedReader(); reader.readLine()
                while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
                peer.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
                arrived.countDown(); check(response.await(3, TimeUnit.SECONDS))
                peer.getOutputStream().apply { write("{}".toByteArray()); flush() }
            } } catch (_: IOException) { }
        }
        val producer = Job(); val entered = CompletableDeferred<Unit>(); val releaseSibling = CompletableDeferred<Unit>()
        val originalScope = CoroutineScope(Dispatchers.IO + producer)
        val sibling = originalScope.launch { withContext(NonCancellable) { entered.complete(Unit); releaseSibling.await() } }
        val host = CoroutineScope(Dispatchers.IO + SupervisorJob()); val client = OkHttpClient()
        var call: okhttp3.Call? = null; var navigation = 0; val changes = TitleActions.changes
        val origin = TitleListOrigin(target, ProfileDataContext.current, producer)
        val access = object : HiddenWrites {
            override suspend fun hide(target: DetailsTarget, operation: CustomListOperation): HiddenItem {
                val request = hiddenRequest(operation, body = buildJsonObject { put("id", target.titleId) })
                call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}${request.path}").build())
                call!!.await(); return HiddenItem(target.titleId)
            }
            override suspend fun delete(item: HiddenItem, operation: CustomListOperation) { error("No delete") }
        }
        try {
            withTimeout(3000) { entered.await() }
            val action = TitleListAction(origin, host) { op ->
                if (TitleActions.runOwned(op, "Hidden", "Failed") { HiddenItemsStore.hideOwned(target, op, access) }) op.publish { navigation++ }
            }
            val task = action.commit(action.accept()!!) { origin.dismiss {} }!!
            check(arrived.await(3, TimeUnit.SECONDS)); producer.cancel()
            withTimeout(3000) { task.join() }
            assertTrue(call!!.isCanceled()); assertTrue(task.isCancelled); assertFalse(producer.isCompleted)
            assertEquals(0, navigation); assertEquals(changes, TitleActions.changes)
        } finally {
            response.countDown(); releaseSibling.complete(Unit); sibling.join(); producer.cancel(); host.cancel()
            server.close(); executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS)
            client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); HiddenItemsStore.reset()
        }
    }
}
