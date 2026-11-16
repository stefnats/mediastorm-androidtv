package com.strmr.mobile.downloads

import android.net.Uri
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.StartPoint
import com.strmr.tv.api.resumeState
import com.strmr.tv.api.ResumeState
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.app.AppLog
import com.strmr.tv.player.PlaybackCoordinator
import com.strmr.tv.player.PlaybackRequest
import com.strmr.tv.player.PlayerSession
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Plays a finished download in the phone player from its file (the iPhone app's
 * `DownloadPlayback`). Progress still goes to the server under the title's ids, so watch
 * history and resume carry over; offline the reports are held ([OfflineProgress]) and sent
 * later. The player leaves out what needs the server for a local file (seek previews, Auto
 * Switch, Switch Source); there is no Up Next, as only this file is on the device.
 */
object DownloadPlayback {
    fun request(item: DownloadItem, start: StartPoint?): PlaybackRequest {
        val episode = if (item.seasonNumber != null && item.episodeNumber != null) {
            PlaybackRequest.EpisodeInfo(item.seasonNumber, item.episodeNumber, name = item.episodeName,
                                        itemId = item.episodeItemId, overview = item.episodeOverview)
        } else null
        return PlaybackRequest(item.target, episode, start, backdrops = listOfNotNull(item.backdropUrl))
    }

    /**
     * Where to offer resuming: the server's resume point when it answers within 2.5 s, else
     * the one kept on the device.
     */
    suspend fun resumePoint(item: DownloadItem,expectedContext:Long=ProfileDataContext.current): StartPoint? {
        val context=OfflineAccess.capture()
        OfflineAccess.require(context,item)
        ProfileDataContext.requireCurrent(expectedContext)
        val mediaType = if (item.isMovie) "movie" else "episode"
        val point=resolveDownloadResume(expectedContext,{ApiClient.resumeState(mediaType,item.progressItemId,expectedContext)},{OfflineProgress.resumePoint(item)})
        OfflineAccess.require(context,item)
        return point
    }

    /** Opens the player on the file; returns an error message when it can't. */
    fun play(item: DownloadItem, start: StartPoint?): String? {
        val context=OfflineAccess.current() ?: return OfflineAccess.unavailableMessage()
        val file=try {OfflineAccess.require(context,item);DownloadStorage.file(item,context)}catch(_:Exception) {return OfflineAccess.CONTEXT_REQUIRED}
        if (!file.isFile) return "The downloaded file is missing."
        if(runCatching {OfflineAccess.require(context,item)}.isFailure)return OfflineAccess.CONTEXT_REQUIRED
        PlaybackCoordinator.playSession(
            PlayerSession(
                request = request(item, start),
                url = Uri.fromFile(file).toString(),
                tracks = item.tracks,
                // streamPath stays the server path: online subtitles and skip segments look it up there.
                streamPath = item.streamPath,
                releaseName = item.releaseName ?: item.fileName,
                pickTracksOnDevice = item.tracks == null,
                sourceLabel = "Downloaded",
                localFile = true,
                progressSink = OfflineProgress.sink(item,context),
                localFileAccess={runCatching {OfflineAccess.require(context,item);true}.getOrDefault(false)},
            ),
        )
        return null
    }
}

/** A denied resume read must not silently become permission to play the local file. */
internal suspend fun resolveDownloadResume(expected:Long,read:suspend ()->ResumeState?,offline:()->StartPoint?):StartPoint? {
    ProfileDataContext.requireCurrent(expected)
    val server=withTimeoutOrNull(2_500) {read()}
    ProfileDataContext.requireCurrent(expected)
    return server?.startPoint ?: offline()
}
