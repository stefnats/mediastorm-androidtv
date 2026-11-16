package com.strmr.tv.library

import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.ui.AppToast
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Existing accepted-menu/page adapter used by actual TV and phone Hide choices. */
@OptIn(ExperimentalCoroutinesApi::class)
class HideMenuOriginTest {
    private val target = DetailsTarget("tmdb:movie:17", "Synthetic", true)
    @After fun after() { HiddenItemsStore.reset(); AppToast.clear() }
    @Test fun `replacement page reference rejects old queued choice even with equal values`() = runTest {
        data class Page(val id: Int)
        val original = Page(1); var current = original
        val origin = TitleListOrigin(target, ProfileDataContext.current, coroutineContext[Job]!!) { current === original }
        current = Page(1)
        assertNull(TitleListAction(origin, this) { error("No old write") }.accept())
    }
    @Test fun `normal accepted dismiss keeps original page action and success publication valid`() = runTest {
        val parent = coroutineContext[Job]!!; val origin = TitleListOrigin(target, ProfileDataContext.current, parent) { true }
        var dismissed = 0; var writes = 0; var navigation = 0
        val previous = TitleActions.changes
        val action = TitleListAction(origin, this) { op ->
            if (TitleActions.runOwned(op, "Hidden", "Failed") { op.requireTarget(target); writes++ }) op.publish { navigation++ }
        }
        val click = action.accept()!!
        action.commit(click) { origin.dismiss { dismissed++ } }!!.join()
        assertEquals(1, writes); assertEquals(1, navigation); assertEquals(1, dismissed)
        assertEquals(previous + 1, TitleActions.changes)
    }
    @Test fun `canceled phone hide animation never commits Hide choice`() = runTest {
        val origin = TitleListOrigin(target, ProfileDataContext.current, coroutineContext[Job]!!)
        var writes = 0; val action = TitleListAction(origin, this) { writes++ }; val click = action.accept()!!
        try { commitTitleListClick({ throw CancellationException("synthetic") }, click::abandon) { action.commit(click) {} }; fail("Hide canceled") } catch (_: CancellationException) { }
        runCurrent(); assertEquals(0, writes)
    }
    @Test fun `late accepted Hide after page replacement cannot toast change or close replacement`() = runTest {
        var selected = true; val origin = TitleListOrigin(target, ProfileDataContext.current, coroutineContext[Job]!!) { selected }
        val release = CompletableDeferred<Unit>(); var navigation = 0; val previous = TitleActions.changes
        val action = TitleListAction(origin, this) { op ->
            if (TitleActions.runOwned(op, "Hidden", "Failed") { withContext(NonCancellable) { release.await() } }) op.publish { navigation++ }
        }
        val click = action.accept()!!; val task = action.commit(click) { origin.dismiss {} }!!; runCurrent()
        selected = false; release.complete(Unit); task.join()
        assertEquals(0, navigation); assertEquals(previous, TitleActions.changes); assertNull(AppToast.message)
    }
    @Test fun `stale failure cannot overwrite current page toast or close it`() = runTest {
        val previous = TitleActions.changes; var selected = true; val operations = CustomListOperations(target = target, selected = { selected }); val operation = operations.begin()!!
        val release = CompletableDeferred<Unit>(); val task = async {
            TitleActions.runOwned(operation, "Hidden", "Failed") { release.await(); throw java.io.IOException("synthetic-private-diagnostic") }
        }
        runCurrent(); selected = false; AppToast.show("Current page"); release.complete(Unit); assertFalse(task.await())
        assertEquals("Current page", AppToast.message); assertEquals(previous, TitleActions.changes)
    }
}
