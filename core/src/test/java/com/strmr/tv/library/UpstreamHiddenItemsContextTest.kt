package com.strmr.tv.library

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpstreamHiddenItemsContextTest {
    @Test fun delayedListCannotPublishAfterProfileChanges() = runTest {
        val original = ProfileDataContext.current
        val reply = CompletableDeferred<Unit>()
        val model = HiddenItemsSettings(original) { expected ->
            assertEquals(original, expected)
            withContext(NonCancellable) { reply.await() }
            listOf(HiddenItem("old-profile-item"))
        }
        val job = backgroundScope.launch { model.loadContinueWatching() }
        runCurrent()
        ProfileDataContext.invalidate()
        reply.complete(Unit)
        runCurrent()
        assertTrue(job.isCancelled)
        assertTrue(model.continueItems.isEmpty())
        model.close()
    }

    @Test fun closingOriginalPageRejectsLateSameProfileReply() = runTest {
        val reply = CompletableDeferred<Unit>()
        val model = HiddenItemsSettings(ProfileDataContext.current) {
            withContext(NonCancellable) { reply.await() }
            listOf(HiddenItem("late-item"))
        }
        val job = backgroundScope.launch { model.loadContinueWatching() }
        runCurrent()
        model.close()
        reply.complete(Unit)
        runCurrent()
        assertTrue(job.isCancelled)
        assertTrue(model.continueItems.isEmpty())
    }

    @Test fun olderListCannotReplaceNewerAcceptedList() = runTest {
        var calls = 0
        val oldReply = CompletableDeferred<Unit>()
        val model = HiddenItemsSettings(ProfileDataContext.current) {
            if (++calls == 1) {
                oldReply.await()
                listOf(HiddenItem("stale"))
            } else listOf(HiddenItem("current"))
        }
        val old = backgroundScope.launch { model.loadContinueWatching() }
        runCurrent()
        model.loadContinueWatching()
        oldReply.complete(Unit)
        old.join()
        assertEquals("current", model.continueItems.single().id)
        model.close()
    }
}
