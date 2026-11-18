package com.strmr.tv.update

import org.junit.Assert.*
import org.junit.Test

class ForegroundEventsTest {
    @Test fun `phone registration followed by updater delivers both existing events once`() {
        val events = ForegroundEvents {}
        val calls = mutableListOf<String>()
        events.onLeft(Any()) { calls += "connection" }
        events.onLeft(Any()) { calls += "update" }
        events.activityStarted()
        events.activityStopped(false)
        assertEquals(listOf("connection", "update"), calls)
        assertFalse(events.visible)
    }

    @Test fun `updater registration before phone also preserves both consumers`() {
        val events = ForegroundEvents {}
        val calls = mutableListOf<String>()
        events.onLeft(Any()) { calls += "update" }
        events.onLeft(Any()) { calls += "connection" }
        events.activityStarted()
        events.activityStopped(false)
        assertEquals(listOf("update", "connection"), calls)
    }

    @Test fun `repeated updater registration replaces only its own stable owner`() {
        val events = ForegroundEvents {}
        val updater = Any()
        val connection = Any()
        val calls = mutableListOf<String>()
        events.onLeft(connection) { calls += "connection" }
        events.onLeft(updater) { calls += "old-update" }
        repeat(5) { events.onLeft(updater) { calls += "update" } }
        events.activityStarted()
        events.activityStopped(false)
        events.activityStarted()
        events.activityStopped(false)
        assertEquals(listOf("connection", "update", "connection", "update"), calls)
    }

    @Test fun `different value equal owners do not overwrite one another`() {
        data class Owner(val name: String)
        val events = ForegroundEvents {}
        var calls = 0
        events.onLeft(Owner("synthetic")) { calls++ }
        events.onLeft(Owner("synthetic")) { calls++ }
        events.activityStarted()
        events.activityStopped(false)
        assertEquals(2, calls)
    }

    @Test fun `dispatch snapshots callback replacement until the following event`() {
        val events = ForegroundEvents {}
        val first = Any()
        val second = Any()
        val calls = mutableListOf<String>()
        events.onLeft(first) {
            calls += "first"
            events.onLeft(second) { calls += "new-second" }
        }
        events.onLeft(second) { calls += "old-second" }
        events.activityStarted()
        events.activityStopped(false)
        assertEquals(listOf("first", "old-second"), calls)
        events.activityStarted()
        events.activityStopped(false)
        assertEquals(listOf("first", "old-second", "first", "new-second"), calls)
    }

    @Test fun `listener registered during dispatch starts with the next event`() {
        val events = ForegroundEvents {}
        val first = Any()
        val late = Any()
        var firstCalls = 0
        var lateCalls = 0
        events.onLeft(first) { firstCalls++; events.onLeft(late) { lateCalls++ } }
        events.activityStarted()
        events.activityStopped(false)
        assertEquals(1, firstCalls)
        assertEquals(0, lateCalls)
        events.activityStarted()
        events.activityStopped(false)
        assertEquals(2, firstCalls)
        assertEquals(1, lateCalls)
    }

    @Test fun `internal activity handover does not emit a whole app departure`() {
        var entered = 0
        var left = 0
        val events = ForegroundEvents { entered++ }
        events.onLeft(Any()) { left++ }
        events.activityStarted()
        events.activityStarted()
        events.activityStopped(false)
        assertTrue(events.visible)
        assertEquals(1, entered)
        assertEquals(0, left)
        events.activityStopped(false)
        assertFalse(events.visible)
        assertEquals(1, left)
        events.activityStarted()
        assertEquals(2, entered)
    }

    @Test fun `rotation does not emit a departure and preserves existing return semantics`() {
        var entered = 0
        var left = 0
        val events = ForegroundEvents { entered++ }
        events.onLeft(Any()) { left++ }
        events.activityStarted()
        events.activityStopped(true)
        assertFalse(events.visible)
        assertEquals(0, left)
        events.activityStarted()
        assertEquals(2, entered)
        events.activityStopped(false)
        assertEquals(1, left)
    }

    @Test fun `TV single updater listener still receives a normal departure`() {
        val events = ForegroundEvents {}
        var calls = 0
        events.onLeft(Any()) { calls++ }
        events.activityStarted()
        events.activityStopped(false)
        assertEquals(1, calls)
    }

    @Test fun `callback exception remains observable instead of being silently suppressed`() {
        val events = ForegroundEvents {}
        val failure = IllegalStateException("Synthetic callback failure")
        events.onLeft(Any()) { throw failure }
        events.activityStarted()
        try {
            events.activityStopped(false)
            fail("Callback exception was suppressed")
        } catch (error: IllegalStateException) {
            assertSame(failure, error)
        }
    }
}
