package com.strmr.tv.home

import com.strmr.tv.api.EpisodeSourceCoverage
import com.strmr.tv.api.SourceAvailability
import java.time.Duration
import java.time.Instant

/** One response snapshot, bound to the observer's profile, view and ordered request. */
data class EpisodeSourceEvidence(
    val hints: Map<String, SourceAvailability> = emptyMap(),
    val coverage: EpisodeSourceCoverage? = null,
    val receivedAt: Instant? = null,
)

/** No denominator, availability guarantee or episode identity is inferred from aggregate counts. */
fun episodeCoverageText(coverage: EpisodeSourceCoverage, seasonNumber: Int? = null): String? {
    if (!coverage.valid) return null
    val season = seasonNumber?.let { number -> coverage.seasons.singleOrNull { it.seasonNumber == number } ?: return null }
    val state = season?.state ?: coverage.state
    val label = if (state == "partial") "Partial episode coverage" else "Episode coverage unknown"
    val found = season?.foundEpisodes ?: coverage.foundEpisodes
    val missing = season?.notFoundEpisodes ?: coverage.notFoundEpisodes
    val unknown = season?.unknownEpisodes ?: coverage.unknownEpisodes
    return "$label. Observed checks: $found with candidates, $missing without candidates, $unknown unknown. Full series coverage is unknown."
}

/** A hidden, failed, stale or replaced view cannot leave aggregate evidence on screen. */
fun episodeCoverageText(evidence: EpisodeSourceEvidence, seasonNumber: Int, now: Instant = Instant.now()): String? {
    val received = evidence.receivedAt ?: return null
    if (received.isAfter(now) || Duration.between(received, now) >= Duration.ofSeconds(30)) return null
    return evidence.coverage?.let { episodeCoverageText(it, seasonNumber) }
}
