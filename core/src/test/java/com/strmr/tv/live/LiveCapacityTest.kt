package com.strmr.tv.live

import com.strmr.tv.api.ApiError
import com.strmr.tv.api.HttpFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveCapacityTest {
    private val token = "a".repeat(48)
    private fun challenge(tokenJson: String = "\"$token\"", count: String = "1", expires: String = "30", status: Int = 409, code: String = "LIVE_RECORDING_CONFIRMATION_REQUIRED") =
        ApiError.Http(HttpFailure(status, """{"code":"$code","confirmationToken":$tokenJson,"recordingCount":$count,"expiresInSeconds":$expires}"""))

    @Test fun `only a complete bounded recording challenge permits a prompt`() {
        assertEquals(RecordingChallenge(token, 1, 30), recordingChallenge(challenge()))
        for (error in listOf(challenge(tokenJson = "{}"), challenge(tokenJson = "\"short\""), challenge(count = "0"),
                challenge(count = "{}"), challenge(expires = "31"), challenge(expires = "0"), challenge(status = 403),
                challenge(code = "LIVE_STREAM_LIMIT_REACHED"), ApiError.Http(HttpFailure(409, "{}")))) {
            assertNull(recordingChallenge(error))
        }
    }

    @Test fun `explicit confirmation retries the exact attempt once and retunes retain its nonce`() = runTest {
        val attempt = LivePlaybackAttempt(requireCurrent = {})
        val seen = mutableListOf<Pair<String, String?>>()
        var prompts = 0
        suspend fun tune(id: String, confirmation: String?, automaticRetry: Boolean): String {
            assertEquals(seen.size >= 2, automaticRetry)
            seen += id to confirmation
            if (seen.size == 1) throw challenge()
            return "synthetic-stream"
        }
        assertEquals("synthetic-stream", attempt.open({ prompts++; true }, ::tune))
        assertEquals("synthetic-stream", attempt.open({ fail("Retune prompted again"); false }, ::tune))
        assertEquals(1, prompts)
        assertEquals(listOf(attempt.id to null, attempt.id to token, attempt.id to null), seen)
        assertNotEquals(attempt.id, LivePlaybackAttempt(requireCurrent = {}).id)
    }

    @Test fun `cancel never sends confirmation or another tune`() = runTest {
        var calls = 0
        try {
            LivePlaybackAttempt(requireCurrent = {}).open({ false }) { _, _, _ -> calls++; throw challenge() }
            fail("Cancelled tune completed")
        } catch (_: CancellationException) { }
        assertEquals(1, calls)
    }

    @Test fun `context change while confirming never submits the token`() = runTest {
        var current = true
        var calls = 0
        val attempt = LivePlaybackAttempt(requireCurrent = { if (!current) throw CancellationException("Synthetic context changed") })
        try {
            attempt.open({ current = false; true }) { _, _, _ -> calls++; throw challenge() }
            fail("Old context continued")
        } catch (_: CancellationException) { }
        assertEquals(1, calls)
    }

    @Test fun `an admitted attempt never prompts on an automatic retune`() = runTest {
        val attempt = LivePlaybackAttempt(requireCurrent = {})
        attempt.open({ fail("Unexpected prompt"); false }) { _, _, _ -> "synthetic-stream" }
        try {
            attempt.open({ fail("Automatic retune prompted"); true }) { _, _, _ -> throw challenge() }
            fail("Automatic interruption succeeded")
        } catch (error: ApiError) { assertEquals("LIVE_RECORDING_CONFIRMATION_REQUIRED", error.code) }
    }

    @Test fun `a rejected confirmed request does not open another prompt`() = runTest {
        var calls = 0
        var prompts = 0
        try {
            LivePlaybackAttempt(requireCurrent = {}).open({ prompts++; true }) { _, _, _ -> calls++; throw challenge() }
            fail("Repeated challenge accepted")
        } catch (_: ApiError) { }
        assertEquals(2, calls)
        assertEquals(1, prompts)
    }

    @Test fun `non-recording refusals preserve their error and never prompt`() = runTest {
        val original = ApiError.Http(HttpFailure(429, """{"code":"LIVE_STREAM_LIMIT_REACHED"}"""))
        try {
            LivePlaybackAttempt(requireCurrent = {}).open({ fail("Capacity refusal prompted"); true }) { _, _, _ -> throw original }
            fail("Refusal swallowed")
        } catch (error: ApiError) { assertSame(original, error) }
    }

    @Test fun `prompt dismiss preserves the recording and clears consent state`() = runTest {
        val context = MutableStateFlow(1L)
        val consent = RecordingInterruptionConsent(context)
        val waiting = async { consent.ask(RecordingChallenge(token, 2, 30), 1) }
        runCurrent()
        val prompt = consent.prompt.value!!
        assertEquals(2, prompt.recordingCount)
        assertTrue(prompt.message.contains("incomplete"))
        consent.answer(prompt, false)
        assertFalse(waiting.await())
        assertNull(consent.prompt.value)
    }

    @Test fun `stale dialog actions cannot approve a newer recording prompt`() = runTest {
        val consent = RecordingInterruptionConsent(MutableStateFlow(1L))
        val first = async { consent.ask(RecordingChallenge(token, 1, 30), 1) }; runCurrent()
        val old = consent.prompt.value!!
        consent.answer(old, false); first.await()
        val second = async { consent.ask(RecordingChallenge(token, 1, 30), 1) }; runCurrent()
        consent.answer(old, true); runCurrent()
        assertFalse(second.isCompleted)
        consent.answer(consent.prompt.value!!, true)
        assertTrue(second.await())
    }

    @Test fun `profile change removes a pending prompt and cancels its tune`() = runTest {
        val context = MutableStateFlow(1L)
        val consent = RecordingInterruptionConsent(context)
        val waiting = async { consent.ask(RecordingChallenge(token, 1, 30), 1) }; runCurrent()
        context.value = 2; runCurrent()
        try { waiting.await(); fail("Old context survived") } catch (_: CancellationException) { }
        assertNull(consent.prompt.value)
    }

    @Test fun `closing the playback request also removes its confirmation`() = runTest {
        val consent = RecordingInterruptionConsent(MutableStateFlow(1L))
        val waiting = async { consent.ask(RecordingChallenge(token, 1, 30), 1) }; runCurrent()
        waiting.cancel(); runCurrent()
        assertNull(consent.prompt.value)
    }

    @Test fun `expired confirmation disappears without approving`() = runTest {
        val consent = RecordingInterruptionConsent(MutableStateFlow(1L))
        val waiting = async {
            try { consent.ask(RecordingChallenge(token, 1, 1), 1); null } catch (error: ApiError) { error }
        }
        runCurrent(); advanceTimeBy(1_001); runCurrent()
        assertNotNull(waiting.await())
        assertNull(consent.prompt.value)
    }

    @Test fun `capacity revocation is terminal through nested player failures`() {
        val revoked = ApiError.Http(HttpFailure(410, """{"code":"LIVE_STREAM_TAKEN_OVER"}"""))
        assertNotNull(liveCapacityRejection(IllegalStateException("synthetic player", revoked)))
        assertNotNull(liveCapacityRejection(challenge()))
        assertNull(liveCapacityRejection(ApiError.Http(HttpFailure(503, "provider unavailable"))))
        assertNull(liveCapacityRejection(IllegalStateException("synthetic network failure")))
    }
}
