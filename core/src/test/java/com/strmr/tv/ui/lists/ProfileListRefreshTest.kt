package com.strmr.tv.ui.lists

import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Actual button/effect adapter consumed by TV/phone PersonalArea and phone Lists. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileListRefreshTest {
    @Test fun `queued old entry uses original epoch instead of latest state getter`() = runTest {
        val parent = Job(backgroundScope.coroutineContext[Job]); val refresh = ProfileListRefresh(ProfileDataContext.current, parent); var reads = 0
        val old = launch { refresh.refresh { reads++ } }; ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); old.join()
        assertEquals(0, reads); assertFalse(refresh.refreshing)
    }
    @Test fun `normal error ends busy visibly and next explicit refresh clears error`() = runTest {
        val parent = Job(backgroundScope.coroutineContext[Job]); val expected = ProfileDataContext.current; val refresh = ProfileListRefresh(expected, parent)
        refresh.refresh { assertEquals(expected, it); throw java.io.IOException("private") }
        assertFalse(refresh.refreshing); assertTrue(refresh.failed)
        refresh.refresh { assertEquals(expected, it) }; assertFalse(refresh.failed)
    }
    @Test fun `retired entry cannot publish late failure or clear replacement busy`() = runTest {
        val parent = Job(backgroundScope.coroutineContext[Job]); val old = ProfileListRefresh(ProfileDataContext.current, parent); val oldGate = CompletableDeferred<Unit>()
        val before = launch { old.refresh { withContext(NonCancellable) { oldGate.await(); throw java.io.IOException("late") } } }; runCurrent(); old.close()
        val next = ProfileListRefresh(ProfileDataContext.current, parent); val gate = CompletableDeferred<Unit>()
        val after = launch { next.refresh { gate.await() } }; runCurrent(); oldGate.complete(Unit); before.join()
        assertTrue(next.refreshing); assertFalse(next.failed); assertFalse(old.failed); gate.complete(Unit); after.join(); assertFalse(next.refreshing)
    }
    @Test fun `producer cancellation reaches owned child before noncancellable sibling completes`() = runTest {
        val parent = Job(); val refresh = ProfileListRefresh(ProfileDataContext.current, parent); val hold = CompletableDeferred<Unit>(); var canceled = false
        val sibling = backgroundScope.launch(parent) { withContext(NonCancellable) { hold.await() } }
        val work = launch { refresh.refresh { try { awaitCancellation() } finally { canceled = true } } }; runCurrent()
        parent.cancel(); runCurrent(); assertTrue(canceled); assertFalse(parent.isCompleted); assertFalse(refresh.refreshing)
        hold.complete(Unit); sibling.join(); work.join()
    }
    @Test fun `same-owner caller cancellation permits a new refresh`() = runTest {
        val parent = Job(backgroundScope.coroutineContext[Job]); val refresh = ProfileListRefresh(ProfileDataContext.current, parent)
        val first = launch { refresh.refresh { awaitCancellation() } }; runCurrent(); first.cancelAndJoin()
        var reads = 0; refresh.refresh { reads++ }; assertEquals(1, reads)
    }
}
