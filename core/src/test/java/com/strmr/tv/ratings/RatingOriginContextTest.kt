package com.strmr.tv.ratings

import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.library.*
import com.strmr.tv.ui.TitleRatingPrompt
import com.strmr.tv.ui.originalRatingAction
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RatingOriginContextTest {
    private val target = DetailsTarget("tmdb:movie:7", "Synthetic", true)
    private class Gateway : TitleRatingGateway {
        var reads = 0
        var writes = 0
        var current = true
        var read: suspend () -> Unit = {}
        var write: suspend () -> Unit = {}
        override fun isCurrent() = current
        override suspend fun read(target: DetailsTarget): TitleRatingReply {
            reads++; read(); return TitleRatingReply("none")
        }
        override suspend fun write(target: DetailsTarget, rating: PersonalRating): TitleRatingReply {
            writes++; write(); return TitleRatingReply(rating.wire)
        }
    }
    @Test fun `accepted normal dismissal opens an editor retaining original parent and selection`() = runTest {
        val parent = Job(); val gateway = Gateway(); var editor: TitleRatingEditor? = null
        val origin = TitleListOrigin(target, ProfileDataContext.current, parent)
        val action = originalRatingAction(target, origin, backgroundScope, { gateway }, { editor = it })
        action.commit(action.accept()!!) { origin.close() }!!.join()
        assertFalse(origin.isCurrent); assertNotNull(editor)
        editor!!.load(); editor!!.save(PersonalRating.LOVE)
        assertEquals(1, gateway.reads); assertEquals(1, gateway.writes)
        parent.cancel(); assertFalse(editor!!.isCurrent())
    }
    @Test fun `held hide from an equal-profile old page cannot open after page replacement`() = runTest {
        val parent = Job(); var selected = true; var opened = 0; var gateways = 0
        val origin = TitleListOrigin(target, ProfileDataContext.current, parent) { selected }
        val action = originalRatingAction(target, origin, backgroundScope, { gateways++; Gateway() }, { opened++ })
        val click = action.accept()!!; val hidden = CompletableDeferred<Unit>()
        val pending = launch { commitTitleListClick({ hidden.await() }, click::abandon) { action.commit(click) { origin.close() } } }
        runCurrent(); selected = false; hidden.complete(Unit); pending.join()
        assertEquals(0, opened); assertEquals(0, gateways); parent.cancel()
    }
    @Test fun `cancelled sheet hide never opens or consumes a gateway`() = runTest {
        val parent = Job(); var opened = 0; var gateways = 0
        val origin = TitleListOrigin(target, ProfileDataContext.current, parent)
        val action = originalRatingAction(target, origin, backgroundScope, { gateways++; Gateway() }, { opened++ })
        val click = action.accept()!!
        val result = runCatching { commitTitleListClick({ throw CancellationException() }, click::abandon) { action.commit(click) {} } }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(0, opened); assertEquals(0, gateways); parent.cancel()
    }
    @Test fun `equal-valued replacement target cannot consume the original selection receipt`() = runTest {
        val parent = Job(); var opened = 0; var gateways = 0
        val origin = TitleListOrigin(target, ProfileDataContext.current, parent)
        val failure = CompletableDeferred<Throwable>()
        val host = CoroutineScope(coroutineContext + Job() + CoroutineExceptionHandler { _, error -> failure.complete(error) })
        val actual = originalRatingAction(target.copy(), origin, host, { gateways++; Gateway() }, { opened++ })
        actual.commit(actual.accept()!!) { origin.close() }!!.join()
        assertTrue(failure.await() is IllegalArgumentException)
        assertEquals(0, opened); assertEquals(0, gateways)
        host.cancel(); parent.cancel()
    }
    @Test fun `queued A B A and retired producer reject before constructing any current gateway`() = runTest {
        val parent = Job(); var opened = 0; var gateways = 0
        val origin = TitleListOrigin(target, ProfileDataContext.current, parent)
        val action = originalRatingAction(target, origin, backgroundScope, { gateways++; Gateway() }, { opened++ })
        val click = action.accept()!!
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        assertNull(action.commit(click) {})
        assertEquals(0, gateways); assertEquals(0, opened); parent.cancel()
    }
    @Test fun `opened editor cannot read or write after original same-profile page retires`() = runTest {
        val parent = Job(); var selected = true; var editor: TitleRatingEditor? = null
        val gateway = Gateway(); val origin = TitleListOrigin(target, ProfileDataContext.current, parent) { selected }
        val action = originalRatingAction(target, origin, backgroundScope, { gateway }, { editor = it })
        action.commit(action.accept()!!) { origin.close() }!!.join()
        editor!!.load(); selected = false
        assertTrue(runCatching { editor!!.save(PersonalRating.LOVE) }.exceptionOrNull() is CancellationException)
        assertFalse(editor!!.isCurrent()); assertEquals(1, gateway.reads); assertEquals(0, gateway.writes); parent.cancel()
    }
    @Test fun `stale Done and retirement effects leave a replacement prompt intact`() = runTest {
        val a = TitleRatingEditor(target, Gateway()); val b = TitleRatingEditor(target.copy(), Gateway())
        TitleRatingPrompt.open(a); TitleRatingPrompt.open(b)
        assertFalse(a.isCurrent()); TitleRatingPrompt.dismiss(a)
        assertSame(b, TitleRatingPrompt.editor); assertTrue(b.isCurrent())
        TitleRatingPrompt.dismiss(b); assertNull(TitleRatingPrompt.editor)
    }
    @Test fun `parent cancellation reaches accepted editor child before noncancellable sibling completes`() = runTest {
        val parent = Job(); val held = CompletableDeferred<Unit>()
        val sibling = launch(parent) { withContext(NonCancellable) { held.await() } }; runCurrent()
        val gateway = Gateway(); var cancelled = false
        gateway.read = { try { awaitCancellation() } finally { cancelled = true } }
        val editor = TitleRatingEditor(target, gateway, parent = parent)
        val read = async(NonCancellable) { runCatching { editor.load() }.exceptionOrNull() }; runCurrent()
        parent.cancel(); runCurrent()
        assertTrue(cancelled); assertFalse(parent.isCompleted)
        assertTrue(read.await() is CancellationException); assertNull(editor.state.value.rating)
        held.complete(Unit); sibling.join()
    }
    @Test fun `masked reply after close cannot publish or emit rating changes`() = runTest {
        val gateway = Gateway(); val reply = CompletableDeferred<Unit>(); var changed = 0
        val editor = TitleRatingEditor(target, gateway, onSaved = { changed++ })
        editor.load(); gateway.write = { withContext(NonCancellable) { reply.await() } }
        val write = async { runCatching { editor.save(PersonalRating.LOVE) }.exceptionOrNull() }; runCurrent()
        editor.close(); reply.complete(Unit)
        assertTrue(write.await() is CancellationException); assertEquals(0, changed)
        assertEquals(PersonalRating.NONE, editor.state.value.rating)
    }
}
