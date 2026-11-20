package com.strmr.tv.live

import com.strmr.tv.api.ApiJson
import com.strmr.tv.api.ObservedResponseClock
import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic inputs, not a separately exported/executed Go-server interoperability fixture. */
internal object ProgressTestData {
    const val incarnation = "11111111-2222-4333-8444-555555555555"
    const val operation = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    const val observed = "2026-11-19T12:00:00.123456789Z"
    const val expires = "2026-11-19T13:00:00.123456789Z"
    const val until = "2026-11-19T12:00:20Z"
    fun clock(received: Long = 0, upper: Long = DerivedTestData.serverMillis + 1001) = ObservedResponseClock(upper, received, DerivedTestData.serverMillis)
    fun original(received: Long = 0, fraction: String = "123456789"): RecordingDerivedObservation {
        val source = DerivedTestData.source(DerivedTestData.recording(profile = "adult"))
        val raw = DerivedTestData.raw(source).replace(DerivedTestData.observed, "2026-11-19T12:00:00.${fraction}Z")
            .replace(DerivedTestData.expires, "2026-11-19T13:00:00.${fraction}Z")
        return RecordingDerivedObservation.accept(source, raw, clock(received))
    }
    fun raw(original: RecordingDerivedObservation, state: String = "matching", revision: String = "0", generation: String = "0", position: Long = 650): String {
        val d = original.descriptor
        val mapping = recordingProgressMapping(original, incarnation)
        return buildJsonObject {
            put("schemaVersion", 1)
            putJsonObject("timeline") {
                put("schemaVersion", 1); put("recordingId", d.recordingId); put("state", "known"); put("coordinateSystem", "derived-media")
                put("incarnation", incarnation); put("artifactId", requireNotNull(d.id)); put("mappingRevision", mapping); put("durationMs", requireNotNull(d.durationMs))
                putJsonArray("spans") { d.partOffsets.forEach { p -> add(buildJsonObject { put("partIndex", p.partIndex); put("startMs", p.startMs); put("durationMs", p.durationMs) }) } }
                put("sourceStatus", d.sourceStatus); put("sourceIncomplete", d.sourceIncomplete); put("gapsOmitted", d.gapsOmitted)
                put("observedAt", requireNotNull(d.observedAt)); put("expiresAt", requireNotNull(d.expiresAt))
            }
            putJsonObject("progress") {
                put("validUntil", until); put("recordingId", d.recordingId); put("incarnation", incarnation); put("artifactId", requireNotNull(d.id)); put("mappingSha256", mapping)
                put("durationMs", requireNotNull(d.durationMs)); put("revision", revision); put("writerGeneration", generation); put("positionState", state)
                if (state == "matching") { put("positionMs", position); put("atDerivedEnd", position == d.durationMs) }
            }
        }.toString()
    }
    fun snapshot(original: RecordingDerivedObservation = original(), state: String = "matching", revision: String = "0", generation: String = "0",
        clock: ObservedResponseClock = clock(), now: Long = 0) = RecordingProgressSnapshotObservation.accept(original, raw(original, state, revision, generation), clock, 1L, now)
    fun result(command: RecordingProgressCommand): String = buildJsonObject {
        put("schemaVersion", 1)
        putJsonObject("result") {
            put("validUntil", until); put("recordingId", command.identity.recordingId); put("incarnation", command.identity.incarnation)
            put("artifactId", command.identity.artifactId); put("mappingSha256", command.identity.mappingSha256); put("durationMs", command.identity.durationMs)
            put("positionMs", command.expectedResultPositionMs); put("atDerivedEnd", command.expectedResultPositionMs == command.identity.durationMs)
            put("revision", Math.addExact(command.revision.value, 1L).toString())
            put("writerGeneration", (if (command.action == "touch") command.writerGeneration.value else Math.addExact(command.writerGeneration.value, 1L)).toString())
            put("operationId", operation)
        }
    }.toString()
    fun replace(raw: String, objectName: String, key: String, value: JsonElement?): String {
        val root = ApiJson.parseToJsonElement(raw).jsonObject.toMutableMap()
        val obj = root.getValue(objectName).jsonObject.toMutableMap()
        if (value == null) obj.remove(key) else obj[key] = value
        root[objectName] = JsonObject(obj)
        return JsonObject(root).toString()
    }
}

