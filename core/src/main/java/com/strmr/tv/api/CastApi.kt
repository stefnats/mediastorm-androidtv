package com.strmr.tv.api

import com.strmr.tv.live.mediaUrl
import kotlinx.serialization.Serializable

// What a Chromecast or DLNA TV is handed: URLs it can fetch with no headers of its own.

/** `GET /api/video/hls/start`: the session, its playlist (made absolute, with the token) and the length. */
data class CastHlsSession(val sessionId: String, val playlistUrl: String, val durationSeconds: Double?)

@Serializable
private data class HlsStartReply(val sessionId: String? = null, val playlistUrl: String? = null, val duration: Double? = null)

/**
 * Starts a server HLS session of the stream at [path] from 0 for a receiver. [cast] asks for the
 * cast profile (H.264 / AAC with a keyframe every 2 s, which the Default Media Receiver needs and
 * the server's fixed-length playlist relies on); without it the server transcodes as for any HLS
 * client (a DLNA TV that takes HLS). [audioTrack] / [subtitleTrack] are container stream indices;
 * a subtitle becomes a WebVTT rendition of the playlist.
 */
suspend fun ApiClient.startCastHls(
    path: String, metadata: Map<String, String>, cast: Boolean, audioTrack: Int?, subtitleTrack: Int?,
    expectedContext:Long=com.strmr.tv.library.ProfileDataContext.current,
): CastHlsSession {
    val (profile,name)=inContext(expectedContext){profileId to profileName}
    val query=castHlsQuery(path,metadata,profile,name,cast,audioTrack,subtitleTrack)
    val reply = send<HlsStartReply>("GET", "/api/video/hls/start", query, timeoutMs = 90_000,expectedContext=expectedContext)
    val playlist = reply.playlistUrl?.ifEmpty { null }
    val id = reply.sessionId?.ifEmpty { null }
    if (playlist == null || id == null) throw ApiError.NotReady("The server didn't start a stream for the TV.")
    return inContext(expectedContext){CastHlsSession(id, mediaUrl(playlist, expectedContext), reply.duration?.takeIf { it > 0 })}
}

/**
 * The direct stream for a receiver: [streamUrl] with the session token in the query, since a
 * receiver sends no headers. [extra] adds query items (`dlnaProfile=avc-ts`).
 */
fun ApiClient.castStreamUrl(path: String, metadata: Map<String, String>, extra: Map<String, String> = emptyMap(),expectedContext:Long=com.strmr.tv.library.ProfileDataContext.current): String = inContext(expectedContext) {
    val base = streamUrl(path, metadata)
    val added = extra.toMutableMap()
    token?.let { added["token"] = it }
    if (added.isEmpty()) return@inContext base
    val query = added.entries.sortedBy { it.key }.joinToString("&") { "${segment(it.key)}=${segment(it.value)}" }
    "$base&$query"
}

/**
 * Artwork or media for a receiver: an absolute URL as is, a server path (`/api/…` or `/…`) made
 * absolute with the token in its query; null for an empty one.
 */
fun ApiClient.castMediaUrl(pathOrUrl: String?,expectedContext:Long=com.strmr.tv.library.ProfileDataContext.current): String?=inContext(expectedContext) {
    val value = pathOrUrl?.trim().orEmpty()
    if (value.isEmpty()) return@inContext null
    if (value.startsWith("http")) return@inContext value
    mediaUrl(value, expectedContext)
}

/** Request construction never accepts a metadata-supplied profile in place of the captured owner. */
internal fun castHlsQuery(path:String,metadata:Map<String,String>,profile:String,name:String?,cast:Boolean,audio:Int?,subtitle:Int?):Map<String,String> = metadata.toMutableMap().apply {
    put("path",path);put("start","0");put("profileId",profile)
    name?.takeIf {it.isNotEmpty()}?.let {put("profileName",it)}
    if(cast){put("cast","true");put("forceAAC","true")}
    audio?.takeIf {it>=0}?.let {put("audioTrack",it.toString())}
    subtitle?.takeIf {it>=0}?.let {put("subtitleTrack",it.toString())}
}
