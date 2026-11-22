package com.strmr.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi

/** The Info panel's stream-details rows, label / value; a row whose value is unknown is left out. */
fun streamInfoRows(session: PlayerSession, video: Format?, audio: Format?): List<Pair<String, String>> {
    val v = video?.facts()
    val a = audio?.facts()
    return listOfNotNull(
        session.releaseName?.takeIf { it.isNotBlank() && !session.isLive }?.let { "File" to it },
        v?.let(StreamInfo::video)?.let { "Video" to it },
        v?.let { StreamInfo.dynamicRange(it, session.sourceDolbyVision, session.releaseName, session.sourceHdr10) }?.let { "Dynamic range" to it },
        a?.let(StreamInfo::audio)?.let { "Audio" to it },
        session.sourceLabel?.let { "Source" to it },
    ) + observedQualityRows(session.sourceAttributes)
}

/** Both native Info panels show the file probe separately from the unchanged release name. */
internal fun observedQualityRows(attributes: Map<String, String>): List<Pair<String, String>> {
    return buildList {
        addAll(observedResolutionRows(attributes))
        if (attributes["codecSource"] == "file_probe") {
            attributes.probedCodec("codec")?.let { codec ->
                add("File video codec" to "${codecLabel(codec)} (file probe)")
                attributes.claim("advertisedCodec", codec)?.let { add("Advertised video codec" to it) }
            }
        }
        if (attributes["audioSource"] == "file_probe") {
            val index = attributes["audioStreamIndex"]?.toIntOrNull()?.takeIf { it >= 0 }
            val codec = attributes.probedCodec("audio")
            if (index != null && codec != null) {
                add("File audio (prepared track)" to "${codecLabel(codec)} · container stream $index (file probe)")
                attributes.claim("advertisedAudio", codec)?.let { add("Advertised audio" to it) }
            }
        }
        if (attributes["hdrSource"] == "file_probe") {
            val hdr = attributes["hdr"]?.takeIf { it in setOf("SDR", "HDR10", "HDR10+", "HLG", "DV") }
            if (hdr != null) {
                add("File dynamic range" to "${if (hdr == "DV") "Dolby Vision" else hdr} (file probe)")
                attributes.claim("advertisedHDR", hdr)?.let { add("Advertised dynamic range" to it) }
                val dv = attributes["hasDV"]?.toBooleanStrictOrNull()
                if (dv != null) {
                    add("File Dolby Vision" to "${if (dv) "Yes" else "No"} (file probe)")
                    attributes.claim("advertisedHasDV", dv.toString())?.let { add("Advertised Dolby Vision" to it) }
                }
            }
        }
    }
}

/** Details' compact track caption retains its resolution-only comparison. */
internal fun observedResolutionRows(attributes: Map<String, String>): List<Pair<String, String>> {
    if (attributes["resolutionSource"] != "file_probe") return emptyList()
    val resolution = attributes["resolution"]?.takeIf { Regex("[0-9]{3,4}p").matches(it) } ?: return emptyList()
    val advertised = attributes["advertisedResolution"]?.takeIf { it.isNotBlank() && it != resolution }
    return listOfNotNull("File quality" to "$resolution (file probe)", advertised?.let { "Advertised quality" to it })
}

private fun Map<String, String>.probedCodec(key: String): String? = get(key)?.trim()
    ?.takeUnless { it.lowercase() in setOf("", "unknown", "und", "none", "n/a") }

private fun Map<String, String>.claim(key: String, observed: String): String? = get(key)?.takeIf { it.isNotBlank() && !it.equals(observed, true) }

private fun codecLabel(value: String): String = when (value.lowercase()) {
    "h264", "avc" -> "H.264"
    "hevc", "h265" -> "HEVC"
    "mpeg2video" -> "MPEG-2"
    "eac3" -> "E-AC-3"
    "ac3" -> "AC-3"
    "truehd" -> "TrueHD"
    else -> value.uppercase()
}

@OptIn(UnstableApi::class)
internal fun Format.facts() = TrackFacts(
    mime = sampleMimeType,
    codecs = codecs,
    width = width.takeIf { it != Format.NO_VALUE },
    height = height.takeIf { it != Format.NO_VALUE },
    frameRate = frameRate.takeIf { it != Format.NO_VALUE.toFloat() },
    bitrate = (averageBitrate.takeIf { it > 0 } ?: bitrate.takeIf { it > 0 } ?: peakBitrate.takeIf { it > 0 }),
    colorTransfer = colorInfo?.colorTransfer?.takeIf { it != Format.NO_VALUE },
    channels = channelCount.takeIf { it != Format.NO_VALUE },
    language = language,
    label = label,
)
