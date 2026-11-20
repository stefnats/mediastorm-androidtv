package com.strmr.tv.live

import com.strmr.tv.api.ApiJson
import com.strmr.tv.api.ObservedResponseClock
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

/** Canonical nonnegative int64 DATA; never pass counters through a floating point number. */
class RecordingProgressCounter private constructor(val value: Long) {
    val wire: String get() = value.toString()
    internal fun next() = Math.addExact(value, 1L)
    companion object {
        fun parse(raw: String): RecordingProgressCounter {
            require(raw.length <= 19 && Regex("0|[1-9][0-9]*").matches(raw))
            return RecordingProgressCounter(raw.toLong().also { require(it >= 0) })
        }
    }
    override fun equals(other: Any?) = other is RecordingProgressCounter && value == other.value
    override fun hashCode() = value.hashCode()
    override fun toString() = "RecordingProgressCounter(data)"
}

data class RecordingProgressIdentity(val recordingId: String, val incarnation: String, val artifactId: String,
    val mappingSha256: String, val durationMs: Long)
data class RecordingProgressSnapshot(val identity: RecordingProgressIdentity, val revision: RecordingProgressCounter,
    val writerGeneration: RecordingProgressCounter, val positionState: String, val positionMs: Long?,
    val atDerivedEnd: Boolean?, val validUntil: String)
data class RecordingProgressResult(val identity: RecordingProgressIdentity, val revision: RecordingProgressCounter,
    val writerGeneration: RecordingProgressCounter, val positionMs: Long, val atDerivedEnd: Boolean,
    val operationId: String, val validUntil: String)

/** Original reply-adoption lifetime only. The fields remain DATA, not media or writer authority. */
class RecordingProgressSnapshotObservation private constructor(val original: RecordingDerivedObservation,
    val data: RecordingProgressSnapshot, val expectedContext: Long, val deadlineNanos: Long,
    private val clock: ObservedResponseClock) {
    fun adoptable(now: Long = System.nanoTime()) = progressCurrent(original, clock, deadlineNanos, now)
    companion object {
        fun accept(original: RecordingDerivedObservation, raw: String, clock: ObservedResponseClock,
            expectedContext: Long, now: Long = System.nanoTime()): RecordingProgressSnapshotObservation {
            val root = ProgressJson(raw).parse()
            require(root.keys == setOf("schemaVersion", "timeline", "progress") && root["schemaVersion"] == 1L)
            val timeline = root.obj("timeline")
            val identity = progressTimeline(original, timeline, clock)
            val value = root.obj("progress")
            val state = value.text("positionState")
            require(state in setOf("none", "matching", "conflict"))
            val common = setOf("validUntil", "recordingId", "incarnation", "artifactId", "mappingSha256", "durationMs", "revision", "writerGeneration", "positionState")
            require(value.keys == common + if (state == "matching") setOf("positionMs", "atDerivedEnd") else emptySet())
            require(progressIdentity(value) == identity)
            val position = if (state == "matching") value.number("positionMs") else null
            val end = if (state == "matching") value.flag("atDerivedEnd") else null
            require(position == null || position in 0..identity.durationMs && end == (position == identity.durationMs))
            val until = value.text("validUntil")
            val data = RecordingProgressSnapshot(identity, value.counter("revision"), value.counter("writerGeneration"), state, position, end, until)
            val deadline = progressDeadline(original, clock, until)
            return RecordingProgressSnapshotObservation(original, data, expectedContext, deadline, clock).also { require(it.adoptable(now)) }
        }
    }
    override fun toString() = "RecordingProgressSnapshotObservation(data)"
}

