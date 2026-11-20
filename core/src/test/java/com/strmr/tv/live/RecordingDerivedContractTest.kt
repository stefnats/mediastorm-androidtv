package com.strmr.tv.live

import com.strmr.tv.api.ApiJson
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ObservedResponseClock
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic-only unit inputs. Separately exported actual Go fixtures are mandatory below. */
internal object DerivedTestData {
    const val observed = "2026-11-19T12:00:00Z"
    const val expires = "2026-11-19T13:00:00Z"
    val serverMillis = Instant.parse(observed).toEpochMilli()
    fun clock(received: Long = 0, upper: Long = serverMillis + 1001) = ObservedResponseClock(upper, received, serverMillis)
    fun recording(id: String = "synthetic-recording", profile: String = ApiClient.profileId, count: Int = 2, incomplete: Boolean = false, gaps: Boolean = false): Recording {
        val start = Instant.parse(observed)
        val parts = (1..count).map { n ->
            val begin = start.plusSeconds(n - 1L + (if (gaps && n > 1) 5L else 0L))
            RecordingPart(n, begin.toString(), begin.plusSeconds(1).toString(), 1024, "source_ended", "recording:$id/parts/$n")
        }
        return Recording(id, "completed", title = "Synthetic recording", capture = RecordingCapture(1, "finished", incomplete, 0, parts,
            if (gaps) listOf(RecordingGap(start.plusSeconds(1).toString(), start.plusSeconds(6).toString(), "capacity")) else emptyList()),
            userId = profile, updatedAt = observed)
    }
    fun source(r: Recording = recording()) = requireNotNull(RecordingDerivedSource.capture(r, requireNotNull(r.userId)))
    fun raw(source: RecordingDerivedSource, unknown: String? = null, id: String = "a".repeat(32)): String = buildJsonObject {
        val r = source.recording; val c = requireNotNull(r.capture)
        put("recordingId", r.id); put("state", if (unknown == null) "ready" else "unknown")
        put("sourceIncomplete", c.incomplete == true || c.gaps.isNotEmpty()); put("gapsOmitted", unknown == null && c.gaps.isNotEmpty()); put("sourceStatus", r.status)
        putJsonArray("partOffsets") { if (unknown == null) repeat(c.parts.size) { n -> add(buildJsonObject {
            put("partIndex", n + 1); put("startMs", n * 1000L); put("durationMs", 1000L)
        }) } }
        if (unknown != null) put("reason", unknown) else {
            put("id", id); put("durationMs", c.parts.size * 1000L); put("bytes", 2048L)
            put("observedAt", observed); put("expiresAt", expires)
        }
    }.toString()
    fun observation(source: RecordingDerivedSource = source(), raw: String = raw(source), clock: ObservedResponseClock = clock()) = RecordingDerivedObservation.accept(source, raw, clock)
}

