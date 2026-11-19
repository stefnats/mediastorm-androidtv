package com.strmr.tv.live

import com.strmr.tv.api.ApiJson
import com.strmr.tv.api.ObservedResponseClock
import kotlinx.serialization.decodeFromString
import java.time.Instant

/** One measured recorded-media section. Capture wall clocks are not media offsets. */
data class RecordingDerivedOffset(val partIndex: Int, val startMs: Long, val durationMs: Long)
data class RecordingDerivedDescriptor(
    val recordingId: String, val state: String, val sourceIncomplete: Boolean, val gapsOmitted: Boolean,
    val sourceStatus: String, val partOffsets: List<RecordingDerivedOffset>, val reason: String? = null,
    val id: String? = null, val durationMs: Long? = null, val bytes: Long? = null,
    val observedAt: String? = null, val expiresAt: String? = null,
) {
    fun label(): String = if (state == "unknown") "No current joined copy is stored. Read status or prepare explicitly."
        else "Joined copy ready. Container duration: approximately ${requireNotNull(durationMs) / 1000}s. " +
            (if (sourceIncomplete) "The source recording is incomplete. " else "") +
            (if (gapsOmitted) "Known capture gaps were omitted; missing content was not restored. " else "") +
            "Original parts remain available. Complete or seamless playback is not guaranteed."
}

/** Original view observation only. The backend independently checks actual files and authority. */
class RecordingDerivedSource private constructor(val recording: Recording, val profile: String) {
    fun matches(value: Recording?) = value == recording
    companion object {
        fun capture(value: Recording, profile: String): RecordingDerivedSource? {
            val capture = value.capture ?: return null
            if (!Regex("[A-Za-z0-9_-]{1,128}").matches(value.id) || profile.isBlank() || value.userId != profile ||
                value.status !in setOf("completed", "failed", "cancelled") || value.updatedAt?.let(::derivedUtc) == null ||
                capture.version != 1 || capture.restored || capture.phase !in setOf("finished", "interrupted") ||
                capture.incomplete == null || capture.sourceChanges == null || capture.sourceChanges !in 0..256 || capture.parts.size !in 2..256 || capture.gaps.size > 256) return null
            val parts = capture.parts.sortedBy { it.index }
            if (parts.withIndex().any { (n, p) -> p.index != n + 1 || !RecordingCaptureRules.playable(value, p) } ||
                capture.gaps.any { gap ->
                    val start = RecordingRules.instant(gap.startedAt); val end = gap.endedAt?.let(RecordingRules::instant)
                    start == null || end == null || end.isBefore(start) || gap.reason !in setOf("capacity", "source_retry")
                }) return null
            for ((previous, next) in parts.zipWithNext()) {
                val end = requireNotNull(RecordingRules.instant(requireNotNull(previous.endedAt)))
                val start = requireNotNull(RecordingRules.instant(next.startedAt))
                if (start.isBefore(end)) return null
                if (java.time.Duration.between(end, start).toMillis() > 250 && capture.gaps.none { gap ->
                    val a = requireNotNull(RecordingRules.instant(gap.startedAt))
                    val b = requireNotNull(RecordingRules.instant(requireNotNull(gap.endedAt)))
                    !a.isAfter(end.plusMillis(250)) && !b.isBefore(start.minusMillis(250))
                }) return null
            }
            return RecordingDerivedSource(value.copy(capture = capture.copy(parts = java.util.Collections.unmodifiableList(capture.parts.toList()), gaps = java.util.Collections.unmodifiableList(capture.gaps.toList()))), profile)
        }
    }
}

/** A response clock limits presentation lifetime; this object is never a media grant. */
class RecordingDerivedObservation private constructor(
    val source: RecordingDerivedSource, val descriptor: RecordingDerivedDescriptor,
    val deadlineNanos: Long?, private val clock: ObservedResponseClock,
) {
    fun ready(now: Long = System.nanoTime()) = runCatching {
        clock.upperMillis(now); descriptor.state == "ready" && deadlineNanos?.let { now < it } == true
    }.getOrDefault(false)
    fun sameArtifact(other: RecordingDerivedObservation) = source === other.source && descriptor == other.descriptor
    fun constrain(previous: RecordingDerivedObservation?): RecordingDerivedObservation {
        if (previous == null) return this
        require(!clock.predates(previous.clock)) { "Recording response clock changed" }
        val bounded = if (descriptor.id != null && descriptor.id == previous.descriptor.id && deadlineNanos != null && previous.deadlineNanos != null)
            minOf(deadlineNanos, previous.deadlineNanos) else deadlineNanos
        return RecordingDerivedObservation(source, descriptor, bounded, clock)
    }
    companion object {
        fun accept(source: RecordingDerivedSource, raw: String, clock: ObservedResponseClock): RecordingDerivedObservation {
            val descriptor = decodeRecordingDerived(raw, source)
            val deadline = if (descriptor.state == "ready") {
                require(derivedUtc(requireNotNull(descriptor.observedAt))!! <= clock.upperMillis())
                clock.deadlineNanos(requireNotNull(derivedUtc(requireNotNull(descriptor.expiresAt))), 86_400_000L)
            } else null
            return RecordingDerivedObservation(source, descriptor, deadline, clock)
        }
    }
    override fun toString() = "RecordingDerivedObservation(presentation)"
}

internal fun derivedUtc(raw: String): Long? = try {
    if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z").matches(raw)) null else Instant.parse(raw).toEpochMilli()
} catch (_: Exception) { null }

