package com.strmr.tv.player

/** Identity-free local report. This snapshot is never persisted or sent with device logs. */
data class PlaybackStartupCohort(
    val origin: String,
    val entry: String,
    val input: String,
    val preparation: String,
    val completed: Int,
    val rendered: Int,
    val outcomes: Map<String, Int>,
    val renderedP95Millis: Long?,
)

data class PlaybackStartupReport(
    val completed: Int,
    val inFlight: Int,
    val cohorts: List<PlaybackStartupCohort>,
) {
    fun text(): String = buildString {
        appendLine("Last $completed completed starts in this app process (maximum 128).")
        appendLine("In flight: $inFlight. Profiles are not identified; this window may span profile changes.")
        appendLine("Click to first frame: User action. Coordinator only starts are separate and exclude earlier UI work.")
        appendLine("Rendered p95: nearest rank; at least 20 rendered samples per matching cohort. This is a display threshold, not a confidence guarantee.")
        appendLine("Render completion uses all completed starts, including failures and cancellations. External handoff is not native rendered success.")
        appendLine("Preparation READY and supplied input describe observations, not byte-cache hits or decoder readiness.")
        appendLine("Home-LAN targets: ready input p95 <=3s; cold start p95 <=15s. Qualification: Not assessed (network/cache/cold conditions are not proven).")
        if (cohorts.isEmpty()) appendLine("No completed samples yet.")
        for (cohort in cohorts) {
            appendLine()
            appendLine("${cohort.origin} / ${cohort.entry}")
            appendLine("Input: ${cohort.input}; preparation at action: ${cohort.preparation}")
            appendLine("Rendered: ${cohort.rendered}/${cohort.completed} completed starts")
            appendLine(cohort.renderedP95Millis?.let { "Rendered p95: ${it}ms (successful first frames only)" }
                ?: "Rendered p95: insufficient samples (${cohort.rendered}/20)")
            appendLine(cohort.outcomes.entries.joinToString("; ") { "${it.key}: ${it.value}" })
        }
        append("Local memory only. No content IDs, URLs, owner identities or telemetry.")
    }
}

internal fun startupReport(samples: List<StartupRecord>, inFlight: Int): PlaybackStartupReport {
    val bounded = samples.takeLast(128)
    val cohorts = bounded.groupBy { listOf(it.origin.name, it.entry.name, it.input.name, it.preparation.name) }
        .map { (key, group) ->
            val rendered = group.filter { it.outcome == StartupOutcome.RENDERED }.map { it.elapsedMillis }.sorted()
            val p95 = if (rendered.size < 20) null else rendered[((95 * rendered.size + 99) / 100) - 1]
            PlaybackStartupCohort(key[0], key[1], key[2], key[3], group.size, rendered.size,
                StartupOutcome.entries.associate { outcome -> outcome.name to group.count { it.outcome == outcome } }, p95)
        }
    return PlaybackStartupReport(bounded.size, inFlight.coerceIn(0, 2), cohorts)
}

/** Actual About screens read a fresh immutable snapshot only when opened or explicitly refreshed. */
object PlaybackStartupDiagnostics {
    fun snapshot(): PlaybackStartupReport = PlaybackStartupTiming.report()
}
