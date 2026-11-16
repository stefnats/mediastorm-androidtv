package com.strmr.tv.ui.home

import com.strmr.tv.library.ProfileDataContext
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

/** Actual factory/state; queued Android Home work is deliberately not executed. */
class HomeStateEpochTest {
    private class Queued : CoroutineDispatcher() {
        val work = mutableListOf<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { work += block }
    }
    @Test fun `same epoch keeps store and focus while new epoch clears both`() {
        val dispatcher = Queued(); val scope = CoroutineScope(dispatcher + SupervisorJob())
        try {
            val state = HomeState(scope)
            val first = state.store("all"); val focus = state.page("all")
            assertSame(first, state.store("all")); assertSame(focus, state.page("all"))
            ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
            val next = state.store("all")
            assertNotSame(first, next); assertNotSame(focus, state.page("all")); assertEquals(ProfileDataContext.current, next.dataContext)
            assertTrue(dispatcher.work.isNotEmpty())
        } finally { scope.cancel() }
    }
    @Test fun `different views share epoch but keep distinct stores and memories`() {
        val scope = CoroutineScope(Queued() + SupervisorJob())
        try {
            val state = HomeState(scope)
            assertNotSame(state.store("all"), state.store("movies")); assertNotSame(state.page("all"), state.page("movies"))
        } finally { scope.cancel() }
    }
}
