package com.strmr.tv.postplay

import com.strmr.tv.api.*
import com.strmr.tv.library.CustomListOperation
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.ratings.PersonalRating
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Actual Editor/Gateway receipt path; no parallel UI reducer or Android controller runtime. */
@OptIn(ExperimentalCoroutinesApi::class)
class PostPlayWatchlistContextTest {
    private val target = DetailsTarget("tmdb:movie:7", "Synthetic", true)
    private class Gateway : PostPlayGateway {
        var choices = listOf(Title("tmdb:movie:8", "Original choice", mediaType = "movie"))
        var beforeRead: suspend () -> Unit = {}
        var beforeSave: suspend () -> Unit = {}
        val writes = mutableListOf<DetailsTarget>()
        override fun isCurrent() = true
        override suspend fun context() = PostPlayReply(Title("tmdb:movie:7", "Synthetic", mediaType = "movie"), "none", "movie", "movie")
        override suspend fun suggestions(): PostPlaySuggestions { beforeRead(); return PostPlaySuggestions(choices.map { ListedTitle(title = it) }, "personalized", "none", "movie") }
        override suspend fun rate(target: DetailsTarget, value: PersonalRating): String = error("Ratings are outside this packet")
        override suspend fun saveToList(target: DetailsTarget, operation: CustomListOperation) { operation.check(); writes += target; beforeSave(); operation.check() }
    }
    @Test fun `original suggested instance saves once while copied equal instance never writes`() = runTest {
        val gateway = Gateway(); val editor = PostPlayEditor(target, gateway, onRated = {}); editor.load()
        val title = editor.state.value.suggestions.single()
        editor.saveToList(title.copy()); assertTrue(gateway.writes.isEmpty())
        editor.saveToList(title); assertEquals(1, gateway.writes.size); assertEquals(title.id, gateway.writes.single().titleId)
        editor.saveToList(title); assertEquals(1, gateway.writes.size); assertTrue(editor.state.value.savedToList.isNotEmpty())
    }
    @Test fun `identical replacement suggestions cannot revive old title receipt`() = runTest {
        val gateway = Gateway(); val editor = PostPlayEditor(target, gateway, onRated = {}); editor.load()
        val old = editor.state.value.suggestions.single(); gateway.choices = listOf(old.copy()); editor.refreshSuggestions()
        editor.saveToList(old); assertTrue(gateway.writes.isEmpty())
        editor.saveToList(editor.state.value.suggestions.single()); assertEquals(1, gateway.writes.size)
    }
    @Test fun `queued save after simulated A B A cannot capture replacement owner`() = runTest {
        val gateway = Gateway(); val editor = PostPlayEditor(target, gateway, onRated = {}); editor.load(); val title = editor.state.value.suggestions.single()
        val job = launch { editor.saveToList(title) }; ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); job.join()
        assertTrue(gateway.writes.isEmpty()); assertTrue(editor.state.value.savedToList.isEmpty())
    }
    @Test fun `closed editor cancels pending save and masked late reply cannot publish success`() = runTest {
        val gateway = Gateway(); val gate = CompletableDeferred<Unit>(); gateway.beforeSave = { withContext(NonCancellable) { gate.await() } }
        val editor = PostPlayEditor(target, gateway, onRated = {}); editor.load(); val title = editor.state.value.suggestions.single()
        val job = async { editor.saveToList(title) }; runCurrent(); editor.close(); gate.complete(Unit)
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException); assertTrue(editor.state.value.savedToList.isEmpty())
    }
    @Test fun `refresh failure retires pending write and old visible busy cards coherently`() = runTest {
        val gateway = Gateway(); val gate = CompletableDeferred<Unit>(); gateway.beforeSave = { withContext(NonCancellable) { gate.await() } }
        val editor = PostPlayEditor(target, gateway, onRated = {}); editor.load(); val old = editor.state.value.suggestions.single()
        val job = async { editor.saveToList(old) }; runCurrent(); assertTrue(editor.state.value.listSaving.isNotEmpty())
        gateway.beforeRead = { throw java.io.IOException("synthetic") }; editor.refreshSuggestions()
        assertTrue(editor.state.value.suggestions.isEmpty()); assertTrue(editor.state.value.listSaving.isEmpty()); assertNotNull(editor.state.value.suggestionsError)
        gate.complete(Unit); assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        assertTrue(editor.state.value.savedToList.isEmpty()); editor.saveToList(old); assertEquals(1, gateway.writes.size)
    }
    @Test fun `original producer cancellation reaches child before blocked sibling completes`() = runTest {
        val producer = Job(); val siblingGate = CompletableDeferred<Unit>(); val sibling = launch(producer) { withContext(NonCancellable) { siblingGate.await() } }; runCurrent()
        val gateway = Gateway(); var stopped = false; gateway.beforeSave = { try { awaitCancellation() } finally { stopped = true } }
        val editor = PostPlayEditor(target, gateway, onRated = {}, producer = producer); editor.load()
        val job = async { editor.saveToList(editor.state.value.suggestions.single()) }; runCurrent(); producer.cancel(); runCurrent()
        assertTrue(stopped); assertFalse(producer.isCompleted); assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        assertTrue(editor.state.value.savedToList.isEmpty()); siblingGate.complete(Unit); sibling.join()
    }
    @Test fun `accepted membership with refresh failure remains saved with explicit static refresh error`() = runTest {
        val gateway = Gateway().apply { beforeSave = { throw com.strmr.tv.library.WatchlistRefreshFailure() } }
        val editor = PostPlayEditor(target, gateway, onRated = {}); editor.load()
        editor.saveToList(editor.state.value.suggestions.single())
        assertTrue(editor.state.value.savedToList.isNotEmpty()); assertTrue(editor.state.value.listSaving.isEmpty())
        assertEquals("Change saved, but the watchlist could not be refreshed. Try refreshing it again.", editor.state.value.listError)
    }
    @Test fun `actual stale production gateway constructor rejects before credential or HTTP work`() {
        val expected = ProfileDataContext.current; ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        assertTrue(runCatching { ServerPostPlayGateway(target, expected) { "old-progress" } }.exceptionOrNull() is CancellationException)
        assertEquals(0, ApiClient.http.dispatcher.runningCallsCount()); assertEquals(0, ApiClient.http.dispatcher.queuedCallsCount())
    }
}
