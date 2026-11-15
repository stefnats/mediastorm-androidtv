package com.strmr.tv.live

import androidx.media3.datasource.HttpDataSource
import com.strmr.tv.api.ApiError
import com.strmr.tv.api.HttpFailure
import com.strmr.tv.library.ProfileDataContext
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A server challenge is opaque and belongs to one tune, never to a profile setting. */
internal data class RecordingChallenge(val token: String, val count: Int, val expiresInSeconds: Int)

internal fun recordingChallenge(error: ApiError): RecordingChallenge? {
    if (error.statusCode != 409 || error.code != "LIVE_RECORDING_CONFIRMATION_REQUIRED") return null
    val body = runCatching { Json.parseToJsonElement(error.failure!!.body) as? JsonObject }.getOrNull() ?: return null
    val token = runCatching { body["confirmationToken"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: return null
    val count = runCatching { body["recordingCount"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: return null
    val expires = runCatching { body["expiresInSeconds"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: return null
    if (!Regex("[a-f0-9]{48}").matches(token) || count <= 0 || expires !in 1..30) return null
    return RecordingChallenge(token, count, expires)
}

/** One explicit Play action; all automatic retunes retain its nonce. */
internal class LivePlaybackAttempt(
    val id: String = UUID.randomUUID().toString(),
    private val requireCurrent: () -> Unit,
) {
    private var admitted = false
    private var opening = false

    suspend fun <T> open(confirm: suspend (RecordingChallenge) -> Boolean, tune: suspend (String, String?, Boolean) -> T): T {
        check(!opening) { "An IPTV tune is already running" }
        opening = true
        try {
            requireCurrent()
            val result = try {
                tune(id, null, admitted)
            } catch (error: ApiError) {
                val challenge = recordingChallenge(error)
                if (admitted || challenge == null) throw error
                requireCurrent()
                if (!confirm(challenge)) throw CancellationException("Recording interruption cancelled")
                requireCurrent()
                // A changed/expired challenge is returned as an error, never silently reconfirmed.
                tune(id, challenge.token, false)
            }
            requireCurrent()
            admitted = true
            return result
        } finally {
            opening = false
        }
    }
}

/** UI receives a count, not a confirmation token, recording title or another viewer's name. */
class RecordingInterruptionPrompt internal constructor(val recordingCount: Int) {
    internal val answer = CompletableDeferred<Boolean>()
    val message: String get() = if (recordingCount == 1)
        "A recording is running. Continuing will stop it. The recorded portion will be kept as incomplete."
    else "$recordingCount recordings are running. Continuing will stop them. Their recorded portions will be kept as incomplete."
}

/** Main-thread consent shared by TV and phone playback overlays. */
class RecordingInterruptionConsent internal constructor(private val contexts: StateFlow<Long>) {
    constructor() : this(ProfileDataContext.changes)
    private val pending = MutableStateFlow<RecordingInterruptionPrompt?>(null)
    val prompt: StateFlow<RecordingInterruptionPrompt?> = pending

    internal suspend fun ask(challenge: RecordingChallenge, expectedContext: Long): Boolean = coroutineScope {
        if (contexts.value != expectedContext) throw CancellationException("Profile data context changed")
        check(pending.value == null) { "An IPTV recording confirmation is already open" }
        val request = RecordingInterruptionPrompt(challenge.count)
        pending.value = request
        val changed = launch {
            contexts.first { it != expectedContext }
            request.answer.cancel(CancellationException("Profile data context changed"))
        }
        try {
            withTimeoutOrNull(challenge.expiresInSeconds * 1_000L) { request.answer.await() }
                ?: throw ApiError.NotReady("The recording confirmation expired. Select Play again.")
        } finally {
            changed.cancel()
            if (pending.value === request) pending.value = null
        }
    }

    fun answer(request: RecordingInterruptionPrompt, allow: Boolean) {
        if (pending.value === request) request.answer.complete(allow)
    }
}

private val capacityCodes = setOf(
    "LIVE_STREAM_TAKEN_OVER", "LIVE_RECORDING_CONFIRMATION_REQUIRED", "LIVE_RECORDING_CONFIRMATION_EXPIRED",
    "LIVE_TAKEOVER_PERMISSION_CHANGED", "LIVE_TAKEOVER_STOP_FAILED", "LIVE_TAKEOVER_PENDING_CLOSE", "LIVE_STREAM_LIMIT_REACHED",
)

/** A refused/revoked grant must not enter the player's automatic source-switch loop. */
internal fun liveCapacityRejection(error: Throwable): String? {
    var cause: Throwable? = error
    repeat(8) {
        val current = cause ?: return null
        val failure = when (current) {
            is ApiError -> current.failure
            is HttpDataSource.InvalidResponseCodeException -> HttpFailure(current.responseCode, current.responseBody.toString(Charsets.UTF_8))
            else -> null
        }
        if (failure?.code in capacityCodes) return if (failure?.code == "LIVE_STREAM_TAKEN_OVER")
            "This IPTV playback was taken over. Select Play to request a new place."
        else failure?.message ?: "All IPTV places are occupied. Select Play to try again."
        cause = current.cause
    }
    return null
}
