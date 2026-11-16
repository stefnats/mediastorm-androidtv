package com.strmr.tv.ui

import androidx.compose.runtime.*
import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.player.ManualTracks
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.delay

/** Display only. Neither a catalogue fact nor a container tag authorizes playback. */
data class MediaFactsPresentation(
    val catalog: String = "Original language (catalog): Unknown",
    val release: List<String> = emptyList(),
    val file: List<String> = emptyList(),
    val notice: String? = null,
    val observation: String? = null,
) {
    val sourceLines: List<String> get() = release + file + listOfNotNull(notice, observation)
    val compactReleaseClaims: String? get() = release.drop(1).take(3).takeIf { it.isNotEmpty() }
        ?.joinToString(" · ") { it.removePrefix("Claimed ") }?.let { "Release claims: $it" }
    val compactFileMetadata: String? get() = file.takeIf { it.isNotEmpty() }?.let { lines ->
        val summary = lines.drop(1).filter { !it.startsWith("Container tags") && !it.startsWith("Track list") }.take(2)
        "File metadata: " + summary.takeIf { it.isNotEmpty() }?.joinToString(" · ").orEmpty().ifEmpty { "Unknown" }
    }
    val compactQualification: String? get() = listOfNotNull(
        notice?.replace("For this source version; title or episode association is unconfirmed.","Source only; title or episode unconfirmed.")
            ?.replace("Association with the selected release is unconfirmed.","Selected release association unconfirmed."),
        if (file.isNotEmpty()) "Tags do not verify speech or device output." else null,
        if (file.any { it == "Track list may be incomplete." }) "Track list may be incomplete." else null,
        observation,
    ).takeIf { it.isNotEmpty() }?.joinToString(" ")
}

object MediaFacts {
    private fun instant(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }
    private fun fresh(observed: String?, expires: String?, now: Instant, maximum: Duration): Boolean {
        val start = instant(observed) ?: return false
        val end = instant(expires) ?: return false
        return !start.isAfter(now) && end.isAfter(start) && end.isAfter(now) && Duration.between(start,end) <= maximum
    }
    private fun language(raw: String): String = ManualTracks.languageName(raw)
    private fun codec(raw: String): String = when (raw.lowercase()) {
        "h264", "avc", "avc1" -> "H.264"
        "hevc", "h265", "hev1", "hvc1" -> "HEVC"
        else -> raw.uppercase(Locale.ROOT)
    }
    private fun range(raw: String?): String = when (raw) {
        "sdr" -> "SDR"; "hdr10" -> "HDR10"; "hlg" -> "HLG"; "dolby_vision" -> "Dolby Vision"; else -> "Unknown"
    }
    private data class CatalogIdentity(val provider: String, val movie: Boolean, val number: Long)
    private fun identity(id: String): CatalogIdentity? {
        Regex("(tmdb|tvdb):(movie|series|tv):([0-9]+)").matchEntire(id)?.let { match ->
            if (match.groupValues[1] == "tvdb" && match.groupValues[2] == "tv") return null
            val number = match.groupValues[3].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return CatalogIdentity(match.groupValues[1],match.groupValues[2] == "movie",number)
        }
        Regex("tvdb:([0-9]+)").matchEntire(id)?.let { match ->
            return match.groupValues[1].toLongOrNull()?.takeIf { it > 0 }?.let { CatalogIdentity("tvdb",false,it) }
        }
        return null
    }
    private fun sameTitle(id: String, target: DetailsTarget): Boolean {
        val proof = identity(id)
        val actual = identity(target.titleId)
        if ((id.startsWith("tmdb:") || id.startsWith("tvdb:")) && proof == null ||
            (target.titleId.startsWith("tmdb:") || target.titleId.startsWith("tvdb:")) && actual == null) return false
        if (proof != null && proof.movie != target.isMovie || actual != null && actual.movie != target.isMovie) return false
        val tmdb = target.tmdbId?.toLongOrNull()?.takeIf { it > 0 }
        val tvdb = target.tvdbId?.toLongOrNull()?.takeIf { it > 0 }
        if (actual?.provider == "tmdb" && tmdb != null && actual.number != tmdb ||
            actual?.provider == "tvdb" && tvdb != null && actual.number != tvdb) return false
        if (actual != null && proof != null && actual.provider == proof.provider) return actual == proof
        if (id == target.titleId) return true
        return proof != null && proof.number == if (proof.provider == "tmdb") tmdb else tvdb
    }
    private fun original(title: Title?, now: Instant): String? {
        val fact = title?.originalLanguageEvidence ?: return null
        if (title.mediaType !in setOf("movie", "series", "tv")) return null
        if (fact.source != "tmdb" || fact.language == null || !fresh(fact.observedAt,fact.expiresAt,now,Duration.ofHours(24))) return null
        val target = DetailsTarget.from(title)
        val source = fact.sourceTitleId ?: return null
        if (!source.startsWith("tmdb:") || !sameTitle(source,target)) return null
        return language(fact.language)
    }
    private fun ordering(raw: String?): String = raw?.trim()?.lowercase().orEmpty().let { if (it.isEmpty()) "official" else it }
    private fun episodeMatches(actual: PrequeueStatus.TargetEpisode, expected: Episode): Boolean =
        (actual.episodeId == null || actual.episodeId == expected.id) && actual.seasonNumber == expected.seasonNumber && actual.episodeNumber == expected.episodeNumber &&
            (actual.numbering == null || expected.numbering == null || actual.numbering.seriesId == expected.numbering.seriesId &&
                ordering(actual.numbering.ordering) == ordering(expected.numbering.ordering))

