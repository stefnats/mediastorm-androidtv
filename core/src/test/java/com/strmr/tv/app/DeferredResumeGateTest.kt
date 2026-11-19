package com.strmr.tv.app

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.player.PlaybackRequest
import com.strmr.tv.player.PlaybackSessionContext
import com.strmr.tv.player.PlayerSession
import com.strmr.tv.player.canOpenPlayer
import org.junit.Assert.*
import org.junit.Test

class DeferredResumeGateTest {
    private class Surface
    private open class Player
    private class PlayerSubclass : Player()
    private class Multiscreen
    private class Main

    private class Harness {
        var now = 1_000L
        val profile = Any()
        val session = Any()
        var owner = ResumeBinding(profile, 1L, session, "synthetic-client", "synthetic-source")
        val player = Any()
        val browsing = Any()
        val gate = DeferredResumeGate { now }
        fun enter(page: Any = browsing, player: Boolean = false, pin: Boolean = true, automatic: Boolean = true,
                  picker: Boolean = true, profiles: Int = 2) = gate.enter(owner, page, player, pin, automatic, picker, profiles)
        fun leaveFor(duration: Long) { gate.backgrounded(owner); now += duration }
    }

    @Test fun `registered player activity remains exempt when paused in PiP or background audio`() {
        for (state in listOf("playing", "paused-VOD", "PiP", "background-audio")) {
            assertTrue(state, resumePlaybackExempt(Player::class.java, Player::class.java, Multiscreen::class.java))
        }
        assertTrue(resumePlaybackExempt(Multiscreen::class.java, Player::class.java, Multiscreen::class.java))
        assertFalse(resumePlaybackExempt(Main::class.java, Player::class.java, Multiscreen::class.java))
        assertFalse(resumePlaybackExempt(PlayerSubclass::class.java, Player::class.java, Multiscreen::class.java))
        assertFalse(resumePlaybackExempt(Main::class.java, Player::class.java, null))
    }

    @Test fun `long player return creates no prompt until the original authority leaves for browsing`() {
        val h = Harness()
        h.enter(h.player, player = true)
        h.leaveFor(300_000)
        assertNull(h.enter(h.player, player = true))
        assertTrue(h.gate.blocked.value)
        val ticket = requireNotNull(h.enter())
        assertEquals(ResumeAction.PIN, ticket.action)
        assertSame(h.player, ticket.origin)
        assertSame(h.owner, ticket.owner)
        assertTrue(h.gate.current(ticket, h.owner))
        assertTrue(h.gate.complete(ticket, h.owner))
        assertFalse(h.gate.blocked.value)
    }

    @Test fun `long absence then direct browsing after stopped player still owes PIN instead of picker`() {
        val h = Harness()
        h.enter(h.player, player = true, automatic = false)
        h.leaveFor(600_000)
        val ticket = requireNotNull(h.enter(automatic = false, picker = true))
        assertEquals(ResumeAction.PIN, ticket.action)
        assertSame(h.player, ticket.origin)
    }

    @Test fun `paused phone PiP chosen audio and multiview retain the exact same obligation`() {
        for (mode in listOf("paused-phone-VOD", "PiP", "chosen-background-audio", "multiview")) {
            val h = Harness()
            h.enter(h.player, player = true)
            h.leaveFor(500_000)
            assertNull(mode, h.enter(h.player, player = true))
            val expected = h.owner
            for (repeat in 0..2) {
                h.leaveFor(1_000)
                assertNull(mode, h.enter(Any(), player = true))
            }
            val ticket = requireNotNull(h.enter())
            assertSame(mode, expected, ticket.owner)
            assertSame(mode, h.player, ticket.origin)
            assertEquals(ResumeAction.PIN, ticket.action)
        }
    }

    @Test fun `player threshold is exactly five minutes and unprotected profiles do not acquire an obligation`() {
        for ((duration, owed) in listOf(299_999L to false, 300_000L to true, 300_001L to true)) {
            val h = Harness()
            h.enter(h.player, player = true)
            h.leaveFor(duration)
            h.enter(h.player, player = true)
            assertEquals(owed, h.gate.blocked.value)
            assertEquals(owed, h.enter() != null)
        }
        val h = Harness()
        h.enter(h.player, player = true, pin = false)
        h.leaveFor(600_000)
        assertNull(h.enter(h.player, player = true, pin = false))
        assertNull(h.enter(pin = false))
        assertFalse(h.gate.blocked.value)
    }

    @Test fun `duplicate pause stop and repeated background signals preserve the first monotonic boundary`() {
        val h = Harness()
        h.enter(h.player, player = true)
        h.gate.backgrounded(h.owner)
        h.now += 299_000
        h.gate.backgrounded(h.owner)
        h.now += 1_000
        h.enter(h.player, player = true)
        assertEquals(ResumeAction.PIN, requireNotNull(h.enter()).action)
    }

