package com.strmr.tv.library

import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.ui.AppToast
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Actual TitleActions owned publication adapter, with no Android AppLog/API execution. */
@OptIn(ExperimentalCoroutinesApi::class)
class TitleActionsOwnedTest {
    @Before fun before() { ProfileDataContext.invalidate(); AppToast.clear() }
    @After fun after() { AppToast.clear(); ProfileDataContext.invalidate() }
    private fun operation() = CustomListOperations(target = DetailsTarget("tmdb:movie:17", "Synthetic", true)).begin()!!
    @Test fun `success publishes actual changes and static toast only for original operation`() = runTest {
        val previous = TitleActions.changes; var writes = 0
        assertTrue(TitleActions.runOwned(operation(), "Synthetic success", "Synthetic failure") { writes++ })
        assertEquals(1, writes); assertEquals(previous + 1, TitleActions.changes); assertEquals("Synthetic success", AppToast.message)
    }
    @Test fun `late success after A B A does not publish changes or toast`() = runTest {
        val previous = TitleActions.changes; val reply = CompletableDeferred<Unit>(); val operation = operation()
        val task = async { TitleActions.runOwned(operation, "Synthetic success", "Synthetic failure") { reply.await() } }
        runCurrent(); ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); reply.complete(Unit)
        assertFalse(task.await()); assertEquals(previous, TitleActions.changes); assertNull(AppToast.message)
    }
    @Test fun `late failure after old dialog replacement does not overwrite current toast`() = runTest {
        val previous = TitleActions.changes; val reply = CompletableDeferred<Unit>()
        val operations = CustomListOperations(); val old = operations.begin("one")!!
        val task = async { TitleActions.runOwned(old, "Synthetic success", "Synthetic failure") { reply.await(); throw java.io.IOException("private synthetic diagnostic") } }
        runCurrent(); operations.begin("one"); AppToast.show("Replacement toast"); reply.complete(Unit)
        assertFalse(task.await()); assertEquals(previous, TitleActions.changes); assertEquals("Replacement toast", AppToast.message)
    }
    @Test fun `ordinary current failure reports static text without leaking server diagnostics`() = runTest {
        val previous = TitleActions.changes
        assertFalse(TitleActions.runOwned(operation(), "Synthetic success", "Synthetic failure") { throw java.io.IOException("https://synthetic.invalid/private") })
        assertEquals(previous, TitleActions.changes); assertEquals("Synthetic failure", AppToast.message)
    }
    @Test fun `cancellation remains cancellation and produces no failure toast`() = runTest {
        val previous = TitleActions.changes
        try { TitleActions.runOwned(operation(), "Synthetic success", "Synthetic failure") { throw CancellationException("Synthetic") }; fail("Cancellation hidden") }
        catch (_: CancellationException) { }
        assertEquals(previous, TitleActions.changes); assertNull(AppToast.message)
    }
}
