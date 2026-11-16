package com.strmr.tv.live

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ApiError
import com.strmr.tv.library.ProfileDataContext
import kotlinx.serialization.Serializable
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
    mediaUrl(route)
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
    return LiveStream(mediaUrl(path), start.sessionId)
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
