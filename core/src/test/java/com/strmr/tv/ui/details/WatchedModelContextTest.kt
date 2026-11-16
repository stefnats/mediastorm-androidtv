package com.strmr.tv.ui.details

import com.strmr.tv.api.*
import com.strmr.tv.library.CustomListOperations
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.library.TitleActions
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Actual DetailsModel and its shared compound adapter via synthetic network ports. */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchedModelContextTest {
    private val movie = DetailsTarget("tmdb:movie:7", "Synthetic", true)
    private val series = DetailsTarget("tmdb:series:41", "Synthetic series", false)
    private fun movieBundle(watched: Boolean = false, resume: ResumeState? = null) = DetailsBundle(movieDetails = Title(movie.titleId, movie.name, mediaType = "movie"), watched = watched, resume = resume)
    private fun episode(id: String = "tvdb:episode:123", n: Int = 1) = Episode(id, name = "Synthetic", seasonNumber = 1, episodeNumber = n, itemId = "tmdb:tv:41:s01e0$n", watched = false, numbering = EpisodeNumbering("tvdb:series:41", "official"))
    private fun seriesBundle(episodes: List<Episode>) = DetailsBundle(seriesDetails = SeriesDetails(Title(series.titleId, series.name, mediaType = "series"), listOf(Season("season", number = 1, episodes = episodes))))
    private class Access : WatchedAccess {
        val requests = mutableListOf<WatchedRequest>(); var sending: suspend (WatchedRequest) -> Unit = {}
        override suspend fun send(request: WatchedRequest) { requests += request; sending(request) }
    }
    private fun op(model: DetailsModel) = CustomListOperations(model.dataContext, model.target).begin()!!
    @Test fun `actual movie unwatched completes both writes clears resume and changes once`() = runTest {
        val model = DetailsModel(movie, backgroundScope); model.reloadForReminder { movieBundle(true, ResumeState(position = 100.0, eligible = true)) }
        val selection = model.movieWatchIntent(false)!!.bind(op(model))!!; val before = TitleActions.changes; val access = Access()
        model.applyWatch(selection, access)
        assertEquals(false, model.movieWatched); assertNull(model.resumePoint(null)); assertEquals(2, access.requests.size); assertEquals(before + 1, TitleActions.changes); assertNull(model.watchedError)
    }
    @Test fun `late earlier metadata read cannot restore cleared movie resume or watched state`() = runTest {
        val model = DetailsModel(movie, backgroundScope); val old = movieBundle(true, ResumeState(position = 100.0, eligible = true)); model.reloadForReminder { old }
        val reply = CompletableDeferred<DetailsBundle>(); val reading = async { model.reloadForReminder { reply.await() } }; runCurrent()
        model.applyWatch(model.movieWatchIntent(false)!!.bind(op(model))!!, Access())
        reply.complete(old); reading.await()
        assertFalse(model.movieWatched!!); assertNull(model.resumePoint(null))
    }
    @Test fun `late metadata episode state cannot overwrite a newer confirmed local mutation`() = runTest {
        val model = DetailsModel(series, backgroundScope); val ep = episode(); model.reloadForReminder { seriesBundle(listOf(ep)) }
        val reply = CompletableDeferred<DetailsBundle>(); val reading = async { model.reloadForReminder { reply.await() } }; runCurrent()
        model.applyWatch(model.episodeWatchIntent(listOf(ep), true, WatchedScope.SEASON)!!.bind(op(model))!!, Access())
        reply.complete(seriesBundle(listOf(ep.copy()))); reading.await()
        assertTrue(model.isWatched(model.seasons.single().episodes.single()))
    }
    @Test fun `initial reload retirement invalidates old confirmation even before replacement returns`() = runTest {
        val model = DetailsModel(series, backgroundScope); val ep = episode(); model.reloadForReminder { seriesBundle(listOf(ep)) }
        val ticket = WatchedConfirmation(model.episodeWatchIntent(listOf(ep), true, null)!!.bind(op(model))!!)
        val reply = CompletableDeferred<DetailsBundle>(); val reading = async { model.reloadForReminder { reply.await() } }; runCurrent()
        assertNull(ticket.confirm()); reply.complete(seriesBundle(listOf(ep.copy()))); reading.await()
    }
    @Test fun `copy and same-id replacement episodes never acquire the old model selection`() = runTest {
        val model = DetailsModel(series, backgroundScope); val ep = episode(); model.reloadForReminder { seriesBundle(listOf(ep)) }
        assertNull(model.episodeWatchIntent(listOf(ep.copy()), true, null))
        val intent = model.episodeWatchIntent(listOf(ep), true, null)!!
        model.reloadForReminder { seriesBundle(listOf(ep.copy(numbering = EpisodeNumbering("tvdb:series:41", "dvd")))) }
        assertNull(intent.bind(op(model)))
    }
    @Test fun `ordinary failed mutation cannot rollback another later same-owner success`() = runTest {
        val model = DetailsModel(series, backgroundScope); val first = episode(); val second = episode("tvdb:episode:124", 2); model.reloadForReminder { seriesBundle(listOf(first, second)) }
        val gate = CompletableDeferred<Unit>(); val access = Access().apply { sending = { if (requests.size == 1) { gate.await(); throw java.io.IOException("synthetic") } } }
        val a = model.episodeWatchIntent(listOf(first), true, null)!!.bind(op(model))!!; val b = model.episodeWatchIntent(listOf(second), true, null)!!.bind(op(model))!!
        val old = async { runCatching { model.applyWatch(a, access) } }; runCurrent(); assertTrue(model.isWatched(first))
        val current = async { model.applyWatch(b, access) }; runCurrent(); gate.complete(Unit); assertTrue(old.await().isFailure); current.await()
        assertFalse(model.isWatched(first)); assertTrue(model.isWatched(second)); assertNull(model.watchedError)
    }
    @Test fun `terminal partial write does not clear resume publish changed or leave optimistic success`() = runTest {
        val model = DetailsModel(movie, backgroundScope); model.reloadForReminder { movieBundle(true, ResumeState(position = 100.0, eligible = true)) }
        val before = TitleActions.changes; val failure = ApiError.Http(HttpFailure(403, "Synthetic"))
        val access = Access().apply { sending = { if (it.progressBody != null) throw failure } }
        assertSame(failure, runCatching { model.applyWatch(model.movieWatchIntent(false)!!.bind(op(model))!!, access) }.exceptionOrNull())
        assertEquals(true, model.movieWatched); assertNotNull(model.resumePoint(null)); assertEquals(before, TitleActions.changes); assertNotNull(model.watchedError)
    }
    @Test fun `late old epoch response cannot publish overrides errors changes or second-stage writes`() = runTest {
        val model = DetailsModel(movie, backgroundScope); model.reloadForReminder { movieBundle() }; val before = TitleActions.changes
        val reply = CompletableDeferred<Unit>(); val access = Access().apply { sending = { withContext(NonCancellable) { reply.await() } } }
        val selected = model.movieWatchIntent(false)!!.bind(op(model))!!; val task = async { model.applyWatch(selected, access) }; runCurrent()
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); reply.complete(Unit)
        assertTrue(runCatching { task.await() }.exceptionOrNull() is CancellationException); assertEquals(before, TitleActions.changes); assertNull(model.watchedError); assertEquals(1, access.requests.size)
    }
    @Test fun `cancelled original producer retires confirmation without a new job`() = runTest {
        val producer = Job(); val scope = CoroutineScope(coroutineContext + producer); val model = DetailsModel(series, scope); val ep = episode()
        model.reloadForReminder { seriesBundle(listOf(ep)) }; val selection = model.episodeWatchIntent(listOf(ep), true, null)!!.bind(op(model))!!
        producer.cancel(); assertNull(WatchedConfirmation(selection).confirm())
    }
}
