package com.strmr.tv.home

import com.strmr.tv.api.EpisodeSourceCoverage
import com.strmr.tv.api.SeasonSourceCoverage
import com.strmr.tv.api.SourceAvailability
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class HeroAvailabilityTest {
    private val now = Instant.parse("2026-11-19T18:00:00Z")
    private fun missing() = SourceAvailability("not_found", now.minusSeconds(60).toString(), now.plusSeconds(60).toString(), complete = true, reason = "no_matching_candidates")

    @Test fun `fresh completed missing evidence changes both Play and Resume to Details`() {
        for (progress in listOf(0f, .75f, 1f)) {
            val cta = heroCta(progress, SourceHint.from(missing(), now))
            assertEquals("Details", cta.label)
            assertTrue(cta.confirmedMissing)
            assertEquals(SourceHint.Kind.MISSING, cta.hint?.kind)
        }
    }

    @Test fun `expired future incomplete and conflicting hints never become confirmed missing`() {
        for (value in listOf(
            missing().copy(complete = false),
            missing().copy(expiresAt = now.toString()),
            missing().copy(checkedAt = now.plusSeconds(1).toString()),
            missing().copy(checkedAt = "invalid"),
            missing().copy(expiresAt = now.plusSeconds(90_000).toString()),
            missing().copy(sourceType = "local"),
            missing().copy(reason = "recheck_context_changed"),
            missing().copy(episodeCoverage = EpisodeSourceCoverage(state = "invalid")),
        )) {
            val cta = heroCta(.75f, SourceHint.from(value, now))
            assertFalse(value.toString(), cta.confirmedMissing)
            assertEquals("Resume", cta.label)
            assertEquals(SourceHint.Kind.UNKNOWN, cta.hint?.kind)
        }
        assertFalse(heroCta(.75f, null).confirmedMissing)
    }

    @Test fun `candidate partial and checked local evidence preserve user playback intent without upgrading proof`() {
        val partial = EpisodeSourceCoverage("partial", 1, 1, 0, false, listOf(SeasonSourceCoverage(1, "partial", 1, 1, 0)))
        val rows = listOf(
            missing().copy(state = "found", reason = "matching_candidates") to SourceHint.Kind.CANDIDATE,
            missing().copy(state = "found", reason = "episode_candidates", episodeCoverage = partial) to SourceHint.Kind.PARTIAL,
            missing().copy(state = "found", sourceType = "local", reason = "local_file_checked") to SourceHint.Kind.LOCAL,
        )
        for ((source, kind) in rows) {
            val cta = heroCta(0f, SourceHint.from(source, now))
            assertEquals("Play", cta.label)
            assertFalse(cta.confirmedMissing)
            assertEquals(kind, cta.hint?.kind)
        }
    }

    @Test fun `the actual expiry boundary removes confirmed missing rather than retaining the drawn decision`() {
        val source = missing()
        assertTrue(heroCta(0f, SourceHint.from(source, now.plusSeconds(59))).confirmedMissing)
        val expired = heroCta(0f, SourceHint.from(source, now.plusSeconds(60)))
        assertFalse(expired.confirmedMissing)
        assertEquals("Play", expired.label)
        assertEquals("Source check expired", expired.hint?.label)
    }
}
