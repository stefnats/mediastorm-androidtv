package com.strmr.tv.player

import org.junit.Assert.*
import org.junit.Test

/** Policy tests exercise the same bounded accounting used by actual source reads. */
class ReplacementBufferTest {
    private class Clock(var value: Long = 1) {
        var current = true
        fun budget(target: Long = 32_000) = ReplacementBufferBudget(target, 100, { current }, { value })
    }
    private fun rejected(action: () -> Unit) { try { action(); fail("Expected conservative rejection") } catch (_: IllegalStateException) { } }
    @Test fun one_pending_owner_until_actual_cleanup() {
        val c = Clock(); val first = c.budget(); val second = c.budget()
        assertTrue(ReplacementBufferBudget.acquire(first))
        try {
            first.retire()
            assertEquals(1, ReplacementBufferBudget.charged())
            assertFalse(ReplacementBufferBudget.acquire(second))
        } finally { ReplacementBufferBudget.releasedAfterActualJoin(first) }
        assertTrue(ReplacementBufferBudget.acquire(second))
        ReplacementBufferBudget.releasedAfterActualJoin(second)
    }
    @Test fun frozen_target_does_not_follow_primary_ticks() {
        val c = Clock(); val b = c.budget(42_000)
        c.value = 50; b.observeRange(); b.observeDecoder(true)
        assertEquals(42_000L, b.targetMs); assertTrue(b.ready())
    }
    @Test fun explicit_target_change_retires_previous_range() {
        val c = Clock(); val previous = c.budget(32_000)
        previous.observeRange(); previous.observeDecoder(true); previous.retire()
        val replacement = c.budget(65_000)
        assertFalse(previous.ready()); assertEquals(65_000L, replacement.targetMs)
        assertFalse(replacement.ready()); rejected { previous.claim() }
    }
    @Test fun encoded_reads_stop_before_budget_exceeded() {
        val b = Clock().budget()
        val first = b.allowance((ReplacementBufferBudget.MAX_BYTES - 3).toInt())
        val competing = b.allowance(4096)
        assertEquals(3, competing.limit)
        rejected { b.allowance(1) } // All bytes already read OR reserved before delegate I/O.
        b.abandon(competing); b.abandon(competing)
        val eof = b.allowance(3); b.consumed(eof, -1)
        val short = b.allowance(3); b.consumed(short, 1)
        b.consumed(first, first.limit)
        val final = b.allowance(4096); assertEquals(2, final.limit); b.consumed(final, 2)
        rejected { b.allowance(1) }
        val zero = b.allowance(0); assertEquals(0, zero.limit); b.consumed(zero, 0)
    }
    @Test fun deadline_never_renews_on_callbacks() {
        val c = Clock(); val b = c.budget(); b.observeRange(); b.observeDecoder(true)
        c.value = 99; b.claim()
        c.value = 100; rejected { b.promote() }; rejected { b.observeRange() }
        assertFalse(b.ready())
    }
    @Test fun source_ready_without_decoder_tracks_is_not_ready() {
        val b = Clock().budget(); b.observeRange(); assertFalse(b.ready())
        b.observeDecoder(false); assertFalse(b.ready()); rejected { b.claim() }
        b.observeDecoder(true); assertTrue(b.ready())
    }
    @Test fun transfer_is_one_attempt_and_keeps_exact_loaded_source() {
        val c = Clock(); val b = c.budget(); b.observeRange(); b.observeDecoder(true)
        b.claim(); rejected { b.claim() }; b.promote(); rejected { b.promote() }
        c.value = 101
        // Promotion retains the same owner/accounting actor; no new preload grant or seek.
        assertEquals(32_000L, b.targetMs); assertEquals(1024, b.allowance(1024).limit)
    }
    @Test fun owner_or_source_change_retires_without_primary_release() {
        val c = Clock(); val b = c.budget(); b.observeRange(); b.observeDecoder(true)
        c.current = false
        assertFalse(b.ready()); rejected { b.claim() }; rejected { b.allowance(1) }
        b.retire(); c.current = true; rejected { b.check() }
    }
}
