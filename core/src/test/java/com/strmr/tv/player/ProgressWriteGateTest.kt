package com.strmr.tv.player

import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** The gate consumed by ProgressReporter; no ExoPlayer or Android send is instantiated. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressWriteGateTest {
    @Test fun `normal noncancelable closing report can finish after its parent is stopped`() = runTest {
        val gate = ProgressWriteGate { true }; val reply = CompletableDeferred<Unit>(); var published = 0
        val job = launch { withContext(NonCancellable) { gate.write { reply.await(); gate.use { published++ } } } }
        runCurrent(); job.cancel(); reply.complete(Unit); job.join()
        assertEquals(1, published); assertTrue(gate.accepts)
    }
    @Test fun `discard before queued report performs no write or publication`() = runTest {
        val gate = ProgressWriteGate { true }; var writes = 0
        val job = async { withContext(NonCancellable) { gate.write { writes++ } } }
        gate.discard(); runCurrent(); assertNull(job.await()); assertEquals(0, writes)
    }
    @Test fun `discard cancels registered transport even under NonCancellable parent`() = runTest {
        val gate = ProgressWriteGate { true }; var canceled = 0; var published = 0
        val job = async { withContext(NonCancellable) { gate.write {
            suspendCancellableCoroutine<Unit> { it.invokeOnCancellation { canceled++ } }
            gate.use { published++ }
        } } }
        runCurrent(); gate.discard(); runCurrent()
        assertTrue(job.isCancelled); assertEquals(1, canceled); assertEquals(0, published); assertFalse(gate.accepts)
    }
    @Test fun `masked late response cannot publish saved or migration state after discard`() = runTest {
        val gate = ProgressWriteGate { true }; val reply = CompletableDeferred<Unit>(); var published = 0
        val job = async { withContext(NonCancellable) { gate.write {
            withContext(NonCancellable) { reply.await() }
            gate.use { published++ }
        } } }
        runCurrent(); gate.discard(); reply.complete(Unit); runCurrent()
        assertTrue(job.isCancelled); assertEquals(0, published)
    }
    @Test fun `original epoch cannot report after simulated A B A retirement`() = runTest {
        val epoch = ProfileDataContext.current; val gate = ProgressWriteGate { epoch == ProfileDataContext.current }; var writes = 0
        val job = async { gate.write { writes++ } }
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); runCurrent()
        assertNull(job.await()); assertEquals(0, writes)
    }
    @Test fun `late ordinary failure cannot publish offline fallback after discard`() = runTest {
        val gate = ProgressWriteGate { true }; val reply = CompletableDeferred<Unit>(); var fallback = 0
        val job = launch { withContext(NonCancellable) {
            try { gate.write { withContext(NonCancellable) { reply.await() }; error("Synthetic failure") } }
            catch (_: Exception) { gate.use { fallback++ } }
        } }
        runCurrent(); gate.discard(); reply.complete(Unit); job.join(); assertEquals(0, fallback)
    }
}
