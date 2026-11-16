package com.strmr.tv.ui.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.strmr.tv.api.SourceResult
import com.strmr.tv.player.ManualSourceTracks

@Composable
fun rememberSourceTracks(expectedContext: Long = com.strmr.tv.library.ProfileDataContext.current): SourceTracks {
    val scope = rememberCoroutineScope()
    return remember(expectedContext) { SourceTracks(scope,expectedContext) }
}

/** The open result's panel ([SourceTracks]); Play hands the result and its tracks to [onPlay]. */
@Composable
fun SourceTracks.Panel(title: com.strmr.tv.api.DetailsTarget? = null, episode: com.strmr.tv.api.Episode? = null,
    expectedContext: Long = com.strmr.tv.library.ProfileDataContext.current, onPlay: (SourceResult, ManualSourceTracks?) -> Unit) {
    val result = openResult ?: return
    val snapshot = probeState(result)
    SourceTracksDialog(
        release = release(result),
        state = snapshot,
        defaults = trackDefaults,
        choice = choice(result),
        mediaFacts = com.strmr.tv.ui.rememberMediaFacts(expectedContext,
            evidence=(snapshot.probe as? com.strmr.tv.api.TrackProbe.Tracks)?.mediaEvidence,
            target=title,episode=episode,originCandidateVersion=result.mediaEvidence?.let { it.candidateVersion ?: if(it.probed==null)it.sourceVersion else null }),
        onChoice = { choose(result, it, snapshot) },
        onRetry = { retry(result, snapshot) },
        onPlay = { play(result,snapshot,onPlay) },
        onDismiss = { close() },
    )
}
