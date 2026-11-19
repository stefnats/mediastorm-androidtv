package com.strmr.tv.live

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ApiError
import com.strmr.tv.library.ProfileDataContext
import kotlinx.serialization.Serializable
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Serializable
private data class HlsStart(val sessionId: String? = null, val playlistUrl: String? = null, val growing: Boolean? = null)

/** One recording from `/api/live/recordings` (the backend's `models.Recording`, the fields the app shows). */
@Serializable
data class Recording(
    val id: String,
    val status: String,
    val ruleId: String? = null,
    val channelId: String = "",
    val tvgId: String? = null,
    val channelName: String = "",
    val title: String = "",
    val description: String? = null,
    val startAt: String = "",
    val endAt: String = "",
    val outputPath: String? = null,
    val outputSizeBytes: Long? = null,
    val actualStartAt: String? = null,
    val actualEndAt: String? = null,
    val error: String? = null,
    val capture: RecordingCapture? = null,
    val userId: String? = null,
    val updatedAt: String? = null,
)

@Serializable
private data class RecordingsResponse(val recordings: List<Recording>? = null)

/** The profile's recordings, every status (Expo's TV Recordings page loads them all and filters locally). */
suspend fun ApiClient.recordings(expectedContext:Long=ProfileDataContext.current): List<Recording> {
    val profile=inContext(expectedContext){profileId}
    return send<RecordingsResponse>("GET", "/api/live/recordings", mapOf("profileId" to profile),expectedContext=expectedContext).recordings.orEmpty()
}

/** Cancels a scheduled recording, or stops one in progress (the video so far is kept). */
suspend fun ApiClient.cancelRecording(id: String, expectedContext:Long=ProfileDataContext.current) {
    val profile=inContext(expectedContext){profileId}
    sendRaw("POST", "/api/live/recordings/${segment(id)}/cancel",query=mapOf("profileId" to profile),expectedContext=expectedContext)
}

/** Deletes a recording and its saved video. */
suspend fun ApiClient.deleteRecording(id: String, expectedContext:Long=ProfileDataContext.current) {
    val profile=inContext(expectedContext){profileId}
    sendRaw("DELETE", "/api/live/recordings/${segment(id)}",query=mapOf("profileId" to profile),expectedContext=expectedContext)
}

/**
 * The recording's video (`/live/recordings/{id}/stream`, MPEG-TS), as the Expo player plays it:
 * the whole file of a recording that has finished (or stopped, or failed part way).
 * The query names the profile and title for the server's stream tracker, as Expo's does.
 */
fun ApiClient.recordingStreamUrl(recording: Recording, title: String, expectedContext:Long=ProfileDataContext.current, part:RecordingPart?=null): String = inContext(expectedContext) {
    if(part!=null)require(RecordingCaptureRules.playable(recording,part)) {"Recording part is unavailable"}
    val route=recordingStreamRoute(recording,title,profileId,profileName,part)
    mediaUrl(route, expectedContext)
}

/** Pure request construction; the API wrapper supplies the captured profile under its epoch. */
internal fun recordingStreamRoute(recording:Recording,title:String,profile:String,profileName:String?=null,part:RecordingPart?=null):String {
    if(part!=null)require(RecordingCaptureRules.playable(recording,part)) {"Recording part is unavailable"}
    val q=buildMap {
        put("profileId",profile)
        profileName?.takeIf {it.isNotEmpty()}?.let {put("profileName",it)}
        put("mediaType","channel");put("title",title)
        (recording.tvgId?.ifEmpty {null} ?: recording.channelId.ifEmpty {null})?.let {put("itemId",it)}
        part?.let {put("part",it.index.toString())}
    }
    val query=q.entries.joinToString("&") {"${ApiClient.segment(it.key)}=${ApiClient.segment(it.value)}"}
    return "/live/recordings/${ApiClient.segment(recording.id)}/stream?$query"
}

/**
 * A recording that is still recording, as a server HLS session from offset 0: an EVENT
 * playlist that grows until the recording stops (`/api/video/hls/start?path=recording:…`).
 * No `target`: video and AAC are copied into fMP4 segments.
 */
suspend fun ApiClient.startRecordingHls(recording: Recording, title: String, expectedContext:Long=ProfileDataContext.current): LiveStream {
    ProfileDataContext.requireCurrent(expectedContext)
    val file = recording.outputPath?.substringAfterLast('/')?.ifEmpty { null } ?: "recording.ts"
    val q = inContext(expectedContext) {buildMap {
        put("path", "recording:${recording.id}/$file")
        put("profileId", profileId)
        profileName?.takeIf { it.isNotEmpty() }?.let { put("profileName", it) }
        put("mediaType", "channel")
        put("title", title)
    }
    }
    val start = send<HlsStart>("GET", "/api/video/hls/start", q, timeoutMs = 60_000,expectedContext=expectedContext)
    ProfileDataContext.requireCurrent(expectedContext)
    val path = start.playlistUrl?.ifEmpty { null } ?: throw ApiError.NotReady("The server did not return a playlist for this recording")
    com.strmr.tv.app.AppLog.i("Recordings", "growing HLS session ${start.sessionId} (growing=${start.growing})")
    return LiveStream(mediaUrl(path, expectedContext), start.sessionId)
}

