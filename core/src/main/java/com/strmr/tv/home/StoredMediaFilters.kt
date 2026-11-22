package com.strmr.tv.home

import com.strmr.tv.sources.SourceFilterBasis
import com.strmr.tv.sources.SourceFilterLanguages
import com.strmr.tv.sources.SourceFilterMenus
import com.strmr.tv.sources.SourceFilters
import kotlinx.serialization.json.*

/** Saved library facts only. Every conjunction belongs to one file and one audio track. */
data class StoredMediaFilters(
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
    val audioCodec: String? = null,
    val videoCodec: String? = null,
    val hdr: String? = null,
    val minHeight: Int? = null,
    val subtitles: String? = null,
    val unknown: String = "include",
) {
    val active get() = listOf(audioLanguage, subtitleLanguage, audioCodec, videoCodec, hdr, minHeight, subtitles).any { it != null }
    private fun facts() = SourceFilters(basis = SourceFilterBasis.PROBED, audioLanguage = audioLanguage,
        subtitleLanguage = subtitleLanguage, audioCodec = audioCodec, videoCodec = videoCodec, dynamicRange = hdr, heightAtLeast = minHeight)
    fun canonical(): StoredMediaFilters? {
        val normalized = copy(
            audioLanguage = audioLanguage?.let { SourceFilterLanguages.canonical(it) ?: return null },
            subtitleLanguage = subtitleLanguage?.let { SourceFilterLanguages.canonical(it) ?: return null })
        return normalized.takeIf { it.facts().valid() && subtitles in listOf(null, "present", "absent") && unknown in listOf("include", "exclude", "only") }
    }
    fun query(): Map<String, String> = buildMap {
        if (!active) return@buildMap
        val f = requireNotNull(canonical()) { "Invalid stored media filters" }
        f.audioLanguage?.let { put("audioLanguage", it) }; f.subtitleLanguage?.let { put("subtitleLanguage", it) }
        f.audioCodec?.let { put("audioCodec", it) }; f.videoCodec?.let { put("videoCodec", it) }
        f.hdr?.let { put("hdr", it) }; f.minHeight?.let { put("minHeight", it.toString()) }
        f.subtitles?.let { put("subtitles", it) }; put("mediaUnknown", f.unknown)
    }
    companion object { const val BASIS = "stored_library_versions" }
}

/** Eligibility precedes the server capability; external discovery is never filtered locally. */
fun BrowseList.storedMediaEligible(extra: Map<String, String> = emptyMap()): Boolean {
    if (this == BrowseList.Watchlist || this is BrowseList.Custom || this == BrowseList.PermanentPrequeue || this == BrowseList.Hidden || this == BrowseList.LovedStored ||
        this is BrowseList.CollectionCached || this is BrowseList.ContinueWatchingCached || this is BrowseList.RecentlyWatchedCached ||
        this is BrowseList.PopularOnServerCached || this is BrowseList.SimilarCached || this is BrowseList.TMDBPublicListCached) return true
    if (this !is BrowseList.Remote) return false
    val q = query + extra
    if (q["source"] == "tmdb-person-credits-cached") return true
    return q["source"] in setOf("mdblist", "mdblist-url", "mdblist-shelf") && q["url"]?.trim()?.startsWith("mediastorm:custom-list:") == true
}

/** Identical choices on TV and phone, using the existing file-fact vocabulary. */
object StoredMediaFilterMenu {
    enum class Field(val label: String) { AUDIO("Audio track language"), SUBTITLE("Subtitle track language"), AUDIO_CODEC("Audio codec"), VIDEO("Video codec"), HDR("Dynamic range"), HEIGHT("Minimum frame height"), SUBTITLES("Embedded subtitles"), UNKNOWN("Unknown facts") }
    private val languages by lazy { SourceFilterLanguages.choices() }
    private fun sourceField(f: Field) = when(f) {
        Field.AUDIO -> SourceFilterMenus.Field.AUDIO; Field.SUBTITLE -> SourceFilterMenus.Field.SUBTITLE
        Field.AUDIO_CODEC -> SourceFilterMenus.Field.AUDIO_CODEC; Field.VIDEO -> SourceFilterMenus.Field.VIDEO
        Field.HDR -> SourceFilterMenus.Field.HDR; Field.HEIGHT -> SourceFilterMenus.Field.RESOLUTION
        else -> null
    }
    private fun facts(f: StoredMediaFilters) = SourceFilters(basis = SourceFilterBasis.PROBED, audioLanguage = f.audioLanguage,
        subtitleLanguage = f.subtitleLanguage, audioCodec = f.audioCodec, videoCodec = f.videoCodec, dynamicRange = f.hdr, heightAtLeast = f.minHeight)
    fun choices(field: Field, f: StoredMediaFilters): List<SourceFilterMenus.Choice> = when(field) {
        Field.SUBTITLES -> listOf(SourceFilterMenus.Choice(null, "Any subtitle inventory"), SourceFilterMenus.Choice("present", "Subtitles present"), SourceFilterMenus.Choice("absent", "No embedded subtitles"))
        Field.UNKNOWN -> listOf(SourceFilterMenus.Choice("include", "Include unknown facts"), SourceFilterMenus.Choice("exclude", "Known matches only"), SourceFilterMenus.Choice("only", "Unknown facts only"))
        else -> SourceFilterMenus.choices(requireNotNull(sourceField(field)), facts(f), (languages + listOfNotNull(f.audioLanguage, f.subtitleLanguage)).distinct())
    }
    fun value(field: Field, f: StoredMediaFilters): String? = when(field) {
        Field.SUBTITLES -> f.subtitles; Field.UNKNOWN -> f.unknown
        else -> SourceFilterMenus.value(requireNotNull(sourceField(field)), facts(f))
    }
    fun choose(field: Field, value: String?, f: StoredMediaFilters): StoredMediaFilters = when(field) {
        Field.AUDIO -> f.copy(audioLanguage=value); Field.SUBTITLE -> f.copy(subtitleLanguage=value)
        Field.AUDIO_CODEC -> f.copy(audioCodec=value); Field.VIDEO -> f.copy(videoCodec=value)
        Field.HDR -> f.copy(hdr=value); Field.HEIGHT -> f.copy(minHeight=value?.toIntOrNull())
        Field.SUBTITLES -> f.copy(subtitles=value); Field.UNKNOWN -> f.copy(unknown=value ?: "include")
    }
    fun label(field: Field, f: StoredMediaFilters): String = field.label + ": " + choices(field, f).firstOrNull { it.value == value(field, f) }?.label.orEmpty()
}

/** Additive outer capability only; callers retain their existing whole-snapshot validation. */
internal fun BrowsePage.withStoredMediaCapability(root: JsonObject): BrowsePage {
    val marker = root["storedMediaFilterSupported"] as? JsonPrimitive
    val basis = (root["storedMediaFilterBasis"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return copy(storedMediaFilterSupported = marker?.isString == false && marker.booleanOrNull == true,
        storedMediaFilterBasis = basis)
}
