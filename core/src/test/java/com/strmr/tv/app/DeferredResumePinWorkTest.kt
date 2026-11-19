package com.strmr.tv.app

import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.Profile
import com.strmr.tv.launcher.DeepLink
import com.strmr.tv.launcher.DeepLinkRouter
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class DeferredResumePinWorkTest {
    private class Harness(scope: CoroutineScope) {
        var now = 1_000L
        val profile = Profile("owned-profile", "Synthetic protected profile", hasPin = true)
        val accountSession = Any()
        var binding = ResumeBinding(profile, 1L, accountSession, "synthetic-client", "synthetic-source")
        val player = Any()
        var page: Any = Any()
        val gate = DeferredResumeGate { now }
        val work = ResumeWork(scope)
        var verifies = 0
        var publications = 0
        var pickedProfiles = false
        var remote: suspend (String, () -> Boolean) -> Boolean = { pin, accept -> pin == "1234" && accept() }
        val prompts = ScopedProfileUnlock({ binding.epoch }) { pin, _, _, accept ->
            verifies++
            remote(pin, accept)
        }
        fun defer() {
            gate.enter(binding, player, true, true, true, true, 2)
            gate.backgrounded(binding)
            now += 300_000
            gate.enter(binding, player, true, true, true, true, 2)
        }
        fun browse(): Job? {
            val ticket = gate.enter(binding, page, false, true, true, true, 2) ?: return null
            val original = binding
            return work.launch({ gate.current(ticket, binding) && original.same(binding) }) {
                check()
                if (prompts.unlock(profile.id, profile, original.epoch) {
                    if (gate.current(ticket, binding)) {
                        pickedProfiles = true
                        gate.reconcile(null, false)
                    }
                }) publish {
                    if (gate.complete(ticket, binding)) publications++
                }
            }
        }
        fun leave() { gate.leave(page); work.cancelStale() }
        suspend fun stale(action: suspend () -> Unit) {
            try { action(); fail("Retired PIN callback completed") } catch (_: CancellationException) {}
        }
    }

    @Test fun `browsing is blocked before queued PIN work and only valid scoped success reveals it`() = runTest {
        val h = Harness(backgroundScope)
        h.defer()
        val job = requireNotNull(h.browse())
        assertTrue(h.gate.blocked.value)
        assertNull(h.prompts.pinChallenge.value)
        runCurrent()
        val challenge = requireNotNull(h.prompts.pinChallenge.value)
        assertEquals(PinVerification.Wrong, h.prompts.verify("0000", challenge))
        assertTrue(h.gate.blocked.value)
        assertEquals(PinVerification.Unlocked, h.prompts.verify("1234", challenge))
        job.join()
        assertFalse(h.gate.blocked.value)
        assertEquals(1, h.publications)
    }

    @Test fun `network failure preserves the exact owed PIN and current prompt for retry`() = runTest {
        val h = Harness(backgroundScope)
        h.defer()
        val job = requireNotNull(h.browse())
        runCurrent()
        val challenge = requireNotNull(h.prompts.pinChallenge.value)
        h.remote = { _, _ -> error("Synthetic unavailable verification") }
        assertTrue(h.prompts.verify("1234", challenge) is PinVerification.Failed)
        assertSame(challenge, h.prompts.pinChallenge.value)
        assertTrue(h.gate.blocked.value)
        assertEquals(0, h.publications)
        h.remote = { _, accept -> accept() }
        assertEquals(PinVerification.Unlocked, h.prompts.verify("1234", challenge))
        job.join()
        assertEquals(1, h.publications)
    }

    @Test fun `PIN cancellation enters the profile picker instead of restoring protected browsing`() = runTest {
        val h = Harness(backgroundScope)
        h.defer()
        val job = requireNotNull(h.browse())
        runCurrent()
        h.prompts.cancel(h.prompts.pinChallenge.value)
        job.join()
        assertTrue(h.pickedProfiles)
        assertEquals(0, h.publications)
        assertNull(h.prompts.pinChallenge.value)
        // Clearing the gate accompanies Login/Profiles, never permission for the old Ready page.
        assertFalse(h.gate.blocked.value)
    }

    @Test fun `page cancellation removes its prompt while a later browsing page must ask again`() = runTest {
        val h = Harness(backgroundScope)
        h.defer()
        val old = requireNotNull(h.browse())
        runCurrent()
        val oldChallenge = requireNotNull(h.prompts.pinChallenge.value)
        h.leave()
        runCurrent()
        assertTrue(old.isCancelled)
        assertNull(h.prompts.pinChallenge.value)
        assertTrue(h.gate.blocked.value)
        h.page = Any()
        val replacement = requireNotNull(h.browse())
        runCurrent()
        val challenge = requireNotNull(h.prompts.pinChallenge.value)
        assertNotSame(oldChallenge, challenge)
        h.stale { h.prompts.verify("1234", oldChallenge) }
        assertTrue(h.gate.blocked.value)
        assertEquals(PinVerification.Unlocked, h.prompts.verify("1234", challenge))
        replacement.join()
        assertEquals(1, h.publications)
    }

    @Test fun `masked old remote verification cannot clear a new page obligation after replacement`() = runTest {
        val h = Harness(backgroundScope)
        h.defer()
        h.browse()
        runCurrent()
        val oldChallenge = requireNotNull(h.prompts.pinChallenge.value)
        val entered = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        h.remote = { _, accept -> withContext(NonCancellable) { entered.complete(Unit); reply.await() }; accept() }
        val oldVerification = async { h.prompts.verify("1234", oldChallenge) }
        entered.await()
        h.leave()
        runCurrent()
        h.page = Any()
        h.remote = { _, accept -> accept() }
        val replacement = requireNotNull(h.browse())
        runCurrent()
        val currentChallenge = requireNotNull(h.prompts.pinChallenge.value)
        reply.complete(Unit)
        h.stale { oldVerification.await() }
        assertSame(currentChallenge, h.prompts.pinChallenge.value)
        assertTrue(h.gate.blocked.value)
        assertEquals(0, h.publications)
        assertEquals(PinVerification.Unlocked, h.prompts.verify("1234", currentChallenge))
        replacement.join()
        assertEquals(1, h.publications)
    }

    @Test fun `profile ABA while verifying cancels the old producer and cannot publish through the new epoch`() = runTest {
        val h = Harness(backgroundScope)
        h.defer()
        val old = requireNotNull(h.browse())
        runCurrent()
        val oldChallenge = requireNotNull(h.prompts.pinChallenge.value)
        val entered = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        h.remote = { _, accept -> withContext(NonCancellable) { entered.complete(Unit); reply.await() }; accept() }
        val verification = async { h.prompts.verify("1234", oldChallenge) }
        entered.await()
        h.binding = ResumeBinding(h.profile, 3L, h.accountSession, "synthetic-client", "synthetic-source")
        h.gate.reconcile(h.binding, true)
        h.work.cancelStale()
        runCurrent()
        assertTrue(old.isCancelled)
        h.prompts.expire()
        reply.complete(Unit)
        h.stale { verification.await() }
        assertEquals(0, h.publications)
        assertTrue(h.gate.blocked.value)
        h.remote = { _, accept -> accept() }
        val replacement = requireNotNull(h.browse())
        runCurrent()
        assertEquals(PinVerification.Unlocked, h.prompts.verify("1234", h.prompts.pinChallenge.value))
        replacement.join()
        assertEquals(1, h.publications)
    }

    @Test fun `launcher play intent is not consumed behind the owed PIN and retains its original target`() = runTest {
        val h = Harness(backgroundScope)
        val target = DetailsTarget("tmdb:movie:42", "Synthetic held title", true)
        val link = DeepLink(DeepLink.Action.PLAY, target, h.profile.id)
        assertTrue(DeepLinkRouter.openInternal(link, ProfileDataContext.current))
        h.defer()
        val job = requireNotNull(h.browse())
        val pending = requireNotNull(DeepLinkRouter.pending.value)
        assertNull(DeepLinkRouter.take(h.profile.id, h.gate.blocked.value))
        assertSame(pending, DeepLinkRouter.pending.value)
        runCurrent()
        assertEquals(PinVerification.Unlocked, h.prompts.verify("1234", h.prompts.pinChallenge.value))
        job.join()
        assertEquals(link, DeepLinkRouter.take(h.profile.id, h.gate.blocked.value))
        assertNull(DeepLinkRouter.pending.value)
    }
}