    fun present(
        title: Title? = null, evidence: MediaEvidence? = null, target: DetailsTarget? = title?.let(DetailsTarget::from),
        episode: Episode? = null, originCandidateVersion: String? = null, now: Instant = Instant.now(),
        locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault(),
    ): MediaFactsPresentation {
        val loadedTarget = title?.let(DetailsTarget::from)
        val catalogueMatches = target == null || loadedTarget != null && sameTitle(target.titleId,target) &&
            (sameTitle(loadedTarget.titleId,target) || sameTitle(target.titleId,loadedTarget))
        val catalog = "Original language (catalog): ${if (catalogueMatches) original(title,now) ?: "Unknown" else "Unknown"}"
        if (evidence == null) return MediaFactsPresentation(catalog)
        fun unknown() = MediaFactsPresentation(catalog, notice = "Source metadata: Unknown")
        if (!evidence.hasValidVersionIdentity()) return unknown()
        if (evidence.titleId != null && (target == null || !sameTitle(evidence.titleId,target))) return unknown()
        if (evidence.targetEpisode != null && (episode == null || !episodeMatches(evidence.targetEpisode,episode))) return unknown()
        if (evidence.candidateVersion != null && originCandidateVersion != null && evidence.candidateVersion != originCandidateVersion) return unknown()
        val candidate = evidence.candidate?.takeIf { it.source == "release_metadata" && fresh(it.observedAt,it.expiresAt,now,Duration.ofMinutes(15)) }
        val probed = evidence.probed?.takeIf { it.source == "ffprobe" && fresh(it.observedAt,it.expiresAt,now,Duration.ofMinutes(15)) }
        val release = candidate?.let { value -> buildList {
            value.audioLanguages?.takeIf { it.isNotEmpty() }?.let { add("Claimed audio: " + it.joinToString(", ",transform=::language)) }
            value.subtitleLanguages?.takeIf { it.isNotEmpty() }?.let { add("Claimed subtitles: " + it.joinToString(", ",transform=::language)) }
            value.resolution?.let { add("Claimed resolution: $it") }
            value.videoCodec?.let { add("Claimed video codec: ${codec(it)}") }
            value.audioCodecs?.takeIf { it.isNotEmpty() }?.let { add("Claimed audio codecs: " + it.joinToString(", ",transform=::codec)) }
            value.hdr?.let { add("Claimed dynamic range: ${range(it)}") }
        }.takeIf { it.isNotEmpty() }?.let { listOf("Release claims") + it } }.orEmpty()
        val file = probed?.let { value -> buildList {
            add("File metadata")
            value.video?.let { video ->
                if (video.width != null && video.height != null) add("Encoded dimensions: ${video.width} × ${video.height}")
                video.codec?.let { add("Video codec tag: ${codec(it)}") }
                add("Dynamic range metadata: ${range(video.hdr)}")
            }
            value.audioStreams?.forEach { stream ->
                add("Audio tag ${stream.index}: ${stream.language?.let(::language) ?: "Unknown"}" +
                    (stream.codec?.let { " · ${codec(it)}" }.orEmpty()) +
                    when(stream.audioDescription) { true -> " · AD tag present"; false -> " · AD tag absent"; null -> " · AD tag unknown" })
            }
            value.subtitleStreams?.forEach { stream ->
                add("Subtitle tag ${stream.index}: ${stream.language?.let(::language) ?: "Unknown"}" +
                    (stream.codec?.let { " · ${codec(it)}" }.orEmpty()) + if (stream.forced == true) " · forced tag" else "")
            }
            if (!value.audioStreamsComplete || !value.subtitleStreamsComplete) add("Track list may be incomplete.")
            add("Container tags do not verify the spoken language or the quality played by this device.")
        } }.orEmpty()
        if (release.isEmpty() && file.isEmpty()) return unknown()
        val bound = evidence.titleId?.let(::identity) != null && (target?.isMovie == true || episode?.numbering != null &&
            evidence.targetEpisode?.numbering?.let { identity(it.seriesId)?.movie == false && ordering(it.ordering) == "official" } == true &&
            evidence.targetEpisode?.episodeId == episode.id)
        val observed = listOfNotNull(candidate?.observedAt?.let(::instant),probed?.observedAt?.let(::instant)).minOrNull()
        val date = observed?.let { runCatching { (if (candidate != null && probed != null) "Oldest observation " else "Observed ") + DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(zone).format(it) }.getOrNull() }
        val scopeNotice = listOfNotNull(
            if (!bound) "For this source version; title or episode association is unconfirmed." else null,
            if (originCandidateVersion != null && evidence.probed != null && evidence.candidateVersion == null)
                "Association with the selected release is unconfirmed." else null,
        ).takeIf { it.isNotEmpty() }?.joinToString(" ")
        return MediaFactsPresentation(catalog,release,file,scopeNotice,date)
    }

