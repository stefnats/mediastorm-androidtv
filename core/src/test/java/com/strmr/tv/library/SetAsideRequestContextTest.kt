package com.strmr.tv.library

import com.strmr.tv.api.*
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

/** Actual stale APIs + actual accepted-child/Call.await cancellation; no successful Android send claim. */
class SetAsideRequestContextTest {
    private val target = DetailsTarget("tmdb:movie:17", "Synthetic", true)
    private suspend fun cancelled(action: suspend () -> Unit) { try { action(); fail("Old context accepted") } catch (_: CancellationException) { } }
    @Test fun `actual stale Set Aside and picker APIs stop before Android stores network or publication`() = runBlocking {
        val operation = CustomListOperations(target = target).begin()!!
        val expected = operation.owner.epoch; val previous = TitleActions.changes
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        cancelled { SetAsideStore.add(target, operation) }; cancelled { SetAsideStore.remove(target, operation) }
        cancelled { TitleActions.setAside(target, operation) }; cancelled { TitleActions.removeFromSetAside(target, operation) }
        cancelled { SetAsideStore.refresh(expected) }; cancelled { CustomListsStore.openPicking(target, operation) }
        assertEquals(previous, TitleActions.changes); assertEquals(0, ApiClient.http.dispatcher.runningCallsCount()); assertEquals(0, ApiClient.http.dispatcher.queuedCallsCount())
    }
    @Test fun `original producer cancels actual accepted HTTP before blocking sibling completes`() = runBlocking {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val arrived = CountDownLatch(1); val response = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "synthetic-set-aside").apply { isDaemon = true } }
        executor.submit {
            try { server.accept().use { peer ->
                peer.soTimeout = 3000; val reader = peer.getInputStream().bufferedReader(); reader.readLine()
                while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
                peer.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
                arrived.countDown(); check(response.await(3, TimeUnit.SECONDS))
                peer.getOutputStream().apply { write("{}".toByteArray()); flush() }
            } } catch (_: IOException) { }
        }
        val parent = Job(); val blocked = CompletableDeferred<Unit>(); val siblingEntered = CompletableDeferred<Unit>()
        val producer = CoroutineScope(Dispatchers.IO + parent)
        val sibling = producer.launch { withContext(NonCancellable) { siblingEntered.complete(Unit); blocked.await() } }
        val host = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val origin = TitleListOrigin(target, ProfileDataContext.current, parent)
        val client = OkHttpClient()
        var call: okhttp3.Call? = null; var publications = 0
        try {
            withTimeout(3000) { siblingEntered.await() }
            val job = origin.accept(host)!!.commit({ origin.close() }) { operation ->
                val request = customListRequest("GET", "", null, operation.owner, operation)
                call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}${request.path}").build())
                call!!.await(); operation.publish { publications++ }
            }!!
            check(arrived.await(3, TimeUnit.SECONDS)); parent.cancel()
            withTimeout(3000) { job.join() }
            assertTrue(call!!.isCanceled()); assertTrue(job.isCancelled); assertFalse(parent.isCompleted); assertEquals(0, publications)
        } finally {
            response.countDown(); blocked.complete(Unit); sibling.join(); parent.cancel(); host.cancel()
            server.close(); executor.shutdownNow(); executor.awaitTermination(2, TimeUnit.SECONDS)
            client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
        }
    }
}
