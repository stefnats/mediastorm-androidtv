package com.strmr.tv.player

import com.strmr.tv.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class ReleaseReplacementBufferTest {
    private class Gateway : ReleaseReportGateway {
        var current = true; var posts = 0; var statuses = 0; var cancels = 0; var options = 0
        override fun isCurrent() = current
        override suspend fun options(id: String): ReleaseReportOptions {
            options++; return ReleaseReportOptions("synthetic-version", audioLanguage = "fra")
        }
        override suspend fun submit(id: String, reporting: Boolean, body: JsonObject): ReleaseReportReply {
            posts++; return ReleaseReportReply(replacement = PrequeueStartResponse("candidate"))
        }
        override suspend fun status(id: String): PrequeueStatus {
            statuses++; return PrequeueStatus(id, "ready", streamPath = "/synthetic/candidate.mp4", duration = 600.0)
        }
        override suspend fun cancel(id: String) { cancels++ }
        override suspend fun undo(id: String) { }
    }
    private class Loaded(override val targetMs: Long) : PreparedReleaseBuffer {
        var ready = true; var closes = 0; var closeCalls = 0
        var join: CompletableDeferred<Unit>? = null
        private val closing = AtomicBoolean(false)
        private val closed = CompletableDeferred<Unit>()
        override fun isReady() = ready
        override suspend fun closeAndJoin() {
            closeCalls++
            withContext(NonCancellable) {
                if (closing.compareAndSet(false, true)) {
                    closes++; ready = false
                    try {
                        join?.await()
                        closed.complete(Unit)
                    } catch (error: Throwable) {
                        closed.completeExceptionally(error)
                        throw error
                    }
                }
                closed.await()
            }
        }
    }
    private class Fixture(val scope: TestScope) {
        val gateway = Gateway(); var position = 42_000L; var adopted = 0; var adoptedPosition: Long? = null
        val loaded = mutableListOf<Loaded>(); val deadlines = mutableListOf<Long>()
        var prepare: suspend (PrequeueStatus, Long, Long) -> PreparedReleaseBuffer = { _, target, deadline ->
            deadlines += deadline; Loaded(target).also { loaded += it }
        }
        val flow = ReleaseReports("original", gateway, scope.backgroundScope, { position },
            { _, _, _ -> fail("Actual local flow must never use unbuffered adoption") }, clientId = "synthetic-device",
            prepareLocal = { status, target, deadline -> prepare(status, target, deadline) },
            adoptLocal = { _, target, _, handle -> assertTrue(handle.isReady()); adopted++; adoptedPosition = target },
            monotonicNanos = { scope.testScheduler.currentTime * 1_000_000 })
        suspend fun start() { flow.load(); flow.submit(false, true); scope.runCurrent() }
        suspend fun close() { flow.close(); scope.runCurrent() }
    }
    @Test fun existing_prequeue_is_not_duplicated() = runTest {
        val f = Fixture(this); f.start(); repeat(3) { f.flow.submit(false, true) }; runCurrent()
        assertEquals(1, f.gateway.posts); assertEquals(1, f.gateway.statuses); assertEquals(1, f.loaded.size)
        assertEquals(0, f.adopted); f.close()
    }
    @Test fun automatic_audio_preparation_keeps_primary_running() = runTest {
        val f = Fixture(this); val recovery = AudioRecoveryAssistant(backgroundScope, { f.flow }, { true })
        assertTrue(recovery.suggest(AudioRecoveryHint.DECODER)); runCurrent()
        assertEquals(1, f.gateway.posts); assertEquals(1, f.loaded.size); assertEquals(0, f.adopted)
        f.position = 94_000; f.flow.take(); assertEquals(32_000L, f.adoptedPosition)
        assertEquals(1, f.adopted); recovery.dismiss(); runCurrent(); assertEquals(0, f.gateway.cancels)
    }
    @Test fun silence_does_not_request_or_buffer_alternative() = runTest {
        val f = Fixture(this); val recovery = AudioRecoveryAssistant(backgroundScope, { f.flow }, { true })
        assertTrue(recovery.suggest(AudioRecoveryHint.SILENCE)); runCurrent()
        assertEquals(0, f.gateway.options); assertEquals(0, f.gateway.posts); assertTrue(f.loaded.isEmpty())
        recovery.dismiss(); runCurrent()
    }
    @Test fun range_failure_retains_primary_and_original_choice() = runTest {
        val f = Fixture(this); f.prepare = { _, _, _ -> error("Synthetic local decoder failure") }; f.start()
        assertNull(f.flow.state.value.ready); assertNotNull(f.flow.state.value.options); assertFalse(f.flow.state.value.busy)
        f.flow.take(); assertEquals(0, f.adopted); assertEquals(0, f.gateway.cancels)
        f.close(); assertEquals(1, f.gateway.cancels)
    }
    @Test fun wrong_content_forces_zero_frozen_target() = runTest {
        val f = Fixture(this); f.flow.load(); f.flow.choose(ReleaseProblem.WRONG_TITLE); f.flow.submit(true, true); runCurrent()
        assertEquals(0L, f.loaded.single().targetMs); f.position = 60_000; f.flow.take()
        assertEquals(0L, f.adoptedPosition); assertEquals(1, f.adopted)
    }
    @Test fun resume_mode_change_cancels_before_new_target() = runTest {
        val f = Fixture(this); f.start(); val old = f.loaded.single(); old.join = CompletableDeferred()
        f.position = 65_000; f.flow.resume(ReleaseResume.CURRENT); runCurrent()
        assertEquals(1, old.closes); assertEquals(1, f.loaded.size); assertNull(f.flow.state.value.ready)
        old.join!!.complete(Unit); runCurrent()
        assertEquals(listOf(32_000L, 65_000L), f.loaded.map { it.targetMs })
        assertEquals(f.deadlines[0], f.deadlines[1]); f.close()
    }
    @Test fun take_waits_actual_buffer_and_decoder_readiness() = runTest {
        val f = Fixture(this); val sourceLoadedOnly = CompletableDeferred<PreparedReleaseBuffer>()
        f.prepare = { _, _, _ -> sourceLoadedOnly.await() }; f.start()
        f.flow.take(); assertEquals(0, f.adopted); assertNull(f.flow.state.value.ready)
        val decoded = Loaded(32_000); sourceLoadedOnly.complete(decoded); runCurrent()
        assertNotNull(f.flow.state.value.ready); f.flow.take(); f.flow.take(); assertEquals(1, f.adopted)
    }
    @Test fun cancel_or_late_callback_never_adopts_or_refunds_twice() = runTest {
        val f = Fixture(this); val result = CompletableDeferred<PreparedReleaseBuffer>(); val late = Loaded(32_000)
        f.prepare = { _, _, _ ->
            // As with the concrete Media3 preparer, the provider owns a late object until
            // a successful handoff; cancellation at a coroutine boundary cannot orphan it.
            try { withContext(NonCancellable) { result.await() } }
            finally { if (!currentCoroutineContext().isActive) withContext(NonCancellable) { late.closeAndJoin() } }
        }; f.start()
        f.flow.close(); runCurrent(); assertEquals(0, f.adopted)
        result.complete(late); runCurrent(); f.flow.take(); f.flow.close(); runCurrent()
        assertEquals(0, f.adopted); assertEquals(1, late.closes); assertEquals(1, f.gateway.cancels)
        assertEquals(2, late.closeCalls)
    }
}
