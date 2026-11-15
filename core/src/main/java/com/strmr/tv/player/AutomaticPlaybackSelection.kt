package com.strmr.tv.player

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ApiError
import com.strmr.tv.api.Episode
import com.strmr.tv.api.PlaybackSettings
import com.strmr.tv.api.PrequeueStatus
import com.strmr.tv.api.libraryPlayback
import com.strmr.tv.library.LibraryVersion
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.ui.details.resolveContentLocalPreference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A decision for one preparation, never retained as the next episode's policy. */
internal sealed interface PlaybackSourcePlan {
    data class Library(val version: LibraryVersion, val choices: List<LibraryVersion>) : PlaybackSourcePlan
    data class Prequeue(val automaticLibrarySelection: Boolean) : PlaybackSourcePlan
}

internal suspend fun requirePlaybackContext(expected: Long) {
    currentCoroutineContext().ensureActive()
    ProfileDataContext.requireCurrent(expected)
}

/** Both Play and prefetch use this gate; supported servers own ordering and eligibility. */
internal suspend fun selectPlaybackSource(
    request: PlaybackRequest,
    settings: PlaybackSettings?,
    expected: Long,
    legacyPreference: suspend (PlaybackRequest, Long) -> Boolean? = ::legacyContentPreference,
): PlaybackSourcePlan {
    requirePlaybackContext(expected)
    request.libraryVersion?.let { return PlaybackSourcePlan.Library(it, listOf(it)) }
    if (request.sourceSelection == PlaybackRequest.SourceSelection.SEARCH_ONLY) return PlaybackSourcePlan.Prequeue(false)
    if (!request.title.fromLibrary && (settings == null || !settings.sourcePolicyAvailable))
        throw ApiError.NotReady("Source settings could not be checked. Try again.")
    if (!request.title.fromLibrary && settings?.automaticLibrarySelectionSupported == true) return PlaybackSourcePlan.Prequeue(true)
    // Legacy servers retain their old library-first path. Do not claim that their profile
    // preference supports automatic Plex/Jellyfin fallback or track eligibility.
    val preference = if (request.title.fromLibrary) null else legacyPreference(request, expected)
    requirePlaybackContext(expected)
    val copies = if (preference == false) emptyList() else request.library?.versions(request.episode?.season, request.episode?.episode).orEmpty()
    return copies.firstOrNull()?.let { PlaybackSourcePlan.Library(it, copies) } ?: PlaybackSourcePlan.Prequeue(false)
}

private suspend fun legacyContentPreference(request: PlaybackRequest, expected: Long): Boolean? =
    resolveContentLocalPreference(request.title, request.episode?.let {
        Episode(it.metadataEpisodeId.orEmpty(), seasonNumber = it.season, episodeNumber = it.episode, numbering = it.numbering)
    }, expected)

/** The actual automatic coordinator resolver, with transport boundaries injectable off-device. */
internal class CatalogPlaybackResolver(
    private val prequeue: suspend (PlaybackRequest, Boolean, Long) -> PrequeueStatus,
    private val library: suspend (LibraryVersion, Long) -> ResolvedStream = { version, expected ->
        val playback = ApiClient.libraryPlayback(version.id, expected)
        ResolvedStream(playback.streamPath, null, version.releaseName, pickTracksOnDevice = true, libraryVersion = version)
    },
    private val legacyPreference: suspend (PlaybackRequest, Long) -> Boolean? = ::legacyContentPreference,
) {
    suspend fun resolve(
        request: PlaybackRequest,
        settings: PlaybackSettings?,
        picks: PrequeuePick?,
        expected: Long,
        chooseLegacyLibrary: (() -> Unit)? = null,
    ): ResolvedStream? {
        val plan = selectPlaybackSource(request, settings, expected, legacyPreference)
        val result = when (plan) {
            is PlaybackSourcePlan.Library -> {
                if (plan.choices.size > 1 && chooseLegacyLibrary != null) {
                    requirePlaybackContext(expected)
                    chooseLegacyLibrary()
                    return null
                }
                library(plan.version, expected).copy(libraryVersion = plan.version)
            }
            is PlaybackSourcePlan.Prequeue -> resolvedPrequeueStream(prequeue(request, plan.automaticLibrarySelection, expected), picks)
        }
        requirePlaybackContext(expected)
        return result
    }
}

/** Keep server-selected tracks and the original report/retry envelope for every source. */
internal fun resolvedPrequeueStream(status: PrequeueStatus, picks: PrequeuePick?): ResolvedStream {
    val tracks = picks?.tracksFor(status)
    if (tracks == null || tracks.choice.isEmpty) return ResolvedStream(
        requireNotNull(status.streamPath), status.trackSelection, status.displayName, status.subtitleTracks.orEmpty(),
        source = prequeueSourceHints(status), prequeueId = status.prequeueId,
    )
    val applied = ManualTracks.applied(status.trackSelection, tracks.choice, tracks.audio, tracks.subtitles)
    return ResolvedStream(requireNotNull(status.streamPath), applied, status.displayName, status.subtitleTracks.orEmpty(),
        manualTracks = true, source = prequeueSourceHints(status), prequeueId = status.prequeueId,
        explicitAudioLanguage = tracks.explicitAudioLanguage)
}

internal fun prequeueSourceHints(status: PrequeueStatus) = SourceHints(
    serviceType = status.serviceType?.ifEmpty { null } ?: status.selectedResult?.serviceType?.ifEmpty { null },
    debridProvider = status.debridProvider?.ifEmpty { null },
    indexer = status.selectedResult?.indexer?.ifEmpty { null },
    attributes = status.selectedResult?.attributes.orEmpty() + status.resultAttributes.orEmpty(),
    dolbyVisionProfile = status.dolbyVisionProfile?.ifEmpty { null },
    hdr10 = status.hasHdr10,
)