class RecordingProgressContractTest {
    @Test fun exact_known_snapshot_and_all_three_position_states() {
        val original = ProgressTestData.original()
        for (state in listOf("none", "matching", "conflict")) {
            val value = ProgressTestData.snapshot(original, state)
            assertEquals(state, value.data.positionState); assertEquals("0", value.data.revision.wire)
            assertEquals(if (state == "matching") 650L else null, value.data.positionMs)
            assertEquals(if (state == "matching") false else null, value.data.atDerivedEnd)
            assertTrue(value.adoptable(0)); assertEquals(original.descriptor.id, value.data.identity.artifactId)
            val fromStart = RecordingProgressCommand.fromStart(value)
            assertEquals("0", fromStart.body().getValue("expectedRevision").jsonPrimitive.content)
            assertEquals(0L, fromStart.positionMs)
            if (state == "matching") assertEquals(650L, RecordingProgressCommand.resume(value).expectedResultPositionMs)
            else assertTrue(runCatching { RecordingProgressCommand.resume(value) }.isFailure)
        }
        val end = RecordingProgressSnapshotObservation.accept(original, ProgressTestData.raw(original, position = 2000), ProgressTestData.clock(), 1, 0)
        assertEquals(true, end.data.atDerivedEnd)
    }
    @Test fun canonical_int64_strings_preserve_max_and_reject_noncanonical_forms() {
        val original = ProgressTestData.original(); val raw = ProgressTestData.raw(original)
        for (number in listOf("0", "1", "9007199254740993", Long.MAX_VALUE.toString())) {
            val value = ProgressTestData.snapshot(original, revision = number, generation = number)
            assertEquals(number, value.data.revision.wire); assertEquals(number, value.data.writerGeneration.wire)
        }
        for (bad in listOf("", "00", "01", "+1", "-0", "-1", " 1", "1 ", "1.0", "1e3", "9223372036854775808")) {
            assertTrue(runCatching { RecordingProgressSnapshotObservation.accept(original, ProgressTestData.replace(raw, "progress", "revision", JsonPrimitive(bad)), ProgressTestData.clock(), 1, 0) }.isFailure)
        }
        for (bad in listOf(JsonPrimitive(1L), JsonPrimitive(1.0), JsonNull, JsonPrimitive(true))) {
            assertTrue(runCatching { RecordingProgressSnapshotObservation.accept(original, ProgressTestData.replace(raw, "progress", "writerGeneration", bad), ProgressTestData.clock(), 1, 0) }.isFailure)
        }
    }
    @Test fun duplicate_missing_unknown_case_and_null_fields_fail_closed() {
        val original = ProgressTestData.original(); val raw = ProgressTestData.raw(original)
        val invalid = listOf(raw.replace("\"revision\":\"0\"", "\"revision\":\"0\",\"revi\\u0073ion\":\"0\""),
            ProgressTestData.replace(raw, "progress", "revision", null), ProgressTestData.replace(raw, "progress", "revision", JsonNull),
            ProgressTestData.replace(raw, "progress", "Revision", JsonPrimitive("0")), ProgressTestData.replace(raw, "timeline", "reason", JsonPrimitive("unknown")),
            raw.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""))
        invalid.forEach { assertTrue(runCatching { RecordingProgressSnapshotObservation.accept(original, it, ProgressTestData.clock(), 1, 0) }.isFailure) }
        for (state in listOf("none", "conflict")) {
            assertTrue(runCatching { RecordingProgressSnapshotObservation.accept(original, ProgressTestData.replace(ProgressTestData.raw(original, state), "progress", "positionMs", JsonPrimitive(0)), ProgressTestData.clock(), 1, 0) }.isFailure)
        }
    }
    @Test fun exact_twelve_tuple_digest_preserves_nanos_and_original_descriptor() {
        // Independently computed compact12-element Go-shaped JSON/SHA256 vectors, not runtime Go fixtures.
        val original = ProgressTestData.original()
        assertEquals("abdda7130381319bec5035485fbab4f4bff228dbf80eb0827cfa6df522d91680", recordingProgressMapping(original, ProgressTestData.incarnation))
        assertEquals("618fd5a2f7ce2369326c0978d8ff79fa55b89f5d634732e93c86e9a69640d40b", recordingProgressMapping(ProgressTestData.original(fraction = "1234"), ProgressTestData.incarnation))
        val raw = ProgressTestData.raw(original)
        for (text in listOf("2026-11-19T12:00:00.123Z", "2026-11-19T12:00:00.1234567890Z", "2026-11-19T12:00:00.123456789+00:00")) {
            assertTrue(runCatching { RecordingProgressSnapshotObservation.accept(original, ProgressTestData.replace(raw, "timeline", "observedAt", JsonPrimitive(text)), ProgressTestData.clock(), 1, 0) }.isFailure)
        }
    }
    @Test fun timeline_span_flags_artifact_and_incarnation_mismatches_reject() {
        val original = ProgressTestData.original(); val raw = ProgressTestData.raw(original)
        for ((key, value) in listOf("artifactId" to JsonPrimitive("b".repeat(32)), "incarnation" to JsonPrimitive("00000000-0000-0000-0000-000000000000"),
            "mappingRevision" to JsonPrimitive("b".repeat(64)), "durationMs" to JsonPrimitive(2001L), "sourceIncomplete" to JsonPrimitive(true), "coordinateSystem" to JsonPrimitive("capture-clock"), "state" to JsonPrimitive("unknown"))) {
            assertTrue(runCatching { RecordingProgressSnapshotObservation.accept(original, ProgressTestData.replace(raw, "timeline", key, value), ProgressTestData.clock(), 1, 0) }.isFailure)
        }
        assertTrue(runCatching { RecordingProgressSnapshotObservation.accept(original, raw.replace("\"startMs\":1000", "\"startMs\":999"), ProgressTestData.clock(), 1, 0) }.isFailure)
        assertTrue(runCatching { RecordingProgressSnapshotObservation.accept(original, ProgressTestData.replace(raw, "progress", "durationMs", JsonPrimitive(2001L)), ProgressTestData.clock(), 1, 0) }.isFailure)
    }
    @Test fun write_results_require_exact_action_cas_and_position() {
        val snapshot = ProgressTestData.snapshot()
        for (command in listOf(RecordingProgressCommand.fromStart(snapshot), RecordingProgressCommand.resume(snapshot))) {
            val result = RecordingProgressWriteObservation.accept(command, ProgressTestData.result(command), ProgressTestData.clock(), 0)
            assertEquals("1", result.data.revision.wire); assertEquals("1", result.data.writerGeneration.wire)
            val touch = RecordingProgressCommand.touch(result, 2000)
            val next = RecordingProgressWriteObservation.accept(touch, ProgressTestData.result(touch), ProgressTestData.clock(), 0)
            assertEquals("2", next.data.revision.wire); assertEquals("1", next.data.writerGeneration.wire); assertTrue(next.data.atDerivedEnd)
            for ((key, value) in listOf("revision" to JsonPrimitive("3"), "writerGeneration" to JsonPrimitive("3"), "positionMs" to JsonPrimitive(1L), "atDerivedEnd" to JsonPrimitive(true), "operationId" to JsonPrimitive("00000000-0000-0000-0000-000000000000"))) {
                assertTrue(runCatching { RecordingProgressWriteObservation.accept(command, ProgressTestData.replace(ProgressTestData.result(command), "result", key, value), ProgressTestData.clock(), 0) }.isFailure)
            }
        }
        val max = RecordingProgressCommand.fromStart(ProgressTestData.snapshot(revision = (Long.MAX_VALUE - 1).toString()))
        assertEquals(Long.MAX_VALUE, RecordingProgressWriteObservation.accept(max, ProgressTestData.result(max), ProgressTestData.clock(), 0).data.revision.value)
    }
    @Test fun receipt_deadline_minimum_and_delayed_parse_do_not_renew() {
        val original = ProgressTestData.original(); val raw = ProgressTestData.raw(original)
        val first = RecordingProgressSnapshotObservation.accept(original, raw, ProgressTestData.clock(), 1, 0)
        assertTrue(first.deadlineNanos < requireNotNull(original.deadlineNanos)); assertTrue(first.adoptable(first.deadlineNanos - 1)); assertFalse(first.adoptable(first.deadlineNanos))
        assertTrue(runCatching { RecordingProgressSnapshotObservation.accept(original, raw, ProgressTestData.clock(), 1, first.deadlineNanos) }.isFailure)
        val tooEarlyReceipt = ProgressTestData.clock(0, DerivedTestData.serverMillis)
        assertTrue(tooEarlyReceipt.upperMillis(1_000_000_000L) > Instant.parse(ProgressTestData.observed).toEpochMilli())
        assertTrue(runCatching { RecordingProgressSnapshotObservation.accept(original, raw, tooEarlyReceipt, 1, 1_000_000_000L) }.isFailure)
        val truncatedReceipt = ProgressTestData.clock(0, DerivedTestData.serverMillis + 123L)
        assertTrue(runCatching { RecordingProgressSnapshotObservation.accept(original, raw, truncatedReceipt, 1, 0) }.isFailure)
    }
    @Test fun expired_reply_is_terminal_while_position_remains_data() {
        val original = ProgressTestData.original(); val value = ProgressTestData.snapshot(original)
        assertFalse(value.adoptable(value.deadlineNanos + 1)); assertEquals(650L, value.data.positionMs)
        assertEquals("0", value.data.revision.wire)
        val expired = ProgressTestData.replace(ProgressTestData.raw(original), "progress", "validUntil", JsonPrimitive(DerivedTestData.observed))
        assertTrue(runCatching { RecordingProgressSnapshotObservation.accept(original, expired, ProgressTestData.clock(), 1, 0) }.isFailure)
        val noncanonical = ProgressTestData.replace(ProgressTestData.raw(original), "progress", "validUntil", JsonPrimitive("2026-11-19T12:00:20.000Z"))
        assertTrue(runCatching { RecordingProgressSnapshotObservation.accept(original, noncanonical, ProgressTestData.clock(), 1, 0) }.isFailure)
    }
    @Test fun finite_parser_limits_and_trailing_content_reject() {
        val original = ProgressTestData.original(); val raw = ProgressTestData.raw(original)
        for (bad in listOf(" ".repeat(65_537), raw + " {}", raw.replace("\"revision\":\"0\"", "\"revision\":\"" + "0".repeat(513) + "\""), raw.replace("\"revision\":\"0\"", "\"revision\":null"),
            raw.replace("\"spans\":[", "\"spans\":[[[[[["))) {
            assertTrue(runCatching { RecordingProgressSnapshotObservation.accept(original, bad, ProgressTestData.clock(), 1, 0) }.isFailure)
        }
    }
}
