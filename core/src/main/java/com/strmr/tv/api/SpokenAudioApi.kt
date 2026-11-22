package com.strmr.tv.api

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

data class SpokenAudioInput(val sourceVersion: String, val audioTrackIndex: Int, val startOffset: Double) {
    internal fun body(): String {
        require(sourceVersion.length in 1..256 && sourceVersion.none { it.isISOControl() })
        require(audioTrackIndex >= 0 && startOffset.isFinite() && startOffset >= 0)
        return buildJsonObject {
            put("sourceVersion", sourceVersion)
            put("audioTrackIndex", audioTrackIndex)
            put("startOffset", startOffset)
        }.toString()
    }
}

/** A sample-based hint only. No transcript, report, playback grant or source replacement. */
@Serializable
data class SpokenAudioReply(
    val status: String,
    val reason: String? = null,
    val requestedLanguage: String,
    val detectedLanguage: String? = null,
    val confidence: Double? = null,
    val samples: Int,
    val sourceVersion: String,
    val audioTrackIndex: Int,
)

internal class SpokenAudioRequest(val id: String, val profile: String, val expected: Long, val input: SpokenAudioInput) {
    private val attempted = AtomicBoolean()
    fun begin() { check(attempted.compareAndSet(false, true)) { "Audio check already attempted" } }
    fun check() {
        require(Regex("[A-Za-z0-9_-]{1,256}").matches(id) && profile.isNotBlank())
        input.body()
    }
    override fun toString() = "SpokenAudioRequest(opaque)"
}

/** One fixed operation with captured credentials; no token refresh or transport replay. */
internal class SpokenAudioTransport(
    private val context: RequestContext.Scope,
    private val base: () -> HttpUrl,
    private val activeProfile: () -> String,
    private val bearer: () -> String?,
    private val unlock: (String) -> String?,
    private val clientId: () -> String,
    private val remoteCredential: () -> String?,
    client: OkHttpClient,
) {
    // Reuse the finite JSON sender's SDK follow-up suppression and joined cancellation.
    private val http = client.recordingProgressClient().newBuilder()
        .readTimeout(100_000L, TimeUnit.MILLISECONDS).build()

    suspend fun send(operation: SpokenAudioRequest): SpokenAudioReply {
        operation.begin()
        currentCoroutineContext().ensureActive()
        val request = context.use {
            operation.check()
            require(context.profileEpoch == operation.expected && activeProfile() == operation.profile)
            val origin = base()
            require(origin.username.isEmpty() && origin.password.isEmpty())
            val url = origin.newBuilder().encodedPath(origin.encodedPath.trimEnd('/') +
                "/api/playback/prequeue/${operation.id}/audio-language")
                .query(null).fragment(null).addQueryParameter("profileId", operation.profile).build()
            Request.Builder().url(url).post(operation.input.body().toRequestBody("application/json; charset=utf-8".toMediaType())).apply {
                header("Authorization", "Bearer ${bearer() ?: throw ApiError.SignedOut()}")
                header("X-Client-ID", clientId())
                unlock(operation.profile)?.let { header(ApiClient.UNLOCK_HEADER, it) }
                remoteCredential()?.let { header("X-Remote-Access-Credential", it) }
            }.build()
        }
        val result = ContextBoundTransport(context, false, { error("Audio check replay prohibited") }, {}, allowReplay = false).execute {
            http.newCall(request).apply { timeout().timeout(100_000L, TimeUnit.MILLISECONDS) }.awaitRecordingProgress()
        }
        currentCoroutineContext().ensureActive()
        return context.use {
            if (result.code != 200) throw ApiError.Http(HttpFailure(result.code, result.text, result.retryAfter))
            val reply = ApiJson.decodeFromString<SpokenAudioReply>(result.text)
            require(reply.sourceVersion == operation.input.sourceVersion && reply.audioTrackIndex == operation.input.audioTrackIndex)
            require(reply.status in setOf("unknown", "likely_match", "likely_mismatch") && reply.samples in 0..2)
            require(reply.confidence == null || reply.confidence.isFinite() && reply.confidence in 0.0..1.0)
            reply
        }
    }
}
