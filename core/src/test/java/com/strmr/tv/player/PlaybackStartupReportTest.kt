package com.strmr.tv.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackStartupReportTest {
    private fun sample(ms: Long = 1, outcome: StartupOutcome = StartupOutcome.RENDERED, input: StartupInput = StartupInput.UNKNOWN,
        origin: StartupOrigin = StartupOrigin.USER_ACTION) = StartupRecord(StartupSample(outcome, ms, input), origin, StartupEntry.DETAILS_PLAY)
    @Test fun `empty and small cohorts do not report invented zero p95`() {
        assertTrue(startupReport(emptyList(), 0).cohorts.isEmpty())
        assertNull(startupReport(List(19) { sample(it.toLong()) }, 0).cohorts.single().renderedP95Millis)
    }
    @Test fun `nearest rank is exact at twenty twenty one and one hundred`() {
        for ((n, expected) in listOf(20 to 19L, 21 to 20L, 100 to 95L)) {
            assertEquals(expected, startupReport((1..n).map { sample(it.toLong()) }.reversed(), 0).cohorts.single().renderedP95Millis)
        }
    }
    @Test fun `ties remain observed durations and are not interpolated`() {
        assertEquals(42L, startupReport(List(20) { sample(42) }, 0).cohorts.single().renderedP95Millis)
    }
    @Test fun `failures cancellations timeouts and external handoff stay in completed denominator`() {
        val samples = List(20) { sample(10) } + StartupOutcome.entries.filter { it != StartupOutcome.RENDERED }.map { sample(99999, it) }
        val row = startupReport(samples, 1).cohorts.single()
        assertEquals(samples.size, row.completed); assertEquals(20, row.rendered); assertEquals(10L, row.renderedP95Millis)
        for (outcome in StartupOutcome.entries.filter { it != StartupOutcome.RENDERED }) assertEquals(1, row.outcomes[outcome.name])
    }
    @Test fun `coordinator only and supplied input observations are never mixed`() {
        val samples = List(20) { sample(1) } + List(20) { sample(2, origin = StartupOrigin.COORDINATOR_ONLY) } + List(20) { sample(3, input = StartupInput.READY_PREPARATION_SUPPLIED) }
        val report = startupReport(samples, 2)
        assertEquals(3, report.cohorts.size); assertEquals(setOf(1L, 2L, 3L), report.cohorts.map { it.renderedP95Millis }.toSet())
    }
    @Test fun `report retains only last128 complete and separates in flight`() {
        val report = startupReport(List(129) { sample(it.toLong()) }, 2)
        assertEquals(128, report.completed); assertEquals(2, report.inFlight); assertEquals(128, report.cohorts.single().completed)
    }
    @Test fun `preparation observation requires exact existing target profile and context`() {
        val warm = WarmPrequeue("synthetic", "private-profile", WarmPrequeue.Phase.READY, context = 42)
        assertEquals(StartupPreparation.READY, startupPreparation(warm, "synthetic", 42, "private-profile"))
        assertEquals(StartupPreparation.UNKNOWN, startupPreparation(warm, "other", 42, "private-profile"))
        assertEquals(StartupPreparation.UNKNOWN, startupPreparation(warm, "synthetic", 43, "private-profile"))
        assertEquals(StartupPreparation.UNKNOWN, startupPreparation(warm, "synthetic", 42, "other"))
        assertEquals(StartupPreparation.UNKNOWN, startupPreparation(null, "synthetic", 42, "private-profile"))
    }
    @Test fun `actual text qualifies survivor p95 cache uncertainty and targets without identities`() {
        val report = startupReport(List(20) { sample(10) }, 1); val text = report.text()
        assertTrue(text.contains("Not assessed")); assertTrue(text.contains("<=3s")); assertTrue(text.contains("<=15s"))
        assertTrue(text.contains("successful first frames only")); assertTrue(text.contains("20/20"))
        assertFalse(text.contains("tmdb:")); assertFalse(text.contains("http")); assertFalse(text.contains("private-profile"))
        assertEquals(setOf("completed", "inFlight", "cohorts"), PlaybackStartupReport::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet())
    }
}