/** Source-specific finite JSON reader. Duplicate keys cannot disappear into JsonObject. */
private class DerivedJson(private val raw: String) {
    private var at = 0
    private fun space() { while (at < raw.length && raw[at] in " \t\r\n") at++ }
    private fun char(): Char { space(); require(at < raw.length); return raw[at++] }
    private fun text(): String {
        space(); val start = at; require(raw.getOrNull(at++) == '"'); var escaped = false
        while (at < raw.length) {
            val c = raw[at++]
            if (!escaped && c == '"') {
                require(at - start <= 4096)
                return ApiJson.decodeFromString<String>(raw.substring(start, at)).also {
                    require(it.length <= 512 && it.none { c -> c.isISOControl() || c.isSurrogate() })
                }
            }
            escaped = !escaped && c == '\\'
        }
        error("Unterminated recording descriptor")
    }
    private fun value(depth: Int): Any {
        require(depth <= 3); space()
        return when (raw.getOrNull(at)) {
            '{' -> {
                at++; val map = linkedMapOf<String, Any>(); space()
                if (raw.getOrNull(at) == '}') { at++; map } else {
                    while (true) {
                        val key = text(); require(key !in map && map.size < 16 && char() == ':')
                        map[key] = value(depth + 1)
                        when (char()) { '}' -> break; ',' -> Unit; else -> error("Object separator") }
                    }; map
                }
            }
            '[' -> {
                at++; val list = mutableListOf<Any>(); space()
                if (raw.getOrNull(at) == ']') { at++; list } else {
                    while (true) {
                        require(list.size < 256); list += value(depth + 1)
                        when (char()) { ']' -> break; ',' -> Unit; else -> error("Array separator") }
                    }; list
                }
            }
            '"' -> text()
            else -> {
                val start = at
                while (at < raw.length && raw[at] !in " ,}\r\n\t]") at++
                val token = raw.substring(start, at)
                when (token) { "true" -> true; "false" -> false; else -> {
                    require(Regex("0|-?[1-9][0-9]*").matches(token) && token.length <= 20)
                    token.toLong()
                } }
            }
        }
    }
    fun parse(): Map<String, Any> {
        require(raw.length <= 65_536 && raw.toByteArray(Charsets.UTF_8).size <= 65_536)
        val result = value(0); space(); require(at == raw.length)
        @Suppress("UNCHECKED_CAST") return result as? Map<String, Any> ?: error("Descriptor object required")
    }
}

internal fun decodeRecordingDerived(raw: String, source: RecordingDerivedSource): RecordingDerivedDescriptor {
    try {
        val root = DerivedJson(raw).parse()
        fun text(key: String) = (root[key] as? String)?.takeIf { it.isNotBlank() } ?: error(key)
        fun bool(key: String) = root[key] as? Boolean ?: error(key)
        val common = setOf("recordingId", "state", "sourceIncomplete", "gapsOmitted", "sourceStatus", "partOffsets")
        val state = text("state")
        val expected = common + if (state == "unknown") setOf("reason") else setOf("id", "durationMs", "bytes", "observedAt", "expiresAt")
        require(root.keys == expected && text("recordingId") == source.recording.id && text("sourceStatus") == source.recording.status)
        val capture = requireNotNull(source.recording.capture)
        val incomplete = bool("sourceIncomplete"); val gaps = bool("gapsOmitted")
        require(!gaps || incomplete); require(capture.incomplete != true || incomplete)
        val offsets = root["partOffsets"] as? List<*> ?: error("offsets")
        if (state == "unknown") {
            require(offsets.isEmpty() && text("reason") in setOf("not_prepared", "configuration_changed", "source_changed", "expired"))
            return RecordingDerivedDescriptor(source.recording.id, state, incomplete, gaps, source.recording.status, emptyList(), reason = text("reason"))
        }
        require(state == "ready" && Regex("[a-f0-9]{32}").matches(text("id")))
        val duration = root["durationMs"] as? Long ?: error("duration")
        val bytes = root["bytes"] as? Long ?: error("bytes")
        require(duration in 1..43_200_000L && bytes in 1..(16L shl 30) && offsets.size == capture.parts.size)
        require(capture.gaps.isEmpty() || gaps && incomplete)
        var end = 0L
        val parts = offsets.mapIndexed { n, value ->
            val part = value as? Map<*, *> ?: error("part")
            require(part.keys == setOf("partIndex", "startMs", "durationMs") && part["partIndex"] == (n + 1).toLong() && part["startMs"] == end)
            val length = part["durationMs"] as? Long ?: error("duration"); require(length > 0)
            val result = RecordingDerivedOffset(n + 1, end, length); end = Math.addExact(end, length); result
        }
        require(end == duration)
        val observed = derivedUtc(text("observedAt")) ?: error("observed")
        val expires = derivedUtc(text("expiresAt")) ?: error("expires")
        require(expires > observed && Math.subtractExact(expires, observed) <= 86_400_000L)
        return RecordingDerivedDescriptor(source.recording.id, state, incomplete, gaps, source.recording.status, java.util.Collections.unmodifiableList(parts),
            id = text("id"), durationMs = duration, bytes = bytes, observedAt = text("observedAt"), expiresAt = text("expiresAt"))
    } catch (_: Exception) { throw IllegalArgumentException("Joined recording response is unavailable") }
}
