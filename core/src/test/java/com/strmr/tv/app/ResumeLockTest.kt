package com.strmr.tv.app

import com.strmr.tv.api.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class ResumeLockTest {
    private fun action(away: Long, pin: Boolean = true, auto: Boolean = false,
                       picker: Boolean = true, profiles: Int = 2) =
        resumeAction(away, pin, auto, picker, profiles)

    @Test fun `protected automatic profile requests PIN at exactly five minutes`() {
        assertEquals(ResumeAction.NONE, action(299_999, auto = true))
        assertEquals(ResumeAction.PIN, action(300_000, auto = true))
        assertEquals(ResumeAction.PIN, action(3_600_000, auto = true))
    }

    @Test fun `automatic PIN free profile still bypasses the short picker`() {
        assertEquals(ResumeAction.NONE, action(2_001, pin = false, auto = true))
        assertEquals(ResumeAction.NONE, action(300_000, pin = false, auto = true))
    }

    @Test fun `ordinary multiple profile return retains strict two second picker boundary`() {
        assertEquals(ResumeAction.NONE, action(2_000))
        assertEquals(ResumeAction.PICKER, action(2_001))
        assertEquals(ResumeAction.PICKER, action(300_000))
    }

    @Test fun `disabled picker and single profile retain existing protected PIN path`() {
        assertEquals(ResumeAction.NONE, action(299_999, picker = false))
        assertEquals(ResumeAction.PIN, action(300_000, picker = false))
        assertEquals(ResumeAction.PIN, action(300_000, profiles = 1))
        assertEquals(ResumeAction.NONE, action(300_000, pin = false, profiles = 1))
    }

    @Test fun `absence consumes elapsed time once without consulting wall clock`() {
        var elapsed = 7_000L
        val absence = ResumeAbsence { elapsed }
        assertNull(absence.take())
        absence.backgrounded()
        // Only the supplied monotonic source is consumed, not a calendar timestamp.
        elapsed += 300_000
        assertEquals(300_000L, absence.take())
        assertNull(absence.take())
        absence.backgrounded()
        elapsed += 2_000
        assertEquals(2_000L, absence.take())
    }

    @Test fun `absence already consumed by an exempt activity is not replayed later`() {
        var elapsed = 0L
        val absence = ResumeAbsence { elapsed }
        absence.backgrounded()
        elapsed = 600_000
        assertEquals(600_000L, absence.take())
        assertNull(absence.take())
    }

    @Test fun `saved picker setting is positive only for the original owner and epoch`() {
        val settings = ResumeSettings()
        val original = Profile("profile-a", "Synthetic profile")
        assertTrue(settings.value(original, 1L))
        settings.save(original, 1L, false)
        assertFalse(settings.value(original, 1L))
        assertTrue(settings.value(original.copy(), 1L))
        assertTrue(settings.value(original, 2L))
        assertTrue(settings.value(Any(), 1L))
        settings.save(original, 2L, true)
        assertTrue(settings.value(original, 2L))
    }

    @Test fun `replacement owner without a settings reply retains the default picker`() {
        val settings = ResumeSettings()
        val first = Any()
        val second = Any()
        settings.save(first, 1L, false)
        assertEquals(ResumeAction.NONE, resumeAction(2_001, false, false, settings.value(first, 1L), 2))
        assertEquals(ResumeAction.PICKER, resumeAction(2_001, false, false, settings.value(second, 2L), 2))
    }

    @Test fun `queued old owner operation stops before its first effect after A B A epochs`() = runTest {
        val work = ResumeWork(this)
        val originalProfile = Any()
        var profile = originalProfile
        var epoch = 1L
        val captured = epoch
        var calls = 0
        val job = work.launch({ profile === originalProfile && epoch == captured }) { calls++ }
        profile = Any(); epoch++
        profile = originalProfile; epoch++
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(0, calls)
    }

    @Test fun `equal profile replacement cannot start a queued original operation`() = runTest {
        val work = ResumeWork(this)
        val original = Profile("profile-a", "Synthetic profile")
        var current = original
        var calls = 0
        val job = work.launch({ current === original }) { calls++ }
        current = original.copy()
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(0, calls)
    }

    @Test fun `replacement ticket prevents masked old settings answer from publishing`() = runTest {
        val work = ResumeWork(this)
        val reply = CompletableDeferred<Unit>()
        var setting = "initial"
        val old = work.launch({ true }) {
            withContext(NonCancellable) { reply.await() }
            publish { setting = "old" }
        }
        runCurrent()
        work.launch({ true }) { publish { setting = "new" } }
        runCurrent()
        reply.complete(Unit)
        runCurrent()
        assertEquals("new", setting)
        assertTrue(old.isCancelled)
    }

    @Test fun `stale observer cancels an already registered child before response publication`() = runTest {
        val work = ResumeWork(this)
        var selected = true
        var cancelled = false
        var published = false
        work.launch({ selected }) {
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation { cancelled = true }
            }
            publish { published = true }
        }
        runCurrent()
        selected = false
        work.cancelStale()
        runCurrent()
        assertTrue(cancelled)
        assertFalse(published)
    }

    @Test fun `producer cancellation reaches child while a different sibling remains blocked`() = runTest {
        val parent = Job(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + parent)
        val entered = CompletableDeferred<Unit>()
        val finishSibling = CompletableDeferred<Unit>()
        scope.launch { withContext(NonCancellable) { entered.complete(Unit); finishSibling.await() } }
        runCurrent()
        entered.await()
        var cancelled = false
        ResumeWork(scope).launch({ true }) {
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation { cancelled = true }
            }
        }
        runCurrent()
        parent.cancel()
        runCurrent()
        assertTrue(cancelled)
        assertFalse(parent.isCompleted)
        finishSibling.complete(Unit)
        parent.join()
    }

    @Test fun `accepted current work retains normal success publication`() = runTest {
        val work = ResumeWork(this)
        var io = 0
        var published = 0
        val job = work.launch({ true }) {
            check(); io++
            publish { published++ }
        }
        job.join()
        assertEquals(1, io)
        assertEquals(1, published)
        assertFalse(job.isCancelled)
    }

    @Test fun `original work consumes existing scoped PIN and clears only its own required state`() = runTest {
        val work = ResumeWork(this)
        val profile = Profile("profile-a", "Synthetic profile")
        val prompts = ScopedProfileUnlock({ 1L }) { _, _, expected, accept -> expected == 1L && accept() }
        var required = false
        val job = work.launch({ true }) {
            publish { required = true }
            if (prompts.unlock(profile.id, profile, 1L) {}) publish { required = false }
        }
        runCurrent()
        assertTrue(required)
        assertEquals(PinVerification.Unlocked, prompts.verify("0000", prompts.pinChallenge.value))
        job.join()
        assertFalse(required)
    }

    @Test fun `cancelling original PIN waiter removes prompt without later publication`() = runTest {
        var epoch = 1L
        val work = ResumeWork(this)
        val profile = Profile("profile-a", "Synthetic profile")
        val prompts = ScopedProfileUnlock({ epoch }) { _, _, _, _ -> true }
        var publications = 0
        work.launch({ epoch == 1L }) {
            if (prompts.unlock(profile.id, profile, 1L) {}) publish { publications++ }
        }
        runCurrent()
        assertNotNull(prompts.pinChallenge.value)
        epoch++
        work.cancelStale()
        runCurrent()
        assertNull(prompts.pinChallenge.value)
        assertEquals(0, publications)
    }
}