    @Test fun `ordinary automatic protected return retains PIN and ordinary multi-profile return retains picker`() {
        val automatic = Harness()
        automatic.enter()
        automatic.leaveFor(300_000)
        assertEquals(ResumeAction.PIN, requireNotNull(automatic.enter()).action)
        val ordinary = Harness()
        ordinary.enter(automatic = false)
        ordinary.leaveFor(2_000)
        assertNull(ordinary.enter(automatic = false))
        ordinary.leaveFor(2_001)
        assertEquals(ResumeAction.PICKER, requireNotNull(ordinary.enter(automatic = false)).action)
    }

    @Test fun `Main start beside still-started PiP blocks before its first browsing ticket runs`() {
        val h = Harness()
        h.enter(h.player, player = true)
        // The resumed/paused callback records this even if PiP never receives onStop.
        h.leaveFor(600_000)
        val ticket = requireNotNull(h.enter())
        assertTrue(h.gate.blocked.value)
        assertTrue(h.gate.current(ticket, h.owner))
        assertNull(h.enter()) // onStart/onResume duplicates share the current page operation.
        assertTrue(h.gate.blocked.value)
    }

    @Test fun `leaving a PIN page retains the obligation but cannot let its callback unlock a replacement page`() {
        val h = Harness()
        h.enter(h.player, player = true)
        h.leaveFor(300_000)
        val old = requireNotNull(h.enter())
        h.gate.leave(h.browsing)
        assertFalse(h.gate.complete(old, h.owner))
        val replacement = requireNotNull(h.enter(Any()))
        assertFalse(h.gate.current(old, h.owner))
        assertTrue(h.gate.current(replacement, h.owner))
        h.gate.release(old)
        assertTrue(h.gate.current(replacement, h.owner))
        assertTrue(h.gate.blocked.value)
    }

    @Test fun `profile ABA session client source and equal-profile replacement cannot adopt an older PIN callback`() {
        for (change in listOf("epoch-ABA", "profile-object", "session", "client", "source")) {
            val h = Harness()
            h.enter(h.player, player = true)
            h.leaveFor(300_000)
            val old = requireNotNull(h.enter())
            h.owner = ResumeBinding(if (change == "profile-object") Any() else h.profile,
                if (change == "epoch-ABA") 3L else 1L,
                if (change == "session") Any() else h.session,
                if (change == "client") "other-client" else "synthetic-client",
                if (change == "source") "other-source" else "synthetic-source")
            val fresh = requireNotNull(h.enter())
            assertNotSame(old, fresh)
            assertFalse(change, h.gate.complete(old, h.owner))
            assertTrue(change, h.gate.blocked.value)
            assertTrue(change, h.gate.complete(fresh, h.owner))
        }
    }

    @Test fun `verified profile activation retires old absence and tickets without imposing it on a PIN-free owner`() {
        val h = Harness()
        h.enter(h.player, player = true)
        h.leaveFor(300_000)
        val old = requireNotNull(h.enter())
        h.gate.profileActivated()
        assertFalse(h.gate.blocked.value)
        assertFalse(h.gate.complete(old, h.owner))
        h.owner = ResumeBinding(Any(), 2L, Any(), "new-client", "new-source")
        assertNull(h.enter(pin = false))
        h.gate.reconcile(null, false)
        assertFalse(h.gate.blocked.value)
    }

    @Test fun `missing current authority in Ready hides browsing and cannot complete a previous PIN ticket`() {
        val h = Harness()
        h.enter(h.player, player = true)
        h.leaveFor(300_000)
        val old = requireNotNull(h.enter())
        h.gate.ownerUnavailable()
        assertTrue(h.gate.blocked.value)
        assertFalse(h.gate.complete(old, null))
        assertFalse(h.gate.complete(old, h.owner))
        val replacement = requireNotNull(h.enter())
        assertTrue(h.gate.current(replacement, h.owner))
        h.gate.reconcile(null, false) // The actual account moved to Login/Profiles, not old Ready content.
        assertFalse(h.gate.blocked.value)
    }

    @Test fun `retained player-exit gate does not invalidate the actual prepared playback owner`() {
        val expected = ProfileDataContext.current
        val binding = PlaybackSessionContext.capture(expected)
        val playback = PlayerSession(PlaybackRequest(DetailsTarget("tmdb:movie:42", "Synthetic movie", true)),
            "https://example.invalid/synthetic-stream", null, ownership = binding)
        val session = ApiClient.session
        val profile = ApiClient.profileId
        val h = Harness()
        h.enter(h.player, player = true)
        h.leaveFor(600_000)
        h.enter(h.player, player = true)
        assertTrue(playback.canOpenPlayer())
        val ticket = requireNotNull(h.enter())
        assertTrue(playback.canOpenPlayer())
        assertEquals(expected, ProfileDataContext.current)
        assertEquals(profile, ApiClient.profileId)
        assertSame(session, ApiClient.session)
        assertTrue(h.gate.complete(ticket, h.owner))
        assertTrue(playback.canOpenPlayer())
        assertSame(binding, playback.ownership)
    }
}
