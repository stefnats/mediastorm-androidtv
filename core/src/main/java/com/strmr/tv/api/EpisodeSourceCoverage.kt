package com.strmr.tv.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/** Counts describe observed coordinates, never the series' episode inventory. */
@Serializable(with = EpisodeSourceCoverageSerializer::class)
data class EpisodeSourceCoverage(
    val state: String = "unknown",
    val foundEpisodes: Int = 0,
    val notFoundEpisodes: Int = 0,
    val unknownEpisodes: Int = 0,
    val catalogueComplete: Boolean = false,
    val seasons: List<SeasonSourceCoverage> = emptyList(),
) {
    val valid: Boolean get() = !catalogueComplete && validCounts(state, foundEpisodes, notFoundEpisodes, unknownEpisodes) &&
        seasons.size <= 1012 && seasons.all { it.valid } && seasons.map { it.seasonNumber }.distinct().size == seasons.size &&
        seasons.sumOf { it.foundEpisodes } == foundEpisodes && seasons.sumOf { it.notFoundEpisodes } == notFoundEpisodes && seasons.sumOf { it.unknownEpisodes } == unknownEpisodes
    val partial: Boolean get() = valid && state == "partial"
}

data class SeasonSourceCoverage(
    val seasonNumber: Int,
    val state: String,
    val foundEpisodes: Int,
    val notFoundEpisodes: Int,
    val unknownEpisodes: Int,
) {
    val valid: Boolean get() = seasonNumber in 0..10_000 && validCounts(state, foundEpisodes, notFoundEpisodes, unknownEpisodes)
}

private fun validCounts(state: String, found: Int, missing: Int, unknown: Int): Boolean =
    state in setOf("unknown", "partial") && found in 0..1012 && missing in 0..1012 && unknown in 0..1012 &&
        found + missing + unknown <= 1012 && (state == "partial") == (found > 0 && missing > 0)

/** Invalid additive metadata must not discard legacy episode hints or catalogue titles. */
object EpisodeSourceCoverageSerializer : KSerializer<EpisodeSourceCoverage> {
    override val descriptor = buildClassSerialDescriptor("EpisodeSourceCoverage")
    override fun deserialize(decoder: Decoder): EpisodeSourceCoverage =
        parse((decoder as JsonDecoder).decodeJsonElement()) ?: EpisodeSourceCoverage(state = "invalid")
    override fun serialize(encoder: Encoder, value: EpisodeSourceCoverage) =
        (encoder as JsonEncoder).encodeJsonElement(toJson(value))

    fun parse(element: JsonElement?): EpisodeSourceCoverage? {
        val obj = element as? JsonObject ?: return null
        fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        val complete = (obj["catalogueComplete"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return null
        val rows = obj["seasons"] as? JsonArray ?: return null
        if (rows.size > 1012) return null
        val seasons = rows.map { element ->
            val row = element as? JsonObject ?: return null
            SeasonSourceCoverage(row.number("seasonNumber") ?: return null, row.text("state") ?: return null,
                row.number("foundEpisodes") ?: return null, row.number("notFoundEpisodes") ?: return null, row.number("unknownEpisodes") ?: return null)
        }
        return EpisodeSourceCoverage(obj.text("state") ?: return null, obj.number("foundEpisodes") ?: return null,
            obj.number("notFoundEpisodes") ?: return null, obj.number("unknownEpisodes") ?: return null, complete, seasons).takeIf { it.valid }
    }

    fun toJson(value: EpisodeSourceCoverage): JsonObject = buildJsonObject {
        put("state", value.state); put("foundEpisodes", value.foundEpisodes); put("notFoundEpisodes", value.notFoundEpisodes)
        put("unknownEpisodes", value.unknownEpisodes); put("catalogueComplete", value.catalogueComplete)
        put("seasons", JsonArray(value.seasons.map { season -> buildJsonObject {
            put("seasonNumber", season.seasonNumber); put("state", season.state); put("foundEpisodes", season.foundEpisodes)
            put("notFoundEpisodes", season.notFoundEpisodes); put("unknownEpisodes", season.unknownEpisodes)
        } }))
    }
}