/** A single local attempt; the backend independently performs the original current authorization and CAS. */
class RecordingProgressCommand private constructor(val original: RecordingDerivedObservation, val expectedContext: Long,
    val identity: RecordingProgressIdentity, val revision: RecordingProgressCounter,
    val writerGeneration: RecordingProgressCounter, val action: String, val positionMs: Long,
    internal val expectedResultPositionMs: Long) {
    private val attempted = AtomicBoolean()
    internal fun beginAttempt() { check(attempted.compareAndSet(false, true)) { "Recording progress attempt already used" } }
    internal fun body(): JsonObject = buildJsonObject {
        put("action", action); put("expectedRevision", revision.wire); put("writerGeneration", writerGeneration.wire)
        put("incarnation", identity.incarnation); put("artifactId", identity.artifactId); put("mappingSha256", identity.mappingSha256)
        put("durationMs", identity.durationMs); put("positionMs", positionMs)
    }
    companion object {
        fun fromStart(snapshot: RecordingProgressSnapshotObservation) = claim(snapshot, "from-start", 0)
        fun resume(snapshot: RecordingProgressSnapshotObservation): RecordingProgressCommand {
            require(snapshot.data.positionState == "matching")
            return claim(snapshot, "resume", requireNotNull(snapshot.data.positionMs))
        }
        private fun claim(snapshot: RecordingProgressSnapshotObservation, action: String, result: Long) =
            RecordingProgressCommand(snapshot.original, snapshot.expectedContext, snapshot.data.identity, snapshot.data.revision,
                snapshot.data.writerGeneration, action, 0, result)
        fun touch(previous: RecordingProgressWriteObservation, positionMs: Long): RecordingProgressCommand {
            require(positionMs in 0..previous.data.identity.durationMs)
            return RecordingProgressCommand(previous.original, previous.expectedContext, previous.data.identity, previous.data.revision,
                previous.data.writerGeneration, "touch", positionMs, positionMs)
        }
    }
    override fun toString() = "RecordingProgressCommand(data)"
}

class RecordingProgressWriteObservation private constructor(val original: RecordingDerivedObservation,
    val data: RecordingProgressResult, val expectedContext: Long, val deadlineNanos: Long,
    private val clock: ObservedResponseClock) {
    fun adoptable(now: Long = System.nanoTime()) = progressCurrent(original, clock, deadlineNanos, now)
    companion object {
        fun accept(command: RecordingProgressCommand, raw: String, clock: ObservedResponseClock,
            now: Long = System.nanoTime()): RecordingProgressWriteObservation {
            val root = ProgressJson(raw).parse()
            require(root.keys == setOf("schemaVersion", "result") && root["schemaVersion"] == 1L)
            val value = root.obj("result")
            require(value.keys == setOf("validUntil", "recordingId", "incarnation", "artifactId", "mappingSha256", "durationMs", "positionMs", "atDerivedEnd", "revision", "writerGeneration", "operationId"))
            val identity = progressIdentity(value)
            require(identity == command.identity)
            val revision = value.counter("revision"); val generation = value.counter("writerGeneration")
            require(revision.value == command.revision.next())
            require(generation.value == if (command.action == "touch") command.writerGeneration.value else command.writerGeneration.next())
            val position = value.number("positionMs"); val end = value.flag("atDerivedEnd")
            require(position == command.expectedResultPositionMs && position in 0..identity.durationMs && end == (position == identity.durationMs))
            val operation = value.text("operationId"); progressUUID(operation)
            val until = value.text("validUntil")
            val data = RecordingProgressResult(identity, revision, generation, position, end, operation, until)
            val deadline = progressDeadline(command.original, clock, until)
            return RecordingProgressWriteObservation(command.original, data, command.expectedContext, deadline, clock).also { require(it.adoptable(now)) }
        }
    }
    override fun toString() = "RecordingProgressWriteObservation(data)"
}

private fun progressCurrent(original: RecordingDerivedObservation, clock: ObservedResponseClock, deadline: Long, now: Long) =
    runCatching { clock.upperMillis(now); now < deadline && original.ready(now) }.getOrDefault(false)
