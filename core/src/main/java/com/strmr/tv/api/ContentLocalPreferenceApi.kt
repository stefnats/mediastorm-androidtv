package com.strmr.tv.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class ContentLocalPreference(
    val preferLocalFiles: Boolean? = null,
    val localSourcePreferenceSupported: Boolean = false,
)

enum class LocalSourceChoice(val preference: Boolean?, val label: String) {
    INHERIT(null, "Use inherited setting"),
    LOCAL(true, "Prefer eligible local files"),
    NORMAL(false, "Normal search-first selection"),
}

/** Source-only partial update. JsonNull must survive ApiJson.explicitNulls=false. */
fun contentLocalPreferenceBody(contentId: String, contentType: String, preference: Boolean?): JsonObject = buildJsonObject {
    put("contentId", contentId)
    put("contentType", contentType)
    put("preferLocalFiles", preference?.let(::JsonPrimitive) ?: JsonNull)
}

suspend fun ApiClient.contentLocalPreference(contentId: String, expectedContext: Long): ContentLocalPreference {
    val path = inContext(expectedContext) { "$profilePath/preferences/content/${segment(contentId)}" }
    return send("GET", path, expectedContext = expectedContext, timeoutMs = 5_000)
}

/** A dedicated route is absent on legacy full-document servers, protecting track choices. */
suspend fun ApiClient.saveContentLocalPreference(contentId: String, contentType: String, preference: Boolean?, expectedContext: Long): ContentLocalPreference {
    val path = inContext(expectedContext) { "$profilePath/content-preferences/local-source" }
    return send("PUT", path, body = contentLocalPreferenceBody(contentId, contentType, preference), expectedContext = expectedContext, timeoutMs = 5_000)
}
