package com.strmr.tv.api

import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

// Online subtitles: search and download through the server's providers (OpenSubtitles,
// SubDL, SubSource), machine translation of a WebVTT source, and extraction of an embedded
// track to WebVTT (the translation's English source).

/** One `/api/subtitles/search` result. */
@Serializable
data class SubtitleSearchResult(
    val id: String,
    val provider: String,
    val language: String = "",
    val release: String = "",
    val downloads: Int = 0,
    @SerialName("hearing_impaired") val hearingImpaired: Boolean = false,
    @SerialName("page_link") val pageLink: String? = null,
)

/** What the search and download identify the title by. */
data class SubtitleQuery(
    val imdbId: String?,
    val title: String?,
    val releaseName: String?,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
) {
    fun params(language: String): MutableMap<String, String> = buildMap {
        imdbId?.takeIf { it.isNotEmpty() }?.let { put("imdbId", it) }
        title?.takeIf { it.isNotEmpty() }?.let { put("title", it) }
        year?.takeIf { it > 0 }?.let { put("year", it.toString()) }
        season?.let { put("season", it.toString()) }
        episode?.let { put("episode", it.toString()) }
        put("language", language)
    }.toMutableMap()
}

suspend fun ApiClient.searchSubtitles(query: SubtitleQuery, language: String, expected: Long = ProfileDataContext.current): List<SubtitleSearchResult> {
    val params = query.params(language)
    query.releaseName?.takeIf { it.isNotEmpty() }?.let { params["releaseName"] = it }
    return send("GET", "/api/subtitles/search", preparationQuery(expected, params), promptOnLock = false, timeoutMs = 60_000, expectedContext = expected)
}

/** The chosen result as WebVTT. */
suspend fun ApiClient.downloadSubtitle(result: SubtitleSearchResult, query: SubtitleQuery, language: String, expected: Long = ProfileDataContext.current): String {
    val params = query.params(language)
    params["subtitleId"] = result.id
    params["provider"] = result.provider
    return sendRaw("GET", "/api/subtitles/download", preparationQuery(expected, params), promptOnLock = false, timeoutMs = 60_000, expectedContext = expected)
}

/**
 * `sourcePath` (a server-relative `/api/...` WebVTT URL, which the server fetches from its own
 * loopback with this request's Authorization) translated to `targetLanguage`. Incremental:
 * each call translates more cues and returns what is done so far.
 */
suspend fun ApiClient.translateSubtitle(sourcePath: String, targetLanguage: String, sourceLanguage: String = "en", expected: Long = ProfileDataContext.current): String {
    val query = subtitleTranslationQuery(sourcePath, targetLanguage, sourceLanguage, expected)
    return sendRaw("GET", "/api/subtitles/translate", query,
        promptOnLock = false, timeoutMs = 60_000, expectedContext = expected)
}

internal fun ApiClient.subtitleTranslationQuery(source: String, target: String, language: String, expected: Long): Map<String, String> {
    val query = preparationQuery(expected, mapOf("sourceUrl" to source, "targetLanguage" to target, "sourceLanguage" to language))
    return query + ("userId" to query.getValue("profileId"))
}

@Serializable
data class SubtitleExtractSession(val sessionId: String = "", val subtitleUrl: String = "", val firstCueTime: Double? = null)

/**
 * Starts extracting embedded subtitle track `subtitleTrack` (its position among the file's
 * subtitle streams) to WebVTT from `startOffset` seconds; `subtitleUrl` is server-relative.
 */
suspend fun ApiClient.startSubtitleExtract(path: String, subtitleTrack: Int, startOffset: Double, expected: Long = ProfileDataContext.current): SubtitleExtractSession {
    val query = buildMap {
        put("path", path)
        put("subtitleTrack", subtitleTrack.toString())
        if (startOffset > 0) put("startOffset", "%.3f".format(java.util.Locale.ROOT, startOffset))
        put("format", "vtt")
    }
    return send("GET", "/api/video/subtitles/start", preparationQuery(expected, query), promptOnLock = false, timeoutMs = 60_000, expectedContext = expected)
}

/** The server's subtitle settings that decide what the player may try. */
data class SubtitleServerSettings(val providerConfigured: Boolean, val translationEnabled: Boolean)

/** Read known flags strictly; missing sections/errors never enable an optional feature. */
internal fun subtitleSettingsFrom(raw: JsonObject): SubtitleServerSettings {
    val fields = raw["subtitles"] as? JsonObject ?: return SubtitleServerSettings(false, false)
    fun configured(name: String) = (fields[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.isNotEmpty() == true
    val provider = configured("openSubtitlesUsername") && configured("openSubtitlesPassword") || configured("subdlApiKey") || configured("subsourceApiKey")
    val translation = if ("enableTranslatedSubs" !in fields) true
        else (fields["enableTranslatedSubs"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true
    return SubtitleServerSettings(provider, translation)
}

/** Successful legacy sections keep their old absent-flag default; failed reads remain unavailable. */
suspend fun ApiClient.subtitleServerSettings(expected: Long = ProfileDataContext.current): SubtitleServerSettings =
    readSubtitleSettings(expected) {
        subtitleSettingsFrom(send<JsonObject>("GET", "/api/settings", preparationQuery(expected), promptOnLock = false, timeoutMs = 10_000, expectedContext = expected))
    }

/** The actual settings fallback, also exercised independently of Android credential storage. */
internal suspend fun readSubtitleSettings(expected: Long, read: suspend () -> SubtitleServerSettings): SubtitleServerSettings {
    currentCoroutineContext().ensureActive()
    ProfileDataContext.requireCurrent(expected)
    return try { read().also { currentCoroutineContext().ensureActive(); ProfileDataContext.requireCurrent(expected) }
    } catch (error: CancellationException) { throw error
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        ProfileDataContext.requireCurrent(expected)
        error.rethrowVodDenial()
        SubtitleServerSettings(false, false)
    }
}