private fun progressDeadline(original: RecordingDerivedObservation, clock: ObservedResponseClock, until: String): Long {
    val expires = progressInstant(requireNotNull(original.descriptor.expiresAt))
    val value = progressInstant(until)
    require(!value.isAfter(expires))
    return minOf(requireNotNull(original.deadlineNanos), clock.deadlineNanos(value.toEpochMilli(), 30_000L))
}
private fun progressIdentity(value: Map<String, Any>): RecordingProgressIdentity {
    val id = value.text("recordingId"); val incarnation = value.text("incarnation")
    val artifact = value.text("artifactId"); val mapping = value.text("mappingSha256"); val duration = value.number("durationMs")
    require(Regex("[A-Za-z0-9_-]{1,128}").matches(id)); progressUUID(incarnation)
    require(Regex("[a-f0-9]{32}").matches(artifact) && Regex("[a-f0-9]{64}").matches(mapping) && duration in 1..43_200_000L)
    return RecordingProgressIdentity(id, incarnation, artifact, mapping, duration)
}
internal fun recordingProgressMapping(original: RecordingDerivedObservation, incarnation: String): String {
    progressUUID(incarnation)
    val d = original.descriptor
    require(d.state == "ready")
    val observed = requireNotNull(d.observedAt); val expires = requireNotNull(d.expiresAt)
    progressInstant(observed); progressInstant(expires)
    val tuple = buildJsonArray {
        add("recording-derived-timeline/v1"); add(d.recordingId); add(incarnation); add(requireNotNull(d.id))
        add(requireNotNull(d.durationMs)); add(requireNotNull(d.bytes)); add(d.sourceStatus)
        add(d.sourceIncomplete); add(d.gapsOmitted); add(observed); add(expires)
        add(buildJsonArray { d.partOffsets.forEach { p -> add(buildJsonArray { add(p.partIndex); add(p.startMs); add(p.durationMs) }) } })
    }
    return MessageDigest.getInstance("SHA-256").digest(tuple.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
private fun progressTimeline(original: RecordingDerivedObservation, value: Map<String, Any>, clock: ObservedResponseClock): RecordingProgressIdentity {
    require(value.keys == setOf("schemaVersion", "recordingId", "state", "coordinateSystem", "incarnation", "artifactId", "mappingRevision", "durationMs", "spans", "sourceStatus", "sourceIncomplete", "gapsOmitted", "observedAt", "expiresAt"))
    require(value["schemaVersion"] == 1L && value.text("state") == "known" && value.text("coordinateSystem") == "derived-media")
    val d = original.descriptor
    require(d.state == "ready" && value.text("recordingId") == d.recordingId && value.text("artifactId") == d.id && value.number("durationMs") == d.durationMs)
    require(value.text("sourceStatus") == d.sourceStatus && value.flag("sourceIncomplete") == d.sourceIncomplete && value.flag("gapsOmitted") == d.gapsOmitted)
    require(value.text("observedAt") == d.observedAt && value.text("expiresAt") == d.expiresAt)
    val observed = progressInstant(value.text("observedAt")); val expires = progressInstant(value.text("expiresAt"))
    val observedUpperMS = Math.addExact(observed.toEpochMilli(), if (observed.nano % 1_000_000 == 0) 0L else 1L)
    require(observed.isBefore(expires) && clock.observedAtReceipt(observedUpperMS))
    val spans = value["spans"] as? List<*> ?: error("Recording timeline spans missing")
    require(spans.size == d.partOffsets.size)
    spans.forEachIndexed { index, raw ->
        val part = raw as? Map<*, *> ?: error("Recording timeline span unavailable")
        val expected = d.partOffsets[index]
        require(part.keys == setOf("partIndex", "startMs", "durationMs") && part["partIndex"] == expected.partIndex.toLong() && part["startMs"] == expected.startMs && part["durationMs"] == expected.durationMs)
    }
    val incarnation = value.text("incarnation")
    val mapping = recordingProgressMapping(original, incarnation)
    require(value.text("mappingRevision") == mapping)
    return RecordingProgressIdentity(d.recordingId, incarnation, requireNotNull(d.id), mapping, requireNotNull(d.durationMs))
}
private fun progressUUID(raw: String) {
    require(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}").matches(raw))
    require(UUID.fromString(raw) != UUID(0L, 0L))
}
private fun progressInstant(raw: String): Instant {
    require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z").matches(raw))
    val value = Instant.parse(raw)
    require(value.atOffset(ZoneOffset.UTC).year in 1..9999)
    val whole = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC).format(value)
    val fraction = if (value.nano == 0) "" else "." + String.format(Locale.ROOT, "%09d", value.nano).trimEnd('0')
    require(raw == whole + fraction + "Z")
    return value
}
private fun Map<String, Any>.text(key: String) = (get(key) as? String)?.takeIf { it.isNotBlank() } ?: error("Recording progress field unavailable")
private fun Map<String, Any>.number(key: String) = get(key) as? Long ?: error("Recording progress integer unavailable")
private fun Map<String, Any>.flag(key: String) = get(key) as? Boolean ?: error("Recording progress Boolean unavailable")
private fun Map<String, Any>.counter(key: String) = RecordingProgressCounter.parse(text(key))
@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.obj(key: String) = get(key) as? Map<String, Any> ?: error("Recording progress object unavailable")

/** Source-specific finite parser: duplicate names are checked after JSON unescaping. */
private class ProgressJson(private val raw: String) {
    private var at = 0
    private fun space() { while (at < raw.length && raw[at] in " \t\r\n") at++ }
    private fun char(): Char { space(); require(at < raw.length); return raw[at++] }
    private fun text(): String {
        space(); val start = at; require(raw.getOrNull(at++) == '"'); var escaped = false
        while (at < raw.length) {
            val c = raw[at++]
            if (!escaped && c == '"') {
                require(at - start <= 4096)
                return ApiJson.decodeFromString<String>(raw.substring(start, at)).also { value -> require(value.length <= 512 && value.none { it.isISOControl() || it.isSurrogate() }) }
            }
            escaped = !escaped && c == '\\'
        }
        error("Recording progress string unavailable")
    }
    private fun value(depth: Int): Any {
        require(depth <= 4); space()
        return when (raw.getOrNull(at)) {
            '{' -> {
                at++; val result = linkedMapOf<String, Any>(); space()
                if (raw.getOrNull(at) == '}') { at++; result } else {
                    while (true) {
                        val key = text(); require(key !in result && result.size < 16 && char() == ':'); result[key] = value(depth + 1)
                        when (char()) { '}' -> break; ',' -> Unit; else -> error("Recording progress object separator") }
                    }; result
                }
            }
            '[' -> {
                at++; val result = mutableListOf<Any>(); space()
                if (raw.getOrNull(at) == ']') { at++; result } else {
                    while (true) {
                        require(result.size < 256); result += value(depth + 1)
                        when (char()) { ']' -> break; ',' -> Unit; else -> error("Recording progress array separator") }
                    }; result
                }
            }
            '"' -> text()
            else -> {
                val start = at
                while (at < raw.length && raw[at] !in " ,}\r\n\t]") at++
                val token = raw.substring(start, at)
                when (token) {
                    "true" -> true; "false" -> false
                    else -> { require(token.length <= 20 && Regex("0|-?[1-9][0-9]*").matches(token)); token.toLong() }
                }
            }
        }
    }
    fun parse(): Map<String, Any> {
        require(raw.length <= 65_536 && raw.toByteArray(Charsets.UTF_8).size <= 65_536)
        val result = value(0); space(); require(at == raw.length)
        @Suppress("UNCHECKED_CAST")
        return result as? Map<String, Any> ?: error("Recording progress object required")
    }
}
