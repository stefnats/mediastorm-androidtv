package com.strmr.tv.home

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

/** Actual production query builders. Synthetic profile is an explicit builder input, not activated Android state. */
@OptIn(ExperimentalCoroutinesApi::class)
class ManagementLanguageRequestTest {
    private fun url(path: String, query: Map<String, String>): String {
        val url = ("http://127.0.0.1" + path).toHttpUrl().newBuilder().apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        return url.encodedPath + "?" + url.encodedQuery
    }
    @Test fun `six actual production query URLs export for independent real Go parser verification`() {
        fun hidden(options: BrowseOptions, limit: Int, offset: Int = 0) = url(hiddenManagementPath("profile-a"), hiddenManagementQuery(limit, offset, emptyMap(), options))
        fun local(options: BrowseOptions, limit: Int, offset: Int = 0) = url(libraryGroupsPath("local"), libraryCardsQuery("profile-a", limit, offset, "created", false, null, options))
        val hidden = BrowseOptions(sort = BrowseSort.NAME, ascending = true, originalLanguages = setOf("fra", "nob"))
        val hiddenUnknown = hidden.copy(originalLanguages = emptySet(), includeUnknownOriginalLanguage = true)
        val local = BrowseOptions(originalLanguages = setOf("fra", "haw"))
        val urls = linkedMapOf(
            "hidden-selected" to hidden(hidden, 1), "hidden-continuation" to hidden(hidden, 1, 1),
            "hidden-unknown" to hidden(hiddenUnknown, 50),
            "local-cards-selected" to local(local, 1), "local-cards-continuation" to local(local, 1, 1),
            "local-cards-unknown" to local(BrowseOptions(includeUnknownOriginalLanguage = true), 50),
        )
        val output = buildJsonObject { urls.forEach { (name, url) -> put(name, url) } }.toString().toByteArray()
        assertEquals(6, urls.size)
        assertTrue(urls.getValue("hidden-selected").contains("originalLanguages=fra%2Cnob"))
        assertTrue(urls.getValue("local-cards-selected").contains("originalLanguages=fra%2Chaw"))
        assertTrue(urls.getValue("local-cards-selected").contains("include=cards"))
        assertFalse(urls.getValue("hidden-unknown").contains("originalLanguages="))
        System.getProperty("sourceFilterContractsDir")?.let { directory ->
            val target = File(directory).apply { mkdirs() }
            File(target, "native-management-queries.json").writeBytes(output)
            val sha = MessageDigest.getInstance("SHA-256").digest(output).joinToString("") { "%02x".format(it) }
            File(target, "native-management-queries.sha256").writeText(sha + "\n")
        }
    }
    @Test fun `hidden options never send unsupported facets watched runtime rating or wrong source`() {
        val q = hiddenManagementQuery(50, 0, emptyMap(), BrowseOptions(sort = BrowseSort.YEAR, kind = BrowseKind.SHOWS, letter = "A", originalLanguages = setOf("fre")))
        assertEquals("hidden", q["source"]); assertEquals("fra", q["originalLanguages"])
        assertEquals("series", q["filterMediaType"])
        assertTrue(q.keys.none { it in setOf("includeFacets", "watchStatus", "genres", "ratingSource", "runtime", "includeUnwatchedCounts") })
        for (options in listOf(BrowseOptions(watch = WatchFilter.WATCHED), BrowseOptions(genres = setOf("Drama")), BrowseOptions(sort = BrowseSort.RATING))) {
            assertTrue(runCatching { hiddenManagementQuery(50, 0, emptyMap(), options) }.exceptionOrNull() is ManagementPageFailure)
        }
        assertTrue(runCatching { hiddenManagementQuery(50, 0, mapOf("source" to "watchlist"), BrowseOptions()) }.isFailure)
    }
    @Test fun `known remote active filter rejects before Android send`() = runTest {
        for (source in listOf("plex", "jellyfin", " PLEX ")) {
            assertTrue(runCatching { ApiClient.libraryPage(LibraryRef("remote", "Remote", sourceType = source), BrowseOptions(originalLanguages = setOf("fra")), 50, 0) }.exceptionOrNull() is ManagementPageFailure)
        }
    }
    @Test fun `actual stale hidden and library APIs reject before query or Android transport`() = runTest {
        val original = ProfileDataContext.current
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        for (read in listOf<suspend () -> Unit>(
            { ApiClient.browsePage(BrowseList.Hidden, 50, expectedContext = original) },
            { ApiClient.libraryPage(LibraryRef("local", "Local"), BrowseOptions(), 50, 0, original) },
        )) {
            assertTrue(runCatching { read() }.exceptionOrNull() is CancellationException)
        }
    }
    @Test fun `actual management builders roundtrip queries through synthetic HTTP transport`() = runBlocking {
        val expected = ProfileDataContext.current
        val requests = listOf(
            hiddenManagementPath("profile-a") to hiddenManagementQuery(1, 0, emptyMap(), BrowseOptions(sort = BrowseSort.NAME, ascending = true, originalLanguages = setOf("fra", "nob"))),
            libraryGroupsPath("local") to libraryCardsQuery("profile-a", 1, 1, "created", false, null, BrowseOptions(originalLanguages = setOf("fra", "haw"))),
        )
        java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val seen = CompletableDeferred<List<String>>()
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            val client = okhttp3.OkHttpClient()
            executor.submit {
                val lines = mutableListOf<String>()
                repeat(2) {
                    server.accept().use { peer ->
                        peer.soTimeout = 3000
                        val reader = peer.getInputStream().bufferedReader()
                        lines += reader.readLine()
                        while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
                        peer.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                            flush()
                        }
                    }
                }
                seen.complete(lines)
            }
            try {
                val scope = RequestContext({ ProfileDataContext.current }, { ProfileDataContext.invalidate() }).capture(expected)
                for ((path, query) in requests) ContextBoundTransport(scope, false, { false }, { error("No recovery expected") }).execute {
                    val request = okhttp3.Request.Builder().url("http://127.0.0.1:${server.localPort}" + url(path, query)).build()
                    client.newCall(request).execute().use { assertEquals(200, it.code) }
                }
                val received = withTimeout(3000) { seen.await() }
                assertEquals(requests.map { (path, query) -> "GET ${url(path, query)} HTTP/1.1" }, received)
                assertTrue(received[0].contains("originalLanguages=fra%2Cnob"))
                assertTrue(received[1].contains("offset=1"))
            } finally {
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
                executor.shutdownNow()
                executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

}
