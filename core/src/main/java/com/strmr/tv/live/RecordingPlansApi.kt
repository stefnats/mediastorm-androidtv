package com.strmr.tv.live

import com.strmr.tv.api.ApiClient
import com.strmr.tv.library.ProfileDataContext
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class RecordingPlan(
    val checkedAt: String = "",
    val complete: Boolean = false,
    val recordings: List<RecordingPlanItem> = emptyList(),
    val candidate: RecordingPlanItem? = null,
)

@Serializable
data class RecordingPlanItem(
    val recordingId: String? = null,
    val state: String = "unavailable",
    val start: String = "",
    val end: String = "",
    val maxStreams: Int = 0,
    val peakScheduledConnections: Int = 0,
    val firstConflictStart: String? = null,
    val firstConflictEnd: String? = null,
) {
    /** A malformed or future response must never silently bypass the warning. */
    val isClear: Boolean get() = state == "clear" && maxStreams > 0 &&
        peakScheduledConnections in 1..maxStreams &&
        runCatching { Instant.parse(end).isAfter(Instant.parse(start)) }.getOrDefault(false)

    val summary: String get() = when {
        state == "conflict" && maxStreams > 0 && peakScheduledConnections > maxStreams ->
            "Recording capacity conflict: up to $peakScheduledConnections scheduled inputs for $maxStreams connections." +
                (firstConflictStart?.takeIf { RecordingRules.instant(it) != null }?.let { " First conflict: ${RecordingRules.dateTime(it)}." } ?: "")
        isClear -> "No scheduled recording conflict found ($peakScheduledConnections/$maxStreams). Live or external use may still occupy connections."
        state == "unknown" -> "Recording capacity is uncertain: the limit or part of the schedule is unknown."
        else -> "The recording capacity forecast is unavailable."
    }
}

/** Exact immutable payload shared by preview and creation, never persisted. */
@ConsistentCopyVisibility
data class RecordingDraft internal constructor(
    val context: Long,
    val profileId: String,
    val channelId: String,
    val channelName: String,
    val sourceUrl: String,
    val title: String,
    val start: String,
    val stop: String,
    val tvgId: String? = null,
    val description: String? = null,
    val epg: Boolean = true,
) {
    internal fun body(): JsonObject = buildJsonObject {
        put("profileId", profileId); put("channelId", channelId); put("channelName", channelName)
        put("sourceUrl", sourceUrl); put("title", title); put("start", start); put("stop", stop)
        tvgId?.takeIf { it.isNotEmpty() }?.let { put("tvgId", it) }
        description?.takeIf { it.isNotEmpty() }?.let { put("description", it) }
    }
}

fun ApiClient.programRecordingDraft(program: EpgProgram, channel: LiveChannel, context: Long = ProfileDataContext.current): RecordingDraft = inContext(context) {
    RecordingDraft(context, profileId, channel.id, channel.name, channel.url, program.title, program.start, program.stop, channel.tvgId, program.description)
}

fun ApiClient.blockRecordingDraft(channel: LiveChannel, durationMs: Long, context: Long = ProfileDataContext.current): RecordingDraft = inContext(context) {
    require(durationMs > 0)
    val start = Instant.now()
    RecordingDraft(context, profileId, channel.id, channel.name, channel.url, "${channel.name} Live Block", start.toString(), start.plusMillis(durationMs).toString(),
        channel.tvgId, "Scheduled from the Live TV action menu.", epg = false)
}

private fun ApiClient.requireDraft(draft: RecordingDraft) = inContext(draft.context) {
    if (profileId != draft.profileId) throw CancellationException("Recording profile changed")
}

suspend fun ApiClient.previewRecording(draft: RecordingDraft): RecordingPlan {
    requireDraft(draft)
    return send("POST", "/api/live/recordings/preview", body = draft.body(), expectedContext = draft.context, timeoutMs = 10_000)
}

suspend fun ApiClient.createRecording(draft: RecordingDraft) {
    requireDraft(draft)
    sendRaw("POST", if (draft.epg) "/api/live/recordings/epg" else "/api/live/recordings/time-block", body = draft.body(), expectedContext = draft.context, retryTransport = false)
}

suspend fun ApiClient.recordingPlan(expectedContext: Long = ProfileDataContext.current): RecordingPlan {
    val profile = inContext(expectedContext) { profileId }
    return send("GET", "/api/live/recordings/plan", mapOf("profileId" to profile), expectedContext = expectedContext, timeoutMs = 10_000, promptOnLock = false)
}
