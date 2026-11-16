package com.strmr.tv.library

import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.home.HomeCard
import com.strmr.tv.home.HomeLink
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Actual menu selection, hide and accepted-click adapters; no Compose/Android execution is claimed. */
@OptIn(ExperimentalCoroutinesApi::class)
class TitleListOriginTest {
    private val target = DetailsTarget("tmdb:movie:17", "Synthetic", true)
    @Before fun before() { ProfileDataContext.invalidate(); CustomListsStore.reset() }
    @After fun after() { CustomListsStore.reset(); ProfileDataContext.invalidate() }
    private fun origin(parent: Job) = TitleListOrigin(target, ProfileDataContext.current, parent)

    @Test fun `selection captured before deferred composition cannot become owner after A B A`() = runTest {
        val card = HomeCard(target.titleId, HomeLink.Details(target), target.name, "")
        val selection = TitleMenuSelection(card, ProfileDataContext.current, coroutineContext[Job]!!, true)
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        assertFalse(selection.isCurrent); assertNull(selection.origin.accept(this)); assertSame(target, selection.origin.target)
    }
    @Test fun `accepted click survives intended synchronous menu dismissal under original parent`() = runTest {
        val original = origin(coroutineContext[Job]!!); var dismissals = 0; var writes = 0
        val action = TitleListAction(original, this) { it.requireTarget(target); writes++ }
        val click = action.accept()!!
        val job = action.commit(click) { original.dismiss { dismissals++ } }!!
        assertFalse(original.isCurrent); job.join(); assertEquals(1, writes); assertEquals(1, dismissals)
    }
    @Test fun `actual dismiss helper synchronously retires unchosen queued callbacks before any compose disposal`() = runTest {
        val original = origin(coroutineContext[Job]!!); var dismissals = 0
        assertTrue(original.dismiss { dismissals++ }); assertFalse(original.dismiss { dismissals++ })
        assertNull(original.accept(this)); assertEquals(1, dismissals)
    }
    @Test fun `one menu intent accepts only one click while hide is pending`() = runTest {
        val original = origin(coroutineContext[Job]!!)
        assertNotNull(original.accept(this)); assertNull(original.accept(this))
    }
    @Test fun `same accepted receipt cannot dismiss or dispatch twice`() = runTest {
        val original = origin(coroutineContext[Job]!!); val click = original.accept(this)!!; var writes = 0; var dismissals = 0
        click.commit({ original.dismiss { dismissals++ } }) { writes++ }!!.join()
        assertNull(click.commit({ dismissals++ }) { writes++ }); assertEquals(1, writes); assertEquals(1, dismissals)
    }
    @Test fun `cancelled actual hide adapter cannot dismiss or run action`() = runTest {
        val original = origin(coroutineContext[Job]!!); val click = original.accept(this)!!; var effects = 0
        val hiding = launch { commitTitleListClick(hide = { awaitCancellation() }, abandon = click::abandon) { click.commit({ effects++ }) { effects++ } } }
        runCurrent(); hiding.cancelAndJoin(); assertEquals(0, effects)
        assertNotNull(original.accept(this)) // A still-visible menu permits an explicit retry.
    }
    @Test fun `masked late hide reply after cancellation still has no action`() = runTest {
        val original = origin(coroutineContext[Job]!!); val click = original.accept(this)!!; val reply = CompletableDeferred<Unit>(); var effects = 0
        val hiding = launch { commitTitleListClick(hide = { withContext(NonCancellable) { reply.await() } }, abandon = click::abandon) { click.commit({ effects++ }) { effects++ } } }
        runCurrent(); hiding.cancel(); reply.complete(Unit); hiding.join(); assertEquals(0, effects)
    }
    @Test fun `ordinary hide failure abandons selection without turning failure into action`() = runTest {
        val original = origin(coroutineContext[Job]!!); val click = original.accept(this)!!; var effects = 0
        try { commitTitleListClick(hide = { throw IllegalStateException("Synthetic") }, abandon = click::abandon) { effects++ }; fail("Failure swallowed") }
        catch (_: IllegalStateException) { }
        assertEquals(0, effects); assertNotNull(original.accept(this))
    }
    @Test fun `Back or replacement before hide completion cancels pending original claim`() = runTest {
        val original = origin(coroutineContext[Job]!!); val click = original.accept(this)!!; val reply = CompletableDeferred<Unit>(); var effects = 0
        val hiding = launch { commitTitleListClick(hide = { reply.await() }, abandon = click::abandon) { click.commit({ effects++ }) { effects++ } } }
        runCurrent(); original.close(); reply.complete(Unit); hiding.join(); assertEquals(0, effects)
    }
    @Test fun `profile retirement while hiding cannot dispatch with replacement identity`() = runTest {
        val original = origin(coroutineContext[Job]!!); val click = original.accept(this)!!; var effects = 0
        commitTitleListClick(hide = { ProfileDataContext.invalidate(); ProfileDataContext.invalidate() }, abandon = click::abandon) {
            assertNull(click.commit({ effects++ }) { effects++ })
        }
        assertEquals(0, effects)
    }
    @Test fun `dead producer cannot open menu or dispatch into still-alive global host`() = runTest {
        val parent = Job(); val original = origin(parent); parent.cancel()
        assertFalse(original.isCurrent); assertNull(original.accept(this))
    }
    @Test fun `producer retirement cancels already accepted host child and following stages`() = runTest {
        val parent = Job(); val original = origin(parent); var cancelled = false; var publications = 0
        val click = original.accept(this)!!
        val job = click.commit({ original.close() }) { operation ->
            try { awaitCancellation() } finally { cancelled = true; operation.publish { publications++ } }
        }!!
        runCurrent(); parent.cancel(); job.join(); assertTrue(cancelled); assertTrue(job.isCancelled); assertEquals(0, publications)
    }
    @Test fun `masked accepted reply after producer close cannot publish or create followup`() = runTest {
        val parent = Job(); val original = origin(parent); val reply = CompletableDeferred<Unit>(); var effects = 0
        val job = original.accept(this)!!.commit({ original.close() }) { operation ->
            checkedListStage(operation.owner, operation) { withContext(NonCancellable) { reply.await() } }
            effects++
        }!!
        runCurrent(); parent.cancel(); reply.complete(Unit); job.join(); assertEquals(0, effects)
    }
    @Test fun `accepted picker uses original target and owner despite normal menu close`() = runTest {
        val original = origin(coroutineContext[Job]!!)
        val job = original.accept(this)!!.commit({ original.close() }) { CustomListsStore.openPicking(target, it) }!!
        job.join(); assertSame(target, CustomListsStore.picking); assertEquals(ProfileDataContext.current, CustomListsStore.pickingOwner(target)!!.epoch)
    }
    @Test fun `same-valued replacement target cannot rebind accepted action receipt`() = runTest {
        val original = origin(coroutineContext[Job]!!); var rejected = false
        original.accept(this)!!.commit({ original.close() }) {
            try { CustomListsStore.openPicking(target.copy(), it) } catch (_: IllegalArgumentException) { rejected = true }
        }!!.join()
        assertTrue(rejected); assertNull(CustomListsStore.picking)
    }
    @Test fun `caller cancellation remains cancellation through actual animation adapter`() = runTest {
        var committed = false
        val job = launch { commitTitleListClick(hide = { awaitCancellation() }) { committed = true } }
        runCurrent(); job.cancelAndJoin(); assertFalse(committed); assertTrue(job.isCancelled)
    }
    @Test fun `accepted child cancels at producer cancellation start before another child completes`() = runTest {
        val parent = Job(); val gate = CompletableDeferred<Unit>()
        val producer = CoroutineScope(coroutineContext + parent)
        val sibling = producer.launch { withContext(NonCancellable) { gate.await() } }
        val original = origin(parent); var cancelled = false
        val work = original.accept(this)!!.commit({ original.close() }) {
            try { awaitCancellation() } finally { cancelled = true }
        }!!
        runCurrent(); parent.cancel(); runCurrent()
        assertFalse(parent.isCompleted); assertTrue(cancelled); assertTrue(work.isCompleted); assertTrue(work.isCancelled)
        gate.complete(Unit); sibling.join(); work.join(); parent.join()
    }

}
