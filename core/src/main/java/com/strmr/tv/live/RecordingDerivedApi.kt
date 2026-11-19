package com.strmr.tv.live

import com.strmr.tv.api.ApiClient
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl

internal data class RecordingDerivedRequest(val method: String, val path: String, val query: Map<String, String>)
internal fun recordingDerivedRequest(id: String, profile: String, prepare: Boolean): RecordingDerivedRequest {
    require(Regex("[A-Za-z0-9_-]{1,128}").matches(id) && profile.isNotBlank())
    return RecordingDerivedRequest(if (prepare) "POST" else "GET",
        "/api/live/recordings/$id/derived" + if (prepare) "/join" else "", mapOf("profileId" to profile))
}

interface RecordingDerivedGateway {
    suspend fun read(source: RecordingDerivedSource, prepare: Boolean, expected: Long): RecordingDerivedObservation
}
object ApiRecordingDerivedGateway : RecordingDerivedGateway {
    override suspend fun read(source: RecordingDerivedSource, prepare: Boolean, expected: Long): RecordingDerivedObservation {
        currentCoroutineContext().ensureActive()
        ApiClient.inContext(expected) { require(ApiClient.profileId == source.profile) }
        val raw = ApiClient.sendRecordingDerivedRaw(source.recording.id, prepare, expected)
        currentCoroutineContext().ensureActive(); ProfileDataContext.requireCurrent(expected)
        return RecordingDerivedObservation.accept(source, raw.text, requireNotNull(raw.clock))
    }
}

/** Never call mediaUrl/streamUrl: this route carries credentials in headers only. */
internal fun recordingDerivedStreamUrl(base: HttpUrl, profile: String, recording: String, artifact: String): HttpUrl {
    require(base.username.isEmpty() && base.password.isEmpty())
    require(profile.isNotBlank() && Regex("[A-Za-z0-9_-]{1,128}").matches(recording) && Regex("[a-f0-9]{32}").matches(artifact))
    return base.newBuilder().encodedPath(base.encodedPath.trimEnd('/') + "/api/live/recordings/$recording/derived/$artifact/stream")
        .query(null).fragment(null).addQueryParameter("profileId", profile).build()
}
