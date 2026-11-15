package com.strmr.tv.live

import com.strmr.tv.api.ApiError
import com.strmr.tv.api.ApiJson
import com.strmr.tv.api.HttpFailure
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingSchedulingTest {
    private fun draft(epg: Boolean = true) = RecordingDraft(7, "synthetic-profile", "channel", "Synthetic channel", "https://fixture.example/live.ts",
        "Synthetic programme", "2026-11-16T19:00:00Z", "2026-11-16T20:00:00Z", epg = epg)
    private fun item(state: String = "conflict", limit: Int = 1, peak: Int = 2) = RecordingPlanItem(state = state,
        start = "2026-11-16T18:55:00Z", end = "2026-11-16T20:05:00Z", maxStreams = limit, peakScheduledConnections = peak)
    private fun clear() = RecordingPlan(candidate = item("clear", 2, 1))

    private inner class Fixture(val test: TestScope) {
        val contexts = MutableStateFlow(7L)
        val previews = mutableListOf<RecordingDraft>()
        val creations = mutableListOf<RecordingDraft>()
        val notices = mutableListOf<String>()
        var refreshes = 0
        var response: suspend (RecordingDraft) -> RecordingPlan = { RecordingPlan(candidate = item()) }
        var creating: suspend (RecordingDraft) -> Unit = {}
        val controller = RecordingScheduling(test.backgroundScope, contexts,
            { previews += it; response(it) }, { creations += it; creating(it) }, notices::add, { refreshes++ }, { test.testScheduler.currentTime })
        fun prompt() = requireNotNull(controller.prompt.value)
        fun answer(choice: RecordingPlanChoice) = controller.answer(prompt(), choice)
    }

    @Test fun `a known clear forecast creates the exact draft once`() = runTest {
        val f = Fixture(this); f.response = { clear() }; val d = draft()
        f.controller.schedule(d); runCurrent()
        assertEquals(listOf(d), f.previews); assertEquals(listOf(d), f.creations)
        assertEquals(1, f.refreshes); assertNull(f.controller.prompt.value)
    }
    @Test fun `a conflict requires an explicit choice`() = runTest {
        val f = Fixture(this); f.controller.schedule(draft()); runCurrent()
        assertTrue(f.creations.isEmpty()); assertTrue(f.prompt().message.contains("No connection is reserved"))
        f.answer(RecordingPlanChoice.SCHEDULE); runCurrent()
        assertEquals(1, f.creations.size); assertEquals(1, f.refreshes)
    }
    @Test fun `cancel does not create or refresh`() = runTest {
        val f = Fixture(this); f.controller.schedule(draft()); runCurrent(); f.answer(RecordingPlanChoice.CANCEL); runCurrent()
        assertTrue(f.creations.isEmpty()); assertEquals(0, f.refreshes); assertNull(f.controller.prompt.value)
    }
    @Test fun `a retry checks again without creating a job`() = runTest {
        val f = Fixture(this); f.controller.schedule(draft()); runCurrent(); val old = f.prompt()
        f.answer(RecordingPlanChoice.RETRY); runCurrent()
        assertEquals(2, f.previews.size); assertTrue(f.creations.isEmpty()); assertNotSame(old, f.prompt())
        f.controller.answer(old, RecordingPlanChoice.SCHEDULE); runCurrent(); assertTrue(f.creations.isEmpty())
    }
    @Test fun `a recheck can resolve a conflict and create the original block`() = runTest {
        val f = Fixture(this); val d = draft(false); f.controller.schedule(d); runCurrent()
        advanceTimeBy(5_000); f.response = { clear() }; f.answer(RecordingPlanChoice.RETRY); runCurrent()
        assertEquals(listOf(d, d), f.previews); assertEquals(listOf(d), f.creations)
        assertEquals("2026-11-16T19:00:00Z", f.creations.single().start)
    }
    @Test fun `a timeout expires the prompt and requires a new check`() = runTest {
        val f = Fixture(this); f.controller.schedule(draft()); runCurrent(); val old = f.prompt()
        advanceTimeBy(15_000); runCurrent()
        assertTrue(f.prompt().expired); assertNotSame(old, f.prompt())
        f.controller.answer(old, RecordingPlanChoice.SCHEDULE); f.answer(RecordingPlanChoice.SCHEDULE); runCurrent()
        assertTrue(f.creations.isEmpty())
        f.answer(RecordingPlanChoice.RETRY); runCurrent(); assertFalse(f.prompt().expired); assertEquals(2, f.previews.size)
    }
    @Test fun `elapsed monotonic time rejects a late answer before the timer runs`() = runTest {
        val contexts = MutableStateFlow(7L); var now = 0L; var creates = 0
        val c = RecordingScheduling(backgroundScope, contexts, { RecordingPlan(candidate = item()) }, { creates++ }, {}, {}, { now })
        c.schedule(draft()); runCurrent(); val prompt = requireNotNull(c.prompt.value)
        now = 15_000; c.answer(prompt, RecordingPlanChoice.SCHEDULE); runCurrent(); assertEquals(0, creates)
    }
    @Test fun `a queued confirmation cannot outlive its monotonic deadline`() = runTest {
        val contexts = MutableStateFlow(7L); var now = 0L; var creates = 0
        val c = RecordingScheduling(backgroundScope, contexts, { RecordingPlan(candidate = item()) }, { creates++ }, {}, {}, { now })
        c.schedule(draft()); runCurrent(); c.answer(requireNotNull(c.prompt.value), RecordingPlanChoice.SCHEDULE)
        now = 15_001; runCurrent(); assertEquals(0, creates); assertTrue(requireNotNull(c.prompt.value).expired)
    }
    @Test fun `double selection and double confirmation create once`() = runTest {
        val f = Fixture(this); f.controller.schedule(draft()); f.controller.schedule(draft(false)); runCurrent()
        assertEquals(1, f.previews.size); val p = f.prompt()
        f.controller.answer(p, RecordingPlanChoice.SCHEDULE); f.controller.answer(p, RecordingPlanChoice.SCHEDULE); runCurrent()
        assertEquals(1, f.creations.size)
    }
    @Test fun `profile account and server epoch changes close consent`() = runTest {
        for (label in listOf("profile", "account", "server")) {
            val f = Fixture(this); f.controller.schedule(draft()); runCurrent(); val p = f.prompt()
            f.contexts.value++; runCurrent(); f.controller.answer(p, RecordingPlanChoice.SCHEDULE); runCurrent()
            assertNull(label, f.controller.prompt.value); assertTrue(label, f.creations.isEmpty())
        }
    }
    @Test fun `returning to the same profile does not revive a prompt`() = runTest {
        val f = Fixture(this); f.controller.schedule(draft()); runCurrent(); val p = f.prompt()
        f.contexts.value = 8; f.contexts.value = 9; runCurrent()
        f.controller.answer(p, RecordingPlanChoice.SCHEDULE); runCurrent(); assertTrue(f.creations.isEmpty())
    }
    @Test fun `a response arriving after an epoch change cannot create`() = runTest {
        val f = Fixture(this); val response = CompletableDeferred<RecordingPlan>()
        f.response = { withContext(NonCancellable) { response.await() } }
        f.controller.schedule(draft()); runCurrent(); f.contexts.value++; runCurrent()
        response.complete(clear()); runCurrent(); assertTrue(f.creations.isEmpty()); assertNull(f.controller.prompt.value)
    }
    @Test fun `a stale draft is rejected before a preview request`() = runTest {
        val f = Fixture(this); f.contexts.value++; f.controller.schedule(draft()); runCurrent()
        assertTrue(f.previews.isEmpty()); assertTrue(f.creations.isEmpty())
    }
    @Test fun `late refusal from a replaced context cannot notify the new profile`() = runTest {
        val f = Fixture(this); val resume = CompletableDeferred<Unit>()
        f.response = { withContext(NonCancellable) { resume.await(); throw ApiError.Http(HttpFailure(403,"synthetic-private-error")) } }
        f.controller.schedule(draft()); runCurrent(); f.contexts.value++; runCurrent()
        resume.complete(Unit); runCurrent()
        assertTrue(f.creations.isEmpty()); assertTrue(f.notices.isEmpty()); assertNull(f.controller.prompt.value)
    }
    @Test fun `disposal clears consent and rejects late taps`() = runTest {
        val f = Fixture(this); f.controller.schedule(draft()); runCurrent(); val p = f.prompt()
        f.controller.dismiss(); runCurrent(); f.controller.answer(p, RecordingPlanChoice.SCHEDULE); runCurrent()
        assertNull(f.controller.prompt.value); assertTrue(f.creations.isEmpty())
    }
    @Test fun `a replaced screen request cannot clear its successors warning`() = runTest {
        val f = Fixture(this); val response = CompletableDeferred<RecordingPlan>(); var first = true
        f.response = { if (first) { first = false; withContext(NonCancellable) { response.await() } } else RecordingPlan(candidate = item()) }
        f.controller.schedule(draft()); runCurrent(); f.controller.dismiss()
        f.controller.schedule(draft(false)); runCurrent(); val current = f.prompt()
        response.complete(clear()); runCurrent()
        assertSame(current, f.prompt()); assertTrue(f.creations.isEmpty())
    }
    @Test fun `transport failure requires consent and never exposes a raw error`() = runTest {
        val f = Fixture(this); f.response = { throw IOException("synthetic-private-url") }; f.controller.schedule(draft()); runCurrent()
        assertFalse(f.prompt().message.contains("synthetic-private")); assertTrue(f.creations.isEmpty())
        f.answer(RecordingPlanChoice.SCHEDULE); runCurrent(); assertEquals(1, f.creations.size)
    }
    @Test fun `unavailable server permits retry or explicit cancellation`() = runTest {
        val f = Fixture(this); f.response = { throw ApiError.Http(HttpFailure(503,"synthetic-private-error")) }
        f.controller.schedule(draft()); runCurrent(); f.answer(RecordingPlanChoice.RETRY); runCurrent()
        assertEquals(2, f.previews.size); assertTrue(f.creations.isEmpty())
        f.answer(RecordingPlanChoice.CANCEL); runCurrent(); assertTrue(f.notices.isEmpty())
    }
    @Test fun `authentication and input refusals cannot be overridden`() = runTest {
        for (status in listOf(400,401,403,404,405,409,422,429)) {
            val f = Fixture(this); f.response = { throw ApiError.Http(HttpFailure(status,"synthetic-private-error")) }
            f.controller.schedule(draft()); runCurrent()
            assertNull("$status", f.controller.prompt.value); assertTrue("$status", f.creations.isEmpty())
            assertFalse(f.notices.joinToString().contains("synthetic-private"))
        }
    }
    @Test fun `signed out sessions cannot be overridden`() = runTest {
        val f = Fixture(this); f.response = { throw ApiError.SignedOut() }; f.controller.schedule(draft()); runCurrent()
        assertNull(f.controller.prompt.value); assertTrue(f.creations.isEmpty())
    }
    @Test fun `unknown or malformed candidate facts do not bypass consent`() = runTest {
        for (candidate in listOf(null,item("unknown",0,2),item("clear",0,1),item("clear",2,-1),item("clear",1,2),item("future-state",1,1),item("clear",1,1).copy(end="invalid"))) {
            val f = Fixture(this); f.response = { RecordingPlan(candidate = candidate) }; f.controller.schedule(draft()); runCurrent()
            assertNotNull(f.controller.prompt.value); assertTrue(f.creations.isEmpty()); f.controller.dismiss(); runCurrent()
        }
    }
    @Test fun `creation failures are not automatically retried`() = runTest {
        val f = Fixture(this); f.response = { clear() }; f.creating = { throw IOException("synthetic-private-create-error") }
        f.controller.schedule(draft()); runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals(1, f.creations.size); assertEquals(0,f.refreshes); assertFalse(f.notices.joinToString().contains("synthetic-private"))
    }
    @Test fun `the backend v1 response decodes and the preview body has no client-only fields`() {
        val raw = """{"checkedAt":"2026-11-16T18:00:00Z","complete":true,"recordings":[],"candidate":{"state":"conflict","start":"2026-11-16T18:55:00Z","end":"2026-11-16T20:05:00Z","maxStreams":1,"peakScheduledConnections":2,"firstConflictStart":"2026-11-16T19:00:00Z","firstConflictEnd":"2026-11-16T19:15:00Z"}}"""
        val plan = ApiJson.decodeFromString<RecordingPlan>(raw)
        assertEquals("conflict",plan.candidate?.state); assertFalse(requireNotNull(plan.candidate).isClear)
        val d = draft(false); val body = d.body()
        assertEquals(setOf("profileId","channelId","channelName","sourceUrl","title","start","stop"),body.keys)
        assertEquals(body,d.copy(epg=true).body())
        System.getenv("MEDIASTORM_NATIVE_CONTRACT_DIR")?.let { dir ->
            File(dir).mkdirs(); File(dir,"recording-preview.json").writeText(body.toString());File(dir,"recording-plan.json").writeText(raw)
        }
    }
}