// Scheduling (the Expo Live guide's programme and channel menus)

/** Scheduled, starting and recording ones: what the guide marks. */
suspend fun ApiClient.activeRecordings(): List<Recording> =
    send<RecordingsResponse>("GET", "/api/live/recordings",
                             mapOf("profileId" to profileId, "status" to "pending,starting,running"),
                             promptOnLock = false).recordings.orEmpty()

/** Low-level creation; UI callers use LiveRecordings.scheduling for the forecast. */
suspend fun ApiClient.recordProgram(program: EpgProgram, channel: LiveChannel) =
    createRecording(programRecordingDraft(program, channel))

/** Captures the block once; preview and creation retain this exact time window. */
suspend fun ApiClient.recordTimeBlock(channel: LiveChannel, durationMs: Long) =
    createRecording(blockRecordingDraft(channel, durationMs))

/**
 * Records every airing of [title] on this channel: a title rule scoped to the channel, so +1 /
 * simulcast channels don't record it twice (Expo's Record Series).
 */
suspend fun ApiClient.recordSeries(title: String, channel: LiveChannel, newOnly: Boolean = false) {
    sendRaw("POST", "/api/live/recording-rules", body = kotlinx.serialization.json.buildJsonObject {
        put("profileId", profileId)
        put("matchType", "title")
        put("pattern", title)
        put("channelId", channel.id)
        channel.tvgId?.takeIf { it.isNotEmpty() }?.let { put("tvgId", it) }
        put("channelName", channel.name)
        put("newOnly", newOnly)
    })
}

/** Historical server observation only; never a current file grant or player timeline. */
data class RecordingPartTiming(
    val recordingId: String,
    val partIndex: Int,
    val state: String,
    val reason: String? = null,
    val containerDurationMs: Long? = null,
    val observedAt: String? = null,
    val expiresAt: String? = null,
    val binding: String? = null,
    val observedBytes: Long? = null,
) {
    fun label(): String = if (state == "container_estimate") {
        val seconds = requireNotNull(containerDurationMs) / 1000
        "Observed container estimate: ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    } else "No stored duration estimate."
    fun observationLabel(): String? = observedAt?.let { "Measured on server: $it · Stored until: $expiresAt" }
}

internal data class RecordingTimingRequest(val method: String, val path: String, val query: Map<String, String>)
internal fun recordingTimingRequest(id: String, index: Int, profile: String, inspect: Boolean): RecordingTimingRequest {
    require(Regex("[A-Za-z0-9_-]{1,128}").matches(id) && index in 1..256 && profile.isNotBlank())
    val path = "/api/live/recordings/${ApiClient.segment(id)}/parts/$index/timing" + if (inspect) "/refresh" else ""
    return RecordingTimingRequest(if (inspect) "POST" else "GET", path, mapOf("profileId" to profile))
}

/** Only explicit callers invoke these operations. A refresh has a genuinely empty body. */
suspend fun ApiClient.recordingPartTiming(id: String, index: Int, inspect: Boolean, expected: Long): RecordingPartTiming {
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    val request = inContext(expected) { recordingTimingRequest(id, index, profileId, inspect) }
    val raw = sendRaw(request.method, request.path, request.query, expectedContext = expected,
        timeoutMs = 15_000, retryTransport = false)
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    ProfileDataContext.requireCurrent(expected)
    return decodeRecordingPartTiming(raw, id, index)
}

/** Internal consistency only: no comparison with the phone/TV clock and no renewed TTL. */
internal fun decodeRecordingPartTiming(raw: String, id: String, index: Int): RecordingPartTiming {
    try {
        val root = com.strmr.tv.api.ApiJson.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject ?: error("object")
        fun text(name: String): String = (root[name] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) } ?: error(name)
        fun integer(name: String): Long = (root[name] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeUnless { it.isString }?.longOrNull ?: error(name)
        require(text("recordingId") == id && integer("partIndex") == index.toLong() && index in 1..256)
        val state = text("state")
        val fields = listOf("containerDurationMs", "observedAt", "expiresAt", "binding", "observedBytes")
        if (state == "unknown") {
            require(fields.none { it in root })
            val reason = text("reason")
            require(reason in setOf("no_observation", "ledger_changed", "observation_expired", "probe_unavailable", "unsupported_container"))
            return RecordingPartTiming(id, index, state, reason)
        }
        require(state == "container_estimate")
        val duration = integer("containerDurationMs"); val bytes = integer("observedBytes")
        require(duration > 0 && bytes > 0)
        val observed = text("observedAt"); val expires = text("expiresAt")
        val start = java.time.OffsetDateTime.parse(observed); val end = java.time.OffsetDateTime.parse(expires)
        require(start.offset.totalSeconds == 0 && end.offset.totalSeconds == 0 && end.isAfter(start))
        require(java.time.Duration.between(start, end) <= java.time.Duration.ofMinutes(15))
        val binding = text("binding")
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(binding))
        return RecordingPartTiming(id, index, state, containerDurationMs = duration, observedAt = observed,
            expiresAt = expires, binding = binding, observedBytes = bytes)
    } catch (_: Exception) {
        throw IllegalArgumentException("Recording timing response is unavailable")
    }
}
