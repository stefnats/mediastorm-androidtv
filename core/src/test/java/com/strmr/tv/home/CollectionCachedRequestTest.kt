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

class CollectionCachedRequestTest {
    private fun list(parent: Job) = BrowseList.CollectionCached.open(CollectionRef(77, "Collection"), ProfileDataContext.current, parent) { true }!!
    private fun url(request: CachedCollectionRequest): String {
        val uri = ("http://127.0.0.1" + request.path).toHttpUrl().newBuilder().apply { request.query.forEach { (k,v) -> addQueryParameter(k,v) } }.build()
        return uri.encodedPath + "?" + uri.encodedQuery
    }
    @Test fun `actual cache collection query exports full server selections without unsupported enrichment`() = runTest {
        val list = list(backgroundScope.coroutineContext[Job]!!)
        fun build(options: BrowseOptions, limit: Int, offset: Int = 0) = url(cachedCollectionRequest("profile-a", list, limit, offset, options))
        val selected = BrowseOptions(sort = BrowseSort.NAME, ascending = true, originalLanguages = setOf("fra", "nob"))
        val values = linkedMapOf("unfiltered" to build(BrowseOptions(), 2), "continuation" to build(BrowseOptions(), 2, 2),
            "french" to build(selected.copy(originalLanguages = setOf("fra")), 2),
            "unknown" to build(BrowseOptions(includeUnknownOriginalLanguage = true), 2),
            "or-unknown" to build(selected.copy(originalLanguages = setOf("fra"), includeUnknownOriginalLanguage = true), 2),
            "random-pool" to build(selected, 0))
        val bytes = buildJsonObject { values.forEach { (k,v) -> put(k,v) } }.toString().toByteArray()
        assertTrue(values.values.all { "collectionId=77" in it && "source=collection-cached" in it })
        assertTrue(values.values.none { "shelfPhase=" in it || "includeFacets=" in it || "watchStatus=" in it })
        System.getProperty("sourceFilterContractsDir")?.let { dir ->
            val target = File(dir).apply { mkdirs() }; File(target,"native-collection-queries.json").writeBytes(bytes)
            File(target,"native-collection-queries.sha256").writeText(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } + "\n")
        }
    }
    @Test fun `unsupported options are rejected before any legacy metadata request`() = runTest {
        val list = list(backgroundScope.coroutineContext[Job]!!)
        for (options in listOf(BrowseOptions(sort = BrowseSort.ADDED), BrowseOptions(sort = BrowseSort.RATING), BrowseOptions(watch = WatchFilter.WATCHED), BrowseOptions(genres = setOf("Drama")), BrowseOptions(ratingSource = RatingSource.TMDB))) assertTrue(runCatching { cachedCollectionRequest("p", list, 1, 0, options) }.isFailure)
        assertTrue(runCatching { cachedCollectionRequest("p", list, 1, 0, BrowseOptions(), mapOf("source" to "collection")) }.isFailure)
        assertEquals(listOf(BrowseSort.DEFAULT, BrowseSort.NAME, BrowseSort.YEAR), list.sorts)
    }
    @Test fun `actual stale API rejects the original entry before Android transport`() = runTest {
        val list = list(backgroundScope.coroutineContext[Job]!!); ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        assertTrue(runCatching { ApiClient.browsePage(list, 2, expectedContext = list.originalContext) }.exceptionOrNull() is CancellationException)
    }
    @Test fun `production query and original context roundtrip through synthetic HTTP without enrichment`() = runBlocking {
        val expected = ProfileDataContext.current
        val request = cachedCollectionRequest("profile-a", list(coroutineContext[Job]!!), 2, 0,
            BrowseOptions(sort = BrowseSort.NAME, ascending = true, originalLanguages = setOf("fra")))
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val seen = CompletableDeferred<String>()
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            val client = okhttp3.OkHttpClient()
            val body = CollectionFixture.bytes("late-french")
            executor.submit {
                server.accept().use { peer ->
                    peer.soTimeout = 3000
                    val reader = peer.getInputStream().bufferedReader()
                    val first = reader.readLine()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
                    seen.complete(first)
                    peer.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
            try {
                val stamp = RequestContext({ ProfileDataContext.current }, { ProfileDataContext.invalidate() }).capture(expected)
                val reply = ContextBoundTransport(stamp, false, { false }, { error("No replacement recovery") }).execute {
                    client.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:${server.localPort}${url(request)}").build())
                        .execute().use { assertEquals(200, it.code); it.body!!.string() }
                }
                assertEquals("GET ${url(request)} HTTP/1.1", withTimeout(3000) { seen.await() })
                val page = decodeCachedCollectionPage(reply, 77, 2, now = CollectionFixture.now)
                assertEquals(1, page.total)
                assertEquals("tmdb:movie:651", (page.entries.single() as BrowseEntry.TitleEntry).title.id)
            } finally {
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
                executor.shutdownNow()
                executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

}
