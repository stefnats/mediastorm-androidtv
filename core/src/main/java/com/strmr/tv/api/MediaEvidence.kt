package com.strmr.tv.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/** A catalogue fact, independent of the audio tracks in any release. */
@Serializable(with = OriginalLanguageEvidenceSerializer::class)
data class OriginalLanguageEvidence(
    val language: String? = null,
    val source: String? = null,
    val sourceTitleId: String? = null,
    val observedAt: String? = null,
    val expiresAt: String? = null,
)

/** One response's versioned facts. Release and resolved-file identities are separate domains. */
@Serializable(with = MediaEvidenceSerializer::class)
data class MediaEvidence(
    val version: Int = 0,
    val sourceVersion: String? = null,
    val candidateVersion: String? = null,
    val titleId: String? = null,
    val targetEpisode: PrequeueStatus.TargetEpisode? = null,
    val candidate: CandidateMediaFacts? = null,
    val probed: ProbedMediaFacts? = null,
)

private val candidateVersionPattern = Regex("candidate_v1_[0-9a-f]{64}")
private val fileVersionPattern = Regex("file_v1_[0-9a-f]{64}")

/** Hash domains describe different identities; an association is never inferred from equality. */
fun MediaEvidence.hasValidVersionIdentity(): Boolean = version == 1 &&
    sourceVersion?.let { (if (probed == null) candidateVersionPattern else fileVersionPattern).matches(it) } == true &&
    (candidateVersion == null || candidateVersionPattern.matches(candidateVersion)) &&
    (probed != null || candidateVersion == null || candidateVersion == sourceVersion)

@Serializable
data class CandidateMediaFacts(
    val source: String? = null, val observedAt: String? = null, val expiresAt: String? = null,
    val audioLanguages: List<String>? = null, val subtitleLanguages: List<String>? = null,
    val resolution: String? = null, val videoCodec: String? = null, val audioCodecs: List<String>? = null,
    val hdr: String? = null,
)

@Serializable
data class ProbedMediaFacts(
    val source: String? = null, val observedAt: String? = null, val expiresAt: String? = null,
    val audioStreams: List<MediaAudioStream>? = null, val audioStreamsComplete: Boolean = false,
    val subtitleStreams: List<MediaSubtitleStream>? = null, val subtitleStreamsComplete: Boolean = false,
    val video: MediaVideoFacts? = null,
)

@Serializable
data class MediaAudioStream(val index: Int, val language: String? = null, val codec: String? = null, val audioDescription: Boolean? = null)
@Serializable
data class MediaSubtitleStream(val index: Int, val language: String? = null, val codec: String? = null, val forced: Boolean? = null)
@Serializable
data class MediaVideoFacts(val codec: String? = null, val width: Int? = null, val height: Int? = null, val hdr: String? = null, val dolbyVisionProfile: String? = null)

