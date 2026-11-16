package com.strmr.tv.api

import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject

// Manual selection: probing one result's audio and subtitle tracks (the Expo app's long press).

/** An audio stream of a probed release; `index` is its container (ffprobe) stream number. */
@Serializable
data class ProbedAudioTrack(
    val index: Int,
    val language: String = "",
    val codec: String = "",
    val profile: String? = null,
    val title: String? = null,
)

/** A subtitle stream of a probed release; `index` is its container (ffprobe) stream number. */
@Serializable
data class ProbedSubtitleTrack(
    val index: Int,
    val language: String = "",
    val codec: String = "",
    val title: String? = null,
    val forced: Boolean = false,
    val isBitmap: Boolean = false,
    val bitmapType: String? = null,
)

/** One result's probed tracks, or why there are none. */
sealed interface TrackProbe {
    data class Tracks(val audio: List<ProbedAudioTrack>, val subtitles: List<ProbedSubtitleTrack>, val mediaEvidence: MediaEvidence? = null) : TrackProbe
    data class Failed(val reason: String) : TrackProbe
}

/** `POST /api/usenet/health` with `probeForTracks`. */
@Serializable
internal data class UsenetTrackProbeHealth(
    val healthy: Boolean = false,
    val status: String = "",
    val tracksProbed: Boolean = false,
    val audioTracks: List<ProbedAudioTrack>? = null,
    val subtitleTracks: List<ProbedSubtitleTrack>? = null,
    val trackProbeError: String? = null,
    val mediaEvidence: MediaEvidence? = null,
)

/** `POST /api/debrid/cached` with `verifyUncached`. */
@Serializable
internal data class DebridTrackProbeHealth(
    val cached: Boolean = false,
    val status: String = "",
    val errorMessage: String? = null,
    val audioTracks: List<ProbedAudioTrack>? = null,
    val subtitleTracks: List<ProbedSubtitleTrack>? = null,
    val trackProbeError: String? = null,
    val mediaEvidence: MediaEvidence? = null,
    val tracksLoading: Boolean = false,
)

/**
 * Probes one manual-selection result for its tracks, as the Expo app's long press does:
 * debrid results through `/debrid/cached` (`verifyUncached`, re-asked every 2 s while the
 * server says `tracksLoading`), everything else through `/usenet/health` (`probeForTracks`).
 * An unhealthy / uncached release or a probe error is a [TrackProbe.Failed].
 */
suspend fun ApiClient.probeTracks(result: SourceResult, expectedContext: Long = ProfileDataContext.current): TrackProbe {
    currentCoroutineContext().ensureActive()
    val profile = inContext(expectedContext) { profileId }
    if (result.serviceType.lowercase() == "debrid") {
        val body = trackProbeRequestBody(result,profile,debrid=true)
        val deadline = System.currentTimeMillis() + 120_000
        while (true) {
            val h = send<DebridTrackProbeHealth>("POST", "/api/debrid/cached", body = body, timeoutMs = 90_000, expectedContext = expectedContext)
            if (!h.cached) return TrackProbe.Failed(h.errorMessage?.ifEmpty { null } ?: "Not cached")
            if (h.tracksLoading && System.currentTimeMillis() < deadline) {
                delay(2_000)
                continue
            }
            h.trackProbeError?.ifEmpty { null }?.let { return TrackProbe.Failed(it) }
            if (h.tracksLoading) return TrackProbe.Failed("Timed out reading the tracks")
            return TrackProbe.Tracks(h.audioTracks.orEmpty(), h.subtitleTracks.orEmpty(), h.mediaEvidence)
        }
    }
    val body = trackProbeRequestBody(result,profile,debrid=false)
    val h = send<UsenetTrackProbeHealth>("POST", "/api/usenet/health", body = body, timeoutMs = 90_000, expectedContext = expectedContext)
    if (!h.healthy) return TrackProbe.Failed(if (h.status.isNotEmpty()) "Unhealthy (${h.status.replace('_', ' ')})" else "Unhealthy")
    h.trackProbeError?.ifEmpty { null }?.let { return TrackProbe.Failed(it) }
    return TrackProbe.Tracks(h.audioTracks.orEmpty(), h.subtitleTracks.orEmpty(), h.mediaEvidence)
}

/** Snapshot correlation is not an uploaded proof; the server resolves its own candidate before probing. */
internal fun trackProbeRequestBody(result:SourceResult,profile:String,debrid:Boolean):JsonObject=buildJsonObject {
    put("result",result.raw);put("profileId",profile)
    if(debrid){put("quickOnly",false);put("verifyUncached",true)}else put("probeForTracks",true)
    result.responseScope?.let {put("snapshotId",it);put("sourceIndex",result.index)}
}
