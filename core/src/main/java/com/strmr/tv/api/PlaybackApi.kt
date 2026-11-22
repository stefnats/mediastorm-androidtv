package com.strmr.tv.api

import com.strmr.tv.adaptive.ThroughputStore
import com.strmr.tv.app.Config
import com.strmr.tv.player.ClientTrackSelector
import com.strmr.tv.player.SubtitleStyle
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.library.optionalProfileRead
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Prequeue, streams, progress and the profile's playback settings.

/** Carries this route's throughput estimate, as Expo's `prequeuePlayback` does, for adaptive caps. */
suspend fun ApiClient.startPrequeue(body: JsonObject, expectedContext: Long? = null): PrequeueStartResponse =
    send("POST", "/api/playback/prequeue", body = body, headers = ThroughputStore.headers(), expectedContext = expectedContext)

/** Existing explicit selector protocol; the opaque ID never selects a replacement profile. */
internal fun ApiClient.preparationQuery(expectedContext: Long, values: Map<String, String> = emptyMap()): Map<String, String> =
    inContext(expectedContext) { values + ("profileId" to profileId) }

suspend fun ApiClient.prequeueStatus(id: String, expectedContext: Long = ProfileDataContext.current): PrequeueStatus =
    send("GET", "/api/playback/prequeue/${segment(id)}", preparationQuery(expectedContext), expectedContext = expectedContext)

/** What the server stored for a progress report, its resume decision, and whether it wants the player off this source. */
@Serializable
data class ProgressReply(
    val percentWatched: Double = 0.0,
    val resume: ResumeState? = null,
    val migrationRequested: Boolean = false,
    val migrationReason: String? = null,
    val itemId: String? = null,
)

/** Throws on failure; the caller logs it, and the next report retries. */
suspend fun ApiClient.reportProgress(body: JsonObject, expectedContext: Long? = null): ProgressReply =
    send("POST", "$profilePath/history/progress", body = body, promptOnLock = false, expectedContext = expectedContext)

/**
 * Raw-file stream URL for the player. transmux=0 keeps the backend from remuxing: ExoPlayer
 * demuxes MKV / MP4 itself. Auth travels in [mediaHeaders]; the profile unlock also rides in
 * the query, so it survives anything that drops custom headers. `profileId` and the media
 * metadata let the stream tracker attribute the stream on the admin dashboard.
 */
fun ApiClient.streamUrl(streamPath: String, metadata: Map<String, String> = emptyMap()): String {
    val query = metadata.toMutableMap()
    query["path"] = streamPath
    query["transmux"] = "0"
    query["profileId"] = profileId
    profileName?.takeIf { it.isNotEmpty() }?.let { query["profileName"] = it }
    unlocks.token(profileId)?.let { query["profileUnlock"] = it }
    return url("/api/video/stream", query).toString()
}

/** Headers the player attaches to every media fetch. */
val ApiClient.mediaHeaders: Map<String, String>
    get() = buildMap {
        put("X-Client-ID", Config.clientId)
        remoteCredential?.let { put("X-Remote-Access-Credential", it) }
        token?.let { put("Authorization", "Bearer $it") }
        unlocks.token(profileId)?.let { put(ApiClient.UNLOCK_HEADER, it) }
    }

/**
 * The playback settings this client reads, layered as the Expo app layers them: this
 * device's override from `/api/clients/{id}/settings`, then the profile's settings (defaults
 * already filled in by the server).
 */
data class PlaybackSettings(
    /** Streams start resolving as a details page opens (`playback.disablePrequeue` off). */
    val prequeueOnOpen: Boolean = true,
    /** The details page plays the trailer behind the header (`playback.autoPlayTrailersTV`). */
    val autoPlayTrailers: Boolean = false,
    /** Next Episode counts down over the credits and plays by itself (`playback.creditsAutoSkip`). */
    val creditsAutoSkip: Boolean = false,
    /** Profile choice, independent of credits and never overridden by the device. */
    val nextEpisodeAutoPlay: Boolean = false,
    val introAutoSkip: Boolean = false,
    val recapAutoSkip: Boolean = false,
    /** Episodes with no known credits are read on screen for them (`playback.creditsDetectionEnabled`). */
    val creditsDetection: Boolean = false,
    /** The Expo app's subtitle style (`playback.subtitle*`) under this device's; null from servers that send none. */
    val subtitleStyle: SubtitleStyle.Server? = null,
    /** Subtitle language (`playback.preferredSubtitleLanguage`), for when the server sent no track choice. */
    val preferredSubtitleLanguage: String? = null,
    /** off | forced-only | foreign-audio | on (the older always / auto mapped), for when the server sent no track choice. */
    val preferredSubtitleMode: String? = null,
    /** Audio language (`playback.preferredAudioLanguage`). */
    val preferredAudioLanguage: String? = null,
    /** The languages the player's menus offer (`playback.allowedTrackLanguages`); empty allows all. */
    val allowedTrackLanguages: List<String> = emptyList(),
    /** Effective profile/server preference; null when the server did not provide it. */
    val preferLocalFiles: Boolean? = null,
    /** Response-only capability; automatic catalog playback can include all library sources. */
    val automaticLibrarySelectionSupported: Boolean = false,
    /** Internal read result: an unavailable policy must not masquerade as an old server. */
    val sourcePolicyAvailable: Boolean = true,
    /** Match this device refresh rate to the observed video frame rate. */
    val matchFrameRate: Boolean = false,
    /** Explicit profile/global opt-in; no device override or inferred enablement. */
    val autoCheckAudioLanguage: Boolean = false,
)

