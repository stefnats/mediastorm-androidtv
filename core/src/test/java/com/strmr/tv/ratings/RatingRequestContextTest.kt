package com.strmr.tv.ratings

import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.await
import com.strmr.tv.library.ProfileDataContext
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

class RatingRequestContextTest {
    private val target = DetailsTarget("tmdb:movie:7", "Synthetic", true)
    @Test fun `actual stale server gateway rejects original A B A before Android transport`() = runBlocking {
        val gateway = ServerTitleRatingGateway()
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        assertFalse(gateway.isCurrent())
        assertTrue(runCatching { gateway.read(target) }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { gateway.write(target, PersonalRating.LOVE) }.exceptionOrNull() is CancellationException)
    }
    @Test fun `actual editor child cancels Call await before an unrelated producer sibling completes`() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val arrived = CountDownLatch(1); val response = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            executor.submit {
                try {
                    server.accept().use { peer ->
                        peer.soTimeout = 3000
                        val reader = peer.getInputStream().bufferedReader(); reader.readLine()
                        while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
                        peer.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 17\r\nConnection: close\r\n\r\n".toByteArray()); flush()
                        }
                        arrived.countDown(); check(response.await(3, TimeUnit.SECONDS))
                        peer.getOutputStream().apply { write("{\"rating\":\"none\"}".toByteArray()); flush() }
                    }
                } catch (_: IOException) { }
            }
            val producer = Job(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val scope = CoroutineScope(Dispatchers.IO + producer)
            val sibling = scope.launch { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
            val client = OkHttpClient(); var call: okhttp3.Call? = null
            val gateway = object : TitleRatingGateway {
                override fun isCurrent() = true
                override suspend fun read(target: DetailsTarget): TitleRatingReply {
                    call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/rating").build())
                    call!!.await()
                    return TitleRatingReply("none")
                }
                override suspend fun write(target: DetailsTarget, rating: PersonalRating): TitleRatingReply = error("No write")
            }
            val editor = TitleRatingEditor(target, gateway, parent = producer)
            try {
                withTimeout(3000) { entered.await() }
                val pending = async(Dispatchers.IO + NonCancellable) { runCatching { editor.load() }.exceptionOrNull() }
                check(arrived.await(3, TimeUnit.SECONDS)); producer.cancel()
                assertTrue(withTimeout(3000) { pending.await() } is CancellationException)
                assertTrue(call!!.isCanceled()); assertFalse(producer.isCompleted)
                assertNull(editor.state.value.rating); assertFalse(editor.isCurrent())
            } finally {
                response.countDown(); release.complete(Unit); sibling.join(); producer.cancel(); editor.close()
                executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS)
                client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
            }
        }
    }
}
