package com.strmr.tv.live

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

interface RecordingProgressGateway {
    suspend fun read(original: RecordingDerivedObservation, expectedContext: Long): RecordingProgressSnapshotObservation
    suspend fun write(original: RecordingDerivedObservation, command: RecordingProgressCommand, expectedContext: Long): RecordingProgressWriteObservation
}
object ApiRecordingProgressGateway : RecordingProgressGateway {
    override suspend fun read(original: RecordingDerivedObservation, expectedContext: Long): RecordingProgressSnapshotObservation {
        currentCoroutineContext().ensureActive()
        val raw = ApiClient.sendRecordingProgressRaw(RecordingProgressRequest(original, expectedContext), expectedContext)
        currentCoroutineContext().ensureActive(); ProfileDataContext.requireCurrent(expectedContext)
        val coroutine = currentCoroutineContext()
        return recordingProgressReply(
            checkCurrent = { coroutine.ensureActive(); ApiClient.inContext(expectedContext) { require(ApiClient.profileId == original.source.profile) } },
            parse = { RecordingProgressSnapshotObservation.accept(original, raw.text, requireNotNull(raw.clock), expectedContext) },
            adoptable = { it.adoptable() })
    }
    override suspend fun write(original: RecordingDerivedObservation, command: RecordingProgressCommand, expectedContext: Long): RecordingProgressWriteObservation {
        currentCoroutineContext().ensureActive()
        require(command.original === original && command.expectedContext == expectedContext)
        val raw = ApiClient.sendRecordingProgressRaw(RecordingProgressRequest(original, expectedContext, command), expectedContext)
        currentCoroutineContext().ensureActive(); ProfileDataContext.requireCurrent(expectedContext)
        val coroutine = currentCoroutineContext()
        return recordingProgressReply(
            checkCurrent = { coroutine.ensureActive(); ApiClient.inContext(expectedContext) { require(ApiClient.profileId == original.source.profile) } },
            parse = { RecordingProgressWriteObservation.accept(command, raw.text, requireNotNull(raw.clock)) },
            adoptable = { it.adoptable() })
    }
}

/** No free URL, selector, profile body field or header is accepted from a consumer. */
internal class RecordingProgressRequest(val original: RecordingDerivedObservation, val expectedContext: Long,
    private val command: RecordingProgressCommand? = null) {
    private val attempted = AtomicBoolean()
    internal fun beginAttempt() {
        check(attempted.compareAndSet(false, true)) { "Recording progress request already used" }
        command?.beginAttempt()
    }
    val profile get() = original.source.profile
    val method get() = if (command == null) "GET" else "POST"
    val path get() = "/api/live/recordings/${original.source.recording.id}/progress"
    internal fun body(): String? = command?.body()?.toString()?.also { require(it.toByteArray(Charsets.UTF_8).size in 1..4096) }
    internal fun check(now: Long = System.nanoTime()) {
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(original.source.recording.id) && profile.isNotBlank() && original.ready(now))
        require(command == null || command.original === original && command.expectedContext == expectedContext)
    }
    override fun toString() = "RecordingProgressRequest(opaque)"
}

/** Real ApiClient supplies its private stores; fixtures exercise this same fixed sender with synthetic values. */
internal class RecordingProgressTransport(
    private val context: RequestContext.Scope, private val base: () -> HttpUrl,
    private val activeProfile: () -> String, private val bearer: () -> String?,
    private val unlock: (String) -> String?, private val clientId: () -> String,
    private val remoteCredential: () -> String?, client: OkHttpClient,
) {
    private val http = client.recordingProgressClient()
    suspend fun send(operation: RecordingProgressRequest): ObservedRawResponse {
        operation.beginAttempt()
        currentCoroutineContext().ensureActive()
        val request = context.use {
            operation.check()
            require(context.profileEpoch == operation.expectedContext && activeProfile() == operation.profile)
            val origin = base()
            require(origin.username.isEmpty() && origin.password.isEmpty())
            val url = origin.newBuilder().encodedPath(origin.encodedPath.trimEnd('/') + operation.path).query(null).fragment(null).build()
            val token = bearer() ?: throw ApiError.SignedOut()
            val body = operation.body()?.toRequestBody("application/json; charset=utf-8".toMediaType())
            Request.Builder().url(url).method(operation.method, body).apply {
                header("Authorization", "Bearer $token"); header("X-Client-ID", clientId())
                unlock(operation.profile)?.let { header(ApiClient.UNLOCK_HEADER, it) }
                remoteCredential()?.let { header("X-Remote-Access-Credential", it) }
            }.build()
        }
        val result = ContextBoundTransport(context, false, { error("Recording progress replay prohibited") }, {}, allowReplay = false).execute {
            http.newCall(request).apply { timeout().timeout(15_000L, TimeUnit.MILLISECONDS) }.awaitRecordingProgress()
        }
        currentCoroutineContext().ensureActive()
        return context.use {
            operation.check()
            if (result.code != 200) throw ApiError.Http(HttpFailure(result.code, result.text, result.retryAfter))
            ObservedRawResponse(result.text, result.observedClock(request.url, ObservedResponsePolicy.PROGRESS.maxRoundTripMillis))
        }
    }
    override fun toString() = "RecordingProgressTransport(opaque)"
}

/** Original-context/expiry guards bracket parsing on the actual gateway publication path. */
internal fun <T> recordingProgressReply(checkCurrent: () -> Unit, parse: () -> T, adoptable: (T) -> Boolean): T {
    checkCurrent()
    val value = parse()
    checkCurrent()
    require(adoptable(value))
    checkCurrent()
    require(adoptable(value))
    checkCurrent()
    return value
}
