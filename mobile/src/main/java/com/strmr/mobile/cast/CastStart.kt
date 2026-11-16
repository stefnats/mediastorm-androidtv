package com.strmr.mobile.cast

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.castMediaUrl
import com.strmr.tv.app.AppLog
import com.strmr.tv.live.EpgProgram
import com.strmr.tv.live.LiveChannel
import com.strmr.tv.live.stopLiveSession
import com.strmr.tv.player.PlaybackRequest
import com.strmr.tv.player.PlaybackSessionContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What a start has built for the receiver: its connection, the media to load, the server HLS
 * session behind it (kept alive, stopped with the cast) and, for a title, what watch progress is
 * reported against (null for a live channel).
 */
internal class PreparedCast(
    val transport: CastTransport,
    val media: CastMedia,
    val hlsSessionId: String?,
    val playback: Pair<PlaybackRequest, String>?,
)

/**
 * Casts a resolved stream ([path], as the local player would open it) to [device] from
 * [position] seconds. [audio] / [subtitle] are container stream indices (null: the server's pick /
 * none). Shows the remote at once; a failure is shown there.
 */
fun CastController.start(
    device: CastDevice, request: PlaybackRequest, path: String, position: Double,
    audio: Int?, subtitle: Int?, subtitleLanguage: String?,context:PlaybackSessionContext=PlaybackSessionContext.capture(),
) {
    if(!ownership.allowed(context))return
    val poster = ApiClient.castMediaUrl(request.title.posterUrl ?: request.episode?.imageUrl,context.epoch)
    val cast = ActiveCast(device, request.title.name, castSubtitle(request), poster)
    AppLog.i(CastController.TAG, "casting ${request.displayTitle} to ${device.name} (${device.kind}) from ${position.toInt()}s")
    begin(cast,context) {ticket->
        when (device.kind) {
            CastDevice.Kind.GOOGLE_CAST -> {
                ownership.require(ticket)
                val connected = GoogleCastService.connect(device)
                connected.ownershipCurrent={ownership.owns(ticket)}
                ticket.transport=connected
                ownership.require(ticket)
                val (media, sessionId) = chromecastMedia(request, path, position, audio, subtitle, subtitleLanguage, poster,context)
                PreparedCast(connected, media, sessionId, request to path)
            }
            CastDevice.Kind.DLNA -> {
                val transport = dlnaTransport(device)
                ticket.transport=transport
                ownership.require(ticket)
                val (media, sessionId) = dlnaMedia(transport, request, path, position, audio, subtitle, poster,context)
                PreparedCast(transport, media, sessionId, request to path)
            }
        }
    }
}

/**
 * Casts a live channel to [device] (the iPhone app's LivePlayer+Cast); [program] is what is on
 * now, shown with the channel's name and logo. No timeline, no progress.
 */
fun CastController.startLive(device: CastDevice, channel: LiveChannel, program: String?,context:PlaybackSessionContext=PlaybackSessionContext.capture()) {
    if(!ownership.allowed(context))return
    val logo = ApiClient.castMediaUrl(channel.logoUrl,context.epoch)
    val cast = ActiveCast(device, channel.name, program, logo, isLive = true, canSeek = false)
    AppLog.i(CastController.TAG, "casting channel ${channel.name} to ${device.name} (${device.kind})")
    begin(cast,context) {ticket->
        requireReachableServer()
        when (device.kind) {
            CastDevice.Kind.GOOGLE_CAST -> {
                ownership.require(ticket)
                val connected = GoogleCastService.connect(device)
                connected.ownershipCurrent={ownership.owns(ticket)}
                ticket.transport=connected
                ownership.require(ticket)
                val opened = openLive(channel, program, forceHls = true,context=context)
                val type = if (opened.sessionId == null) directLiveContentType(channel) else DlnaProtocol.HLS_MIME
                PreparedCast(connected, liveMedia(opened.url, type, channel, program, logo), opened.sessionId, null)
            }
            CastDevice.Kind.DLNA -> {
                val transport = dlnaTransport(device)
                ticket.transport=transport
                ownership.require(ticket)
                val (media, sessionId) = dlnaLiveMedia(transport, channel, program, logo,context)
                PreparedCast(transport, media, sessionId, null)
            }
        }
    }
}

/** "S01E02 · Pilot" under an episode's show name. */
internal fun castSubtitle(request: PlaybackRequest): String? =
    request.episode?.let { listOfNotNull(it.code, it.name?.ifEmpty { null }).joinToString(" · ") }

/**
 * Replaces what was casting, brings the remote up and builds and loads the media on the
 * receiver, a failure shown on the remote.
 */
internal fun CastController.begin(cast:ActiveCast,context:PlaybackSessionContext,prepare:suspend (CastOwnership.Ticket)->PreparedCast) {
    if(!ownership.allowed(context))return
    val previous=ticket
    startJob?.cancel()
    val own=ownership.begin(context,cast.device.id)
    startTicket=own
    startJob=scope.launch {
        var accepted=false
        try {
            previous?.let {finish("replaced by a new cast",stopReceiver=it.deviceId!=cast.device.id,expected=it)}
            ownership.require(own)
            ticket=own;session=cast.copy(generation=own.generation);remoteVisible=true
            ownership.start(own,prepare={prepare(own)}) {ready->
                own.context.use {
                    ownership.require(own)
                    hlsSessionId=ready.hlsSessionId;playback=ready.playback;transport=ready.transport
                    session=cast.copy(generation=own.generation,phase=ActiveCast.Phase.Active,canSeek=ready.transport.canSeek && !ready.media.isLive,
                        state=CastPlaybackState(phase=CastPlaybackState.Phase.LOADING,position=ready.media.startPosition,duration=if(ready.media.isLive)0.0 else ready.media.duration ?: 0.0))
                    accepted=true;startPolling(own)
                }
            }
        } catch(error:CancellationException) {throw error
        } catch(_:Exception) {
            if(ticket===own && ownership.owns(own))session=cast.copy(generation=own.generation,phase=ActiveCast.Phase.Failed("Casting could not be started."))
        } finally {
            if(!accepted) {
                if(ticket===own && !ownership.owns(own)) {ticket=null;session=null;remoteVisible=false}
            }
        }
    }
}

/** Observable context loss removes UI state first; cleanup uses only the captured receiver/epoch. */
internal fun CastController.retireCast(expected:CastOwnership.Ticket) {
    if(startTicket===expected)startJob?.cancel()
    scope.launch {finish("playback context changed",stopReceiver=true,expected=expected)}
}
