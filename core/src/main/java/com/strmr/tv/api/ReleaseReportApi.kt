package com.strmr.tv.api

import com.strmr.tv.library.ProfileDataContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** The server's verified selection and effective language, not a source supplied by the player. */
@Serializable
data class ReleaseReportOptions(
    val sourceVersion: String,
    val releaseName: String = "Selected release",
    val audioLanguage: String = "",
    val audioTracks: List<ProbedAudioTrack> = emptyList(),
    val selectedAudioTrack: Int = -1,
    val canShare: Boolean = false,
    val hasEpisode: Boolean = false,
)

@Serializable
data class ReleaseReportReply(
    val report: SavedReleaseReport? = null,
    val replacement: PrequeueStartResponse? = null,
    val resumePosition: Double = 0.0,
)

@Serializable
data class SavedReleaseReport(val id: String)

suspend fun ApiClient.releaseReportOptions(id: String, expectedContext: Long = ProfileDataContext.current): ReleaseReportOptions =
    send("GET", "/api/playback/prequeue/${segment(id)}/report", preparationQuery(expectedContext), expectedContext = expectedContext)

suspend fun ApiClient.submitReleaseAction(id: String, reporting: Boolean, body: JsonObject, expectedContext: Long = ProfileDataContext.current): ReleaseReportReply =
    send("POST", "/api/playback/prequeue/${segment(id)}/${if (reporting) "report" else "retry"}", preparationQuery(expectedContext), body = body, timeoutMs = 10_000, expectedContext = expectedContext)

suspend fun ApiClient.cancelReleaseAlternative(id: String, expectedContext: Long = ProfileDataContext.current) {
    sendRaw("DELETE", "/api/playback/prequeue/${segment(id)}/retry", preparationQuery(expectedContext), promptOnLock = false, timeoutMs = 5_000, expectedContext = expectedContext)
}

suspend fun ApiClient.undoReleaseReport(id: String, expectedContext: Long = ProfileDataContext.current) {
    sendRaw("DELETE", "/api/playback/bad-streams/${segment(id)}", preparationQuery(expectedContext), expectedContext = expectedContext)
}
