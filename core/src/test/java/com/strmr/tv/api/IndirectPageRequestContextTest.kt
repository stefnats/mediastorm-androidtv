package com.strmr.tv.api

import com.strmr.tv.home.*
import com.strmr.tv.library.HiddenItemsStore
import com.strmr.tv.library.ProfileDataContext
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

/** Real query helper + synthetic HTTP, and stale actual APIs before Android send; these are separate proofs. */
class IndirectPageRequestContextTest {
    private suspend fun cancelled(read: suspend () -> Any?) {
        try { read(); fail("Retired read survived") } catch (_: CancellationException) { }
    }
    @Test fun `production library query preserves profile pagination sorting search and encoded values`() {
        val query = ApiClient.libraryGroupsQuery(ProfileDataContext.current, 60, 120, "name", true, "Ä", "movie", "A+B & C", false)
        assertEquals(mapOf("limit" to "60", "offset" to "120", "userId" to ApiClient.profileId,
            "sort" to "name", "dir" to "asc", "alphabet" to "Ä", "mediaType" to "movie", "query" to "A+B & C"), query)
    }
    @Test fun `actual old APIs stop before Android stores or any HTTP`() = runBlocking {
        val expected = ProfileDataContext.current
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        cancelled { ApiClient.browsePage(BrowseList.Library(LibraryRef("synthetic", "Synthetic")), 60, expectedContext = expected) }
        cancelled { ApiClient.browsePage(BrowseList.ContinueWatching, 60, expectedContext = expected) }
        cancelled { ApiClient.browsePage(BrowseList.Hidden, 60, expectedContext = expected) }
        cancelled { ApiClient.libraryGroups("synthetic", 60, expectedContext = expected) }
        cancelled { ApiClient.libraryPage(LibraryRef("synthetic", "Synthetic"), null, 60, 0, expected) }
        cancelled { ApiClient.continueWatching(expected) }
        cancelled { HiddenItemsStore.pageItems(expected) }
        cancelled { HiddenItemsStore.refresh(expected) }
        assertEquals(0, ApiClient.http.dispatcher.runningCallsCount()); assertEquals(0, ApiClient.http.dispatcher.queuedCallsCount())
    }
    @Test fun `actual library page adapter maps server rows and retains original options and owner`() = runTest {
        val expected = ProfileDataContext.current
        val library = LibraryRef("synthetic", "Synthetic")
        val result = readLibraryPage(library, BrowseOptions(sort = BrowseSort.NAME, ascending = true, letter = "S"), 60, 120, expected) { id, count, offset, sort, ascending, letter, owner ->
            assertEquals(library.id, id); assertEquals(60, count); assertEquals(120, offset)
            assertEquals("name", sort); assertTrue(ascending); assertEquals("S", letter); assertEquals(expected, owner)
            LibraryGroupPage(listOf(LibraryGroup("one", title = "Synthetic")), 121, listOf("S"))
        }
        assertEquals(121, result.total); assertEquals(listOf("S"), result.letters)
        val entry = (result.entries.single() as BrowseEntry.LibraryItemEntry).entry
        assertEquals(library, entry.library); assertEquals("one", entry.group.id)
    }
    @Test fun `library adapter rejects profile change after successful callback`() = runTest {
        val expected = ProfileDataContext.current
        cancelled { readLibraryPage(LibraryRef("synthetic", "Synthetic"), null, 60, 0, expected) { _, _, _, _, _, _, _ ->
            ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); LibraryGroupPage(emptyList(), 99)
        } }
    }
    @Test fun `real loopback request roundtrips actual library query without credential fixture`() = runBlocking {
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val requestLine = CompletableDeferred<String>()
            executor.submit { server.accept().use { socket ->
                socket.soTimeout = 3000
                val reader = socket.getInputStream().bufferedReader()
                requestLine.complete(reader.readLine())
                while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
                socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray()); flush() }
            } }
            val client = OkHttpClient()
            try {
                val expected = ProfileDataContext.current
                val query = ApiClient.libraryGroupsQuery(expected, 60, 120, "name", true, "Ä", null, "A+B & C", true)
                val url = okhttp3.HttpUrl.Builder().scheme("http").host("127.0.0.1").port(server.localPort)
                    .addPathSegments("api/library/libraries/synthetic/groups").apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
                val owner = RequestContext({ ProfileDataContext.current }, { ProfileDataContext.invalidate() }).capture(expected)
                ContextBoundTransport(owner, false, { false }, { error("No recovery") }).execute {
                    client.newCall(Request.Builder().url(url).build()).execute().use { assertEquals(200, it.code) }
                }
                val actual = url.resolve(withTimeout(3000) { requestLine.await() }.split(' ')[1])!!
                assertEquals(ApiClient.profileId, actual.queryParameter("userId")); assertEquals("120", actual.queryParameter("offset"))
                assertEquals("A+B & C", actual.queryParameter("query")); assertEquals("Ä", actual.queryParameter("alphabet"))
            } finally {
                client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
                executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS)
            }
        }
    }
}