@Serializable
internal data class PlaybackFields(
    val disablePrequeue: Boolean? = null,
    val autoPlayTrailersTV: Boolean? = null,
    val creditsAutoSkip: Boolean? = null,
    val nextEpisodeAutoPlay: Boolean? = null,
    val introAutoSkip: Boolean? = null,
    val recapAutoSkip: Boolean? = null,
    /** The older name of `creditsAutoSkip`. */
    val creditsDetection: Boolean? = null,
    val creditsDetectionEnabled: Boolean? = null,
    val subtitleSize: Double? = null,
    val subtitleColor: String? = null,
    val subtitleOpacity: Double? = null,
    val subtitleBold: Boolean? = null,
    val subtitleOutlineEnabled: Boolean? = null,
    val subtitleOutlineColor: String? = null,
    val subtitleOutlineWeight: Double? = null,
    val subtitleBackgroundEnabled: Boolean? = null,
    val subtitleBackgroundColor: String? = null,
    val subtitleBackgroundOpacity: Double? = null,
    val preferredSubtitleLanguage: String? = null,
    val preferredSubtitleMode: String? = null,
    val preferredAudioLanguage: String? = null,
    val allowedTrackLanguages: List<String>? = null,
    val preferLocalFiles: Boolean? = null,
    val matchFrameRate: Boolean? = null,
    val autoCheckAudioLanguage: Boolean? = null,
) {
    /** The `subtitle*` fields of the same object. */
    val style: SubtitleStyle.Server
        get() = SubtitleStyle.Server(
            subtitleSize, subtitleColor, subtitleOpacity, subtitleBold, subtitleOutlineEnabled, subtitleOutlineColor,
            subtitleOutlineWeight, subtitleBackgroundEnabled, subtitleBackgroundColor, subtitleBackgroundOpacity,
        )

    val autoSkip: Boolean? get() = creditsAutoSkip ?: creditsDetection
}

@Serializable
internal data class UserPlayback(
    val playback: PlaybackFields? = null,
    val automaticLibrarySelectionSupported: Boolean = false,
)

suspend fun ApiClient.playbackSettings(expectedContext: Long = ProfileDataContext.current): PlaybackSettings = coroutineScope {
    val captured = expectedContext
    val (path, profile) = inContext(captured) { profilePath to profileId }
    val user = async {
        ProfileDataContext.requireCurrent(captured)
        vodOptionalRead { send<UserPlayback>("GET", "$path/settings", promptOnLock = false, timeoutMs = 10_000, expectedContext = captured) }
    }
    val device = async {
        ProfileDataContext.requireCurrent(captured)
        vodOptionalRead {
            send<PlaybackFields>("GET", "/api/clients/${segment(Config.clientId)}/settings", mapOf("userId" to profile),
                                 promptOnLock = false, timeoutMs = 10_000, expectedContext = captured)
        }
    }
    val (u, d) = user.await() to device.await()
    ProfileDataContext.requireCurrent(captured)
    mergePlaybackSettings(u?.playback, d, u?.automaticLibrarySelectionSupported == true, u != null)
}

internal fun mergePlaybackSettings(
    u: PlaybackFields?, d: PlaybackFields?, automaticLibrarySelectionSupported: Boolean = false,
    sourcePolicyAvailable: Boolean = u != null,
): PlaybackSettings =
    PlaybackSettings(
        prequeueOnOpen = !(d?.disablePrequeue ?: u?.disablePrequeue ?: false),
        autoPlayTrailers = d?.autoPlayTrailersTV ?: u?.autoPlayTrailersTV ?: false,
        // `d` is the admin's per-device setting on the server (not storage on
        // this device); it still decides the credits countdown, as on tvOS and
        // Expo. Intro and recap have no per-device setting and follow the profile.
        creditsAutoSkip = d?.autoSkip ?: u?.autoSkip ?: false,
        nextEpisodeAutoPlay = u?.nextEpisodeAutoPlay ?: (u != null),
        introAutoSkip = u?.introAutoSkip ?: false,
        recapAutoSkip = u?.recapAutoSkip ?: false,
        creditsDetection = d?.creditsDetectionEnabled ?: u?.creditsDetectionEnabled ?: false,
        subtitleStyle = d?.style?.mergedOver(u?.style) ?: u?.style,
        preferredSubtitleLanguage = (d?.preferredSubtitleLanguage?.ifEmpty { null } ?: u?.preferredSubtitleLanguage)?.ifEmpty { null },
        preferredSubtitleMode = when (val mode = d?.preferredSubtitleMode?.ifEmpty { null } ?: u?.preferredSubtitleMode) {
            "always" -> "on"
            "auto" -> "forced-only"
            "smart", "foreign" -> ClientTrackSelector.FOREIGN_AUDIO
            "" -> null
            else -> mode
        },
        preferredAudioLanguage = (d?.preferredAudioLanguage?.ifEmpty { null } ?: u?.preferredAudioLanguage)?.ifEmpty { null },
        allowedTrackLanguages = d?.allowedTrackLanguages?.ifEmpty { null } ?: u?.allowedTrackLanguages ?: emptyList(),
        // Source ordering has no device override. The server also applies content scopes.
        preferLocalFiles = u?.preferLocalFiles,
        automaticLibrarySelectionSupported = automaticLibrarySelectionSupported,
        sourcePolicyAvailable = sourcePolicyAvailable,
        matchFrameRate = d?.matchFrameRate ?: u?.matchFrameRate ?: false,
        autoCheckAudioLanguage = u?.autoCheckAudioLanguage ?: false,
    )

/** Missing optional settings may use legacy defaults; an authorization rejection may not. */
internal suspend fun <T> vodOptionalRead(read:suspend ()->T):T? = try {read()} catch(error:Exception) {
    error.rethrowVodDenial();null
}