class RecordingDerivedContractTest {
    @Test fun `strict current ready and unknown descriptors keep positions distinct from capture clocks`() {
        val source = DerivedTestData.source(DerivedTestData.recording(incomplete = true, gaps = true))
        val ready = decodeRecordingDerived(DerivedTestData.raw(source), source)
        assertEquals(2000L, ready.durationMs); assertEquals(listOf(0L, 1000L), ready.partOffsets.map { it.startMs })
        assertTrue(ready.label().contains("missing content was not restored")); assertTrue(ready.label().contains("not guaranteed"))
        for (reason in listOf("not_prepared", "configuration_changed", "source_changed", "expired")) {
            val value = decodeRecordingDerived(DerivedTestData.raw(source, reason), source)
            assertEquals("unknown", value.state); assertNull(value.id); assertTrue(value.partOffsets.isEmpty())
        }
    }
    @Test fun `duplicate unknown coercion null and trailing fields never become a ready observation`() {
        val source = DerivedTestData.source(); val valid = DerivedTestData.raw(source)
        val invalid = listOf(valid.replace("\"state\":\"ready\"", "\"state\":\"unknown\",\"state\":\"ready\""),
            valid.dropLast(1) + ",\"path\":\"/private/file\"}", valid + " {}", valid.replace("\"bytes\":2048", "\"bytes\":\"2048\""),
            valid.replace("\"bytes\":2048", "\"bytes\":null"), valid.replace("\"bytes\":2048", "\"bytes\":2.048e3"),
            valid.replace("\"partIndex\":1", "\"partIndex\":1,\"partIndex\":1"), valid.replace("\"sourceIncomplete\":false", "\"sourceIncomplete\":\"false\""),
            valid.replace("\"recordingId\":\"synthetic-recording\"", "\"recordingId\":\"other\""))
        invalid.forEach { raw -> assertTrue(runCatching { decodeRecordingDerived(raw, source) }.isFailure) }
        assertTrue(runCatching { decodeRecordingDerived(" ".repeat(65_537), source) }.isFailure)
    }
    @Test fun `offset count order overflow endpoints UTC expiry and incomplete provenance are strict`() {
        val source = DerivedTestData.source(DerivedTestData.recording(incomplete = true, gaps = true)); val valid = DerivedTestData.raw(source)
        for (raw in listOf(valid.replace("\"sourceIncomplete\":true", "\"sourceIncomplete\":false"), valid.replace("\"gapsOmitted\":true", "\"gapsOmitted\":false"),
            valid.replace("\"partIndex\":2", "\"partIndex\":1"), valid.replace("\"startMs\":1000", "\"startMs\":999"),
            valid.replace("\"durationMs\":2000", "\"durationMs\":2001"), valid.replace("\"durationMs\":1000", "\"durationMs\":9223372036854775807"),
            valid.replace(DerivedTestData.expires, "2026-11-20T13:00:00Z"), valid.replace(DerivedTestData.expires, DerivedTestData.observed),
            valid.replace(DerivedTestData.expires, "2026-11-19T13:00:00+00:00"), valid.replace("a".repeat(32), "A".repeat(32)))) {
            assertTrue(runCatching { decodeRecordingDerived(raw, source) }.isFailure)
        }
        val large = DerivedTestData.source(DerivedTestData.recording(count = 256))
        assertEquals(256, decodeRecordingDerived(DerivedTestData.raw(large), large).partOffsets.size)
    }
    @Test fun `later same artifact clock cannot renew original absolute deadline`() {
        val source = DerivedTestData.source(); val first = DerivedTestData.observation(source)
        val later = DerivedTestData.observation(source, clock = DerivedTestData.clock(1_000_000_000L, DerivedTestData.serverMillis + 1001)).constrain(first)
        assertEquals(first.deadlineNanos, later.deadlineNanos); assertTrue(first.sameArtifact(later))
        assertTrue(first.ready(requireNotNull(first.deadlineNanos) - 1)); assertFalse(first.ready(requireNotNull(first.deadlineNanos)))
        val future = DerivedTestData.raw(source).replace(DerivedTestData.observed, "2026-11-19T12:10:00Z")
        assertTrue(runCatching { DerivedTestData.observation(source, future) }.isFailure)
    }
    @Test fun `delayed decoding cannot move the immutable provider observation receipt bound`() {
        val source = DerivedTestData.source()
        val received = System.nanoTime() - 60_000_000_000L
        val clock = DerivedTestData.clock(received, DerivedTestData.serverMillis + 1001)
        val future = DerivedTestData.raw(source).replace(DerivedTestData.observed, "2026-11-19T12:00:30Z")
        val futureMillis = Instant.parse("2026-11-19T12:00:30Z").toEpochMilli()
        assertTrue(clock.upperMillis() > futureMillis)
        assertFalse(clock.observedAtReceipt(futureMillis))
        assertTrue(runCatching { RecordingDerivedObservation.accept(source, future, clock) }.isFailure)
        assertTrue(RecordingDerivedObservation.accept(source, DerivedTestData.raw(source), clock).ready())
    }
    @Test fun `joined eligibility never treats restored unknown foreign or active sources as complete`() {
        val r = DerivedTestData.recording(); val capture = requireNotNull(r.capture)
        for (changed in listOf(r.copy(userId = null), r.copy(userId = "other"), r.copy(updatedAt = null), r.copy(status = "running"),
            r.copy(capture = capture.copy(restored = true)), r.copy(capture = capture.copy(version = 0)),
            r.copy(capture = capture.copy(parts = capture.parts.take(1))), r.copy(capture = capture.copy(parts = capture.parts.map { it.copy(bytes = 0) })))) {
            assertNull(RecordingDerivedSource.capture(changed, requireNotNull(r.userId)))
        }
        val copied = ApiJson.decodeFromString<Recording>(ApiJson.encodeToString(Recording.serializer(), r))
        assertEquals(r, copied); assertNotNull(RecordingDerivedSource.capture(copied, requireNotNull(r.userId)))
    }
    @Test fun `actual separately exported Go descriptors and source DTOs match their exact manifest hashes`() {
        val loader = javaClass.classLoader!!
        fun bytes(name: String) = requireNotNull(loader.getResourceAsStream("recording-derived-go/$name")) { "Actual Go fixtures have not been exported" }.use { it.readBytes() }
        val manifest = ApiJson.parseToJsonElement(bytes("manifest.json").toString(Charsets.UTF_8)).jsonObject
        val hashes = manifest.getValue("files").jsonObject
        hashes.forEach { (name, expected) ->
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes(name)).joinToString("") { "%02x".format(it) }
            assertEquals(expected.jsonPrimitive.content, actual)
        }
        val cases = manifest.getValue("cases").jsonArray; assertTrue(cases.size >= 7)
        cases.forEach { value ->
            val item = value.jsonObject
            val sourceName = item.getValue("source").jsonPrimitive.content
            val descriptorName = item.getValue("descriptor").jsonPrimitive.content
            assertTrue(sourceName in hashes && descriptorName in hashes)
            val recording = ApiJson.decodeFromString<Recording>(bytes(sourceName).toString(Charsets.UTF_8))
            val source = requireNotNull(RecordingDerivedSource.capture(recording, requireNotNull(recording.userId)))
            val descriptor = decodeRecordingDerived(bytes(descriptorName).toString(Charsets.UTF_8), source)
            assertEquals(item.getValue("state").jsonPrimitive.content, descriptor.state)
        }
    }
}
