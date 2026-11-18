package com.strmr.tv.library

import com.strmr.tv.api.*
import com.strmr.tv.home.*
import com.strmr.tv.ui.lists.refreshPersonalArea
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class ListsTabRawContextTest {
    @Test fun `actual continue decoder preserves artwork resume and next-episode shape`() {
        val raw = """[{"seriesId":"tmdb:series:7","seriesTitle":"Synthetic","posterUrl":"synthetic-poster","nextEpisode":{"seasonNumber":1,"episodeNumber":2},"externalIds":{"imdb":"tt7"}}]"""
        val row = decodeListsTabContinue(raw).single()
        assertEquals("synthetic-poster", row.posterUrl); assertEquals("tt7", row.externalIds!!["imdb"]); assertNotNull(row.nextEpisode)
        assertTrue(decodeListsTabContinue("null").isEmpty()); assertTrue(decodeListsTabContinue("[]").isEmpty())
    }
    @Test fun `strict current continue feed rejects every bad row without lossy drop`() {
        for (raw in listOf("{}", "[null]", "[{}]", "[{\"seriesId\":\"tmdb:movie:7\",\"seriesTitle\":3}]", "[{\"seriesId\":\"tmdb:movie:7\",\"seriesTitle\":\"T\",\"externalIds\":{\"imdb\":7}}]")) assertTrue(runCatching { decodeListsTabContinue(raw) }.isFailure)
    }
    @Test fun `actual strict raw continuing filters aside aliases once and stops after malformed aside`() = runTest {
        val expected = ProfileDataContext.current; val paths = mutableListOf<String>()
        val rows = readListsTabContinue(expected) { path, owner ->
            assertEquals(expected, owner); paths += path
            when {
                path.endsWith("/custom-lists") -> """[{"id":"aside","name":"Set Aside"}]"""
                path.endsWith("/items") -> """[{"id":"tmdb:movie:77","mediaType":"movie","name":"Alias","externalIds":{"imdb":"tt7"}}]"""
                else -> """[{"seriesId":"tmdb:movie:7","seriesTitle":"T","externalIds":{"imdb":"tt7"}}]"""
            }
        }
        assertTrue(rows.isEmpty()); assertEquals(3, paths.size)
        assertTrue(runCatching { readListsTabContinue(expected) { path, _ -> if (path.endsWith("/custom-lists")) "[{}]" else "[]" } }.isFailure)
    }
    @Test fun `strict library decoder accepts actual array shape and rejects malformed rows`() {
        assertEquals("local", decodeOwnedLibraries("""[{"id":"local","name":"Local","type":"movie","sourceType":"local"}]""").single().id)
        assertTrue(decodeOwnedLibraries("[]").isEmpty())
        for (raw in listOf("null", "{}", "[{}]", "[{\"id\":\"local\",\"name\":3}]", "[{\"id\":\"local\",\"name\":\"L\"},null]")) assertTrue(runCatching { decodeOwnedLibraries(raw) }.isFailure)
    }
    @Test fun `actual library query and stale APIs retain original profile before Android IO`() = runTest {
        val expected = ProfileDataContext.current
        assertEquals(ApiClient.inContext(expected) { ApiClient.profileId }, ApiClient.librariesQuery(expected)["profileId"])
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        for (read in listOf<suspend () -> Unit>(
            { ApiClient.libraries(expected) }, { ApiClient.libraries(expected, strict = true) },
            { WatchlistStore.refresh(expected) }, { refreshPersonalArea(expected) },
        )) assertTrue(runCatching { read() }.exceptionOrNull() is CancellationException)
    }
    @Test fun `actual library query constructor roundtrips captured selector through synthetic transport`() = runBlocking {
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            val seen = CompletableDeferred<String>(); val executor = Executors.newSingleThreadExecutor(); val client = OkHttpClient()
            executor.submit { server.accept().use { peer ->
                val reader = peer.getInputStream().bufferedReader(); seen.complete(reader.readLine())
                while (true) { val h = reader.readLine() ?: break; if (h.isEmpty()) break }
                peer.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n[]".toByteArray())
            } }
            try {
                val expected = ProfileDataContext.current; val query = ApiClient.librariesQuery(expected)
                val url = okhttp3.HttpUrl.Builder().scheme("http").host("127.0.0.1").port(server.localPort).addPathSegments("api/library/libraries").apply { query.forEach { (k,v) -> addQueryParameter(k,v) } }.build()
                val stamp = RequestContext({ ProfileDataContext.current }, { ProfileDataContext.invalidate() }).capture(expected)
                ContextBoundTransport(stamp, false, { false }, { error("No recovery") }).execute {
                    client.newCall(Request.Builder().url(url).build()).await().also { assertEquals(200, it.code) }
                }
                assertEquals("GET ${url.encodedPath}?${url.encodedQuery} HTTP/1.1", withTimeout(3000) { seen.await() })
            } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS) }
        }
    }
    @Test fun `actual stale library watchlist and personal area APIs stop before Android transport`() = runTest {
        val original = ProfileDataContext.current; ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        for (read in listOf<suspend () -> Unit>(
            { ApiClient.libraries(expectedContext = original, strict = true) },
            { WatchlistStore.refresh(original) },
            { refreshPersonalArea(original) },
        )) assertTrue(runCatching { read() }.exceptionOrNull() is CancellationException)
    }
    @Test fun `actual UI refresh and Store cancel library Call before original sibling finishes`() = runBlocking {
        ListsTabStore.reset()
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val arrived = java.util.concurrent.CountDownLatch(1); val finish = java.util.concurrent.CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor { Thread(it, "synthetic-lists-tab").apply { isDaemon = true } }; val client = OkHttpClient()
        executor.submit { try { server.accept().use { peer ->
            peer.soTimeout = 3000; val reader = peer.getInputStream().bufferedReader(); reader.readLine()
            while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
            arrived.countDown(); check(finish.await(3, TimeUnit.SECONDS)); peer.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n[]".toByteArray())
        } } catch (_: java.io.IOException) { } }
        val parent = Job(); val entered = CompletableDeferred<Unit>(); val hold = CompletableDeferred<Unit>()
        val original = CoroutineScope(Dispatchers.IO + parent); val host = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val sibling = original.launch { withContext(NonCancellable) { entered.complete(Unit); hold.await() } }
        val refresh = com.strmr.tv.ui.lists.ProfileListRefresh(ProfileDataContext.current, parent); var call: okhttp3.Call? = null
        try {
            withTimeout(3000) { entered.await() }
            val task = host.launch { refresh.refresh { expected -> ListsTabStore.refresh(expected, object : ListsTabReads {
                override suspend fun shelves(expected: Long) = emptyList<ShelfConfig>()
                override suspend fun continuing(expected: Long) = emptyList<ContinueWatchingItem>()
                override suspend fun libraries(expected: Long): List<LibraryInfo> {
                    val query = ApiClient.librariesQuery(expected)
                    val url = okhttp3.HttpUrl.Builder().scheme("http").host("127.0.0.1").port(server.localPort).addPathSegments("api/library/libraries").apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
                    call = client.newCall(Request.Builder().url(url).build()); val response = call!!.await()
                    return decodeOwnedLibraries(response.text)
                }
            }) } }
            check(arrived.await(3, TimeUnit.SECONDS)); parent.cancel(); withTimeout(3000) { task.join() }
            assertTrue(call!!.isCanceled()); assertFalse(parent.isCompleted); assertFalse(ListsTabStore.isLoaded)
            assertNull(ListsTabStore.loadError); assertFalse(refresh.failed); assertFalse(refresh.refreshing)
        } finally {
            finish.countDown(); hold.complete(Unit); sibling.join(); parent.cancel(); host.cancel(); refresh.close(); ListsTabStore.reset(); server.close()
            client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS)
        }
    }

}
