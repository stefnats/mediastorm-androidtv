package com.strmr.tv.live

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal object PartTimingFixture {
    fun bytes(name: String) = requireNotNull(javaClass.getResourceAsStream("/recording-part-timing-go/$name.json")).use { it.readBytes() }
    fun raw(name: String) = String(bytes(name))
    fun reply(name: String = "container-estimate") = decodeRecordingPartTiming(raw(name), "recording-one", 1)
}
class RecordingPartTimingContractTest {
    @Test fun `five actual Go replies match original producer hashes`() {
        val hashes = mapOf(
            "container-estimate.json" to "b48b184a65c0877fa122906dfa0008f44b9c0d1a54bff269ece5d17003c9bcd8",
            "invalid-index.json" to "64c720b3a6eb803f53028ad5826460a4848be8329be846b2413e3786e4e1f829",
            "ledger-changed.json" to "5b0a57c9dca8239d412b3897c059d97db6b036a34204d1617ddee88ad3cc14a8",
            "passive-same-observation.json" to "b48b184a65c0877fa122906dfa0008f44b9c0d1a54bff269ece5d17003c9bcd8",
            "unknown.json" to "0a20b70d535dd45da60c04c0c71f621df7f4b88afd27a6c2ee728bbae2d23f6b"
        )
        for ((name, expected) in hashes) assertEquals(expected, MessageDigest.getInstance("SHA-256").digest(PartTimingFixture.bytes(name.removeSuffix(".json"))).joinToString("") { "%02x".format(it) })
        assertEquals(5, ApiJson.parseToJsonElement(PartTimingFixture.raw("manifest")).jsonObject.getValue("count").jsonPrimitive.int)
    }
    @Test fun `actual estimate remains historical at any client wall clock and passive read renews nothing`() {
        val a = PartTimingFixture.reply(); val b = PartTimingFixture.reply("passive-same-observation")
        assertEquals(a, b); assertEquals(2000L, a.containerDurationMs); assertEquals(83472L, a.observedBytes)
        val labels = a.label() to a.observationLabel()
        val root = ApiJson.parseToJsonElement(PartTimingFixture.raw("container-estimate")).jsonObject
        for (skew in listOf(-1L, 0L, 1L)) {
            // Shift the server observation relative to the unchanged client clock.
            val observed = java.time.OffsetDateTime.parse(a.observedAt).plusHours(skew).toString()
            val expires = java.time.OffsetDateTime.parse(a.expiresAt).plusHours(skew).toString()
            val shifted = decodeRecordingPartTiming(JsonObject(root + mapOf("observedAt" to JsonPrimitive(observed), "expiresAt" to JsonPrimitive(expires))).toString(), "recording-one", 1)
            assertEquals(labels.first, shifted.label())
            assertTrue(shifted.observationLabel()!!.contains(observed))
            assertTrue(shifted.observationLabel()!!.contains(expires))
        }
        assertTrue(a.observationLabel()!!.contains(a.observedAt!!)); assertTrue(a.observationLabel()!!.contains(a.expiresAt!!))
    }
    @Test fun `unknown and ledger changed never acquire duration or renewal claims`() {
        for (name in listOf("unknown", "ledger-changed")) {
            val value = PartTimingFixture.reply(name)
            assertEquals("unknown", value.state); assertNull(value.containerDurationMs); assertNull(value.observationLabel())
            assertEquals("No stored duration estimate.", value.label())
        }
    }
    @Test fun `strict identity numeric types state and observation interval reject whole malformed response`() {
        val good = ApiJson.parseToJsonElement(PartTimingFixture.raw("container-estimate")).jsonObject
        fun rejected(value: String) = assertTrue(runCatching { decodeRecordingPartTiming(value, "recording-one", 1) }.isFailure)
        for (key in listOf("recordingId", "partIndex", "state", "containerDurationMs", "observedAt", "expiresAt", "binding", "observedBytes")) rejected(JsonObject(good - key).toString())
        for ((key,value) in listOf("recordingId" to JsonPrimitive("other"), "partIndex" to JsonPrimitive("1"), "state" to JsonPrimitive("exact"), "containerDurationMs" to JsonPrimitive(0), "observedBytes" to JsonPrimitive(-1), "expiresAt" to good.getValue("observedAt"), "observedAt" to JsonPrimitive("invalid"))) rejected(JsonObject(good + (key to value)).toString())
        rejected(PartTimingFixture.raw("invalid-index"))
        val unknown = ApiJson.parseToJsonElement(PartTimingFixture.raw("unknown")).jsonObject
        rejected(JsonObject(unknown + ("containerDurationMs" to JsonPrimitive(2000))).toString())
    }
    @Test fun `production builders export explicit passive GET and empty POST with captured selector`() {
        val get = recordingTimingRequest("recording-one",1,"profile-a",false)
        val post = recordingTimingRequest("recording-one",1,"profile-a",true)
        assertEquals("GET",get.method); assertEquals("POST",post.method)
        assertEquals(get.path + "/refresh",post.path); assertEquals(mapOf("profileId" to "profile-a"),post.query)
        for (index in listOf(0,257)) assertTrue(runCatching { recordingTimingRequest("recording-one",index,"p",true) }.isFailure)
        val exports = buildJsonObject { put("passive", get.path + "?profileId=profile-a"); put("inspect", post.path + "?profileId=profile-a") }
        System.getProperty("sourceFilterContractsDir")?.let { dir -> java.io.File(dir).apply { mkdirs() }.resolve("native-recording-timing-queries.json").writeText(exports.toString()) }
    }
    @Test fun `actual stale timing API stops before query and Android transport`() = runBlocking {
        val original = ProfileDataContext.current; ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        for (inspect in listOf(false,true)) assertTrue(runCatching { ApiClient.recordingPartTiming("recording-one",1,inspect,original) }.exceptionOrNull() is CancellationException)
    }
}
