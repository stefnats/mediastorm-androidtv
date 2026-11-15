package com.strmr.tv.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class ReminderEpisode(val episodeId: String, val numbering: EpisodeNumbering, val seasonNumber: Int, val episodeNumber: Int)

@Serializable
data class Reminder(
    val id: String, val contentId: String, val contentType: String, val eventKind: String, val title: Title,
    val releaseType: String? = null, val episode: ReminderEpisode? = null, val state: String = "waiting",
    val createdAt: String? = null, val lastObservedAt: String? = null,
    val createdOnCurrentDevice: Boolean? = null,
)

@Serializable
data class ReminderEvent(
    val id: String, val reminderId: String, val eventKind: String, val contentId: String, val contentType: String,
    val title: Title, val episode: ReminderEpisode? = null, val releaseType: String? = null,
    val occurredAt: String? = null, val readAt: String? = null, val dismissedAt: String? = null,
    val candidateOnly: Boolean = false,
)

@Serializable
data class ReminderList(val remindersSupported: Boolean = false, val monitoring: String? = null, val items: List<Reminder> = emptyList())
@Serializable
data class ReminderInbox(val remindersSupported: Boolean = false, val monitoring: String? = null, val items: List<ReminderEvent> = emptyList(), val nextCursor: String? = null)
@Serializable
data class ReminderCreated(val remindersSupported: Boolean = false, val monitoring: String? = null, val item: Reminder? = null, val currentCandidate: SourceAvailability? = null, val requestedContentId: String? = null)
@Serializable
data class ReminderEventUpdated(val remindersSupported: Boolean = false, val item: ReminderEvent? = null)

fun reminderBody(contentId: String, contentType: String, eventKind: String, releaseType: String? = null, episode: ReminderEpisode? = null): JsonObject = buildJsonObject {
    require(contentType in setOf("movie","series"))
    require(eventKind in setOf("release","new_episode","matching_source"))
    require(eventKind!="release" || contentType=="movie" && episode==null && releaseType in setOf("digital","physical","theatrical"))
    require(eventKind=="release" || releaseType==null)
    require(eventKind!="new_episode" || contentType=="series" && episode==null)
    require(eventKind!="matching_source" || contentType=="movie" && episode==null || contentType=="series" && episode!=null)
    require(episode==null || episode.numbering.ordering=="official")
    put("contentId", contentId); put("contentType", contentType); put("eventKind", eventKind)
    releaseType?.let { put("releaseType", it) }
    episode?.let { ep -> put("episode", buildJsonObject {
        put("episodeId", ep.episodeId); put("seasonNumber", ep.seasonNumber); put("episodeNumber", ep.episodeNumber)
        put("numbering", buildJsonObject { put("seriesId", ep.numbering.seriesId); put("ordering", "official") })
    }) }
}

fun reminderEventBody(read: Boolean? = null, dismissed: Boolean? = null): JsonObject = buildJsonObject {
    require(read!=null || dismissed!=null)
    read?.let { put("read", it) }; dismissed?.let { put("dismissed", it) }
}

suspend fun ApiClient.reminders(expected: Long): ReminderList {
    val path = inContext(expected) { "$profilePath/reminders" }
    return send("GET", path, expectedContext = expected, timeoutMs = 5_000)
}
suspend fun ApiClient.reminderInbox(expected: Long, unread: Boolean, cursor: String?): ReminderInbox {
    val path = inContext(expected) { "$profilePath/reminder-events" }
    return send("GET", path, query = buildMap {
        put("limit", "25"); if (unread) put("unread", "true"); cursor?.let { put("cursor", it) }
    }, expectedContext = expected, timeoutMs = 5_000)
}
suspend fun ApiClient.createReminder(body: JsonObject, expected: Long): ReminderCreated {
    val path = inContext(expected) { "$profilePath/reminders" }
    return send("POST", path, body = body, expectedContext = expected, timeoutMs = 5_000)
}
suspend fun ApiClient.revokeReminder(id: String, expected: Long) {
    val path = inContext(expected) { "$profilePath/reminders/${segment(id)}" }
    sendRaw("DELETE", path, expectedContext = expected, timeoutMs = 5_000)
}
suspend fun ApiClient.updateReminderEvent(id: String, body: JsonObject, expected: Long): ReminderEventUpdated {
    val path = inContext(expected) { "$profilePath/reminder-events/${segment(id)}" }
    return send("PATCH", path, body = body, expectedContext = expected, timeoutMs = 5_000)
}
