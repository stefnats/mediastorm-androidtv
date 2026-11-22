package com.strmr.tv.live

import android.os.SystemClock
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ApiError
import com.strmr.tv.app.Config
import com.strmr.tv.library.ProfileDataContext
import java.time.Instant
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl

@Serializable
data class KidsLivePolicy(val enabled: Boolean = false, val country: String = "DE", val maximumAge: Int = 6, val guideSourceId: String = "") {
    val configured get() = enabled && country == "DE" && maximumAge in setOf(0, 6, 12, 16, 18) && guideSourceId.isNotBlank()
}

@Serializable
data class KidsLivePolicyReply(val policy: KidsLivePolicy = KidsLivePolicy())

@Serializable
data class KidsLiveGuide(val id: String, val type: String, val name: String = "", val observedAt: String)

/** The selected guide's claim, not an independent age classification. */
@Serializable
data class KidsLiveProgramme(val title: String, val startsAt: String, val endsAt: String, val age: Int, val country: String,
    val guide: KidsLiveGuide, val classificationBasis: String) {
    val classificationLabel get() = "Age $age+ according to " + if (guide.name.isBlank()) "the selected guide" else "${guide.name} (selected guide)"
}

@Serializable
internal data class KidsLiveStartReply(val sessionId: String, val playlistUrl: String, val programmeEndsAt: String,
    val programmeRemainingMillis: Long, val programme: KidsLiveProgramme, val timeshiftWindowSeconds: Int = 0, val isDirect: Boolean = false)

/** Fixed to the beginning of the original start request, including all response latency. */
class KidsProgrammeLimit private constructor(val programme: KidsLiveProgramme, val endsAt: Instant, private val deadline: Long) {
    fun remainingMillis(elapsedMillis: Long): Long = (deadline - elapsedMillis).coerceAtLeast(0)
    companion object {
        internal fun from(reply: KidsLiveStartReply, requestStartedMillis: Long): KidsProgrammeLimit {
            require(requestStartedMillis >= 0 && reply.programmeRemainingMillis > 0)
            val end = Instant.parse(reply.programmeEndsAt)
            require(end == Instant.parse(reply.programme.endsAt) && end > Instant.parse(reply.programme.startsAt))
            return KidsProgrammeLimit(reply.programme, end, Math.addExact(requestStartedMillis, reply.programmeRemainingMillis))
        }
    }
}

/** Runs on the player's scope even when its Activity is in PiP or playing background audio. */
internal class KidsProgrammeClock(private val limit: KidsProgrammeLimit, private val elapsedMillis: () -> Long, private val stopPlayback: () -> Unit) {
    private var stopped = false
    fun active(): Boolean {
        if (!stopped && limit.remainingMillis(elapsedMillis()) == 0L) {
            stopped = true
            stopPlayback()
        }
        return !stopped
    }
    suspend fun watch() {
        while (active()) delay(limit.remainingMillis(elapsedMillis()).coerceIn(1, 250))
    }
}

suspend fun ApiClient.kidsLivePolicy(expectedContext: Long = ProfileDataContext.current): KidsLivePolicy {
    val path = inContext(expectedContext) { "$profilePath/kids/live-policy" }
    val reply = send<KidsLivePolicyReply>("GET", path, timeoutMs = 10_000, expectedContext = expectedContext)
    ProfileDataContext.requireCurrent(expectedContext)
    return reply.policy
}

internal fun kidsLiveStartQuery(profile: String, channelId: String, clientId: String): Map<String, String> {
    require(channelId.isNotBlank()) { "Choose a channel again" }
    return mapOf("profileId" to profile, "channelId" to channelId, "clientId" to clientId, "target" to "native")
}

/** No provider URL, direct stream, timeshift or automatic source retry is accepted on this path. */
internal suspend fun openKidsLive(
    query: Map<String, String>, elapsedMillis: () -> Long,
    fetch: suspend (Map<String, String>) -> KidsLiveStartReply,
    mediaUrl: (String) -> String, stop: suspend (String) -> Unit,
): LiveStream {
    val started = elapsedMillis()
    val reply = fetch(query)
    try {
        check(reply.sessionId.isNotBlank() && reply.playlistUrl.isNotBlank() && !reply.isDirect && reply.timeshiftWindowSeconds == 0)
        val limit = KidsProgrammeLimit.from(reply, started)
        check(limit.remainingMillis(elapsedMillis()) > 0) { "This programme has ended. Choose a programme again." }
        return LiveStream(mediaUrl(reply.playlistUrl), reply.sessionId, programmeLimit = limit)
    } catch (error: Exception) {
        withContext(NonCancellable) { if (reply.sessionId.isNotBlank()) runCatching { stop(reply.sessionId) } }
        throw error
    }
}

suspend fun ApiClient.startKidsLive(channelId: String, expectedContext: Long = ProfileDataContext.current): LiveStream {
    val policy = kidsLivePolicy(expectedContext)
    if (!policy.configured) throw ApiError.NotReady("Live TV is not enabled for this child profile.")
    val query = inContext(expectedContext) { kidsLiveStartQuery(profileId, channelId, Config.clientId) }
    return openKidsLive(query, SystemClock::elapsedRealtime,
        fetch = { send("GET", "/api/live/kids/hls/start", it, timeoutMs = 60_000, expectedContext = expectedContext) },
        mediaUrl = { path -> inContext(expectedContext) {
            val url = mediaUrl(path, expectedContext).toHttpUrl()
            check(url.scheme == baseUrl.scheme && url.host == baseUrl.host && url.port == baseUrl.port &&
                url.encodedPath.startsWith(baseUrl.encodedPath.trimEnd('/') + "/api/video/hls/") &&
                url.encodedPath.endsWith("/stream.m3u8")) { "The child programme did not return a server HLS playlist" }
            url.toString()
        } }, stop = { stopLiveSession(it, expectedContext) })
}
