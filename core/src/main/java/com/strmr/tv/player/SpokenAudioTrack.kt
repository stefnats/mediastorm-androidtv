package com.strmr.tv.player

import androidx.media3.common.Format
import com.strmr.tv.api.ReleaseReportOptions
import com.strmr.tv.api.SpokenAudioReply

/** No language or type-order guess may turn a different container track into this check. */
internal fun spokenAudioContainerIndex(format: Format?, typeIndex: Int?, options: ReleaseReportOptions): Int? {
    val selected = format ?: return null
    val container = selected.id?.toIntOrNull()?.takeIf { it > 0 }?.minus(1) ?: return null
    val ordered = typeIndex?.takeIf { it >= 0 }?.let { options.audioTracks.getOrNull(it) } ?: return null
    if (ordered.index != container) return null
    val matches = options.audioTracks.filter { it.index == container }
    return container.takeIf { matches.size == 1 && TrackPreferences.sameLanguage(selected.language, matches.single().language) }
}

internal fun spokenAudioMessage(reply: SpokenAudioReply, wanted: String): String {
    val credible = reply.samples == 2 && reply.confidence?.let { it.isFinite() && it >= 0.8 && it <= 1 } == true &&
        reply.requestedLanguage.matches(Regex("[a-z]{3}")) && reply.requestedLanguage !in setOf("und", "mul", "zxx") &&
        reply.detectedLanguage?.matches(Regex("[a-z]{3}")) == true && reply.detectedLanguage !in setOf("und", "mul", "zxx") &&
        TrackPreferences.sameLanguage(reply.requestedLanguage, wanted)
    if (!credible || reply.status == "unknown") return "Spoken language is unknown from these samples. No report or source change was made."
    val same = TrackPreferences.sameLanguage(reply.detectedLanguage, reply.requestedLanguage)
    if (reply.status == "likely_match" && same) return "Both samples likely match ${languageLabel(reply.requestedLanguage)}. This is only a hint; mixed dialogue and model errors remain possible."
    if (reply.status == "likely_mismatch" && !same) return "Both samples likely contain ${languageLabel(reply.detectedLanguage.orEmpty())}, rather than ${languageLabel(reply.requestedLanguage)}. Review the audio before reporting; nothing was changed."
    return "Spoken language is unknown from these samples. No report or source change was made."
}
