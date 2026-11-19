package com.strmr.tv.api

import com.strmr.tv.home.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

/** Actual portable HTTP/auth/context/Date path; this does not execute Android sendOwnedRaw. */
class CachedSimilarTransportTest {
    private fun client() = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
    private fun OkHttpClient.stop() { dispatcher.executorService.shutdownNow(); connectionPool.evictAll() }
    private fun request(peer: WatchingPeer, selected: CachedSimilarRequest): Request {
        val url = peer.url.newBuilder().encodedPath(selected.path).query(null).apply { selected.query.toSortedMap().forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        requireObservedBrowseRequest("GET", selected.path, "/api/users/profile", selected.query, true)
        return Request.Builder().url(url).header("X-Client-ID", "synthetic-client").build()
    }
    private fun fixtureDate(): String = SimilarFixture.records().first {
        it.jsonObject.getValue("name").jsonPrimitive.content == "late-filtered"
    }.jsonObject.getValue("httpDate").jsonPrimitive.content
    @Test fun `actual Similar request receives genuine backend body and final receipt-bound clock`() = runBlocking {
        val list = SimilarFixture.list(); val selected = cachedSimilarRequest("profile", list, 1, 0, BrowseOptions(originalLanguages = setOf("fra")))
        WatchingPeer(listOf(WatchingReply(date = fixtureDate(), body = SimilarFixture.raw("late-filtered"), delayHeader = 20, delayBody = 20))).use { peer ->
            val http = client()
            try {
                val req = request(peer, selected); val owner = RequestContext({ 0L }, {}).capture(0)
                val result = ContextBoundTransport(owner, false, { false }, { error("No fallback") }).execute { http.newCall(req).await(true) }
                val page = decodeCachedSimilarPage(result.text, list, result.observedClock(req.url), 1, 0)
                assertEquals(2, page.total); assertEquals("tmdb:movie:650", HomeCard.from(page.entries.single()).target!!.titleId)
                assertEquals(1, peer.count.get()); assertTrue(peer.lines.single().contains("source=similar-cached")); assertTrue(peer.lines.single().contains("tmdbId=77"))
                assertFalse(peer.lines.single().contains("includeUnwatchedCounts"))
            } finally { http.stop() }
        }
    }
    @Test fun `actual bad Date body bounds invalid UTF8 and redirected URL never provide Similar freshness`() = runBlocking {
        val selected = cachedSimilarRequest("profile", SimilarFixture.list(), 1, 0, BrowseOptions())
        for (reply in listOf(WatchingReply(date = null), WatchingReply(duplicateDate = true), WatchingReply(date = "invalid"), WatchingReply(date = "Mon, 30 Feb 2026 23:43:46 GMT"), WatchingReply(body = "x".repeat(4 * 1024 * 1024 + 1)), WatchingReply(rawBody = byteArrayOf(0xc3.toByte(), 0x28)))) {
            WatchingPeer(listOf(reply)).use { peer -> val http = client(); try { val req = request(peer, selected); assertTrue(runCatching { http.newCall(req).await(true).observedClock(req.url) }.isFailure) } finally { http.stop() } }
        }
        WatchingPeer(listOf(WatchingReply())).use { target -> WatchingPeer(listOf(WatchingReply(status = 302, location = target.url.toString()))).use { origin ->
            val http = client(); try { val req = request(origin, selected); val result = http.newCall(req).await(true); assertTrue(runCatching { result.observedClock(req.url) }.isFailure); assertEquals(1, target.count.get()) } finally { http.stop() }
        } }
    }
    @Test fun `only final authorized success has a clock and changed PIN owner cannot retry old Similar`() = runBlocking {
        val selected = cachedSimilarRequest("profile", SimilarFixture.list(), 1, 0, BrowseOptions())
        WatchingPeer(listOf(WatchingReply(status = 401, body = "{\"code\":\"unauthorized\"}"), WatchingReply(date = fixtureDate(), body = SimilarFixture.raw("late-filtered")))).use { peer ->
            val http = client()
            try {
                val owner = RequestContext({ 0L }, {}).capture(0); var rotations = 0; var final: HttpResult? = null
                RequestAuthorization(owner, { "synthetic-token" }, { false }, { rotations++; ApiClient.RefreshOutcome.REFRESHED }, { error("No signout") }) {
                    val response = ContextBoundTransport(owner, false, { false }, { error("No fallback") }).execute { http.newCall(request(peer, selected)).await(true) }
                    if (!response.isSuccessful) { assertNull(response.observation); throw ApiError.Http(HttpFailure(response.code, response.text)) }
                    final = response; response.text
                }.execute()
                assertEquals(2, peer.count.get()); assertEquals(1, rotations); assertNotNull(final!!.observedClock(request(peer, selected).url))
            } finally { http.stop() }
        }
        WatchingPeer(listOf(WatchingReply(status = 403, body = "{\"code\":\"profile_locked\",\"profileId\":\"profile\"}"))).use { peer ->
            val http = client()
            try {
                var epoch = 0L; val contexts = RequestContext({ epoch }, { epoch++ }); val owner = contexts.capture(0)
                val failure = runCatching {
                    val response = ContextBoundTransport(owner, false, { false }, { error("No fallback") }).execute { http.newCall(request(peer, selected)).await(true) }
                    if (!response.isSuccessful) { assertNull(response.observation); val error = ApiError.Http(HttpFailure(response.code, response.text)); assertTrue(error.isProfileLocked); contexts.changeProfile {}; owner.check() }
                }.exceptionOrNull()
                assertTrue(failure is CancellationException); assertEquals(1, peer.count.get())
            } finally { http.stop() }
        }
    }
    @Test fun `late physical response after profile ABA and delayed body expiry cannot publish saved rows`() = runBlocking {
        val selected = cachedSimilarRequest("profile", SimilarFixture.list(), 1, 0, BrowseOptions())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        WatchingPeer(listOf(WatchingReply(date = fixtureDate(), body = SimilarFixture.raw("late-filtered"), entered = entered, release = release))).use { peer ->
            val http = client()
            try {
                var epoch = 0L; val contexts = RequestContext({ epoch }, { epoch++ }); val owner = contexts.capture(0)
                val pending = async(Dispatchers.IO) { runCatching { ContextBoundTransport(owner, false, { false }, { error("No fallback") }).execute { http.newCall(request(peer, selected)).await(true) } }.exceptionOrNull() }
                assertTrue(entered.await(2, TimeUnit.SECONDS)); contexts.changeProfile {}; contexts.changeProfile {}; release.countDown()
                assertTrue(pending.await() is CancellationException); assertEquals(1, peer.count.get())
            } finally { release.countDown(); http.stop() }
        }
        val list = SimilarFixture.list()
        WatchingPeer(listOf(WatchingReply(date = SimilarFixture.dateText(SimilarFixture.date), body = SimilarFixture.synthetic(1, 0, 1, expires = SimilarFixture.date + 1100L), delayBody = 200))).use { peer ->
            val http = client(); try { val req = request(peer, selected); val response = http.newCall(req).await(true); assertTrue(runCatching { decodeCachedSimilarPage(response.text, list, response.observedClock(req.url), 1, 0) }.isFailure) } finally { http.stop() }
        }
    }
}
