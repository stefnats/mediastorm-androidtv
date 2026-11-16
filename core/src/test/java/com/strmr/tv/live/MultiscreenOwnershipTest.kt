package com.strmr.tv.live

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.player.PlaybackSessionContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Actual production session queue/lifecycles, with deferred synthetic start/stop effects. */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiscreenOwnershipTest {
    private fun channel(id: String) = LiveChannel(id, "Synthetic $id", "https://example.invalid/$id")
    private class Transport : MultiscreenSession.Transport {
        val starts = mutableListOf<String>(); val stops = mutableListOf<String>()
        var reply: CompletableDeferred<LiveStream>? = null
        var error: Exception? = null; var failStop = false
        var id: String? = null
        override suspend fun start(channel: LiveChannel): LiveStream {
            starts += channel.id
            error?.let { throw it }
            return reply?.let { withContext(NonCancellable) { it.await() } } ?: LiveStream("https://example.invalid/stream", id ?: "session-${channel.id}")
        }
        override suspend fun stop(sessionId: String) {
            stops += sessionId
            if (failStop) throw IllegalStateException("Synthetic stop failure")
        }
    }
    private suspend fun canceled(job: Deferred<*>) { try { job.await(); fail("Retired operation survived") } catch (_: CancellationException) { } }
    @Test fun `queued original epoch cannot start after simulated A B A`() = runTest {
        val t = Transport(); val s = MultiscreenSession(t, backgroundScope)
        val start = s.tune(0, channel("old")); ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); runCurrent()
        start.await(); assertTrue(t.starts.isEmpty()); s.close(); assertTrue(t.stops.isEmpty())
    }
    @Test fun `late returned start after retire cleans known original ID and queue can close`() = runTest {
        val t = Transport().apply { reply = CompletableDeferred() }; val s = MultiscreenSession(t, backgroundScope)
        val start = s.tune(0, channel("old")); runCurrent(); s.retire()
        val close = async { s.close() }; t.reply!!.complete(LiveStream("https://example.invalid/old", "owned"))
        start.await(); close.await(); assertEquals(listOf("owned"), t.stops); assertTrue(s.state.value.slots.none { it.source != null })
    }
    @Test fun `local operation retirement leaves queue available for replacement and close`() = runTest {
        val t = Transport().apply { reply = CompletableDeferred() }; val s = MultiscreenSession(t, backgroundScope)
        val old = s.tune(0, channel("old")); runCurrent(); val replacement = s.tune(0, channel("new"))
        t.reply!!.complete(LiveStream("https://example.invalid/owned", "returned")); old.await(); replacement.await()
        assertEquals("new", s.state.value.slots[0].channel?.id); s.close(); assertEquals(2, t.starts.size)
    }
    @Test fun `parent cancellation settles pending queue results instead of hanging close`() = runTest {
        val parent = SupervisorJob(); val worker = CoroutineScope(coroutineContext + parent)
        val t = Transport().apply { reply = CompletableDeferred() }; val s = MultiscreenSession(t, worker)
        val old = s.tune(0, channel("old")); val queued = s.tune(1, channel("queued")); runCurrent()
        parent.cancel(); t.reply!!.complete(LiveStream("https://example.invalid/owned", "returned")); runCurrent()
        canceled(old); canceled(queued)
        val closing = async { s.close() }; runCurrent(); canceled(closing)
        assertEquals(listOf("old"), t.starts)
    }
    @Test fun `terminal first start prevents queued restore and every replacement attempt`() = runTest {
        val t = Transport().apply { error = ApiError.Http(HttpFailure(403, "{\"code\":\"kids_vod_access_denied\"}")) }
        val denied = mutableListOf<VodAccessDenial>(); val s = MultiscreenSession(t, backgroundScope, onDenied = { denied += it })
        val first = s.tune(0, channel("first")); val next = s.tune(1, channel("next")); first.await(); next.await()
        s.tune(0, channel("retry")).await(); s.tune(1, channel("replace")).await(); s.close()
        assertEquals(listOf("first"), t.starts); assertEquals(listOf(VodAccessDenial.BLOCKED), denied)
    }
    @Test fun `terminal keepalive retires all source actions and queue still releases owned IDs`() = runTest {
        val t = Transport(); val s = MultiscreenSession(t, backgroundScope); s.tune(0, channel("first")).await(); s.tune(1, channel("second")).await()
        val source = s.state.value.slots[0].source!!
        try { s.keepAlive(0, source) { _, _ -> throw ApiError.Http(HttpFailure(401, "{}")) }; fail() } catch (_: CancellationException) { }
        s.tune(1, channel("retry")).await(); s.close()
        assertEquals(listOf("first", "second"), t.starts); assertEquals(setOf("session-first", "session-second"), t.stops.toSet())
    }
    @Test fun `stale keepalive denial cannot retire newly tuned same-slot source`() = runTest {
        val t = Transport(); val s = MultiscreenSession(t, backgroundScope); s.tune(0, channel("old")).await()
        val old = s.state.value.slots[0].source!!; val release = CompletableDeferred<Unit>()
        val ping = async { s.keepAlive(0, old) { _, _ -> withContext(NonCancellable) { release.await() }; throw ApiError.Http(HttpFailure(403, "{}")) } }
        runCurrent(); s.tune(0, channel("new")).await(); release.complete(Unit); canceled(ping)
        assertTrue(s.isCurrent); assertEquals("new", s.state.value.slots[0].channel?.id)
    }
    @Test fun `same opaque ID cannot be adopted or stopped by another slot`() = runTest {
        val t = Transport().apply { id = "shared" }; val s = MultiscreenSession(t, backgroundScope)
        s.tune(0, channel("first")).await(); val first = s.state.value.slots[0].source!!
        s.tune(1, channel("second")).await(); assertNull(s.state.value.slots[1].source); assertSame(first, s.state.value.slots[0].source)
        s.remove(1).await(); assertTrue(t.stops.isEmpty()); s.close(); assertEquals(listOf("shared"), t.stops)
    }
    @Test fun `failed late orphan cleanup retains exact original ID for explicit exit retry`() = runTest {
        val t = Transport().apply { reply = CompletableDeferred(); failStop = true }; val s = MultiscreenSession(t, backgroundScope)
        val start = s.tune(0, channel("old")); runCurrent(); s.retire(); t.reply!!.complete(LiveStream("https://example.invalid/owned", "owned"))
        start.await(); assertEquals(listOf("owned"), t.stops)
        t.failStop = false; s.close(); assertEquals(listOf("owned", "owned"), t.stops)
    }
    @Test fun `old owner cleanup never uses a replacement epoch`() = runTest {
        val t = Transport(); val s = MultiscreenSession(t, backgroundScope); s.tune(0, channel("old")).await()
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); s.close(); assertTrue(t.stops.isEmpty())
    }
    @Test fun `retired tile callbacks cannot fail or deny a replacement`() = runTest {
        val t = Transport(); val s = MultiscreenSession(t, backgroundScope); s.tune(0, channel("old")).await(); val old = s.state.value.slots[0].source!!
        s.tune(0, channel("new")).await(); s.fail(0, old); s.deny(0, old, VodAccessDenial.BLOCKED)
        assertTrue(s.isCurrent); assertNull(s.state.value.slots[0].error); assertEquals("new", s.state.value.slots[0].channel?.id)
    }
    @Test fun `scheduling retune retires old tile callbacks before replacement start completes`() = runTest {
        val t = Transport(); val s = MultiscreenSession(t, backgroundScope); s.tune(0, channel("old")).await()
        val old = s.state.value.slots[0].source!!; t.reply = CompletableDeferred()
        val next = s.tune(0, channel("new")); assertFalse(s.matches(0, old)); s.deny(0, old, VodAccessDenial.BLOCKED)
        assertTrue(s.isCurrent); runCurrent(); t.reply!!.complete(LiveStream("https://example.invalid/new", "new-session")); next.await()
        assertEquals("new", s.state.value.slots[0].channel?.id); assertTrue(s.matches(0, s.state.value.slots[0].source!!))
    }

    @Test fun `queued remove immediately retires old callbacks while another slot start waits`() = runTest {
        val t = Transport(); val s = MultiscreenSession(t, backgroundScope); s.tune(0, channel("remove")).await()
        val old = s.state.value.slots[0].source!!; t.reply = CompletableDeferred()
        val other = s.tune(1, channel("keep")); runCurrent(); val removal = s.remove(0)
        assertFalse(s.matches(0, old)); s.deny(0, old, VodAccessDenial.BLOCKED); assertTrue(s.isCurrent)
        t.reply!!.complete(LiveStream("https://example.invalid/keep", "keep-session")); other.await(); removal.await()
        assertNull(s.state.value.slots[0].source); assertEquals("keep", s.state.value.slots[1].channel?.id)
        assertEquals(listOf("session-remove"), t.stops); assertTrue(s.matches(1, s.state.value.slots[1].source!!))
    }

}
