package com.strmr.tv.player

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.LibraryPlaybackInfo
import com.strmr.tv.api.libraryPlayback
import com.strmr.tv.library.LibraryVersion
import com.strmr.tv.library.ProfileDataContext

/**
 * Plays a library copy (local, Plex or Jellyfin) instead of searching: the server names the file's
 * stream path and streams it like any other source. There is no server track pick, so the
 * profile's languages choose the audio and subtitles once the player has read them. `known` is
 * the file's playback info when the caller already fetched it.
 */
fun PlaybackCoordinator.playLibrary(request: PlaybackRequest, version: LibraryVersion, known: LibraryPlaybackInfo? = null,expectedContext:Long=ProfileDataContext.current, startup: PlaybackStartupIntent? = null) {
    val expected = expectedContext
    if(expected!=ProfileDataContext.current) { startup?.finish(StartupOutcome.OWNER_LOST); return }
    begin(request.copy(libraryVersion = version), "Preparing…", expectedContext = expected, startup = startup, resolve = {
        val playback = known ?: ApiClient.libraryPlayback(version.id, expected)
        ResolvedStream(playback.streamPath, null, version.releaseName, pickTracksOnDevice = true, libraryVersion = version, libraryPlayback = playback)
    })
}

/** Keep a combined file out of episode progress, Up Next and catalogue actions. */
internal fun libraryPlaybackRequest(request: PlaybackRequest, version: LibraryVersion, playback: LibraryPlaybackInfo): PlaybackRequest {
    require(playback.itemId == version.id && playback.streamPath.isNotBlank()) { "The selected library file changed. Select it again." }
    val selected = request.copy(libraryVersion = version)
    if (!playback.requiresFilePlayback) return selected
    return selected.copy(
        title = request.title.copy(
            titleId = playback.streamPath, name = version.item.fileName?.takeIf { it.isNotBlank() } ?: "${request.title.name} (combined episode file)",
            isMovie = true, tmdbId = null, imdbId = null, tvdbId = null, fromLibrary = true,
        ),
        episode = null, start = null, following = emptyList(), episodeOrderKnown = false,
        library = com.strmr.tv.library.LibraryVersions(listOf(version), emptyMap()),
    )
}

internal fun LibraryPlaybackInfo.combinedFileMessage(): String =
    "This file contains multiple episodes. Playback starts from the beginning. " +
        "Progress and watched status apply to this file, not to individual episodes. Up Next is unavailable."

/** Library preflight and the explicit file choice finish before any player/cast progress target. */
internal suspend fun resolvedLibraryRequest(
    request: PlaybackRequest, stream: ResolvedStream, consent: CombinedLibraryFileConsent, expectedContext: Long,
): PlaybackRequest {
    val version = stream.libraryVersion ?: return request
    val playback = stream.libraryPlayback ?: return request.copy(libraryVersion = version)
    require(stream.path == playback.streamPath) { "The selected library file changed. Select it again." }
    val selected = libraryPlaybackRequest(request, version, playback)
    if (playback.requiresFilePlayback && !consent.ask(selected.title.name, playback.combinedFileMessage(), expectedContext)) {
        throw kotlinx.coroutines.CancellationException("Combined file playback cancelled")
    }
    return selected
}
