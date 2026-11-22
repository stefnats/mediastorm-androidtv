package com.strmr.tv.live

import com.strmr.tv.api.ApiJson
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KidsLivePlaybackTest {
    private val programme = KidsLiveProgramme("Synthetic programme", "2026-11-22T09:00:00Z", "2026-11-22T09:30:00Z", 6, "DE",
        KidsLiveGuide("guide-choice", "xmltv", "Parent-selected guide", "2026-11-22T08:00:00Z"), "selected_guide_claim")
    private fun reply(remaining: Long = 1_000) = KidsLiveStartReply("synthetic-session", "/video/hls/synthetic-session/stream.m3u8",
        programme.endsAt, remaining, programme)

    @Test fun childStartUsesExactCatalogIdWithoutProviderOrTimeshiftParameters() = runTest {
        val query = kidsLiveStartQuery("child", "catalog:exact-id", "device")
        assertEquals(mapOf("profileId" to "child", "channelId" to "catalog:exact-id", "clientId" to "device", "target" to "native"), query)
        var calls = 0
        val stream = openKidsLive(query, { 0 }, { received -> calls++; assertEquals(query, received); reply() },
            mediaUrl = { "http://fixture/api$it" }, stop = { fail("Valid session stopped") })
        assertEquals(1, calls)
        assertEquals("synthetic-session", stream.sessionId)
        assertFalse(stream.timeshift)
        assertNull(stream.stremioStreamIndexes)
        assertNotNull(stream.programmeLimit)
        assertTrue(stream.programmeLimit!!.programme.classificationLabel.contains("according to Parent-selected guide"))
    }

    @Test fun responseLatencyConsumesOriginalMonotonicTimeAndStopsWithoutPlaybackTicks() = runTest {
        val stream = openKidsLive(kidsLiveStartQuery("child", "channel", "device"), { testScheduler.currentTime },
            fetch = { delay(400); reply() }, mediaUrl = { it }, stop = { fail("Valid session stopped") })
        val limit = stream.programmeLimit!!
        assertEquals(600L, limit.remainingMillis(testScheduler.currentTime))
        val actions = mutableListOf<String>()
        val clock = KidsProgrammeClock(limit, { testScheduler.currentTime }) {
            actions += listOf("pause", "stop", "clear buffered media")
        }
        val timer = launch { clock.watch() }
        runCurrent()
        // No activity position tick, play/pause change or keepalive is needed for this timer.
        advanceTimeBy(599); runCurrent(); assertTrue(actions.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("pause", "stop", "clear buffered media"), actions)
        assertFalse(clock.active())
        assertEquals(3, actions.size)
        timer.join()
    }

    @Test fun keepaliveWithLaterProgrammeFieldsCannotRenewTheOriginalDeadline() = runTest {
        var elapsed = 0L
        val stream = openKidsLive(emptyMap(), { elapsed }, { reply() }, { it }, { fail() })
        var stops = 0
        val clock = KidsProgrammeClock(stream.programmeLimit!!, { elapsed }) { stops++ }
        elapsed = 900
        val growing = readHlsKeepalive(ProfileDataContext.current, capacity = true, strict = true) {
            """{"growing":false,"programmeEndsAt":"2026-11-22T12:00:00Z","programmeRemainingMillis":9999999}"""
        }
        assertEquals(false, growing)
        assertTrue(clock.active())
        elapsed = 1_000
        assertFalse(clock.active())
        assertEquals(1, stops)
    }

    @Test fun wakeAfterDeadlineStopsImmediatelyEvenIfTheTimerWasNotDispatched() = runTest {
        var elapsed = 0L
        val stream = openKidsLive(emptyMap(), { elapsed }, { reply() }, { it }, { fail() })
        var stops = 0
        val clock = KidsProgrammeClock(stream.programmeLimit!!, { elapsed }) { stops++ }
        assertTrue(clock.active())
        elapsed = 60_000 // elapsedRealtime includes sleep; no wall-clock value participates.
        assertFalse(clock.active())
        assertFalse(clock.active())
        assertEquals(1, stops)
    }

    @Test fun invalidOrAlreadyExpiredStartStopsItsSessionWithoutOpeningAnyFallback() = runTest {
        for (bad in listOf(reply().copy(isDirect = true), reply().copy(timeshiftWindowSeconds = 60),
            reply(0), reply().copy(programmeEndsAt = "invalid"), reply().copy(programmeEndsAt = "2026-11-22T09:31:00Z"))) {
            var urls = 0
            var requests = 0
            val stopped = mutableListOf<String>()
            try {
                openKidsLive(emptyMap(), { 0 }, { requests++; bad }, { urls++; it }, { stopped += it })
                fail("Invalid child stream opened")
            } catch (_: Exception) { }
            assertEquals(1, requests)
            assertEquals(0, urls)
            assertEquals(listOf("synthetic-session"), stopped)
        }
        var elapsed = 0L
        val stopped = mutableListOf<String>()
        try {
            openKidsLive(emptyMap(), { elapsed }, { elapsed = 2_000; reply() }, { fail("Expired stream opened"); it }, { stopped += it })
            fail("Expired reply accepted")
        } catch (_: Exception) { }
        assertEquals(listOf("synthetic-session"), stopped)
    }

    @Test fun startResponseDecodesTheExactProgrammeAndGuideWireFields() {
        val parsed = ApiJson.decodeFromString<KidsLiveStartReply>("""{"sessionId":"s","playlistUrl":"/video/hls/s/stream.m3u8","isLive":true,"hasClosedCaptions":false,"timeshiftWindowSeconds":0,"programmeEndsAt":"2026-11-22T09:30:00Z","programmeRemainingMillis":1234,"programme":{"title":"Synthetic programme","startsAt":"2026-11-22T09:00:00Z","endsAt":"2026-11-22T09:30:00Z","age":6,"country":"DE","guide":{"id":"guide-choice","type":"xmltv","name":"Parent-selected guide","observedAt":"2026-11-22T08:00:00Z"},"classificationBasis":"selected_guide_claim"}}""")
        assertEquals(programme, parsed.programme)
        assertEquals(1_234L, KidsProgrammeLimit.from(parsed, 0).remainingMillis(0))
    }
}
