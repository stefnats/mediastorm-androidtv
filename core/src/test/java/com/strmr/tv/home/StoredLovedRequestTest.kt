package com.strmr.tv.home

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

/** Real builder/transport port, not Android authenticated send or a network-recorded fixture. */
class StoredLovedRequestTest {
    private fun relative(request: StoredLovedRequest): String {
        val url = ("http://127.0.0.1" + request.path).toHttpUrl().newBuilder().apply { request.query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return url.encodedPath + "?" + url.encodedQuery
    }
    @Test fun `six actual builder requests export immutable server selections for Go reverse test`() {
        val selected = BrowseOptions(sort = BrowseSort.NAME, ascending = true, originalLanguages = setOf("fra", "nob"))
        fun request(options: BrowseOptions, limit: Int, offset: Int = 0) = relative(storedLovedRequest("profile-a", "synthetic-client", limit, offset, options))
        val urls = linkedMapOf(
            "selected" to request(selected, 1), "continuation" to request(selected, 1, 1),
            "unknown-only" to request(selected.copy(originalLanguages = emptySet(), includeUnknownOriginalLanguage = true), 50),
            "rare" to request(selected.copy(originalLanguages = setOf("haw")), 50),
            "random-pool" to request(selected, 0), "explicit-rate-refresh" to request(selected, 1, 1),
        )
        assertEquals(urls["continuation"], urls["explicit-rate-refresh"])
        assertTrue(urls.getValue("selected").contains("originalLanguages=fra%2Cnob"))
        assertFalse(urls.getValue("unknown-only").contains("originalLanguages="))
        val output = buildJsonObject { urls.forEach { (name, url) -> put(name, url) } }.toString().toByteArray()
        System.getProperty("sourceFilterContractsDir")?.let { dir ->
            val path = File(dir).apply { mkdirs() }; File(path, "native-loved-stored-queries.json").writeBytes(output)
            File(path, "native-loved-stored-queries.sha256").writeText(MessageDigest.getInstance("SHA-256").digest(output).joinToString("") { "%02x".format(it) } + "\n")
        }
    }
    @Test fun `only supported stored options are sent and legacy loved options stay separate`() {
        val request = storedLovedRequest("profile-a", "synthetic-client", 1, 1, BrowseOptions(sort = BrowseSort.NAME, ascending = true, kind = BrowseKind.MOVIES, letter = "A", originalLanguages = setOf("fre"), includeUnknownOriginalLanguage = true))
        assertEquals("loved-stored", request.query["source"]); assertEquals("fra", request.query["originalLanguages"]); assertEquals("synthetic-client", request.headers["X-Client-ID"])
        assertEquals("movie", request.query["filterMediaType"]); assertEquals("A", request.query["alphabet"])
        assertTrue(request.query.keys.none { it in setOf("watchStatus", "includeFacets", "genres", "runtime", "ratingSource", "includeUnwatchedCounts") })
        for (options in listOf(BrowseOptions(watch = WatchFilter.WATCHED), BrowseOptions(genres = setOf("Drama")), BrowseOptions(sort = BrowseSort.RATING), BrowseOptions(ratingSource = RatingSource.TMDB), BrowseOptions(originalLanguages = setOf("zzz")))) assertTrue(runCatching { storedLovedRequest("p", "c", 1, 0, options) }.isFailure)
        assertTrue(runCatching { storedLovedRequest("p", "c", 1, 0, BrowseOptions(), mapOf("source" to "loved")) }.isFailure)
        assertEquals(mapOf("source" to "loved"), com.strmr.tv.ui.lists.PersonalArea.loved.query)
    }
    @Test fun `actual stale API rejects before Android state credentials or HTTP`() = runTest {
        val old = ProfileDataContext.current; ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        assertTrue(runCatching { ApiClient.browsePage(BrowseList.LovedStored, 1, expectedContext = old) }.exceptionOrNull() is CancellationException)
    }
    @Test fun `actual request constructor sends exact client and query through synthetic transport`() = runBlocking {
        val request = storedLovedRequest("profile-a", "synthetic-client", 1, 1, BrowseOptions(sort = BrowseSort.NAME, ascending = true, originalLanguages = setOf("fra", "nob")))
        java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val received = CompletableDeferred<List<String>>(); val executor = java.util.concurrent.Executors.newSingleThreadExecutor(); val client = okhttp3.OkHttpClient()
            executor.submit {
                server.accept().use { peer ->
                    peer.soTimeout = 3000; val reader = peer.getInputStream().bufferedReader(); val lines = mutableListOf<String>()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; lines += line }
                    received.complete(lines)
                    peer.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray()); flush() }
                }
            }
            try {
                val stamp = RequestContext({ ProfileDataContext.current }, { ProfileDataContext.invalidate() }).capture()
                ContextBoundTransport(stamp, false, { false }, { error("No recovery") }).execute {
                    val raw = okhttp3.Request.Builder().url("http://127.0.0.1:${server.localPort}" + relative(request)).apply { request.headers.forEach { (k, v) -> header(k, v) } }.build()
                    client.newCall(raw).execute().use { assertEquals(200, it.code) }
                }
                val lines = withTimeout(3000) { received.await() }
                assertEquals("GET ${relative(request)} HTTP/1.1", lines.first()); assertTrue(lines.any { it == "X-Client-ID: synthetic-client" })
            } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); executor.shutdownNow(); executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS) }
        }
    }
    @Test fun `accepted original producer cancels real row renewal Call before blocked sibling completes`() = runBlocking {
        val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val arrived = java.util.concurrent.CountDownLatch(1); val finish = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "synthetic-stored-rate").apply { isDaemon = true } }
        val client = okhttp3.OkHttpClient()
        executor.submit { try { server.accept().use { peer ->
            peer.soTimeout = 3000; val reader = peer.getInputStream().bufferedReader(); reader.readLine()
            while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
            arrived.countDown(); check(finish.await(3, java.util.concurrent.TimeUnit.SECONDS))
            peer.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
        } } catch (_: java.io.IOException) { } }
        val parent = Job(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val original = CoroutineScope(Dispatchers.IO + parent); val host = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val sibling = original.launch { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
        var call: okhttp3.Call? = null; var opens = 0; var reads = 0
        val page = decodeStoredLovedPage(requireNotNull(javaClass.getResourceAsStream("/loved-stored-go/loved-stored-selected.json")).bufferedReader().use { it.readText() }, 1)
        try {
            withTimeout(3000) { entered.await() }
            val model = com.strmr.tv.ui.lists.ListGridModel(BrowseList.LovedStored, emptyMap(), host,
                clientIdentity = { "synthetic-client" }, openRating = { opens++ }, pageSize = 1,
                readPage = { _, limit, offset, _, options, expected ->
                    if (reads++ > 0) {
                        val request = storedLovedRequest("profile-a", "synthetic-client", limit, offset, options)
                        val stamp = RequestContext({ ProfileDataContext.current }, { ProfileDataContext.invalidate() }).capture(expected)
                        ContextBoundTransport(stamp, false, { false }, { error("No recovery") }).execute {
                            call = client.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:${server.localPort}" + relative(request)).apply { request.headers.forEach { (k, v) -> header(k, v) } }.build())
                            call!!.await().also { assertEquals(200, it.code) }
                        }
                    }
                    page
                })
            model.reload(); val card = model.cards.single()
            val selection = com.strmr.tv.library.TitleMenuSelection(card, model.dataContext, parent)
            val action = com.strmr.tv.library.TitleListAction(selection.origin, host) { card.reloadStoredRating!!(card.target!!, it) }
            val task = action.commit(action.accept()!!) { selection.close() }!!
            check(arrived.await(3, java.util.concurrent.TimeUnit.SECONDS)); parent.cancel(); withTimeout(3000) { task.join() }
            assertTrue(call!!.isCanceled()); assertFalse(parent.isCompleted); assertEquals(0, opens); assertEquals(2, reads)
        } finally {
            finish.countDown(); release.complete(Unit); sibling.join(); parent.cancel(); host.cancel(); server.close()
            client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); executor.shutdownNow(); executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

}
