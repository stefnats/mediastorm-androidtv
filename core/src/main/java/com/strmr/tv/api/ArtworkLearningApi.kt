package com.strmr.tv.api

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Confirmed profile settings, separate from explicit title artwork overrides. */
data class ArtworkLearningState(val profileId: String, val supported: Boolean, val enabled: Boolean,
    val minimumSamples: Int, val maxSamples: Int, val posterSamples: Int, val backdropSamples: Int)

internal fun artworkLearningState(raw: String, expectedProfile: String): ArtworkLearningState {
    val value = ApiJson.parseToJsonElement(raw) as? JsonObject ?: error("Invalid artwork learning settings")
    fun primitive(key: String) = value[key] as? JsonPrimitive ?: error("Missing artwork learning field")
    fun boolean(key: String): Boolean = primitive(key).let {
        require(!it.isString); requireNotNull(it.booleanOrNull)
    }
    fun integer(key: String): Int = primitive(key).let {
        require(!it.isString); requireNotNull(it.intOrNull)
    }
    val profile = primitive("profileId").also { require(it.isString) }.content
    require(profile.isNotBlank() && profile == expectedProfile)
    return ArtworkLearningState(profile, boolean("supported"), boolean("enabled"), integer("minimumSamples"),
        integer("maxSamples"), integer("posterSamples"), integer("backdropSamples")).also {
        require(it.supported && it.minimumSamples == 3 && it.maxSamples == 128)
        require(it.posterSamples in 0..128 && it.backdropSamples in 0..128)
        require(it.posterSamples + it.backdropSamples <= it.maxSamples)
    }
}

internal fun artworkLearningBody(enabled: Boolean?, reset: Boolean): JsonObject {
    require((enabled != null) != reset)
    return buildJsonObject { if (reset) put("reset", true) else put("enabled", requireNotNull(enabled)) }
}

interface ArtworkLearningGateway {
    fun isCurrent(): Boolean
    suspend fun read(): ArtworkLearningState?
    suspend fun write(enabled: Boolean?, reset: Boolean): ArtworkLearningState
}

/** One captured profile and one request per action, without refresh, prompts or HTTP replay. */
internal class ArtworkLearningTransport(private val context: RequestContext.Scope, private val base: HttpUrl,
    private val profile: String, private val headers: Map<String, String>, client: OkHttpClient) : ArtworkLearningGateway {
    private val http = client.recordingProgressClient()
    override fun isCurrent() = runCatching { context.check() }.isSuccess
    override suspend fun read(): ArtworkLearningState? = request(null)
    override suspend fun write(enabled: Boolean?, reset: Boolean): ArtworkLearningState =
        requireNotNull(request(artworkLearningBody(enabled, reset))).also {
            if (reset) require(!it.enabled && it.posterSamples == 0 && it.backdropSamples == 0)
            else require(it.enabled == enabled)
        }

    private suspend fun request(body: JsonObject?): ArtworkLearningState? {
        currentCoroutineContext().ensureActive()
        val request = context.use {
            require(profile.isNotBlank() && base.username.isEmpty() && base.password.isEmpty())
            val url = base.newBuilder().encodedPath(base.encodedPath.trimEnd('/') +
                "/api/users/${ApiClient.segment(profile)}/artwork-learning").query(null).fragment(null).build()
            Request.Builder().url(url).apply {
                headers.forEach { (name, value) -> header(name, value) }
                if (body == null) get() else put(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            }.build()
        }
        val reply = ContextBoundTransport(context, false, { error("Artwork learning replay prohibited") }, {}, allowReplay = false).execute {
            http.newCall(request).apply { timeout().timeout(5_000L, TimeUnit.MILLISECONDS) }.awaitRecordingProgress()
        }
        currentCoroutineContext().ensureActive()
        return context.use {
            if (body == null && reply.code == 404) null
            else {
                if (reply.code != 200) throw ApiError.Http(HttpFailure(reply.code, reply.text, reply.retryAfter))
                artworkLearningState(reply.text, profile)
            }
        }
    }
}
