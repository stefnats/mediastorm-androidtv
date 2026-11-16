package com.strmr.tv.api

import com.strmr.tv.library.CustomListOperations
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** The same compound adapter actually consumed by Model and TitleActions; synthetic IO only. */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchedCompoundContextTest {
    private val movie = DetailsTarget("tmdb:movie:7", "Synthetic", true, year = 2020, tmdbId = "7")
    private val series = DetailsTarget("tmdb:series:41", "Synthetic series", false, tmdbId = "41")
    private fun op(target: DetailsTarget = movie) = CustomListOperations(target = target).begin()!!
    private class Access : WatchedAccess {
        val requests = mutableListOf<WatchedRequest>()
        var send: suspend (WatchedRequest) -> Unit = {}
        override suspend fun send(request: WatchedRequest) { requests += request; send.invoke(request) }
    }
    private suspend fun canceled(action: suspend () -> Unit) { assertTrue(runCatching { action() }.exceptionOrNull() is CancellationException) }
    @Test fun `positive movie watched is one bulk and unwatched clears progress in same compound`() = runTest {
        val watched = Access(); performWatched(selectMovieWatched(movie, true, op()), watched)
        assertEquals(1, watched.requests.size)
        val unwatched = Access(); var done = 0
        performWatched(selectMovieWatched(movie, false, op()), unwatched, success = { done++ })
        assertEquals(listOf("bulk", "delete"), unwatched.requests.map { it.path.substringAfterLast('/') })
        assertEquals(movie.titleId, unwatched.requests.last().progressBody!!.getValue("itemId").jsonPrimitive.content)
        assertEquals(1, done); assertTrue(unwatched.requests.all { it.expected == ProfileDataContext.current })
    }
    @Test fun `history item identity stays separate from metadata leaf in episode and card payloads`() = runTest {
        val episode = Episode("tvdb:episode:123", name = "Synthetic episode", seasonNumber = 1, episodeNumber = 2, itemId = "tmdb:tv:41:s01e02")
        val access = Access(); performWatched(selectEpisodesWatched(series, listOf(episode), true, WatchedScope.SEASON, op(series)), access)
        val request = access.requests.single(); val body = request.body!!.single().jsonObject
        assertEquals("tmdb:tv:41:s01e02", body.getValue("itemId").jsonPrimitive.content)
        assertFalse(body.toString().contains(episode.id)); assertEquals("season", request.query["scope"])
        val card = CardEpisode(1, 2, "Synthetic episode", "progress-card")
        val selected = selectCardEpisodeWatched(series, card, false, op(series)); assertSame(card, selected.cardEpisode)
        assertEquals("progress-card", selected.bulk.body!!.single().jsonObject.getValue("itemId").jsonPrimitive.content)
        assertNull(selected.progress)
    }
    @Test fun `bulk snapshot is immutable and show scope and original indices survive caller changes`() {
        val original = Episode("leaf", seasonNumber = 4, episodeNumber = 9, itemId = "history")
        val input = mutableListOf(original); val intent = episodesWatchedIntent(series, input, true, WatchedScope.SHOW, ProfileDataContext.current)
        input.clear(); val selection = intent.bind(op(series))!!
        assertSame(original, selection.episodes.single()); assertEquals("show", selection.bulk.query["scope"])
        assertEquals(4, selection.bulk.body!!.single().jsonObject.getValue("seasonNumber").jsonPrimitive.int)
        assertTrue(runCatching { (selection.episodes as MutableList).clear() }.isFailure)
    }
    @Test fun `second same-owner write cannot interleave between movie history and progress`() = runTest {
        val firstGate = CompletableDeferred<Unit>(); val access = Access().apply { send = { if (requests.size == 1) firstGate.await() } }
        val first = async { performWatched(selectMovieWatched(movie, false, op()), access) }; runCurrent()
        val second = async { performWatched(selectMovieWatched(movie, true, op()), access) }; runCurrent()
        assertEquals(1, access.requests.size); firstGate.complete(Unit); first.await(); second.await()
        assertEquals(listOf("bulk", "delete", "bulk"), access.requests.map { it.path.substringAfterLast('/') })
    }
    @Test fun `retirement after accepted history does zero progress delete or success publication`() = runTest {
        val operations = CustomListOperations(target = movie); val selected = selectMovieWatched(movie, false, operations.begin()!!)
        val access = Access().apply { send = { operations.close() } }; var success = 0
        canceled { performWatched(selected, access, success = { success++ }) }
        assertEquals(1, access.requests.size); assertEquals(0, success)
    }
    @Test fun `simulated A B A after masked first response cannot start followup under new owner`() = runTest {
        val reply = CompletableDeferred<Unit>(); val access = Access().apply { send = { withContext(NonCancellable) { reply.await() } } }
        val old = selectMovieWatched(movie, false, op()); var success = 0
        val task = async { performWatched(old, access, success = { success++ }) }; runCurrent()
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); reply.complete(Unit); canceled { task.await() }
        assertEquals(1, access.requests.size); assertEquals(0, success)
    }
    @Test fun `only progress missing404 is accepted and bulk404 and terminal failures remain errors`() = runTest {
        val access = Access().apply { send = { if (it.progressBody != null) throw ApiError.Http(HttpFailure(404, "Missing")) } }
        var success = 0; performWatched(selectMovieWatched(movie, false, op()), access, success = { success++ }); assertEquals(1, success)
        for (status in listOf(404, 401, 403, 500)) {
            val failed = Access().apply { send = { throw ApiError.Http(HttpFailure(status, "Synthetic")) } }
            assertTrue(runCatching { performWatched(selectMovieWatched(movie, false, op()), failed) }.isFailure)
            assertEquals(1, failed.requests.size)
        }
    }
    @Test fun `second-stage terminal denies complete success without rollback or retry`() = runTest {
        val failure = ApiError.Http(HttpFailure(403, """{"code":"kids_vod_access_denied"}"""))
        val access = Access().apply { send = { if (it.progressBody != null) throw failure } }; var success = 0
        assertSame(failure, runCatching { performWatched(selectMovieWatched(movie, false, op()), access, success = { success++ }) }.exceptionOrNull())
        assertEquals(2, access.requests.size); assertEquals(0, success)
    }
    @Test fun `confirmation is one-shot and accepted normal dismiss does not cancel original selection`() = runTest {
        val selected = selectMovieWatched(movie, true, op()); val ticket = WatchedConfirmation(selected)
        assertSame(selected, ticket.confirm()); ticket.cancel(); assertNull(ticket.confirm())
        val access = Access(); performWatched(selected, access); assertEquals(1, access.requests.size)
        val canceled = WatchedConfirmation(selectMovieWatched(movie, true, op())); canceled.cancel(); assertNull(canceled.confirm())
    }
    @Test fun `confirmation cannot bind a value-equal replacement target or retired selection`() {
        val intent = movieWatchedIntent(movie, true, ProfileDataContext.current)
        assertTrue(runCatching { intent.bind(op(movie.copy())) }.isFailure)
        val selected = intent.bind(op())!!; val ticket = WatchedConfirmation(selected)
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); assertNull(ticket.confirm()); assertNull(intent.bind(op()))
    }
    @Test fun `initial read404 false is guarded and terminal or malformed responses never become false`() = runTest {
        assertFalse(readWatched(ProfileDataContext.current) { throw ApiError.Http(HttpFailure(404, "Missing")) })
        assertTrue(readWatched(ProfileDataContext.current) { """{"watched":true}""" })
        for (raw in listOf("{}", """{"watched":"false"}""", """{"watched":null}""", "[]")) assertTrue(runCatching { readWatched(ProfileDataContext.current) { raw } }.isFailure)
        val failure = ApiError.Http(HttpFailure(403, "Synthetic")); assertSame(failure, runCatching { readWatched(ProfileDataContext.current) { throw failure } }.exceptionOrNull())
    }
    @Test fun `retired initial read before or during404 yields cancellation with no default fallback`() = runTest {
        val expected = ProfileDataContext.current; var reads = 0; ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        canceled { readWatched(expected) { reads++; """{"watched":false}""" } }; assertEquals(0, reads)
        val current = ProfileDataContext.current
        canceled { readWatched(current) { ProfileDataContext.invalidate(); throw ApiError.Http(HttpFailure(404, "Missing")) } }
    }
    @Test fun `typed terminal404 never becomes missing read or successful progress clear`() = runTest {
        val error = ApiError.Http(HttpFailure(404, """{"code":"kids_vod_access_denied"}"""))
        assertSame(error, runCatching { readWatched(ProfileDataContext.current) { throw error } }.exceptionOrNull())
        val access = Access().apply { send = { if (it.progressBody != null) throw error } }; var success = 0
        assertSame(error, runCatching { performWatched(selectMovieWatched(movie, false, op()), access, success = { success++ }) }.exceptionOrNull())
        assertEquals(0, success); assertEquals(2, access.requests.size)
    }
    @Test fun `start publication retiring producer selection performs zero IO`() = runTest {
        val parent = Job(); val operations = CustomListOperations(target = movie) { parent.isActive }
        val selected = selectMovieWatched(movie, false, operations.begin()!!); val access = Access(); var success = 0
        canceled { performWatched(selected, access, start = { parent.cancel() }, success = { success++ }) }
        assertTrue(access.requests.isEmpty()); assertEquals(0, success)
    }
    @Test fun `empty episode set is a true no-op without publication`() = runTest {
        val access = Access(); var success = 0
        performWatched(selectEpisodesWatched(series, emptyList(), false, null, op(series)), access, success = { success++ })
        assertTrue(access.requests.isEmpty()); assertEquals(0, success)
    }
}
