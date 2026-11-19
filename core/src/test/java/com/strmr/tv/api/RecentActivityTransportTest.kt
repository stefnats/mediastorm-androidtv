package com.strmr.tv.api

import com.strmr.tv.home.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

/** Real builder and portable HTTP/auth/context components; no Android sender claim. */
class RecentActivityTransportTest {
    private fun client() = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
    private fun OkHttpClient.stop() { dispatcher.executorService.shutdownNow(); connectionPool.evictAll() }
    private fun request(peer: WatchingPeer, selected: RecentActivityRequest): Request {
        val url = peer.url.newBuilder().encodedPath(selected.path).query(null).apply { selected.query.toSortedMap().forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return Request.Builder().url(url).header("X-Client-ID", "synthetic-client").build()
    }
    @Test fun `real local HTTP accepts genuine backend bytes only for the explicit original profile request`() = runBlocking {
        val selected = recentActivityRequest("profile-a", RecentActivityFixture.list(), 1, 0, BrowseOptions(originalLanguages = setOf("fra")))
        WatchingPeer(listOf(WatchingReply(body = RecentActivityFixture.raw("late-filtered")))).use { peer ->
            val http = client()
            try {
                val context = RequestContext({ 0L }, {}).capture(0)
                val result = ContextBoundTransport(context, false, { false }, { error("No fallback") }).execute { http.newCall(request(peer, selected)).await() }
                val page = decodeRecentActivityPage(result.text, 1, 0)
                assertEquals(2, page.total); assertEquals(1, page.entries.size)
                assertTrue(peer.lines.single().startsWith("GET /api/users/profile-a/display-list?"))
                assertTrue(peer.lines.single().contains("source=recently-watched-cached")); assertFalse(peer.lines.single().contains("includeUnwatchedCounts"))
                assertEquals(1, peer.count.get()); assertNull(result.observation)
            } finally { http.stop() }
        }
    }
    @Test fun `authorization rotation retains the same source and cannot publish after original context changes`() = runBlocking {
        WatchingPeer(listOf(WatchingReply(status = 401, body = "{\"code\":\"unauthorized\"}"))).use { peer ->
            val http = client()
            try {
                var epoch = 0L; val contexts = RequestContext({ epoch }, { epoch++ }); val owner = contexts.capture(0)
                val selected = recentActivityRequest("profile-a", RecentActivityFixture.list(), 1, 0, BrowseOptions())
                val outcome = runCatching {
                    RequestAuthorization(owner, { "synthetic" }, { false }, { contexts.changeProfile {}; ApiClient.RefreshOutcome.REFRESHED }, { error("No signout") }) {
                        val response = ContextBoundTransport(owner, false, { false }, { error("No fallback") }).execute { http.newCall(request(peer, selected)).await() }
                        if (!response.isSuccessful) throw ApiError.Http(HttpFailure(response.code, response.text))
                        response.text
                    }.execute()
                }
                assertTrue(outcome.exceptionOrNull() is CancellationException); assertEquals(1, peer.count.get())
            } finally { http.stop() }
        }
    }
    @Test fun `PIN denial remains explicit and changed owner cannot send a retry or normal feed`() = runBlocking {
        WatchingPeer(listOf(WatchingReply(status = 403, body = "{\"code\":\"profile_locked\",\"profileId\":\"profile-a\"}"))).use { peer ->
            val http = client()
            try {
                var epoch = 0L; val contexts = RequestContext({ epoch }, { epoch++ }); val owner = contexts.capture(0)
                val selected = recentActivityRequest("profile-a", RecentActivityFixture.list(), 1, 0, BrowseOptions())
                suspend fun attempt(): String {
                    val response = ContextBoundTransport(owner, false, { false }, { error("No fallback") }).execute { http.newCall(request(peer, selected)).await() }
                    if (!response.isSuccessful) throw ApiError.Http(HttpFailure(response.code, response.text))
                    return response.text
                }
                val outcome = runCatching { try { attempt() } catch (error: ApiError) { assertTrue(error.isProfileLocked); contexts.changeProfile {}; owner.check(); attempt() } }
                assertTrue(outcome.exceptionOrNull() is CancellationException); assertEquals(1, peer.count.get())
            } finally { http.stop() }
        }
    }
}