    /** Local expiry only: this never schedules a source search or a media probe. */
    fun nextUpdateMillis(original: OriginalLanguageEvidence?, evidence: MediaEvidence?, now: Instant): Long {
        val next = listOfNotNull(original?.observedAt,original?.expiresAt,evidence?.candidate?.observedAt,evidence?.candidate?.expiresAt,
            evidence?.probed?.observedAt,evidence?.probed?.expiresAt).mapNotNull(::instant).filter { it.isAfter(now) }.minOrNull()
        return next?.let { Duration.between(now,it).let { remaining -> if (remaining.seconds >= 60) 60_000 else remaining.toMillis().coerceAtLeast(1) } } ?: 60_000
    }
}

/** A visible surface updates its expiry clock, never its provider data. */
@Composable
fun rememberMediaFacts(
    context: Long, title: Title? = null, evidence: MediaEvidence? = null,
    target: DetailsTarget? = title?.let(DetailsTarget::from), episode: Episode? = null, originCandidateVersion: String? = null,
): MediaFactsPresentation {
    val current by ProfileDataContext.changes.collectAsState()
    var now by remember(context,title?.originalLanguageEvidence,evidence) { mutableStateOf(Instant.now()) }
    LaunchedEffect(context,title?.originalLanguageEvidence,evidence) {
        while (context == ProfileDataContext.current) {
            now = Instant.now()
            delay(MediaFacts.nextUpdateMillis(title?.originalLanguageEvidence,evidence,now))
        }
    }
    return if (current == context) MediaFacts.present(title,evidence,target,episode,originCandidateVersion,now) else MediaFactsPresentation()
}

/** A late button callback cannot apply a source selected under another profile or server. */
fun runInMediaContext(expected: Long, action: () -> Unit) {
    try { ApiClient.inContext(expected) { action() } }
    catch (_: kotlinx.coroutines.CancellationException) { }
}