private fun JsonObject.text(key: String, limit: Int = 128): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.length in 1..limit && it.none(Char::isISOControl) }
private fun JsonObject.integer(key: String): Int? =
    (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
private fun JsonObject.boolean(key: String): Boolean? =
    (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
private fun JsonObject.language(key: String): String? = text(key, 32)?.takeIf { Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*").matches(it) }
private fun JsonObject.codec(key: String): String? = text(key, 32)?.takeIf { Regex("[A-Za-z0-9._+:-]+").matches(it) }
private fun JsonObject.hdr(): String? = text("hdr")?.takeIf { it in setOf("sdr", "hdr10", "hlg", "dolby_vision") }
private fun JsonObject.strings(key: String, maximum: Int, language: Boolean = false): List<String>? {
    val array = get(key) as? JsonArray ?: return null
    if (array.size > maximum) return null
    val values = array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull ?: return null }
    if (values.any { it.length !in 1..32 || it.any(Char::isISOControl) ||
            !(if (language) Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*") else Regex("[A-Za-z0-9._+:-]+")).matches(it) }) return null
    return values.distinct()
}

object OriginalLanguageEvidenceSerializer : KSerializer<OriginalLanguageEvidence> {
    override val descriptor = buildClassSerialDescriptor("OriginalLanguageEvidence")
    override fun deserialize(decoder: Decoder): OriginalLanguageEvidence {
        val obj = (decoder as JsonDecoder).decodeJsonElement() as? JsonObject ?: return OriginalLanguageEvidence()
        return OriginalLanguageEvidence(obj.language("language"), obj.text("source"), obj.text("sourceTitleId", 1024), obj.text("observedAt", 64), obj.text("expiresAt", 64))
    }
    override fun serialize(encoder: Encoder, value: OriginalLanguageEvidence) {
        (encoder as JsonEncoder).encodeJsonElement(buildJsonObject {
            value.language?.let { put("language", it) }; value.source?.let { put("source", it) }
            value.sourceTitleId?.let { put("sourceTitleId", it) }; value.observedAt?.let { put("observedAt", it) }; value.expiresAt?.let { put("expiresAt", it) }
        })
    }
}

/** Bad additive data remains unknown without dropping its otherwise usable title or result. */
object MediaEvidenceSerializer : KSerializer<MediaEvidence> {
    override val descriptor = buildClassSerialDescriptor("MediaEvidence")
    override fun deserialize(decoder: Decoder): MediaEvidence = parse((decoder as JsonDecoder).decodeJsonElement())
    fun parse(element: JsonElement?): MediaEvidence {
        val obj = element as? JsonObject ?: return MediaEvidence()
        val version = obj.integer("version") ?: return MediaEvidence()
        val sourceVersion = obj.text("sourceVersion", 256)?.takeIf { Regex("[A-Za-z0-9._:-]+").matches(it) } ?: return MediaEvidence()
        val title = obj.text("titleId", 1024)
        if (obj["titleId"] != null && obj["titleId"] != JsonNull && title == null) return MediaEvidence()
        val target = obj["targetEpisode"]?.takeUnless { it == JsonNull }?.let {
            runCatching { ApiJson.decodeFromJsonElement<PrequeueStatus.TargetEpisode>(it) }.getOrNull() ?: return MediaEvidence()
        }
        if (target != null && (target.seasonNumber !in 0..10_000 || target.episodeNumber !in 1..100_000 ||
                target.episodeId?.length?.let { it !in 1..1024 } == true || target.numbering?.seriesId?.length?.let { it !in 1..1024 } == true)) return MediaEvidence()
        val candidateVersion = obj.text("candidateVersion",256)?.takeIf { Regex("[A-Za-z0-9._:-]+").matches(it) }
        if (obj["candidateVersion"] != null && obj["candidateVersion"] != JsonNull && candidateVersion == null) return MediaEvidence()
        val evidence = MediaEvidence(version, sourceVersion, candidateVersion, title, target,
            (obj["candidate"] as? JsonObject)?.let(::candidate), (obj["probed"] as? JsonObject)?.let(::probed))
        return if (version == 1 && !evidence.hasValidVersionIdentity()) MediaEvidence() else evidence
    }
    private fun candidate(obj: JsonObject) = CandidateMediaFacts(
        obj.text("source"), obj.text("observedAt", 64), obj.text("expiresAt", 64),
        obj.strings("audioLanguages",16,true), obj.strings("subtitleLanguages",16,true),
        obj.text("resolution",16)?.takeIf { Regex("[0-9]{3,4}p").matches(it) }, obj.codec("videoCodec"), obj.strings("audioCodecs",16), obj.hdr(),
    )
    private fun probed(obj: JsonObject): ProbedMediaFacts {
        val audio = (obj["audioStreams"] as? JsonArray)?.takeIf { it.size <= 32 }?.let { array ->
            val values = array.mapNotNull { (it as? JsonObject)?.let { row ->
                row.integer("index")?.takeIf { index -> index in 0..100_000 }?.let { index -> MediaAudioStream(index,row.language("language"),row.codec("codec"),row.boolean("audioDescription")) }
            } }
            values.takeIf { it.size == array.size && it.map { row -> row.index }.distinct().size == it.size }
        }
        val subtitles = (obj["subtitleStreams"] as? JsonArray)?.takeIf { it.size <= 32 }?.let { array ->
            val values = array.mapNotNull { (it as? JsonObject)?.let { row ->
                row.integer("index")?.takeIf { index -> index in 0..100_000 }?.let { index -> MediaSubtitleStream(index,row.language("language"),row.codec("codec"),row.boolean("forced")) }
            } }
            values.takeIf { it.size == array.size && it.map { row -> row.index }.distinct().size == it.size }
        }
        val video = (obj["video"] as? JsonObject)?.let { row -> MediaVideoFacts(row.codec("codec"),
            row.integer("width")?.takeIf { it in 1..32_768 },row.integer("height")?.takeIf { it in 1..32_768 },row.hdr(),row.codec("dolbyVisionProfile")) }
        return ProbedMediaFacts(obj.text("source"),obj.text("observedAt",64),obj.text("expiresAt",64),audio,
            audio != null && obj.boolean("audioStreamsComplete") == true,subtitles,subtitles != null && obj.boolean("subtitleStreamsComplete") == true,video)
    }
    override fun serialize(encoder: Encoder, value: MediaEvidence) {
        (encoder as JsonEncoder).encodeJsonElement(buildJsonObject {
            put("version",value.version); value.sourceVersion?.let { put("sourceVersion",it) }
            value.candidateVersion?.let { put("candidateVersion",it) }; value.titleId?.let { put("titleId",it) }
            value.targetEpisode?.let { put("targetEpisode",ApiJson.encodeToJsonElement(it)) }
            value.candidate?.let { put("candidate",ApiJson.encodeToJsonElement(it)) }; value.probed?.let { put("probed",ApiJson.encodeToJsonElement(it)) }
        })
    }
}
