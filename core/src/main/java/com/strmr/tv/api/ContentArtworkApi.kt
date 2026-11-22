package com.strmr.tv.api

import com.strmr.tv.app.Config
import com.strmr.tv.library.ProfileDataContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class ContentArtworkPreference(val contentId: String = "", val artworkPreferenceSupported: Boolean = false,
    val posterUrl: String? = null, val backdropUrl: String? = null)

enum class ArtworkKind(val key: String, val label: String) { POSTER("posterUrl", "Poster"), BACKDROP("backdropUrl", "Background") }

/** One field only: resetting artwork must preserve language and source choices. */
fun contentArtworkPreferenceBody(contentId: String, contentType: String, kind: ArtworkKind, value: String?): JsonObject = buildJsonObject {
    put("contentId", contentId); put("contentType", contentType)
    put(kind.key, value?.let(::JsonPrimitive) ?: JsonNull)
}

suspend fun ApiClient.contentArtworkPreference(contentId: String, expected: Long): ContentArtworkPreference {
    val path = inContext(expected) { "$profilePath/preferences/content/${segment(contentId)}" }
    return send("GET", path, mapOf("clientId" to Config.clientId), timeoutMs = 5_000, expectedContext = expected)
}

suspend fun ApiClient.contentArtworkPreferences(expected: Long): List<ContentArtworkPreference> {
    val path = inContext(expected) { "$profilePath/preferences/content" }
    return send("GET", path, timeoutMs = 5_000, expectedContext = expected)
}

suspend fun ApiClient.saveContentArtwork(contentId: String, contentType: String, kind: ArtworkKind, value: String?, expected: Long): ContentArtworkPreference {
    val path = inContext(expected) { "$profilePath/content-preferences/artwork" }
    return send("PUT", path, mapOf("clientId" to Config.clientId),
        body = contentArtworkPreferenceBody(contentId, contentType, kind, value), timeoutMs = 5_000, expectedContext = expected)
}
