package com.strmr.tv.ui.details

import com.strmr.tv.api.*
import com.strmr.tv.library.*
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class ContentPreferenceRequestContextTest {
    private val target = DetailsTarget("tmdb:movie:7", "Synthetic", true)
    @Test fun `existing actual source preference APIs reject stale original epoch before Android IO`() = runBlocking {
        val expected = ProfileDataContext.current
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        assertTrue(runCatching { ApiClient.contentLocalPreference(target.titleId, expected) }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { ApiClient.saveContentLocalPreference(target.titleId, "movie", null, expected) }.exceptionOrNull() is CancellationException)
    }
    @Test fun `actual editor original producer cancellation reaches Call await before sibling completion`() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val arrived = CountDownLatch(1); val response = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            executor.submit {
                try {
                    server.accept().use { peer ->
                        peer.soTimeout = 3000
                        val reader = peer.getInputStream().bufferedReader(); reader.readLine()
                        while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
                        peer.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
                        arrived.countDown(); check(response.await(3, TimeUnit.SECONDS))
                        peer.getOutputStream().apply { write("{}".toByteArray()); flush() }
                    }
                } catch (_: IOException) { }
            }
            val parent = Job(); val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val sibling = scope.launch(parent) { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
            val origin = TitleListOrigin(target, ProfileDataContext.current, parent)
            var request: ContentLocalRequest? = null
            val action = TitleListAction(origin, scope) { op -> request = ContentLocalRequest.capture(target, contentLocalScopes(target), op, origin, { request }) { true } }
            val client = OkHttpClient(); val call = AtomicReference<okhttp3.Call>(); val cancelled = CompletableDeferred<Unit>()
            var saved = 0
            var editor: ContentLocalPreferences? = null
            try {
                withTimeout(3000) { entered.await() }
                action.commit(action.accept()!!) { origin.close() }!!.join()
                val original = requireNotNull(request)
                editor = ContentLocalPreferences(scope, original.scopes, ProfileDataContext.changes,
                    { _, expected ->
                        assertEquals(original.context, expected)
                        val actual = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/source-preference").build())
                        call.set(actual)
                        try { actual.await() } finally { cancelled.complete(Unit) }
                        ContentLocalPreference(null, true)
                    }, { _, _, _ -> error("No write") }, { saved++ }, original.context, original)
                editor.load()
                check(arrived.await(3, TimeUnit.SECONDS)); parent.cancel()
                withTimeout(3000) { cancelled.await() }
                assertTrue(call.get().isCanceled()); assertFalse(parent.isCompleted)
                assertFalse(editor.state.value.supported); assertEquals(0, saved)
            } finally {
                response.countDown(); release.complete(Unit); sibling.join(); parent.cancel(); editor?.close(); scope.cancel()
                executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS)
                client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
            }
        }
    }
}
